package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ModelRuntimeIdentityTest {
    @Test
    fun bindAcceptsExactSourceAndCapturesDurableIdentity() {
        val model = installed(locator = "content://models/brain.gguf", length = 4096L)
        val source = StaticSource(model.locator, 4096L)

        val identity = ModelRuntimeIdentity.bind(model, source)

        assertEquals(model.descriptor.id, identity.modelId)
        assertEquals(model.locator, identity.locator)
        assertEquals(model.sha256, identity.sha256)
        assertEquals(model.lengthBytes, identity.lengthBytes)
        assertEquals(model.ggufVersion, identity.ggufVersion)
        assertEquals(model.tensorCount, identity.tensorCount)
        assertEquals(model.metadataKeyValueCount, identity.metadataKeyValueCount)
    }

    @Test
    fun bindRejectsLocatorSubstitutionBeforeWarmReuse() {
        val model = installed(locator = "content://models/brain.gguf", length = 4096L)
        val substituted = StaticSource("content://models/other.gguf", 4096L)

        val failure = runCatching { ModelRuntimeIdentity.bind(model, substituted) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("locator") == true)
    }

    @Test
    fun bindRejectsConflictingProviderDeclaredLength() {
        val model = installed(locator = "content://models/brain.gguf", length = 4096L)
        val changed = StaticSource(model.locator, 8192L)

        val failure = runCatching { ModelRuntimeIdentity.bind(model, changed) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("length") == true)
    }

    @Test
    fun unknownProviderLengthDoesNotForceExpensiveWarmRehash() {
        val model = installed(locator = "content://models/brain.gguf", length = 4096L)
        val source = StaticSource(model.locator, null)

        val identity = ModelRuntimeIdentity.bind(model, source)

        assertEquals(model.lengthBytes, identity.lengthBytes)
        assertEquals(model.sha256, identity.sha256)
    }

    @Test
    fun sameDigestAtDifferentAuthorizedLocatorsProducesDifferentWarmIdentity() {
        val first = installed(locator = "content://models/a.gguf", length = 4096L)
        val second = first.copy(locator = "content://models/b.gguf")

        val firstIdentity = ModelRuntimeIdentity.bind(first, StaticSource(first.locator, 4096L))
        val secondIdentity = ModelRuntimeIdentity.bind(second, StaticSource(second.locator, 4096L))

        assertEquals(first.sha256, second.sha256)
        assertNotEquals(firstIdentity, secondIdentity)
    }

    private class StaticSource(
        override val locator: String,
        override val lengthBytes: Long?
    ) : ModelArtifactSource {
        override val displayName: String = "brain.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private fun installed(locator: String, length: Long?): InstalledModel = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId("brain"),
            format = "gguf",
            capabilities = setOf(CapabilityId("reasoning")),
            local = true
        ),
        displayName = "brain.gguf",
        locator = locator,
        lengthBytes = length,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 123UL,
        metadataKeyValueCount = 456UL,
        installedAtEpochMs = 1L
    )
}
