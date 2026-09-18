package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class PreferredModelContinuityRoutingTest {
    private val reasoning = TitanCapabilities.REASONING
    private val code = TitanCapabilities.CODE_GENERATION

    @Test
    fun preferredModelWinsNormalHealthyRankingWithinCapabilityProfile() {
        val planner = planner(
            descriptor("a-default", setOf(reasoning)),
            descriptor("z-preferred", setOf(reasoning)),
            explorationEveryPlanningPasses = 100
        )

        val route = planner.plan(
            InferenceRequest(
                prompt = "continue the same turn",
                preferredModelId = ModelId("z-preferred")
            )
        ).getOrThrow()

        assertEquals("z-preferred", route.descriptor.id.value)
    }

    @Test
    fun feedbackPenaltyOverridesPreferredModel() {
        val planner = planner(
            descriptor("a-healthy", setOf(reasoning)),
            descriptor("z-preferred", setOf(reasoning)),
            explorationEveryPlanningPasses = 100
        )
        val request = InferenceRequest(
            prompt = "continue the same turn",
            preferredModelId = ModelId("z-preferred")
        )

        val first = planner.plan(request).getOrThrow()
        assertEquals("z-preferred", first.descriptor.id.value)
        planner.recordFailure(first)

        val fallback = planner.plan(request).getOrThrow()
        assertEquals("a-healthy", fallback.descriptor.id.value)
    }

    @Test
    fun boundedExplorationCanOverridePreferredModel() {
        val planner = planner(
            descriptor("a-explore", setOf(reasoning)),
            descriptor("z-preferred", setOf(reasoning)),
            explorationEveryPlanningPasses = 4
        )
        val request = InferenceRequest(
            prompt = "continue the same turn",
            preferredModelId = ModelId("z-preferred")
        )

        repeat(3) {
            assertEquals("z-preferred", planner.plan(request).getOrThrow().descriptor.id.value)
        }
        assertEquals("a-explore", planner.plan(request).getOrThrow().descriptor.id.value)
    }

    @Test
    fun missingPreferredModelFallsBackToNormalRoute() {
        val planner = planner(
            descriptor("a-available", setOf(reasoning)),
            explorationEveryPlanningPasses = 100
        )

        val route = planner.plan(
            InferenceRequest(
                prompt = "continue",
                preferredModelId = ModelId("missing")
            )
        ).getOrThrow()

        assertEquals("a-available", route.descriptor.id.value)
    }

    @Test
    fun capabilityIncompatiblePreferredModelCannotWeakenMandatoryProfile() {
        val planner = planner(
            descriptor("a-code", setOf(reasoning, code)),
            descriptor("z-general", setOf(reasoning)),
            explorationEveryPlanningPasses = 100
        )

        val route = planner.plan(
            InferenceRequest(
                prompt = "write code",
                requiredCapabilities = setOf(reasoning, code),
                preferredModelId = ModelId("z-general")
            )
        ).getOrThrow()

        assertEquals("a-code", route.descriptor.id.value)
        assertEquals(setOf(reasoning, code), route.selectedCapabilities)
    }

    @Test
    fun capabilityBindingPreservesPreferredModelHint() {
        val preferred = ModelId("turn-model")
        val request = InferenceRequest(
            prompt = "implement parser",
            requiredCapabilities = setOf(reasoning),
            preferredCapabilityProfiles = listOf(setOf(reasoning, code)),
            preferredModelId = preferred
        )

        val bound = request.bindSelectedCapabilities(setOf(reasoning, code))

        assertEquals(preferred, bound.preferredModelId)
        assertEquals(setOf(reasoning, code), bound.requiredCapabilities)
    }

    private fun planner(
        vararg descriptors: ModelDescriptor,
        explorationEveryPlanningPasses: Int
    ): TitanInferenceRoutePlanner {
        val models = InMemoryModelRegistry().also { registry ->
            descriptors.forEach(registry::register)
        }
        val catalog = InMemoryInstalledModelCatalog().also { installedCatalog ->
            descriptors.forEach { installedCatalog.put(installed(it)) }
        }
        val artifacts = object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource =
                StaticSource(model.locator)
        }
        val backends = InferenceBackendRegistry().apply {
            register(SimpleBackend())
        }
        return TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = artifacts,
            backends = backends,
            explorationEveryPlanningPasses = explorationEveryPlanningPasses
        )
    }

    private fun descriptor(id: String, capabilities: Set<CapabilityId>): ModelDescriptor =
        ModelDescriptor(
            id = ModelId(id),
            format = "gguf",
            capabilities = capabilities,
            local = true
        )

    private fun installed(descriptor: ModelDescriptor): InstalledModel = InstalledModel(
        descriptor = descriptor,
        displayName = "${descriptor.id.value}.gguf",
        locator = "memory://${descriptor.id.value}.gguf",
        lengthBytes = 128L,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL,
        installedAtEpochMs = 1L
    )

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private class SimpleBackend : InferenceBackend {
        override val id: String = "continuity-test"
        override fun supports(model: InstalledModel): Boolean = true
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = Result.success(
            InferenceResponse(
                modelId = model.descriptor.id,
                backendId = id,
                text = "ok"
            )
        )
    }
}
