package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class AutomaticResourceReconciliationTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun governedInferenceUsesOneBudgetSnapshotForReconcileAndRoute() {
        val model = installed("m")
        val budget = ResourceBudget(memoryMb = 512, thermalClass = 1)
        val governor = CountingGovernor(budget)
        val backend = RecordingBackend("managed", model.descriptor.id)
        val runtime = runtime(listOf(model), listOf(backend), governor)

        val response = runtime.infer(InferenceRequest("hello")).getOrThrow()

        assertEquals("managed", response.backendId)
        assertEquals(1, governor.reads)
        assertEquals(1, backend.reconcileCalls)
        assertSame(budget, backend.seenBudgets.single())
        assertEquals(1, backend.inferCalls)
    }

    @Test
    fun governedPreparationUsesOneBudgetSnapshotForReconcileAndRoute() {
        val model = installed("m")
        val budget = ResourceBudget(memoryMb = 512, thermalClass = 1)
        val governor = CountingGovernor(budget)
        val backend = RecordingBackend("managed", model.descriptor.id)
        val runtime = runtime(listOf(model), listOf(backend), governor)

        val prepared = runtime.prepare(InferenceRequest("warm")).getOrThrow()

        assertEquals("managed", prepared.backendId)
        assertEquals(1, governor.reads)
        assertEquals(1, backend.reconcileCalls)
        assertSame(budget, backend.seenBudgets.single())
        assertEquals(1, backend.prepareCalls)
    }

    @Test
    fun reconciliationFailureStopsInferenceBeforeExecution() {
        val model = installed("m")
        val governor = CountingGovernor(ResourceBudget(memoryMb = 512))
        val backend = RecordingBackend(
            id = "broken",
            modelId = model.descriptor.id,
            reconcileFailure = IllegalStateException("cannot reclaim")
        )
        val runtime = runtime(listOf(model), listOf(backend), governor)

        val result = runtime.infer(InferenceRequest("must not execute"))

        assertTrue(result.isFailure)
        assertEquals(1, governor.reads)
        assertEquals(1, backend.reconcileCalls)
        assertEquals(0, backend.inferCalls)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("broken:cannot reclaim"))
    }

    @Test
    fun reconciliationFailureStopsPreparationBeforeBackendPrepare() {
        val model = installed("m")
        val governor = CountingGovernor(ResourceBudget(memoryMb = 512))
        val backend = RecordingBackend(
            id = "broken",
            modelId = model.descriptor.id,
            reconcileFailure = IllegalStateException("cannot reclaim")
        )
        val runtime = runtime(listOf(model), listOf(backend), governor)

        val result = runtime.prepare(InferenceRequest("must not prepare"))

        assertTrue(result.isFailure)
        assertEquals(1, backend.reconcileCalls)
        assertEquals(0, backend.prepareCalls)
    }

    @Test
    fun ungovernedInferenceDoesNotAutoReconcile() {
        val model = installed("m")
        val backend = RecordingBackend("managed", model.descriptor.id)
        val runtime = runtime(listOf(model), listOf(backend), governor = null)

        val response = runtime.infer(InferenceRequest("ungoverned")).getOrThrow()

        assertEquals("managed", response.backendId)
        assertEquals(0, backend.reconcileCalls)
        assertEquals(1, backend.inferCalls)
    }

    @Test
    fun automaticPressureReleaseClearsPreparedHandoffBeforeRouting() {
        val model = installed("m")
        val governor = CountingGovernor(ResourceBudget(memoryMb = 512, thermalClass = 1))
        val legacy = LegacyBackend("legacy", model.descriptor.id)
        val warmable = RecordingBackend("warmable", model.descriptor.id, memoryMb = 128)
        val runtime = runtime(listOf(model), listOf(legacy, warmable), governor)
        val request = InferenceRequest("handoff")

        runtime.prepare(request).getOrThrow()
        warmable.releaseOnNextReconcile = true
        governor.budget = ResourceBudget(memoryMb = 64, thermalClass = 1)
        val response = runtime.infer(request).getOrThrow()

        assertEquals("legacy", response.backendId)
        assertEquals(0, warmable.inferCalls)
        assertEquals(1, legacy.inferCalls)
    }

    private fun runtime(
        installedModels: List<InstalledModel>,
        backendList: List<InferenceBackend>,
        governor: ResourceGovernor?
    ): TitanCortexRuntime {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        installedModels.forEach {
            models.register(it.descriptor)
            catalog.put(it)
        }
        val registry = InferenceBackendRegistry().apply { backendList.forEach(::register) }
        return TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = StaticResolver(),
            backends = registry,
            governor = governor
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
            lengthBytes = 128L * 1024L * 1024L,
            sha256 = "a".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL
        )
    }

    private class CountingGovernor(var budget: ResourceBudget) : ResourceGovernor {
        var reads: Int = 0
        override fun currentBudget(): ResourceBudget {
            reads += 1
            return budget
        }
        override fun allows(agentCount: Int): Boolean = agentCount <= budget.maxConcurrentAgents
    }

    private class RecordingBackend(
        override val id: String,
        private val modelId: ModelId,
        private val memoryMb: Int = 64,
        private val reconcileFailure: Throwable? = null
    ) : PreparableInferenceBackend, ResourceReclaimingInferenceBackend {
        var reconcileCalls: Int = 0
        var prepareCalls: Int = 0
        var inferCalls: Int = 0
        var releaseOnNextReconcile: Boolean = false
        val seenBudgets = mutableListOf<ResourceBudget>()

        override fun supports(model: InstalledModel): Boolean = model.descriptor.id == modelId
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(memoryMb, 2, 2048)

        override fun reconcileResources(
            budget: ResourceBudget
        ): Result<BackendResourceReconciliation> {
            reconcileCalls += 1
            seenBudgets += budget
            reconcileFailure?.let { return Result.failure(it) }
            if (!releaseOnNextReconcile) return Result.success(BackendResourceReconciliation())
            releaseOnNextReconcile = false
            return Result.success(BackendResourceReconciliation(setOf(modelId)))
        }

        override fun prepare(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<BackendPreparationResult> {
            prepareCalls += 1
            return Result.success(BackendPreparationResult(sessionReused = false))
        }

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferCalls += 1
            return Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
        }
    }

    private class LegacyBackend(
        override val id: String,
        private val modelId: ModelId
    ) : ManagedInferenceBackend {
        var inferCalls: Int = 0
        override fun supports(model: InstalledModel): Boolean = model.descriptor.id == modelId
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(32, 2, 2048)
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferCalls += 1
            return Result.success(InferenceResponse(model.descriptor.id, id, "legacy"))
        }
    }

    private class StaticResolver : ModelArtifactResolver {
        override fun resolve(model: InstalledModel): ModelArtifactSource =
            StaticSource(model.locator, model.lengthBytes)
    }

    private class StaticSource(
        override val locator: String,
        override val lengthBytes: Long?
    ) : ModelArtifactSource {
        override val displayName: String = "automatic-reconcile.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
