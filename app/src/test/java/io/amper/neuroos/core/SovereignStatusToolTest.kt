package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignStatusToolTest {
    @Test
    fun runtimeSourceReportsModelsBackendsAndBudgetWithoutArtifactSecrets() {
        val catalog = InMemoryInstalledModelCatalog().also {
            it.put(
                InstalledModel(
                    descriptor = ModelDescriptor(
                        id = ModelId("user-model"),
                        format = "gguf",
                        capabilities = setOf(CapabilityId("reasoning")),
                        local = true
                    ),
                    displayName = "Local Titan",
                    locator = "content://private/model.gguf",
                    lengthBytes = 1234,
                    sha256 = "deadbeef",
                    ggufVersion = 3,
                    tensorCount = 1u,
                    metadataKeyValueCount = 1u
                )
            )
        }
        val backends = InferenceBackendRegistry().also { registry ->
            registry.register(object : ManagedInferenceBackend {
                override val id: String = "test-backend"
                override fun supports(model: InstalledModel): Boolean = true
                override fun health(): BackendHealth = BackendHealth(
                    state = BackendState.READY,
                    hardwareAcceleration = true
                )
                override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                    InferenceCost(estimatedMemoryMb = 128, preferredThreads = 2, contextTokens = 2048)
                override fun infer(
                    model: InstalledModel,
                    source: ModelArtifactSource,
                    request: InferenceRequest
                ): Result<InferenceResponse> = Result.failure(UnsupportedOperationException("not used"))
            })
        }
        val governor = MobileResourceGovernor(
            ResourceBudget(maxConcurrentAgents = 2, memoryMb = 768, thermalClass = 2)
        )
        val provider = SovereignStatusToolProvider(
            RuntimeSovereignStatusSource(catalog, backends, governor)
        )

        val output = provider.execute("summary").getOrThrow()

        assertEquals(ToolSideEffect.READ_ONLY, provider.descriptor.sideEffect)
        assertTrue(output.contains("Local Titan:user-model"))
        assertTrue(output.contains("test-backend:READY:hw=true"))
        assertTrue(output.contains("memory_budget_mb=768"))
        assertTrue(output.contains("thermal_class=2"))
        assertFalse(output.contains("content://"))
        assertFalse(output.contains("deadbeef"))
    }

    @Test
    fun providerRejectsUnknownInspectionScope() {
        val provider = SovereignStatusToolProvider(
            SovereignStatusSource {
                SovereignStatusSnapshot(emptyList(), emptyList(), ResourceBudget())
            }
        )
        assertTrue(provider.execute("secrets").isFailure)
    }
}
