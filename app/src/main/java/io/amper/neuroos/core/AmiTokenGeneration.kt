package io.amper.neuroos.core

import kotlin.math.exp
import kotlin.random.Random

data class AmiOutputHeadPlan(
    val hiddenSize: Int,
    val vocabularySize: Int,
    val rmsEpsilon: Float,
    val normWeight: AmiTensorDescriptor,
    val outputWeight: AmiTensorDescriptor,
    val tiedEmbedding: Boolean
) {
    init {
        require(hiddenSize > 0)
        require(vocabularySize > 0)
        require(rmsEpsilon > 0f && rmsEpsilon.isFinite())
    }
}

object AmiOutputHeadPlanner {
    fun plan(
        graph: AmiTensorGraph,
        metadata: AmiGgufMetadataSnapshot,
        stack: AmiDecoderStackPlan
    ): Result<AmiOutputHeadPlan> = runCatching {
        val architecture = graph.architecture.value
        val norm = requireNotNull(graph.tensor("output_norm.weight")) {
            "AMI output norm tensor is missing"
        }
        require(norm.dimensions.size == 1) {
            "AMI output norm tensor must be rank-1"
        }
        val normWidth = checkedInt(norm.dimensions.single(), "output norm width")
        require(normWidth == stack.hiddenSize) {
            "AMI output norm width differs from decoder hidden size"
        }
        val normEncoding = AmneTensorEncoding.fromGgmlType(norm.sourceEncodingType)
        require(
            normEncoding == AmneTensorEncoding.F32 ||
                normEncoding == AmneTensorEncoding.F16
        ) {
            "AMNE output norm encoding is unsupported"
        }
        requireNotNull(norm.storageBytes)

        val explicit = graph.tensor("output.weight")
        val output = explicit ?: stack.embedding.tensor
        val shape = AmiDecoderFfnPlanner.matrixShape(output)
        require(shape.columns == stack.hiddenSize) {
            "AMI output projection input width differs from decoder hidden size"
        }
        require(shape.rows == stack.vocabularySize) {
            "AMI output projection row count differs from vocabulary size"
        }
        require(AmneTensorKernelPlanner.requiredPrimitive(output) != null) {
            "AMNE output projection encoding is unsupported: " +
                output.sourceEncodingType
        }
        requireNotNull(output.storageBytes)

        val epsilon = metadata.floating(
            "$architecture.attention.layer_norm_rms_epsilon"
        ) ?: error(
            "preserved GGUF metadata is missing " +
                "$architecture.attention.layer_norm_rms_epsilon"
        )
        require(epsilon > 0.0 && epsilon.isFinite())

        AmiOutputHeadPlan(
            hiddenSize = stack.hiddenSize,
            vocabularySize = stack.vocabularySize,
            rmsEpsilon = epsilon.toFloat(),
            normWeight = norm,
            outputWeight = output,
            tiedEmbedding = explicit == null
        )
    }

    private fun checkedInt(value: ULong, label: String): Int {
        require(value in 1UL..Int.MAX_VALUE.toULong()) {
            "$label exceeds AMNE Int limit"
        }
        return value.toInt()
    }
}

data class AmiOutputHeadExecutionResult(
    val logits: FloatArray,
    val normBackendId: String,
    val projectionBackendId: String,
    val tiedEmbedding: Boolean,
    val mappedBytes: Long,
    val matrixWindows: Int,
    val wallTimeMs: Long
)

class AmiOutputHeadExecutor(
    private val maxWindowBytes: Int = 8 * 1024 * 1024
) {
    fun execute(
        loaded: AmiLoadedArtifact,
        graph: AmiTensorGraph,
        plan: AmiOutputHeadPlan,
        hidden: FloatArray,
        hardware: AmiHardwareSnapshot?
    ): Result<AmiOutputHeadExecutionResult> = runCatching {
        require(hidden.size == plan.hiddenSize)
        require(hidden.all { it.isFinite() })

        val startedNs = System.nanoTime()
        val tensors = AmiTensorWindowExecutor(
            loaded = loaded,
            graph = graph,
            hardware = hardware,
            maxWindowBytes = maxWindowBytes
        )
        val registry = AmneProcessKernelRuntime.registry()
        val normWeight = tensors.readVector(plan.normWeight)
        val normBackend = registry.backendFor(
            AmneKernelPrimitive.RMS_NORM_F32,
            hardware
        )
        val normalized = normBackend.rmsNormF32(
            input = hidden,
            weight = normWeight.values,
            epsilon = plan.rmsEpsilon
        )
        val projected = tensors.matVec(
            tensor = plan.outputWeight,
            vector = normalized
        )
        require(projected.output.size == plan.vocabularySize)
        require(projected.output.all { it.isFinite() }) {
            "AMNE output head produced non-finite logits"
        }

        AmiOutputHeadExecutionResult(
            logits = projected.output,
            normBackendId = normBackend.descriptor.backendId,
            projectionBackendId = projected.backendId,
            tiedEmbedding = plan.tiedEmbedding,
            mappedBytes = normWeight.mappedBytes + projected.mappedBytes,
            matrixWindows = projected.windows,
            wallTimeMs = (System.nanoTime() - startedNs) / 1_000_000L
        )
    }
}

