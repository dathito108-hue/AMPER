package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BackendConcurrencyAwareAdmissionTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun planningSnapshotExposesSaturatedBackendCapacity() {
        val gate = TitanExecutionAdmissionGate()
        val budget = ResourceBudget(maxConcurrentAgents = 3, memoryMb = 512)
        val lease = gate.acquire(
            budget = budget,
            estimatedMemoryMb = 128,
            backendAdmission = TitanBackendExecutionAdmission(
                backendId = "single-session",
                maxConcurrentExecutions = 1
            )
        ).getOrThrow()

        val availability = gate.planningAvailability(budget).getOrThrow()

        assertEquals(2, availability.budget.maxConcurrentAgents)
        assertEquals(384, availability.budget.memoryMb)
        assertTrue(availability.requireKnownMemoryEstimate)
        assertEquals(setOf("single-session"), availability.saturatedBackendIds)
        lease.close()
    }

    @Test
    fun activeSingleSessionBackendMakesParallelRequestRouteToFallbackWithoutWaiting() {
        val heavy = installed("a-heavy")
        val light = installed("b-light")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val single = SingleSlotBackend(
            id = "single-session",
            supportedModel = heavy.descriptor.id,
            memoryMb = 128,
            state = BackendState.READY,
            entered = entered,
            release = release
        )
        val fallback = FixedBackend(
            id = "parallel-fallback",
            supportedModel = light.descriptor.id,
            memoryMb = 128,
            state = BackendState.DEGRADED
        )
        val governor = CountingGovernor(
            ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        )
        val runtime = runtime(
            models = listOf(heavy, light),
            backends = listOf(single, fallback),
            governor = governor
        )
        val first = AtomicReference<Result<InferenceResponse>>()
        val firstThread = Thread {
            first.set(runtime.infer(InferenceRequest("occupy single session")))
        }
        firstThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = runtime.infer(InferenceRequest("parallel request")).getOrThrow()

        assertEquals(ModelId("b-light"), second.modelId)
        assertEquals("parallel-fallback", second.backendId)
        assertEquals(1, single.inferCalls.get())
        assertEquals(1, fallback.inferCalls.get())
        assertEquals(2, governor.reads.get())

        release.countDown()
        firstThread.join(5_000)
        assertFalse(firstThread.isAlive)
        assertTrue(first.get().isSuccess)
    }

    @Test
    fun backendCapacityRaceReplansBeforeInferenceStarts() {
        val heavy = installed("a-heavy")
        val light = installed("b-light")
        val affinityEntered = CountDownLatch(1)
        val affinityRelease = CountDownLatch(1)
        val single = SingleSlotBackend(
            id = "single-session",
            supportedModel = heavy.descriptor.id,
            memoryMb = 128,
            state = BackendState.READY,
            affinityEntered = affinityEntered,
            affinityRelease = affinityRelease
        )
        val fallback = FixedBackend(
            id = "parallel-fallback",
            supportedModel = light.descriptor.id,
            memoryMb = 128,
            state = BackendState.DEGRADED
        )
        val budget = ResourceBudget(maxConcurrentAgents = 3, memoryMb = 512)
        val governor = CountingGovernor(budget)
        val runtime = runtime(
            models = listOf(heavy, light),
            backends = listOf(single, fallback),
            governor = governor
        )
        val result = AtomicReference<Result<InferenceResponse>>()
        val thread = Thread {
            result.set(runtime.infer(InferenceRequest("backend slot race")))
        }
        thread.start()
        assertTrue(affinityEntered.await(5, TimeUnit.SECONDS))

        val competingLease = executionAdmission(runtime).acquire(
            budget = budget,
            estimatedMemoryMb = 64,
            backendAdmission = TitanBackendExecutionAdmission(
                backendId = "single-session",
                maxConcurrentExecutions = 1
            )
        ).getOrThrow()
        affinityRelease.countDown()

        thread.join(5_000)
        assertFalse(thread.isAlive)
        val replanned = result.get().getOrThrow()
        assertEquals(ModelId("b-light"), replanned.modelId)
        assertEquals("parallel-fallback", replanned.backendId)
        assertEquals(0, single.inferCalls.get())
        assertEquals(1, fallback.inferCalls.get())
        assertEquals(1, governor.reads.get())
        competingLease.close()
    }

    @Test
    fun saturatedBackendIsFilteredBeforeSessionAffinityEvaluation() {
        val model = installed("m")
        val blocked = SingleSlotBackend(
            id = "single-session",
            supportedModel = model.descriptor.id,
            memoryMb = 128,
            state = BackendState.READY
        )
        val fallback = FixedBackend(
            id = "fallback",
            supportedModel = model.descriptor.id,
            memoryMb = 128,
            state = BackendState.DEGRADED
        )
        val registry = InferenceBackendRegistry().apply {
            register(blocked)
            register(fallback)
        }

        val candidates = registry.evaluatedCandidates(
            model = model,
            request = InferenceRequest("skip saturated"),
            budget = ResourceBudget(maxConcurrentAgents = 1, memoryMb = 512),
            excludedBackendIds = setOf("single-session")
        )

        assertEquals(listOf("fallback"), candidates.map { it.backend.id })
        assertEquals(0, blocked.affinityCalls.get())
    }

    private fun runtime(
        models: List<InstalledModel>,
        backends: List<InferenceBackend>,
        governor: ResourceGovernor
    ): TitanCortexRuntime {
        val modelRegistry = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        models.forEach {
            modelRegistry.register(it.descriptor)
            catalog.put(it)
        }
        val backendRegistry = InferenceBackendRegistry().apply { backends.forEach(::register) }
        return TitanCortexRuntime(
            models = modelRegistry,
            catalog = catalog,
            artifacts = StaticResolver(),
            backends = backendRegistry,
            governor = governor
        )
    }

    private fun executionAdmission(runtime: TitanCortexRuntime): TitanExecutionAdmissionGate {
        val field = TitanCortexRuntime::class.java.getDeclaredField("executionAdmission")
        field.isAccessible = true
        return field.get(runtime) as TitanExecutionAdmissionGate
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

    private class CountingGovernor(private val budget: ResourceBudget) : ResourceGovernor {
        val reads = AtomicInteger(0)

        override fun currentBudget(): ResourceBudget {
            reads.incrementAndGet()
            return budget
        }

        override fun allows(agentCount: Int): Boolean = agentCount <= budget.maxConcurrentAgents
    }

    private open class FixedBackend(
        override val id: String,
        private val supportedModel: ModelId,
        private val memoryMb: Int,
        private val state: BackendState
    ) : ManagedInferenceBackend {
        val inferCalls = AtomicInteger(0)

        override fun supports(model: InstalledModel): Boolean = model.descriptor.id == supportedModel
        override fun health(): BackendHealth = BackendHealth(state)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(memoryMb, 2, 2048)

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = runCatching {
            inferCalls.incrementAndGet()
            InferenceResponse(model.descriptor.id, id, "ok")
        }
    }

    private class SingleSlotBackend(
        override val id: String,
        private val supportedModel: ModelId,
        private val memoryMb: Int,
        private val state: BackendState,
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
        private val affinityEntered: CountDownLatch? = null,
        private val affinityRelease: CountDownLatch? = null
    ) : ManagedInferenceBackend, ConcurrencyLimitedInferenceBackend {
        override val maxConcurrentExecutions: Int = 1
        val inferCalls = AtomicInteger(0)
        val affinityCalls = AtomicInteger(0)
        private val affinityBlocked = AtomicBoolean(false)

        override fun supports(model: InstalledModel): Boolean = model.descriptor.id == supportedModel
        override fun health(): BackendHealth = BackendHealth(state)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(memoryMb, 2, 2048)

        override fun sessionAffinity(
            model: InstalledModel,
            request: InferenceRequest,
            cost: InferenceCost
        ): BackendSessionAffinity {
            affinityCalls.incrementAndGet()
            if (
                affinityEntered != null &&
                affinityRelease != null &&
                affinityBlocked.compareAndSet(false, true)
            ) {
                affinityEntered.countDown()
                check(affinityRelease.await(5, TimeUnit.SECONDS)) { "affinity release timed out" }
            }
            return BackendSessionAffinity.COLD
        }

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = runCatching {
            inferCalls.incrementAndGet()
            if (entered != null && release != null) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "execution release timed out" }
            }
            InferenceResponse(model.descriptor.id, id, "ok")
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
        override val displayName: String = "backend-concurrency.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
