package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TitanScopedExplorationCadenceTest {
    private val reasoning = CapabilityId("reasoning")
    private val coding = CapabilityId("coding")

    @Test
    fun differentWorkloadDoesNotAdvanceCompactExplorationCadence() {
        val planner = planner(
            modelCapabilities = setOf(reasoning),
            explorationEveryPlanningPasses = 2,
            maxExplorationScopes = 64
        )
        val compact = InferenceRequest(prompt = "short", maxOutputTokens = 128)
        val heavy = InferenceRequest(prompt = "x".repeat(9_000), maxOutputTokens = 2_048)

        val compactFirst = planner.plan(compact).getOrThrow()
        assertEquals("a-primary", compactFirst.descriptor.id.value)
        planner.recordSuccess(compactFirst, response(compactFirst, 5.0))

        val heavyFirst = planner.plan(heavy).getOrThrow()
        assertEquals("a-primary", heavyFirst.descriptor.id.value)
        planner.recordSuccess(heavyFirst, response(heavyFirst, 5.0))

        val compactSecond = planner.plan(compact).getOrThrow()
        assertEquals("b-alternative", compactSecond.descriptor.id.value)
    }

    @Test
    fun differentCapabilityProfileDoesNotAdvanceReasoningCadence() {
        val planner = planner(
            modelCapabilities = setOf(reasoning, coding),
            explorationEveryPlanningPasses = 2,
            maxExplorationScopes = 64
        )
        val reasoningRequest = InferenceRequest(
            prompt = "same workload",
            requiredCapabilities = setOf(reasoning),
            maxOutputTokens = 128
        )
        val codingRequest = InferenceRequest(
            prompt = "same workload",
            requiredCapabilities = setOf(coding),
            maxOutputTokens = 128
        )

        val reasoningFirst = planner.plan(reasoningRequest).getOrThrow()
        assertEquals("a-primary", reasoningFirst.descriptor.id.value)
        planner.recordSuccess(reasoningFirst, response(reasoningFirst, 5.0))

        val codingFirst = planner.plan(codingRequest).getOrThrow()
        assertEquals("a-primary", codingFirst.descriptor.id.value)
        planner.recordSuccess(codingFirst, response(codingFirst, 5.0))

        val reasoningSecond = planner.plan(reasoningRequest).getOrThrow()
        assertEquals("b-alternative", reasoningSecond.descriptor.id.value)
    }

    @Test
    fun explorationScopeStateIsBoundedAndEvictsEldestScope() {
        val planner = planner(
            modelCapabilities = setOf(reasoning),
            explorationEveryPlanningPasses = 2,
            maxExplorationScopes = 1
        )
        val compact = InferenceRequest(prompt = "short", maxOutputTokens = 128)
        val heavy = InferenceRequest(prompt = "x".repeat(9_000), maxOutputTokens = 2_048)

        val compactFirst = planner.plan(compact).getOrThrow()
        planner.recordSuccess(compactFirst, response(compactFirst, 5.0))

        val heavyFirst = planner.plan(heavy).getOrThrow()
        planner.recordSuccess(heavyFirst, response(heavyFirst, 5.0))

        val compactAfterEviction = planner.plan(compact).getOrThrow()
        assertEquals("a-primary", compactAfterEviction.descriptor.id.value)
    }

    private fun planner(
        modelCapabilities: Set<CapabilityId>,
        explorationEveryPlanningPasses: Int,
        maxExplorationScopes: Int
    ): TitanInferenceRoutePlanner {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        listOf("a-primary", "b-alternative").forEach { id ->
            val installed = installed(id, modelCapabilities)
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
            minThroughputSamplesForRanking = 1,
            maxExplorationScopes = maxExplorationScopes
        )
    }

    private fun installed(id: String, capabilities: Set<CapabilityId>): InstalledModel {
        val descriptor = ModelDescriptor(
            id = ModelId(id),
            format = "gguf",
            capabilities = capabilities,
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
