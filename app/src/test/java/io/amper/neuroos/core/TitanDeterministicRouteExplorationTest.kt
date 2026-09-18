package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TitanDeterministicRouteExplorationTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun boundedExplorationSamplesAlternativeThenUsesFasterConfidentRoute() {
        val feedback = TitanRuntimeFeedback(minSlowSamplesForPenalty = 2)
        val planner = planner(
            ids = listOf("a-primary", "b-faster"),
            feedback = feedback,
            explorationEveryPlanningPasses = 2,
            minThroughputSamplesForRanking = 1
        )
        val request = InferenceRequest("route me")

        val first = planner.plan(request).getOrThrow()
        assertEquals("a-primary", first.descriptor.id.value)
        planner.recordSuccess(first, response(first, tokensPerSecond = 5.0))

        val second = planner.plan(request).getOrThrow()
        assertEquals("b-faster", second.descriptor.id.value)
        planner.recordSuccess(second, response(second, tokensPerSecond = 12.0))

        val third = planner.plan(request).getOrThrow()
        assertEquals("b-faster", third.descriptor.id.value)
    }

    @Test
    fun explorationDoesNotOverrideFailurePenalty() {
        val feedback = TitanRuntimeFeedback()
        val planner = planner(
            ids = listOf("a-primary", "b-fallback"),
            feedback = feedback,
            explorationEveryPlanningPasses = 1,
            minThroughputSamplesForRanking = 2
        )
        val request = InferenceRequest("route me")

        val first = planner.plan(request).getOrThrow()
        assertEquals("a-primary", first.descriptor.id.value)
        planner.recordFailure(first)

        val second = planner.plan(request).getOrThrow()
        assertEquals("b-fallback", second.descriptor.id.value)
    }

    @Test
    fun explorationStopsOnceHealthyRoutesHaveEnoughSamples() {
        val feedback = TitanRuntimeFeedback()
        val planner = planner(
            ids = listOf("a-primary", "b-faster"),
            feedback = feedback,
            explorationEveryPlanningPasses = 1,
            minThroughputSamplesForRanking = 1
        )
        val request = InferenceRequest("route me")

        val first = planner.plan(request).getOrThrow()
        planner.recordSuccess(first, response(first, tokensPerSecond = 4.0))
        val second = planner.plan(request).getOrThrow()
        planner.recordSuccess(second, response(second, tokensPerSecond = 9.0))

        val third = planner.plan(request).getOrThrow()
        val fourth = planner.plan(request).getOrThrow()
        assertEquals("b-faster", third.descriptor.id.value)
        assertEquals("b-faster", fourth.descriptor.id.value)
    }

    private fun planner(
        ids: List<String>,
        feedback: TitanRuntimeFeedback,
        explorationEveryPlanningPasses: Int,
        minThroughputSamplesForRanking: Int
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
            explorationEveryPlanningPasses = explorationEveryPlanningPasses,
            minThroughputSamplesForRanking = minThroughputSamplesForRanking
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
