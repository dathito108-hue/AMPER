package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationSessionRoutingPreferenceTest {
    private val model = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId("session-model"),
            format = "gguf",
            capabilities = setOf(TitanCapabilities.REASONING),
            local = true
        ),
        displayName = "session.gguf",
        locator = "memory://session.gguf",
        lengthBytes = 128L,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL
    )
    private val budget = ResourceBudget(memoryMb = 512, thermalClass = 1)

    @Test
    fun standardModePreservesExistingWarmSessionBonus() {
        val cold = backend("cold", BackendSessionAffinity.COLD)
        val warm = backend("warm", BackendSessionAffinity.WARM_COMPATIBLE)
        val request = InferenceRequest("standard")

        val coldScore = TitanBackendPolicy.evaluate(cold, model, request, budget).score
        val warmScore = TitanBackendPolicy.evaluate(warm, model, request, budget).score

        assertEquals(150, warmScore - coldScore)
    }

    @Test
    fun preferReuseStrengthensOnlyWarmCompatibleRanking() {
        val cold = backend("cold", BackendSessionAffinity.COLD)
        val warm = backend("warm", BackendSessionAffinity.WARM_COMPATIBLE)
        val request = InferenceRequest(
            prompt = "prefer reuse",
            sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE
        )

        val coldScore = TitanBackendPolicy.evaluate(cold, model, request, budget).score
        val warmScore = TitanBackendPolicy.evaluate(warm, model, request, budget).score

        assertEquals(300, warmScore - coldScore)
    }

    @Test
    fun ignoreReuseRemovesWarmBonusWithoutDisablingBackend() {
        val registry = InferenceBackendRegistry().apply {
            register(backend("cold-first", BackendSessionAffinity.COLD))
            register(backend("warm-second", BackendSessionAffinity.WARM_COMPATIBLE))
        }

        val standard = registry.route(
            model,
            InferenceRequest("standard"),
            budget
        )
        val ignore = registry.route(
            model,
            InferenceRequest(
                prompt = "ignore",
                sessionRoutingPreference = TitanSessionRoutingPreference.IGNORE_REUSE
            ),
            budget
        )

        assertEquals("warm-second", standard?.id)
        assertEquals("cold-first", ignore?.id)
    }

    @Test
    fun preferReuseCannotBypassContextAdmission() {
        val tinyWarm = backend(
            id = "tiny-warm",
            affinity = BackendSessionAffinity.WARM_COMPATIBLE,
            contextTokens = 128
        )
        val request = InferenceRequest(
            prompt = "context gate",
            maxOutputTokens = 256,
            sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE
        )

        val evaluation = TitanBackendPolicy.evaluate(tinyWarm, model, request, budget)

        assertFalse(evaluation.eligible)
        assertEquals(0, evaluation.score)
    }

    @Test
    fun preparedHintRejectsSessionPolicyDrift() {
        val request = InferenceRequest(
            prompt = "prepared",
            maxOutputTokens = 256,
            temperature = 0.4,
            sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE
        )
        val hint = TitanPreparedRouteHint(
            runtimeIdentity = ModelRuntimeIdentity(
                modelId = model.descriptor.id,
                locator = model.locator,
                sha256 = model.sha256,
                lengthBytes = model.lengthBytes,
                ggufVersion = model.ggufVersion,
                tensorCount = model.tensorCount,
                metadataKeyValueCount = model.metadataKeyValueCount
            ),
            backendId = "warm",
            selectedCapabilities = setOf(TitanCapabilities.REASONING),
            maxOutputTokens = 256,
            temperature = 0.4,
            sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE
        )

        assertEquals(true, hint.isRequestCompatible(request))
        assertEquals(
            false,
            hint.isRequestCompatible(
                request.copy(sessionRoutingPreference = TitanSessionRoutingPreference.IGNORE_REUSE)
            )
        )
    }

    private fun backend(
        id: String,
        affinity: BackendSessionAffinity,
        contextTokens: Int = 4_096
    ): ManagedInferenceBackend = object : ManagedInferenceBackend {
        override val id: String = id
        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(estimatedMemoryMb = 128, preferredThreads = 2, contextTokens = contextTokens)
        override fun sessionAffinity(
            model: InstalledModel,
            request: InferenceRequest,
            cost: InferenceCost
        ): BackendSessionAffinity = affinity
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = Result.success(
            InferenceResponse(model.descriptor.id, id, "ok")
        )
    }
}
