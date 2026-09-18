package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class PersistentModelCatalogTest {
    @Test
    fun catalogSurvivesReloadAndRemove() {
        val root = Files.createTempDirectory("amper-model-catalog").toFile()
        val file = File(root, "models.catalog")
        val model = installed("alpha", "content://models/alpha.gguf")

        FileInstalledModelCatalog(file).put(model)
        val restored = FileInstalledModelCatalog(file)
        val loaded = restored.get(ModelId("alpha"))!!
        assertEquals("alpha.gguf", loaded.displayName)
        assertEquals("content://models/alpha.gguf", loaded.locator)
        assertEquals(setOf(CapabilityId("reasoning"), CapabilityId("code-generation")), loaded.descriptor.capabilities)
        assertEquals(42UL, loaded.tensorCount)

        assertTrue(restored.remove(ModelId("alpha")))
        assertFalse(FileInstalledModelCatalog(file).list().isNotEmpty())
        assertFalse(File(root, "models.catalog.tmp").exists())
        root.deleteRecursively()
    }

    @Test
    fun restoredGgufOutranksContractPlaceholder() {
        val root = Files.createTempDirectory("amper-model-route").toFile()
        val catalog = FileInstalledModelCatalog(File(root, "models.catalog"))
        catalog.put(installed("user-brain", "content://models/brain.gguf"))

        val registry = AmperRuntime.defaultModelRegistry()
        assertEquals(1, InstalledModelRegistryBootstrap(catalog, registry).restore())
        assertEquals("user-brain", registry.route(setOf(CapabilityId("reasoning")))?.id?.value)
        root.deleteRecursively()
    }

    @Test
    fun malformedCatalogRecordFailsClosedInsteadOfBeingSilentlyDropped() {
        val root = Files.createTempDirectory("amper-model-catalog-corrupt").toFile()
        val file = File(root, "models.catalog")
        file.writeText("not-a-valid-model-record\n", StandardCharsets.UTF_8)

        val failure = runCatching { FileInstalledModelCatalog(file) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        root.deleteRecursively()
    }

    @Test
    fun tornFinalCatalogRecordFailsClosed() {
        val root = Files.createTempDirectory("amper-model-catalog-torn").toFile()
        val file = File(root, "models.catalog")
        FileInstalledModelCatalog(file).put(installed("alpha", "content://models/alpha.gguf"))
        val complete = file.readText(StandardCharsets.UTF_8)
        assertTrue(complete.endsWith('\n'))
        file.writeText(complete.dropLast(1), StandardCharsets.UTF_8)

        val failure = runCatching { FileInstalledModelCatalog(file) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        root.deleteRecursively()
    }

    @Test
    fun symlinkCatalogIsRejectedWithoutTouchingVictim() {
        val root = Files.createTempDirectory("amper-model-catalog-symlink").toFile()
        val victim = File(root, "victim.txt")
        victim.writeText("victim-bytes\n", StandardCharsets.UTF_8)
        val catalog = File(root, "models.catalog")
        Files.createSymbolicLink(catalog.toPath(), victim.toPath())

        val failure = runCatching { FileInstalledModelCatalog(catalog) }.exceptionOrNull()
        assertTrue(failure != null)
        assertEquals("victim-bytes\n", victim.readText(StandardCharsets.UTF_8))
        root.deleteRecursively()
    }

    @Test
    fun invalidArtifactDigestIsRejectedBeforePublication() {
        val root = Files.createTempDirectory("amper-model-catalog-digest").toFile()
        val file = File(root, "models.catalog")
        val invalid = installed("alpha", "content://models/alpha.gguf").copy(sha256 = "bad")
        val catalog = FileInstalledModelCatalog(file)

        val failure = runCatching { catalog.put(invalid) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(file.exists())
        root.deleteRecursively()
    }

    private fun installed(id: String, locator: String): InstalledModel = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId(id),
            format = "gguf",
            capabilities = setOf(CapabilityId("reasoning"), CapabilityId("code-generation")),
            local = true
        ),
        displayName = "$id.gguf",
        locator = locator,
        lengthBytes = 1024L,
        sha256 = "ab".repeat(32),
        ggufVersion = 3,
        tensorCount = 42UL,
        metadataKeyValueCount = 7UL,
        installedAtEpochMs = 123456789L
    )
}
