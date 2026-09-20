package io.amper.neuroos.core

import java.nio.ByteOrder

data class AmiTokenEmbeddingPlan(
    val tensor: AmiTensorDescriptor,
    val hiddenSize: Int,
    val vocabularySize: Int,
    val encoding: AmneTensorEncoding
) {
    init {
        require(hiddenSize > 0)
        require(vocabularySize > 0)
    }
}

data class AmiDecoderStackPlan(
    val architecture: AmiArchitectureId,
    val embedding: AmiTokenEmbeddingPlan,
    val layers: List<AmiDecoderLayerPlan>,
    val maxContextTokens: Int
) {
    init {
        require(layers.isNotEmpty())
        require(maxContextTokens > 0)
        require(layers.indices.all { layers[it].layerIndex == it }) {
            "AMI decoder layers must be contiguous from zero"
        }
        require(layers.all { it.attention.hiddenSize == embedding.hiddenSize }) {
            "AMI decoder layer hidden width differs from token embedding width"
        }
    }

    val hiddenSize: Int
        get() = embedding.hiddenSize

    val vocabularySize: Int
        get() = embedding.vocabularySize

    val layerCount: Int
        get() = layers.size
}

object AmiDecoderStackPlanner {
    fun plan(
        graph: AmiTensorGraph,
        metadata: AmiGgufMetadataSnapshot
    ): Result<AmiDecoderStackPlan> = runCatching {
        val embeddingTensor = requireNotNull(graph.tensor("token_embd.weight")) {
            "AMI token embedding tensor is missing"
        }
        val embeddingShape = AmiDecoderFfnPlanner.matrixShape(embeddingTensor)
        val encoding = requireNotNull(
            AmneTensorEncoding.fromGgmlType(embeddingTensor.sourceEncodingType)
        ) {
            "AMNE token embedding encoding is unsupported: " +
                embeddingTensor.sourceEncodingType
        }
        require(
            encoding == AmneTensorEncoding.F32 ||
                encoding == AmneTensorEncoding.F16 ||
                encoding == AmneTensorEncoding.Q4_0 ||
                encoding == AmneTensorEncoding.Q8_0 ||
                encoding == AmneTensorEncoding.Q4_K ||
                encoding == AmneTensorEncoding.Q5_K ||
                encoding == AmneTensorEncoding.Q6_K
        ) {
            "AMNE token embedding encoding is not admitted: $encoding"
        }
        requireNotNull(embeddingTensor.storageBytes) {
            "AMI token embedding has unknown byte footprint"
        }

        val architecture = graph.architecture.value
        val blockCount = requiredPositiveInt(
            metadata.integer("$architecture.block_count"),
            "$architecture.block_count"
        )
        val contextLength = requiredPositiveInt(
            metadata.integer("$architecture.context_length"),
            "$architecture.context_length"
        )

        val layers = ArrayList<AmiDecoderLayerPlan>(blockCount)
        for (layerIndex in 0 until blockCount) {
            layers += AmiDecoderLayerPlanner
                .plan(graph, metadata, layerIndex)
                .getOrThrow()
        }

        AmiDecoderStackPlan(
            architecture = graph.architecture,
            embedding = AmiTokenEmbeddingPlan(
                tensor = embeddingTensor,
                hiddenSize = embeddingShape.columns,
                vocabularySize = embeddingShape.rows,
                encoding = encoding
            ),
            layers = layers,
            maxContextTokens = contextLength
        )
    }

    private fun requiredPositiveInt(
        value: Long?,
        label: String
    ): Int {
        val resolved = requireNotNull(value) {
            "preserved GGUF metadata is missing $label"
        }
        require(resolved in 1L..Int.MAX_VALUE.toLong()) {
            "$label exceeds AMNE Int limit"
        }
        return resolved.toInt()
    }
}

data class AmiEmbeddingRead(
    val tokenId: Int,
    val values: FloatArray,
    val encoding: AmneTensorEncoding,
    val mappedBytes: Long
)

/**
 * Row-addressable embedding lookup directly over verified AMI FOUNDATION_WEIGHTS.
 *
 * Only the requested token row is mmap'd and decoded. The full vocabulary matrix is never copied.
 */
