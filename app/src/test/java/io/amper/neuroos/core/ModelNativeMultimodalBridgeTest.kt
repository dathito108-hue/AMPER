package io.amper.neuroos.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelNativeMultimodalBridgeTest {
    @Test
    fun attachmentRequiresExplicitModelCapability() {
        val image = InferenceAttachment.fromBytes(
            kind = InferenceAttachmentKind.IMAGE,
            mediaType = "image/jpeg",
            displayName = "frame.jpg",
            bytes = byteArrayOf(1, 2, 3)
        )

        val failure = runCatching {
            InferenceRequest(
                prompt = "describe this image",
                attachments = listOf(image)
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun textOnlyBackendIsRejectedBeforeInferenceStarts() {
        val fixture = Fixture(
            capabilities = setOf(TitanCapabilities.REASONING, TitanCapabilities.VISION)
        )
        val backend = TextOnlyBackend()
        fixture.backends.register(backend)
        val titan = fixture.titan()
        val image = imageAttachment()

        val result = titan.infer(
            InferenceRequest(
                prompt = "describe image",
                requiredCapabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.VISION
                ),
                attachments = listOf(image)
            )
        )

        assertTrue(result.isFailure)
        assertEquals(0, backend.executions)
    }

    @Test
    fun attachmentAwareBackendReceivesAuthoritativePayload() {
        val fixture = Fixture(
            capabilities = setOf(
                TitanCapabilities.REASONING,
                TitanCapabilities.VISION,
                TitanCapabilities.AUDIO_UNDERSTANDING
            )
        )
        val backend = MultimodalBackend(
            supportedKinds = setOf(
                InferenceAttachmentKind.IMAGE,
                InferenceAttachmentKind.AUDIO
            )
        )
        fixture.backends.register(backend)
        val titan = fixture.titan()
        val image = imageAttachment()
        val audio = InferenceAttachment.fromBytes(
            kind = InferenceAttachmentKind.AUDIO,
            mediaType = "audio/wav",
            displayName = "voice.wav",
            bytes = byteArrayOf(9, 8, 7, 6)
        )

        val response = titan.infer(
            InferenceRequest(
                prompt = "inspect both attachments",
                requiredCapabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.VISION,
                    TitanCapabilities.AUDIO_UNDERSTANDING
                ),
                attachments = listOf(image, audio)
            )
        ).getOrThrow()

        assertEquals(1, backend.executions)
        assertEquals(
            setOf(
                TitanCapabilities.REASONING,
                TitanCapabilities.VISION,
                TitanCapabilities.AUDIO_UNDERSTANDING
            ),
            response.selectedCapabilities
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), backend.received[0])
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), backend.received[1])
    }

    @Test
    fun assistantForwardsAttachmentOnlyThroughInferenceRequestAndDoesNotPersistRawPayload() {
        val runtime = AmperRuntime.reference()
        val image = InferenceAttachment.fromBytes(
            kind = InferenceAttachmentKind.IMAGE,
            mediaType = "image/png",
            displayName = "private-frame.png",
            bytes = "raw-secret-pixels".toByteArray()
        )
        var captured: InferenceRequest? = null
        val inference = object : CancellableStreamingCognitiveInferencePort {
            override fun infer(request: InferenceRequest): Result<InferenceResponse> =
                Result.failure(IllegalStateException("streaming path expected"))

            override fun inferStream(
                request: InferenceRequest,
                onChunk: (InferenceChunk) -> Unit
            ): Result<InferenceResponse> =
                Result.failure(IllegalStateException("cancellable path expected"))

            override fun inferStream(
                request: InferenceRequest,
                cancellation: InferenceCancellationSignal,
                onChunk: (InferenceChunk) -> Unit
            ): Result<InferenceResponse> = runCatching {
                captured = request
                cancellation.throwIfCancelled()
                onChunk(InferenceChunk("image understood", 0))
                onChunk(InferenceChunk("", 1, finished = true))
                InferenceResponse(
                    modelId = ModelId("vision-model"),
                    backendId = "vision-backend",
                    text = "image understood",
                    selectedCapabilities = request.requiredCapabilities
                )
            }
        }
        val registry = InMemoryToolRegistry()
        val assistant = SovereignAssistantTurnCoordinator(
            runtime = runtime,
            inference = inference,
            actions = runtime.actionLoop(
                registry = registry,
                fabric = AuditedToolFabric(
                    gate = DenyByDefaultAuthorityGate(emptySet()),
                    registry = registry,
                    audit = InMemoryToolAuditLog()
                )
            ),
            advertisedCapabilities = emptySet()
        )
        val conversation = ConversationId("multimodal-assistant")
        val result = assistant.respondStreaming(
            conversationId = conversation,
            userPrompt = "What is in this frame?",
            attachments = listOf(image),
            cancellation = InferenceCancellationSignal(),
            onEvent = { }
        )

        assertTrue(result.isSuccess)
        val request = checkNotNull(captured)
        assertEquals(listOf(InferenceAttachmentKind.IMAGE), request.attachments.map { it.kind })
        assertTrue(TitanCapabilities.VISION in request.requiredCapabilities)
        assertArrayEquals("raw-secret-pixels".toByteArray(), request.attachments.single().readBytes())

        val persistedText = runtime.conversations.recent(conversation, 16)
            .joinToString("\n") { it.text }
        assertFalse(persistedText.contains("raw-secret-pixels"))
        assertFalse(persistedText.contains("private-frame.png"))
    }

    @Test
    fun multimodalWorkloadsUseSeparateFeedbackIdentity() {
        val text = InferenceRequest(prompt = "hello")
        val image = imageAttachment()
        val vision = InferenceRequest(
            prompt = "hello",
            requiredCapabilities = setOf(TitanCapabilities.REASONING, TitanCapabilities.VISION),
            attachments = listOf(image)
        )

        assertEquals(
            TitanInputModalityClass.TEXT_ONLY,
            TitanInferenceWorkloadClass.from(text).inputModality
        )
        assertEquals(
            TitanInputModalityClass.IMAGE,
            TitanInferenceWorkloadClass.from(vision).inputModality
        )
    }

    private fun imageAttachment(): InferenceAttachment = InferenceAttachment.fromBytes(
        kind = InferenceAttachmentKind.IMAGE,
        mediaType = "image/jpeg",
        displayName = "frame.jpg",
        bytes = byteArrayOf(1, 2, 3, 4)
    )

    private class StaticSource : ModelArtifactSource {
        override val locator: String = "memory://model.gguf"
        override val displayName: String = "model.gguf"
        override val lengthBytes: Long = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private class Fixture(capabilities: Set<CapabilityId>) {
        private val descriptor = ModelDescriptor(
            id = ModelId("multimodal-model"),
            format = "gguf",
            capabilities = capabilities,
            local = true
        )
        private val installed = InstalledModel(
            descriptor = descriptor,
            displayName = "multimodal.gguf",
            locator = "memory://model.gguf",
            lengthBytes = 128L,
            sha256 = "a".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL,
            installedAtEpochMs = 1L
        )
        val backends = InferenceBackendRegistry()
        private val models = InMemoryModelRegistry().also { it.register(descriptor) }
        private val catalog = InMemoryInstalledModelCatalog().also { it.put(installed) }

        fun titan(): TitanCortexRuntime = TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource = StaticSource()
            },
            backends = backends
        )
    }

    private class TextOnlyBackend : ManagedInferenceBackend {
        override val id: String = "text-only"
        var executions = 0

        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(estimatedMemoryMb = 64, preferredThreads = 1, contextTokens = 4096)

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            executions += 1
            return Result.success(
                InferenceResponse(model.descriptor.id, id, "must not run")
            )
        }
    }

    private class MultimodalBackend(
        private val supportedKinds: Set<InferenceAttachmentKind>
    ) : ManagedInferenceBackend, AttachmentAwareInferenceBackend {
        override val id: String = "native-multimodal"
        var executions = 0
        val received = mutableListOf<ByteArray>()

        override fun supports(model: InstalledModel): Boolean = true
        override fun supportedAttachmentKinds(model: InstalledModel): Set<InferenceAttachmentKind> =
            supportedKinds
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(estimatedMemoryMb = 64, preferredThreads = 1, contextTokens = 4096)

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = runCatching {
            executions += 1
            received += request.attachments.map { it.readBytes() }
            InferenceResponse(
                modelId = model.descriptor.id,
                backendId = id,
                text = "multimodal ok"
            )
        }
    }
}
