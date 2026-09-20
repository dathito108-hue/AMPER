package io.amper.neuroos.core

import kotlin.math.sqrt

data class AmiDecoderAttentionPlan(
    val layerIndex: Int,
    val hiddenSize: Int,
    val headCount: Int,
    val kvHeadCount: Int,
    val headDimension: Int,
    val rmsEpsilon: Float,
    val ropeTheta: Float,
    val normWeight: AmiTensorDescriptor,
    val queryWeight: AmiTensorDescriptor,
    val keyWeight: AmiTensorDescriptor,
    val valueWeight: AmiTensorDescriptor,
    val outputWeight: AmiTensorDescriptor
) {
    init {
        require(layerIndex >= 0)
        require(hiddenSize > 0)
        require(headCount > 0)
        require(kvHeadCount > 0)
        require(headCount % kvHeadCount == 0) {
            "AMNE grouped-query attention requires head_count divisible by head_count_kv"
        }
        require(headDimension > 0 && headDimension % 2 == 0)
        require(rmsEpsilon > 0f && rmsEpsilon.isFinite())
        require(ropeTheta > 1f && ropeTheta.isFinite())
    }

    val queryWidth: Int
        get() = Math.multiplyExact(headCount, headDimension)

    val kvWidth: Int
        get() = Math.multiplyExact(kvHeadCount, headDimension)
}

object AmiDecoderAttentionPlanner {
    fun plan(
        graph: AmiTensorGraph,
        metadata: AmiGgufMetadataSnapshot,
        layerIndex: Int
    ): Result<AmiDecoderAttentionPlan> = runCatching {
        require(layerIndex >= 0)
        val prefix = "blk.$layerIndex"
        val norm = requireTensor(graph, "$prefix.attn_norm.weight")
        val query = requireTensor(graph, "$prefix.attn_q.weight")
        val key = requireTensor(graph, "$prefix.attn_k.weight")
        val value = requireTensor(graph, "$prefix.attn_v.weight")
        val output = requireTensor(graph, "$prefix.attn_output.weight")

        require(norm.dimensions.size == 1) {
            "attention norm tensor must be rank-1"
        }
        val hidden = checkedInt(norm.dimensions.single(), "hidden size")

        val qShape = AmiDecoderFfnPlanner.matrixShape(query)
        val kShape = AmiDecoderFfnPlanner.matrixShape(key)
        val vShape = AmiDecoderFfnPlanner.matrixShape(value)
        val oShape = AmiDecoderFfnPlanner.matrixShape(output)

        require(qShape.columns == hidden)
        require(kShape.columns == hidden)
        require(vShape.columns == hidden)
        require(oShape.rows == hidden)
        require(oShape.columns == qShape.rows) {
            "attention output projection input width must match query width"
        }
        require(kShape.rows == vShape.rows) {
            "attention key/value projection widths differ"
        }

        requireVectorEncoding(norm)
        requireMatrixEncoding(query)
        requireMatrixEncoding(key)
        requireMatrixEncoding(value)
        requireMatrixEncoding(output)

        val architecture = graph.architecture.value
        val headCount = requiredPositiveInt(
            metadata.integer("$architecture.attention.head_count"),
            "$architecture.attention.head_count"
        )
        val kvHeadCount = metadata.integer(
            "$architecture.attention.head_count_kv"
        )?.let {
            requiredPositiveInt(it, "$architecture.attention.head_count_kv")
        } ?: headCount

        require(qShape.rows % headCount == 0) {
            "query projection width is not divisible by attention head count"
        }
        val headDimension = qShape.rows / headCount
        require(headDimension % 2 == 0) {
            "attention head dimension must be even for RoPE"
        }
        require(kShape.rows == Math.multiplyExact(kvHeadCount, headDimension)) {
            "key projection width does not match KV head geometry"
        }
        require(vShape.rows == Math.multiplyExact(kvHeadCount, headDimension)) {
            "value projection width does not match KV head geometry"
        }

        val epsilon = metadata.floating(
            "$architecture.attention.layer_norm_rms_epsilon"
        ) ?: error(
            "preserved GGUF metadata is missing " +
                "$architecture.attention.layer_norm_rms_epsilon"
        )
        require(epsilon > 0.0 && epsilon.isFinite()) {
            "invalid attention RMSNorm epsilon"
        }

        val ropeTheta = metadata.floating(
            "$architecture.rope.freq_base"
        ) ?: 10_000.0
        require(ropeTheta > 1.0 && ropeTheta.isFinite()) {
            "invalid RoPE frequency base"
        }

        AmiDecoderAttentionPlan(
            layerIndex = layerIndex,
            hiddenSize = hidden,
            headCount = headCount,
            kvHeadCount = kvHeadCount,
            headDimension = headDimension,
            rmsEpsilon = epsilon.toFloat(),
            ropeTheta = ropeTheta.toFloat(),
            normWeight = norm,
            queryWeight = query,
            keyWeight = key,
            valueWeight = value,
            outputWeight = output
        )
    }

