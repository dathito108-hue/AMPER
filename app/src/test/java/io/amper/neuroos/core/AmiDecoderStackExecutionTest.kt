package io.amper.neuroos.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmiDecoderStackExecutionTest {
    @Test
    fun stackPlannerBindsEmbeddingLayerCountAndContextFromMetadata() {
        val fixture = stackFixture()
        try {
            val plan = AmiDecoderStackPlanner.plan(
                fixture.graph,
                metadata()
            ).getOrThrow()

            assertEquals(4, plan.hiddenSize)
            assertEquals(3, plan.vocabularySize)
            assertEquals(2, plan.layerCount)
            assertEquals(4, plan.maxContextTokens)
            assertEquals(
                listOf(0, 1),
                plan.layers.map { it.layerIndex }
            )
        } finally {
            fixture.file.delete()
        }
    }

    @Test
    fun embeddingLookupMapsOnlyRequestedF32Row() {
        val fixture = stackFixture()
        try {
            val plan = AmiDecoderStackPlanner.plan(
                fixture.graph,
                metadata()
            ).getOrThrow()

            val row = AmiTokenEmbeddingReader(
                fixture.loaded,
                fixture.graph,
                maxWindowBytes = 64
            ).read(
                plan.embedding,
                tokenId = 1
            )

            assertArrayEquals(
                floatArrayOf(5f, 6f, 7f, 8f),
                row.values,
                0f
            )
            assertEquals(16L, row.mappedBytes)
            assertEquals(AmneTensorEncoding.F32, row.encoding)
        } finally {
            fixture.file.delete()
        }
    }

    @Test
    fun q4EmbeddingRowDecodesWithoutMaterializingVocabularyMatrix() {
        val fixture = q4EmbeddingFixture()
        try {
            val row = AmiTokenEmbeddingReader(
                fixture.loaded,
                fixture.graph,
                maxWindowBytes = 64
            ).read(
                fixture.plan,
                tokenId = 1
            )

            assertEquals(32, row.values.size)
            // scale=1.0; second row stores low nibble 9 -> +1 and high nibble 10 -> +2.
            for (index in 0 until 16) {
                assertEquals(1f, row.values[index], 0f)
            }
            for (index in 16 until 32) {
                assertEquals(2f, row.values[index], 0f)
            }
            assertEquals(18L, row.mappedBytes)
        } finally {
            fixture.file.delete()
        }
    }

    @Test
    fun oneTokenRunsEmbeddingThroughEveryDecoderLayerAndOwnsPerLayerKv() {
        val fixture = stackFixture()
        try {
            AmneProcessKernelRuntime.resetToReference()
            val plan = AmiDecoderStackPlanner.plan(
                fixture.graph,
                metadata()
            ).getOrThrow()
            val state = AmiDecoderStackState(
                plan = plan,
                maxContextTokens = 2
            )

            val result = AmiDecoderStackExecutor(
                maxWindowBytes = 64
            ).executeToken(
                loaded = fixture.loaded,
                graph = fixture.graph,
                plan = plan,
                state = state,
                tokenId = 1,
                hardware = null
            ).getOrThrow()

            assertEquals(0, result.position)
            assertEquals(1, state.position)
            assertEquals(2, result.layers.size)
            assertEquals(1, state.cacheFor(0).size)
            assertEquals(1, state.cacheFor(1).size)
            assertEquals(4, result.output.size)
            assertTrue(result.output.all { it.isFinite() })
            assertTrue(result.totalMappedBytes > result.embeddingMappedBytes)
            assertTrue(result.totalMatrixWindows > 0)
        } finally {
            AmneProcessKernelRuntime.resetToReference()
            fixture.file.delete()
        }
    }

    @Test
    fun failedLaterLayerRollsBackEveryKvCacheToOriginalPosition() {
        val fixture = stackFixture(corruptLayer1Down = true)
        try {
            AmneProcessKernelRuntime.resetToReference()
            val plan = AmiDecoderStackPlanner.plan(
                fixture.graph,
                metadata()
            ).getOrThrow()
            val state = AmiDecoderStackState(plan, maxContextTokens = 2)

            val result = AmiDecoderStackExecutor(
                maxWindowBytes = 64
            ).executeToken(
                loaded = fixture.loaded,
                graph = fixture.graph,
                plan = plan,
                state = state,
                tokenId = 0,
                hardware = null
            )

            assertTrue(result.isFailure)
            assertEquals(0, state.position)
            assertEquals(0, state.cacheFor(0).size)
            assertEquals(0, state.cacheFor(1).size)
        } finally {
            AmneProcessKernelRuntime.resetToReference()
            fixture.file.delete()
        }
    }

    @Test
    fun stackStateDoesNotSilentlyAdvanceBeyondConfiguredContext() {
        val fixture = stackFixture()
        try {
            AmneProcessKernelRuntime.resetToReference()
            val plan = AmiDecoderStackPlanner.plan(
                fixture.graph,
                metadata()
            ).getOrThrow()
            val state = AmiDecoderStackState(plan, maxContextTokens = 1)
            val executor = AmiDecoderStackExecutor(maxWindowBytes = 64)

            executor.executeToken(
                fixture.loaded,
                fixture.graph,
                plan,
                state,
                tokenId = 0,
                hardware = null
            ).getOrThrow()

            val second = executor.executeToken(
                fixture.loaded,
                fixture.graph,
                plan,
                state,
                tokenId = 1,
                hardware = null
            )

            assertTrue(second.isFailure)
            assertEquals(1, state.position)
        } finally {
            AmneProcessKernelRuntime.resetToReference()
            fixture.file.delete()
        }
    }

    private data class StackFixture(
        val file: File,
        val loaded: AmiLoadedArtifact,
        val graph: AmiTensorGraph
    )

    private data class Q4Fixture(
        val file: File,
        val loaded: AmiLoadedArtifact,
        val graph: AmiTensorGraph,
        val plan: AmiTokenEmbeddingPlan
    )

    private fun metadata() = AmiGgufMetadataSnapshot(
        mapOf(
            "llama.block_count" to
                AmiGgufMetadataScalar.Unsigned(2UL),
            "llama.context_length" to
                AmiGgufMetadataScalar.Unsigned(4UL),
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

    private fun stackFixture(
        corruptLayer1Down: Boolean = false
    ): StackFixture {
        val file = File.createTempFile("amper-stack-", ".ami")
        val foundationOffset = 4096L
        val foundationLength = 16_384L
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(foundationOffset + foundationLength + 64L)
        }

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

        addMatrix(
            "token_embd.weight",
            rows = 3,
            columns = 4,
            values = floatArrayOf(
                1f, 2f, 3f, 4f,
                5f, 6f, 7f, 8f,
                9f, 10f, 11f, 12f
            )
        )

        for (layer in 0..1) {
            addVector(
                "blk.$layer.attn_norm.weight",
                floatArrayOf(1f, 1f, 1f, 1f)
            )
            addMatrix(
                "blk.$layer.attn_q.weight",
                4,
                4,
                identity4()
            )
            addMatrix(
                "blk.$layer.attn_k.weight",
                2,
                4,
                floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f
                )
            )
            addMatrix(
                "blk.$layer.attn_v.weight",
                2,
                4,
                floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f
                )
            )
            addMatrix(
                "blk.$layer.attn_output.weight",
                4,
                4,
                identity4()
            )
            addVector(
                "blk.$layer.ffn_norm.weight",
                floatArrayOf(1f, 1f, 1f, 1f)
            )
            addMatrix(
                "blk.$layer.ffn_gate.weight",
                4,
                4,
                identity4()
            )
            addMatrix(
                "blk.$layer.ffn_up.weight",
                4,
                4,
                floatArrayOf(
                    0.5f, 0f, 0f, 0f,
                    0f, 0.5f, 0f, 0f,
                    0f, 0f, 0.5f, 0f,
                    0f, 0f, 0f, 0.5f
                )
            )
            val down = identity4()
            if (corruptLayer1Down && layer == 1) {
                down[0] = Float.NaN
            }
            addMatrix(
                "blk.$layer.ffn_down.weight",
                4,
                4,
                down
            )
        }

        val digest = "a".repeat(64)
        val foundation = AmiSectionDescriptor(
            type = AmiSectionType.FOUNDATION_WEIGHTS,
            offset = foundationOffset,
            length = foundationLength,
            alignmentBytes = 4096,
            sha256 = digest
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
            index = AmiContainerIndex(
                manifest,
                mandatorySections(foundation, digest)
            ),
            fileSha256 = digest
        )
        val graph = AmiTensorGraph(
            architecture = AmiArchitectureId("llama"),
            tensors = tensors,
            foundationSection = foundation
        )
        return StackFixture(file, loaded, graph)
    }

    private fun q4EmbeddingFixture(): Q4Fixture {
        val file = File.createTempFile("amper-q4-embedding-", ".ami")
        val foundationOffset = 4096L
        val foundationLength = 4096L
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(foundationOffset + foundationLength + 64L)
            raf.seek(foundationOffset)
            // row 0: scale=1.0; all zero values (nibble 8).
            raf.write(byteArrayOf(0x00, 0x3c))
            repeat(16) { raf.write(0x88) }
            // row 1: scale=1.0; low=9 -> +1, high=10 -> +2.
            raf.write(byteArrayOf(0x00, 0x3c))
            repeat(16) { raf.write(0xa9) }
        }

        val digest = "a".repeat(64)
        val foundation = AmiSectionDescriptor(
            AmiSectionType.FOUNDATION_WEIGHTS,
            foundationOffset,
            foundationLength,
            4096,
            digest
        )
        val embedding = AmiTensorDescriptor(
            name = "token_embd.weight",
            dimensions = listOf(32UL, 2UL),
            sourceEncodingType = 2L,
            foundationOffset = 0L,
            storageBytes = 36L
        )
        val manifest = AmiManifest(
            architecture = AmiArchitectureId("llama"),
            tensorCount = 1,
            vocabularySize = 2,
            source = AmiSourceLineage(
                AmiSourceFormat.GGUF,
                digest,
                1024L,
                true
            )
        )
        val loaded = AmiLoadedArtifact(
            file,
            AmiContainerIndex(
                manifest,
                mandatorySections(foundation, digest)
            ),
            digest
        )
        val graph = AmiTensorGraph(
            AmiArchitectureId("llama"),
            listOf(embedding),
            foundation
        )
        return Q4Fixture(
            file,
            loaded,
            graph,
            AmiTokenEmbeddingPlan(
                tensor = embedding,
                hiddenSize = 32,
                vocabularySize = 2,
                encoding = AmneTensorEncoding.Q4_0
            )
        )
    }

    private fun mandatorySections(
        foundation: AmiSectionDescriptor,
        digest: String
    ) = listOf(
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
            foundation.endExclusive,
            64L,
            64,
            digest
        )
    )

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
