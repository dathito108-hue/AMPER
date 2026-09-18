package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TitanInferenceRoutePlannerTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun plannerReturnsIdentityBoundExecutableRoute() {
        val models = registry("brain")
        val catalog = catalog(installed("brain"))
        val backend = CostAwareBackend(mapOf("brain" to 128))
        val planner = planner(models, catalog, resolverFor("brain"), backend)
        val budget = ResourceBudget(memoryMb = 512, thermalClass = 1)

        val route = planner.plan(InferenceRequest("prove route"), budget).getOrThrow()

        assertEquals("brain", route.descriptor.id.value)
        assertEquals("brain", route.installed.descriptor.id.value)
        assertEquals("cost-aware", route.backend.id)
        assertEquals("brain", route.runtimeIdentity.modelId.value)
        assertEquals("memory://brain.gguf", route.runtimeIdentity.locator)
        assertEquals(budget, route.budget)
        assertTrue(backend.inferredModels.isEmpty())
    }

    @Test
    fun plannerSkipsRegistryCatalogDescriptorMismatch() {
        val models = registry("a-stale", "b-good")
        val staleInstalled = installed(
            id = "a-stale",
            descriptor = descriptor("a-stale").copy(local = false)
        )
        val catalog = catalog(staleInstalled, installed("b-good"))
        val backend = CostAwareBackend(mapOf("a-stale" to 64, "b-good" to 64))
        val planner = planner(
            models,
            catalog,
            resolverFor("a-stale", "b-good"),
            backend
        )

        val route = planner.plan(InferenceRequest("avoid stale registry")).getOrThrow()

        assertEquals("b-good", route.installed.descriptor.id.value)
    }

    @Test
    fun plannerRejectsResolvedLocatorSubstitutionBeforeExecution() {
        val models = registry("brain")
        val catalog = catalog(installed("brain"))
        val backend = CostAwareBackend(mapOf("brain" to 64))
        val resolver = object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource =
                StaticSource(locator = "memory://different.gguf")
        }
        val planner = planner(models, catalog, resolver, backend)

        val result = planner.plan(InferenceRequest("keep identity pinned"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("artifact-identity-mismatch") == true)
        assertTrue(backend.inferredModels.isEmpty())
    }

    @Test
    fun executionAwareAgentLeaseUsesFeasibleSecondModel() {
        val models = registry("a-heavy", "b-mobile")
        val catalog = catalog(installed("a-heavy"), installed("b-mobile"))
        val backend = CostAwareBackend(
            mapOf(
                "a-heavy" to 1_024,
                "b-mobile" to 256
            )
        )
        val planner = planner(
            models,
            catalog,
            resolverFor("a-heavy", "b-mobile"),
            backend
        )
        val governor = MobileResourceGovernor(
            ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512, thermalClass = 1)
        )
        val agents = EphemeralAgentFabric(governor, models, planner)

        val lease = agents.spawn(
            AgentSpec(
                role = "reasoning-specialist",
                requiredCapabilities = setOf(reasoning),
                purpose = "mobile reasoning"
            )
        ).getOrThrow()

        assertEquals("b-mobile", lease.model.value)
        assertEquals("cost-aware", lease.backendId)
        assertNotNull(lease.runtimeIdentity)
        assertEquals("b-mobile", lease.runtimeIdentity!!.modelId.value)
        assertEquals(1, agents.active().size)
        assertTrue(backend.inferredModels.isEmpty())
    }

    @Test
    fun executionAwareAgentLeaseFailsClosedWhenNoRouteFitsBudget() {
        val models = registry("brain")
        val catalog = catalog(installed("brain"))
        val backend = CostAwareBackend(mapOf("brain" to 64))
        val planner = planner(models, catalog, resolverFor("brain"), backend)
        val governor = MobileResourceGovernor(
            ResourceBudget(maxConcurrentAgents = 2, memoryMb = 4_096, thermalClass = 4)
        )
        val agents = EphemeralAgentFabric(governor, models, planner)

        val result = agents.spawn(
            AgentSpec(
                role = "reasoning-specialist",
                requiredCapabilities = setOf(reasoning),
                purpose = "thermal gated"
            )
        )

        assertTrue(result.isFailure)
        assertTrue(agents.active().isEmpty())
    }

    @Test
    fun referenceAgentLeaseRemainsDescriptorOnlyWithoutExecutionPlanner() {
        val models = registry("brain")
        val agents = EphemeralAgentFabric(
            governor = MobileResourceGovernor(ResourceBudget(maxConcurrentAgents = 2)),
            models = models
        )

        val lease = agents.spawn(
            AgentSpec(
                role = "reference",
                requiredCapabilities = setOf(reasoning),
                purpose = "contract test"
            )
        ).getOrThrow()

        assertEquals("brain", lease.model.value)
        assertNull(lease.backendId)
        assertNull(lease.runtimeIdentity)
    }

    private fun planner(
        models: ModelRegistry,
        catalog: InstalledModelCatalog,
        resolver: ModelArtifactResolver,
        backend: InferenceBackend
    ): TitanInferenceRoutePlanner = TitanInferenceRoutePlanner(
        models = models,
        catalog = catalog,
        artifacts = resolver,
        backends = InferenceBackendRegistry().apply { register(backend) }
    )

    private fun registry(vararg ids: String): InMemoryModelRegistry =
        InMemoryModelRegistry().also { registry -> ids.forEach { registry.register(descriptor(it)) } }

    private fun catalog(vararg installed: InstalledModel): InMemoryInstalledModelCatalog =
        InMemoryInstalledModelCatalog().also { catalog -> installed.forEach(catalog::put) }

    private fun descriptor(id: String): ModelDescriptor = ModelDescriptor(
        id = ModelId(id),
        format = "gguf",
        capabilities = setOf(reasoning),
        local = true
    )

    private fun installed(
        id: String,
        descriptor: ModelDescriptor = descriptor(id)
    ): InstalledModel = InstalledModel(
        descriptor = descriptor,
        displayName = "$id.gguf",
        locator = "memory://$id.gguf",
        lengthBytes = 128L,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL,
        installedAtEpochMs = 1L
    )

    private fun resolverFor(vararg ids: String): ModelArtifactResolver {
        val allowed = ids.toSet()
        return object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource? =
                if (model.descriptor.id.value in allowed) StaticSource(model.locator) else null
        }
    }

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private class CostAwareBackend(
        private val memoryByModelMb: Map<String, Int>
    ) : ManagedInferenceBackend {
        override val id: String = "cost-aware"
        val inferredModels = mutableListOf<String>()

        override fun supports(model: InstalledModel): Boolean =
            model.descriptor.id.value in memoryByModelMb

        override fun health(): BackendHealth = BackendHealth(BackendState.READY)

        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(
                estimatedMemoryMb = memoryByModelMb.getValue(model.descriptor.id.value),
                preferredThreads = 1,
                contextTokens = 2_048
            )

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferredModels += model.descriptor.id.value
            return Result.success(
                InferenceResponse(
                    modelId = model.descriptor.id,
                    backendId = id,
                    text = "processed:${model.descriptor.id.value}"
                )
            )
        }
    }
}