    private fun requireTensor(
        graph: AmiTensorGraph,
        name: String
    ): AmiTensorDescriptor =
        requireNotNull(graph.tensor(name)) {
            "AMI decoder tensor is missing: $name"
        }

    private fun requireVectorEncoding(tensor: AmiTensorDescriptor) {
        val encoding = AmneTensorEncoding.fromGgmlType(tensor.sourceEncodingType)
        require(
            encoding == AmneTensorEncoding.F32 ||
                encoding == AmneTensorEncoding.F16
        ) {
            "AMNE attention norm does not support source encoding " +
                tensor.sourceEncodingType
        }
        requireNotNull(tensor.storageBytes)
    }

    private fun requireMatrixEncoding(tensor: AmiTensorDescriptor) {
        require(AmneTensorKernelPlanner.requiredPrimitive(tensor) != null) {
            "AMNE attention matrix does not support source encoding " +
                tensor.sourceEncodingType + " for " + tensor.name
        }
        requireNotNull(tensor.storageBytes)
    }

    private fun requiredPositiveInt(value: Long?, label: String): Int {
        val resolved = requireNotNull(value) {
            "preserved GGUF metadata is missing $label"
        }
        require(resolved in 1L..Int.MAX_VALUE.toLong()) {
            "$label exceeds AMNE Int limit"
        }
        return resolved.toInt()
    }

    private fun checkedInt(value: ULong, label: String): Int {
        require(value in 1UL..Int.MAX_VALUE.toULong()) {
            "$label exceeds AMNE Int limit"
        }
        return value.toInt()
    }
}

data class AmiKvToken(
    val position: Int,
    val key: FloatArray,
    val value: FloatArray
)

/**
 * Bounded single-layer KV cache.
 *
 * Phase607 deliberately fails closed at the configured token capacity instead of silently evicting
 * old causal context. Later context policies can introduce explicit sliding-window semantics.
 */
class AmiLayerKvCache(
    val layerIndex: Int,
    val kvWidth: Int,
    val maxTokens: Int
) {
    init {
        require(layerIndex >= 0)
        require(kvWidth > 0)
        require(maxTokens > 0)
    }

    private val tokens = ArrayList<AmiKvToken>(maxTokens)

    val size: Int
        get() = tokens.size

    fun append(
        position: Int,
        key: FloatArray,
        value: FloatArray
    ) {
        require(position == tokens.size) {
            "AMNE KV cache positions must be contiguous from zero"
        }
        require(tokens.size < maxTokens) {
            "AMNE KV cache reached configured context capacity"
        }
        require(key.size == kvWidth && value.size == kvWidth)
        require(key.all { it.isFinite() })
        require(value.all { it.isFinite() })
        tokens += AmiKvToken(
            position = position,
            key = key.copyOf(),
            value = value.copyOf()
        )
    }

    fun snapshot(): List<AmiKvToken> =
        tokens.map { token ->
            AmiKvToken(
                position = token.position,
                key = token.key.copyOf(),
                value = token.value.copyOf()
            )
        }

    fun clear() {
        tokens.clear()
    }
}

data class AmiDecoderAttentionTrace(
    val layerIndex: Int,
    val position: Int,
    val headCount: Int,
    val kvHeadCount: Int,
    val headDimension: Int,
    val contextTokens: Int,
    val normBackendId: String,
    val queryBackendId: String,
    val keyBackendId: String,
    val valueBackendId: String,
    val ropeBackendId: String,
    val dotBackendId: String,
    val softmaxBackendId: String,
    val outputBackendId: String,
    val mappedBytes: Long,
    val matrixWindows: Int,
    val wallTimeMs: Long
)

data class AmiDecoderAttentionExecutionResult(
    val output: FloatArray,
    val trace: AmiDecoderAttentionTrace
)

/**
 * Single-token causal self-attention over AMI weights plus a persistent layer KV cache.
 */
