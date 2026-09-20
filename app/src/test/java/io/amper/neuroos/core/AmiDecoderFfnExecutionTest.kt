package io.amper.neuroos.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmiDecoderFfnExecutionTest {
    @Test
    fun preservedMetadataReaderRecoversRmsEpsilonWithoutMaterializingTokenizerArray() {
        val output = File.createTempFile("amper-metadata-", ".ami")
        try {
            GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(ggufWithRmsEpsilon()),
                output
            ).getOrThrow()
            val loaded = AmiBinaryReader().read(output).getOrThrow()

            val metadata = AmiPreservedGgufMetadataReader()
                .read(loaded)
                .getOrThrow()

            assertEquals(
                1e-5,
                metadata.floating("llama.attention.layer_norm_rms_epsilon")!!,
                1e-9
            )
            assertEquals(
                "llama",
                metadata.text("general.architecture")
            )
            assertEquals(
                null,
                metadata.values["tokenizer.ggml.tokens"]
            )
        } finally {
            output.setWritable(true)
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    @Test
    fun ffnPlannerBindsCanonicalGgufTensorNamesAndShapes() {
        val fixture = ffnFixture()
        try {
            val metadata = AmiGgufMetadataSnapshot(
                mapOf(
                    "llama.attention.layer_norm_rms_epsilon" to
                        AmiGgufMetadataScalar.Floating(1e-5)
                )
            )

            val plan = AmiDecoderFfnPlanner.plan(
                graph = fixture.graph,
                metadata = metadata,
                layerIndex = 0
            ).getOrThrow()

            assertEquals(2, plan.hiddenSize)
            assertEquals(2, plan.feedForwardSize)
            assertEquals("blk.0.ffn_norm.weight", plan.normWeight.name)
            assertEquals("blk.0.ffn_down.weight", plan.downWeight.name)
        } finally {
            fixture.file.delete()
        }
    }

    @Test
    fun firstDecoderFfnExecutesDirectlyFromBoundedAmiTensorWindows() {
        val fixture = ffnFixture()
        try {
            AmneProcessKernelRuntime.resetToReference()
            val metadata = AmiGgufMetadataSnapshot(
                mapOf(
                    "llama.attention.layer_norm_rms_epsilon" to
                        AmiGgufMetadataScalar.Floating(1e-5)
                )
            )
            val plan = AmiDecoderFfnPlanner.plan(
                fixture.graph,
                metadata,
                layerIndex = 0
            ).getOrThrow()
            val input = floatArrayOf(1f, 2f)

            val norm = AmneReferenceCpuKernels.rmsNormF32(
                input = input,
                weight = floatArrayOf(1f, 1f),
                epsilon = 1e-5f
            )
            val gate = AmneReferenceCpuKernels.matVecF32(
                matrixRowMajor = floatArrayOf(
                    1f, 0f,
                    0f, 1f
                ),
                rows = 2,
                columns = 2,
                vector = norm
            )
            val up = AmneReferenceCpuKernels.matVecF32(
                matrixRowMajor = floatArrayOf(
                    0.5f, 0f,
                    0f, 0.5f
                ),
                rows = 2,
                columns = 2,
                vector = norm
            )
            val activated = AmneReferenceCpuKernels.swiGluF32(gate, up)
            val down = AmneReferenceCpuKernels.matVecF32(
                matrixRowMajor = floatArrayOf(
                    1f, 0f,
                    0f, 1f
                ),
                rows = 2,
                columns = 2,
                vector = activated
            )
            val expected = FloatArray(2) { index -> input[index] + down[index] }

            val result = AmiDecoderFfnExecutor(
                maxWindowBytes = 8
            ).execute(
                loaded = fixture.loaded,
                graph = fixture.graph,
                plan = plan,
                input = input,
                hardware = null
            ).getOrThrow()

            assertEquals(expected[0], result.output[0], 1e-6f)
            assertEquals(expected[1], result.output[1], 1e-6f)
            assertEquals(6, result.trace.matrixWindows)
            assertEquals(
                AmneReferenceCpuKernels.descriptor.backendId,
                result.trace.gateBackendId
            )
            assertTrue(result.trace.mappedBytes > 0L)
        } finally {
            AmneProcessKernelRuntime.resetToReference()
            fixture.file.delete()
        }
    }

    private data class FfnFixture(
        val file: File,
        val loaded: AmiLoadedArtifact,
        val graph: AmiTensorGraph
    )

    private fun ffnFixture(): FfnFixture {
        val file = File.createTempFile("amper-ffn-", ".ami")
        val foundationOffset = 4096L
        val foundationLength = 4096L
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(foundationOffset + foundationLength)
            writeF32(
                raf,
                foundationOffset + 0L,
                floatArrayOf(1f, 1f)
            )
            writeF32(
                raf,
                foundationOffset + 64L,
                floatArrayOf(
                    1f, 0f,
                    0f, 1f
                )
            )
            writeF32(
                raf,
                foundationOffset + 128L,
                floatArrayOf(
                    0.5f, 0f,
                    0f, 0.5f
                )
            )
            writeF32(
                raf,
                foundationOffset + 192L,
                floatArrayOf(
                    1f, 0f,
                    0f, 1f
                )
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
                8192L,
                64L,
                64,
                digest
            )
        )
        val manifest = AmiManifest(
            architecture = AmiArchitectureId("llama"),
            tensorCount = 4,
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
            tensors = listOf(
                tensor(
                    name = "blk.0.ffn_norm.weight",
                    dimensions = listOf(2UL),
                    offset = 0L,
                    bytes = 8L
                ),
                tensor(
                    name = "blk.0.ffn_gate.weight",
                    dimensions = listOf(2UL, 2UL),
                    offset = 64L,
                    bytes = 16L
                ),
                tensor(
                    name = "blk.0.ffn_up.weight",
                    dimensions = listOf(2UL, 2UL),
                    offset = 128L,
                    bytes = 16L
                ),
                tensor(
                    name = "blk.0.ffn_down.weight",
                    dimensions = listOf(2UL, 2UL),
                    offset = 192L,
                    bytes = 16L
                )
            ),
            foundationSection = foundation
        )
        return FfnFixture(file, loaded, graph)
    }

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
        raf: RandomAccessFile,
        offset: Long,
        values: FloatArray
    ) {
        val bytes = ByteBuffer
            .allocate(values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(bytes::putFloat)
        raf.seek(offset)
        raf.write(bytes.array())
    }

    private fun ggufWithRmsEpsilon(): ByteArray {
        val tensorBytes = byteArrayOf(1, 2, 3, 4)
        val out = GgufTestFixtures.header(
            version = 3L,
            tensorCount = 1UL,
            metadataCount = 4UL
        )

        GgufTestFixtures.writeString(out, "general.alignment")
        GgufTestFixtures.writeU32(out, 4L)
        GgufTestFixtures.writeU32(out, 32L)

        GgufTestFixtures.writeString(out, "general.architecture")
        GgufTestFixtures.writeU32(out, 8L)
        GgufTestFixtures.writeString(out, "llama")

        GgufTestFixtures.writeString(out, "tokenizer.ggml.tokens")
        GgufTestFixtures.writeU32(out, 9L)
        GgufTestFixtures.writeU32(out, 8L)
        GgufTestFixtures.writeU64(out, 3UL)
        GgufTestFixtures.writeString(out, "<s>")
        GgufTestFixtures.writeString(out, "hello")
        GgufTestFixtures.writeString(out, "world")

        GgufTestFixtures.writeString(
            out,
            "llama.attention.layer_norm_rms_epsilon"
        )
        GgufTestFixtures.writeU32(out, 6L)
        GgufTestFixtures.writeU32(
            out,
            java.lang.Float.floatToRawIntBits(1e-5f).toLong() and 0xffff_ffffL
        )

        GgufTestFixtures.writeString(out, "token_embd.weight")
        GgufTestFixtures.writeU32(out, 1L)
        GgufTestFixtures.writeU64(out, 1UL)
        GgufTestFixtures.writeU32(out, 0L)
        GgufTestFixtures.writeU64(out, 0UL)

        GgufTestFixtures.padTo(out, 32)
        out.write(tensorBytes)
        return out.toByteArray()
    }
}
