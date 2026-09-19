package io.amper.neuroos.core

data class NativeExperienceTrainingAssembly(
    val manifest: NativeDistillationManifest,
    val run: NativeTrainingRun
) {
    init {
        require(run.manifestId == manifest.id)
        require(run.manifestDigest == manifest.canonicalDigest)
        require(run.status == NativeTrainingRunStatus.PREPARED)
    }

    val authorityBearing: Boolean
        get() = false
}

interface NativeExperienceTrainingAssembler {
    fun prepare(
        bundle: NativeExperienceTrainingBundle,
        teacherSnapshotIds: List<NativeTeacherSnapshotId>,
        studentContractId: NativeModelContractId,
        manifestId: NativeDistillationManifestId,
        runId: NativeTrainingRunId,
        outputCheckpointId: NativeCheckpointId,
        target: NativeMobileTargetProfile,
        parentCheckpointId: NativeCheckpointId? = null,
        optimizer: String = "adamw",
        precision: String = "bf16",
        learningRate: Double = 0.0002,
        teacherTemperature: Double = 2.0,
        teacherLossWeight: Double = 0.65
    ): NativeExperienceTrainingAssembly
}

/**
 * Phase406-410 deterministic assembly of verified native-experience training inputs.
 *
 * This component prepares the existing NativeDistillationManifest and NativeTrainingRun only.
 * It never executes a NativeTrainerPort, records an evaluation, creates a promotion candidate,
 * registers a live model, or acquires tool/device authority.
 */
class CanonicalNativeExperienceTrainingAssembler(
    private val foundation: NativeModelFoundation,
    private val pipeline: NativeTrainingPipeline,
    private val clock: () -> Long = System::currentTimeMillis
) : NativeExperienceTrainingAssembler {
    override fun prepare(
        bundle: NativeExperienceTrainingBundle,
        teacherSnapshotIds: List<NativeTeacherSnapshotId>,
        studentContractId: NativeModelContractId,
        manifestId: NativeDistillationManifestId,
        runId: NativeTrainingRunId,
        outputCheckpointId: NativeCheckpointId,
        target: NativeMobileTargetProfile,
        parentCheckpointId: NativeCheckpointId?,
        optimizer: String,
        precision: String,
        learningRate: Double,
        teacherTemperature: Double,
        teacherLossWeight: Double
    ): NativeExperienceTrainingAssembly {
        require(teacherSnapshotIds.isNotEmpty())
        require(teacherSnapshotIds.distinct().size == teacherSnapshotIds.size)

        val contract = requireNotNull(foundation.getContract(studentContractId)) {
            "AMPER native student contract is unavailable"
        }
        require(contract.ownedByAmper) {
            "verified-experience assembly requires an AMPER-owned student contract"
        }
        require(target.contextTokens <= contract.maxContextTokens)
        require(
            foundation.getDatasetShard(bundle.shard.manifest.id) == bundle.shard.manifest
        ) {
            "verified-experience shard is not registered in the native foundation"
        }
        require(
            foundation.getCurriculum(bundle.curriculum.id) == bundle.curriculum
        ) {
            "verified-experience curriculum is not registered in the native foundation"
        }
        require(
            foundation.datasetSnapshotDigest(listOf(bundle.shard.manifest.id)) ==
                bundle.datasetSnapshotDigest
        ) {
            "verified-experience dataset snapshot changed"
        }

        val recipe = NativeTrainingRecipe(
            optimizer = optimizer,
            precision = precision,
            maxSequenceTokens = target.contextTokens,
            learningRate = learningRate,
            curriculumDigest = bundle.curriculum.canonicalDigest
        )

        val existing = pipeline.getManifest(manifestId)
        val manifest = if (existing != null) {
            val expected = buildManifest(
                bundle = bundle,
                teacherSnapshotIds = teacherSnapshotIds,
                contract = contract,
                manifestId = manifestId,
                parentCheckpointId = parentCheckpointId,
                recipe = recipe,
                target = target,
                teacherTemperature = teacherTemperature,
                teacherLossWeight = teacherLossWeight,
                createdAtEpochMs = existing.createdAtEpochMs
            )
            require(existing == expected) {
                "native experience training manifest id changed configuration"
            }
            existing
        } else {
            buildManifest(
                bundle = bundle,
                teacherSnapshotIds = teacherSnapshotIds,
                contract = contract,
                manifestId = manifestId,
                parentCheckpointId = parentCheckpointId,
                recipe = recipe,
                target = target,
                teacherTemperature = teacherTemperature,
                teacherLossWeight = teacherLossWeight,
                createdAtEpochMs = clock()
            ).also(pipeline::putManifest)
        }

        val run = pipeline.prepareRun(
            id = runId,
            manifestId = manifest.id,
            outputCheckpointId = outputCheckpointId
        )
        require(run.status == NativeTrainingRunStatus.PREPARED) {
            "native experience assembly may only return a prepared run"
        }
        return NativeExperienceTrainingAssembly(manifest, run)
    }

    private fun buildManifest(
        bundle: NativeExperienceTrainingBundle,
        teacherSnapshotIds: List<NativeTeacherSnapshotId>,
        contract: AmperNativeModelContract,
        manifestId: NativeDistillationManifestId,
        parentCheckpointId: NativeCheckpointId?,
        recipe: NativeTrainingRecipe,
        target: NativeMobileTargetProfile,
        teacherTemperature: Double,
        teacherLossWeight: Double,
        createdAtEpochMs: Long
    ): NativeDistillationManifest = NativeDistillationManifest(
        id = manifestId,
        teacherSnapshotIds = teacherSnapshotIds,
        studentContractId = contract.id,
        studentContractDigest = contract.canonicalDigest,
        parentCheckpointId = parentCheckpointId,
        datasetShardIds = listOf(bundle.shard.manifest.id),
        datasetSnapshotDigest = bundle.datasetSnapshotDigest,
        curriculumId = bundle.curriculum.id,
        curriculumDigest = bundle.curriculum.canonicalDigest,
        recipe = recipe,
        target = target,
        teacherTemperature = teacherTemperature,
        teacherLossWeight = teacherLossWeight,
        createdAtEpochMs = createdAtEpochMs
    )
}
