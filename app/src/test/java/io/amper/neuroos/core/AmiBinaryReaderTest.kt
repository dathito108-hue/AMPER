package io.amper.neuroos.core

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmiBinaryReaderTest {
    @Test
    fun readerReconstructsVerifiedAmiManifestAndSections() {
        val output = File.createTempFile("amper-reader-", ".ami")
        try {
            GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(AmiTestFixtures.compilerReadyGguf()),
                output
            ).getOrThrow()

            val loaded = AmiBinaryReader().read(output).getOrThrow()

            assertEquals(AmiArchitectureId("llama"), loaded.index.manifest.architecture)
            assertEquals(3, loaded.index.manifest.vocabularySize)
            assertEquals(AmiSourceFormat.GGUF, loaded.index.manifest.source.sourceFormat)
            assertEquals(
                AmperMobileIntelligenceFormat.mandatorySections,
                loaded.index.sections.mapTo(linkedSetOf()) { it.type }
            )
            assertEquals(64, loaded.fileSha256.length)
        } finally {
            output.setWritable(true)
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    @Test
    fun readerRejectsTamperedFoundationBeforeRuntimeAdmission() {
        val output = File.createTempFile("amper-tamper-", ".ami")
        try {
            val compiled = GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(
                    AmiTestFixtures.compilerReadyGguf(
                        tensorBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
                    )
                ),
                output
            ).getOrThrow()
            val foundation = compiled.index.sections.single {
                it.type == AmiSectionType.FOUNDATION_WEIGHTS
            }

            RandomAccessFile(output, "rw").use { raf ->
                raf.seek(foundation.offset)
                raf.write(0x7f)
            }

            val result = AmiBinaryReader().read(output)

            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull()?.message?.contains("digest mismatch") == true
            )
        } finally {
            output.setWritable(true)
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    @Test
    fun foundationSupportsBoundedWindowedMmap() {
        val tensorBytes = byteArrayOf(5, 6, 7, 8)
        val output = File.createTempFile("amper-mmap-", ".ami")
        try {
            val loaded = GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(
                    AmiTestFixtures.compilerReadyGguf(tensorBytes)
                ),
                output
            ).getOrThrow()
            val foundation = loaded.index.sections.single {
                it.type == AmiSectionType.FOUNDATION_WEIGHTS
            }

            val buffer = AmiMappedSectionAccess(
                file = output,
                descriptor = foundation,
                maxWindowBytes = 1024
            ).mapWindow(relativeOffset = 0, length = tensorBytes.size)

            val observed = ByteArray(tensorBytes.size)
            buffer.get(observed)
            assertTrue(tensorBytes.contentEquals(observed))
        } finally {
            output.setWritable(true)
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    @Test
    fun mmapWindowCannotEscapeDeclaredFoundationSection() {
        val output = File.createTempFile("amper-mmap-bound-", ".ami")
        try {
            val loaded = GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(AmiTestFixtures.compilerReadyGguf()),
                output
            ).getOrThrow()
            val foundation = loaded.index.sections.single {
                it.type == AmiSectionType.FOUNDATION_WEIGHTS
            }

            val access = AmiMappedSectionAccess(
                file = output,
                descriptor = foundation,
                maxWindowBytes = 1024
            )
            val result = runCatching {
                access.mapWindow(
                    relativeOffset = foundation.length,
                    length = 1
                )
            }
            assertTrue(result.isFailure)
        } finally {
            output.setWritable(true)
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }
}
