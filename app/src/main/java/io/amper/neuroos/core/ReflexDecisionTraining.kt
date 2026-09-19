package io.amper.neuroos.core

data class ReflexDecisionTrainingBundle(
    val shard: ReflexExperienceDatasetShard,
    val curriculum: NativeCurriculumManifest,
    val datasetSnapshotDigest: String,
    val actionExamples: Int,
    val escalationExamples: Int
) {
    init {
        require(actionExamples > 0)
        require(escalationExamples > 0)
        require(datasetSnapshotDigest.matches(Regex("[0-9a-f]{64}")))
        require(curriculum.stages.size == 1)
        require(curriculum.stages.single().capabilities == setOf(TitanCapabilities.REFLEX_DECISION))
        require(curriculum.stages.single().minExamples <= shard.exampleIds.size.toLong())
    }

    val authorityBearing: Boolean
        get() = false
}

interface ReflexDecisionCurriculumPlanner {
    fun synthesize(
        shardId: NativeDatasetShardId,
        curriculumId: NativeCurriculumId,
        minActionExamples: Int = 2,
        minEscalationExamples: Int = 2,
        limit: Int = 256
    ): ReflexDecisionTrainingBundle
}

/**
 * Phase432 one-stage evidence curriculum for AMPER's native System-1 decision model.
 */
