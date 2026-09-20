package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmperSingleCoreFoundationTest {
    @Test
    fun liveRegistryNeverExposesMoreThanOneModel() {
        val registry = AmperSingleCoreModelRegistry()
        val first = descriptor("first")
        val second = descriptor("second")

        registry.register(first)
        registry.register(second)

        assertEquals(ModelId("second"), registry.route(setOf(TitanCapabilities.REASONING))?.id)
        assertEquals(
            listOf(ModelId("second")),
            registry.candidates(setOf(TitanCapabilities.REASONING)).map { it.id }
        )
    }

    @Test
    fun controllerRetainsManySourcesButActivatesExactlyOne() {
        val catalog = InMemoryInstalledModelCatalog()
        val registry = AmperSingleCoreModelRegistry()
        val first = installed("first", installedAt = 1L)
        val second = installed("second", installedAt = 2L)
        catalog.put(first)
        catalog.put(second)

        val controller = AmperSingleCoreFoundationController(catalog, registry)
        val state = controller.restore()

        assertEquals(ModelId("second"), state.activeModelId)
        assertEquals(2, state.retainedSourceCount)
        assertEquals(ModelId("second"), registry.activeModelId())

        controller.activate(ModelId("first"))

        assertEquals(ModelId("first"), registry.activeModelId())
        assertEquals(2, catalog.list().size)
    }

    @Test
    fun deactivationLeavesSourceLineageButNoLiveModel() {
        val catalog = InMemoryInstalledModelCatalog()
        val registry = AmperSingleCoreModelRegistry()
        catalog.put(installed("foundation", installedAt = 1L))
        val controller = AmperSingleCoreFoundationController(catalog, registry)
        controller.restore()

        val state = controller.deactivate()

        assertNull(state.activeModelId)
        assertNull(registry.activeModelId())
        assertEquals(1, catalog.list().size)
    }

    private fun descriptor(id: String) = ModelDescriptor(
        id = ModelId(id),
        format = "gguf",
        capabilities = setOf(TitanCapabilities.REASONING),
        local = true
    )

    private fun installed(
        id: String,
        installedAt: Long
    ) = InstalledModel(
        descriptor = descriptor(id),
        displayName = "$id.gguf",
        locator = "content://test/$id",
        lengthBytes = 1024L,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL,
        installedAtEpochMs = installedAt
    )
}
