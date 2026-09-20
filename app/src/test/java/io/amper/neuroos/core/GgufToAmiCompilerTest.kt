package io.amper.neuroos.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufToAmiCompilerTest {
    @Test
    fun sourceExactCompilerCopiesFoundationTensorBytesExactly() {
        val tensorBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val sourceBytes = compilerFixture(tensorBytes)
        val output = File.createTempFile("amper-source-exact-", ".ami")
        try {
            val compiled = GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(
                    sourceBytes,
                    displayName = "foundation.gguf"
                ),
                output
            ).getOrThrow()

            assertEquals(AmiArchitectureId("llama"), compiled.index.manifest.architecture)
            assertEquals(3, compiled.index.manifest.vocabularySize)
            assertEquals(AmiPrecisionPolicy.SOURCE_EXACT, compiled.index.manifest.canonicalPrecisionPolicy)
            assertTrue(compiled.index.manifest.source.sourcePrecisionPreserved)

            val foundation = compiled.index.sections.single {
                it.type == AmiSectionType.FOUNDATION_WEIGHTS
            }
            assertEquals(sha256(tensorBytes), foundation.sha256)
            assertEquals(sha256(tensorBytes), compiled.foundationSha256)

            val copied = ByteArray(tensorBytes.size)
            RandomAccessFile(output, "r").use { raf ->
                raf.seek(foundation.offset)
                raf.readFully(copied)
            }
            assertTrue(tensorBytes.contentEquals(copied))

            val magic = ByteArray(4)
            RandomAccessFile(output, "r").use { raf ->
                raf.readFully(magic)
            }
            assertEquals("AMI1", magic.toString(Charsets.US_ASCII))
        } finally {
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    @Test
    fun compilerCreatesIndependentAmiTensorIndex() {
        val output = File.createTempFile("amper-index-", ".ami")
        try {
            val compiled = GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(compilerFixture(byteArrayOf(1, 2, 3, 4))),
                output
            ).getOrThrow()

            val tensorIndex = compiled.index.sections.single {
                it.type == AmiSectionType.TENSOR_INDEX
            }
            assertTrue(tensorIndex.length > 0)
            assertTrue(tensorIndex.offset >= AmiBinaryLayout.HEADER_REGION_BYTES)
            assertEquals(
                1,
                compiled.index.sections.count { it.type == AmiSectionType.FOUNDATION_WEIGHTS }
            )
        } finally {
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    @Test
    fun compilerPreservesOriginalGgufLineage() {
        val sourceBytes = compilerFixture(byteArrayOf(9, 8, 7, 6))
        val output = File.createTempFile("amper-lineage-", ".ami")
        try {
            val compiled = GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(sourceBytes),
                output
            ).getOrThrow()

            assertEquals(AmiSourceFormat.GGUF, compiled.index.manifest.source.sourceFormat)
            assertEquals(sha256(sourceBytes), compiled.sourceSha256)
            assertEquals(sha256(sourceBytes), compiled.index.manifest.source.sourceSha256)
            assertEquals(sourceBytes.size.toLong(), compiled.index.manifest.source.sourceByteLength)
        } finally {
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    @Test
    fun compilerFailsClosedWithoutArchitectureMetadata() {
        val bytes = compilerFixture(
            tensorBytes = byteArrayOf(1, 2, 3, 4),
            includeArchitecture = false
        )
        val output = File.createTempFile("amper-no-arch-", ".ami")
        try {
            val result = GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(bytes),
                output
            )
            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull()?.message?.contains("general.architecture") == true
            )
        } finally {
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    @Test
    fun compilerFailsClosedWithoutTokenizerVocabulary() {
        val bytes = compilerFixture(
            tensorBytes = byteArrayOf(1, 2, 3, 4),
            includeTokenizer = false
        )
        val output = File.createTempFile("amper-no-tokenizer-", ".ami")
        try {
            val result = GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(bytes),
                output
            )
            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull()?.message?.contains("tokenizer.ggml.tokens") == true
            )
        } finally {
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    private fun compilerFixture(
        tensorBytes: ByteArray,
        includeArchitecture: Boolean = true,
        includeTokenizer: Boolean = true
    ): ByteArray {
        require(tensorBytes.size == 4)
        val metadataCount =
            1 + (if (includeArchitecture) 1 else 0) + (if (includeTokenizer) 1 else 0)
        val out = GgufTestFixtures.header(
            version = 3L,
            tensorCount = 1UL,
            metadataCount = metadataCount.toULong()
        )

        GgufTestFixtures.writeString(out, "general.alignment")
        GgufTestFixtures.writeU32(out, 4L)
        GgufTestFixtures.writeU32(out, 32L)

        if (includeArchitecture) {
            GgufTestFixtures.writeString(out, "general.architecture")
            GgufTestFixtures.writeU32(out, 8L)
            GgufTestFixtures.writeString(out, "llama")
        }

        if (includeTokenizer) {
            GgufTestFixtures.writeString(out, "tokenizer.ggml.tokens")
            GgufTestFixtures.writeU32(out, 9L)
            GgufTestFixtures.writeU32(out, 8L)
            GgufTestFixtures.writeU64(out, 3UL)
            GgufTestFixtures.writeString(out, "<s>")
            GgufTestFixtures.writeString(out, "hello")
            GgufTestFixtures.writeString(out, "world")
        }

        GgufTestFixtures.writeString(out, "token_embd.weight")
        GgufTestFixtures.writeU32(out, 1L)
        GgufTestFixtures.writeU64(out, 1UL)
        GgufTestFixtures.writeU32(out, 0L)
        GgufTestFixtures.writeU64(out, 0UL)

        GgufTestFixtures.padTo(out, 32)
        out.write(tensorBytes)
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
