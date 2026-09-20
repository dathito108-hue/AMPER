package io.amper.neuroos.core

import java.util.concurrent.ConcurrentHashMap

data class AmiDirectPreparedModel(
    val artifact: StoredAmiArtifact,
    val graph: AmiTensorGraph,
    val metadata: AmiGgufMetadataSnapshot,
    val tokenizer: AmiTokenizerLexicon,
    val stackPlan: AmiDecoderStackPlan
)

/**
 * Direct AMI text backend.
 *
 * The installed model remains the user's GGUF identity for Titan capability/routing purposes.
 * Execution is performed only when a verified app-private AMI artifact with matching source lineage
 * already exists. The user-owned GGUF source passed by Titan is never rewritten by this backend.
 */
class AmiDirectStreamingInferenceBackend(
    private val artifactLookup: (InstalledModel) -> StoredAmiArtifact?,
    private val hardwareSnapshot: () -> AmiHardwareSnapshot? = { null },
    private val maxWindowBytes: Int = 8 * 1024 * 1024
) : ManagedInferenceBackend,
    RequestAwareInferenceBackend,
    PromptTokenEstimatingInferenceBackend,
    CancellableStreamingInferenceBackend,
    ConcurrencyLimitedInferenceBackend {

    override val id: String = BACKEND_ID
    override val maxConcurrentExecutions: Int = 1

    private val prepared = ConcurrentHashMap<String, AmiDirectPreparedModel>()

    override fun supports(model: InstalledModel): Boolean {
        if (!model.descriptor.format.equals("gguf", ignoreCase = true)) return false
        return prepareModel(model).isSuccess
    }

    override fun supportsRequest(
        model: InstalledModel,
        request: InferenceRequest
    ): Boolean {
        if (request.attachments.isNotEmpty()) return false
        val runtime = prepareModel(model).getOrNull() ?: return false
        return runCatching {
            val promptTokens = AmiTokenizerEncoder.encode(
                runtime.tokenizer,
                request.prompt
            ).size
            promptTokens > 0 &&
                promptTokens.toLong() + request.maxOutputTokens.toLong() <=
                    runtime.stackPlan.maxContextTokens.toLong()
        }.getOrDefault(false)
    }

    override fun requestRejectionReason(
        model: InstalledModel,
        request: InferenceRequest
    ): String = when {
        request.attachments.isNotEmpty() -> "ami-direct-text-only"
        artifactLookup(model) == null -> "ami-artifact-not-compiled"
        else -> "ami-direct-request-unsupported"
    }

    override fun health(): BackendHealth {
        val nativeAdmission = AmneProcessKernelRuntime.admission()
        val accelerated = nativeAdmission?.admittedPrimitives?.isNotEmpty() == true
        return BackendHealth(
            state = BackendState.READY,
            detail = if (accelerated) {
                "direct AMI decoder · qualified AMNE acceleration"
            } else {
                "direct AMI decoder · reference kernels available"
            },
            hardwareAcceleration = accelerated
        )
    }

    override fun estimate(
        model: InstalledModel,
        request: InferenceRequest
    ): InferenceCost {
        val runtime = prepareModel(model).getOrThrow()
        val hardware = hardwareSnapshot()
        val contextTokens = runtime.stackPlan.maxContextTokens
        val kvBytes = runtime.stackPlan.layers.fold(0L) { total, layer ->
            val perLayer = Math.multiplyExact(
                Math.multiplyExact(
                    layer.attention.kvWidth.toLong(),
                    contextTokens.toLong()
                ),
                2L * Float.SIZE_BYTES.toLong()
            )
            Math.addExact(total, perLayer)
        }
        val kvMb = ((kvBytes + MIB - 1L) / MIB)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val workingMb = 192
        return InferenceCost(
            estimatedMemoryMb = Math.addExact(workingMb, kvMb),
            preferredThreads = hardware?.logicalProcessors
                ?.minus(1)
                ?.coerceIn(1, 6)
                ?: 1,
            contextTokens = contextTokens
        )
    }

    override fun estimatePromptTokens(
        model: InstalledModel,
        request: InferenceRequest
    ): Int {
        val runtime = prepareModel(model).getOrThrow()
        return AmiTokenizerEncoder.encode(
            runtime.tokenizer,
            request.prompt
        ).size.also {
            require(it > 0) { "AMI tokenizer produced an empty prompt" }
        }
    }

    override fun infer(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest
    ): Result<InferenceResponse> =
        inferStream(
            model = model,
            source = source,
            request = request,
            cancellation = InferenceCancellationSignal(),
            onChunk = { }
        )

    override fun inferStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> =
        inferStream(
            model = model,
            source = source,
            request = request,
            cancellation = InferenceCancellationSignal(),
            onChunk = onChunk
        )

    override fun inferStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> = runCatching {
        cancellation.throwIfCancelled()
        require(supportsRequest(model, request)) {
            requestRejectionReason(model, request)
        }

        val runtime = prepareModel(model).getOrThrow()
        val promptStartedNs = System.nanoTime()
        val promptIds = AmiTokenizerEncoder.encode(
            runtime.tokenizer,
            request.prompt
        )
        val promptPrepMs = (System.nanoTime() - promptStartedNs) / 1_000_000L

        val stopIds = buildSet {
            runtime.tokenizer.special.eosTokenId?.let(::add)
        }
        val state = AmiDecoderStackState(
            plan = runtime.stackPlan,
            maxContextTokens = minOf(
                runtime.stackPlan.maxContextTokens,
                promptIds.size + request.maxOutputTokens
            )
        )
        val generatedIds = ArrayList<Int>(request.maxOutputTokens)
        val textEmitter = AmiStableStreamingTextEmitter()
        var chunkIndex = 0
        val generationStartedNs = System.nanoTime()

        val result = AmiAutoregressiveGenerator(maxWindowBytes).generate(
            loaded = runtime.artifact.loaded,
            graph = runtime.graph,
            metadata = runtime.metadata,
            stackPlan = runtime.stackPlan,
            state = state,
            promptTokenIds = promptIds,
            config = AmiGenerationConfig(
                maxNewTokens = request.maxOutputTokens,
                sampling = AmiSamplingConfig(
                    temperature = request.temperature.toFloat()
                ),
                stopTokenIds = stopIds
            ),
            hardware = hardwareSnapshot(),
            cancellation = cancellation,
            onToken = { token ->
                cancellation.throwIfCancelled()
                generatedIds += token.tokenId
                val candidate = AmiDetokenizer.decode(
                    runtime.tokenizer,
                    generatedIds.toIntArray()
                )
                textEmitter.observe(candidate)?.takeIf { it.isNotEmpty() }?.let { stable ->
                    onChunk(
                        InferenceChunk(
                            text = stable,
                            index = chunkIndex++
                        )
                    )
                }
            }
        ).getOrThrow()
        cancellation.throwIfCancelled()

        val finalText = AmiDetokenizer.decode(
            runtime.tokenizer,
            result.generatedTokenIds
        )
        textEmitter.finish(finalText)
            .takeIf { it.isNotEmpty() }
            ?.let { tail ->
                onChunk(
                    InferenceChunk(
                        text = tail,
                        index = chunkIndex++
                    )
                )
            }
        onChunk(
            InferenceChunk(
                text = "",
                index = chunkIndex,
                finished = true
            )
        )

        val generationMs =
            (System.nanoTime() - generationStartedNs) / 1_000_000L
        val outputTokens = result.generatedTokenIds.size
        val tokensPerSecond = if (generationMs > 0L) {
            outputTokens.toDouble() * 1000.0 / generationMs.toDouble()
        } else {
            null
        }

        InferenceResponse(
            modelId = model.descriptor.id,
            backendId = id,
            text = finalText,
            promptTokens = promptIds.size,
            outputTokens = outputTokens,
            sessionReused = false,
            tokensPerSecond = tokensPerSecond,
            promptEvalTimeMs = promptPrepMs,
            generationTimeMs = generationMs
        )
    }

    override fun unload(modelId: ModelId): Result<Unit> = runCatching {
        prepared.entries.removeIf { it.value.artifact.modelId == modelId }
    }

    private fun prepareModel(
        model: InstalledModel
    ): Result<AmiDirectPreparedModel> = runCatching {
        val key = model.descriptor.id.value + ":" + model.sha256
        prepared[key]?.let { return@runCatching it }

        val artifact = requireNotNull(artifactLookup(model)) {
            "verified AMI artifact has not been compiled for this GGUF"
        }
        require(artifact.loaded.index.manifest.source.sourceSha256 == model.sha256) {
            "AMI source lineage does not match installed GGUF"
        }

        val graph = AmiTensorGraphReader()
            .read(artifact.loaded)
            .getOrThrow()
        val metadata = AmiPreservedGgufMetadataReader()
            .read(artifact.loaded)
            .getOrThrow()
        val tokenizer = AmiTokenizerLexiconReader()
            .read(artifact.loaded)
            .getOrThrow()
        val stackPlan = AmiDecoderStackPlanner
            .plan(graph, metadata)
            .getOrThrow()

        require(tokenizer.vocabularySize == stackPlan.vocabularySize) {
            "AMI tokenizer vocabulary differs from decoder vocabulary"
        }

        AmiDirectPreparedModel(
            artifact = artifact,
            graph = graph,
            metadata = metadata,
            tokenizer = tokenizer,
            stackPlan = stackPlan
        ).also { prepared[key] = it }
    }

    companion object {
        const val BACKEND_ID: String = "amne-ami-direct"
        private const val MIB: Long = 1024L * 1024L
    }
}

