package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ResourcePressureReconciliationTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun reconciliationUsesOneBudgetSnapshotAcrossAllBackends() {
        val modelA = installed("a")
        val modelB = installed("b")
        val budget = ResourceBudget(memoryMb = 256, thermalClass = 3)
        val governor = CountingGovernor(budget)
        val first = RecordingReclaimer("r1", setOf(modelA.descriptor.id))
        val second = RecordingReclaimer("r2", setOf(modelB.descriptor.id))
        val runtime = runtime(listOf(modelA, modelB), listOf(first, second), governor)

        val released = runtime.reconcileResources().getOrThrow()

        assertEquals(1, governor.reads)
        assertEquals(setOf(modelA.descriptor.id, modelB.descriptor.id), released)
        assertSame(budget, first.seenBudgets.single())
        assertSame(budget, second.seenBudgets.single())
    }

    @Test
    fun backendFailureDoesNotPreventHealthyPeerReconciliation() {
        val model = installed("m")
        val failing = RecordingReclaimer(
            id = "failing",
            released = emptySet(),
            failure = IllegalStateException("release failed")
        )
        val healthy = RecordingReclaimer("healthy", setOf(model.descriptor.id))
        val runtime = runtime(
            listOf(model),
            listOf(failing, healthy),
            CountingGovernor(ResourceBudget(memoryMb = 128))
        )

        val result = runtime.reconcileResources()

        assertTrue(result.isFailure)
        assertEquals(1, failing.calls)
        assertEquals(1, healthy.calls)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("failing:release failed"))
    }

    @Test
    fun reconciliationRequiresGovernor() {
        val model = installed("m")
        val reclaimer = RecordingReclaimer("r", setOf(model.descriptor.id))
        val runtime = runtime(listOf(model), listOf(reclaimer), governor = null)

        val result = runtime.reconcileResources()

        assertTrue(result.isFailure)
        assertEquals(0, reclaimer.calls)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("requires a resource governor"))
    }

    @Test
    fun releasedPreparedModelLosesOneShotHandoff() {
        val model = installed("m")
        val legacy = LegacyBackend("legacy")
        val warmable = WarmableReclaimer("warmable", model.descriptor.id)
        val governor = CountingGovernor(ResourceBudget(memoryMb = 512, thermalClass = 1))
        val runtime = runtime(listOf(model), listOf(legacy, warmable), governor)
        val request = InferenceRequest("prepared route")

        runtime.prepare(request).getOrThrow()
        governor.budget = ResourceBudget(memoryMb = 64, thermalClass = 4)
        val released = runtime.reconcileResources().getOrThrow()
        governor.budget = ResourceBudget(memoryMb = 512, thermalClass = 1)
        val response = runtime.infer(request).getOrThrow()

        assertEquals(setOf(model.descriptor.id), released)
        assertEquals("legacy", response.backendId)
        assertEquals(1, legacy.inferCalls)
        assertEquals(0, warmable.inferCalls)
    }

    @Test
    fun noReleasePreservesPreparedHandoff() {
        val model = installed("m")
        val legacy = LegacyBackend("legacy")
        val warmable = WarmableReclaimer("warmable", model.descriptor.id)
        val governor = CountingGovernor(ResourceBudget(memoryMb = 512, thermalClass = 1))
        val runtime = runtime(listOf(model), listOf(legacy, warmable), governor)
        val request = InferenceRequest("prepared route")

        runtime.prepare(request).getOrThrow()
        val released = runtime.reconcileResources().getOrThrow()
        val response = runtime.infer(request).getOrThrow()

        assertTrue(released.isEmpty())
        assertEquals("warmable", response.backendId)
        assertEquals(0, legacy.inferCalls)
        assertEquals(1, warmable.inferCalls)
    }

    private fun runtime(
        modelsToInstall: List<InstalledModel>,
        backendList: List<InferenceBackend>,
        governor: ResourceGovernor?
    ): TitanCortexRuntime {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        modelsToInstall.forEach {
            models.register(it.descriptor)
            catalog.put(it)
        }
        val resolver = object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource =
                StaticSource(model.locator, model.lengthBytes)
        }
        val registry = InferenceBackendRegistry().apply { backendList.forEach(::register) }
        return TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = resolver,
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

    private class RecordingReclaimer(
        override val id: String,
        private val released: Set<ModelId>,
        private val failure: Throwable? = null
    ) : ResourceReclaimingInferenceBackend {
        var calls: Int = 0
        val seenBudgets = mutableListOf<ResourceBudget>()
        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(64, 2, 2048)
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = Result.success(
            InferenceResponse(model.descriptor.id, id, "unused")
        )
        override fun reconcileResources(
            budget: ResourceBudget
        ): Result<BackendResourceReconciliation> {
            calls += 1
            seenBudgets += budget
            return failure?.let { Result.failure(it) }
                ?: Result.success(BackendResourceReconciliation(released))
        }
    }

    private class LegacyBackend(override val id: String) : ManagedInferenceBackend {
        var inferCalls: Int = 0
        override fun supports(model: InstalledModel): Boolean = true
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

    private class WarmableReclaimer(
        override val id: String,
        private val modelId: ModelId
    ) : PreparableInferenceBackend, ResourceReclaimingInferenceBackend {
        private var resident = false
        var inferCalls: Int = 0

        override fun supports(model: InstalledModel): Boolean = model.descriptor.id == modelId
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(128, 2, 2048)
        override fun prepare(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<BackendPreparationResult> {
            resident = true
            return Result.success(BackendPreparationResult(sessionReused = false))
        }
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferCalls += 1
            return Result.success(InferenceResponse(model.descriptor.id, id, "warmable"))
        }
        override fun reconcileResources(
            budget: ResourceBudget
        ): Result<BackendResourceReconciliation> {
            val release = resident && (budget.thermalClass >= 4 || budget.memoryMb < 128)
            if (!release) return Result.success(BackendResourceReconciliation())
            resident = false
            return Result.success(BackendResourceReconciliation(setOf(modelId)))
        }
    }

    private class StaticSource(
        override val locator: String,
        override val lengthBytes: Long?
    ) : ModelArtifactSource {
        override val displayName: String = "resource-pressure.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
