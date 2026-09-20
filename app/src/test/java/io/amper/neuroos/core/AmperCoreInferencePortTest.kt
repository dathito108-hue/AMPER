package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AmperCoreInferencePortTest {
    @Test
    fun sealedSingleCoreRegistryContainsExactlyOneEndpoint() {
        val core = StubBackend("amper-core")
        val registry = InferenceBackendRegistry.singleCore(core)

        assertTrue(registry.isSealedSingleCore())
        assertEquals(1, registry.list().size)
        assertSame(core, registry.list().single())
    }

    @Test
    fun sealedSingleCoreRegistryRejectsForeignOrReplacementBackend() {
        val core = StubBackend("amper-core")
        val registry = InferenceBackendRegistry.singleCore(core)

        assertThrows(IllegalArgumentException::class.java) {
            registry.register(StubBackend("foreign-runtime"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(core)
        }
        assertEquals(1, registry.list().size)
    }

    @Test
    fun genericRegistryRemainsAvailableForInternalCompatibilityTests() {
        val registry = InferenceBackendRegistry()
        registry.register(StubBackend("one"))
        registry.register(StubBackend("two"))

        assertFalse(registry.isSealedSingleCore())
        assertEquals(listOf("one", "two"), registry.list().map { it.id })
    }

    @Test
    fun productionPortExposesOneAmperCoreEndpoint() {
        val port = AmperCoreInferencePort(
            artifactLookup = { null },
            hardwareSnapshot = { null }
        )

        assertEquals(AmperCoreInferencePort.CORE_ID, port.id)
        assertEquals(1, port.inferenceEndpointCount)
    }

    private class StubBackend(
        override val id: String
    ) : InferenceBackend {
        override fun supports(model: InstalledModel): Boolean = true

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> =
            Result.failure(UnsupportedOperationException("test backend"))
    }
}
