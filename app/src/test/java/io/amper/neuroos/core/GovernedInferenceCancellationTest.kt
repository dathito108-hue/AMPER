package io.amper.neuroos.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernedInferenceCancellationTest {
    @Test
    fun assistantCancellationResetsTransientTextAndDoesNotCommitAssistantTurn() {
        val runtime = AmperRuntime.reference()
        val inference = object : CancellableStreamingCognitiveInferencePort {
            override fun infer(request: InferenceRequest): Result<InferenceResponse> =
                Result.failure(IllegalStateException("blocking path not expected"))

            override fun inferStream(
                request: InferenceRequest,
                onChunk: (InferenceChunk) -> Unit
            ): Result<InferenceResponse> =
                Result.failure(IllegalStateException("uncancelled stream path not expected"))

            override fun inferStream(
                request: InferenceRequest,
                cancellation: InferenceCancellationSignal,
                onChunk: (InferenceChunk) -> Unit
            ): Result<InferenceResponse> = runCatching {
                onChunk(InferenceChunk("partial answer", 0))
                cancellation.cancel()
                cancellation.throwIfCancelled()
                InferenceResponse(
                    modelId = ModelId("cancel-model"),
                    backendId = "cancel-backend",
                    text = "partial answer"
                )
            }
        }
        val actions = runtime.actionLoop(
            registry = InMemoryToolRegistry(),
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(emptySet()),
                registry = InMemoryToolRegistry(),
                audit = InMemoryToolAuditLog()
            )
        )
        val assistant = SovereignAssistantTurnCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = emptySet()
        )
        val conversation = ConversationId("cancel-assistant")
        val cancellation = InferenceCancellationSignal()
        val events = mutableListOf<AssistantStreamEvent>()

        val result = assistant.respondStreaming(
            conversation,
            "produce a long answer",
            cancellation,
            events::add
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InferenceCancelledException)
        assertTrue(events.any { it == AssistantStreamEvent.Reset })
        assertFalse(
            runtime.conversations.recent(conversation, 16)
                .any { it.role == ConversationRole.ASSISTANT }
        )
    }

    @Test
    fun titanCancellationReleasesExecutionLeaseAndFreshRequestCanRun() {
        val descriptor = ModelDescriptor(
            id = ModelId("cancel-brain"),
            format = "gguf",
            capabilities = setOf(TitanCapabilities.REASONING),
            local = true
        )
        val installed = InstalledModel(
            descriptor = descriptor,
            displayName = "cancel-brain.gguf",
            locator = "memory://cancel-brain.gguf",
            lengthBytes = 128L,
            sha256 = "a".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL,
            installedAtEpochMs = 1L
        )
        val models = InMemoryModelRegistry().also { it.register(descriptor) }
        val catalog = InMemoryInstalledModelCatalog().also { it.put(installed) }
        val backend = CancellingBackend()
        val titan = TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource =
                    StaticSource(model.locator)
            },
            backends = InferenceBackendRegistry().also { it.register(backend) },
            governor = MobileResourceGovernor(
                ResourceBudget(
                    maxConcurrentAgents = 1,
                    memoryMb = 512,
                    thermalClass = 1
                )
            )
        )
        val cancellation = InferenceCancellationSignal()
        val transient = mutableListOf<String>()

        val cancelled = titan.inferStream(
            request = InferenceRequest("cancel me"),
            cancellation = cancellation
        ) { chunk ->
            if (!chunk.finished) {
                transient += chunk.text
                cancellation.cancel()
            }
        }

        assertTrue(cancelled.isFailure)
        assertTrue(cancelled.exceptionOrNull() is InferenceCancelledException)
        assertEquals(listOf("first"), transient)

        backend.cancelOnFirstChunk = false
        val fresh = titan.inferStream(
            request = InferenceRequest("fresh request"),
            cancellation = InferenceCancellationSignal(),
            onChunk = { }
        ).getOrThrow()

        assertEquals("firstsecond", fresh.text)
        assertEquals(2, backend.executions)
    }

    @Test
    fun cancellationDuringRoutePreflightNeverStartsBackendOrRecordsRouteDecision() {
        val descriptor = ModelDescriptor(
            id = ModelId("planning-cancel"),
            format = "gguf",
            capabilities = setOf(TitanCapabilities.REASONING),
            local = true
        )
        val installed = InstalledModel(
            descriptor = descriptor,
            displayName = "planning-cancel.gguf",
            locator = "memory://planning-cancel.gguf",
            lengthBytes = 128L,
            sha256 = "c".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL,
            installedAtEpochMs = 1L
        )
        val signal = InferenceCancellationSignal()
        val backend = object : ManagedInferenceBackend {
            override val id: String = "planning-cancel-backend"
            var cancelDuringEstimate: Boolean = true
            var executions: Int = 0

            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)

            override fun estimate(
                model: InstalledModel,
                request: InferenceRequest
            ): InferenceCost {
                if (cancelDuringEstimate) signal.cancel()
                return InferenceCost(128, 1, 4_096)
            }

            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> {
                executions += 1
                return Result.success(
                    InferenceResponse(model.descriptor.id, id, "fresh")
                )
            }
        }
        val titan = TitanCortexRuntime(
            models = InMemoryModelRegistry().also { it.register(descriptor) },
            catalog = InMemoryInstalledModelCatalog().also { it.put(installed) },
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource =
                    StaticSource(model.locator)
            },
            backends = InferenceBackendRegistry().also { it.register(backend) },
            governor = MobileResourceGovernor(
                ResourceBudget(
                    maxConcurrentAgents = 1,
                    memoryMb = 512,
                    thermalClass = 1
                )
            )
        )

        val cancelled = titan.inferStream(
            request = InferenceRequest("cancel during route preflight"),
            cancellation = signal,
            onChunk = { }
        )

        assertTrue(cancelled.isFailure)
        assertTrue(cancelled.exceptionOrNull() is InferenceCancelledException)
        assertEquals(0, backend.executions)
        assertTrue(titan.latestRouteObservation() == null)

        backend.cancelDuringEstimate = false
        val fresh = titan.inferStream(
            request = InferenceRequest("fresh after planning cancellation"),
            cancellation = InferenceCancellationSignal(),
            onChunk = { }
        ).getOrThrow()

        assertEquals("fresh", fresh.text)
        assertEquals(1, backend.executions)
    }

    @Test
    fun blockingBackendResultIsDiscardedWhenCancellationHappensBeforeCompletionObservation() {
        val descriptor = ModelDescriptor(
            id = ModelId("blocking-cancel"),
            format = "gguf",
            capabilities = setOf(TitanCapabilities.REASONING),
            local = true
        )
        val installed = InstalledModel(
            descriptor = descriptor,
            displayName = "blocking-cancel.gguf",
            locator = "memory://blocking-cancel.gguf",
            lengthBytes = 128L,
            sha256 = "b".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL,
            installedAtEpochMs = 1L
        )
        val signal = InferenceCancellationSignal()
        val backend = object : ManagedInferenceBackend {
            override val id: String = "blocking-cancel-backend"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 1, 4_096)

            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> {
                signal.cancel()
                return Result.success(
                    InferenceResponse(model.descriptor.id, id, "must-be-discarded")
                )
            }
        }
        val titan = TitanCortexRuntime(
            models = InMemoryModelRegistry().also { it.register(descriptor) },
            catalog = InMemoryInstalledModelCatalog().also { it.put(installed) },
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource =
                    StaticSource(model.locator)
            },
            backends = InferenceBackendRegistry().also { it.register(backend) }
        )
        val chunks = mutableListOf<InferenceChunk>()

        val result = titan.inferStream(
            request = InferenceRequest("blocking cancellation"),
            cancellation = signal,
            onChunk = chunks::add
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InferenceCancelledException)
        assertTrue(chunks.isEmpty())
    }

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private class CancellingBackend : ManagedInferenceBackend, CancellableStreamingInferenceBackend {
        override val id: String = "cancel-stream-backend"
        var executions: Int = 0
        var cancelOnFirstChunk: Boolean = true

        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(128, 1, 4_096)

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> =
            inferStream(
                model,
                source,
                request,
                InferenceCancellationSignal(),
                onChunk = { }
            )

        override fun inferStream(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest,
            onChunk: (InferenceChunk) -> Unit
        ): Result<InferenceResponse> =
            inferStream(
                model,
                source,
                request,
                InferenceCancellationSignal(),
                onChunk
            )

        override fun inferStream(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest,
            cancellation: InferenceCancellationSignal,
            onChunk: (InferenceChunk) -> Unit
        ): Result<InferenceResponse> = runCatching {
            executions += 1
            onChunk(InferenceChunk("first", 0))
            if (cancelOnFirstChunk) cancellation.throwIfCancelled()
            onChunk(InferenceChunk("second", 1))
            onChunk(InferenceChunk("", 2, finished = true))
            InferenceResponse(model.descriptor.id, id, "firstsecond")
        }
    }
}
