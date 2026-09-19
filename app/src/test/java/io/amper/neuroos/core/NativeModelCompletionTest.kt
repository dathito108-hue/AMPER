package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class NativeModelCompletionTest {
    private val reasoning = TitanCapabilities.REASONING
    private val planning = TitanCapabilities.PLANNING
    private val vision = TitanCapabilities.VISION

    @Test
    fun runtimePromotionKeepsVisionLatentUntilAdapterAdmission() {
        val fixture = fixture("latent-vision")
        val activation = fixture.promotion.activate(
            fixture.checkpointId,
            fixture.modelSource
        ).getOrThrow()

        assertTrue(reasoning in activation.installedModel.descriptor.capabilities)
        assertTrue(planning in activation.installedModel.descriptor.capabilities)
        assertFalse(vision in activation.installedModel.descriptor.capabilities)
        assertNull(fixture.projectors.get(activation.modelId))
    }

    @Test
    fun admittedProjectorUnlocksVisionAndDeactivationReturnsToBaseCapabilities() {
        val fixture = fixture("vision-admission")
        val activation = fixture.promotion.activate(
            fixture.checkpointId,
            fixture.modelSource
        ).getOrThrow()
        val adapterBytes = GgufTestFixtures.validArtifact(
            payload = "vision-projector".toByteArray()
        )
        val adapterSource = TestDescriptorBoundSource(
            bytes = adapterBytes,
            displayName = "vision-mmproj.gguf",
            locator = "memory://vision-mmproj.gguf"
        )

        val adapter = fixture.adapters.activate(
            checkpointId = fixture.checkpointId,
            source = adapterSource,
            kinds = setOf(InferenceAttachmentKind.IMAGE),
            evaluation = NativeMultimodalAdapterEvaluation(
                checkpointId = fixture.checkpointId,
                projectorSha256 = sha256(adapterBytes),
                evaluatedKinds = setOf(InferenceAttachmentKind.IMAGE),
                passRate = 0.97,
                samples = 64
            )
        ).getOrThrow()

        assertEquals(NativeMultimodalAdapterStatus.ACTIVE, adapter.status)
        assertEquals(setOf(vision), adapter.enabledCapabilities)
        assertTrue(
            vision in requireNotNull(
                fixture.catalog.get(activation.modelId)
            ).descriptor.capabilities
        )
        assertEquals(
            setOf(InferenceAttachmentKind.IMAGE),
            requireNotNull(fixture.projectors.get(activation.modelId)).expectedKinds
        )
        assertFalse(adapter.authorityBearing)

        val inactive = fixture.adapters.deactivate(adapter).getOrThrow()
        assertEquals(NativeMultimodalAdapterStatus.INACTIVE, inactive.status)
        assertFalse(
            vision in requireNotNull(
                fixture.catalog.get(activation.modelId)
            ).descriptor.capabilities
        )
        assertNull(fixture.projectors.get(activation.modelId))
    }

    @Test
    fun weakAdapterEvidenceCannotUnlockVision() {
        val fixture = fixture("weak-adapter")
        val activation = fixture.promotion.activate(
            fixture.checkpointId,
            fixture.modelSource
        ).getOrThrow()
        val weakBytes = GgufTestFixtures.validArtifact(
            payload = "weak-projector".toByteArray()
        )
        val source = TestDescriptorBoundSource(
            bytes = weakBytes,
            displayName = "weak-mmproj.gguf",
            locator = "memory://weak-mmproj.gguf"
        )

        val result = fixture.adapters.activate(
            checkpointId = fixture.checkpointId,
            source = source,
            kinds = setOf(InferenceAttachmentKind.IMAGE),
            evaluation = NativeMultimodalAdapterEvaluation(
                checkpointId = fixture.checkpointId,
                projectorSha256 = sha256(weakBytes),
                evaluatedKinds = setOf(InferenceAttachmentKind.IMAGE),
                passRate = 0.80,
                samples = 64
            )
        )

        assertTrue(result.isFailure)
        assertFalse(
            vision in requireNotNull(
                fixture.catalog.get(activation.modelId)
            ).descriptor.capabilities
        )
        assertNull(fixture.projectors.get(activation.modelId))
    }

    @Test
    fun adapterCannotUnlockCapabilityOutsideNativeContract() {
        val fixture = fixture(
            suffix = "contract-bound",
            contractCapabilities = setOf(reasoning, planning)
        )
        fixture.promotion.activate(
            fixture.checkpointId,
            fixture.modelSource
        ).getOrThrow()
        val forbiddenBytes = GgufTestFixtures.validArtifact(
            payload = "forbidden-vision".toByteArray()
        )
        val source = TestDescriptorBoundSource(
            bytes = forbiddenBytes,
            displayName = "forbidden-mmproj.gguf",
            locator = "memory://forbidden-mmproj.gguf"
        )

        val result = fixture.adapters.activate(
            checkpointId = fixture.checkpointId,
            source = source,
            kinds = setOf(InferenceAttachmentKind.IMAGE),
            evaluation = NativeMultimodalAdapterEvaluation(
                checkpointId = fixture.checkpointId,
                projectorSha256 = sha256(forbiddenBytes),
                evaluatedKinds = setOf(InferenceAttachmentKind.IMAGE),
                passRate = 1.0,
                samples = 64
            )
        )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("exceed") == true
        )
    }

    @Test
    fun adapterEvidenceFromDifferentProjectorCannotBeReused() {
        val fixture = fixture("evidence-binding")
        val activation = fixture.promotion.activate(
            fixture.checkpointId,
            fixture.modelSource
        ).getOrThrow()
        val actualBytes = GgufTestFixtures.validArtifact(
            payload = "actual-projector".toByteArray()
        )
        val source = TestDescriptorBoundSource(
            bytes = actualBytes,
            displayName = "actual-mmproj.gguf",
            locator = "memory://actual-mmproj.gguf"
        )

        val result = fixture.adapters.activate(
            checkpointId = fixture.checkpointId,
            source = source,
            kinds = setOf(InferenceAttachmentKind.IMAGE),
            evaluation = NativeMultimodalAdapterEvaluation(
                checkpointId = fixture.checkpointId,
                projectorSha256 = "7".repeat(64),
                evaluatedKinds = setOf(InferenceAttachmentKind.IMAGE),
                passRate = 1.0,
                samples = 64
            )
        )

        assertTrue(result.isFailure)
        assertFalse(
            vision in requireNotNull(
                fixture.catalog.get(activation.modelId)
            ).descriptor.capabilities
        )
        assertNull(fixture.projectors.get(activation.modelId))
    }

    @Test
    fun manualCapabilityReclassificationCannotBypassNativeAdmission() {
        val descriptor = ModelDescriptor(
            id = ModelId("amper-native-manual-bypass"),
            format = "gguf",
            capabilities = setOf(reasoning),
            local = true
        )
        val installed = installed(
            descriptor = descriptor,
            locator = "memory://native-manual.gguf"
        )
        val catalog = InMemoryInstalledModelCatalog().also { it.put(installed) }
        val registry = InMemoryModelRegistry().also { it.register(descriptor) }
        val service = InstalledModelCapabilityService(catalog, registry)

        val result = service.reclassify(
            descriptor.id,
            ModelCapabilityProfile(vision = true)
        )

        assertTrue(result.isFailure)
        assertEquals(
            setOf(reasoning),
            requireNotNull(catalog.get(descriptor.id)).descriptor.capabilities
        )
        assertEquals(
            setOf(reasoning),
            requireNotNull(registry.route(setOf(reasoning))).capabilities
        )
    }

    @Test
    fun restartReconciliationRemovesStaleVisionWhenProjectorIsMissing() {
        val descriptor = ModelDescriptor(
            id = ModelId("amper-native-stale-vision"),
            format = "gguf",
            capabilities = setOf(reasoning, vision),
            local = true
        )
        val installed = installed(
            descriptor = descriptor,
            locator = "memory://stale-vision.gguf"
        )
        val catalog = InMemoryInstalledModelCatalog().also { it.put(installed) }
        val registry = InMemoryModelRegistry().also { it.register(descriptor) }

        val changed = NativeRuntimeCapabilityReconciler(
            catalog = catalog,
            registry = registry,
            projectors = InMemoryMultimodalProjectorCatalog()
        ).reconcile()

        assertEquals(1, changed)
        assertEquals(
            setOf(reasoning),
            requireNotNull(catalog.get(descriptor.id)).descriptor.capabilities
        )
        assertNull(registry.route(setOf(reasoning, vision)))
        assertEquals(descriptor.id, registry.route(setOf(reasoning))?.id)
    }

    @Test
    fun explicitUserPreferenceOutranksNativeSoftPreference() {
        val recording = RecordingInference()
        val nativeId = ModelId("amper-native-soft")
        val userId = ModelId("user-selected")
        val port = PreferredModelInferencePort(
            delegate = NativeModelPreferenceInferencePort(
                delegate = recording,
                preferredNativeModel = { nativeId }
            ),
            initialPreferredModelId = userId
        )

        port.infer(InferenceRequest(prompt = "hello")).getOrThrow()

        val request = recording.requests.single()
        assertEquals(userId, request.userPreferredModelId)
        assertNull(request.preferredModelId)
        assertEquals(userId, request.effectivePreferredModelId())
    }

    @Test
    fun existingContinuityHintOutranksNativeSoftPreference() {
        val recording = RecordingInference()
        val continuity = ModelId("continuity-model")
        val port = NativeModelPreferenceInferencePort(
            delegate = recording,
            preferredNativeModel = { ModelId("amper-native-soft") }
        )

        port.infer(
            InferenceRequest(
                prompt = "continue",
                preferredModelId = continuity
            )
        ).getOrThrow()

        val request = recording.requests.single()
        assertEquals(continuity, request.preferredModelId)
        assertNull(request.userPreferredModelId)
    }

    @Test
    fun titanFallsBackWhenNativePreferenceCannotSatisfyRequiredCapability() {
        val nativeId = ModelId("amper-native-fallback")
        val fallbackId = ModelId("user-code-specialist")
        val nativeDescriptor = ModelDescriptor(
            id = nativeId,
            format = "gguf",
            capabilities = setOf(reasoning),
            local = true
        )
        val fallbackDescriptor = ModelDescriptor(
            id = fallbackId,
            format = "gguf",
            capabilities = setOf(reasoning, TitanCapabilities.CODE_GENERATION),
            local = true
        )
        val nativeModel = installed(nativeDescriptor, "memory://native.gguf")
        val fallbackModel = installed(fallbackDescriptor, "memory://fallback.gguf")
        val models = InMemoryModelRegistry().also {
            it.register(nativeDescriptor)
            it.register(fallbackDescriptor)
        }
        val catalog = InMemoryInstalledModelCatalog().also {
            it.put(nativeModel)
            it.put(fallbackModel)
        }
        val sources = mapOf(
            nativeModel.locator to StaticSource(nativeModel.locator),
            fallbackModel.locator to StaticSource(fallbackModel.locator)
        )
        val backends = InferenceBackendRegistry().apply {
            register(object : InferenceBackend {
                override val id: String = "fallback-test"
                override fun supports(model: InstalledModel): Boolean = true
                override fun infer(
                    model: InstalledModel,
                    source: ModelArtifactSource,
                    request: InferenceRequest
                ): Result<InferenceResponse> = Result.success(
                    InferenceResponse(
                        modelId = model.descriptor.id,
                        backendId = id,
                        text = model.descriptor.id.value
                    )
                )
            })
        }
        val titan = TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource? =
                    sources[model.locator]
            },
            backends = backends
        )
        val port = NativeModelPreferenceInferencePort(
            delegate = TitanInferencePort(titan),
            preferredNativeModel = { nativeId }
        )

        val response = port.infer(
            InferenceRequest(
                prompt = "write Kotlin code",
                requiredCapabilities = setOf(
                    reasoning,
                    TitanCapabilities.CODE_GENERATION
                )
            )
        ).getOrThrow()

        assertEquals(fallbackId, response.modelId)
    }

    @Test
    fun selectorChoosesNewestInstalledAmperNativeModelOnly() {
        val catalog = InMemoryInstalledModelCatalog()
        catalog.put(
            installed(
                ModelDescriptor(
                    ModelId("user-model"),
                    "gguf",
                    setOf(reasoning),
                    true
                ),
                "memory://user.gguf",
                installedAt = 100L
            )
        )
        catalog.put(
            installed(
                ModelDescriptor(
                    ModelId("amper-native-old"),
                    "gguf",
                    setOf(reasoning),
                    true
                ),
                "memory://old.gguf",
                installedAt = 200L
            )
        )
        catalog.put(
            installed(
                ModelDescriptor(
                    ModelId("amper-native-new"),
                    "gguf",
                    setOf(reasoning),
                    true
                ),
                "memory://new.gguf",
                installedAt = 300L
            )
        )

        assertEquals(
            ModelId("amper-native-new"),
            NativeInstalledModelSelector.preferredModelId(catalog)
        )
    }

    private data class Fixture(
        val checkpointId: NativeCheckpointId,
        val modelSource: TestDescriptorBoundSource,
        val catalog: InMemoryInstalledModelCatalog,
        val registry: InMemoryModelRegistry,
        val projectors: InMemoryMultimodalProjectorCatalog,
        val foundation: NativeModelFoundation,
        val pipeline: NativeTrainingPipeline,
        val promotion: NativeCheckpointRuntimePromotionService,
        val adapters: NativeMultimodalAdapterActivationService
    )

    private fun fixture(
        suffix: String,
        contractCapabilities: Set<CapabilityId> = setOf(
            reasoning,
            planning,
            vision
        )
    ): Fixture {
        val memory = InMemoryMemoryOs()
        var now = 10_000L
        val foundation = MemoryBackedNativeModelFoundation(memory, clock = { now++ })
        val pipeline = MemoryBackedNativeTrainingPipeline(
            memory = memory,
            foundation = foundation,
            clock = { now++ }
        )
        val contract = AmperNativeModelContract(
            id = NativeModelContractId("contract-" + suffix),
            familyVersion = 1,
            parameterCount = 120_000_000L,
            layerCount = 24,
            hiddenSize = 1024,
            maxContextTokens = 8192,
            capabilities = contractCapabilities,
            ownedByAmper = true
        )
        val curriculumCapabilities = contractCapabilities
        val curriculum = NativeCurriculumManifest(
            id = NativeCurriculumId("curriculum-" + suffix),
            stages = listOf(
                NativeCurriculumStage(
                    index = 1,
                    name = "native-capabilities",
                    capabilities = curriculumCapabilities,
                    minExamples = 128,
                    weight = 1.0
                )
            )
        )
        val recipe = NativeTrainingRecipe(
            optimizer = "adamw",
            precision = "bf16",
            maxSequenceTokens = 4096,
            learningRate = 0.0002,
            curriculumDigest = curriculum.canonicalDigest
        )
        val shard = NativeDatasetShardManifest(
            id = NativeDatasetShardId("dataset-" + suffix),
            sha256 = "a".repeat(64),
            sourceLabel = "user-owned completion fixture",
            rights = NativeDatasetRights.USER_OWNED,
            exampleCount = 512,
            byteCount = 65_536,
            targetCapabilities = curriculumCapabilities,
            createdAtEpochMs = now++
        )
        foundation.putContract(contract)
        foundation.putCurriculum(curriculum)
        foundation.putDatasetShard(shard)

        val teacher = NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId("teacher-" + suffix),
            modelId = ModelId("teacher-model-" + suffix),
            artifactSha256 = "9".repeat(64),
            sourceLabel = "teacher",
            rights = NativeTeacherRights.USER_OWNED,
            capabilities = curriculumCapabilities,
            local = true,
            createdAtEpochMs = now++
        )
        pipeline.putTeacher(teacher)

        val shardIds = listOf(shard.id)
        val manifest = NativeDistillationManifest(
            id = NativeDistillationManifestId("manifest-" + suffix),
            teacherSnapshotIds = listOf(teacher.id),
            studentContractId = contract.id,
            studentContractDigest = contract.canonicalDigest,
            parentCheckpointId = null,
            datasetShardIds = shardIds,
            datasetSnapshotDigest = foundation.datasetSnapshotDigest(shardIds),
            curriculumId = curriculum.id,
            curriculumDigest = curriculum.canonicalDigest,
            recipe = recipe,
            target = NativeMobileTargetProfile(
                outputFormat = "gguf",
                quantization = "Q4_K_M",
                maxRuntimeMemoryMb = 2048,
                contextTokens = 4096,
                androidArm64 = true
            ),
            teacherTemperature = 2.0,
            teacherLossWeight = 0.65,
            createdAtEpochMs = now++
        )
        pipeline.putManifest(manifest)

        val modelBytes = GgufTestFixtures.validArtifact(
            payload = ("native-model-" + suffix).toByteArray()
        )
        val modelDigest = sha256(modelBytes)
        val checkpointId = NativeCheckpointId("checkpoint-" + suffix)
        val run = pipeline.prepareRun(
            NativeTrainingRunId("run-" + suffix),
            manifest.id,
            checkpointId
        )
        val finished = pipeline.execute(run.id) { request ->
            Result.success(
                NativeTrainingArtifact(
                    runId = request.runId,
                    manifestDigest = request.manifest.canonicalDigest,
                    trainingBackendId = "completion-fixture-trainer",
                    weightArtifactSha256 = modelDigest,
                    outputFormat = "gguf",
                    quantization = "Q4_K_M",
                    artifactBytes = modelBytes.size.toLong(),
                    examplesSeen = 512,
                    completedSteps = 128,
                    finalLoss = 0.40
                )
            )
        }
        assertEquals(NativeTrainingRunStatus.SUCCEEDED, finished.status)
        pipeline.recordEvaluation(
            checkpointId,
            NativeCheckpointEvaluation(
                planningProtocolPassRate = 1.0,
                toolContractPassRate = 1.0,
                regressionPassRate = 1.0,
                heldoutGeneralizationPassRate = 0.95,
                planningSamples = 64,
                toolContractSamples = 64,
                regressionSamples = 64,
                heldoutGeneralizationSamples = 64
            )
        )

        val catalog = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        val projectors = InMemoryMultimodalProjectorCatalog()
        val promotion = NativeCheckpointRuntimePromotionService(
            foundation = foundation,
            training = pipeline,
            catalog = catalog,
            registry = registry,
            clock = { now++ }
        )
        val adapters = NativeMultimodalAdapterActivationService(
            foundation = foundation,
            promotion = promotion,
            catalog = catalog,
            registry = registry,
            projectors = projectors,
            clock = { now++ }
        )
        return Fixture(
            checkpointId = checkpointId,
            modelSource = TestDescriptorBoundSource(
                modelBytes,
                "native-" + suffix + ".gguf",
                "memory://native-" + suffix + ".gguf"
            ),
            catalog = catalog,
            registry = registry,
            projectors = projectors,
            foundation = foundation,
            pipeline = pipeline,
            promotion = promotion,
            adapters = adapters
        )
    }

    private class RecordingInference : CognitiveInferencePort {
        val requests = mutableListOf<InferenceRequest>()
        override fun infer(request: InferenceRequest): Result<InferenceResponse> {
            requests += request
            return Result.success(
                InferenceResponse(
                    modelId = request.effectivePreferredModelId() ?: ModelId("auto"),
                    backendId = "recording",
                    text = "ok"
                )
            )
        }
    }

    private class TestDescriptorBoundSource(
        private val bytes: ByteArray,
        override val displayName: String,
        override val locator: String
    ) : DescriptorBoundNativeModelPathSource {
        override val lengthBytes: Long = bytes.size.toLong()
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
        override fun <T> withNativePath(block: (String) -> T): T =
            error("native path not required by completion unit test")
    }

    private class StaticSource(
        override val locator: String
    ) : ModelArtifactSource {
        override val displayName: String = locator.substringAfterLast('/')
        override val lengthBytes: Long = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private fun installed(
        descriptor: ModelDescriptor,
        locator: String,
        installedAt: Long = 1L
    ): InstalledModel = InstalledModel(
        descriptor = descriptor,
        displayName = descriptor.id.value + ".gguf",
        locator = locator,
        lengthBytes = 128L,
        sha256 = "b".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL,
        installedAtEpochMs = installedAt
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}