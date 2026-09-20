package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiTestFixtures
import io.amper.neuroos.core.ByteArrayModelArtifactSource
import io.amper.neuroos.core.GgufToAmiCompiler
import io.amper.neuroos.core.ModelArtifactSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufToAmi2StreamingCompilerTest {
    @Test
    fun directAndLegacyMigrationProduceSameFoundationIdentity() {
        val bytes = AmiTestFixtures.compilerReadyGguf(
            tensorBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        )
        val direct = File.createTempFile("amper-phase628-direct-", ".ami")
        val legacy = File.createTempFile("amper-phase628-legacy-", ".ami")
        val migrated = File.createTempFile("amper-phase628-migrated-", ".ami")
        try {
            direct.delete()
            migrated.delete()

            GgufToAmi2StreamingCompiler().compile(
                ByteArrayModelArtifactSource(bytes),
                direct
            ).getOrThrow()
            GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(bytes),
                legacy
            ).getOrThrow()
            Ami1ToAmi2StreamingMigrationEmitter()
                .migrate(legacy, migrated)
                .getOrThrow()

            val directLoaded = Ami2CanonicalBinaryReader().read(direct).getOrThrow()
            val migratedLoaded = Ami2CanonicalBinaryReader().read(migrated).getOrThrow()

            assertEquals(
                migratedLoaded.bundle.foundation,
                directLoaded.bundle.foundation
            )
            assertNull(directLoaded.migrationEvidence)
            assertTrue(migratedLoaded.migrationEvidence != null)
            assertEquals(
                Ami2ImportSource.GGUF_WEIGHTS,
                directLoaded.bundle.foundation.lineage.source
            )
        } finally {
            listOf(direct, legacy, migrated).forEach {
                it.setWritable(true)
                it.delete()
                File(it.parentFile, it.name + ".partial").delete()
            }
        }
    }


    @Test
    fun directCompilerDeletesOutputIfSourceChangesAfterStreaming() {
        val bytes = AmiTestFixtures.compilerReadyGguf(
            tensorBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        )
        val source = MutatingOnOpenSource(
            initial = bytes,
            mutateOnOpen = 8
        )
        val output = File.createTempFile("amper-phase628-mutating-", ".ami")
        try {
            output.delete()
            val result = GgufToAmi2StreamingCompiler().compile(source, output)

            assertTrue(result.isFailure)
            assertTrue(!output.exists())
        } finally {
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    @Test
    fun directCompilerPublishesAmi2WithoutAmi1Staging() {
        val output = File.createTempFile("amper-phase628-ami2-", ".ami")
        try {
            output.delete()
            val result = GgufToAmi2StreamingCompiler().compile(
                ByteArrayModelArtifactSource(AmiTestFixtures.compilerReadyGguf()),
                output
            ).getOrThrow()
            val loaded = Ami2CanonicalBinaryReader().read(output).getOrThrow()

            assertEquals("AMI2", output.inputStream().use { input ->
                ByteArray(4).also { input.read(it) }.toString(Charsets.US_ASCII)
            })
            assertEquals(result.output.foundation, loaded.bundle.foundation)
            assertNull(loaded.migrationEvidence)
        } finally {
            output.setWritable(true)
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    private class MutatingOnOpenSource(
        initial: ByteArray,
        private val mutateOnOpen: Int
    ) : ModelArtifactSource {
        private var bytes: ByteArray = initial.copyOf()
        private var opens: Int = 0

        override val locator: String = "memory://phase628-mutating.gguf"
        override val displayName: String = "phase628-mutating.gguf"
        override val lengthBytes: Long
            get() = bytes.size.toLong()

        override fun openStream(): InputStream {
            opens += 1
            if (opens == mutateOnOpen) {
                bytes = bytes.copyOf().also { current ->
                    current[current.lastIndex] = (current.last() xor 0x01)
                }
            }
            return ByteArrayInputStream(bytes)
        }
    }
}
