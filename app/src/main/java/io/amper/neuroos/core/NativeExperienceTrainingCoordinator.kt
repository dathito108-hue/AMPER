package io.amper.neuroos.core

data class NativeExperienceTrainingSpec(
    val shardId: NativeDatasetShardId,
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
    val minExamplesPerCapability: Int = 2,
    val limit: Int = 256
) {
    init {
        require(teacherSnapshotIds.isNotEmpty())
        require(teacherSnapshotIds.distinct().size == teacherSnapshotIds.size)
        require(optimizer.isNotBlank())
        require(precision.isNotBlank())
        require(maxSequenceTokens in 128..1_048_576)
        require(learningRate > 0.0 && learningRate <= 1.0)
        require(teacherTemperature in 0.1..20.0)
        require(teacherLossWeight in 0.0..1.0)
        require(minExamplesPerCapability > 0)
        require(
            limit in minExamplesPerCapability..
                MemoryBackedNativeExperienceDatasetStore.MAX_SHARD_EXAMPLES
        )
    }

    val authorityBearing: Boolean
        get() = false
}

data class PreparedNativeExperienceTraining(
    val bundle: NativeExperienceTrainingBundle,
    val manifest: NativeDistillationManifest,
    val run: NativeTrainingRun
) {
    init {
        require(run.manifestId == manifest.id)
        require(run.manifestDigest == manifest.canonicalDigest)
        require(run.status == NativeTrainingRunStatus.PREPARED)
        require(manifest.datasetShardIds == listOf(bundle.shard.manifest.id))
        require(manifest.datasetSnapshotDigest == bundle.datasetSnapshotDigest)
        require(manifest.curriculumId == bundle.curriculum.id)
        require(manifest.curriculumDigest == bundle.curriculum.canonicalDigest)
    }

    val authorityBearing: Boolean
        get() = false
}

interface NativeExperienceTrainingCoordinator {
    fun prepare(spec: NativeExperienceTrainingSpec): PreparedNativeExperienceTraining
}

/**
 * Phase406-410 compiler from verified AMPER experience into the existing native training pipeline.
 *
 * This coordinator owns no training implementation and no promotion authority. It only composes the
 * immutable shard/curriculum evidence already produced by Phase396-405 with an existing AMPER-owned
 * model contract, eligible teacher snapshots, a bounded recipe and a mobile target. The result is an
 * existing NativeDistillationManifest plus a PREPARED NativeTrainingRun.
 */
