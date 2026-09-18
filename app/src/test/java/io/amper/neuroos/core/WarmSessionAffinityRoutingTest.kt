package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class WarmSessionAffinityRoutingTest {
    private val reasoning = CapabilityId("reasoning")
    private val request = InferenceRequest("reuse a compatible warm session")
    private val budget = ResourceBudget(memoryMb = 1_000, thermalClass = 1)

    @Test
    fun governedPlannerPrefersWarmCompatibleSessionAcrossModels() {
        val cold = installed("a-cold", 100L * 1024L * 1024L)
        val warm = installed("b-warm", 900L * 1024L * 1024L)
        val backend = object : ManagedInferenceBackend {
            override val id: String = "shared"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(
                    estimatedMemoryMb = if (model.descriptor.id.value == "a-cold") 100 else 900,
                    preferredThreads = 2,
                    contextTokens = 2048
                )
            override fun sessionAffinity(
                model: InstalledModel,
                request: InferenceRequest,
                cost: InferenceCost
            ): BackendSessionAffinity =
                if (model.descriptor.id.value == "b-warm") {
                    BackendSessionAffinity.WARM_COMPATIBLE
                } else {
                    BackendSessionAffinity.COLD
                }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "ok")
            )
        }

        val route = planner(listOf(cold, warm), listOf(backend)).plan(request, budget).getOrThrow()

        assertEquals(ModelId("b-warm"), route.installed.descriptor.id)
        assertEquals("shared", route.backend.id)
    }

    @Test
    fun readyColdRouteStillOutranksWarmDegradedRoute() {
        val warm = installed("a-warm-degraded", 100L * 1024L * 1024L)
        val ready = installed("b-ready-cold", 900L * 1024L * 1024L)
        val warmDegraded = specificBackend(
            id = "warm-degraded",
            supportedModel = "a-warm-degraded",
            state = BackendState.DEGRADED,
            memoryMb = 100,
            affinity = BackendSessionAffinity.WARM_COMPATIBLE
        )
        val readyCold = specificBackend(
            id = "ready-cold",
            supportedModel = "b-ready-cold",
            state = BackendState.READY,
            memoryMb = 900,
            affinity = BackendSessionAffinity.COLD
        )

        val route = planner(listOf(warm, ready), listOf(warmDegraded, readyCold))
            .plan(request, budget)
            .getOrThrow()

        assertEquals(ModelId("b-ready-cold"), route.installed.descriptor.id)
        assertEquals("ready-cold", route.backend.id)
    }

    @Test
    fun ungovernedPlannerPreservesModelOrderAndDoesNotProbeAffinity() {
        val first = installed("a-first", 900L * 1024L * 1024L)
        val second = installed("b-warm", 100L * 1024L * 1024L)
        var affinityCalls = 0
        val backend = object : ManagedInferenceBackend {
            override val id: String = "shared"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(100, 2, 2048)
            override fun sessionAffinity(
                model: InstalledModel,
                request: InferenceRequest,
                cost: InferenceCost
            ): BackendSessionAffinity {
                affinityCalls += 1
                return if (model.descriptor.id.value == "b-warm") {
                    BackendSessionAffinity.WARM_COMPATIBLE
                } else {
                    BackendSessionAffinity.COLD
                }
            }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "ok")
            )
        }

        val route = planner(listOf(first, second), listOf(backend))
            .plan(request, budget = null)
            .getOrThrow()

        assertEquals(ModelId("a-first"), route.installed.descriptor.id)
        assertEquals(0, affinityCalls)
    }

    @Test
    fun governedAffinityReceivesFrozenCostWithoutSecondEstimate() {
        val model = installed("m", 128L * 1024L * 1024L)
        val frozenCost = InferenceCost(128, 3, 2048)
        var estimateCalls = 0
        var affinityCalls = 0
        var receivedFrozenInstance = false
        val backend = object : ManagedInferenceBackend {
            override val id: String = "counting"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost {
                estimateCalls += 1
                return frozenCost
            }
            override fun sessionAffinity(
                model: InstalledModel,
                request: InferenceRequest,
                cost: InferenceCost
            ): BackendSessionAffinity {
                affinityCalls += 1
                receivedFrozenInstance = cost === frozenCost
                return BackendSessionAffinity.WARM_COMPATIBLE
            }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "ok")
            )
        }

        planner(listOf(model), listOf(backend)).plan(request, budget).getOrThrow()

        assertEquals(1, estimateCalls)
        assertEquals(1, affinityCalls)
        assertTrue(receivedFrozenInstance)
    }

    @Test
    fun affinityFailureIsIsolatedToBrokenBackend() {
        val model = installed("m", 128L * 1024L * 1024L)
        val broken = object : ManagedInferenceBackend {
            override val id: String = "broken"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 2, 2048)
            override fun sessionAffinity(
                model: InstalledModel,
                request: InferenceRequest,
                cost: InferenceCost
            ): BackendSessionAffinity = throw UnsatisfiedLinkError("native warm-state probe failed")
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = error("broken backend must not execute")
        }
        val healthy = specificBackend(
            id = "healthy",
            supportedModel = "m",
            state = BackendState.READY,
            memoryMb = 128,
            affinity = BackendSessionAffinity.COLD
        )

        val route = planner(listOf(model), listOf(broken, healthy)).plan(request, budget).getOrThrow()

        assertEquals("healthy", route.backend.id)
    }

    @Test
    fun affinityIsNotReadForBackendRejectedByMemoryGate() {
        val model = installed("m", 128L * 1024L * 1024L)
        var affinityCalled = false
        val overBudget = object : ManagedInferenceBackend {
            override val id: String = "over-budget"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(2_000, 2, 2048)
            override fun sessionAffinity(
                model: InstalledModel,
                request: InferenceRequest,
                cost: InferenceCost
            ): BackendSessionAffinity {
                affinityCalled = true
                return BackendSessionAffinity.WARM_COMPATIBLE
            }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = error("over-budget backend must not execute")
        }
        val healthy = specificBackend(
            id = "healthy",
            supportedModel = "m",
            state = BackendState.READY,
            memoryMb = 128,
            affinity = BackendSessionAffinity.COLD
        )

        val route = planner(listOf(model), listOf(overBudget, healthy)).plan(request, budget).getOrThrow()

        assertEquals("healthy", route.backend.id)
        assertFalse(affinityCalled)
    }

    @Test
    fun existingManagedBackendDefaultsToColdAffinity() {
        val model = installed("m", 128L * 1024L * 1024L)
        val cost = InferenceCost(128, 2, 2048)
        val backend = object : ManagedInferenceBackend {
            override val id: String = "legacy-managed"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost = cost
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "ok")
            )
        }

        assertEquals(
            BackendSessionAffinity.COLD,
            backend.sessionAffinity(model, request, cost)
        )
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

    private fun specificBackend(
        id: String,
        supportedModel: String,
        state: BackendState,
        memoryMb: Int,
        affinity: BackendSessionAffinity
    ): ManagedInferenceBackend = object : ManagedInferenceBackend {
        override val id: String = id
        override fun supports(model: InstalledModel): Boolean = model.descriptor.id.value == supportedModel
        override fun health(): BackendHealth = BackendHealth(state)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(memoryMb, 2, 2048)
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

    private class StaticSource(
        override val locator: String,
        override val lengthBytes: Long?
    ) : ModelArtifactSource {
        override val displayName: String = "warm-affinity.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
