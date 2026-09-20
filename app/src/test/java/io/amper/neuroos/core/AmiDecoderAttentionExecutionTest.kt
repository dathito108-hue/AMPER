package io.amper.neuroos.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmiDecoderAttentionExecutionTest {
    @Test
    fun plannerBindsGqaAttentionGeometryFromPreservedMetadata() {
        val fixture = decoderFixture()
        try {
            val plan = AmiDecoderAttentionPlanner.plan(
                graph = fixture.graph,
                metadata = metadata(),
                layerIndex = 0
            ).getOrThrow()

            assertEquals(4, plan.hiddenSize)
            assertEquals(2, plan.headCount)
            assertEquals(1, plan.kvHeadCount)
            assertEquals(2, plan.headDimension)
            assertEquals(4, plan.queryWidth)
            assertEquals(2, plan.kvWidth)
            assertEquals("blk.0.attn_q.weight", plan.queryWeight.name)
            assertEquals("blk.0.attn_output.weight", plan.outputWeight.name)
        } finally {
            fixture.file.delete()
        }
    }

    @Test
    fun firstTokenAttentionUsesRealValueProjectionAndAppendsKv() {
        val fixture = decoderFixture()
        try {
            AmneProcessKernelRuntime.resetToReference()
            val plan = AmiDecoderAttentionPlanner.plan(
                fixture.graph,
                metadata(),
                0
            ).getOrThrow()
            val cache = AmiLayerKvCache(
                layerIndex = 0,
                kvWidth = plan.kvWidth,
                maxTokens = 4
            )
            val input = floatArrayOf(1f, 2f, 3f, 4f)

            val norm = AmneReferenceCpuKernels.rmsNormF32(
                input,
                floatArrayOf(1f, 1f, 1f, 1f),
                1e-5f
            )
            val value = AmneReferenceCpuKernels.matVecF32(
                matrixRowMajor = floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f
                ),
                rows = 2,
                columns = 4,
                vector = norm
            )
            val expectedContext = floatArrayOf(
                value[0], value[1], value[0], value[1]
            )
            val expected = FloatArray(4) { index ->
                input[index] + expectedContext[index]
            }

            val result = AmiDecoderAttentionExecutor(
                maxWindowBytes = 64
            ).execute(
                loaded = fixture.loaded,
                graph = fixture.graph,
                plan = plan,
                input = input,
                position = 0,
                kvCache = cache,
                hardware = null
            ).getOrThrow()

            assertEquals(1, cache.size)
            assertEquals(1, result.trace.contextTokens)
            expected.indices.forEach { index ->
                assertEquals(expected[index], result.output[index], 1e-5f)
            }
            assertEquals(
                AmneReferenceCpuKernels.descriptor.backendId,
                result.trace.dotBackendId
            )
            assertTrue(result.trace.mappedBytes > 0L)
        } finally {
            AmneProcessKernelRuntime.resetToReference()
            fixture.file.delete()
        }
    }

    @Test
    fun secondTokenAttendsOverExistingCausalKvHistory() {
        val fixture = decoderFixture()
        try {
            AmneProcessKernelRuntime.resetToReference()
            val plan = AmiDecoderAttentionPlanner.plan(
                fixture.graph,
                metadata(),
                0
            ).getOrThrow()
            val cache = AmiLayerKvCache(0, plan.kvWidth, 4)
            val executor = AmiDecoderAttentionExecutor(maxWindowBytes = 64)

            executor.execute(
                fixture.loaded,
                fixture.graph,
                plan,
                floatArrayOf(1f, 0f, 0f, 1f),
                0,
                cache,
                null
            ).getOrThrow()

            val second = executor.execute(
                fixture.loaded,
                fixture.graph,
                plan,
                floatArrayOf(0.5f, 1f, -0.5f, 2f),
                1,
                cache,
                null
            ).getOrThrow()

            assertEquals(2, cache.size)
            assertEquals(2, second.trace.contextTokens)
            assertTrue(second.output.all { it.isFinite() })
        } finally {
            AmneProcessKernelRuntime.resetToReference()
            fixture.file.delete()
        }
    }

    @Test
    fun completeDecoderLayerRunsAttentionThenFfnFromAmiWeights() {
        val fixture = decoderFixture()
        try {
            AmneProcessKernelRuntime.resetToReference()
            val plan = AmiDecoderLayerPlanner.plan(
                fixture.graph,
                metadata(),
                0
            ).getOrThrow()
            val cache = AmiLayerKvCache(
                layerIndex = 0,
                kvWidth = plan.attention.kvWidth,
                maxTokens = 4
            )

            val result = AmiDecoderLayerExecutor(
                maxWindowBytes = 64
            ).execute(
                loaded = fixture.loaded,
                graph = fixture.graph,
                plan = plan,
                input = floatArrayOf(1f, 2f, 3f, 4f),
                position = 0,
                kvCache = cache,
                hardware = null
            ).getOrThrow()

            assertEquals(4, result.output.size)
            assertTrue(result.output.all { it.isFinite() })
            assertEquals(1, result.attentionTrace.contextTokens)
            assertEquals(0, result.ffnTrace.layerIndex)
            assertTrue(result.attentionTrace.matrixWindows > 0)
            assertTrue(result.ffnTrace.matrixWindows > 0)
        } finally {
            AmneProcessKernelRuntime.resetToReference()
            fixture.file.delete()
        }
    }

    @Test
    fun kvCacheFailsClosedInsteadOfSilentlyEvictingContext() {
        val cache = AmiLayerKvCache(
            layerIndex = 0,
            kvWidth = 2,
            maxTokens = 1
        )
        cache.append(
            position = 0,
            key = floatArrayOf(1f, 2f),
            value = floatArrayOf(3f, 4f)
        )

        val result = runCatching {
            cache.append(
                position = 1,
                key = floatArrayOf(5f, 6f),
                value = floatArrayOf(7f, 8f)
            )
        }

        assertTrue(result.isFailure)
        assertEquals(1, cache.size)
    }

    private data class DecoderFixture(
        val file: File,
        val loaded: AmiLoadedArtifact,
        val graph: AmiTensorGraph
    )

    private fun metadata() = AmiGgufMetadataSnapshot(
        mapOf(
            "llama.attention.layer_norm_rms_epsilon" to
                AmiGgufMetadataScalar.Floating(1e-5),
            "llama.attention.head_count" to
                AmiGgufMetadataScalar.Unsigned(2UL),
            "llama.attention.head_count_kv" to
                AmiGgufMetadataScalar.Unsigned(1UL),
            "llama.rope.freq_base" to
                AmiGgufMetadataScalar.Floating(10_000.0)
        )
    )

    private fun decoderFixture(): DecoderFixture {
        val file = File.createTempFile("amper-decoder-layer-", ".ami")
        val foundationOffset = 4096L
        val foundationLength = 8192L

        val tensors = mutableListOf<AmiTensorDescriptor>()
        var cursor = 0L

        fun addVector(name: String, values: FloatArray) {
            writeF32(file, foundationOffset + cursor, values)
            tensors += tensor(
                name,
                listOf(values.size.toULong()),
                cursor,
                values.size.toLong() * 4L
            )
            cursor += 64L
        }

        fun addMatrix(
            name: String,
            rows: Int,
            columns: Int,
            values: FloatArray
        ) {
            require(values.size == rows * columns)
            writeF32(file, foundationOffset + cursor, values)
            tensors += tensor(
                name,
                listOf(columns.toULong(), rows.toULong()),
                cursor,
                values.size.toLong() * 4L
            )
            cursor += 128L
        }

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(foundationOffset + foundationLength)
        }

        addVector(
            "blk.0.attn_norm.weight",
            floatArrayOf(1f, 1f, 1f, 1f)
        )
        addMatrix(
            "blk.0.attn_q.weight",
            4,
            4,
            identity4()
        )
        addMatrix(
            "blk.0.attn_k.weight",
            2,
            4,
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f
            )
        )
        addMatrix(
            "blk.0.attn_v.weight",
            2,
            4,
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f
            )
        )
        addMatrix(
            "blk.0.attn_output.weight",
            4,
            4,
            identity4()
        )

        addVector(
            "blk.0.ffn_norm.weight",
            floatArrayOf(1f, 1f, 1f, 1f)
        )
        addMatrix(
            "blk.0.ffn_gate.weight",
            4,
            4,
            identity4()
        )
        addMatrix(
            "blk.0.ffn_up.weight",
            4,
            4,
            floatArrayOf(
                0.5f, 0f, 0f, 0f,
                0f, 0.5f, 0f, 0f,
                0f, 0f, 0.5f, 0f,
                0f, 0f, 0f, 0.5f
            )
        )
        addMatrix(
            "blk.0.ffn_down.weight",
            4,
            4,
            identity4()
        )

        val digest = "a".repeat(64)
        val foundation = AmiSectionDescriptor(
            type = AmiSectionType.FOUNDATION_WEIGHTS,
            offset = foundationOffset,
            length = foundationLength,
            alignmentBytes = 4096,
            sha256 = digest
        )
        val sections = listOf(
            AmiSectionDescriptor(
                AmiSectionType.MANIFEST,
                0L,
                64L,
                64,
                digest
            ),
            AmiSectionDescriptor(
                AmiSectionType.TOKENIZER,
                64L,
                64L,
                64,
                digest
            ),
            AmiSectionDescriptor(
                AmiSectionType.GRAPH_IR,
                128L,
                64L,
                64,
                digest
            ),
            AmiSectionDescriptor(
                AmiSectionType.TENSOR_INDEX,
                192L,
                64L,
                64,
                digest
            ),
            foundation,
            AmiSectionDescriptor(
                AmiSectionType.INTEGRITY,
                12288L,
                64L,
                64,
                digest
            )
        )
        val manifest = AmiManifest(
            architecture = AmiArchitectureId("llama"),
            tensorCount = tensors.size,
            vocabularySize = 3,
            source = AmiSourceLineage(
                sourceFormat = AmiSourceFormat.GGUF,
                sourceSha256 = digest,
                sourceByteLength = 1024L,
                sourcePrecisionPreserved = true
            )
        )
        val loaded = AmiLoadedArtifact(
            file = file,
            index = AmiContainerIndex(manifest, sections),
            fileSha256 = digest
        )
        val graph = AmiTensorGraph(
            architecture = AmiArchitectureId("llama"),
            tensors = tensors,
            foundationSection = foundation
        )
        return DecoderFixture(file, loaded, graph)
    }

    private fun identity4() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    private fun tensor(
        name: String,
        dimensions: List<ULong>,
        offset: Long,
        bytes: Long
    ) = AmiTensorDescriptor(
        name = name,
        dimensions = dimensions,
        sourceEncodingType = 0L,
        foundationOffset = offset,
        storageBytes = bytes
    )

    private fun writeF32(
        file: File,
        offset: Long,
        values: FloatArray
    ) {
        val bytes = ByteBuffer
            .allocate(values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(bytes::putFloat)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes.array())
        }
    }
}
