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
    fun productionPortErasesLegacyModelChoiceHintsOnly() {
        val port = AmperCoreInferencePort(
            artifactLookup = { null },
            hardwareSnapshot = { null }
        )
        val request = InferenceRequest(
            prompt = "xin chao",
            requiredCapabilities = setOf(CapabilityId("reasoning")),
            maxOutputTokens = 96,
            temperature = 0.25,
            preferredModelId = ModelId("legacy-continuity"),
            userPreferredModelId = ModelId("legacy-user-choice"),
            sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE
        )

        val normalized = port.normalizeRequest(request)

        assertEquals(request.prompt, normalized.prompt)
        assertEquals(request.requiredCapabilities, normalized.requiredCapabilities)
        assertEquals(request.maxOutputTokens, normalized.maxOutputTokens)
        assertEquals(request.temperature, normalized.temperature, 0.0)
        assertEquals(
            request.sessionRoutingPreference,
            normalized.sessionRoutingPreference
        )
        assertTrue(normalized.attachments.isEmpty())
        assertEquals(null, normalized.preferredModelId)
        assertEquals(null, normalized.userPreferredModelId)
        assertEquals(null, normalized.effectivePreferredModelId())
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
