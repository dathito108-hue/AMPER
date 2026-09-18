package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstalledModelCapabilityReclassificationTest {
    @Test
    fun legacyOverClaimCanBeReducedToReasoningWithoutChangingArtifactIdentity() {
        val original = installed(
            id = "legacy",
            capabilities = setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)
        )
        val catalog = InMemoryInstalledModelCatalog().apply { put(original) }
        val registry = InMemoryModelRegistry().apply { register(original.descriptor) }
        val service = InstalledModelCapabilityService(catalog, registry)

        val updated = service.reclassify(ModelId("legacy"), ModelCapabilityProfile()).getOrThrow()

        assertEquals(setOf(TitanCapabilities.REASONING), updated.descriptor.capabilities)
        assertEquals(original.descriptor.id, updated.descriptor.id)
        assertEquals(original.descriptor.format, updated.descriptor.format)
        assertEquals(original.descriptor.local, updated.descriptor.local)
        assertEquals(original.displayName, updated.displayName)
        assertEquals(original.locator, updated.locator)
        assertEquals(original.lengthBytes, updated.lengthBytes)
        assertEquals(original.sha256, updated.sha256)
        assertEquals(original.ggufVersion, updated.ggufVersion)
        assertEquals(original.tensorCount, updated.tensorCount)
        assertEquals(original.metadataKeyValueCount, updated.metadataKeyValueCount)
        assertEquals(original.installedAtEpochMs, updated.installedAtEpochMs)
        assertNull(registry.route(setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)))
        assertEquals("legacy", registry.route(setOf(TitanCapabilities.REASONING))?.id?.value)
    }

    @Test
    fun explicitSpecialistReclassificationSurvivesPersistentCatalogReload() {
        val root = Files.createTempDirectory("amper-model-reclassify").toFile()
        try {
            val file = File(root, "models.catalog")
            val original = installed("planner", setOf(TitanCapabilities.REASONING))
            val catalog = FileInstalledModelCatalog(file).apply { put(original) }
            val registry = InMemoryModelRegistry().apply { register(original.descriptor) }
            val service = InstalledModelCapabilityService(catalog, registry)

            val updated = service.reclassify(
                ModelId("planner"),
                ModelCapabilityProfile(codeGeneration = true, planning = true)
            ).getOrThrow()

            val expected = setOf(
                TitanCapabilities.REASONING,
                TitanCapabilities.CODE_GENERATION,
                TitanCapabilities.PLANNING
            )
            assertEquals(expected, updated.descriptor.capabilities)
            assertEquals(expected, FileInstalledModelCatalog(file).get(ModelId("planner"))?.descriptor?.capabilities)
            assertEquals(
                "planner",
                registry.route(setOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING))?.id?.value
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun noOpProfileReturnsExistingCatalogInstanceAndDoesNotRepublishRegistry() {
        val original = installed("same", setOf(TitanCapabilities.REASONING))
        val catalog = InMemoryInstalledModelCatalog().apply { put(original) }
        var registrations = 0
        val registry = object : ModelRegistry {
            override fun register(model: ModelDescriptor) {
                registrations += 1
            }

            override fun route(required: Set<CapabilityId>): ModelDescriptor? = null
        }
        val service = InstalledModelCapabilityService(catalog, registry)

        val updated = service.reclassify(ModelId("same"), ModelCapabilityProfile()).getOrThrow()

        assertSame(original, updated)
        assertEquals(0, registrations)
    }

    @Test
    fun registryFailureRollsDurableCatalogBackToOriginalCapabilities() {
        val root = Files.createTempDirectory("amper-model-reclassify-rollback").toFile()
        try {
            val file = File(root, "models.catalog")
            val original = installed(
                "rollback",
                setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)
            )
            val catalog = FileInstalledModelCatalog(file).apply { put(original) }
            val rejectingRegistry = object : ModelRegistry {
                override fun register(model: ModelDescriptor) {
                    throw IllegalStateException("registry rejected update")
                }

                override fun route(required: Set<CapabilityId>): ModelDescriptor? = null
            }
            val service = InstalledModelCapabilityService(catalog, rejectingRegistry)

            val failure = service.reclassify(ModelId("rollback"), ModelCapabilityProfile()).exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            val restored = FileInstalledModelCatalog(file).get(ModelId("rollback"))!!
            assertEquals(original.descriptor.capabilities, restored.descriptor.capabilities)
            assertEquals(original.sha256, restored.sha256)
            assertEquals(original.installedAtEpochMs, restored.installedAtEpochMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingModelFailsWithoutPublishingAnything() {
        var registrations = 0
        val service = InstalledModelCapabilityService(
            InMemoryInstalledModelCatalog(),
            object : ModelRegistry {
                override fun register(model: ModelDescriptor) {
                    registrations += 1
                }

                override fun route(required: Set<CapabilityId>): ModelDescriptor? = null
            }
        )

        val failure = service.reclassify(ModelId("missing"), ModelCapabilityProfile()).exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, registrations)
    }

    private fun installed(id: String, capabilities: Set<CapabilityId>): InstalledModel = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId(id),
            format = "gguf",
            capabilities = capabilities,
            local = true
        ),
        displayName = "$id.gguf",
        locator = "content://models/$id.gguf",
        lengthBytes = 987654L,
        sha256 = "cd".repeat(32),
        ggufVersion = 3L,
        tensorCount = 42UL,
        metadataKeyValueCount = 7UL,
        installedAtEpochMs = 123456789L
    )
}
