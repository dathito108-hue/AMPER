package io.amper.neuroos.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitanGovernedStreamingTest {
    private val reasoning = TitanCapabilities.REASONING

    @Test
    fun titanStreamsSelectedBackendUnderNormalRouteAndAdmission() {
        val descriptor = ModelDescriptor(
            id = ModelId("stream-brain"),
            format = "gguf",
            capabilities = setOf(reasoning),
            local = true
        )
        val installed = InstalledModel(
            descriptor = descriptor,
            displayName = "stream-brain.gguf",
            locator = "memory://stream-brain.gguf",
            lengthBytes = 128L,
            sha256 = "a".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL,
            installedAtEpochMs = 1L
        )
        val models = InMemoryModelRegistry().also { it.register(descriptor) }
        val catalog = InMemoryInstalledModelCatalog().also { it.put(installed) }
        val backend = RecordingStreamingBackend()
        val backends = InferenceBackendRegistry().also { it.register(backend) }
        val titan = TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource =
                    StaticSource(model.locator)
            },
            backends = backends,
            governor = MobileResourceGovernor(
                ResourceBudget(memoryMb = 1_024, thermalClass = 1)
            )
        )
        val chunks = mutableListOf<InferenceChunk>()

        val response = titan.inferStream(
            InferenceRequest("stream through Titan", maxOutputTokens = 64),
            chunks::add
        ).getOrThrow()

        assertEquals("hello world", response.text)
        assertEquals(setOf(reasoning), response.selectedCapabilities)
        assertEquals(listOf("hello", " ", "world"), chunks.filterNot { it.finished }.map { it.text })
        assertTrue(chunks.last().finished)
        assertEquals(1, backend.executions)
    }

    @Test
    fun mismatchedStreamingTextFailsClosedBeforeAuthoritativeSuccess() {
        val descriptor = ModelDescriptor(
            id = ModelId("mismatch-brain"),
            format = "gguf",
            capabilities = setOf(reasoning),
            local = true
        )
        val installed = InstalledModel(
            descriptor = descriptor,
            displayName = "mismatch.gguf",
            locator = "memory://mismatch.gguf",
            lengthBytes = 128L,
            sha256 = "c".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL,
            installedAtEpochMs = 1L
        )
        val models = InMemoryModelRegistry().also { it.register(descriptor) }
        val catalog = InMemoryInstalledModelCatalog().also { it.put(installed) }
        val backend = object : ManagedInferenceBackend, StreamingInferenceBackend {
            override val id: String = "mismatch-backend"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 1, 4_096)

            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> =
                Result.success(InferenceResponse(model.descriptor.id, id, "authoritative"))

            override fun inferStream(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest,
                onChunk: (InferenceChunk) -> Unit
            ): Result<InferenceResponse> = runCatching {
                onChunk(InferenceChunk("different", 0))
                onChunk(InferenceChunk("", 1, finished = true))
                InferenceResponse(model.descriptor.id, id, "authoritative")
            }
        }
        val titan = TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource =
                    StaticSource(model.locator)
            },
            backends = InferenceBackendRegistry().also { it.register(backend) }
        )

        val result = titan.inferStream(InferenceRequest("mismatch"), onChunk = { })

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message
                ?.contains("streamed text does not match authoritative inference response") == true
        )
    }

    @Test
    fun trueStreamingBackendKeepsFullCallerOutputBudget() {
        val backend = RecordingStreamingBackend()
        val request = InferenceRequest("stream budget", maxOutputTokens = 384)

        val bounded = TitanBlockingStreamFallbackPolicy.bound(backend, request)

        assertEquals(384, bounded.maxOutputTokens)
    }

    @Test
    fun blockingBackendOutputBudgetIsCappedForInteractiveStreamFallback() {
        val backend = BlockingBackend()
        val request = InferenceRequest("blocking budget", maxOutputTokens = 384)

        val bounded = TitanBlockingStreamFallbackPolicy.bound(backend, request)

        assertEquals(32, TitanBlockingStreamFallbackPolicy.MAX_OUTPUT_TOKENS)
        assertEquals(32, bounded.maxOutputTokens)
        assertEquals(384, request.maxOutputTokens)
    }

    @Test
    fun blockingBackendFallsBackToOneCompletedChunkWithoutPretendingRealtime() {
        val descriptor = ModelDescriptor(
            id = ModelId("blocking-brain"),
            format = "gguf",
            capabilities = setOf(reasoning),
            local = true
        )
        val installed = InstalledModel(
            descriptor = descriptor,
            displayName = "blocking.gguf",
            locator = "memory://blocking.gguf",
            lengthBytes = 128L,
            sha256 = "b".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL,
            installedAtEpochMs = 1L
        )
        val models = InMemoryModelRegistry().also { it.register(descriptor) }
        val catalog = InMemoryInstalledModelCatalog().also { it.put(installed) }
        val backend = BlockingBackend()
        val titan = TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource =
                    StaticSource(model.locator)
            },
            backends = InferenceBackendRegistry().also { it.register(backend) }
        )
        val chunks = mutableListOf<InferenceChunk>()

        val response = titan.inferStream(
            InferenceRequest("blocking fallback", maxOutputTokens = 384),
            chunks::add
        ).getOrThrow()

        assertEquals("completed-only", response.text)
        assertEquals(listOf("completed-only"), chunks.filterNot { it.finished }.map { it.text })
        assertTrue(chunks.last().finished)
        assertEquals(1, backend.executions)
        assertEquals(
            TitanBlockingStreamFallbackPolicy.MAX_OUTPUT_TOKENS,
            backend.lastMaxOutputTokens
        )
    }

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private class RecordingStreamingBackend : ManagedInferenceBackend, StreamingInferenceBackend {
        override val id: String = "stream-backend"
        var executions: Int = 0

        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(estimatedMemoryMb = 128, preferredThreads = 1, contextTokens = 4_096)

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> =
            inferStream(model, source, request) { }

        override fun inferStream(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest,
            onChunk: (InferenceChunk) -> Unit
        ): Result<InferenceResponse> = runCatching {
            executions += 1
            val pieces = listOf("hello", " ", "world")
            pieces.forEachIndexed { index, text -> onChunk(InferenceChunk(text, index)) }
            onChunk(InferenceChunk("", pieces.size, finished = true))
            InferenceResponse(model.descriptor.id, id, pieces.joinToString(""))
        }
    }

    private class BlockingBackend : ManagedInferenceBackend {
        override val id: String = "blocking-backend"
        var executions: Int = 0
        var lastMaxOutputTokens: Int? = null

        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(estimatedMemoryMb = 128, preferredThreads = 1, contextTokens = 4_096)

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            executions += 1
            lastMaxOutputTokens = request.maxOutputTokens
            return Result.success(
                InferenceResponse(model.descriptor.id, id, "completed-only")
            )
        }
    }
}
