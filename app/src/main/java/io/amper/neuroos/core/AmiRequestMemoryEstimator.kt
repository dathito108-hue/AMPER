package io.amper.neuroos.core

data class AmiRequestMemoryEstimate(
    val promptTokens: Int,
    val requestedOutputTokens: Int,
    val activeContextTokens: Int,
    val kvCacheBytes: Long,
    val transientWorkingBytes: Long,
    val mmapWindowBytes: Long,
    val estimatedMemoryMb: Int
) {
    init {
        require(promptTokens > 0)
        require(requestedOutputTokens > 0)
        require(activeContextTokens == promptTokens + requestedOutputTokens)
        require(kvCacheBytes >= 0L)
        require(transientWorkingBytes > 0L)
        require(mmapWindowBytes > 0L)
        require(estimatedMemoryMb > 0)
    }
}

/**
 * Request-scoped mobile memory estimate for direct AMI execution.
 *
 * AMI weights are file-backed and accessed through bounded mmap windows. The complete advertised
 * model context must therefore not be treated as allocated KV memory. Phase620 estimates only the
 * exact prompt + requested generation horizon that [AmiDecoderStackState] will configure for this
 * request, plus bounded tensor/scratch working memory.
 */
object AmiRequestMemoryEstimator {
    private const val MIB: Long = 1024L * 1024L

    fun estimate(
        plan: AmiDecoderStackPlan,
        promptTokens: Int,
        requestedOutputTokens: Int,
        maxWindowBytes: Int
    ): AmiRequestMemoryEstimate =
        estimateGeometry(
            layerKvWidths = plan.layers.map { it.attention.kvWidth },
            hiddenSize = plan.hiddenSize,
            maxQueryWidth = plan.layers.maxOf { it.attention.queryWidth },
            maxFfnWidth = plan.layers.maxOf { it.ffn.feedForwardSize },
            maxContextTokens = plan.maxContextTokens,
            promptTokens = promptTokens,
            requestedOutputTokens = requestedOutputTokens,
            maxWindowBytes = maxWindowBytes
        )

    internal fun estimateGeometry(
        layerKvWidths: List<Int>,
        hiddenSize: Int,
        maxQueryWidth: Int,
        maxFfnWidth: Int,
        maxContextTokens: Int,
        promptTokens: Int,
        requestedOutputTokens: Int,
        maxWindowBytes: Int
    ): AmiRequestMemoryEstimate {
        require(layerKvWidths.isNotEmpty())
        require(layerKvWidths.all { it > 0 })
        require(hiddenSize > 0)
        require(maxQueryWidth > 0)
        require(maxFfnWidth > 0)
        require(maxContextTokens > 0)
        require(promptTokens > 0)
        require(requestedOutputTokens > 0)
        require(maxWindowBytes > 0)

        val activeContextTokens = Math.addExact(
            promptTokens,
            requestedOutputTokens
        )
        require(activeContextTokens <= maxContextTokens) {
            "request context exceeds AMI decoder capacity"
        }

        val kvBytes = layerKvWidths.fold(0L) { total, kvWidth ->
            val layerBytes = Math.multiplyExact(
                Math.multiplyExact(
                    kvWidth.toLong(),
                    activeContextTokens.toLong()
                ),
                2L * Float.SIZE_BYTES.toLong()
            )
            Math.addExact(total, layerBytes)
        }

        val maxKvWidth = layerKvWidths.max()

        // Bounded live FloatArray scratch used by attention/FFN plus a conservative multiplier for
        // JNI/output overlap. This is transient working memory, not foundation weight residency.
        val scratchFloats = listOf(
            Math.multiplyExact(hiddenSize.toLong(), 12L),
            Math.multiplyExact(maxQueryWidth.toLong(), 4L),
            Math.multiplyExact(maxKvWidth.toLong(), 4L),
            Math.multiplyExact(maxFfnWidth.toLong(), 4L),
            Math.multiplyExact(activeContextTokens.toLong(), 2L)
        ).fold(0L, Math::addExact)

        val scratchBytes = Math.multiplyExact(
            scratchFloats,
            Float.SIZE_BYTES.toLong()
        )

        // The tensor executor maps at most one bounded window at a time. A quantized tile may also
        // coexist with its ByteArray copy; F16/F32 tiles may coexist with one decoded FloatArray.
        // Reserve twice the mapping window and at least 64 MiB process-local transient headroom for
        // allocator/JNI/Compose overlap during one interactive turn.
        val mappedAndTileBytes = Math.multiplyExact(
            maxWindowBytes.toLong(),
            2L
        )
        val minimumTransientHeadroom = 64L * MIB
        val transientWorkingBytes = maxOf(
            minimumTransientHeadroom,
            Math.addExact(scratchBytes, mappedAndTileBytes)
        )

        val totalBytes = Math.addExact(kvBytes, transientWorkingBytes)
        val estimatedMb = ((totalBytes + MIB - 1L) / MIB)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()

        return AmiRequestMemoryEstimate(
            promptTokens = promptTokens,
            requestedOutputTokens = requestedOutputTokens,
            activeContextTokens = activeContextTokens,
            kvCacheBytes = kvBytes,
            transientWorkingBytes = transientWorkingBytes,
            mmapWindowBytes = maxWindowBytes.toLong(),
            estimatedMemoryMb = estimatedMb
        )
    }
}
