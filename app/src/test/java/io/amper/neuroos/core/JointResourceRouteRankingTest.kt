package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class JointResourceRouteRankingTest {
    private val reasoning = CapabilityId("reasoning")
    private val request = InferenceRequest("choose a complete route")
    private val budget = ResourceBudget(memoryMb = 512, thermalClass = 1)

    @Test
    fun governedPlannerCanPreferLighterModelBackendPairAcrossModelBoundary() {
        val heavy = installed("a-heavy", 500L * 1024L * 1024L)
        val light = installed("b-light", 100L * 1024L * 1024L)
        val backend = modelCostBackend(
            id = "shared",
            state = BackendState.READY,
            memoryByModel = mapOf("a-heavy" to 500, "b-light" to 100)
        )
        val planner = planner(listOf(heavy, light), listOf(backend))

        val route = planner.plan(request, budget).getOrThrow()

        assertEquals(ModelId("b-light"), route.installed.descriptor.id)
        assertEquals("shared", route.backend.id)
        assertNotNull(route.backendPolicyScore)
    }

    @Test
    fun backendHealthRemainsDominantAcrossDifferentModels() {
        val degradedLight = installed("a-degraded-light", 32L * 1024L * 1024L)
        val readyHeavy = installed("b-ready-heavy", 500L * 1024L * 1024L)
        val degraded = modelSpecificBackend(
            id = "degraded",
            supportedModel = "a-degraded-light",
            state = BackendState.DEGRADED,
            memoryMb = 32
        )
        val ready = modelSpecificBackend(
            id = "ready",
            supportedModel = "b-ready-heavy",
            state = BackendState.READY,
            memoryMb = 500
        )
        val planner = planner(listOf(degradedLight, readyHeavy), listOf(degraded, ready))

        val route = planner.plan(request, budget).getOrThrow()

        assertEquals(ModelId("b-ready-heavy"), route.installed.descriptor.id)
        assertEquals("ready", route.backend.id)
    }

    @Test
    fun ungovernedPlannerPreservesDeterministicModelOrder() {
        val heavy = installed("a-heavy", 500L * 1024L * 1024L)
        val light = installed("b-light", 100L * 1024L * 1024L)
        val backend = modelCostBackend(
            id = "shared",
            state = BackendState.READY,
            memoryByModel = mapOf("a-heavy" to 500, "b-light" to 100)
        )
        val planner = planner(listOf(heavy, light), listOf(backend))

        val route = planner.plan(request, budget = null).getOrThrow()

        assertEquals(ModelId("a-heavy"), route.installed.descriptor.id)
        assertEquals(null, route.backendPolicyScore)
    }

    @Test
    fun feedbackPenaltyStillOverridesHigherResourcePolicyScore() {
        val heavy = installed("a-heavy", 500L * 1024L * 1024L)
        val light = installed("b-light", 100L * 1024L * 1024L)
        val backend = modelCostBackend(
            id = "shared",
            state = BackendState.READY,
            memoryByModel = mapOf("a-heavy" to 500, "b-light" to 100)
        )
        val planner = planner(listOf(heavy, light), listOf(backend))

        val first = planner.plan(request, budget).getOrThrow()
        assertEquals(ModelId("b-light"), first.installed.descriptor.id)
        planner.recordFailure(first)

        val second = planner.plan(request, budget).getOrThrow()

        assertEquals(ModelId("a-heavy"), second.installed.descriptor.id)
    }

    @Test
    fun equalGovernedPolicyScoresRetainModelRegistryOrder() {
        val first = installed("a-first", 128L * 1024L * 1024L)
        val second = installed("b-second", 128L * 1024L * 1024L)
        val backend = modelCostBackend(
            id = "shared",
            state = BackendState.READY,
            memoryByModel = mapOf("a-first" to 128, "b-second" to 128)
        )
        val planner = planner(listOf(first, second), listOf(backend))

        val route = planner.plan(request, budget).getOrThrow()

        assertEquals(ModelId("a-first"), route.installed.descriptor.id)
    }

    @Test
    fun jointRankingReusesFrozenEvaluationWithoutExtraEstimateCalls() {
        val first = installed("a-heavy", 500L * 1024L * 1024L)
        val second = installed("b-light", 100L * 1024L * 1024L)
        var estimateCalls = 0
        val backend = object : ManagedInferenceBackend {
            override val id: String = "counting"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost {
                estimateCalls += 1
                val memory = if (model.descriptor.id.value == "a-heavy") 500 else 100
                return InferenceCost(memory, 2, 2048)
            }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "ok")
            )
        }
        val planner = planner(listOf(first, second), listOf(backend))

        val route = planner.plan(request, budget).getOrThrow()

        assertEquals(ModelId("b-light"), route.installed.descriptor.id)
        assertEquals(2, estimateCalls)
        assertTrue(route.backendPolicyScore!! > 0)
    }

    private fun planner(
        installedModels: List<InstalledModel>,
        backendList: List<InferenceBackend>
    ): TitanInferenceRoutePlanner {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        installedModels.forEach {
            models.register(it.descriptor)
            catalog.put(it)
        }
        val allowed = installedModels.associateBy { it.descriptor.id }
        return TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource? =
                    allowed[model.descriptor.id]?.let { StaticSource(it.locator, it.lengthBytes) }
            },
            backends = InferenceBackendRegistry().apply {
                backendList.forEach(::register)
            }
        )
    }

    private fun installed(id: String, lengthBytes: Long): InstalledModel {
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
            lengthBytes = lengthBytes,
            sha256 = "a".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL
        )
    }

    private fun modelCostBackend(
        id: String,
        state: BackendState,
        memoryByModel: Map<String, Int>
    ): ManagedInferenceBackend = object : ManagedInferenceBackend {
        override val id: String = id
        override fun supports(model: InstalledModel): Boolean = model.descriptor.id.value in memoryByModel
        override fun health(): BackendHealth = BackendHealth(state)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(
                estimatedMemoryMb = memoryByModel.getValue(model.descriptor.id.value),
                preferredThreads = 2,
                contextTokens = 2048
            )
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
    }

    private fun modelSpecificBackend(
        id: String,
        supportedModel: String,
        state: BackendState,
        memoryMb: Int
    ): ManagedInferenceBackend = object : ManagedInferenceBackend {
        override val id: String = id
        override fun supports(model: InstalledModel): Boolean = model.descriptor.id.value == supportedModel
        override fun health(): BackendHealth = BackendHealth(state)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(memoryMb, 2, 2048)
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
    }

    private class StaticSource(
        override val locator: String,
        override val lengthBytes: Long?
    ) : ModelArtifactSource {
        override val displayName: String = "joint-route.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
