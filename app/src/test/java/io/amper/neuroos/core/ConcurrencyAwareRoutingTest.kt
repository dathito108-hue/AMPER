package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConcurrencyAwareRoutingTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun planningAvailabilitySubtractsReservedSlotsAndKnownMemory() {
        val gate = TitanExecutionAdmissionGate()
        val base = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512, thermalClass = 1)
        val lease = gate.acquire(base, estimatedMemoryMb = 384).getOrThrow()

        val availability = gate.planningAvailability(base).getOrThrow()

        assertEquals(1, availability.budget.maxConcurrentAgents)
        assertEquals(128, availability.budget.memoryMb)
        assertEquals(1, availability.budget.thermalClass)
        assertTrue(availability.requireKnownMemoryEstimate)
        lease.close()
    }

    @Test
    fun activeUnknownMemoryExecutionBlocksConcurrentPlanning() {
        val gate = TitanExecutionAdmissionGate()
        val base = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        val lease = gate.acquire(base, estimatedMemoryMb = null).getOrThrow()

        val result = gate.planningAvailability(base)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("unknown-memory"))
        lease.close()
    }

    @Test
    fun stalePlanningSnapshotStillCannotBypassFinalAcquire() {
        val gate = TitanExecutionAdmissionGate()
        val base = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        val stale = gate.planningAvailability(base).getOrThrow()
        assertEquals(512, stale.budget.memoryMb)

        val winner = gate.acquire(base, estimatedMemoryMb = 384).getOrThrow()
        val loser = gate.acquire(base, estimatedMemoryMb = 384)

        assertTrue(loser.isFailure)
        assertTrue(loser.exceptionOrNull()?.message.orEmpty().contains("memory budget exceeded"))
        winner.close()
    }

    @Test
    fun knownMemoryRequirementFiltersUnknownCostBackendBeforeRanking() {
        val model = installed("m")
        val unknown = FixedBackend(
            id = "unknown-ready",
            supportedModel = model.descriptor.id,
            memoryMb = 0,
            state = BackendState.READY
        )
        val known = FixedBackend(
            id = "known-degraded",
            supportedModel = model.descriptor.id,
            memoryMb = 64,
            state = BackendState.DEGRADED
        )
        val registry = InferenceBackendRegistry().apply {
            register(unknown)
            register(known)
        }

        val candidates = registry.evaluatedCandidates(
            model = model,
            request = InferenceRequest("known only"),
            budget = ResourceBudget(maxConcurrentAgents = 1, memoryMb = 128),
            requireKnownMemoryEstimate = true
        )

        assertEquals(listOf("known-degraded"), candidates.map { it.backend.id })
        assertEquals(64, candidates.single().estimatedMemoryMb)
    }

    @Test
    fun activeHeavyInferenceMakesSecondRequestSelectLightFallback() {
        val heavy = installed("a-heavy")
        val light = installed("b-light")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heavyBackend = FixedBackend(
            id = "heavy-ready",
            supportedModel = heavy.descriptor.id,
            memoryMb = 384,
            state = BackendState.READY,
            entered = entered,
            release = release
        )
        val lightBackend = FixedBackend(
            id = "light-degraded",
            supportedModel = light.descriptor.id,
            memoryMb = 128,
            state = BackendState.DEGRADED
        )
        val governor = CountingGovernor(
            ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512, thermalClass = 1)
        )
        val runtime = runtime(
            models = listOf(heavy, light),
            backends = listOf(heavyBackend, lightBackend),
            governor = governor
        )
        val first = AtomicReference<Result<InferenceResponse>>()
        val thread = Thread {
            first.set(runtime.infer(InferenceRequest("first heavy request")))
        }
        thread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = runtime.infer(InferenceRequest("parallel request")).getOrThrow()

        assertEquals(ModelId("b-light"), second.modelId)
        assertEquals("light-degraded", second.backendId)
        assertEquals(1, lightBackend.inferCalls.get())
        assertEquals(2, governor.reads.get())

        release.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertTrue(first.get().isSuccess)
        assertEquals(ModelId("a-heavy"), first.get().getOrThrow().modelId)
    }

    @Test
    fun exhaustedConcurrentSlotFailsBeforeASecondPlanningPass() {
        val model = installed("m")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = FixedBackend(
            id = "blocking",
            supportedModel = model.descriptor.id,
            memoryMb = 128,
            state = BackendState.READY,
            entered = entered,
            release = release
        )
        val runtime = runtime(
            models = listOf(model),
            backends = listOf(backend),
            governor = CountingGovernor(
                ResourceBudget(maxConcurrentAgents = 1, memoryMb = 512)
            )
        )
        val first = AtomicReference<Result<InferenceResponse>>()
        val thread = Thread {
            first.set(runtime.infer(InferenceRequest("first")))
        }
        thread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val estimatesAfterFirstPlan = backend.estimateCalls.get()

        val second = runtime.infer(InferenceRequest("second"))

        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message.orEmpty().contains("before route planning"))
        assertEquals(estimatesAfterFirstPlan, backend.estimateCalls.get())
        assertEquals(1, backend.inferCalls.get())

        release.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertTrue(first.get().isSuccess)
    }

    @Test
    fun preparationAlsoFallsThroughToAResolvableLightRoute() {
        val heavy = installed("a-heavy")
        val light = installed("b-light")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heavyBackend = FixedPreparableBackend(
            id = "heavy-ready",
            supportedModel = heavy.descriptor.id,
            memoryMb = 384,
            state = BackendState.READY,
            entered = entered,
            release = release
        )
        val lightBackend = FixedPreparableBackend(
            id = "light-degraded",
            supportedModel = light.descriptor.id,
            memoryMb = 128,
            state = BackendState.DEGRADED
        )
        val runtime = runtime(
            models = listOf(heavy, light),
            backends = listOf(heavyBackend, lightBackend),
            governor = CountingGovernor(
                ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
            )
        )
        val first = AtomicReference<Result<InferenceResponse>>()
        val thread = Thread {
            first.set(runtime.infer(InferenceRequest("occupy heavy route")))
        }
        thread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val prepared = runtime.prepare(InferenceRequest("prewarm parallel route")).getOrThrow()

        assertEquals(ModelId("b-light"), prepared.modelId)
        assertEquals("light-degraded", prepared.backendId)
        assertEquals(1, lightBackend.prepareCalls.get())

        release.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertTrue(first.get().isSuccess)
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
        private val state: BackendState,
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null
    ) : ManagedInferenceBackend {
        val estimateCalls = AtomicInteger(0)
        val inferCalls = AtomicInteger(0)

        override fun supports(model: InstalledModel): Boolean = model.descriptor.id == supportedModel
        override fun health(): BackendHealth = BackendHealth(state)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost {
            estimateCalls.incrementAndGet()
            return InferenceCost(memoryMb, 2, 2048)
        }
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = runCatching {
            inferCalls.incrementAndGet()
            if (entered != null && release != null) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test release timed out" }
            }
            InferenceResponse(model.descriptor.id, id, "ok")
        }
    }

    private class FixedPreparableBackend(
        override val id: String,
        private val supportedModel: ModelId,
        private val memoryMb: Int,
        private val state: BackendState,
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null
    ) : PreparableInferenceBackend {
        val inferCalls = AtomicInteger(0)
        val prepareCalls = AtomicInteger(0)

        override fun supports(model: InstalledModel): Boolean = model.descriptor.id == supportedModel
        override fun health(): BackendHealth = BackendHealth(state)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(memoryMb, 2, 2048)
        override fun prepare(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<BackendPreparationResult> {
            prepareCalls.incrementAndGet()
            return Result.success(BackendPreparationResult(sessionReused = false))
        }
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = runCatching {
            inferCalls.incrementAndGet()
            if (entered != null && release != null) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test release timed out" }
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
        override val displayName: String = "concurrency-aware-routing.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
