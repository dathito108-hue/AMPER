package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiHardwareFeature
import io.amper.neuroos.core.AmiHardwareSnapshot
import io.amper.neuroos.core.ByteArrayModelArtifactSource
import io.amper.neuroos.core.GgufTestFixtures
import io.amper.neuroos.core.InferenceCancellationSignal
import io.amper.neuroos.core.AmneProcessKernelRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Amne2ExecutionSessionTest {
    @Test
    fun sessionOwnsOneVerifiedFoundationPlanAndKvLifecycle() {
        withDecoderReadyAmi2 { file ->
            AmneProcessKernelRuntime.resetToReference()
            val loaded = Ami2CanonicalBinaryReader().read(file).getOrThrow()
            val session = Amne2ExecutionSessionFactory(
                maxWindowBytes = 1024
            ).open(
                artifactFile = file,
                hardware = arm64Hardware(),
                maxContextTokens = 2
            ).getOrThrow()

            assertEquals(
                loaded.bundle.foundation.foundationId,
                session.identity.foundationId
            )
            assertEquals(
                loaded.bundle.foundation.semanticSha256,
                session.identity.semanticSha256
            )
            assertEquals(Amne2ExecutionSessionState.OPEN, session.state)
            assertEquals(0, session.position)
            assertEquals(2, session.maxContextTokens)

            val decoded = session.executeToken(tokenId = 1).getOrThrow()
            assertEquals(0, decoded.position)
            assertEquals(1, session.position)
            assertTrue(decoded.output.all { it.isFinite() })

            session.resetKv().getOrThrow()
            assertEquals(0, session.position)

            session.close().getOrThrow()
            assertEquals(Amne2ExecutionSessionState.CLOSED, session.state)
            assertTrue(session.executeToken(0).isFailure)
        }
        AmneProcessKernelRuntime.resetToReference()
    }

    @Test
    fun cancelledRequestCannotAdvanceSessionKv() {
        withDecoderReadyAmi2 { file ->
            AmneProcessKernelRuntime.resetToReference()
            val session = Amne2ExecutionSessionFactory(
                maxWindowBytes = 1024
            ).open(
                artifactFile = file,
                hardware = arm64Hardware(),
                maxContextTokens = 2
            ).getOrThrow()

            val cancellation = InferenceCancellationSignal().also { it.cancel() }
            val result = session.executeToken(
                tokenId = 0,
                cancellation = cancellation
            )

            assertTrue(result.isFailure)
            assertEquals(0, session.position)
            session.close().getOrThrow()
        }
        AmneProcessKernelRuntime.resetToReference()
    }

    private fun arm64Hardware() = AmiHardwareSnapshot(
        features = setOf(
            AmiHardwareFeature.ARM64,
            AmiHardwareFeature.NEON
        ),
        logicalProcessors = 8,
        memoryClassMb = 512,
        lowRamDevice = false
    )

    private inline fun withDecoderReadyAmi2(block: (File) -> Unit) {
        val file = File.createTempFile("amper-phase633-", ".ami")
        try {
            file.delete()
            GgufToAmi2StreamingCompiler().compile(
                source = ByteArrayModelArtifactSource(decoderReadyGguf()),
                destination = file
            ).getOrThrow()
            block(file)
        } finally {
            file.setWritable(true)
            file.delete()
            File(file.parentFile, file.name + ".partial").delete()
        }
    }

    private data class TensorSpec(
        val name: String,
        val dimensions: List<ULong>,
        val values: FloatArray,
        val offset: Long
    )

    private fun decoderReadyGguf(): ByteArray {
        val tensors = ArrayList<TensorSpec>()
        var offset = 0L

        fun add(
            name: String,
            dimensions: List<ULong>,
            values: FloatArray
        ) {
            tensors += TensorSpec(name, dimensions, values, offset)
            offset += 256L
        }

        add(
            "token_embd.weight",
            listOf(4UL, 3UL),
            floatArrayOf(
                1f, 2f, 3f, 4f,
                5f, 6f, 7f, 8f,
                9f, 10f, 11f, 12f
            )
        )
        add("blk.0.attn_norm.weight", listOf(4UL), ones4())
        add("blk.0.attn_q.weight", listOf(4UL, 4UL), identity4())
        add(
            "blk.0.attn_k.weight",
            listOf(4UL, 2UL),
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f
            )
        )
        add(
            "blk.0.attn_v.weight",
            listOf(4UL, 2UL),
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f
            )
        )
        add("blk.0.attn_output.weight", listOf(4UL, 4UL), identity4())
        add("blk.0.ffn_norm.weight", listOf(4UL), ones4())
        add("blk.0.ffn_gate.weight", listOf(4UL, 4UL), identity4())
        add(
            "blk.0.ffn_up.weight",
            listOf(4UL, 4UL),
            floatArrayOf(
                0.5f, 0f, 0f, 0f,
                0f, 0.5f, 0f, 0f,
                0f, 0f, 0.5f, 0f,
                0f, 0f, 0f, 0.5f
            )
        )
        add("blk.0.ffn_down.weight", listOf(4UL, 4UL), identity4())
        add("output_norm.weight", listOf(4UL), ones4())

        val out = GgufTestFixtures.header(
            version = 3L,
            tensorCount = tensors.size.toULong(),
            metadataCount = 9UL
        )

        metadataU32(out, "general.alignment", 32L)
        metadataString(out, "general.architecture", "llama")
        metadataStringArray(
            out,
            "tokenizer.ggml.tokens",
            listOf("<s>", "hello", "world")
        )
        metadataU32(out, "llama.block_count", 1L)
        metadataU32(out, "llama.context_length", 4L)
        metadataF32(out, "llama.attention.layer_norm_rms_epsilon", 1e-5f)
        metadataU32(out, "llama.attention.head_count", 2L)
        metadataU32(out, "llama.attention.head_count_kv", 1L)
        metadataF32(out, "llama.rope.freq_base", 10_000f)

        tensors.forEach { tensor ->
            GgufTestFixtures.writeString(out, tensor.name)
            GgufTestFixtures.writeU32(out, tensor.dimensions.size.toLong())
            tensor.dimensions.forEach { dimension ->
                GgufTestFixtures.writeU64(out, dimension)
            }
            GgufTestFixtures.writeU32(out, 0L) // F32
            GgufTestFixtures.writeU64(out, tensor.offset.toULong())
        }

        GgufTestFixtures.padTo(out, 32)
        val foundation = ByteArray((tensors.size * 256).coerceAtLeast(256))
        tensors.forEach { tensor ->
            val bytes = ByteBuffer
                .allocate(tensor.values.size * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            tensor.values.forEach(bytes::putFloat)
            bytes.array().copyInto(
                foundation,
                destinationOffset = tensor.offset.toInt()
            )
        }
        out.write(foundation)
        return out.toByteArray()
    }

    private fun metadataU32(
        out: ByteArrayOutputStream,
        key: String,
        value: Long
    ) {
        GgufTestFixtures.writeString(out, key)
        GgufTestFixtures.writeU32(out, 4L)
        GgufTestFixtures.writeU32(out, value)
    }

    private fun metadataF32(
        out: ByteArrayOutputStream,
        key: String,
        value: Float
    ) {
        GgufTestFixtures.writeString(out, key)
        GgufTestFixtures.writeU32(out, 6L)
        GgufTestFixtures.writeU32(
            out,
            value.toRawBits().toLong() and 0xffff_ffffL
        )
    }

    private fun metadataString(
        out: ByteArrayOutputStream,
        key: String,
        value: String
    ) {
        GgufTestFixtures.writeString(out, key)
        GgufTestFixtures.writeU32(out, 8L)
        GgufTestFixtures.writeString(out, value)
    }

    private fun metadataStringArray(
        out: ByteArrayOutputStream,
        key: String,
        values: List<String>
    ) {
        GgufTestFixtures.writeString(out, key)
        GgufTestFixtures.writeU32(out, 9L)
        GgufTestFixtures.writeU32(out, 8L)
        GgufTestFixtures.writeU64(out, values.size.toULong())
        values.forEach { GgufTestFixtures.writeString(out, it) }
    }

    private fun ones4() = floatArrayOf(1f, 1f, 1f, 1f)

    private fun identity4() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )
}