data class AmiSamplingConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val repetitionPenalty: Float = 1.0f,
    val seed: Long = 0L
) {
    init {
        require(temperature >= 0f && temperature.isFinite())
        require(topK >= 0)
        require(topP > 0f && topP <= 1f && topP.isFinite())
        require(repetitionPenalty >= 1f && repetitionPenalty.isFinite())
    }
}

data class AmiSampleResult(
    val tokenId: Int,
    val probability: Float,
    val consideredTokens: Int
)

object AmiLogitSampler {
    fun sample(
        logits: FloatArray,
        config: AmiSamplingConfig,
        history: IntArray = intArrayOf(),
        random: Random = Random(config.seed)
    ): AmiSampleResult {
        require(logits.isNotEmpty())
        require(logits.all { it.isFinite() })
        require(history.all { it in logits.indices })

        val adjusted = logits.copyOf()
        if (config.repetitionPenalty > 1f && history.isNotEmpty()) {
            history.toSet().forEach { tokenId ->
                adjusted[tokenId] =
                    if (adjusted[tokenId] >= 0f) {
                        adjusted[tokenId] / config.repetitionPenalty
                    } else {
                        adjusted[tokenId] * config.repetitionPenalty
                    }
            }
        }

        if (config.temperature == 0f) {
            var best = 0
            for (index in 1 until adjusted.size) {
                if (adjusted[index] > adjusted[best]) best = index
            }
            return AmiSampleResult(
                tokenId = best,
                probability = 1f,
                consideredTokens = 1
            )
        }

        val temperature = config.temperature.toDouble()
        val sorted = adjusted.indices
            .sortedByDescending { adjusted[it] }
            .let { indices ->
                if (config.topK > 0) indices.take(minOf(config.topK, indices.size))
                else indices
            }

        val maxLogit = sorted.maxOf { adjusted[it] }.toDouble()
        val weights = DoubleArray(sorted.size)
        var denominator = 0.0
        sorted.indices.forEach { index ->
            val weight = exp(
                (adjusted[sorted[index]].toDouble() - maxLogit) / temperature
            )
            weights[index] = weight
            denominator += weight
        }
        require(denominator.isFinite() && denominator > 0.0)

        val probabilities = DoubleArray(sorted.size) { index ->
            weights[index] / denominator
        }

        var cumulative = 0.0
        var cutoff = sorted.size
        for (index in sorted.indices) {
            cumulative += probabilities[index]
            if (cumulative >= config.topP.toDouble()) {
                cutoff = index + 1
                break
            }
        }
        cutoff = cutoff.coerceAtLeast(1)

        var keptTotal = 0.0
        for (index in 0 until cutoff) keptTotal += probabilities[index]
        require(keptTotal > 0.0 && keptTotal.isFinite())

        val target = random.nextDouble() * keptTotal
        var running = 0.0
        for (index in 0 until cutoff) {
            running += probabilities[index]
            if (target <= running || index == cutoff - 1) {
                return AmiSampleResult(
                    tokenId = sorted[index],
                    probability = (probabilities[index] / keptTotal).toFloat(),
                    consideredTokens = cutoff
                )
            }
        }

        error("unreachable AMI sampler state")
    }
}

data class AmiGenerationConfig(
    val maxNewTokens: Int = 32,
    val sampling: AmiSamplingConfig = AmiSamplingConfig(),
    val stopTokenIds: Set<Int> = emptySet()
) {
    init {
        require(maxNewTokens > 0)
        require(stopTokenIds.all { it >= 0 })
    }
}

data class AmiGeneratedToken(
    val tokenId: Int,
    val probability: Float,
    val position: Int,
    val decodeWallTimeMs: Long,
    val outputHeadWallTimeMs: Long
)

