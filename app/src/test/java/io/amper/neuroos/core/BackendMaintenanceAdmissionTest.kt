package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BackendMaintenanceAdmissionTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun maintenanceLeaseExcludesBackendFromPlanningAndFinalAcquire() {
        val gate = TitanExecutionAdmissionGate()
        val budget = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        val backend = TitanBackendExecutionAdmission("backend-a", Int.MAX_VALUE)
        val maintenance = gate.tryAcquireBackendMaintenance("backend-a")
        requireNotNull(maintenance)

        val availability = gate.planningAvailability(budget).getOrThrow()
        assertTrue("backend-a" in availability.saturatedBackendIds)

        val blocked = gate.acquire(
            budget = budget,
            estimatedMemoryMb = 64,
            backendAdmission = backend
        )
        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull()?.message.orEmpty().contains("maintenance active"))

        maintenance.close()
        val admitted = gate.acquire(
            budget = budget,
            estimatedMemoryMb = 64,
            backendAdmission = backend
        ).getOrThrow()
        admitted.close()
    }

    @Test
    fun activeUnlimitedBackendExecutionDefersMaintenanceWithoutWaiting() {
        val gate = TitanExecutionAdmissionGate()
        val budget = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        val execution = gate.acquire(
            budget = budget,
            estimatedMemoryMb = 64,
            backendAdmission = TitanBackendExecutionAdmission("unlimited", Int.MAX_VALUE)
        ).getOrThrow()

        assertNull(gate.tryAcquireBackendMaintenance("unlimited"))

        execution.close()
        val maintenance = gate.tryAcquireBackendMaintenance("unlimited")
        requireNotNull(maintenance)
        maintenance.close()
    }

    @Test
    fun maintenanceRaceTriggersBoundedReplanToAnotherBackend() {
        val gate = TitanExecutionAdmissionGate()
        val replanner = TitanBoundedAdmissionReplanner(gate, maxReplans = 1)
        val budget = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        val planningPasses = AtomicInteger(0)
        val heldMaintenance = AtomicReference<TitanBackendMaintenanceLease>()

        val admitted = replanner.acquire(
            baseBudget = budget,
            estimatedMemoryMb = { candidate: Candidate -> candidate.memoryMb },
            backendAdmission = { candidate -> candidate.backendAdmission }
        ) { availability ->
            when (planningPasses.incrementAndGet()) {
                1 -> {
                    assertFalse("primary" in availability.saturatedBackendIds)
                    heldMaintenance.set(
                        requireNotNull(gate.tryAcquireBackendMaintenance("primary"))
                    )
                    Candidate(
                        id = "primary",
                        memoryMb = 128,
                        backendAdmission = TitanBackendExecutionAdmission("primary", Int.MAX_VALUE)
                    )
                }
                2 -> {
                    assertTrue("primary" in availability.saturatedBackendIds)
                    Candidate(
                        id = "fallback",
                        memoryMb = 64,
                        backendAdmission = TitanBackendExecutionAdmission("fallback", Int.MAX_VALUE)
                    )
                }
                else -> error("maintenance race exceeded bounded replanning")
            }
        }

        assertEquals("fallback", admitted.value.id)
        assertEquals(2, planningPasses.get())
        admitted.lease.close()
        heldMaintenance.get().close()
    }

    @Test
    fun automaticReconciliationSkipsExecutingBackendAndRoutesFallback() {
        val model = installed("shared-model")
        val primaryEntered = CountDownLatch(1)
        val primaryRelease = CountDownLatch(1)
        val primary = BlockingReclaimingBackend(
            id = "primary",
            modelId = model.descriptor.id,
            primaryEntered = primaryEntered,
            primaryRelease = primaryRelease
        )
        val fallback = FallbackBackend("fallback", model.descriptor.id)
        val governor = CountingGovernor(
            ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512, thermalClass = 1)
        )
        val runtime = runtime(model, listOf(primary, fallback), governor)
        val firstResult = AtomicReference<Result<InferenceResponse>>()
        val first = Thread {
            firstResult.set(runtime.infer(InferenceRequest("first")))
        }
        first.start()
        assertTrue(primaryEntered.await(5, TimeUnit.SECONDS))
        assertEquals(1, primary.reconcileCalls.get())

        val second = runtime.infer(InferenceRequest("second")).getOrThrow()

        assertEquals("fallback", second.backendId)
        assertEquals(1, fallback.inferCalls.get())
        assertEquals(1, primary.reconcileCalls.get())
        assertEquals(2, governor.reads.get())

        primaryRelease.countDown()
        first.join(5_000)
        assertFalse(first.isAlive)
        assertEquals("primary", firstResult.get().getOrThrow().backendId)
        assertEquals(1, primary.inferCalls.get())
    }

    private fun runtime(
        model: InstalledModel,
        backendList: List<InferenceBackend>,
        governor: ResourceGovernor
    ): TitanCortexRuntime {
        val models = InMemoryModelRegistry().apply { register(model.descriptor) }
        val catalog = InMemoryInstalledModelCatalog().apply { put(model) }
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
            lengthBytes = 64L * 1024L * 1024L,
            sha256 = "a".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL
        )
    }

    private data class Candidate(
        val id: String,
        val memoryMb: Int,
        val backendAdmission: TitanBackendExecutionAdmission
    )

    private class CountingGovernor(
        private val budget: ResourceBudget
    ) : ResourceGovernor {
        val reads = AtomicInteger(0)
        override fun currentBudget(): ResourceBudget {
            reads.incrementAndGet()
            return budget
        }
        override fun allows(agentCount: Int): Boolean = agentCount <= budget.maxConcurrentAgents
    }

    private class BlockingReclaimingBackend(
        override val id: String,
        private val modelId: ModelId,
        private val primaryEntered: CountDownLatch,
        private val primaryRelease: CountDownLatch
    ) : ResourceReclaimingInferenceBackend, ConcurrencyLimitedInferenceBackend {
        override val maxConcurrentExecutions: Int = 1
        val reconcileCalls = AtomicInteger(0)
        val inferCalls = AtomicInteger(0)

        override fun supports(model: InstalledModel): Boolean = model.descriptor.id == modelId
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(128, 2, 2048)

        override fun reconcileResources(
            budget: ResourceBudget
        ): Result<BackendResourceReconciliation> {
            reconcileCalls.incrementAndGet()
            return Result.success(BackendResourceReconciliation())
        }

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = runCatching {
            inferCalls.incrementAndGet()
            primaryEntered.countDown()
            check(primaryRelease.await(5, TimeUnit.SECONDS)) { "primary release timed out" }
            InferenceResponse(model.descriptor.id, id, "primary")
        }
    }

    private class FallbackBackend(
        override val id: String,
        private val modelId: ModelId
    ) : ManagedInferenceBackend {
        val inferCalls = AtomicInteger(0)
        override fun supports(model: InstalledModel): Boolean = model.descriptor.id == modelId
        override fun health(): BackendHealth = BackendHealth(BackendState.DEGRADED)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(64, 1, 2048)
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferCalls.incrementAndGet()
            return Result.success(InferenceResponse(model.descriptor.id, id, "fallback"))
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
        override val displayName: String = "maintenance-admission.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
