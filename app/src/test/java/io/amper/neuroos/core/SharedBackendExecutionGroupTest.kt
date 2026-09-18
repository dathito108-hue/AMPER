package io.amper.neuroos.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedBackendExecutionGroupTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun saturatedBackendPublishesItsSharedExecutionGroup() {
        val gate = TitanExecutionAdmissionGate()
        val budget = ResourceBudget(maxConcurrentAgents = 3, memoryMb = 512)
        val lease = gate.acquire(
            budget = budget,
            estimatedMemoryMb = 64,
            backendAdmission = TitanBackendExecutionAdmission(
                backendId = "native-text",
                maxConcurrentExecutions = 1,
                executionGroupId = "native-llama"
            )
        ).getOrThrow()

        val availability = gate.planningAvailability(budget).getOrThrow()

        assertEquals(setOf("native-text"), availability.saturatedBackendIds)
        assertEquals(
            setOf("native-llama"),
            availability.saturatedExecutionGroupIds
        )

        val sibling = gate.acquire(
            budget = budget,
            estimatedMemoryMb = 64,
            backendAdmission = TitanBackendExecutionAdmission(
                backendId = "native-mtmd",
                maxConcurrentExecutions = 1,
                executionGroupId = "native-llama"
            )
        )
        assertTrue(sibling.isFailure)
        assertTrue(
            sibling.exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("execution-group concurrency limit reached")
        )

        lease.close()

        gate.acquire(
            budget = budget,
            estimatedMemoryMb = 64,
            backendAdmission = TitanBackendExecutionAdmission(
                backendId = "native-mtmd",
                maxConcurrentExecutions = 1,
                executionGroupId = "native-llama"
            )
        ).getOrThrow().close()
    }

    @Test
    fun maintenanceOnOneSiblingExcludesTheWholeExecutionGroup() {
        val gate = TitanExecutionAdmissionGate()
        val budget = ResourceBudget(maxConcurrentAgents = 2, memoryMb = 512)
        val maintenance = requireNotNull(
            gate.tryAcquireBackendMaintenance(
                backendId = "native-text",
                executionGroupId = "native-llama"
            )
        )

        val availability = gate.planningAvailability(budget).getOrThrow()
        assertTrue("native-text" in availability.saturatedBackendIds)
        assertTrue("native-llama" in availability.saturatedExecutionGroupIds)

        val sibling = gate.acquire(
            budget = budget,
            estimatedMemoryMb = 64,
            backendAdmission = TitanBackendExecutionAdmission(
                backendId = "native-mtmd",
                maxConcurrentExecutions = 1,
                executionGroupId = "native-llama"
            )
        )
        assertTrue(sibling.isFailure)
        assertTrue(
            sibling.exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("execution-group maintenance active")
        )

        maintenance.close()
    }

    @Test
    fun activeNativeTextExecutionRoutesParallelRequestAwayFromMtmdSibling() {
        val textModel = installed("a-text")
        val mtmdModel = installed("b-mtmd")
        val fallbackModel = installed("c-fallback")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        val text = GroupedBackend(
            id = "native-text",
            supportedModel = textModel.descriptor.id,
            state = BackendState.READY,
            entered = entered,
            release = release
        )
        val mtmd = GroupedBackend(
            id = "native-mtmd",
            supportedModel = mtmdModel.descriptor.id,
            state = BackendState.READY
        )
        val fallback = FixedBackend(
            id = "fallback",
            supportedModel = fallbackModel.descriptor.id,
            state = BackendState.DEGRADED
        )

        val runtime = runtime(
            models = listOf(textModel, mtmdModel, fallbackModel),
            backends = listOf(text, mtmd, fallback)
        )
        val first = AtomicReference<Result<InferenceResponse>>()
        val thread = Thread {
            first.set(
                runtime.infer(
                    InferenceRequest(
                        prompt = "occupy native execution group",
                        userPreferredModelId = textModel.descriptor.id
                    )
                )
            )
        }
        thread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = runtime.infer(
            InferenceRequest(
                prompt = "parallel sibling request",
                userPreferredModelId = mtmdModel.descriptor.id
            )
        ).getOrThrow()

        assertEquals(fallbackModel.descriptor.id, second.modelId)
        assertEquals("fallback", second.backendId)
        assertEquals(0, mtmd.inferCalls.get())
        assertEquals(1, fallback.inferCalls.get())

        release.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertEquals("native-text", first.get().getOrThrow().backendId)
    }

    private fun runtime(
        models: List<InstalledModel>,
        backends: List<InferenceBackend>
    ): TitanCortexRuntime {
        val registry = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        models.forEach {
            registry.register(it.descriptor)
            catalog.put(it)
        }
        val backendRegistry = InferenceBackendRegistry().apply {
            backends.forEach(::register)
        }
        return TitanCortexRuntime(
            models = registry,
            catalog = catalog,
            artifacts = StaticResolver(),
            backends = backendRegistry,
            governor = MobileResourceGovernor(
                ResourceBudget(
                    maxConcurrentAgents = 2,
                    memoryMb = 512,
                    thermalClass = 1
                )
            )
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

    private open class FixedBackend(
        override val id: String,
        private val supportedModel: ModelId,
        private val state: BackendState
    ) : ManagedInferenceBackend {
        val inferCalls = AtomicInteger(0)

        override fun supports(model: InstalledModel): Boolean =
            model.descriptor.id == supportedModel

        override fun health(): BackendHealth = BackendHealth(state)

        override fun estimate(
            model: InstalledModel,
            request: InferenceRequest
        ): InferenceCost = InferenceCost(
            estimatedMemoryMb = 64,
            preferredThreads = 2,
            contextTokens = 2048
        )

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = runCatching {
            inferCalls.incrementAndGet()
            InferenceResponse(model.descriptor.id, id, "ok")
        }
    }

    private class GroupedBackend(
        id: String,
        supportedModel: ModelId,
        state: BackendState,
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null
    ) : FixedBackend(id, supportedModel, state),
        ConcurrencyLimitedInferenceBackend,
        SharedExecutionGroupInferenceBackend {

        override val maxConcurrentExecutions: Int = 1
        override val executionGroupId: String = "native-llama"
        override val maxConcurrentExecutionsInGroup: Int = 1

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = runCatching {
            inferCalls.incrementAndGet()
            if (entered != null && release != null) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) {
                    "shared execution group release timed out"
                }
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
        override val displayName: String = "shared-execution-group.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
