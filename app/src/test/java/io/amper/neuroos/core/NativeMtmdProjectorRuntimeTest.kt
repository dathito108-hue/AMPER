package io.amper.neuroos.core

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMtmdProjectorRuntimeTest {
    @Test
    fun projectorCatalogSurvivesReloadAndRemoval() {
        val root = Files.createTempDirectory("amper-mmproj-catalog").toFile()
        try {
            val file = File(root, "multimodal-projectors.catalog")
            val projector = InstalledMultimodalProjector(
                modelId = ModelId("vision-model"),
                displayName = "mmproj-model-f16.gguf",
                locator = "content://projectors/mmproj.gguf",
                lengthBytes = 2048L,
                sha256 = "cd".repeat(32),
                ggufVersion = 3L,
                tensorCount = 12UL,
                metadataKeyValueCount = 5UL,
                expectedKinds = setOf(
                    InferenceAttachmentKind.IMAGE,
                    InferenceAttachmentKind.AUDIO
                ),
                installedAtEpochMs = 1234L
            )

            FileMultimodalProjectorCatalog(file).put(projector)
            val restored = FileMultimodalProjectorCatalog(file)
            assertEquals(projector, restored.get(ModelId("vision-model")))
            assertTrue(restored.remove(ModelId("vision-model")))
            assertTrue(FileMultimodalProjectorCatalog(file).list().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun projectorInstallCannotExceedExplicitModelProfile() {
        val root = Files.createTempDirectory("amper-mmproj-profile").toFile()
        try {
            val projectorFile = File(root, "mmproj.gguf").also {
                it.writeBytes(
                    GgufTestFixtures.validArtifact(
                        tensorCount = 1UL,
                        metadataCount = 1UL
                    )
                )
            }
            val projectorSource = TestDescriptorSource(
                projectorFile,
                "content://projectors/mmproj.gguf"
            )
            val model = installedModel(
                id = "vision-model",
                source = TestDescriptorSource(
                    File(root, "model.gguf").also {
                        it.writeBytes(
                            GgufTestFixtures.validArtifact(
                                tensorCount = 1UL,
                                metadataCount = 1UL
                            )
                        )
                    },
                    "content://models/vision.gguf"
                ),
                capabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.VISION
                )
            )
            val catalog = InMemoryMultimodalProjectorCatalog()
            val service = MultimodalProjectorInstallService(GgufInspector(), catalog)

            val failure = service.install(
                model = model,
                source = projectorSource,
                expectedKinds = setOf(InferenceAttachmentKind.AUDIO)
            )

            assertTrue(failure.isFailure)
            assertTrue(catalog.list().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun titanStreamsImageThroughPairedNativeMtmdBackend() {
        val root = Files.createTempDirectory("amper-mtmd-runtime").toFile()
        try {
            val modelSource = TestDescriptorSource(
                File(root, "model.gguf").also {
                    it.writeBytes(
                        GgufTestFixtures.validArtifact(
                            tensorCount = 1UL,
                            metadataCount = 1UL
                        )
                    )
                },
                "content://models/vision.gguf"
            )
            val projectorSource = TestDescriptorSource(
                File(root, "mmproj.gguf").also {
                    it.writeBytes(
                        GgufTestFixtures.validArtifact(
                            tensorCount = 1UL,
                            metadataCount = 1UL
                        )
                    )
                },
                "content://projectors/vision-mmproj.gguf"
            )
            val model = installedModel(
                id = "vision-model",
                source = modelSource,
                capabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.VISION
                )
            )
            val projector = installedProjector(
                modelId = model.descriptor.id,
                source = projectorSource,
                expectedKinds = setOf(InferenceAttachmentKind.IMAGE)
            )
            val projectorCatalog = InMemoryMultimodalProjectorCatalog().also {
                it.put(projector)
            }
            val engine = FakeMtmdEngine(
                probedKinds = setOf(InferenceAttachmentKind.IMAGE)
            )
            val backend = MtmdNativeInferenceBackend(
                engine = engine,
                projectors = projectorCatalog,
                projectorArtifacts = object : MultimodalProjectorArtifactResolver {
                    override fun resolve(
                        projector: InstalledMultimodalProjector
                    ): ModelArtifactSource = projectorSource
                }
            )
            val modelCatalog = InMemoryInstalledModelCatalog().also { it.put(model) }
            val modelRegistry = InMemoryModelRegistry().also {
                it.register(model.descriptor)
            }
            val backends = InferenceBackendRegistry().also { it.register(backend) }
            val titan = TitanCortexRuntime(
                models = modelRegistry,
                catalog = modelCatalog,
                artifacts = object : ModelArtifactResolver {
                    override fun resolve(model: InstalledModel): ModelArtifactSource = modelSource
                },
                backends = backends
            )
            val attachment = InferenceAttachment.fromBytes(
                kind = InferenceAttachmentKind.IMAGE,
                mediaType = "image/png",
                displayName = "frame.png",
                bytes = byteArrayOf(1, 2, 3, 4, 5)
            )
            val chunks = mutableListOf<InferenceChunk>()

            val response = titan.inferStream(
                InferenceRequest(
                    prompt = "Describe this image.",
                    requiredCapabilities = setOf(
                        TitanCapabilities.REASONING,
                        TitanCapabilities.VISION
                    ),
                    attachments = listOf(attachment)
                ),
                InferenceCancellationSignal(),
                chunks::add
            ).getOrThrow()

            assertEquals("vision ok", response.text)
            assertEquals("llama.cpp-mtmd-native", response.backendId)
            assertEquals(model.descriptor.id, response.modelId)
            assertEquals(
                setOf(TitanCapabilities.REASONING, TitanCapabilities.VISION),
                response.selectedCapabilities
            )
            assertEquals(listOf("vision ", "ok", ""), chunks.map { it.text })
            assertTrue(chunks.last().finished)
            assertEquals(1, engine.generateCalls)
            assertEquals(
                listOf(InferenceAttachmentKind.IMAGE),
                engine.lastRequest?.attachments?.map { it.kind }
            )
            assertEquals(modelSource.file.absolutePath, engine.lastModelPath)
            assertEquals(projectorSource.file.absolutePath, engine.lastProjectorPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun titanStreamsAudioThroughPairedNativeMtmdBackend() {
        val root = Files.createTempDirectory("amper-mtmd-audio-runtime").toFile()
        try {
            val modelSource = TestDescriptorSource(
                File(root, "audio-model.gguf").also {
                    it.writeBytes(
                        GgufTestFixtures.validArtifact(
                            tensorCount = 1UL,
                            metadataCount = 1UL
                        )
                    )
                },
                "content://models/audio.gguf"
            )
            val projectorSource = TestDescriptorSource(
                File(root, "audio-mmproj.gguf").also {
                    it.writeBytes(
                        GgufTestFixtures.validArtifact(
                            tensorCount = 1UL,
                            metadataCount = 1UL
                        )
                    )
                },
                "content://projectors/audio-mmproj.gguf"
            )
            val model = installedModel(
                id = "audio-model",
                source = modelSource,
                capabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.AUDIO_UNDERSTANDING
                )
            )
            val projector = installedProjector(
                modelId = model.descriptor.id,
                source = projectorSource,
                expectedKinds = setOf(InferenceAttachmentKind.AUDIO)
            )
            val engine = FakeMtmdEngine(
                probedKinds = setOf(InferenceAttachmentKind.AUDIO)
            )
            val backend = MtmdNativeInferenceBackend(
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
            val modelCatalog = InMemoryInstalledModelCatalog().also { it.put(model) }
            val modelRegistry = InMemoryModelRegistry().also {
                it.register(model.descriptor)
            }
            val backends = InferenceBackendRegistry().also { it.register(backend) }
            val titan = TitanCortexRuntime(
                models = modelRegistry,
                catalog = modelCatalog,
                artifacts = object : ModelArtifactResolver {
                    override fun resolve(model: InstalledModel): ModelArtifactSource = modelSource
                },
                backends = backends
            )
            val audio = Pcm16WaveEncoder.encodeMono(
                samples = ShortArray(1_600) { index ->
                    if (index % 2 == 0) 4_000 else -4_000
                },
                sampleRateHz = 16_000
            ).toAttachment("live.wav")
            val chunks = mutableListOf<InferenceChunk>()

            val response = titan.inferStream(
                InferenceRequest(
                    prompt = "What can you hear?",
                    requiredCapabilities = setOf(
                        TitanCapabilities.REASONING,
                        TitanCapabilities.AUDIO_UNDERSTANDING
                    ),
                    attachments = listOf(audio)
                ),
                InferenceCancellationSignal(),
                chunks::add
            ).getOrThrow()

            assertEquals("audio understood", response.text)
            assertEquals("llama.cpp-mtmd-native", response.backendId)
            assertEquals(
                setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.AUDIO_UNDERSTANDING
                ),
                response.selectedCapabilities
            )
            assertEquals(listOf("audio ", "understood", ""), chunks.map { it.text })
            assertTrue(chunks.last().finished)
            assertEquals(1, engine.generateCalls)
            assertEquals(
                listOf(InferenceAttachmentKind.AUDIO),
                engine.lastRequest?.attachments?.map { it.kind }
            )
            assertEquals("audio/wav", engine.lastRequest?.attachments?.single()?.mediaType)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun libmtmdProbeMismatchFailsBeforeNativeGeneration() {
        val root = Files.createTempDirectory("amper-mtmd-probe").toFile()
        try {
            val modelSource = TestDescriptorSource(
                File(root, "model.gguf").also {
                    it.writeBytes(GgufTestFixtures.validArtifact(tensorCount = 1UL, metadataCount = 1UL))
                },
                "content://models/vision.gguf"
            )
            val projectorSource = TestDescriptorSource(
                File(root, "mmproj.gguf").also {
                    it.writeBytes(GgufTestFixtures.validArtifact(tensorCount = 1UL, metadataCount = 1UL))
                },
                "content://projectors/vision-mmproj.gguf"
            )
            val model = installedModel(
                id = "vision-model",
                source = modelSource,
                capabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.VISION
                )
            )
            val projector = installedProjector(
                model.descriptor.id,
                projectorSource,
                setOf(InferenceAttachmentKind.IMAGE)
            )
            val engine = FakeMtmdEngine(probedKinds = emptySet())
            val backend = MtmdNativeInferenceBackend(
                engine = engine,
                projectors = InMemoryMultimodalProjectorCatalog().also { it.put(projector) },
                projectorArtifacts = object : MultimodalProjectorArtifactResolver {
                    override fun resolve(
                        projector: InstalledMultimodalProjector
                    ): ModelArtifactSource = projectorSource
                }
            )
            val image = InferenceAttachment.fromBytes(
                kind = InferenceAttachmentKind.IMAGE,
                mediaType = "image/jpeg",
                displayName = "frame.jpg",
                bytes = byteArrayOf(9, 8, 7)
            )

            val result = backend.inferStream(
                model = model,
                source = modelSource,
                request = InferenceRequest(
                    prompt = "Describe.",
                    requiredCapabilities = setOf(
                        TitanCapabilities.REASONING,
                        TitanCapabilities.VISION
                    ),
                    attachments = listOf(image)
                ),
                cancellation = InferenceCancellationSignal(),
                onChunk = { }
            )

            assertTrue(result.isFailure)
            assertEquals(0, engine.generateCalls)
            assertEquals(1, engine.probeCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeBackendIsIneligibleWithoutProjectorPairing() {
        val root = Files.createTempDirectory("amper-mtmd-no-projector").toFile()
        try {
            val modelSource = TestDescriptorSource(
                File(root, "model.gguf").also {
                    it.writeBytes(GgufTestFixtures.validArtifact(tensorCount = 1UL, metadataCount = 1UL))
                },
                "content://models/vision.gguf"
            )
            val model = installedModel(
                id = "vision-model",
                source = modelSource,
                capabilities = setOf(
                    TitanCapabilities.REASONING,
                    TitanCapabilities.VISION
                )
            )
            val backend = MtmdNativeInferenceBackend(
                engine = FakeMtmdEngine(setOf(InferenceAttachmentKind.IMAGE)),
                projectors = InMemoryMultimodalProjectorCatalog(),
                projectorArtifacts = object : MultimodalProjectorArtifactResolver {
                    override fun resolve(
                        projector: InstalledMultimodalProjector
                    ): ModelArtifactSource? = null
                }
            )

            assertFalse(backend.supports(model))
            assertTrue(backend.supportedAttachmentKinds(model).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun installedModel(
        id: String,
        source: TestDescriptorSource,
        capabilities: Set<CapabilityId>
    ): InstalledModel {
        val inspection = GgufInspector().inspect(source).getOrThrow()
        return InstalledModel(
            descriptor = ModelDescriptor(
                id = ModelId(id),
                format = "gguf",
                capabilities = capabilities,
                local = true
            ),
            displayName = inspection.displayName,
            locator = inspection.locator,
            lengthBytes = inspection.lengthBytes,
            sha256 = inspection.sha256,
            ggufVersion = inspection.header.version,
            tensorCount = inspection.header.tensorCount,
            metadataKeyValueCount = inspection.header.metadataKeyValueCount,
            installedAtEpochMs = 1L
        )
    }

    private fun installedProjector(
        modelId: ModelId,
        source: TestDescriptorSource,
        expectedKinds: Set<InferenceAttachmentKind>
    ): InstalledMultimodalProjector {
        val inspection = GgufInspector().inspect(source).getOrThrow()
        return InstalledMultimodalProjector(
            modelId = modelId,
            displayName = inspection.displayName,
            locator = inspection.locator,
            lengthBytes = inspection.lengthBytes,
            sha256 = inspection.sha256,
            ggufVersion = inspection.header.version,
            tensorCount = inspection.header.tensorCount,
            metadataKeyValueCount = inspection.header.metadataKeyValueCount,
            expectedKinds = expectedKinds,
            installedAtEpochMs = 2L
        )
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

    private class FakeMtmdEngine(
        private val probedKinds: Set<InferenceAttachmentKind>
    ) : MtmdNativeEngine {
        override val engineId: String = "fake-mtmd"
        var probeCalls: Int = 0
        var generateCalls: Int = 0
        var lastModelPath: String? = null
        var lastProjectorPath: String? = null
        var lastRequest: InferenceRequest? = null

        override fun health(): BackendHealth =
            BackendHealth(BackendState.READY, "fake MTMD ready")

        override fun probeProjector(
            projectorPath: String
        ): Result<Set<InferenceAttachmentKind>> = runCatching {
            probeCalls += 1
            probedKinds
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
            cancellation.throwIfCancelled()
            generateCalls += 1
            lastModelPath = modelPath
            lastProjectorPath = projectorPath
            lastRequest = request
            assertTrue(threads > 0)
            assertTrue(contextTokens >= 2_048)
            assertNotNull(request.attachments.singleOrNull())
            val audio = request.attachments.singleOrNull()?.kind ==
                InferenceAttachmentKind.AUDIO
            if (audio) {
                onToken("audio ")
                onToken("understood")
            } else {
                onToken("vision ")
                onToken("ok")
            }
            MtmdNativeGeneration(
                text = if (audio) "audio understood" else "vision ok",
                outputTokens = 2,
                generationTimeMs = 10,
                tokensPerSecond = 200.0
            )
        }
    }
}
