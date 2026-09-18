package io.amper.neuroos.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendResourcePolicyTest {
    private val model = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId("test-gguf"),
            format = "gguf",
            capabilities = setOf(CapabilityId("reasoning")),
            local = true
        ),
        displayName = "test.gguf",
        locator = "/tmp/test.gguf",
        lengthBytes = 800L * 1024L * 1024L,
        sha256 = "00",
        ggufVersion = 3,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL
    )

    private val backend = object : ManagedInferenceBackend {
        override val id: String = "managed-test"
        override fun supports(model: InstalledModel): Boolean = true
        override fun health() = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest) =
            InferenceCost(estimatedMemoryMb = 1000, preferredThreads = 2, contextTokens = 2048)
        override fun infer(model: InstalledModel, source: ModelArtifactSource, request: InferenceRequest) =
            Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
    }

    private val request = InferenceRequest("test")

    @Test
    fun managedBackendFitsHealthyBudget() {
        assertTrue(
            TitanBackendPolicy.eligible(
                backend,
                model,
                request,
                ResourceBudget(memoryMb = 1400, thermalClass = 1)
            )
        )
    }

    @Test
    fun managedBackendIsRejectedWhenMemoryBudgetIsTooSmall() {
        assertFalse(
            TitanBackendPolicy.eligible(
                backend,
                model,
                request,
                ResourceBudget(memoryMb = 700, thermalClass = 1)
            )
        )
    }

    @Test
    fun allBackendsFailClosedAtCriticalThermalState() {
        assertFalse(
            TitanBackendPolicy.eligible(
                backend,
                model,
                request,
                ResourceBudget(memoryMb = 4096, thermalClass = 4)
            )
        )
    }
}
