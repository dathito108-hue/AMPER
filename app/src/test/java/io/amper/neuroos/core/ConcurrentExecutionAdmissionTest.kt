package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConcurrentExecutionAdmissionTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun gateEnforcesConcurrentSlotLimitAndReleasesIdempotently() {
        val gate = TitanExecutionAdmissionGate()
        val budget = ResourceBudget(maxConcurrentAgents = 1, memoryMb = 512)
        val first = gate.acquire(budget, estimatedMemoryMb = 128).getOrThrow()

        val blocked = gate.acquire(budget, estimatedMemoryMb = 128)
        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull()?.message.orEmpty().contains("concurrent execution limit"))

        first.close()
        first.close()
        gate.acquire(budget, estimatedMemoryMb = 128).getOrThrow().close()
    }

    @Test
    fun gateEnforcesAggregateKnownMemoryAcrossConcurrentExecutions() {
        val gate = TitanExecutionAdmissionGate()
        val budget = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        val first = gate.acquire(budget, estimatedMemoryMb = 384).getOrThrow()

        val tooLarge = gate.acquire(budget, estimatedMemoryMb = 256)
        assertTrue(tooLarge.isFailure)
        assertTrue(tooLarge.exceptionOrNull()?.message.orEmpty().contains("memory budget exceeded"))

        val fits = gate.acquire(budget, estimatedMemoryMb = 128).getOrThrow()
        fits.close()
        first.close()
    }

    @Test
    fun unknownMemoryExecutionRequiresExclusiveAdmission() {
        val gate = TitanExecutionAdmissionGate()
        val budget = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        val unknown = gate.acquire(budget, estimatedMemoryMb = null).getOrThrow()

        val knownWhileUnknown = gate.acquire(budget, estimatedMemoryMb = 64)
        assertTrue(knownWhileUnknown.isFailure)
        assertTrue(knownWhileUnknown.exceptionOrNull()?.message.orEmpty().contains("unknown-memory"))

        unknown.close()
        val known = gate.acquire(budget, estimatedMemoryMb = 64).getOrThrow()
        val unknownWhileKnown = gate.acquire(budget, estimatedMemoryMb = null)
        assertTrue(unknownWhileKnown.isFailure)
        assertTrue(unknownWhileKnown.exceptionOrNull()?.message.orEmpty().contains("exclusive"))
        known.close()
    }

    @Test
    fun plannerCarriesSingleFrozenMemoryEstimateIntoRoute() {
        val model = installed("m")
        val backend = CountingBackend("managed", memoryMb = 192)
        val models = InMemoryModelRegistry().also { it.register(model.descriptor) }
        val catalog = InMemoryInstalledModelCatalog().also { it.put(model) }
        val registry = InferenceBackendRegistry().apply { register(backend) }
        val planner = TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = StaticResolver(),
            backends = registry
        )

        val route = planner.plan(
            InferenceRequest("route"),
            ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        ).getOrThrow()

        assertEquals(192, route.estimatedMemoryMb)
        assertEquals(1, backend.estimateCalls.get())
    }

    @Test
    fun runtimeRejectsSecondInferenceWhenConcurrentSlotIsOccupiedThenRecovers() {
        val model = installed("m")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = BlockingInferenceBackend("blocking", 128, entered, release)
        val runtime = runtime(
            model = model,
            backend = backend,
            budget = ResourceBudget(maxConcurrentAgents = 1, memoryMb = 512)
        )
        val firstResult = AtomicReference<Result<InferenceResponse>>()
        val firstThread = Thread {
            firstResult.set(runtime.infer(InferenceRequest("first")))
        }
        firstThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = runtime.infer(InferenceRequest("second"))
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message.orEmpty().contains("concurrent execution limit"))
        assertEquals(1, backend.inferCalls.get())

        release.countDown()
        firstThread.join(5_000)
        assertTrue(firstResult.get().isSuccess)

        val third = runtime.infer(InferenceRequest("third"))
        assertTrue(third.isSuccess)
        assertEquals(2, backend.inferCalls.get())
    }

    @Test
    fun runtimeRejectsAggregateMemoryOversubscriptionEvenWhenSecondSlotExists() {
        val model = installed("m")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = BlockingInferenceBackend("blocking", 384, entered, release)
        val runtime = runtime(
            model = model,
            backend = backend,
            budget = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        )
        val firstResult = AtomicReference<Result<InferenceResponse>>()
        val firstThread = Thread {
            firstResult.set(runtime.infer(InferenceRequest("first")))
        }
        firstThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = runtime.infer(InferenceRequest("second"))
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message.orEmpty().contains("no feasible model route"))
        assertEquals(1, backend.inferCalls.get())

        release.countDown()
        firstThread.join(5_000)
        assertTrue(firstResult.get().isSuccess)
    }

    @Test
    fun preparationAndInferenceShareTheSameConcurrentAdmissionGate() {
        val model = installed("m")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = BlockingPreparableBackend("preparable", 128, entered, release)
        val runtime = runtime(
            model = model,
            backend = backend,
            budget = ResourceBudget(maxConcurrentAgents = 1, memoryMb = 512)
        )
        val prepareResult = AtomicReference<Result<InferencePreparation>>()
        val prepareThread = Thread {
            prepareResult.set(runtime.prepare(InferenceRequest("prepare")))
        }
        prepareThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val inference = runtime.infer(InferenceRequest("infer"))
        assertTrue(inference.isFailure)
        assertTrue(inference.exceptionOrNull()?.message.orEmpty().contains("concurrent execution limit"))
        assertEquals(0, backend.inferCalls.get())

        release.countDown()
        prepareThread.join(5_000)
        assertTrue(prepareResult.get().isSuccess)
    }

    @Test
    fun failedBackendExecutionStillReleasesAdmissionLease() {
        val model = installed("m")
        val backend = FailOnceBackend("fail-once", 128)
        val runtime = runtime(
            model = model,
            backend = backend,
            budget = ResourceBudget(maxConcurrentAgents = 1, memoryMb = 512)
        )

        assertTrue(runtime.infer(InferenceRequest("first")).isFailure)
        assertTrue(runtime.infer(InferenceRequest("second")).isSuccess)
        assertEquals(2, backend.inferCalls.get())
    }

    private fun runtime(
        model: InstalledModel,
        backend: InferenceBackend,
        budget: ResourceBudget
    ): TitanCortexRuntime {
        val models = InMemoryModelRegistry().also { it.register(model.descriptor) }
        val catalog = InMemoryInstalledModelCatalog().also { it.put(model) }
        val registry = InferenceBackendRegistry().apply { register(backend) }
        return TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = StaticResolver(),
            backends = registry,
            governor = StaticGovernor(budget)
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

    private class StaticGovernor(private val budget: ResourceBudget) : ResourceGovernor {
        override fun currentBudget(): ResourceBudget = budget
        override fun allows(agentCount: Int): Boolean = agentCount <= budget.maxConcurrentAgents
    }

    private open class CountingBackend(
        override val id: String,
        private val memoryMb: Int
    ) : ManagedInferenceBackend {
        val estimateCalls = AtomicInteger(0)
        val inferCalls = AtomicInteger(0)

        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost {
            estimateCalls.incrementAndGet()
            return InferenceCost(memoryMb, 2, 2048)
        }
        open override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferCalls.incrementAndGet()
            return Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
        }
    }

    private class BlockingInferenceBackend(
        id: String,
        memoryMb: Int,
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : CountingBackend(id, memoryMb) {
        private val first = AtomicInteger(0)

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferCalls.incrementAndGet()
            if (first.getAndIncrement() == 0) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test release timed out" }
            }
            return Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
        }
    }

    private class BlockingPreparableBackend(
        override val id: String,
        private val memoryMb: Int,
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : PreparableInferenceBackend {
        val inferCalls = AtomicInteger(0)

        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(memoryMb, 2, 2048)
        override fun prepare(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<BackendPreparationResult> {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test release timed out" }
            return Result.success(BackendPreparationResult(sessionReused = false))
        }
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferCalls.incrementAndGet()
            return Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
        }
    }

    private class FailOnceBackend(
        id: String,
        memoryMb: Int
    ) : CountingBackend(id, memoryMb) {
        private val attempts = AtomicInteger(0)

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferCalls.incrementAndGet()
            return if (attempts.getAndIncrement() == 0) {
                Result.failure(IllegalStateException("synthetic failure"))
            } else {
                Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
            }
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
        override val displayName: String = "concurrent-admission.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
