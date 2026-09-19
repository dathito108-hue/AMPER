package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class NativeModelRuntimePromotionTest {
    private val reasoning = TitanCapabilities.REASONING
    private val planning = TitanCapabilities.PLANNING

    @Test
    fun promotableCheckpointWithExactGgufBecomesLiveTitanRoute() {
        val fixture = fixture("live-route")
        val catalog = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        val service = NativeCheckpointRuntimePromotionService(
            foundation = fixture.foundation,
            training = fixture.pipeline,
            catalog = catalog,
            registry = registry,
            clock = { 50_000L }
        )

        val activation = service.activate(
            checkpointId = fixture.checkpointId,
            source = fixture.source
        ).getOrThrow()

        assertTrue(activation.liveRegistered)
        assertFalse(activation.authorityBearing)
        assertEquals(fixture.sha256, activation.installedModel.sha256)
        assertEquals(
            fixture.contract.capabilities,
            activation.installedModel.descriptor.capabilities
        )
        assertEquals(
            activation.modelId,
            registry.route(setOf(reasoning))?.id
        )
        ModelArtifactIdentityVerifier()
            .verifySource(activation.installedModel, fixture.source)
            .getOrThrow()

        val backends = InferenceBackendRegistry().apply {
            register(object : InferenceBackend {
                override val id: String = "native-promotion-test-backend"
                override fun supports(model: InstalledModel): Boolean =
                    model.descriptor.format.equals("gguf", ignoreCase = true)

                override fun infer(
                    model: InstalledModel,
                    source: ModelArtifactSource,
                    request: InferenceRequest
                ): Result<InferenceResponse> = Result.success(
                    InferenceResponse(
                        modelId = model.descriptor.id,
                        backendId = id,
                        text = "native:" + request.prompt
                    )
                )
            })
        }
        val resolver = object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource? =
                fixture.source.takeIf { model.locator == it.locator }
        }
        val titan = TitanCortexRuntime(
            models = registry,
            catalog = catalog,
            artifacts = resolver,
            backends = backends
        )

        val response = titan.infer(
            InferenceRequest(
                prompt = "route through AMPER native checkpoint",
                requiredCapabilities = setOf(reasoning)
            )
        ).getOrThrow()

        assertEquals(activation.modelId, response.modelId)
        assertEquals("native-promotion-test-backend", response.backendId)
        assertEquals("native:route through AMPER native checkpoint", response.text)
    }

    @Test
    fun mismatchedGgufDigestCannotEnterCatalogOrRegistry() {
        val fixture = fixture("digest-mismatch")
        val catalog = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        val service = NativeCheckpointRuntimePromotionService(
            fixture.foundation,
            fixture.pipeline,
            catalog,
            registry
        )
        val otherBytes = GgufTestFixtures.validArtifact(
            payload = "different-runtime-weights".toByteArray()
        )
        val other = TestDescriptorBoundSource(
            bytes = otherBytes,
            displayName = "different.gguf",
            locator = "memory://different.gguf"
        )

        val result = service.activate(fixture.checkpointId, other)

        assertTrue(result.isFailure)
        val expectedId = service.expectedModelId(fixture.checkpointId)
        assertNull(catalog.get(expectedId))
        assertNull(registry.route(setOf(reasoning)))
    }

    @Test
    fun nonDescriptorBoundSourceIsRejectedEvenWhenBytesAreExact() {
        val fixture = fixture("descriptor-required")
        val catalog = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        val service = NativeCheckpointRuntimePromotionService(
            fixture.foundation,
            fixture.pipeline,
            catalog,
            registry
        )
        val unbound = ByteArrayModelArtifactSource(
            bytes = fixture.bytes,
            displayName = "unbound.gguf",
            locator = fixture.source.locator
        )

        val result = service.activate(fixture.checkpointId, unbound)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("descriptor-bound") == true
        )
        assertTrue(catalog.list().isEmpty())
    }

    @Test
    fun registryFailureRollsBackCatalogPublication() {
        val fixture = fixture("registry-rollback")
        val catalog = InMemoryInstalledModelCatalog()
        val registry = ThrowingRegistry()
        val service = NativeCheckpointRuntimePromotionService(
            fixture.foundation,
            fixture.pipeline,
            catalog,
            registry
        )

        val result = service.activate(fixture.checkpointId, fixture.source)

        assertTrue(result.isFailure)
        assertTrue(catalog.list().isEmpty())
        assertTrue(registry.models.isEmpty())
    }

    @Test
    fun exactReactivationIsIdempotentAndKeepsDeterministicModelIdentity() {
        val fixture = fixture("idempotent")
        val catalog = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        var now = 60_000L
        val service = NativeCheckpointRuntimePromotionService(
            fixture.foundation,
            fixture.pipeline,
            catalog,
            registry,
            clock = { now++ }
        )

        val first = service.activate(fixture.checkpointId, fixture.source).getOrThrow()
        val second = service.activate(fixture.checkpointId, fixture.source).getOrThrow()

        assertEquals(first.modelId, second.modelId)
        assertEquals(first.installedModel, second.installedModel)
        assertEquals(1, catalog.list().size)
        assertEquals(first.modelId, service.expectedModelId(fixture.checkpointId))
    }

    @Test
    fun rollbackUnloadsAndRemovesLiveCatalogAndRegistryState() {
        val fixture = fixture("runtime-rollback")
        val catalog = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        val unloaded = mutableListOf<ModelId>()
        var now = 70_000L
        val service = NativeCheckpointRuntimePromotionService(
            foundation = fixture.foundation,
            training = fixture.pipeline,
            catalog = catalog,
            registry = registry,
            unloadRuntime = { id ->
                unloaded += id
                Result.success(Unit)
            },
            clock = { now++ }
        )
        val active = service.activate(
            fixture.checkpointId,
            fixture.source
        ).getOrThrow()

        val rolledBack = service.rollback(active).getOrThrow()

        assertEquals(NativeRuntimeActivationStatus.ROLLED_BACK, rolledBack.status)
        assertFalse(rolledBack.liveRegistered)
        assertEquals(listOf(active.modelId), unloaded)
        assertNull(catalog.get(active.modelId))
        assertNull(registry.route(setOf(reasoning)))
    }

    @Test
    fun nonPromotableCheckpointCannotBeActivatedEvenWithExactBytes() {
        val bytes = GgufTestFixtures.validArtifact(
            payload = "non-promotable".toByteArray()
        )
        val fixture = fixture(
            suffix = "non-promotable",
            bytes = bytes,
            evaluation = passingEvaluation().copy(
                heldoutGeneralizationPassRate = 0.50
            )
        )
        val catalog = InMemoryInstalledModelCatalog()
        val registry = InMemoryModelRegistry()
        val service = NativeCheckpointRuntimePromotionService(
            fixture.foundation,
            fixture.pipeline,
            catalog,
            registry
        )

        val result = service.activate(fixture.checkpointId, fixture.source)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("not objectively promotable") == true
        )
        assertTrue(catalog.list().isEmpty())
    }

    private data class Fixture(
        val bytes: ByteArray,
        val sha256: String,
        val source: TestDescriptorBoundSource,
        val contract: AmperNativeModelContract,
        val foundation: NativeModelFoundation,
        val pipeline: NativeTrainingPipeline,
        val checkpointId: NativeCheckpointId
    )

    private fun fixture(
        suffix: String,
        bytes: ByteArray = GgufTestFixtures.validArtifact(
            payload = ("amper-native-" + suffix).toByteArray()
        ),
        evaluation: NativeCheckpointEvaluation = passingEvaluation()
    ): Fixture {
        val memory = InMemoryMemoryOs()
        var now = 1_000L
        val foundation = MemoryBackedNativeModelFoundation(memory, clock = { now++ })
        val pipeline = MemoryBackedNativeTrainingPipeline(
            memory,
            foundation,
            clock = { now++ }
        )
        val contract = AmperNativeModelContract(
            id = NativeModelContractId("native-contract-" + suffix),
            familyVersion = 1,
            parameterCount = 120_000_000L,
            layerCount = 24,
            hiddenSize = 1024,
            maxContextTokens = 8192,
            capabilities = setOf(reasoning, planning),
            ownedByAmper = true
        )
        val curriculum = NativeCurriculumManifest(
            id = NativeCurriculumId("curriculum-" + suffix),
            stages = listOf(
                NativeCurriculumStage(
                    index = 1,
                    name = "reasoning",
                    capabilities = setOf(reasoning),
                    minExamples = 128,
                    weight = 0.45
                ),
                NativeCurriculumStage(
                    index = 2,
                    name = "planning",
                    capabilities = setOf(reasoning, planning),
                    minExamples = 128,
                    weight = 0.55
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
            sourceLabel = "user-owned native runtime fixture",
            rights = NativeDatasetRights.USER_OWNED,
            exampleCount = 512,
            byteCount = 65_536,
            targetCapabilities = setOf(reasoning, planning),
            createdAtEpochMs = now++
        )
        foundation.putContract(contract)
        foundation.putCurriculum(curriculum)
        foundation.putDatasetShard(shard)

        val teacher = NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId("teacher-" + suffix),
            modelId = ModelId("teacher-model-" + suffix),
            artifactSha256 = "9".repeat(64),
            sourceLabel = "user-owned teacher",
            rights = NativeTeacherRights.USER_OWNED,
            capabilities = setOf(reasoning, planning),
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

        val checkpointId = NativeCheckpointId("checkpoint-" + suffix)
        val run = pipeline.prepareRun(
            id = NativeTrainingRunId("run-" + suffix),
            manifestId = manifest.id,
            outputCheckpointId = checkpointId
        )
        val digest = sha256(bytes)
        val finished = pipeline.execute(run.id) { request ->
            Result.success(
                NativeTrainingArtifact(
                    runId = request.runId,
                    manifestDigest = request.manifest.canonicalDigest,
                    trainingBackendId = "fixture-trainer",
                    weightArtifactSha256 = digest,
                    outputFormat = "gguf",
                    quantization = "Q4_K_M",
                    artifactBytes = bytes.size.toLong(),
                    examplesSeen = 512,
                    completedSteps = 128,
                    finalLoss = 0.40
                )
            )
        }
        assertEquals(NativeTrainingRunStatus.SUCCEEDED, finished.status)
        pipeline.recordEvaluation(checkpointId, evaluation)

        val source = TestDescriptorBoundSource(
            bytes = bytes,
            displayName = "amper-native-" + suffix + ".gguf",
            locator = "memory://amper-native-" + suffix + ".gguf"
        )
        return Fixture(
            bytes = bytes,
            sha256 = digest,
            source = source,
            contract = contract,
            foundation = foundation,
            pipeline = pipeline,
            checkpointId = checkpointId
        )
    }

    private fun passingEvaluation(): NativeCheckpointEvaluation =
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

    private class TestDescriptorBoundSource(
        private val bytes: ByteArray,
        override val displayName: String,
        override val locator: String
    ) : DescriptorBoundNativeModelPathSource {
        override val lengthBytes: Long = bytes.size.toLong()
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
        override fun <T> withNativePath(block: (String) -> T): T =
            error("native path is not needed by this JVM promotion test")
    }

    private class ThrowingRegistry : MutableModelRegistry {
        val models = linkedMapOf<ModelId, ModelDescriptor>()

        override fun register(model: ModelDescriptor) {
            throw IllegalStateException("registry rejected model")
        }

        override fun unregister(id: ModelId): Boolean = models.remove(id) != null

        override fun candidates(required: Set<CapabilityId>): List<ModelDescriptor> =
            models.values.filter { it.capabilities.containsAll(required) }

        override fun route(required: Set<CapabilityId>): ModelDescriptor? =
            candidates(required).firstOrNull()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