/**
 * Emits only text proven stable across two consecutive cumulative detokenizations.
 *
 * Byte-fallback tokenizers may temporarily decode an incomplete UTF-8 byte sequence to a replacement
 * character. One-token look-behind prevents that unstable suffix from reaching the user. finish()
 * flushes the authoritative final decode.
 */
class AmiStableStreamingTextEmitter {
    private var previousCandidate: String = ""
    private var emittedLength: Int = 0

    fun observe(candidate: String): String? {
        require(candidate.length >= 0)
        val stableLength = commonPrefixLength(previousCandidate, candidate)
        val output = if (stableLength > emittedLength) {
            previousCandidate.substring(emittedLength, stableLength)
        } else {
            null
        }
        emittedLength = maxOf(emittedLength, stableLength)
        previousCandidate = candidate
        return output
    }

    fun finish(authoritative: String): String {
        require(authoritative.length >= emittedLength) {
            "authoritative AMI text is shorter than already-streamed prefix"
        }
        val emittedPrefix = authoritative.substring(0, emittedLength)
        val previousPrefix = previousCandidate
            .take(emittedLength.coerceAtMost(previousCandidate.length))
        require(
            emittedLength == 0 ||
                previousPrefix == emittedPrefix
        ) {
            "AMI detokenization changed an already-streamed prefix"
        }
        val tail = authoritative.substring(emittedLength)
        previousCandidate = authoritative
        emittedLength = authoritative.length
        return tail
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        val limit = minOf(left.length, right.length)
        var index = 0
        while (index < limit && left[index] == right[index]) index += 1
        return index
    }
}
