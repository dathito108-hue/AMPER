package io.amper.neuroos.core

import io.amper.neuroos.core.v2.Amne2ExecutionSessionFactory
import io.amper.neuroos.core.v2.StoredAmi2Artifact
import java.util.concurrent.ConcurrentHashMap

data class AmiDirectPreparedModel(
    val artifact: StoredAmi2Artifact,
    val graph: AmiTensorGraph,
    val metadata: AmiGgufMetadataSnapshot,
    val tokenizer: AmiTokenizerLexicon,
    val stackPlan: AmiDecoderStackPlan,
    val requiredMatrixPrimitives: Set<AmneKernelPrimitive>
)

/**
 * Production AMPER Core text backend over canonical AMI2 + AMNE2.
 *
 * The installed model remains the user's GGUF import identity for Titan capability/routing
 * purposes. Execution is admitted only from a verified app-private AMI2 artifact with matching
 * source lineage, then runs through one AMNE2 execution session. The GGUF source passed by Titan is
 * import provenance only and is never a runtime backend.
 */
class AmiDirectStreamingInferenceBackend(
    private val artifactLookup: (InstalledModel) -> StoredAmi2Artifact?,
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
    private val sessionFactory = Amne2ExecutionSessionFactory(
        maxWindowBytes = maxWindowBytes
    )

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
            val preparedPrompt = prepareInstructionPrompt(runtime, request)
            val promptTokens = preparedPrompt.tokenIds.size
            val readiness = directReadiness(runtime)
            readiness.ready &&
                promptTokens > 0 &&
                promptTokens.toLong() + request.maxOutputTokens.toLong() <=
                    runtime.stackPlan.maxContextTokens.toLong()
        }.getOrDefault(false)
    }

    override fun requestRejectionReason(
        model: InstalledModel,
        request: InferenceRequest
    ): String {
        if (request.attachments.isNotEmpty()) return "amper-core-text-only"
        if (artifactLookup(model) == null) return "amper-core-ami2-not-compiled"

        val runtime = prepareModel(model).getOrNull()
            ?: return "amper-core-foundation-unsupported"
        val readiness = runCatching { directReadiness(runtime) }.getOrNull()
        if (readiness != null && !readiness.ready) {
            return "amper-core-native-matrix-not-admitted:" +
                readiness.referenceOnlyMatrixPrimitives
                    .sortedBy { it.ordinal }
                    .joinToString(",") { it.name }
        }
        return "amper-core-request-unsupported"
    }

    override fun health(): BackendHealth {
        val nativeAdmission = AmneProcessKernelRuntime.admission()
        val acceleratedMatrices = nativeAdmission?.admittedPrimitives
            ?.any {
                it == AmneKernelPrimitive.MATVEC_F32 ||
                    it == AmneKernelPrimitive.MATVEC_Q4_0 ||
                    it == AmneKernelPrimitive.MATVEC_Q8_0 ||
                    it == AmneKernelPrimitive.MATVEC_Q4_K ||
                    it == AmneKernelPrimitive.MATVEC_Q5_K ||
                    it == AmneKernelPrimitive.MATVEC_Q6_K
            } == true
        return BackendHealth(
            state = if (acceleratedMatrices) BackendState.READY else BackendState.DEGRADED,
            detail = if (acceleratedMatrices) {
                "AMPER Core · native matrix admission active"
            } else {
                "AMPER Core · native matrix admission pending; no foreign fallback runtime"
            },
            hardwareAcceleration = acceleratedMatrices
        )
    }

    override fun estimate(
        model: InstalledModel,
        request: InferenceRequest
    ): InferenceCost {
        val runtime = prepareModel(model).getOrThrow()
        val hardware = hardwareSnapshot()
        val promptTokens = prepareInstructionPrompt(runtime, request)
            .tokenIds
            .size
            .also {
                require(it > 0) { "AMPER Core instruction prompt produced no tokens" }
            }
        val memory = AmiRequestMemoryEstimator.estimate(
            plan = runtime.stackPlan,
            promptTokens = promptTokens,
            requestedOutputTokens = request.maxOutputTokens,
            maxWindowBytes = maxWindowBytes
        )
        return InferenceCost(
            estimatedMemoryMb = memory.estimatedMemoryMb,
            preferredThreads = hardware?.logicalProcessors
                ?.minus(1)
                ?.coerceIn(1, 6)
                ?: 1,
            contextTokens = runtime.stackPlan.maxContextTokens
        )
    }

    override fun estimatePromptTokens(
        model: InstalledModel,
        request: InferenceRequest
    ): Int {
        val runtime = prepareModel(model).getOrThrow()
        return prepareInstructionPrompt(runtime, request)
            .tokenIds
            .size
            .also {
                require(it > 0) { "AMPER Core instruction prompt produced no tokens" }
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
        val hardware = requireNotNull(hardwareSnapshot()) {
            "AMNE2 production execution requires an Android hardware snapshot"
        }
        val promptStartedNs = System.nanoTime()
        val preparedPrompt = prepareInstructionPrompt(runtime, request)
        val promptIds = preparedPrompt.tokenIds
        val promptPrepMs = (System.nanoTime() - promptStartedNs) / 1_000_000L
        val stopIds = preparedPrompt.stopTokenIds
        val generatedIds = ArrayList<Int>(request.maxOutputTokens)
        val textEmitter = AmiStableStreamingTextEmitter()
        var chunkIndex = 0
        val generationStartedNs = System.nanoTime()
        val session = sessionFactory.open(
            artifactFile = runtime.artifact.file,
            hardware = hardware,
            maxContextTokens = minOf(
                runtime.stackPlan.maxContextTokens,
                promptIds.size + request.maxOutputTokens
            )
        ).getOrThrow()

        val result = try {
            session.generate(
                promptTokenIds = promptIds,
                config = AmiGenerationConfig(
                    maxNewTokens = request.maxOutputTokens,
                    sampling = AmiSamplingConfig(
                        temperature = request.temperature.toFloat()
                    ),
                    stopTokenIds = stopIds
                ),
                cancellation = cancellation,
                onToken = { token ->
                    cancellation.throwIfCancelled()
                    if (token.tokenId !in stopIds) {
                        generatedIds += token.tokenId
                        val candidate = AmiDetokenizer.decode(
                            runtime.tokenizer,
                            generatedIds.toIntArray()
                        )
                        textEmitter.observe(candidate)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { stable ->
                                onChunk(
                                    InferenceChunk(
                                        text = stable,
                                        index = chunkIndex++
                                    )
                                )
                            }
                    }
                }
            ).getOrThrow()
        } finally {
            session.close()
        }
        cancellation.throwIfCancelled()

        val finalGeneratedIds = result.generatedTokenIds
            .takeWhile { it !in stopIds }
            .toIntArray()
        val finalText = AmiDetokenizer.decode(
            runtime.tokenizer,
            finalGeneratedIds
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
            "verified AMI2 artifact has not been compiled for this GGUF"
        }
        require(
            artifact.loaded.bundle.foundation.lineage.sourceSha256 == model.sha256
        ) {
            "AMI2 source lineage does not match installed GGUF"
        }

        val hardware = requireNotNull(hardwareSnapshot()) {
            "AMNE2 production preparation requires an Android hardware snapshot"
        }
        val session = sessionFactory.open(
            artifactFile = artifact.file,
            hardware = hardware
        ).getOrThrow()
        val binding = session.binding
        val graph = binding.tensorGraph
        val metadata = binding.preservedMetadata
        val tokenizer = AmiTokenizerLexiconReader()
            .read(binding.decoderArtifactView)
            .getOrThrow()
        val stackPlan = session.stackPlan
        val outputPlan = AmiOutputHeadPlanner
            .plan(graph, metadata, stackPlan)
            .getOrThrow()
        session.close().getOrThrow()

        require(tokenizer.vocabularySize == stackPlan.vocabularySize) {
            "AMI tokenizer vocabulary differs from decoder vocabulary"
        }

        val requiredMatrixPrimitives =
            AmiDirectExecutionAdmissionPolicy.requiredMatrixPrimitives(
                stack = stackPlan,
                output = outputPlan
            )

        AmiDirectPreparedModel(
            artifact = artifact,
            graph = graph,
            metadata = metadata,
            tokenizer = tokenizer,
            stackPlan = stackPlan,
            requiredMatrixPrimitives = requiredMatrixPrimitives
        ).also { prepared[key] = it }
    }

    private fun prepareInstructionPrompt(
        runtime: AmiDirectPreparedModel,
        request: InferenceRequest
    ): AmiPreparedInstructionPrompt =
        AmiInstructionPromptCompatibility.prepare(
            metadata = runtime.metadata,
            lexicon = runtime.tokenizer,
            amperPrompt = request.prompt
        )

    private fun directReadiness(
        runtime: AmiDirectPreparedModel
    ): AmiDirectExecutionReadiness =
        AmiDirectExecutionAdmissionPolicy.evaluate(
            requiredMatrixPrimitives = runtime.requiredMatrixPrimitives,
            registry = AmneProcessKernelRuntime.registry(),
            hardware = hardwareSnapshot()
        )

    companion object {
        const val BACKEND_ID: String = AmperCoreInferencePort.CORE_ID
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
