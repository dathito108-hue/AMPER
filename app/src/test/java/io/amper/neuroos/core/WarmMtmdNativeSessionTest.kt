package io.amper.neuroos.core

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmMtmdNativeSessionTest {
    @Test
    fun successfulNativeTurnCreatesWarmAffinityAndReusesExactRuntimeIdentity() {
        val fixture = fixture()
        try {
            val engine = FakeWarmEngine()
            val backend = fixture.backend(engine)
            val request = fixture.imageRequest("first")

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
                fixture.modelSource,
                request,
                InferenceCancellationSignal()
            ) { chunk ->
                if (!chunk.finished) chunks += chunk.text
            }.getOrThrow()

            assertFalse(first.sessionReused)
            assertEquals(9, first.promptTokens)
            assertEquals(4, first.outputTokens)
            assertEquals(17L, first.promptEvalTimeMs)
            assertEquals(19L, first.generationTimeMs)
            assertEquals(4.0, first.tokensPerSecond ?: -1.0, 0.0001)
            assertEquals(listOf("warm ", "ok"), chunks)
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

            val second = backend.inferStream(
                fixture.model,
                fixture.modelSource,
                fixture.imageRequest("second"),
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            assertTrue(second.sessionReused)
            assertEquals(2, engine.warmGenerateCalls)
            assertEquals(1, engine.sessionKeys.distinct().size)
            assertEquals(0, engine.releasedKeys.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unloadReleasesWarmRuntimeAndReturnsAffinityToCold() {
        val fixture = fixture()
        try {
            val engine = FakeWarmEngine()
            val backend = fixture.backend(engine)
            val request = fixture.imageRequest("prime")

            backend.inferStream(
                fixture.model,
                fixture.modelSource,
                request,
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            val warmedKey = engine.sessionKeys.single()
            backend.unload(fixture.model.descriptor.id).getOrThrow()

            assertEquals(listOf(warmedKey), engine.releasedKeys)
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
    fun resourcePressureEvictsWarmRuntimeWithoutRemovingModel() {
        val fixture = fixture()
        try {
            val engine = FakeWarmEngine()
            val backend = fixture.backend(engine)
            val request = fixture.imageRequest("prime")

            backend.inferStream(
                fixture.model,
                fixture.modelSource,
                request,
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            val reconciled = backend.reconcileResources(
                ResourceBudget(
                    memoryMb = 64,
                    thermalClass = 1
                )
            ).getOrThrow()

            assertEquals(setOf(fixture.model.descriptor.id), reconciled.releasedModelIds)
            assertEquals(1, engine.releasedKeys.size)
            assertTrue(backend.supports(fixture.model))
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
    fun failedFirstWarmGenerationIsReleasedAndNeverAdvertisedAsWarm() {
        val fixture = fixture()
        try {
            val engine = FakeWarmEngine(failFirstWarmGeneration = true)
            val backend = fixture.backend(engine)
            val request = fixture.imageRequest("fail")

            val result = backend.inferStream(
                fixture.model,
                fixture.modelSource,
                request,
                InferenceCancellationSignal()
            ) { }

            assertTrue(result.isFailure)
            assertEquals(1, engine.warmGenerateCalls)
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
    fun accelerationAwareEngineExposesHardwareAccelerationToTitanPolicy() {
        val fixture = fixture()
        try {
            val engine = FakeWarmEngine(acceleration = "cpu")
            val backend = fixture.backend(engine)

            assertFalse(backend.health().hardwareAcceleration)

            engine.acceleration = "vulkan:test-gpu:layers=8"

            assertTrue(backend.health().hardwareAcceleration)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun accelerationTierChangeInvalidatesWarmRuntimeBeforeReuse() {
        val fixture = fixture()
        try {
            val engine = FakeWarmEngine(acceleration = "cpu")
            val backend = fixture.backend(engine)
            val request = fixture.imageRequest("prime")

            backend.inferStream(
                fixture.model,
                fixture.modelSource,
                request,
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            val firstKey = engine.sessionKeys.single()
            assertEquals(
                BackendSessionAffinity.WARM_COMPATIBLE,
                backend.sessionAffinity(
                    fixture.model,
                    request,
                    backend.estimate(fixture.model, request)
                )
            )

            engine.acceleration = "vulkan:test-gpu:layers=8"
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
                fixture.modelSource,
                fixture.imageRequest("accelerated"),
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            assertEquals(listOf(firstKey), engine.releasedKeys)
            assertEquals(2, engine.sessionKeys.size)
            assertFalse(engine.sessionKeys[0] == engine.sessionKeys[1])
            assertEquals(
                BackendSessionAffinity.WARM_COMPATIBLE,
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
    fun legacyEngineWithoutWarmCapabilityKeepsColdPath() {
        val fixture = fixture()
        try {
            val engine = object : MtmdNativeEngine {
                override val engineId: String = "legacy"
                var generateCalls = 0

                override fun health(): BackendHealth =
                    BackendHealth(BackendState.READY, "legacy ready")

                override fun probeProjector(
                    projectorPath: String
                ): Result<Set<InferenceAttachmentKind>> =
                    Result.success(setOf(InferenceAttachmentKind.IMAGE))

                override fun generate(
                    modelPath: String,
                    projectorPath: String,
                    request: InferenceRequest,
                    threads: Int,
                    contextTokens: Int,
                    cancellation: InferenceCancellationSignal,
                    onToken: (String) -> Unit
                ): Result<MtmdNativeGeneration> = runCatching {
                    generateCalls += 1
                    onToken("legacy")
                    MtmdNativeGeneration("legacy")
                }
            }
            val backend = fixture.backend(engine)
            val request = fixture.imageRequest("legacy")

            backend.inferStream(
                fixture.model,
                fixture.modelSource,
                request,
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            assertEquals(1, engine.generateCalls)
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
    fun nativeResidencyEvictionMakesCachedMtmdAffinityCold() {
        val fixture = fixture()
        try {
            val engine = FakeWarmEngine()
            val backend = fixture.backend(engine)
            val request = fixture.imageRequest("prime")

            val first = backend.inferStream(
                fixture.model,
                fixture.modelSource,
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
                fixture.modelSource,
                fixture.imageRequest("reload"),
                InferenceCancellationSignal()
            ) { }.getOrThrow()
            assertFalse(second.sessionReused)
            assertEquals(2, engine.warmGenerateCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun preparationWarmsMtmdWithoutGeneratingAndNextTurnReusesIt() {
        val fixture = fixture()
        try {
            val engine = FakeWarmEngine()
            val backend = fixture.backend(engine)
            val request = fixture.imageRequest("prepare")

            val prepared = backend.prepare(
                fixture.model,
                fixture.modelSource,
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
                fixture.modelSource,
                request,
                InferenceCancellationSignal()
            ) { }.getOrThrow()

            assertTrue(response.sessionReused)
            assertEquals(1, engine.prepareCalls)
            assertEquals(1, engine.warmGenerateCalls)

            val preparedAgain = backend.prepare(
                fixture.model,
                fixture.modelSource,
                request
            ).getOrThrow()
            assertTrue(preparedAgain.sessionReused)
            assertEquals(1, engine.prepareCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun warmMtmdRouteCannotCaptureATextOnlyRequest() {
        val fixture = fixture()
        try {
            val engine = FakeWarmEngine()
            val backend = fixture.backend(engine)
            val imageRequest = fixture.imageRequest("warm multimodal route")

            backend.prepare(
                fixture.model,
                fixture.modelSource,
                imageRequest
            ).getOrThrow()

            assertEquals(
                BackendSessionAffinity.WARM_COMPATIBLE,
                backend.sessionAffinity(
                    fixture.model,
                    imageRequest,
                    backend.estimate(fixture.model, imageRequest)
                )
            )

            val textOnly = InferenceRequest(
                prompt = "text only",
                requiredCapabilities = setOf(TitanCapabilities.REASONING)
            )
            assertFalse(backend.supportsRequest(fixture.model, textOnly))
            assertFalse(
                TitanBackendPolicy.eligibleForRequest(
                    backend,
                    fixture.model,
                    textOnly
                )
            )
            assertFalse(
                TitanBackendPolicy.eligible(
                    backend,
                    fixture.model,
                    textOnly,
                    ResourceBudget(memoryMb = 8_192, thermalClass = 1)
                )
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun warmMtmdTokenizerRejectsExactContextOverflowBeforeGeneration() {
        val fixture = fixture()
        try {
            val engine = FakeWarmEngine(promptTokenEstimate = 8_000)
            val backend = fixture.backend(engine)
            val request = fixture.imageRequest("short multimodal prompt").copy(
                maxOutputTokens = 512
            )
            val budget = ResourceBudget(memoryMb = 8_192, thermalClass = 1)

            assertEquals(
                TitanPromptTokenEstimator.estimate(request.prompt),
                backend.estimatePromptTokens(fixture.model, request)
            )
            assertEquals(0, engine.promptEstimateCalls)
            assertTrue(
                TitanBackendPolicy.eligible(
                    backend,
                    fixture.model,
                    request,
                    budget
                )
            )

            backend.prepare(
                fixture.model,
                fixture.modelSource,
                request
            ).getOrThrow()

            assertEquals(8_000, backend.estimatePromptTokens(fixture.model, request))
            assertEquals(1, engine.promptEstimateCalls)
            assertFalse(
                TitanBackendPolicy.eligible(
                    backend,
                    fixture.model,
                    request,
                    budget
                )
            )
            assertEquals(1, engine.promptEstimateCalls)
            assertEquals(0, engine.warmGenerateCalls)

            val differentAttachment = request.copy(
                attachments = listOf(
                    InferenceAttachment.fromBytes(
                        kind = InferenceAttachmentKind.IMAGE,
                        mediaType = "image/jpeg",
                        displayName = "different.jpg",
                        bytes = byteArrayOf(9, 8, 7, 6)
                    )
                )
            )
            assertEquals(
                8_000,
                backend.estimatePromptTokens(fixture.model, differentAttachment)
            )
            assertEquals(2, engine.promptEstimateCalls)
            assertEquals(
                8_000,
                backend.estimatePromptTokens(fixture.model, differentAttachment)
            )
            assertEquals(2, engine.promptEstimateCalls)

            engine.evictNativeResidency()
            assertEquals(
                TitanPromptTokenEstimator.estimate(request.prompt),
                backend.estimatePromptTokens(fixture.model, request)
            )
        } finally {
            fixture.close()
        }
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("amper-warm-mtmd").toFile()
        val modelFile = File(root, "model.gguf").also {
            it.writeBytes(
                GgufTestFixtures.validArtifact(
                    tensorCount = 1UL,
                    metadataCount = 1UL
                )
            )
        }
        val projectorFile = File(root, "mmproj.gguf").also {
            it.writeBytes(
                GgufTestFixtures.validArtifact(
                    tensorCount = 1UL,
                    metadataCount = 1UL
                )
            )
        }
        val modelSource = TestDescriptorSource(
            modelFile,
            "content://models/warm.gguf"
        )
        val projectorSource = TestDescriptorSource(
            projectorFile,
            "content://projectors/warm-mmproj.gguf"
        )
        val modelInspection = GgufInspector().inspect(modelSource).getOrThrow()
        val model = InstalledModel(
            descriptor = ModelDescriptor(
                id = ModelId("warm-model"),
                format = "gguf",
                capabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.VISION
                ),
                local = true
            ),
            displayName = modelInspection.displayName,
            locator = modelInspection.locator,
            lengthBytes = modelInspection.lengthBytes,
            sha256 = modelInspection.sha256,
            ggufVersion = modelInspection.header.version,
            tensorCount = modelInspection.header.tensorCount,
            metadataKeyValueCount = modelInspection.header.metadataKeyValueCount
        )
        val projectorInspection = GgufInspector().inspect(projectorSource).getOrThrow()
        val projector = InstalledMultimodalProjector(
            modelId = model.descriptor.id,
            displayName = projectorInspection.displayName,
            locator = projectorInspection.locator,
            lengthBytes = projectorInspection.lengthBytes,
            sha256 = projectorInspection.sha256,
            ggufVersion = projectorInspection.header.version,
            tensorCount = projectorInspection.header.tensorCount,
            metadataKeyValueCount = projectorInspection.header.metadataKeyValueCount,
            expectedKinds = setOf(InferenceAttachmentKind.IMAGE)
        )
        return Fixture(
            root = root,
            model = model,
            modelSource = modelSource,
            projector = projector,
            projectorSource = projectorSource
        )
    }

    private data class Fixture(
        val root: File,
        val model: InstalledModel,
        val modelSource: TestDescriptorSource,
        val projector: InstalledMultimodalProjector,
        val projectorSource: TestDescriptorSource
    ) {
        fun backend(engine: MtmdNativeEngine): MtmdNativeInferenceBackend =
            MtmdNativeInferenceBackend(
                engine = engine,
                projectors = InMemoryMultimodalProjectorCatalog().also {
                    it.put(projector)
                },
                projectorArtifacts = object : MultimodalProjectorArtifactResolver {
                    override fun resolve(
                        projector: InstalledMultimodalProjector
                    ): ModelArtifactSource = projectorSource
                }
            )

        fun imageRequest(prompt: String): InferenceRequest =
            InferenceRequest(
                prompt = prompt,
                requiredCapabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.VISION
                ),
                attachments = listOf(
                    InferenceAttachment.fromBytes(
                        kind = InferenceAttachmentKind.IMAGE,
                        mediaType = "image/jpeg",
                        displayName = "frame.jpg",
                        bytes = byteArrayOf(1, 2, 3, 4)
                    )
                )
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

    private class FakeWarmEngine(
        private val failFirstWarmGeneration: Boolean = false,
        var acceleration: String = "cpu",
        var promptTokenEstimate: Int = 9
    ) : MtmdNativeEngine,
        MtmdNativePromptTokenEngine,
        PreparableWarmSessionMtmdNativeEngine,
        MtmdNativeAccelerationAwareEngine,
        NativeWarmSessionResidencyAwareEngine {
        override val engineId: String = "fake-warm-mtmd"
        var coldGenerateCalls: Int = 0
        var warmGenerateCalls: Int = 0
        var prepareCalls: Int = 0
        var promptEstimateCalls: Int = 0
        val sessionKeys = mutableListOf<String>()
        val releasedKeys = mutableListOf<String>()
        private val residentMtmdKeys = linkedSetOf<String>()

        override fun health(): BackendHealth =
            BackendHealth(
                BackendState.READY,
                "warm fake ready",
                hardwareAcceleration = acceleration.startsWith("vulkan:")
            )

        override fun accelerationIdentity(): String = acceleration

        override fun estimateWarmMtmdPromptTokens(
            sessionKey: String,
            request: InferenceRequest
        ): Result<Int> = runCatching {
            require(sessionKey in residentMtmdKeys)
            require(request.attachments.isNotEmpty())
            promptEstimateCalls += 1
            promptTokenEstimate
        }

        override fun probeProjector(
            projectorPath: String
        ): Result<Set<InferenceAttachmentKind>> =
            Result.success(setOf(InferenceAttachmentKind.IMAGE))

        override fun prepareWarmSession(
            sessionKey: String,
            modelPath: String,
            projectorPath: String,
            threads: Int
        ): Result<Unit> = runCatching {
            require(sessionKey.isNotBlank())
            require(modelPath.isNotBlank())
            require(projectorPath.isNotBlank())
            require(threads > 0)
            prepareCalls += 1
            sessionKeys += sessionKey
            residentMtmdKeys.clear()
            residentMtmdKeys += sessionKey
        }

        override fun generate(
            modelPath: String,
            projectorPath: String,
            request: InferenceRequest,
            threads: Int,
            contextTokens: Int,
            cancellation: InferenceCancellationSignal,
            onToken: (String) -> Unit
        ): Result<MtmdNativeGeneration> = runCatching {
            coldGenerateCalls += 1
            onToken("cold")
            MtmdNativeGeneration("cold")
        }

        override fun generateWarm(
            sessionKey: String,
            modelPath: String,
            projectorPath: String,
            request: InferenceRequest,
            threads: Int,
            contextTokens: Int,
            cancellation: InferenceCancellationSignal,
            onToken: (String) -> Unit
        ): Result<MtmdNativeGeneration> = runCatching {
            cancellation.throwIfCancelled()
            warmGenerateCalls += 1
            sessionKeys += sessionKey
            if (failFirstWarmGeneration && warmGenerateCalls == 1) {
                error("simulated first warm generation failure")
            }
            residentMtmdKeys.clear()
            residentMtmdKeys += sessionKey
            onToken("warm ")
            onToken("ok")
            MtmdNativeGeneration(
                text = "warm ok",
                promptTokens = 9,
                outputTokens = 4,
                tokensPerSecond = 4.0,
                promptEvalTimeMs = 17L,
                generationTimeMs = 19L
            )
        }

        override fun releaseWarmSession(sessionKey: String): Result<Unit> = runCatching {
            releasedKeys += sessionKey
            residentMtmdKeys.remove(sessionKey)
        }

        override fun isWarmTextSessionResident(sessionKey: String): Boolean = false

        override fun isWarmMtmdSessionResident(sessionKey: String): Boolean =
            sessionKey in residentMtmdKeys

        fun evictNativeResidency() {
            residentMtmdKeys.clear()
        }
    }
}
