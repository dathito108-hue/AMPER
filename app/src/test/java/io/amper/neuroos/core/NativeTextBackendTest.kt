package io.amper.neuroos.core

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTextBackendTest {
    @Test
    fun textGgufStreamsWithoutProjectorAndReusesImmutableWarmModel() {
        val fixture = fixture()
        try {
            val engine = FakeTextEngine()
            val backend = LlamaNativeTextInferenceBackend(engine)
            val request = fixture.request("first")

            assertTrue(backend.supports(fixture.model))
            assertTrue(backend.supportedAttachmentKinds(fixture.model).isEmpty())
            assertEquals(
                BackendSessionAffinity.COLD,
                backend.sessionAffinity(
                    fixture.model,
                    request,
                    backend.estimate(fixture.model, request)
                )
            )

            val chunks = mutableListOf<String>()
            val first = backend.inferStream(
                fixture.model,
                fixture.source,
                request,
                InferenceCancellationSignal()
            ) { chunk ->
                if (!chunk.finished) chunks += chunk.text
            }.getOrThrow()

            assertEquals("native ok", first.text)
            assertEquals(7, first.promptTokens)
            assertEquals(3, first.outputTokens)
            assertEquals(11L, first.promptEvalTimeMs)
            assertEquals(13L, first.generationTimeMs)
            assertEquals(3.0, first.tokensPerSecond ?: -1.0, 0.0001)
            assertEquals(listOf("native ", "ok"), chunks)
            assertEquals(1, engine.warmGenerateCalls)
            assertEquals(0, engine.coldGenerateCalls)
            assertEquals(
                BackendSessionAffinity.WARM_COMPATIBLE,
                backend.sessionAffinity(
                    fixture.model,
                    request,
                    backend.estimate(fixture.model, request)
                )
            )

            backend.inferStream(
                fixture.model,
                fixture.source,
                fixture.request("second"),
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            assertEquals(2, engine.warmGenerateCalls)
            assertEquals(1, engine.sessionKeys.distinct().size)
            assertTrue(engine.releasedKeys.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun multimodalPayloadCannotEnterTextNativeBackend() {
        val fixture = fixture()
        try {
            val backend = LlamaNativeTextInferenceBackend(FakeTextEngine())
            val request = InferenceRequest(
                prompt = "image",
                requiredCapabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.VISION
                ),
                attachments = listOf(
                    InferenceAttachment.fromBytes(
                        kind = InferenceAttachmentKind.IMAGE,
                        mediaType = "image/jpeg",
                        displayName = "frame.jpg",
                        bytes = byteArrayOf(1, 2, 3)
                    )
                )
            )

            assertTrue(
                MultimodalInferencePolicy.rejectionReason(
                    backend,
                    fixture.model,
                    request.attachments
                )?.startsWith("attachment-kind-unsupported") == true
            )
            assertTrue(
                backend.inferStream(
                    fixture.model,
                    fixture.source,
                    request,
                    InferenceCancellationSignal()
                ) { }.isFailure
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun accelerationTierChangeInvalidatesTextWarmSession() {
        val fixture = fixture()
        try {
            val engine = FakeTextEngine(acceleration = "cpu")
            val backend = LlamaNativeTextInferenceBackend(engine)
            val request = fixture.request("prime")

            backend.inferStream(
                fixture.model,
                fixture.source,
                request,
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            val firstKey = engine.sessionKeys.single()
            assertFalse(backend.health().hardwareAcceleration)

            engine.acceleration = "vulkan:test-gpu:layers=8"

            assertTrue(backend.health().hardwareAcceleration)
            assertEquals(
                BackendSessionAffinity.COLD,
                backend.sessionAffinity(
                    fixture.model,
                    request,
                    backend.estimate(fixture.model, request)
                )
            )

            backend.inferStream(
                fixture.model,
                fixture.source,
                fixture.request("accelerated"),
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            assertEquals(listOf(firstKey), engine.releasedKeys)
            assertEquals(2, engine.sessionKeys.size)
            assertFalse(engine.sessionKeys[0] == engine.sessionKeys[1])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun resourcePressureEvictsResidentTextModel() {
        val fixture = fixture()
        try {
            val engine = FakeTextEngine()
            val backend = LlamaNativeTextInferenceBackend(engine)
            val request = fixture.request("prime")

            backend.inferStream(
                fixture.model,
                fixture.source,
                request,
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            val reconciled = backend.reconcileResources(
                ResourceBudget(memoryMb = 64, thermalClass = 1)
            ).getOrThrow()

            assertEquals(setOf(fixture.model.descriptor.id), reconciled.releasedModelIds)
            assertEquals(1, engine.releasedKeys.size)
            assertEquals(
                BackendSessionAffinity.COLD,
                backend.sessionAffinity(
                    fixture.model,
                    request,
                    backend.estimate(fixture.model, request)
                )
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun nativeResidencyEvictionMakesCachedTextAffinityCold() {
        val fixture = fixture()
        try {
            val engine = FakeTextEngine()
            val backend = LlamaNativeTextInferenceBackend(engine)
            val request = fixture.request("prime")

            val first = backend.inferStream(
                fixture.model,
                fixture.source,
                request,
                InferenceCancellationSignal()
            ) { }.getOrThrow()
            assertFalse(first.sessionReused)
            assertEquals(
                BackendSessionAffinity.WARM_COMPATIBLE,
                backend.sessionAffinity(
                    fixture.model,
                    request,
                    backend.estimate(fixture.model, request)
                )
            )

            engine.evictNativeResidency()

            assertEquals(
                BackendSessionAffinity.COLD,
                backend.sessionAffinity(
                    fixture.model,
                    request,
                    backend.estimate(fixture.model, request)
                )
            )

            val second = backend.inferStream(
                fixture.model,
                fixture.source,
                fixture.request("reload"),
                InferenceCancellationSignal()
            ) { }.getOrThrow()
            assertFalse(second.sessionReused)
            assertEquals(2, engine.warmGenerateCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun preparationWarmsNativeTextWithoutGeneratingAndNextTurnReusesIt() {
        val fixture = fixture()
        try {
            val engine = FakeTextEngine()
            val backend = LlamaNativeTextInferenceBackend(engine)
            val request = fixture.request("prepare")

            val prepared = backend.prepare(
                fixture.model,
                fixture.source,
                request
            ).getOrThrow()

            assertFalse(prepared.sessionReused)
            assertEquals(1, engine.prepareCalls)
            assertEquals(0, engine.warmGenerateCalls)
            assertEquals(
                BackendSessionAffinity.WARM_COMPATIBLE,
                backend.sessionAffinity(
                    fixture.model,
                    request,
                    backend.estimate(fixture.model, request)
                )
            )

            val response = backend.inferStream(
                fixture.model,
                fixture.source,
                request,
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            assertTrue(response.sessionReused)
            assertEquals(1, engine.prepareCalls)
            assertEquals(1, engine.warmGenerateCalls)

            val preparedAgain = backend.prepare(
                fixture.model,
                fixture.source,
                request
            ).getOrThrow()
            assertTrue(preparedAgain.sessionReused)
            assertEquals(1, engine.prepareCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun verifiedWarmTokenizerRefinesContextPreflightWithoutColdNativeLoad() {
        val fixture = fixture()
        try {
            val engine = FakeTextEngine(promptTokenEstimate = 3_000)
            val backend = LlamaNativeTextInferenceBackend(engine)
            val request = InferenceRequest(
                prompt = "short visible prompt",
                requiredCapabilities = setOf(TitanCapabilities.REASONING),
                maxOutputTokens = 512
            )

            val coldEstimate = backend.estimate(fixture.model, request)
            assertEquals(2_048, coldEstimate.contextTokens)
            assertEquals(0, engine.promptEstimateCalls)

            backend.prepare(
                fixture.model,
                fixture.source,
                request
            ).getOrThrow()

            val warmPromptTokens = backend.estimatePromptTokens(fixture.model, request)
            val warmEstimate = backend.estimate(fixture.model, request)

            assertEquals(3_000, warmPromptTokens)
            assertEquals(4_096, warmEstimate.contextTokens)
            assertEquals(1, engine.promptEstimateCalls)

            assertEquals(
                3_000,
                backend.estimatePromptTokens(
                    fixture.model,
                    request.copy(prompt = "different prompt")
                )
            )
            assertEquals(2, engine.promptEstimateCalls)
            assertEquals(
                3_000,
                backend.estimatePromptTokens(
                    fixture.model,
                    request.copy(prompt = "different prompt")
                )
            )
            assertEquals(2, engine.promptEstimateCalls)

            engine.evictNativeResidency()
            val fallback = backend.estimatePromptTokens(fixture.model, request)
            assertEquals(TitanPromptTokenEstimator.estimate(request.prompt), fallback)
        } finally {
            fixture.close()
        }
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("amper-native-text").toFile()
        val modelFile = File(root, "text.gguf").also {
            it.writeBytes(
                GgufTestFixtures.validArtifact(
                    tensorCount = 1UL,
                    metadataCount = 1UL
                )
            )
        }
        val source = TestDescriptorSource(
            file = modelFile,
            locator = "content://models/native-text.gguf"
        )
        val inspection = GgufInspector().inspect(source).getOrThrow()
        val model = InstalledModel(
            descriptor = ModelDescriptor(
                id = ModelId("native-text"),
                format = "gguf",
                capabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.CODE_GENERATION,
                    TitanCapabilities.PLANNING
                ),
                local = true
            ),
            displayName = inspection.displayName,
            locator = inspection.locator,
            lengthBytes = inspection.lengthBytes,
            sha256 = inspection.sha256,
            ggufVersion = inspection.header.version,
            tensorCount = inspection.header.tensorCount,
            metadataKeyValueCount = inspection.header.metadataKeyValueCount
        )
        return Fixture(root, model, source)
    }

    private data class Fixture(
        val root: File,
        val model: InstalledModel,
        val source: TestDescriptorSource
    ) {
        fun request(prompt: String): InferenceRequest =
            InferenceRequest(
                prompt = prompt,
                requiredCapabilities = setOf(TitanCapabilities.REASONING)
            )

        fun close() {
            root.deleteRecursively()
        }
    }

    private class TestDescriptorSource(
        val file: File,
        override val locator: String
    ) : DescriptorBoundNativeModelPathSource {
        override val displayName: String = file.name
        override val lengthBytes: Long
            get() = file.length()

        override fun openStream(): InputStream = FileInputStream(file)

        override fun <T> withNativePath(block: (String) -> T): T =
            block(file.absolutePath)
    }

    private class FakeTextEngine(
        var acceleration: String = "cpu",
        var promptTokenEstimate: Int = 7
    ) : LlamaNativeTextEngine,
        LlamaNativePromptTokenEngine,
        PreparableWarmSessionLlamaNativeTextEngine,
        NativeAccelerationAwareEngine,
        NativeWarmSessionResidencyAwareEngine {

        override val engineId: String = "fake-native-text"
        var coldGenerateCalls: Int = 0
        var warmGenerateCalls: Int = 0
        var prepareCalls: Int = 0
        var promptEstimateCalls: Int = 0
        val sessionKeys = mutableListOf<String>()
        val releasedKeys = mutableListOf<String>()
        private val residentTextKeys = linkedSetOf<String>()

        override fun health(): BackendHealth =
            BackendHealth(
                state = BackendState.READY,
                detail = "fake text ready",
                hardwareAcceleration = acceleration.startsWith("vulkan:")
            )

        override fun accelerationIdentity(): String = acceleration

        override fun estimateWarmTextPromptTokens(
            sessionKey: String,
            prompt: String
        ): Result<Int> = runCatching {
            require(sessionKey in residentTextKeys)
            require(prompt.isNotBlank())
            promptEstimateCalls += 1
            promptTokenEstimate
        }

        override fun prepareWarmTextSession(
            sessionKey: String,
            modelPath: String,
            threads: Int
        ): Result<Unit> = runCatching {
            require(sessionKey.isNotBlank())
            require(modelPath.isNotBlank())
            require(threads > 0)
            prepareCalls += 1
            sessionKeys += sessionKey
            residentTextKeys.clear()
            residentTextKeys += sessionKey
        }

        override fun generateText(
            modelPath: String,
            request: InferenceRequest,
            threads: Int,
            contextTokens: Int,
            cancellation: InferenceCancellationSignal,
            onToken: (String) -> Unit
        ): Result<LlamaNativeGeneration> = runCatching {
            coldGenerateCalls += 1
            onToken("cold")
            LlamaNativeGeneration("cold")
        }

        override fun generateTextWarm(
            sessionKey: String,
            modelPath: String,
            request: InferenceRequest,
            threads: Int,
            contextTokens: Int,
            cancellation: InferenceCancellationSignal,
            onToken: (String) -> Unit
        ): Result<LlamaNativeGeneration> = runCatching {
            cancellation.throwIfCancelled()
            warmGenerateCalls += 1
            sessionKeys += sessionKey
            residentTextKeys.clear()
            residentTextKeys += sessionKey
            onToken("native ")
            onToken("ok")
            LlamaNativeGeneration(
                text = "native ok",
                promptTokens = 7,
                outputTokens = 3,
                tokensPerSecond = 3.0,
                promptEvalTimeMs = 11L,
                generationTimeMs = 13L
            )
        }

        override fun releaseWarmTextSession(sessionKey: String): Result<Unit> = runCatching {
            releasedKeys += sessionKey
            residentTextKeys.remove(sessionKey)
        }

        override fun isWarmTextSessionResident(sessionKey: String): Boolean =
            sessionKey in residentTextKeys

        override fun isWarmMtmdSessionResident(sessionKey: String): Boolean = false

        fun evictNativeResidency() {
            residentTextKeys.clear()
        }
    }
}