class EvidenceReflexDecisionCurriculumPlanner(
    private val store: ReflexExperienceDatasetStore,
    private val foundation: NativeModelFoundation
) : ReflexDecisionCurriculumPlanner {
    override fun synthesize(
        shardId: NativeDatasetShardId,
        curriculumId: NativeCurriculumId,
        minActionExamples: Int,
        minEscalationExamples: Int,
        limit: Int
    ): ReflexDecisionTrainingBundle {
        require(minActionExamples > 0)
        require(minEscalationExamples > 0)
        require(limit in 1..MemoryBackedReflexExperienceDatasetStore.MAX_SHARD_EXAMPLES)
        val shard = store.getShard(shardId)
            ?: store.materializeShard(
                id = shardId,
                minExamples = minActionExamples + minEscalationExamples,
                limit = limit
            )
        val examples = shard.exampleIds.map { id ->
            requireNotNull(store.getExample(id)) {
                "reflex training shard references a missing example"
            }
        }
        val actionCount = examples.count {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalationCount = examples.size - actionCount
        require(actionCount >= minActionExamples) {
            "reflex training shard has insufficient ACTION evidence"
        }
        require(escalationCount >= minEscalationExamples) {
            "reflex training shard has insufficient ESCALATE evidence"
        }

        val requiredExamples = minActionExamples + minEscalationExamples
        val expected = NativeCurriculumManifest(
            id = curriculumId,
            stages = listOf(
                NativeCurriculumStage(
                    index = 1,
                    name = "reflex-decision",
                    capabilities = setOf(TitanCapabilities.REFLEX_DECISION),
                    minExamples = requiredExamples.toLong(),
                    weight = 1.0
                )
            )
        )
        foundation.getCurriculum(curriculumId)?.let { existing ->
            require(existing == expected) {
                "reflex curriculum id is already bound to different evidence requirements"
            }
        } ?: foundation.putCurriculum(expected)

        return ReflexDecisionTrainingBundle(
            shard = shard,
            curriculum = expected,
            datasetSnapshotDigest = foundation.datasetSnapshotDigest(
                listOf(shard.manifest.id)
            ),
            actionExamples = actionCount,
            escalationExamples = escalationCount
        )
    }
}

data class ReflexDecisionTrainingSpec(
    val trainingShardId: NativeDatasetShardId,
    val holdoutShardId: NativeDatasetShardId,
    val curriculumId: NativeCurriculumId,
    val manifestId: NativeDistillationManifestId,
    val runId: NativeTrainingRunId,
    val outputCheckpointId: NativeCheckpointId,
    val teacherSnapshotIds: List<NativeTeacherSnapshotId>,
    val studentContractId: NativeModelContractId,
    val parentCheckpointId: NativeCheckpointId? = null,
    val optimizer: String,
    val precision: String,
    val maxSequenceTokens: Int,
    val learningRate: Double,
    val target: NativeMobileTargetProfile,
    val teacherTemperature: Double = 1.0,
    val teacherLossWeight: Double = 0.5,
    val holdoutRatio: Double = 0.20,
    val minTrainingPerClass: Int = 2,
    val minHoldoutPerClass: Int = 1,
    val limit: Int = 256
) {
    init {
        require(trainingShardId != holdoutShardId)
        require(teacherSnapshotIds.isNotEmpty())
        require(teacherSnapshotIds.distinct().size == teacherSnapshotIds.size)
        require(optimizer.isNotBlank())
        require(precision.isNotBlank())
        require(maxSequenceTokens in 128..1_048_576)
        require(learningRate > 0.0 && learningRate <= 1.0)
        require(teacherTemperature in 0.1..20.0)
        require(teacherLossWeight in 0.0..1.0)
        require(holdoutRatio > 0.0 && holdoutRatio < 1.0)
        require(minTrainingPerClass > 0)
        require(minHoldoutPerClass > 0)
        require(limit in 1..MemoryBackedReflexExperienceDatasetStore.MAX_SHARD_EXAMPLES)
    }

    val authorityBearing: Boolean
        get() = false
}

data class PreparedReflexDecisionTraining(
    val partition: ReflexExperiencePartition,
    val bundle: ReflexDecisionTrainingBundle,
    val manifest: NativeDistillationManifest,
    val run: NativeTrainingRun
) {
    init {
        require(bundle.shard.manifest.id == partition.trainingShard.manifest.id)
        require(partition.holdoutShard.manifest.id !in manifest.datasetShardIds)
        require(manifest.datasetShardIds == listOf(partition.trainingShard.manifest.id))
        require(manifest.datasetSnapshotDigest == bundle.datasetSnapshotDigest)
        require(manifest.curriculumId == bundle.curriculum.id)
        require(run.manifestId == manifest.id)
        require(run.manifestDigest == manifest.canonicalDigest)
        require(run.status == NativeTrainingRunStatus.PREPARED)
    }

    val authorityBearing: Boolean
        get() = false
}

interface ReflexDecisionTrainingCoordinator {
    fun prepare(spec: ReflexDecisionTrainingSpec): PreparedReflexDecisionTraining
}

/**
 * Phase433-435 compiler from privacy-preserving Reflex experience into the existing native pipeline.
 *
 * The holdout shard is deliberately excluded from the training manifest. The coordinator prepares a
 * run only; NativeTrainerPort execution, held-out evaluation, checkpoint admission and runtime
 * promotion remain independent downstream gates.
 */
class CanonicalReflexDecisionTrainingCoordinator(
    private val partitioner: ReflexExperiencePartitioner,
    private val curriculum: ReflexDecisionCurriculumPlanner,
    private val foundation: NativeModelFoundation,
    private val training: NativeTrainingPipeline,
    private val clock: () -> Long = System::currentTimeMillis
) : ReflexDecisionTrainingCoordinator {
    @Synchronized
    override fun prepare(spec: ReflexDecisionTrainingSpec): PreparedReflexDecisionTraining {
        val partition = partitioner.partition(
            trainingShardId = spec.trainingShardId,
            holdoutShardId = spec.holdoutShardId,
            holdoutRatio = spec.holdoutRatio,
            minTrainingPerClass = spec.minTrainingPerClass,
            minHoldoutPerClass = spec.minHoldoutPerClass,
            limit = spec.limit
        )
        val bundle = curriculum.synthesize(
            shardId = partition.trainingShard.manifest.id,
            curriculumId = spec.curriculumId,
            minActionExamples = spec.minTrainingPerClass,
            minEscalationExamples = spec.minTrainingPerClass,
            limit = spec.limit
        )
        val manifest = compileManifest(spec, bundle)
        training.getManifest(spec.manifestId)?.let { existing ->
            require(existing == manifest) {
                "reflex decision manifest id is already bound to different inputs"
            }
        } ?: training.putManifest(manifest)

        val run = training.prepareRun(
            id = spec.runId,
            manifestId = spec.manifestId,
            outputCheckpointId = spec.outputCheckpointId
        )
        require(run.status == NativeTrainingRunStatus.PREPARED) {
            "reflex training preparation may only resume a PREPARED run"
        }
        return PreparedReflexDecisionTraining(
            partition = partition,
            bundle = bundle,
            manifest = manifest,
            run = run
        )
    }

    private fun compileManifest(
        spec: ReflexDecisionTrainingSpec,
        bundle: ReflexDecisionTrainingBundle
    ): NativeDistillationManifest {
        val contract = requireNotNull(foundation.getContract(spec.studentContractId)) {
            "reflex decision student contract is unavailable"
        }
        require(contract.ownedByAmper) {
            "reflex decision training requires an AMPER-owned student contract"
        }
        require(TitanCapabilities.REFLEX_DECISION in contract.capabilities) {
            "reflex decision capability is missing from student contract"
        }
        spec.parentCheckpointId?.let { parentId ->
            val parent = requireNotNull(foundation.getCheckpoint(parentId)) {
                "reflex parent checkpoint is unavailable"
            }
            require(parent.contractId == contract.id)
            require(parent.contractDigest == contract.canonicalDigest)
        }

        val teachers = spec.teacherSnapshotIds.map { id ->
            requireNotNull(training.getTeacher(id)) {
                "reflex decision teacher snapshot is unavailable: " + id.value
            }
        }
        require(teachers.all { it.distillationEligible }) {
            "reflex decision teacher rights are not training eligible"
        }
        require(teachers.any { TitanCapabilities.REFLEX_DECISION in it.capabilities }) {
            "reflex decision teachers do not cover reflex-decision capability"
        }

        val recipe = NativeTrainingRecipe(
            optimizer = spec.optimizer,
            precision = spec.precision,
            maxSequenceTokens = spec.maxSequenceTokens,
            learningRate = spec.learningRate,
            curriculumDigest = bundle.curriculum.canonicalDigest
        )
        return NativeDistillationManifest(
            id = spec.manifestId,
            teacherSnapshotIds = spec.teacherSnapshotIds,
            studentContractId = contract.id,
            studentContractDigest = contract.canonicalDigest,
            parentCheckpointId = spec.parentCheckpointId,
            datasetShardIds = listOf(bundle.shard.manifest.id),
            datasetSnapshotDigest = bundle.datasetSnapshotDigest,
            curriculumId = bundle.curriculum.id,
            curriculumDigest = bundle.curriculum.canonicalDigest,
            recipe = recipe,
            target = spec.target,
            teacherTemperature = spec.teacherTemperature,
            teacherLossWeight = spec.teacherLossWeight,
            createdAtEpochMs = maxOf(clock(), bundle.shard.manifest.createdAtEpochMs)
        )
    }
}
