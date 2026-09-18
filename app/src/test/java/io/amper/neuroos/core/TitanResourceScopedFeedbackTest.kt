package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TitanResourceScopedFeedbackTest {
    private val reasoning = CapabilityId("reasoning")
    private val request = InferenceRequest("same interactive workload", maxOutputTokens = 128)

    @Test
    fun resourceConditionClassificationHasDeterministicBoundaries() {
        assertEquals(TitanResourceConditionClass.UNGOVERNED, TitanResourceConditionClass.from(null))
        assertEquals(
            TitanResourceConditionClass.COOL_CONSTRAINED,
            TitanResourceConditionClass.from(ResourceBudget(memoryMb = 1_024, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            TitanResourceConditionClass.from(ResourceBudget(memoryMb = 1_025, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            TitanResourceConditionClass.from(ResourceBudget(memoryMb = 4_096, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_EXPANDED,
            TitanResourceConditionClass.from(ResourceBudget(memoryMb = 4_097, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.WARM_STANDARD,
            TitanResourceConditionClass.from(ResourceBudget(memoryMb = 2_048, thermalClass = 2))
        )
        assertEquals(
            TitanResourceConditionClass.CRITICAL_STANDARD,
            TitanResourceConditionClass.from(ResourceBudget(memoryMb = 2_048, thermalClass = 4))
        )
    }

    @Test
    fun resourceConditionParticipatesInFeedbackKeyIdentity() {
        val base = route("brain", TitanResourceConditionClass.COOL_CONSTRAINED)
        val constrained = TitanRouteFeedbackKey.from(base)
        val expanded = TitanRouteFeedbackKey.from(
            base.copy(resourceCondition = TitanResourceConditionClass.COOL_EXPANDED)
        )

        assertNotEquals(constrained, expanded)
    }

    @Test
    fun constrainedFailureDoesNotDownrankExpandedResourceRoute() {
        val planner = planner(explorationEveryPlanningPasses = 1_000)
        val constrained = ResourceBudget(memoryMb = 512, thermalClass = 1)
        val expanded = ResourceBudget(memoryMb = 8_192, thermalClass = 1)

        val constrainedFirst = planner.plan(request, constrained).getOrThrow()
        assertEquals("a-primary", constrainedFirst.descriptor.id.value)
        planner.recordFailure(constrainedFirst)

        val expandedRoute = planner.plan(request, expanded).getOrThrow()
        assertEquals("a-primary", expandedRoute.descriptor.id.value)

        val constrainedSecond = planner.plan(request, constrained).getOrThrow()
        assertEquals("b-fallback", constrainedSecond.descriptor.id.value)
    }

    @Test
    fun resourceClassHasIndependentExplorationCadence() {
        val planner = planner(explorationEveryPlanningPasses = 2)
        val constrained = ResourceBudget(memoryMb = 512, thermalClass = 1)
        val expanded = ResourceBudget(memoryMb = 8_192, thermalClass = 1)

        val constrainedFirst = planner.plan(request, constrained).getOrThrow()
        assertEquals("a-primary", constrainedFirst.descriptor.id.value)
        planner.recordSuccess(constrainedFirst, response(constrainedFirst, 5.0))

        val expandedFirst = planner.plan(request, expanded).getOrThrow()
        assertEquals("a-primary", expandedFirst.descriptor.id.value)
        planner.recordSuccess(expandedFirst, response(expandedFirst, 5.0))

        val constrainedSecond = planner.plan(request, constrained).getOrThrow()
        assertEquals("b-fallback", constrainedSecond.descriptor.id.value)
    }

    @Test
    fun slowWarmFeedbackDoesNotLeakIntoCoolFeedbackBucket() {
        val feedback = TitanRuntimeFeedback(minSlowSamplesForPenalty = 2)
        val warm = TitanRouteFeedbackKey.from(route("brain", TitanResourceConditionClass.WARM_STANDARD))
        val cool = TitanRouteFeedbackKey.from(route("brain", TitanResourceConditionClass.COOL_STANDARD))
        val slow = InferenceResponse(
            modelId = ModelId("brain"),
            backendId = "test-backend",
            text = "ok",
            outputTokens = 20,
            tokensPerSecond = 1.0,
            generationTimeMs = 20_000L
        )

        feedback.recordSuccess(warm, slow)
        feedback.recordSuccess(warm, slow)

        assertEquals(1, feedback.snapshot(warm).performancePenalty)
        assertEquals(0, feedback.snapshot(cool).performancePenalty)
        assertEquals(0, feedback.snapshot(cool).throughputSamples)
    }

    private fun planner(explorationEveryPlanningPasses: Int): TitanInferenceRoutePlanner {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        listOf("a-primary", "b-fallback").forEach { id ->
            val installed = installed(id)
            models.register(installed.descriptor)
            catalog.put(installed)
        }
        val resolver = object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource = StaticSource(model.locator)
        }
        val backends = InferenceBackendRegistry().apply {
            register(object : InferenceBackend {
                override val id: String = "test-backend"
                override fun supports(model: InstalledModel): Boolean = true
                override fun infer(
                    model: InstalledModel,
                    source: ModelArtifactSource,
                    request: InferenceRequest
                ): Result<InferenceResponse> = error("planner test does not execute inference")
            })
        }
        return TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = resolver,
            backends = backends,
            explorationEveryPlanningPasses = explorationEveryPlanningPasses,
            minThroughputSamplesForRanking = 1
        )
    }

    private fun route(id: String, resourceCondition: TitanResourceConditionClass): TitanInferenceRoute {
        val installed = installed(id)
        val source = StaticSource(installed.locator)
        return TitanInferenceRoute(
            descriptor = installed.descriptor,
            installed = installed,
            source = source,
            backend = object : InferenceBackend {
                override val id: String = "test-backend"
                override fun supports(model: InstalledModel): Boolean = true
                override fun infer(
                    model: InstalledModel,
                    source: ModelArtifactSource,
                    request: InferenceRequest
                ): Result<InferenceResponse> = error("not executed")
            },
            runtimeIdentity = ModelRuntimeIdentity.bind(installed, source),
            budget = null,
            selectedCapabilities = setOf(reasoning),
            workloadClass = TitanInferenceWorkloadClass.from(request),
            resourceCondition = resourceCondition
        )
    }

    private fun installed(id: String): InstalledModel {
        val descriptor = ModelDescriptor(
            id = ModelId(id),
            format = "gguf",
            capabilities = setOf(reasoning),
            local = true
        )
        return InstalledModel(
            descriptor = descriptor,
            displayName = "$id.gguf",
            locator = "memory://$id.gguf",
            lengthBytes = 128L,
            sha256 = id.padEnd(64, '0').take(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL,
            installedAtEpochMs = 1L
        )
    }

    private fun response(route: TitanInferenceRoute, tokensPerSecond: Double): InferenceResponse =
        InferenceResponse(
            modelId = route.installed.descriptor.id,
            backendId = route.backend.id,
            text = "ok",
            outputTokens = 32,
            tokensPerSecond = tokensPerSecond,
            generationTimeMs = 1_000L,
            selectedCapabilities = route.selectedCapabilities
        )

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