data class AmiGenerationResult(
    val promptTokenIds: IntArray,
    val generatedTokenIds: IntArray,
    val stoppedByToken: Boolean,
    val finalPosition: Int,
    val tokens: List<AmiGeneratedToken>,
    val wallTimeMs: Long
)

/**
 * First complete autoregressive AMI token loop.
 *
 * Tokenization/text decoding remains a separate concern. This executor consumes already-tokenized
 * prompt ids and returns generated token ids. Decoder KV state is rolled back to its original
 * position if any prompt/decode/output-head step fails.
 */
class AmiAutoregressiveGenerator(
    private val maxWindowBytes: Int = 8 * 1024 * 1024
) {
    fun generate(
        loaded: AmiLoadedArtifact,
        graph: AmiTensorGraph,
        metadata: AmiGgufMetadataSnapshot,
        stackPlan: AmiDecoderStackPlan,
        state: AmiDecoderStackState,
        promptTokenIds: IntArray,
        config: AmiGenerationConfig,
        hardware: AmiHardwareSnapshot?
    ): Result<AmiGenerationResult> {
        require(promptTokenIds.isNotEmpty()) {
            "AMI generation requires at least one prompt token"
        }
        require(promptTokenIds.all { it in 0 until stackPlan.vocabularySize }) {
            "AMI prompt token id is outside vocabulary"
        }
        require(config.stopTokenIds.all { it in 0 until stackPlan.vocabularySize }) {
            "AMI stop token id is outside vocabulary"
        }

        val originalPosition = runCatching { state.position }
            .getOrElse { return Result.failure(it) }

        return runCatching {
            val startedNs = System.nanoTime()
            val outputPlan = AmiOutputHeadPlanner
                .plan(graph, metadata, stackPlan)
                .getOrThrow()
            val stackExecutor = AmiDecoderStackExecutor(maxWindowBytes)
            val outputExecutor = AmiOutputHeadExecutor(maxWindowBytes)

            var lastHidden: FloatArray? = null
            for (tokenId in promptTokenIds) {
                val decoded = stackExecutor.executeToken(
                    loaded = loaded,
                    graph = graph,
                    plan = stackPlan,
                    state = state,
                    tokenId = tokenId,
                    hardware = hardware
                ).getOrThrow()
                lastHidden = decoded.output
            }

            val generated = ArrayList<Int>(config.maxNewTokens)
            val traces = ArrayList<AmiGeneratedToken>(config.maxNewTokens)
            val history = ArrayList<Int>(
                promptTokenIds.size + config.maxNewTokens
            )
            promptTokenIds.forEach(history::add)

            var stopped = false
            var currentHidden = requireNotNull(lastHidden)

            repeat(config.maxNewTokens) {
                val outputHead = outputExecutor.execute(
                    loaded = loaded,
                    graph = graph,
                    plan = outputPlan,
                    hidden = currentHidden,
                    hardware = hardware
                ).getOrThrow()

                val sample = AmiLogitSampler.sample(
                    logits = outputHead.logits,
                    config = config.sampling,
                    history = history.toIntArray(),
                    random = Random(
                        config.sampling.seed + generated.size.toLong()
                    )
                )
                val tokenId = sample.tokenId
                generated += tokenId
                history += tokenId

                val tokenPosition = state.position
                traces += AmiGeneratedToken(
                    tokenId = tokenId,
                    probability = sample.probability,
                    position = tokenPosition,
                    decodeWallTimeMs = 0L,
                    outputHeadWallTimeMs = outputHead.wallTimeMs
                )

                if (tokenId in config.stopTokenIds) {
                    stopped = true
                    return@repeat
                }

                if (generated.size < config.maxNewTokens) {
                    val decoded = stackExecutor.executeToken(
                        loaded = loaded,
                        graph = graph,
                        plan = stackPlan,
                        state = state,
                        tokenId = tokenId,
                        hardware = hardware
                    ).getOrThrow()
                    currentHidden = decoded.output
                    traces[traces.lastIndex] = traces.last().copy(
                        decodeWallTimeMs = decoded.wallTimeMs
                    )
                }

                if (stopped) return@repeat
            }

            AmiGenerationResult(
                promptTokenIds = promptTokenIds.copyOf(),
                generatedTokenIds = generated.toIntArray(),
                stoppedByToken = stopped,
                finalPosition = state.position,
                tokens = traces,
                wallTimeMs = (System.nanoTime() - startedNs) / 1_000_000L
            )
        }.onFailure {
            runCatching { state.rollbackTo(originalPosition) }
        }
    }
}
