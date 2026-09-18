package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BackendContextCapacityRoutingTest {
    private val reasoning = CapabilityId("reasoning")
    private val model = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId("context-model"),
            format = "gguf",
            capabilities = setOf(reasoning),
            local = true
        ),
        displayName = "context.gguf",
        locator = "memory://context.gguf",
        lengthBytes = 128L,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL
    )

    @Test
    fun governedRoutingSkipsBackendWhoseContextCannotFitPromptAndOutputBudget() {
        val request = InferenceRequest("large output", maxOutputTokens = 1_024)
        val registry = InferenceBackendRegistry().apply {
            register(managed("small-context", contextTokens = 512))
            register(managed("large-context", contextTokens = 2_048))
        }

        val routed = registry.route(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1))

        assertEquals("large-context", routed?.id)
    }

    @Test
    fun ungovernedRequestAwareCandidatesRejectInsufficientCombinedContext() {
        val request = InferenceRequest("large output", maxOutputTokens = 1_024)
        val registry = InferenceBackendRegistry().apply {
            register(managed("small-context", contextTokens = 512))
            register(managed("large-context", contextTokens = 2_048))
        }

        assertEquals(
            listOf("large-context"),
            registry.candidates(model, request).map { it.id }
        )
    }

    @Test
    fun outputBudgetEqualToContextCapacityIsRejectedBecausePromptAlsoConsumesContext() {
        val request = InferenceRequest("boundary", maxOutputTokens = 1_024)
        val registry = InferenceBackendRegistry().apply {
            register(managed("exact-context", contextTokens = 1_024))
        }

        assertNull(registry.route(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1)))
        assertEquals(emptyList<String>(), registry.candidates(model, request).map { it.id })
    }

    @Test
    fun promptEstimatePlusOutputAtOrBelowCapacityRemainsEligible() {
        val request = InferenceRequest("boundary", maxOutputTokens = 1_024)
        val required = TitanPromptTokenEstimator.estimate(request.prompt) + request.maxOutputTokens
        val registry = InferenceBackendRegistry().apply {
            register(managed("combined-context", contextTokens = required))
        }

        assertNotNull(registry.route(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1)))
        assertEquals(listOf("combined-context"), registry.candidates(model, request).map { it.id })
    }

    @Test
    fun longPromptFallsThroughToLargerContextBeforeInference() {
        val request = InferenceRequest("a".repeat(3_000), maxOutputTokens = 256)
        val registry = InferenceBackendRegistry().apply {
            register(managed("small-context", contextTokens = 1_024))
            register(managed("large-context", contextTokens = 2_048))
        }

        assertEquals(
            "large-context",
            registry.route(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1))?.id
        )
    }

    @Test
    fun backendSpecificPromptEstimatorOverridesDeterministicFallback() {
        val request = InferenceRequest("a".repeat(3_000), maxOutputTokens = 256)
        val deterministicRequired = TitanPromptTokenEstimator.estimate(request.prompt) + request.maxOutputTokens
        val exact = exactManaged(
            id = "exact-estimator",
            contextTokens = 512,
            promptTokens = 100
        )
        val registry = InferenceBackendRegistry().apply { register(exact) }

        assertEquals(1_288, deterministicRequired)
        assertEquals(listOf("exact-estimator"), registry.candidates(model, request).map { it.id })
        assertEquals(
            "exact-estimator",
            registry.route(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1))?.id
        )
    }

    @Test
    fun nonAsciiEstimatorReservesMoreContextThanAsciiTextOfSameCodePointCount() {
        val ascii = TitanPromptTokenEstimator.estimate("a".repeat(300))
        val vietnamese = TitanPromptTokenEstimator.estimate("你".repeat(300))

        assertEquals(132, ascii)
        assertEquals(332, vietnamese)
    }

    @Test
    fun legacyNoRequestCandidateApiRetainsOriginalSupportOnlyBehavior() {
        val registry = InferenceBackendRegistry().apply {
            register(managed("small-context", contextTokens = 128))
            register(managed("large-context", contextTokens = 4_096))
        }

        assertEquals(
            listOf("small-context", "large-context"),
            registry.candidates(model).map { it.id }
        )
    }

    @Test
    fun ungovernedPlannerFallsThroughToContextCompatibleBackendBeforeInference() {
        val request = InferenceRequest("planner route", maxOutputTokens = 1_024)
        val models = InMemoryModelRegistry().apply { register(model.descriptor) }
        val catalog = InMemoryInstalledModelCatalog().apply { put(model) }
        val backends = InferenceBackendRegistry().apply {
            register(managed("small-context", contextTokens = 512))
            register(managed("large-context", contextTokens = 2_048))
        }
        val planner = TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource = StaticSource(model.locator)
            },
            backends = backends
        )

        val route = planner.plan(request, budget = null).getOrThrow()

        assertEquals("large-context", route.backend.id)
    }

    @Test
    fun unmanagedBackendRemainsEligibleWhenItDeclaresNoCapacityContract() {
        val unmanaged = object : InferenceBackend {
            override val id: String = "legacy-unmanaged"
            override fun supports(model: InstalledModel): Boolean = true
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "ok")
            )
        }
        val request = InferenceRequest("large output", maxOutputTokens = 8_192)
        val registry = InferenceBackendRegistry().apply { register(unmanaged) }

        assertEquals(listOf("legacy-unmanaged"), registry.candidates(model, request).map { it.id })
        assertEquals(
            "legacy-unmanaged",
            registry.route(model, request, ResourceBudget(memoryMb = 512, thermalClass = 1))?.id
        )
    }

    private fun managed(id: String, contextTokens: Int): ManagedInferenceBackend =
        object : ManagedInferenceBackend {
            override val id: String = id
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(
                    estimatedMemoryMb = 128,
                    preferredThreads = 2,
                    contextTokens = contextTokens
                )
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "ok")
            )
        }

    private fun exactManaged(
        id: String,
        contextTokens: Int,
        promptTokens: Int
    ): ManagedInferenceBackend = object : ManagedInferenceBackend,
        PromptTokenEstimatingInferenceBackend {
        override val id: String = id
        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(
                estimatedMemoryMb = 128,
                preferredThreads = 2,
                contextTokens = contextTokens
            )
        override fun estimatePromptTokens(model: InstalledModel, request: InferenceRequest): Int =
            promptTokens
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = Result.success(
            InferenceResponse(model.descriptor.id, id, "ok")
        )
    }

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "context.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