class AmiTokenEmbeddingReader(
    private val loaded: AmiLoadedArtifact,
    private val graph: AmiTensorGraph,
    private val maxWindowBytes: Int = 1 * 1024 * 1024
) {
    init {
        require(maxWindowBytes > 0)
    }

    private val mapped = AmiMappedSectionAccess(
        file = loaded.file,
        descriptor = graph.foundationSection,
        maxWindowBytes = maxWindowBytes
    )

    fun read(
        plan: AmiTokenEmbeddingPlan,
        tokenId: Int
    ): AmiEmbeddingRead {
        require(tokenId in 0 until plan.vocabularySize) {
            "AMI token id is outside vocabulary"
        }

        val bytesPerRow = bytesPerRow(
            encoding = plan.encoding,
            columns = plan.hiddenSize
        )
        val expectedTensorBytes = Math.multiplyExact(
            bytesPerRow.toLong(),
            plan.vocabularySize.toLong()
        )
        require(plan.tensor.storageBytes == expectedTensorBytes) {
            "AMI token embedding byte footprint mismatch"
        }
        require(bytesPerRow <= maxWindowBytes) {
            "AMI token embedding row exceeds bounded mmap window"
        }

        val relativeOffset = Math.addExact(
            plan.tensor.foundationOffset,
            Math.multiplyExact(tokenId.toLong(), bytesPerRow.toLong())
        )
        val buffer = mapped.mapWindow(
            relativeOffset = relativeOffset,
            length = bytesPerRow
        ).order(ByteOrder.LITTLE_ENDIAN)

        val values = when (plan.encoding) {
            AmneTensorEncoding.F32 ->
                FloatArray(plan.hiddenSize) { buffer.float }

            AmneTensorEncoding.F16 ->
                FloatArray(plan.hiddenSize) {
                    AmneReferenceCpuKernels.halfToFloat(
                        buffer.short.toInt() and 0xffff
                    )
                }

            AmneTensorEncoding.Q4_0 -> {
                val bytes = ByteArray(bytesPerRow)
                buffer.get(bytes)
                decodeQ4_0(bytes, plan.hiddenSize)
            }

            AmneTensorEncoding.Q8_0 -> {
                val bytes = ByteArray(bytesPerRow)
                buffer.get(bytes)
                decodeQ8_0(bytes, plan.hiddenSize)
            }

            AmneTensorEncoding.Q4_K,
            AmneTensorEncoding.Q5_K,
            AmneTensorEncoding.Q6_K -> {
                val bytes = ByteArray(bytesPerRow)
                buffer.get(bytes)
                AmneKQuantCodec.decodeRow(
                    encoding = plan.encoding,
                    rowBytes = bytes,
                    elements = plan.hiddenSize
                )
            }
        }

        require(values.all { it.isFinite() }) {
            "AMI token embedding decoded non-finite values"
        }

        return AmiEmbeddingRead(
            tokenId = tokenId,
            values = values,
            encoding = plan.encoding,
            mappedBytes = bytesPerRow.toLong()
        )
    }

    private fun bytesPerRow(
        encoding: AmneTensorEncoding,
        columns: Int
    ): Int = when (encoding) {
        AmneTensorEncoding.F32 ->
            Math.multiplyExact(columns, encoding.blockBytes)

        AmneTensorEncoding.F16 ->
            Math.multiplyExact(columns, encoding.blockBytes)

        AmneTensorEncoding.Q4_0,
        AmneTensorEncoding.Q8_0,
        AmneTensorEncoding.Q4_K,
        AmneTensorEncoding.Q5_K,
        AmneTensorEncoding.Q6_K -> {
            require(columns % encoding.blockSize == 0) {
                "quantized AMI embedding width is not block aligned"
            }
            Math.multiplyExact(
                columns / encoding.blockSize,
                encoding.blockBytes
            )
        }
    }

    private fun decodeQ4_0(
        bytes: ByteArray,
        elements: Int
    ): FloatArray {
        require(elements % AmneTensorEncoding.Q4_0.blockSize == 0)
        val output = FloatArray(elements)
        var byteOffset = 0
        var elementOffset = 0
        while (elementOffset < elements) {
            val scaleBits =
                (bytes[byteOffset].toInt() and 0xff) or
                    ((bytes[byteOffset + 1].toInt() and 0xff) shl 8)
            val scale = AmneReferenceCpuKernels.halfToFloat(scaleBits)
            for (packedIndex in 0 until 16) {
                val packed = bytes[byteOffset + 2 + packedIndex].toInt() and 0xff
                output[elementOffset + packedIndex] =
                    scale * ((packed and 0x0f) - 8).toFloat()
                output[elementOffset + 16 + packedIndex] =
                    scale * (((packed ushr 4) and 0x0f) - 8).toFloat()
            }
            byteOffset += AmneTensorEncoding.Q4_0.blockBytes
            elementOffset += AmneTensorEncoding.Q4_0.blockSize
        }
        return output
    }

    private fun decodeQ8_0(
        bytes: ByteArray,
        elements: Int
    ): FloatArray {
        require(elements % AmneTensorEncoding.Q8_0.blockSize == 0)
        val output = FloatArray(elements)
        var byteOffset = 0
        var elementOffset = 0
        while (elementOffset < elements) {
            val scaleBits =
                (bytes[byteOffset].toInt() and 0xff) or
                    ((bytes[byteOffset + 1].toInt() and 0xff) shl 8)
            val scale = AmneReferenceCpuKernels.halfToFloat(scaleBits)
            for (index in 0 until 32) {
                output[elementOffset + index] =
                    scale * bytes[byteOffset + 2 + index].toInt().toFloat()
            }
            byteOffset += AmneTensorEncoding.Q8_0.blockBytes
            elementOffset += AmneTensorEncoding.Q8_0.blockSize
        }
        return output
    }
}

