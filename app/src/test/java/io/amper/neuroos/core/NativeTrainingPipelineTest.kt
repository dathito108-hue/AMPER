package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTrainingPipelineTest {
    private val reasoning = TitanCapabilities.REASONING
    private val planning = TitanCapabilities.PLANNING

    @Test
    fun unknownTeacherRightsFailClosedAtManifestPublication() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val pipeline = MemoryBackedNativeTrainingPipeline(memory, foundation)
        val setup = publishFoundation(foundation)
        val teacher = teacher(
            id = "teacher-unknown",
            rights = NativeTeacherRights.UNKNOWN
        )
        pipeline.putTeacher(teacher)
        val manifest = manifest(
            foundation = foundation,
            setup = setup,
            teacherIds = listOf(teacher.id),
            id = "manifest-unknown-teacher"
        )

        val result = runCatching { pipeline.putManifest(manifest) }

        assertTrue(result.isFailure)
        assertNull(pipeline.getManifest(manifest.id))
    }

    @Test
    fun successfulTrainerResultPublishesImmutableCheckpointAndEvaluation() {
        val memory = InMemoryMemoryOs()
        var now = 10_000L
        val foundation = MemoryBackedNativeModelFoundation(memory, clock = { now++ })
        val pipeline = MemoryBackedNativeTrainingPipeline(memory, foundation, clock = { now++ })
        val setup = publishFoundation(foundation)
        val teacher = teacher("teacher-primary")
        pipeline.putTeacher(teacher)
        val manifest = manifest(
            foundation = foundation,
            setup = setup,
            teacherIds = listOf(teacher.id),
            id = "manifest-success"
        )
        pipeline.putManifest(manifest)
        val checkpointId = NativeCheckpointId("checkpoint-success")
        val run = pipeline.prepareRun(
            id = NativeTrainingRunId("run-success"),
            manifestId = manifest.id,
            outputCheckpointId = checkpointId
        )

        val finished = pipeline.execute(run.id) { request ->
            Result.success(
                artifact(
                    request = request,
                    sha = "c".repeat(64),
                    finalLoss = 0.42
                )
            )
        }

        assertEquals(NativeTrainingRunStatus.SUCCEEDED, finished.status)
        assertEquals("c".repeat(64), finished.weightArtifactSha256)
        val checkpoint = requireNotNull(foundation.getCheckpoint(checkpointId))
        assertEquals(manifest.studentContractId, checkpoint.contractId)
        assertEquals(manifest.datasetSnapshotDigest, checkpoint.datasetSnapshotDigest)
        assertEquals(manifest.recipe.canonicalDigest, checkpoint.recipeDigest)

        val evaluation = passingEvaluation(
            planningRate = 0.99,
            toolRate = 0.99,
            regressionRate = 0.97,
            generalizationRate = 0.90
        )
        val first = pipeline.recordEvaluation(checkpointId, evaluation)
        val second = pipeline.recordEvaluation(checkpointId, evaluation)
        assertEquals(first, second)
        assertTrue(first.admission.admitted)

        val promotion = pipeline.promotionCandidate(checkpointId)
        assertTrue(promotion.promotable)
        assertFalse(promotion.liveRegistered)
        assertFalse(promotion.authorityBearing)
    }

    @Test
    fun artifactTargetMismatchFailsRunAndPublishesNoCheckpoint() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val pipeline = MemoryBackedNativeTrainingPipeline(memory, foundation)
        val setup = publishFoundation(foundation)
        val teacher = teacher("teacher-mismatch")
        pipeline.putTeacher(teacher)
        val manifest = manifest(
            foundation = foundation,
            setup = setup,
            teacherIds = listOf(teacher.id),
            id = "manifest-mismatch"
        )
        pipeline.putManifest(manifest)
        val checkpointId = NativeCheckpointId("checkpoint-mismatch")
        val run = pipeline.prepareRun(
            NativeTrainingRunId("run-mismatch"),
            manifest.id,
            checkpointId
        )

        val finished = pipeline.execute(run.id) { request ->
            Result.success(
                artifact(
                    request = request,
                    sha = "d".repeat(64),
                    quantization = "Q8_0"
                )
            )
        }

        assertEquals(NativeTrainingRunStatus.FAILED, finished.status)
        assertNotNull(finished.failureCode)
        assertNull(foundation.getCheckpoint(checkpointId))
        assertNull(pipeline.getEvaluation(checkpointId))
    }

    @Test
    fun trainerFailurePublishesNoCheckpoint() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val pipeline = MemoryBackedNativeTrainingPipeline(memory, foundation)
        val setup = publishFoundation(foundation)
        val teacher = teacher("teacher-failure")
        pipeline.putTeacher(teacher)
        val manifest = manifest(
            foundation = foundation,
            setup = setup,
            teacherIds = listOf(teacher.id),
            id = "manifest-failure"
        )
        pipeline.putManifest(manifest)
        val checkpointId = NativeCheckpointId("checkpoint-failure")
        val run = pipeline.prepareRun(
            NativeTrainingRunId("run-failure"),
            manifest.id,
            checkpointId
        )

        val finished = pipeline.execute(run.id) {
            Result.failure(IllegalStateException("accelerator offline with private detail"))
        }

        assertEquals(NativeTrainingRunStatus.FAILED, finished.status)
        assertEquals("IllegalStateException", finished.failureCode)
        assertNull(foundation.getCheckpoint(checkpointId))
    }

    @Test
    fun betterCandidateBecomesPromotionCandidateWithoutLiveRegistration() {
        val memory = InMemoryMemoryOs()
        var now = 20_000L
        val foundation = MemoryBackedNativeModelFoundation(memory, clock = { now++ })
        val pipeline = MemoryBackedNativeTrainingPipeline(memory, foundation, clock = { now++ })
        val setup = publishFoundation(foundation)
        val teacher = teacher("teacher-compare")
        pipeline.putTeacher(teacher)
        val manifest = manifest(
            foundation = foundation,
            setup = setup,
            teacherIds = listOf(teacher.id),
            id = "manifest-compare"
        )
        pipeline.putManifest(manifest)

        val baselineId = train(
            pipeline = pipeline,
            manifest = manifest,
            runName = "run-baseline",
            checkpointName = "checkpoint-baseline",
            sha = "e".repeat(64)
        )
        val candidateId = train(
            pipeline = pipeline,
            manifest = manifest,
            runName = "run-candidate",
            checkpointName = "checkpoint-candidate",
            sha = "f".repeat(64)
        )

        pipeline.recordEvaluation(
            baselineId,
            passingEvaluation(
                planningRate = 0.98,
                toolRate = 0.98,
                regressionRate = 0.95,
                generalizationRate = 0.85
            )
        )
        pipeline.recordEvaluation(
            candidateId,
            passingEvaluation(
                planningRate = 1.0,
                toolRate = 1.0,
                regressionRate = 0.99,
                generalizationRate = 0.92
            )
        )

        val comparison = pipeline.compare(candidateId, baselineId)
        assertTrue(comparison.noMaterialRegression)
        assertTrue(comparison.aggregateDelta > MemoryBackedNativeTrainingPipeline.MIN_AGGREGATE_IMPROVEMENT)

        val promotion = pipeline.promotionCandidate(candidateId, baselineId)
        assertTrue(promotion.promotable)
        assertFalse(promotion.liveRegistered)
    }

    @Test
    fun admittedCandidateWithMaterialRegressionIsNotPromotable() {
        val memory = InMemoryMemoryOs()
        var now = 30_000L
        val foundation = MemoryBackedNativeModelFoundation(memory, clock = { now++ })
        val pipeline = MemoryBackedNativeTrainingPipeline(memory, foundation, clock = { now++ })
        val setup = publishFoundation(foundation)
        val teacher = teacher("teacher-regression")
        pipeline.putTeacher(teacher)
        val manifest = manifest(
            foundation = foundation,
            setup = setup,
            teacherIds = listOf(teacher.id),
            id = "manifest-regression"
        )
        pipeline.putManifest(manifest)

        val baselineId = train(
            pipeline, manifest, "run-strong", "checkpoint-strong", "1".repeat(64)
        )
        val candidateId = train(
            pipeline, manifest, "run-regress", "checkpoint-regress", "2".repeat(64)
        )

        pipeline.recordEvaluation(
            baselineId,
            passingEvaluation(1.0, 1.0, 0.99, 0.92)
        )
        pipeline.recordEvaluation(
            candidateId,
            passingEvaluation(0.99, 0.98, 0.98, 0.90)
        )

        val candidateRecord = requireNotNull(pipeline.getEvaluation(candidateId))
        assertTrue(candidateRecord.admission.admitted)

        val comparison = pipeline.compare(candidateId, baselineId)
        assertFalse(comparison.noMaterialRegression)
        assertTrue(comparison.reasons.any { it.contains("material held-out regression") })

        val promotion = pipeline.promotionCandidate(candidateId, baselineId)
        assertFalse(promotion.promotable)
    }

    @Test
    fun trainingPipelineRecordsAreExcludedFromGenericContext() {
        val runtime = AmperRuntime.reference()
        val teacher = teacher("teacher-context")
        runtime.nativeTrainingPipeline.putTeacher(teacher)

        val snapshot = runtime.context.capture(
            query = "teacher-context",
            memoryLimit = 16,
            worldLimit = 0,
            workspaceLimit = 0
        )

        assertFalse(snapshot.memories.any {
            it.kind == MemoryBackedNativeTrainingPipeline.TEACHER_KIND ||
                it.kind == MemoryBackedNativeTrainingPipeline.MANIFEST_KIND ||
                it.kind == MemoryBackedNativeTrainingPipeline.RUN_KIND ||
                it.kind == MemoryBackedNativeTrainingPipeline.OUTPUT_INDEX_KIND ||
                it.kind == MemoryBackedNativeTrainingPipeline.EVALUATION_KIND ||
                it.kind == MemoryBackedNativeTrainingPipeline.PROMOTION_KIND
        })
    }

    private data class FoundationSetup(
        val contract: AmperNativeModelContract,
        val curriculum: NativeCurriculumManifest,
        val recipe: NativeTrainingRecipe,
        val shards: List<NativeDatasetShardManifest>
    )

    private fun publishFoundation(
        foundation: NativeModelFoundation
    ): FoundationSetup {
        val contract = AmperNativeModelContract(
            id = NativeModelContractId("amper-native-core-v1"),
            familyVersion = 1,
            parameterCount = 120_000_000L,
            layerCount = 24,
            hiddenSize = 1024,
            maxContextTokens = 8192,
            capabilities = setOf(reasoning, planning),
            ownedByAmper = true
        )
        val curriculum = NativeCurriculumManifest(
            id = NativeCurriculumId("native-curriculum-v1"),
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
        val shards = listOf(
            NativeDatasetShardManifest(
                id = NativeDatasetShardId("distill-user"),
                sha256 = "a".repeat(64),
                sourceLabel = "user-owned distilled reasoning data",
                rights = NativeDatasetRights.USER_OWNED,
                exampleCount = 512,
                byteCount = 65_536,
                targetCapabilities = setOf(reasoning, planning),
                createdAtEpochMs = 1_000L
            )
        )
        foundation.putContract(contract)
        foundation.putCurriculum(curriculum)
        shards.forEach(foundation::putDatasetShard)
        return FoundationSetup(contract, curriculum, recipe, shards)
    }

    private fun teacher(
        id: String,
        rights: NativeTeacherRights = NativeTeacherRights.USER_OWNED
    ): NativeTeacherSnapshot = NativeTeacherSnapshot(
        id = NativeTeacherSnapshotId(id),
        modelId = ModelId("model-" + id),
        artifactSha256 = "9".repeat(64),
        sourceLabel = "teacher snapshot " + id,
        rights = rights,
        capabilities = setOf(reasoning, planning),
        local = true,
        createdAtEpochMs = 2_000L
    )

    private fun manifest(
        foundation: NativeModelFoundation,
        setup: FoundationSetup,
        teacherIds: List<NativeTeacherSnapshotId>,
        id: String
    ): NativeDistillationManifest {
        val shardIds = setup.shards.map { it.id }
        return NativeDistillationManifest(
            id = NativeDistillationManifestId(id),
            teacherSnapshotIds = teacherIds,
            studentContractId = setup.contract.id,
            studentContractDigest = setup.contract.canonicalDigest,
            parentCheckpointId = null,
            datasetShardIds = shardIds,
            datasetSnapshotDigest = foundation.datasetSnapshotDigest(shardIds),
            curriculumId = setup.curriculum.id,
            curriculumDigest = setup.curriculum.canonicalDigest,
            recipe = setup.recipe,
            target = NativeMobileTargetProfile(
                outputFormat = "gguf",
                quantization = "Q4_K_M",
                maxRuntimeMemoryMb = 2048,
                contextTokens = 4096,
                androidArm64 = true
            ),
            teacherTemperature = 2.0,
            teacherLossWeight = 0.65,
            createdAtEpochMs = 3_000L
        )
    }

    private fun artifact(
        request: NativeTrainingRequest,
        sha: String,
        quantization: String = "Q4_K_M",
        finalLoss: Double = 0.5
    ): NativeTrainingArtifact = NativeTrainingArtifact(
        runId = request.runId,
        manifestDigest = request.manifest.canonicalDigest,
        trainingBackendId = "fake-native-trainer",
        weightArtifactSha256 = sha,
        outputFormat = "gguf",
        quantization = quantization,
        artifactBytes = 80_000_000L,
        examplesSeen = 512,
        completedSteps = 128,
        finalLoss = finalLoss
    )

    private fun train(
        pipeline: NativeTrainingPipeline,
        manifest: NativeDistillationManifest,
        runName: String,
        checkpointName: String,
        sha: String
    ): NativeCheckpointId {
        val checkpointId = NativeCheckpointId(checkpointName)
        val run = pipeline.prepareRun(
            NativeTrainingRunId(runName),
            manifest.id,
            checkpointId
        )
        val finished = pipeline.execute(run.id) { request ->
            Result.success(artifact(request, sha))
        }
        assertEquals(NativeTrainingRunStatus.SUCCEEDED, finished.status)
        return checkpointId
    }

    private fun passingEvaluation(
        planningRate: Double,
        toolRate: Double,
        regressionRate: Double,
        generalizationRate: Double
    ): NativeCheckpointEvaluation = NativeCheckpointEvaluation(
        planningProtocolPassRate = planningRate,
        toolContractPassRate = toolRate,
        regressionPassRate = regressionRate,
        heldoutGeneralizationPassRate = generalizationRate,
        planningSamples = 64,
        toolContractSamples = 64,
        regressionSamples = 64,
        heldoutGeneralizationSamples = 64
    )
}
