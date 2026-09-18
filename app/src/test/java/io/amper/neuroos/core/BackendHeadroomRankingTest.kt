package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendHeadroomRankingTest {
    private val model = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId("headroom-model"),
            format = "gguf",
            capabilities = setOf(CapabilityId("reasoning")),
            local = true
        ),
        displayName = "headroom.gguf",
        locator = "memory://headroom.gguf",
        lengthBytes = 128L,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL
    )
    private val request = InferenceRequest("route this model")

    @Test
    fun healthyMemoryHeadroomCanOutrankNearLimitAcceleration() {
        val registry = InferenceBackendRegistry().apply {
            register(managed("accelerated-heavy", BackendState.READY, accelerated = true, memoryMb = 500))
            register(managed("cpu-light", BackendState.READY, accelerated = false, memoryMb = 100))
        }

        val routed = registry.route(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1))

        assertEquals("cpu-light", routed?.id)
    }

    @Test
    fun readyHealthRemainsDominantOverDegradedHeadroom() {
        val registry = InferenceBackendRegistry().apply {
            register(managed("ready-heavy", BackendState.READY, accelerated = false, memoryMb = 500))
            register(managed("degraded-light", BackendState.DEGRADED, accelerated = true, memoryMb = 32))
        }

        val routed = registry.route(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1))

        assertEquals("ready-heavy", routed?.id)
    }

    @Test
    fun unknownMemoryEstimateDoesNotReceiveSyntheticHeadroom() {
        val registry = InferenceBackendRegistry().apply {
            register(managed("unknown-accelerated", BackendState.READY, accelerated = true, memoryMb = 0))
            register(managed("known-light", BackendState.READY, accelerated = false, memoryMb = 100))
        }

        val routed = registry.route(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1))

        assertEquals("known-light", routed?.id)
    }

    @Test
    fun equalGovernedScoresPreserveRegistrationOrder() {
        val registry = InferenceBackendRegistry().apply {
            register(managed("first", BackendState.READY, accelerated = false, memoryMb = 256))
            register(managed("second", BackendState.READY, accelerated = false, memoryMb = 256))
        }

        val candidates = registry.candidates(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1))

        assertEquals(listOf("first", "second"), candidates.map { it.id })
    }

    @Test
    fun governedCandidateEvaluationSamplesHealthAndCostOnce() {
        var healthCalls = 0
        var estimateCalls = 0
        val counting = object : ManagedInferenceBackend {
            override val id: String = "counting"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth {
                healthCalls += 1
                return BackendHealth(BackendState.READY, hardwareAcceleration = false)
            }
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost {
                estimateCalls += 1
                return InferenceCost(estimatedMemoryMb = 128, preferredThreads = 2, contextTokens = 2048)
            }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "ok")
            )
        }
        val registry = InferenceBackendRegistry().apply { register(counting) }

        registry.candidates(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1))

        assertEquals(1, healthCalls)
        assertEquals(1, estimateCalls)
    }

    @Test
    fun ungovernedCandidateOrderRemainsRegistrationOrder() {
        val registry = InferenceBackendRegistry().apply {
            register(managed("heavy-first", BackendState.READY, accelerated = false, memoryMb = 500))
            register(managed("light-second", BackendState.READY, accelerated = false, memoryMb = 32))
        }

        assertEquals(listOf("heavy-first", "light-second"), registry.candidates(model).map { it.id })
    }

    private fun managed(
        id: String,
        state: BackendState,
        accelerated: Boolean,
        memoryMb: Int
    ): ManagedInferenceBackend = object : ManagedInferenceBackend {
        override val id: String = id
        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(state, hardwareAcceleration = accelerated)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(estimatedMemoryMb = memoryMb, preferredThreads = 2, contextTokens = 2048)
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
    }
}