class AmiDecoderAttentionExecutor(
    private val maxWindowBytes: Int = 8 * 1024 * 1024
) {
    fun execute(
        loaded: AmiLoadedArtifact,
        graph: AmiTensorGraph,
        plan: AmiDecoderAttentionPlan,
        input: FloatArray,
        position: Int,
        kvCache: AmiLayerKvCache,
        hardware: AmiHardwareSnapshot?
    ): Result<AmiDecoderAttentionExecutionResult> = runCatching {
        require(input.size == plan.hiddenSize)
        require(input.all { it.isFinite() })
        require(position == kvCache.size) {
            "attention position must equal current KV cache size"
        }
        require(kvCache.layerIndex == plan.layerIndex)
        require(kvCache.kvWidth == plan.kvWidth)

        val startedNs = System.nanoTime()
        val tensorExecutor = AmiTensorWindowExecutor(
            loaded = loaded,
            graph = graph,
            hardware = hardware,
            maxWindowBytes = maxWindowBytes
        )
        val registry = AmneProcessKernelRuntime.registry()

        val normWeight = tensorExecutor.readVector(plan.normWeight)
        val normBackend = registry.backendFor(
            AmneKernelPrimitive.RMS_NORM_F32,
            hardware
        )
        val normalized = normBackend.rmsNormF32(
            input = input,
            weight = normWeight.values,
            epsilon = plan.rmsEpsilon
        )

        val query = tensorExecutor.matVec(plan.queryWeight, normalized)
        val key = tensorExecutor.matVec(plan.keyWeight, normalized)
        val value = tensorExecutor.matVec(plan.valueWeight, normalized)

        require(query.output.size == plan.queryWidth)
        require(key.output.size == plan.kvWidth)
        require(value.output.size == plan.kvWidth)

        val ropeBackend = registry.backendFor(
            AmneKernelPrimitive.ROPE_F32,
            hardware
        )
        val rotatedQuery = rotateHeads(
            query.output,
            plan.headCount,
            plan.headDimension,
            position,
            plan.ropeTheta,
            ropeBackend
        )
        val rotatedKey = rotateHeads(
            key.output,
            plan.kvHeadCount,
            plan.headDimension,
            position,
            plan.ropeTheta,
            ropeBackend
        )

        kvCache.append(
            position = position,
            key = rotatedKey,
            value = value.output
        )
        val history = kvCache.snapshot()

        val dotBackend = registry.backendFor(
            AmneKernelPrimitive.DOT_F32,
            hardware
        )
        val softmaxBackend = registry.backendFor(
            AmneKernelPrimitive.SOFTMAX_F32,
            hardware
        )
        val scale = 1f / sqrt(plan.headDimension.toFloat())
        val context = FloatArray(plan.queryWidth)
        val headsPerKv = plan.headCount / plan.kvHeadCount

        for (queryHead in 0 until plan.headCount) {
            val kvHead = queryHead / headsPerKv
            val q = sliceHead(
                rotatedQuery,
                queryHead,
                plan.headDimension
            )
            val scores = FloatArray(history.size)
            history.indices.forEach { tokenIndex ->
                val k = sliceHead(
                    history[tokenIndex].key,
                    kvHead,
                    plan.headDimension
                )
                scores[tokenIndex] = dotBackend.dotF32(q, k) * scale
            }
            val probabilities = softmaxBackend.softmaxF32(scores)
            require(probabilities.size == history.size)

            val contextBase = queryHead * plan.headDimension
            history.indices.forEach { tokenIndex ->
                val probability = probabilities[tokenIndex]
                val valueHead = sliceHead(
                    history[tokenIndex].value,
                    kvHead,
                    plan.headDimension
                )
                for (dimension in 0 until plan.headDimension) {
                    context[contextBase + dimension] +=
                        probability * valueHead[dimension]
                }
            }
        }

        require(context.all { it.isFinite() }) {
            "AMNE attention context produced non-finite values"
        }

        val projected = tensorExecutor.matVec(
            plan.outputWeight,
            context
        )
        require(projected.output.size == plan.hiddenSize)

        val output = FloatArray(plan.hiddenSize) { index ->
            input[index] + projected.output[index]
        }
        require(output.all { it.isFinite() }) {
            "AMNE attention residual produced non-finite values"
        }

        AmiDecoderAttentionExecutionResult(
            output = output,
            trace = AmiDecoderAttentionTrace(
                layerIndex = plan.layerIndex,
                position = position,
                headCount = plan.headCount,
                kvHeadCount = plan.kvHeadCount,
                headDimension = plan.headDimension,
                contextTokens = history.size,
                normBackendId = normBackend.descriptor.backendId,
                queryBackendId = query.backendId,
                keyBackendId = key.backendId,
                valueBackendId = value.backendId,
                ropeBackendId = ropeBackend.descriptor.backendId,
                dotBackendId = dotBackend.descriptor.backendId,
                softmaxBackendId = softmaxBackend.descriptor.backendId,
                outputBackendId = projected.backendId,
                mappedBytes = normWeight.mappedBytes +
                    query.mappedBytes +
                    key.mappedBytes +
                    value.mappedBytes +
                    projected.mappedBytes,
                matrixWindows = query.windows +
                    key.windows +
                    value.windows +
                    projected.windows,
                wallTimeMs =
                    (System.nanoTime() - startedNs) / 1_000_000L
            )
        )
    }

    private fun rotateHeads(
        values: FloatArray,
        headCount: Int,
        headDimension: Int,
        position: Int,
        theta: Float,
        backend: AmneKernelBackend
    ): FloatArray {
        require(values.size == Math.multiplyExact(headCount, headDimension))
        val output = FloatArray(values.size)
        for (head in 0 until headCount) {
            val source = sliceHead(values, head, headDimension)
            val rotated = backend.ropeF32(
                input = source,
                position = position,
                theta = theta
            )
            require(rotated.size == headDimension)
            rotated.copyInto(
                output,
                destinationOffset = head * headDimension
            )
        }
        return output
    }

    private fun sliceHead(
        values: FloatArray,
        headIndex: Int,
        headDimension: Int
    ): FloatArray {
        require(headIndex >= 0)
        val start = Math.multiplyExact(headIndex, headDimension)
        val end = Math.addExact(start, headDimension)
        require(end <= values.size)
        return values.copyOfRange(start, end)
    }
}

