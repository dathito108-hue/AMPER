package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendRuntimeTest {
    private val model = InstalledModel(
        descriptor = ModelDescriptor(ModelId("m"), "gguf", setOf(CapabilityId("reasoning")), true),
        displayName = "m.gguf", locator = "memory://m", lengthBytes = 10L,
        sha256 = "abc", ggufVersion = 3, tensorCount = 1u, metadataKeyValueCount = 1u
    )
    private val request = InferenceRequest("hello")

    @Test
    fun routingPrefersReadyAcceleratedBackend() {
        val registry = InferenceBackendRegistry().apply {
            register(managed("cpu", BackendState.READY, false, 200))
            register(managed("accelerated", BackendState.READY, true, 200))
        }
        val routed = registry.route(model, request, ResourceBudget(memoryMb = 512))
        assertEquals("accelerated", routed?.id)
    }

    @Test
    fun routingRejectsUnavailableOrOverBudgetBackends() {
        val registry = InferenceBackendRegistry().apply {
            register(managed("dead", BackendState.UNAVAILABLE, true, 100))
            register(managed("huge", BackendState.READY, true, 4096))
        }
        assertNull(registry.route(model, request, ResourceBudget(memoryMb = 512)))
    }

    @Test
    fun requestAwareBackendIsExcludedBeforeUngovernedAndGovernedRanking() {
        val blocked = object : ManagedInferenceBackend, RequestAwareInferenceBackend {
            override val id: String = "request-blocked"
            override fun supports(model: InstalledModel): Boolean = true
            override fun supportsRequest(
                model: InstalledModel,
                request: InferenceRequest
            ): Boolean = false
            override fun requestRejectionReason(
                model: InstalledModel,
                request: InferenceRequest
            ): String = "shape-not-supported"
            override fun health(): BackendHealth =
                BackendHealth(BackendState.READY, hardwareAcceleration = true)
            override fun estimate(
                model: InstalledModel,
                request: InferenceRequest
            ): InferenceCost = InferenceCost(64, 4, 4096)
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> =
                Result.success(InferenceResponse(model.descriptor.id, id, "blocked"))
        }
        val registry = InferenceBackendRegistry().apply {
            register(blocked)
            register(managed("fallback", BackendState.READY, false, 128))
        }

        assertEquals(
            listOf("fallback"),
            registry.candidates(model, request).map { it.id }
        )
        assertEquals(
            "fallback",
            registry.route(model, request, ResourceBudget(memoryMb = 512))?.id
        )

        val evaluated = TitanBackendPolicy.evaluate(
            backend = blocked,
            model = model,
            request = request,
            budget = ResourceBudget(memoryMb = 512)
        )
        assertEquals(false, evaluated.eligible)
        assertEquals("shape-not-supported", evaluated.rejectionReason)
    }

    @Test
    fun adapterBackendPreservesCanonicalResponseIdentity() {
        val backend = AdapterInferenceBackend(object : NativeInferenceAdapter {
            override val adapterId = "native-test"
            override fun health() = BackendHealth(BackendState.READY, hardwareAcceleration = true)
            override fun supportsFormat(format: String) = format == "gguf"
            override fun estimate(model: InstalledModel, request: InferenceRequest) = InferenceCost(128, 4, 4096)
            override fun generate(model: InstalledModel, source: ModelArtifactSource, request: InferenceRequest) = Result.success("ok:${request.prompt}")
        })
        val response = backend.infer(model, ByteArrayModelArtifactSource(ByteArray(1)), request).getOrThrow()
        assertEquals("m", response.modelId.value)
        assertEquals("native-test", response.backendId)
        assertEquals("ok:hello", response.text)
    }

    private fun managed(id: String, state: BackendState, accelerated: Boolean, memoryMb: Int) =
        object : ManagedInferenceBackend {
            override val id = id
            override fun supports(model: InstalledModel) = true
            override fun health() = BackendHealth(state, hardwareAcceleration = accelerated)
            override fun estimate(model: InstalledModel, request: InferenceRequest) = InferenceCost(memoryMb, 4, 4096)
            override fun infer(model: InstalledModel, source: ModelArtifactSource, request: InferenceRequest) =
                Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
        }
}
