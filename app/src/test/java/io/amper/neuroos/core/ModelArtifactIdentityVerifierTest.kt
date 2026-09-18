package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

class ModelArtifactIdentityVerifierTest {
    @Test
    fun verifySourceAcceptsExactInstalledArtifact() {
        val bytes = GgufTestFixtures.validArtifact(payload = "installed-brain".toByteArray())
        val locator = "memory://installed-brain.gguf"
        val source = ByteArrayModelArtifactSource(bytes, "brain.gguf", locator)
        val model = installed(bytes, locator)

        val inspection = ModelArtifactIdentityVerifier().verifySource(model, source).getOrThrow()

        assertEquals(model.sha256, inspection.sha256)
        assertEquals(model.lengthBytes, inspection.lengthBytes)
        assertEquals(model.tensorCount, inspection.header.tensorCount)
    }

    @Test
    fun verifySourceRejectsResolverLocatorSubstitutionEvenWithSameBytes() {
        val bytes = GgufTestFixtures.validArtifact(payload = "same-bytes".toByteArray())
        val model = installed(bytes, "memory://installed.gguf")
        val substituted = ByteArrayModelArtifactSource(bytes, "brain.gguf", "memory://other.gguf")

        val result = ModelArtifactIdentityVerifier().verifySource(model, substituted)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("locator") == true)
    }

    @Test
    fun verifyNativePathRejectsArtifactMutationSinceInstall() {
        val admitted = GgufTestFixtures.validArtifact(payload = "artifact-before-mutation".toByteArray())
        val mutated = GgufTestFixtures.validArtifact(payload = "artifact-after--mutation".toByteArray())
        val locator = "content://models/brain.gguf"
        val model = installed(admitted, locator)
        val source = StaticLocatorSource(locator, admitted)
        val path = Files.createTempFile("amper-model-identity", ".gguf")

        try {
            Files.write(path, mutated)

            val result = ModelArtifactIdentityVerifier().verifyNativePath(
                model = model,
                source = source,
                nativePath = path.toString()
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("SHA-256") == true)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private class StaticLocatorSource(
        override val locator: String,
        private val bytes: ByteArray
    ) : ModelArtifactSource {
        override val displayName: String = "brain.gguf"
        override val lengthBytes: Long = bytes.size.toLong()
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun installed(bytes: ByteArray, locator: String): InstalledModel = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId("brain"),
            format = "gguf",
            capabilities = setOf(CapabilityId("reasoning")),
            local = true
        ),
        displayName = "brain.gguf",
        locator = locator,
        lengthBytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
        ggufVersion = 3L,
        tensorCount = 7UL,
        metadataKeyValueCount = 11UL,
        installedAtEpochMs = 1L
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
