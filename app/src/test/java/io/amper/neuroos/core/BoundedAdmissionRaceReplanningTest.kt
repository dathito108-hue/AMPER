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

class BoundedAdmissionRaceReplanningTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun inferenceReplansToLightRouteAfterLosingFinalAcquireWithoutRereadingGovernor() {
        val heavy = installed("a-heavy")
        val light = installed("b-light")
        val affinityEntered = CountDownLatch(1)
        val affinityRelease = CountDownLatch(1)
        val heavyBackend = FixedManagedBackend(
            id = "heavy-ready",
            supportedModel = heavy.descriptor.id,
            memoryMb = 384,
            state = BackendState.READY,
            affinityEntered = affinityEntered,
            affinityRelease = affinityRelease
        )
        val lightBackend = FixedManagedBackend(
            id = "light-degraded",
            supportedModel = light.descriptor.id,
            memoryMb = 128,
            state = BackendState.DEGRADED
        )
        val budget = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512, thermalClass = 1)
        val governor = CountingGovernor(budget)
        val runtime = runtime(
            models = listOf(heavy, light),
            backends = listOf(heavyBackend, lightBackend),
            governor = governor
        )
        val first = AtomicReference<Result<InferenceResponse>>()
        val thread = Thread {
            first.set(runtime.infer(InferenceRequest("race request")))
        }
        thread.start()
        assertTrue(affinityEntered.await(5, TimeUnit.SECONDS))

        val competingLease = executionAdmission(runtime)
            .acquire(budget, estimatedMemoryMb = 384)
            .getOrThrow()
        affinityRelease.countDown()

        thread.join(5_000)
        assertFalse(thread.isAlive)
        val replanned = first.get().getOrThrow()
        assertEquals(ModelId("b-light"), replanned.modelId)
        assertEquals("light-degraded", replanned.backendId)
        assertEquals(0, heavyBackend.inferCalls.get())
        assertEquals(1, lightBackend.inferCalls.get())
        assertEquals(1, governor.reads.get())

        competingLease.close()

        // The route that lost admission never started infer and therefore must not receive a failure
        // penalty. Once capacity is free again, normal policy ranking can select it immediately.
        val next = runtime.infer(InferenceRequest("capacity restored")).getOrThrow()
        assertEquals(ModelId("a-heavy"), next.modelId)
        assertEquals("heavy-ready", next.backendId)
        assertEquals(1, heavyBackend.inferCalls.get())
        assertEquals(2, governor.reads.get())
    }

    @Test
    fun preparationUsesTheSameBoundedRaceReplanningPath() {
        val heavy = installed("a-heavy")
        val light = installed("b-light")
        val affinityEntered = CountDownLatch(1)
        val affinityRelease = CountDownLatch(1)
        val heavyBackend = FixedPreparableBackend(
            id = "heavy-ready",
            supportedModel = heavy.descriptor.id,
            memoryMb = 384,
            state = BackendState.READY,
            affinityEntered = affinityEntered,
            affinityRelease = affinityRelease
        )
        val lightBackend = FixedPreparableBackend(
            id = "light-degraded",
            supportedModel = light.descriptor.id,
            memoryMb = 128,
            state = BackendState.DEGRADED
        )
        val budget = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        val governor = CountingGovernor(budget)
        val runtime = runtime(
            models = listOf(heavy, light),
            backends = listOf(heavyBackend, lightBackend),
            governor = governor
        )
        val prepared = AtomicReference<Result<InferencePreparation>>()
        val thread = Thread {
            prepared.set(runtime.prepare(InferenceRequest("prepare race")))
        }
        thread.start()
        assertTrue(affinityEntered.await(5, TimeUnit.SECONDS))

        val competingLease = executionAdmission(runtime)
            .acquire(budget, estimatedMemoryMb = 384)
            .getOrThrow()
        affinityRelease.countDown()

        thread.join(5_000)
        assertFalse(thread.isAlive)
        val replanned = prepared.get().getOrThrow()
        assertEquals(ModelId("b-light"), replanned.modelId)
        assertEquals("light-degraded", replanned.backendId)
        assertEquals(0, heavyBackend.prepareCalls.get())
        assertEquals(1, lightBackend.prepareCalls.get())
        assertEquals(1, governor.reads.get())
        competingLease.close()
    }

    @Test
    fun replanningStopsAfterTheConfiguredBound() {
        val gate = TitanExecutionAdmissionGate()
        val replanner = TitanBoundedAdmissionReplanner(gate, maxReplans = 1)
        val budget = ResourceBudget(maxConcurrentAgents = 4, memoryMb = 512)
        val competingLeases = mutableListOf<TitanExecutionAdmissionLease>()
        val planningPasses = AtomicInteger(0)

        val result = runCatching {
            replanner.acquire(
                baseBudget = budget,
                estimatedMemoryMb = { candidate: Candidate -> candidate.memoryMb }
            ) { availability ->
                when (planningPasses.incrementAndGet()) {
                    1 -> {
                        assertEquals(512, availability.budget.memoryMb)
                        competingLeases += gate.acquire(budget, 256).getOrThrow()
                        Candidate("first-heavy", 384)
                    }
                    2 -> {
                        assertEquals(256, availability.budget.memoryMb)
                        competingLeases += gate.acquire(budget, 128).getOrThrow()
                        Candidate("second-light", 256)
                    }
                    else -> error("replanning exceeded its configured bound")
                }
            }
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("memory budget exceeded"))
        assertEquals(2, planningPasses.get())
        competingLeases.asReversed().forEach { it.close() }
    }

    @Test
    fun backendFailureAfterInferenceStartsSurfacesWithoutSilentFailover() {
        val heavy = installed("a-heavy")
        val light = installed("b-light")
        val heavyBackend = FixedManagedBackend(
            id = "heavy-ready",
            supportedModel = heavy.descriptor.id,
            memoryMb = 256,
            state = BackendState.READY,
            failInference = true
        )
        val lightBackend = FixedManagedBackend(
            id = "light-degraded",
            supportedModel = light.descriptor.id,
            memoryMb = 128,
            state = BackendState.DEGRADED
        )
        val governor = CountingGovernor(
            ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        )
        val runtime = runtime(
            models = listOf(heavy, light),
            backends = listOf(heavyBackend, lightBackend),
            governor = governor
        )

        val result = runtime.infer(InferenceRequest("backend must surface failure"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("backend boom"))
        assertEquals(1, heavyBackend.inferCalls.get())
        assertEquals(0, lightBackend.inferCalls.get())
        assertEquals(1, governor.reads.get())
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

    private data class Candidate(val id: String, val memoryMb: Int?)

    private class CountingGovernor(private val budget: ResourceBudget) : ResourceGovernor {
        val reads = AtomicInteger(0)

        override fun currentBudget(): ResourceBudget {
            reads.incrementAndGet()
            return budget
        }

        override fun allows(agentCount: Int): Boolean = agentCount <= budget.maxConcurrentAgents
    }

    private open class FixedManagedBackend(
        override val id: String,
        private val supportedModel: ModelId,
        private val memoryMb: Int,
        private val state: BackendState,
        private val affinityEntered: CountDownLatch? = null,
        private val affinityRelease: CountDownLatch? = null,
        private val failInference: Boolean = false
    ) : ManagedInferenceBackend {
        val inferCalls = AtomicInteger(0)
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
            if (failInference) error("backend boom")
            InferenceResponse(model.descriptor.id, id, "ok")
        }
    }

    private class FixedPreparableBackend(
        override val id: String,
        private val supportedModel: ModelId,
        private val memoryMb: Int,
        private val state: BackendState,
        private val affinityEntered: CountDownLatch? = null,
        private val affinityRelease: CountDownLatch? = null
    ) : PreparableInferenceBackend {
        val prepareCalls = AtomicInteger(0)
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
        ): Result<InferenceResponse> = Result.success(
            InferenceResponse(model.descriptor.id, id, "ok")
        )
    }

    private class StaticResolver : ModelArtifactResolver {
        override fun resolve(model: InstalledModel): ModelArtifactSource =
            StaticSource(model.locator, model.lengthBytes)
    }

    private class StaticSource(
        override val locator: String,
        override val lengthBytes: Long?
    ) : ModelArtifactSource {
        override val displayName: String = "bounded-admission-race.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
