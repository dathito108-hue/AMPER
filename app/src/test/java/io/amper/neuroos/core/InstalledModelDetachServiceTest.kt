package io.amper.neuroos.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledModelDetachServiceTest {
    @Test
    fun detachUnloadsThenRemovesLiveAndDurableModelWithoutDeletingArtifact() {
        val root = Files.createTempDirectory("amper-model-detach").toFile()
        try {
            val artifact = root.resolve("user-model.gguf").apply { writeText("user-owned-gguf-bytes") }
            val catalog = FileInstalledModelCatalog(root.resolve("models.catalog"))
            val registry = InMemoryModelRegistry()
            val model = model("detach-model", artifact.toURI().toString())
            catalog.put(model)
            registry.register(model.descriptor)
            val unloads = mutableListOf<ModelId>()
            val service = InstalledModelDetachService(catalog, registry) { id ->
                unloads += id
                Result.success(Unit)
            }

            val detached = service.detach(model.descriptor.id).getOrThrow()

            assertEquals(model, detached)
            assertEquals(listOf(model.descriptor.id), unloads)
            assertNull(catalog.get(model.descriptor.id))
            assertTrue(registry.candidates(setOf(CapabilityId("reasoning"))).none { it.id == model.descriptor.id })
            assertTrue(artifact.exists())
            assertEquals("user-owned-gguf-bytes", artifact.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detachedModelStaysAbsentAfterDurableCatalogRestart() {
        val root = Files.createTempDirectory("amper-model-detach-restart").toFile()
        try {
            val artifact = root.resolve("restart-model.gguf").apply { writeText("still-owned-by-user") }
            val catalogFile = root.resolve("models.catalog")
            val firstCatalog = FileInstalledModelCatalog(catalogFile)
            val registry = InMemoryModelRegistry()
            val model = model("restart-detach-model", artifact.toURI().toString())
            firstCatalog.put(model)
            registry.register(model.descriptor)

            InstalledModelDetachService(firstCatalog, registry) { Result.success(Unit) }
                .detach(model.descriptor.id)
                .getOrThrow()

            val restartedCatalog = FileInstalledModelCatalog(catalogFile)
            val restartedRegistry = InMemoryModelRegistry()
            val restored = InstalledModelRegistryBootstrap(restartedCatalog, restartedRegistry).restore()

            assertEquals(0, restored)
            assertNull(restartedCatalog.get(model.descriptor.id))
            assertTrue(restartedRegistry.candidates(setOf(CapabilityId("reasoning"))).none { it.id == model.descriptor.id })
            assertTrue(artifact.exists())
            assertEquals("still-owned-by-user", artifact.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unloadFailureLeavesCatalogAndLiveRegistryUntouched() {
        val catalog = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        val model = model("unload-failure", "content://user/model")
        catalog.put(model)
        registry.register(model.descriptor)
        val service = InstalledModelDetachService(catalog, registry) {
            Result.failure(IllegalStateException("backend busy"))
        }

        val result = service.detach(model.descriptor.id)

        assertTrue(result.isFailure)
        assertNotNull(catalog.get(model.descriptor.id))
        assertTrue(registry.candidates(setOf(CapabilityId("reasoning"))).any { it.id == model.descriptor.id })
    }

    @Test
    fun missingLiveRegistryEntryIsRepairedWhenDetachCannotUnregister() {
        val catalog = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        val model = model("registry-missing", "content://user/model")
        catalog.put(model)
        var unloads = 0
        val service = InstalledModelDetachService(catalog, registry) {
            unloads += 1
            Result.success(Unit)
        }

        val result = service.detach(model.descriptor.id)

        assertTrue(result.isFailure)
        assertEquals(1, unloads)
        assertNotNull(catalog.get(model.descriptor.id))
        assertTrue(registry.candidates(setOf(CapabilityId("reasoning"))).any { it.id == model.descriptor.id })
    }

    @Test
    fun durableCatalogFailureRollsLiveRegistryBack() {
        val backing = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        val model = model("catalog-failure", "content://user/model")
        backing.put(model)
        registry.register(model.descriptor)
        val failingCatalog = object : InstalledModelCatalog {
            override fun put(model: InstalledModel) = backing.put(model)
            override fun get(id: ModelId): InstalledModel? = backing.get(id)
            override fun list(): List<InstalledModel> = backing.list()
            override fun remove(id: ModelId): Boolean = error("durable publication failed")
        }
        val service = InstalledModelDetachService(failingCatalog, registry) { Result.success(Unit) }

        val result = service.detach(model.descriptor.id)

        assertTrue(result.isFailure)
        assertNotNull(backing.get(model.descriptor.id))
        assertTrue(registry.candidates(setOf(CapabilityId("reasoning"))).any { it.id == model.descriptor.id })
    }

    @Test
    fun unknownModelFailsWithoutCallingRuntimeUnload() {
        val catalog = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        var unloadCalled = false
        val service = InstalledModelDetachService(catalog, registry) {
            unloadCalled = true
            Result.success(Unit)
        }

        val result = service.detach(ModelId("missing-model"))

        assertTrue(result.isFailure)
        assertTrue(!unloadCalled)
    }

    private fun model(id: String, locator: String): InstalledModel = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId(id),
            format = "gguf",
            capabilities = setOf(CapabilityId("reasoning")),
            local = true
        ),
        displayName = "$id.gguf",
        locator = locator,
        lengthBytes = 128L,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1uL,
        metadataKeyValueCount = 1uL,
        installedAtEpochMs = 1L
    )
}
