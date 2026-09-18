package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TitanPenalizedRouteProbationTest {
    private val reasoning = CapabilityId("reasoning")
    private val request = InferenceRequest(
        prompt = "probation workload",
        requiredCapabilities = setOf(reasoning),
        maxOutputTokens = 128
    )

    @Test
    fun singleFailureIsNotRetriedBeforeProbationCadence() {
        val feedback = TitanRuntimeFeedback()
        val planner = planner(
            ids = listOf("a-primary", "b-fallback"),
            feedback = feedback,
            probationEveryPlanningPasses = 4
        )

        val first = planner.plan(request).getOrThrow()
        assertEquals("a-primary", first.descriptor.id.value)
        planner.recordFailure(first)

        assertEquals("b-fallback", planner.plan(request).getOrThrow().descriptor.id.value)
        assertEquals("b-fallback", planner.plan(request).getOrThrow().descriptor.id.value)
        assertEquals("a-primary", planner.plan(request).getOrThrow().descriptor.id.value)
    }

    @Test
    fun probationNeverBypassesActiveFailureDeferral() {
        val feedback = TitanRuntimeFeedback(
            quarantineAfterFailures = 2,
            quarantinePlanningPasses = 2
        )
        val planner = planner(
            ids = listOf("a-primary", "b-fallback"),
            feedback = feedback,
            probationEveryPlanningPasses = 1
        )

        val first = planner.plan(request).getOrThrow()
        planner.recordFailure(first)
        planner.recordFailure(first)

        assertEquals("b-fallback", planner.plan(request).getOrThrow().descriptor.id.value)
        assertEquals("b-fallback", planner.plan(request).getOrThrow().descriptor.id.value)
        assertEquals("a-primary", planner.plan(request).getOrThrow().descriptor.id.value)
    }

    @Test
    fun fastProbationSuccessRehabilitatesOldPerformancePenalty() {
        val feedback = TitanRuntimeFeedback()
        val planner = planner(
            ids = listOf("a-primary", "b-fallback"),
            feedback = feedback,
            probationEveryPlanningPasses = 4
        )

        val first = planner.plan(request).getOrThrow()
        planner.recordSuccess(first, response(first, tokensPerSecond = 1.0))
        planner.recordSuccess(first, response(first, tokensPerSecond = 1.0))
        assertEquals(1, feedback.snapshot(TitanRouteFeedbackKey.from(first)).performancePenalty)

        assertEquals("b-fallback", planner.plan(request).getOrThrow().descriptor.id.value)
        assertEquals("b-fallback", planner.plan(request).getOrThrow().descriptor.id.value)

        val probation = planner.plan(request).getOrThrow()
        assertEquals("a-primary", probation.descriptor.id.value)
        planner.recordSuccess(probation, response(probation, tokensPerSecond = 20.0))
        assertEquals(0, feedback.snapshot(TitanRouteFeedbackKey.from(probation)).performancePenalty)

        assertEquals("a-primary", planner.plan(request).getOrThrow().descriptor.id.value)
    }

    @Test
    fun probationRotatesAcrossMultiplePenalizedRoutes() {
        val feedback = TitanRuntimeFeedback()
        feedback.recordFailure(feedbackKey("a-primary"))
        feedback.recordFailure(feedbackKey("b-secondary"))
        val planner = planner(
            ids = listOf("a-primary", "b-secondary", "c-healthy"),
            feedback = feedback,
            probationEveryPlanningPasses = 2
        )

        assertEquals("c-healthy", planner.plan(request).getOrThrow().descriptor.id.value)
        assertEquals("a-primary", planner.plan(request).getOrThrow().descriptor.id.value)
        assertEquals("c-healthy", planner.plan(request).getOrThrow().descriptor.id.value)
        assertEquals("b-secondary", planner.plan(request).getOrThrow().descriptor.id.value)
    }

    private fun planner(
        ids: List<String>,
        feedback: TitanRuntimeFeedback,
        probationEveryPlanningPasses: Int
    ): TitanInferenceRoutePlanner {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        ids.forEach { id ->
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
            feedback = feedback,
            explorationEveryPlanningPasses = 1_000,
            minThroughputSamplesForRanking = 1,
            probationEveryPlanningPasses = probationEveryPlanningPasses
        )
    }

    private fun feedbackKey(id: String): TitanRouteFeedbackKey {
        val installed = installed(id)
        return TitanRouteFeedbackKey(
            runtimeIdentity = ModelRuntimeIdentity(
                modelId = installed.descriptor.id,
                locator = installed.locator,
                sha256 = installed.sha256,
                lengthBytes = installed.lengthBytes,
                ggufVersion = installed.ggufVersion,
                tensorCount = installed.tensorCount,
                metadataKeyValueCount = installed.metadataKeyValueCount
            ),
            backendId = "test-backend",
            selectedCapabilities = setOf(reasoning),
            workloadClass = TitanInferenceWorkloadClass.from(request),
            resourceCondition = TitanResourceConditionClass.UNGOVERNED
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

    private fun response(
        route: TitanInferenceRoute,
        tokensPerSecond: Double
    ): InferenceResponse = InferenceResponse(
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