class CanonicalNativeExperienceTrainingCoordinator(
    private val datasets: NativeExperienceDatasetStore,
    private val curriculum: NativeExperienceCurriculumPlanner,
    private val foundation: NativeModelFoundation,
    private val training: NativeTrainingPipeline,
    private val clock: () -> Long = System::currentTimeMillis
) : NativeExperienceTrainingCoordinator {
    @Synchronized
    override fun prepare(
        spec: NativeExperienceTrainingSpec
    ): PreparedNativeExperienceTraining {
        training.getRun(spec.runId)?.let { existingRun ->
            require(existingRun.manifestId == spec.manifestId) {
                "verified-experience run manifest changed"
            }
            require(existingRun.outputCheckpointId == spec.outputCheckpointId) {
                "verified-experience output checkpoint changed"
            }
            require(existingRun.status == NativeTrainingRunStatus.PREPARED) {
                "verified-experience preparation may only resume a prepared run"
            }
            val existingManifest = requireNotNull(training.getManifest(spec.manifestId)) {
                "prepared verified-experience run lost its manifest"
            }
            val bundle = existingBundle(spec)
            require(existingManifest == compileManifest(spec, bundle)) {
                "verified-experience manifest no longer matches the requested preparation"
            }
            return PreparedNativeExperienceTraining(
                bundle = bundle,
                manifest = existingManifest,
                run = existingRun
            )
        }

        val bundle = existingBundleOrSynthesize(spec)
        val manifest = compileManifest(spec, bundle)
        training.getManifest(spec.manifestId)?.let { existing ->
            require(existing == manifest) {
                "verified-experience manifest id is already bound to different inputs"
            }
        } ?: training.putManifest(manifest)

        val run = training.prepareRun(
            id = spec.runId,
            manifestId = spec.manifestId,
            outputCheckpointId = spec.outputCheckpointId
        )
        return PreparedNativeExperienceTraining(
            bundle = bundle,
            manifest = manifest,
            run = run
        )
    }

    private fun existingBundleOrSynthesize(
        spec: NativeExperienceTrainingSpec
    ): NativeExperienceTrainingBundle =
        if (foundation.getCurriculum(spec.curriculumId) == null) {
            curriculum.synthesize(
                shardId = spec.shardId,
                curriculumId = spec.curriculumId,
                minExamplesPerCapability = spec.minExamplesPerCapability,
                limit = spec.limit
            )
        } else {
            existingBundle(spec)
        }

    private fun existingBundle(
        spec: NativeExperienceTrainingSpec
    ): NativeExperienceTrainingBundle {
        val shard = requireNotNull(datasets.getShard(spec.shardId)) {
            "verified-experience shard is unavailable"
        }
        val existingCurriculum = requireNotNull(foundation.getCurriculum(spec.curriculumId)) {
            "verified-experience curriculum is unavailable"
        }
        val examples = shard.exampleIds.map { id ->
            requireNotNull(datasets.getExample(id)) {
                "verified-experience shard references a missing example"
            }
        }
        val counts = linkedMapOf<CapabilityId, Int>()
        examples.forEach { example ->
            example.strategy.capabilities.forEach { capability ->
                counts[capability] = counts.getOrDefault(capability, 0) + 1
            }
        }
        existingCurriculum.stages.forEach { stage ->
            stage.capabilities.forEach { capability ->
                require(
                    counts.getOrDefault(capability, 0) >= stage.minExamples
                ) {
                    "existing verified-experience curriculum exceeds shard evidence"
                }
            }
        }
        return NativeExperienceTrainingBundle(
            shard = shard,
            curriculum = existingCurriculum,
            datasetSnapshotDigest = foundation.datasetSnapshotDigest(
                listOf(shard.manifest.id)
            ),
            capabilityExampleCounts = counts
        )
    }

    private fun compileManifest(
        spec: NativeExperienceTrainingSpec,
        bundle: NativeExperienceTrainingBundle
    ): NativeDistillationManifest {
        val contract = requireNotNull(foundation.getContract(spec.studentContractId)) {
            "verified-experience student contract is unavailable"
        }
        require(contract.ownedByAmper) {
            "verified-experience training requires an AMPER-owned student contract"
        }
        spec.parentCheckpointId?.let { parentId ->
            val parent = requireNotNull(foundation.getCheckpoint(parentId)) {
                "verified-experience parent checkpoint is unavailable"
            }
            require(parent.contractId == contract.id)
            require(parent.contractDigest == contract.canonicalDigest)
        }

        val teachers = spec.teacherSnapshotIds.map { id ->
            requireNotNull(training.getTeacher(id)) {
                "verified-experience teacher snapshot is unavailable: " + id.value
            }
        }
        require(teachers.all { it.distillationEligible }) {
            "verified-experience teacher rights are not training eligible"
        }

        val requiredCapabilities = bundle.curriculum.stages
            .flatMap { it.capabilities }
            .toSet()
        require(requiredCapabilities.all { it in contract.capabilities }) {
            "verified-experience curriculum exceeds student contract capabilities"
        }
        require(
            requiredCapabilities.all { capability ->
                teachers.any { capability in it.capabilities }
            }
        ) {
            "verified-experience teachers do not cover the synthesized curriculum"
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
            createdAtEpochMs = maxOf(
                clock(),
                bundle.shard.manifest.createdAtEpochMs
            )
        )
    }
}