data class AmiDecoderLayerPlan(
    val layerIndex: Int,
    val attention: AmiDecoderAttentionPlan,
    val ffn: AmiDecoderFfnPlan
) {
    init {
        require(attention.layerIndex == layerIndex)
        require(ffn.layerIndex == layerIndex)
        require(attention.hiddenSize == ffn.hiddenSize) {
            "attention and FFN hidden widths differ"
        }
    }
}

object AmiDecoderLayerPlanner {
    fun plan(
        graph: AmiTensorGraph,
        metadata: AmiGgufMetadataSnapshot,
        layerIndex: Int
    ): Result<AmiDecoderLayerPlan> = runCatching {
        val attention = AmiDecoderAttentionPlanner
            .plan(graph, metadata, layerIndex)
            .getOrThrow()
        val ffn = AmiDecoderFfnPlanner
            .plan(graph, metadata, layerIndex)
            .getOrThrow()
        AmiDecoderLayerPlan(layerIndex, attention, ffn)
    }
}

data class AmiDecoderLayerExecutionResult(
    val output: FloatArray,
    val attentionTrace: AmiDecoderAttentionTrace,
    val ffnTrace: AmiDecoderFfnTrace
)

/**
 * First complete AMI decoder layer:
 *
 * attention RMSNorm -> Q/K/V -> per-head RoPE -> causal GQA -> O projection -> residual
 * -> FFN RMSNorm -> Gate/Up -> SwiGLU -> Down -> residual
 */
class AmiDecoderLayerExecutor(
    private val maxWindowBytes: Int = 8 * 1024 * 1024
) {
    fun execute(
        loaded: AmiLoadedArtifact,
        graph: AmiTensorGraph,
        plan: AmiDecoderLayerPlan,
        input: FloatArray,
        position: Int,
        kvCache: AmiLayerKvCache,
        hardware: AmiHardwareSnapshot?
    ): Result<AmiDecoderLayerExecutionResult> = runCatching {
        val attention = AmiDecoderAttentionExecutor(maxWindowBytes)
            .execute(
                loaded = loaded,
                graph = graph,
                plan = plan.attention,
                input = input,
                position = position,
                kvCache = kvCache,
                hardware = hardware
            )
            .getOrThrow()

        val ffn = AmiDecoderFfnExecutor(maxWindowBytes)
            .execute(
                loaded = loaded,
                graph = graph,
                plan = plan.ffn,
                input = attention.output,
                hardware = hardware
            )
            .getOrThrow()

        AmiDecoderLayerExecutionResult(
            output = ffn.output,
            attentionTrace = attention.trace,
            ffnTrace = ffn.trace
        )
    }
}