/**
 * Persistent state for one AMI decoder sequence.
 *
 * Every layer owns an independent KV cache with the same logical token position. Cache memory grows
 * lazily as tokens arrive.
 */
class AmiDecoderStackState(
    val plan: AmiDecoderStackPlan,
    maxContextTokens: Int = plan.maxContextTokens
) {
    val maxContextTokens: Int =
        minOf(maxContextTokens, plan.maxContextTokens)

    init {
        require(this.maxContextTokens > 0)
    }

    private val caches: List<AmiLayerKvCache> =
        plan.layers.map { layer ->
            AmiLayerKvCache(
                layerIndex = layer.layerIndex,
                kvWidth = layer.attention.kvWidth,
                maxTokens = this.maxContextTokens
            )
        }

    val position: Int
        get() {
            val sizes = caches.map { it.size }.distinct()
            require(sizes.size == 1) {
                "AMI per-layer KV caches are not synchronized"
            }
            return sizes.single()
        }

    fun cacheFor(layerIndex: Int): AmiLayerKvCache {
        require(layerIndex in caches.indices)
        return caches[layerIndex]
    }

    fun clear() {
        caches.forEach(AmiLayerKvCache::clear)
    }

    internal fun rollbackTo(position: Int) {
        require(position >= 0)
        caches.forEach { cache ->
            if (cache.size > position) {
                cache.truncate(position)
            }
        }
        require(caches.all { it.size == position }) {
            "AMI KV rollback could not restore a synchronized position"
        }
    }
}

data class AmiDecoderStackLayerTrace(
    val layerIndex: Int,
    val attention: AmiDecoderAttentionTrace,
    val ffn: AmiDecoderFfnTrace
)

data class AmiDecoderStackExecutionResult(
    val tokenId: Int,
    val position: Int,
    val output: FloatArray,
    val embeddingMappedBytes: Long,
    val layers: List<AmiDecoderStackLayerTrace>,
    val wallTimeMs: Long
) {
    val totalMappedBytes: Long
        get() = embeddingMappedBytes +
            layers.sumOf {
                it.attention.mappedBytes + it.ffn.mappedBytes
            }

    val totalMatrixWindows: Int
        get() = layers.sumOf {
            it.attention.matrixWindows + it.ffn.matrixWindows
        }
}

/**
 * Executes one token embedding through every planned decoder layer.
 *
 * KV updates are transactional across layers. If any layer fails after earlier layers have appended
 * the token, all caches are rolled back to the original position before the failure is returned.
 */
class AmiDecoderStackExecutor(
    private val maxWindowBytes: Int = 8 * 1024 * 1024
) {
    fun executeToken(
        loaded: AmiLoadedArtifact,
        graph: AmiTensorGraph,
        plan: AmiDecoderStackPlan,
        state: AmiDecoderStackState,
        tokenId: Int,
        hardware: AmiHardwareSnapshot?
    ): Result<AmiDecoderStackExecutionResult> {
        val originalPosition = runCatching { state.position }
            .getOrElse { return Result.failure(it) }

        if (originalPosition >= state.maxContextTokens) {
            return Result.failure(
                IllegalStateException(
                    "AMI decoder stack reached configured context capacity"
                )
            )
        }

        return runCatching {
            val startedNs = System.nanoTime()
            val embedding = AmiTokenEmbeddingReader(
                loaded = loaded,
                graph = graph,
                maxWindowBytes = minOf(maxWindowBytes, 1 * 1024 * 1024)
            ).read(
                plan = plan.embedding,
                tokenId = tokenId
            )
            require(embedding.values.size == plan.hiddenSize)

            var hidden = embedding.values
            val traces = ArrayList<AmiDecoderStackLayerTrace>(
                plan.layerCount
            )
            val layerExecutor = AmiDecoderLayerExecutor(maxWindowBytes)

            for (layer in plan.layers) {
                val result = layerExecutor.execute(
                    loaded = loaded,
                    graph = graph,
                    plan = layer,
                    input = hidden,
                    position = originalPosition,
                    kvCache = state.cacheFor(layer.layerIndex),
                    hardware = hardware
                ).getOrThrow()
                hidden = result.output
                traces += AmiDecoderStackLayerTrace(
                    layerIndex = layer.layerIndex,
                    attention = result.attentionTrace,
                    ffn = result.ffnTrace
                )
            }

            require(state.position == originalPosition + 1) {
                "AMI decoder stack did not advance all layer caches atomically"
            }
            require(hidden.size == plan.hiddenSize)
            require(hidden.all { it.isFinite() })

            AmiDecoderStackExecutionResult(
                tokenId = tokenId,
                position = originalPosition,
                output = hidden,
                embeddingMappedBytes = embedding.mappedBytes,
                layers = traces,
                wallTimeMs =
                    (System.nanoTime() - startedNs) / 1_000_000L
            )
        }.onFailure {
            runCatching { state.rollbackTo(originalPosition) }
        }
    }
}
