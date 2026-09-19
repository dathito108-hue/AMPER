package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs

@JvmInline
value class NativeTeacherSnapshotId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class NativeDistillationManifestId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class NativeTrainingRunId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class NativeTeacherRights {
    USER_OWNED,
    INTERNAL,
    PERMISSIVE_EXTERNAL,
    UNKNOWN
}

data class NativeTeacherSnapshot(
    val id: NativeTeacherSnapshotId,
    val modelId: ModelId,
    val artifactSha256: String,
    val sourceLabel: String,
    val rights: NativeTeacherRights,
    val capabilities: Set<CapabilityId>,
    val local: Boolean,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(artifactSha256.matches(SHA256))
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 256)
        require(capabilities.isNotEmpty())
        require(createdAtEpochMs >= 0L)
    }

    val distillationEligible: Boolean
        get() = rights != NativeTeacherRights.UNKNOWN

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class NativeMobileTargetProfile(
    val outputFormat: String,
    val quantization: String,
    val maxRuntimeMemoryMb: Int,
    val contextTokens: Int,
    val androidArm64: Boolean = true
) {
    init {
        require(outputFormat.isNotBlank() && outputFormat.length <= 32)
        require(quantization.isNotBlank() && quantization.length <= 32)
        require(maxRuntimeMemoryMb in 128..65_536)
        require(contextTokens in 512..1_048_576)
    }

    val canonicalDigest: String
        get() = trainingSha256(
            listOf(
                outputFormat.lowercase(),
                quantization.lowercase(),
                maxRuntimeMemoryMb.toString(),
                contextTokens.toString(),
                androidArm64.toString()
            ).joinToString("|")
        )
}

data class NativeDistillationManifest(
    val id: NativeDistillationManifestId,
    val teacherSnapshotIds: List<NativeTeacherSnapshotId>,
    val studentContractId: NativeModelContractId,
    val studentContractDigest: String,
    val parentCheckpointId: NativeCheckpointId?,
    val datasetShardIds: List<NativeDatasetShardId>,
    val datasetSnapshotDigest: String,
    val curriculumId: NativeCurriculumId,
    val curriculumDigest: String,
    val recipe: NativeTrainingRecipe,
    val target: NativeMobileTargetProfile,
    val teacherTemperature: Double,
    val teacherLossWeight: Double,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(teacherSnapshotIds.isNotEmpty())
        require(teacherSnapshotIds.distinct().size == teacherSnapshotIds.size)
        require(studentContractDigest.matches(SHA256))
        require(datasetShardIds.isNotEmpty())
        require(datasetShardIds.distinct().size == datasetShardIds.size)
        require(datasetSnapshotDigest.matches(SHA256))
        require(curriculumDigest.matches(SHA256))
        require(recipe.curriculumDigest == curriculumDigest)
        require(teacherTemperature in 0.1..20.0)
        require(teacherLossWeight in 0.0..1.0)
        require(createdAtEpochMs >= 0L)
    }

    val canonicalDigest: String
        get() = trainingSha256(
            listOf(
                id.value,
                teacherSnapshotIds.map { it.value }.sorted().joinToString(","),
                studentContractId.value,
                studentContractDigest,
                parentCheckpointId?.value ?: "~",
                datasetShardIds.map { it.value }.sorted().joinToString(","),
                datasetSnapshotDigest,
                curriculumId.value,
                curriculumDigest,
                recipe.canonicalDigest,
                target.canonicalDigest,
                teacherTemperature.toString(),
                teacherLossWeight.toString()
            ).joinToString("|")
        )

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

enum class NativeTrainingRunStatus {
    PREPARED,
    SUCCEEDED,
    FAILED
}

data class NativeTrainingRun(
    val id: NativeTrainingRunId,
    val manifestId: NativeDistillationManifestId,
    val manifestDigest: String,
    val outputCheckpointId: NativeCheckpointId,
    val status: NativeTrainingRunStatus,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val weightArtifactSha256: String? = null,
    val trainingBackendId: String? = null,
    val examplesSeen: Long? = null,
    val completedSteps: Long? = null,
    val finalLoss: Double? = null,
    val failureCode: String? = null
) {
    init {
        require(manifestDigest.matches(SHA256))
        require(createdAtEpochMs >= 0L)
        require(updatedAtEpochMs >= createdAtEpochMs)
        require(weightArtifactSha256 == null || weightArtifactSha256.matches(SHA256))
        require(trainingBackendId == null || trainingBackendId.isNotBlank())
        require(examplesSeen == null || examplesSeen > 0L)
        require(completedSteps == null || completedSteps > 0L)
        require(finalLoss == null || (finalLoss.isFinite() && finalLoss >= 0.0))
        require(failureCode == null || (failureCode.isNotBlank() && failureCode.length <= 128))
        when (status) {
            NativeTrainingRunStatus.PREPARED -> {
                require(weightArtifactSha256 == null)
                require(trainingBackendId == null)
                require(examplesSeen == null)
                require(completedSteps == null)
                require(finalLoss == null)
                require(failureCode == null)
            }
            NativeTrainingRunStatus.SUCCEEDED -> {
                requireNotNull(weightArtifactSha256)
                require(!trainingBackendId.isNullOrBlank())
                requireNotNull(examplesSeen)
                requireNotNull(completedSteps)
                requireNotNull(finalLoss)
                require(failureCode == null)
            }
            NativeTrainingRunStatus.FAILED -> {
                require(!failureCode.isNullOrBlank())
                require(weightArtifactSha256 == null)
            }
        }
    }
}

data class NativeTrainingRequest(
    val runId: NativeTrainingRunId,
    val manifest: NativeDistillationManifest,
    val teachers: List<NativeTeacherSnapshot>,
    val contract: AmperNativeModelContract,
    val curriculum: NativeCurriculumManifest,
    val datasets: List<NativeDatasetShardManifest>,
    val parentCheckpoint: NativeCheckpointLineage?
) {
    init {
        require(teachers.isNotEmpty())
        require(datasets.isNotEmpty())
        require(manifest.teacherSnapshotIds.toSet() == teachers.map { it.id }.toSet())
        require(manifest.datasetShardIds.toSet() == datasets.map { it.id }.toSet())
        require(manifest.studentContractId == contract.id)
        require(manifest.curriculumId == curriculum.id)
        require(manifest.parentCheckpointId == parentCheckpoint?.id)
    }
}

data class NativeTrainingArtifact(
    val runId: NativeTrainingRunId,
    val manifestDigest: String,
    val trainingBackendId: String,
    val weightArtifactSha256: String,
    val artifactBytes: Long,
    val examplesSeen: Long,
    val completedSteps: Long,
    val finalLoss: Double
) {
    init {
        require(manifestDigest.matches(SHA256))
        require(trainingBackendId.isNotBlank() && trainingBackendId.length <= 128)
        require(weightArtifactSha256.matches(SHA256))
        require(artifactBytes > 0L)
        require(examplesSeen > 0L)
        require(completedSteps > 0L)
        require(finalLoss.isFinite() && finalLoss >= 0.0)
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Pluggable model-training boundary.
 *
 * The pipeline supplies immutable lineage metadata. A concrete implementation may run locally,
 * delegate to a workstation/accelerator, or use a future training service. The port has no ToolFabric
 * or AuthorityGate handle and cannot directly register a model into live AMPER inference routing.
 */
fun interface NativeTrainerPort {
    fun train(request: NativeTrainingRequest): Result<NativeTrainingArtifact>
}

data class NativeCheckpointEvaluationRecord(
    val checkpointId: NativeCheckpointId,
    val evaluation: NativeCheckpointEvaluation,
    val admission: NativeCheckpointAdmission,
    val recordedAtEpochMs: Long
) {
    init {
        require(recordedAtEpochMs >= 0L)
        require(admission.checkpointId == checkpointId)
    }
}

data class NativeCheckpointComparison(
    val candidateCheckpointId: NativeCheckpointId,
    val baselineCheckpointId: NativeCheckpointId?,
    val candidateEvaluation: NativeCheckpointEvaluation,
    val baselineEvaluation: NativeCheckpointEvaluation?,
    val noMaterialRegression: Boolean,
    val aggregateDelta: Double,
    val reasons: List<String>
) {
    init {
        require(reasons.isNotEmpty())
    }
}

data class NativeModelPromotionCandidate(
    val checkpointId: NativeCheckpointId,
    val baselineCheckpointId: NativeCheckpointId?,
    val promotable: Boolean,
    val comparison: NativeCheckpointComparison,
    val createdAtEpochMs: Long
) {
    init {
        require(comparison.candidateCheckpointId == checkpointId)
        require(comparison.baselineCheckpointId == baselineCheckpointId)
        require(createdAtEpochMs >= 0L)
    }

    val liveRegistered: Boolean
        get() = false

    val authorityBearing: Boolean
        get() = false
}

interface NativeTrainingPipeline {
    fun putTeacher(snapshot: NativeTeacherSnapshot)
    fun getTeacher(id: NativeTeacherSnapshotId): NativeTeacherSnapshot?

    fun putManifest(manifest: NativeDistillationManifest)
    fun getManifest(id: NativeDistillationManifestId): NativeDistillationManifest?

    fun prepareRun(
        id: NativeTrainingRunId,
        manifestId: NativeDistillationManifestId,
        outputCheckpointId: NativeCheckpointId
    ): NativeTrainingRun

    fun getRun(id: NativeTrainingRunId): NativeTrainingRun?

    fun execute(
        id: NativeTrainingRunId,
        trainer: NativeTrainerPort
    ): NativeTrainingRun

    fun recordEvaluation(
        checkpointId: NativeCheckpointId,
        evaluation: NativeCheckpointEvaluation
    ): NativeCheckpointEvaluationRecord

    fun getEvaluation(checkpointId: NativeCheckpointId): NativeCheckpointEvaluationRecord?

    fun compare(
        candidateCheckpointId: NativeCheckpointId,
        baselineCheckpointId: NativeCheckpointId? = null
    ): NativeCheckpointComparison

    fun promotionCandidate(
        candidateCheckpointId: NativeCheckpointId,
        baselineCheckpointId: NativeCheckpointId? = null
    ): NativeModelPromotionCandidate
}

/**
 * Phase216-220 AMPER-native distillation/training pipeline.
 *
 * Phase216 freezes exact teacher identities and rights plus an immutable distillation manifest.
 * UNKNOWN teacher rights fail closed, just like UNKNOWN dataset rights.
 *
 * Phase217 prepares a mobile-targeted training run only when the student contract, dataset snapshot,
 * curriculum, recipe and optional parent checkpoint all match the Phase211-215 foundation.
 *
 * Phase218 invokes one pluggable [NativeTrainerPort]. Only a result bound to the exact run and
 * manifest digest can publish a new immutable checkpoint lineage. A trainer failure publishes no
 * checkpoint.
 *
 * Phase219 records held-out evaluations and compares candidate vs baseline with bounded regression
 * tolerance across all four foundation metrics.
 *
 * Phase220 produces a non-authoritative promotion candidate. It does not call ModelRegistry and does
 * not make a checkpoint live. Native routing/promotion remains Phase221+ work.
 */
class MemoryBackedNativeTrainingPipeline(
    private val memory: MemoryOs,
    private val foundation: NativeModelFoundation,
    private val clock: () -> Long = System::currentTimeMillis
) : NativeTrainingPipeline {
    override fun putTeacher(snapshot: NativeTeacherSnapshot) {
        memory.rememberIfAbsent(
            MemoryRecord(
                id = teacherMemoryId(snapshot.id),
                kind = TEACHER_KIND,
                content = NativeTrainingCodec.encodeTeacher(snapshot),
                importance = 0.92,
                provenance = Provenance(
                    source = "amper-native-distillation",
                    producer = "native-teacher-store",
                    confidence = if (snapshot.distillationEligible) 1.0 else 0.5
                ),
                createdAtEpochMs = snapshot.createdAtEpochMs
            )
        ).also { inserted ->
            if (!inserted) {
                require(getTeacher(snapshot.id) == snapshot) {
                    "native teacher snapshot identity is immutable"
                }
            }
        }
    }

    override fun getTeacher(id: NativeTeacherSnapshotId): NativeTeacherSnapshot? =
        memory.get(teacherMemoryId(id))
            ?.takeIf { it.kind == TEACHER_KIND }
            ?.let { NativeTrainingCodec.decodeTeacher(it.content) }

    override fun putManifest(manifest: NativeDistillationManifest) {
        validateManifest(manifest)
        memory.rememberIfAbsent(
            MemoryRecord(
                id = manifestMemoryId(manifest.id),
                kind = MANIFEST_KIND,
                content = NativeTrainingCodec.encodeManifest(manifest),
                importance = 0.96,
                provenance = Provenance(
                    source = "amper-native-distillation",
                    producer = "native-distillation-manifest",
                    confidence = 1.0,
                    parents = buildSet {
                        manifest.teacherSnapshotIds.forEach { add(teacherMemoryId(it)) }
                        add(MemoryId("native-contract:" + manifest.studentContractId.value))
                        add(MemoryId("native-curriculum:" + manifest.curriculumId.value))
                        manifest.datasetShardIds.forEach {
                            add(MemoryId("native-dataset:" + it.value))
                        }
                        manifest.parentCheckpointId?.let {
                            add(MemoryId("native-checkpoint:" + it.value))
                        }
                    }
                ),
                createdAtEpochMs = manifest.createdAtEpochMs
            )
        ).also { inserted ->
            if (!inserted) {
                require(getManifest(manifest.id) == manifest) {
                    "native distillation manifest is immutable"
                }
            }
        }
    }

    override fun getManifest(id: NativeDistillationManifestId): NativeDistillationManifest? =
        memory.get(manifestMemoryId(id))
            ?.takeIf { it.kind == MANIFEST_KIND }
            ?.let { NativeTrainingCodec.decodeManifest(it.content) }

    override fun prepareRun(
        id: NativeTrainingRunId,
        manifestId: NativeDistillationManifestId,
        outputCheckpointId: NativeCheckpointId
    ): NativeTrainingRun {
        val existing = getRun(id)
        if (existing != null) {
            require(existing.manifestId == manifestId) { "training run manifest changed" }
            require(existing.outputCheckpointId == outputCheckpointId) { "training output checkpoint id changed" }
            return existing
        }

        val manifest = requireNotNull(getManifest(manifestId)) {
            "distillation manifest unavailable: " + manifestId.value
        }
        validateManifest(manifest)
        require(foundation.getCheckpoint(outputCheckpointId) == null) {
            "output checkpoint id already exists"
        }
        val now = clock()
        val run = NativeTrainingRun(
            id = id,
            manifestId = manifest.id,
            manifestDigest = manifest.canonicalDigest,
            outputCheckpointId = outputCheckpointId,
            status = NativeTrainingRunStatus.PREPARED,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        memory.remember(
            MemoryRecord(
                id = runMemoryId(id),
                kind = RUN_KIND,
                content = NativeTrainingCodec.encodeRun(run),
                importance = 0.94,
                provenance = Provenance(
                    source = "amper-native-training",
                    producer = "native-training-pipeline",
                    confidence = 1.0,
                    parents = setOf(manifestMemoryId(manifest.id))
                ),
                createdAtEpochMs = now
            )
        )
        return run
    }

    override fun getRun(id: NativeTrainingRunId): NativeTrainingRun? =
        memory.get(runMemoryId(id))
            ?.takeIf { it.kind == RUN_KIND }
            ?.let { NativeTrainingCodec.decodeRun(it.content) }

    override fun execute(
        id: NativeTrainingRunId,
        trainer: NativeTrainerPort
    ): NativeTrainingRun {
        val run = requireNotNull(getRun(id)) { "training run unavailable: " + id.value }
        require(run.status == NativeTrainingRunStatus.PREPARED) {
            "only a prepared training run may execute"
        }
        val request = trainingRequest(run)
        val result = trainer.train(request)

        return result.fold(
            onSuccess = { artifact ->
                require(artifact.runId == run.id) { "trainer result run id mismatch" }
                require(artifact.manifestDigest == run.manifestDigest) {
                    "trainer result manifest digest mismatch"
                }
                require(foundation.getCheckpoint(run.outputCheckpointId) == null) {
                    "training output checkpoint already exists"
                }

                val manifest = request.manifest
                val checkpoint = NativeCheckpointLineage(
                    id = run.outputCheckpointId,
                    parentCheckpointId = manifest.parentCheckpointId,
                    contractId = manifest.studentContractId,
                    contractDigest = manifest.studentContractDigest,
                    datasetShardIds = manifest.datasetShardIds,
                    datasetSnapshotDigest = manifest.datasetSnapshotDigest,
                    curriculumId = manifest.curriculumId,
                    curriculumDigest = manifest.curriculumDigest,
                    recipeDigest = manifest.recipe.canonicalDigest,
                    weightArtifactSha256 = artifact.weightArtifactSha256,
                    createdAtEpochMs = clock()
                )
                foundation.putCheckpoint(checkpoint)

                val succeeded = run.copy(
                    status = NativeTrainingRunStatus.SUCCEEDED,
                    updatedAtEpochMs = checkpoint.createdAtEpochMs,
                    weightArtifactSha256 = artifact.weightArtifactSha256,
                    trainingBackendId = artifact.trainingBackendId,
                    examplesSeen = artifact.examplesSeen,
                    completedSteps = artifact.completedSteps,
                    finalLoss = artifact.finalLoss
                )
                persistRun(succeeded)
                succeeded
            },
            onFailure = { failure ->
                val failed = run.copy(
                    status = NativeTrainingRunStatus.FAILED,
                    updatedAtEpochMs = clock(),
                    failureCode = sanitizeFailure(failure)
                )
                persistRun(failed)
                failed
            }
        )
    }

    override fun recordEvaluation(
        checkpointId: NativeCheckpointId,
        evaluation: NativeCheckpointEvaluation
    ): NativeCheckpointEvaluationRecord {
        val run = requireNotNull(successfulRunFor(checkpointId)) {
            "checkpoint was not produced by a successful native training run"
        }
        val manifest = requireNotNull(getManifest(run.manifestId))
        val admission = foundation.admit(
            checkpointId = checkpointId,
            recipe = manifest.recipe,
            evaluation = evaluation
        )
        val record = NativeCheckpointEvaluationRecord(
            checkpointId = checkpointId,
            evaluation = evaluation,
            admission = admission,
            recordedAtEpochMs = clock()
        )
        val memoryId = evaluationMemoryId(checkpointId)
        val existing = getEvaluation(checkpointId)
        if (existing != null) {
            require(existing == record) {
                "native checkpoint evaluation is immutable"
            }
            return existing
        }
        memory.remember(
            MemoryRecord(
                id = memoryId,
                kind = EVALUATION_KIND,
                content = NativeTrainingCodec.encodeEvaluationRecord(record),
                importance = 0.96,
                provenance = Provenance(
                    source = "amper-native-heldout-evaluation",
                    producer = "native-training-pipeline",
                    confidence = 1.0,
                    parents = setOf(MemoryId("native-checkpoint:" + checkpointId.value))
                ),
                createdAtEpochMs = record.recordedAtEpochMs
            )
        )
        return record
    }

    override fun getEvaluation(
        checkpointId: NativeCheckpointId
    ): NativeCheckpointEvaluationRecord? =
        memory.get(evaluationMemoryId(checkpointId))
            ?.takeIf { it.kind == EVALUATION_KIND }
            ?.let { NativeTrainingCodec.decodeEvaluationRecord(it.content) }

    override fun compare(
        candidateCheckpointId: NativeCheckpointId,
        baselineCheckpointId: NativeCheckpointId?
    ): NativeCheckpointComparison {
        val candidate = requireNotNull(getEvaluation(candidateCheckpointId)) {
            "candidate checkpoint has no held-out evaluation"
        }
        val baseline = baselineCheckpointId?.let {
            requireNotNull(getEvaluation(it)) {
                "baseline checkpoint has no held-out evaluation"
            }
        }

        val reasons = mutableListOf<String>()
        if (!candidate.admission.admitted) {
            reasons += "candidate failed foundation admission"
        }
        val deltas = if (baseline == null) {
            emptyList()
        } else {
            metricDeltas(candidate.evaluation, baseline.evaluation).also { values ->
                if (values.any { it < -MAX_METRIC_REGRESSION }) {
                    reasons += "candidate has material held-out regression"
                }
            }
        }
        val noMaterialRegression = reasons.none { it.contains("material held-out regression") }
        val aggregateDelta = if (deltas.isEmpty()) {
            0.0
        } else {
            deltas.average()
        }
        if (baseline == null) {
            reasons += "first admitted AMPER-native checkpoint has no baseline"
        } else if (noMaterialRegression && aggregateDelta > MIN_AGGREGATE_IMPROVEMENT) {
            reasons += "candidate improves aggregate held-out score without material regression"
        } else if (noMaterialRegression) {
            reasons += "candidate is non-regressing but aggregate improvement is insufficient"
        }

        return NativeCheckpointComparison(
            candidateCheckpointId = candidateCheckpointId,
            baselineCheckpointId = baselineCheckpointId,
            candidateEvaluation = candidate.evaluation,
            baselineEvaluation = baseline?.evaluation,
            noMaterialRegression = noMaterialRegression,
            aggregateDelta = aggregateDelta,
            reasons = reasons.distinct()
        )
    }

    override fun promotionCandidate(
        candidateCheckpointId: NativeCheckpointId,
        baselineCheckpointId: NativeCheckpointId?
    ): NativeModelPromotionCandidate {
        val candidateEvaluation = requireNotNull(getEvaluation(candidateCheckpointId))
        val comparison = compare(candidateCheckpointId, baselineCheckpointId)
        val promotable = candidateEvaluation.admission.admitted &&
            comparison.noMaterialRegression &&
            (
                baselineCheckpointId == null ||
                    comparison.aggregateDelta > MIN_AGGREGATE_IMPROVEMENT
                )
        val promotion = NativeModelPromotionCandidate(
            checkpointId = candidateCheckpointId,
            baselineCheckpointId = baselineCheckpointId,
            promotable = promotable,
            comparison = comparison,
            createdAtEpochMs = clock()
        )
        memory.remember(
            MemoryRecord(
                kind = PROMOTION_KIND,
                content = NativeTrainingCodec.encodePromotion(promotion),
                importance = if (promotable) 0.97 else 0.82,
                provenance = Provenance(
                    source = "amper-native-model-comparison",
                    producer = "native-training-pipeline",
                    confidence = 1.0,
                    parents = buildSet {
                        add(MemoryId("native-checkpoint:" + candidateCheckpointId.value))
                        baselineCheckpointId?.let {
                            add(MemoryId("native-checkpoint:" + it.value))
                        }
                    }
                ),
                createdAtEpochMs = promotion.createdAtEpochMs
            )
        )
        return promotion
    }

    private fun validateManifest(manifest: NativeDistillationManifest) {
        val teachers = manifest.teacherSnapshotIds.map { id ->
            requireNotNull(getTeacher(id)) { "teacher snapshot missing: " + id.value }
        }
        require(teachers.all { it.distillationEligible }) {
            "distillation manifest contains teacher with unknown rights"
        }

        val contract = requireNotNull(foundation.getContract(manifest.studentContractId)) {
            "student model contract missing"
        }
        require(contract.ownedByAmper) { "student model contract is not AMPER-owned" }
        require(contract.canonicalDigest == manifest.studentContractDigest) {
            "student model contract digest mismatch"
        }
        require(manifest.target.contextTokens <= contract.maxContextTokens) {
            "mobile target context exceeds student contract"
        }

        val curriculum = requireNotNull(foundation.getCurriculum(manifest.curriculumId)) {
            "native curriculum missing"
        }
        require(curriculum.canonicalDigest == manifest.curriculumDigest) {
            "native curriculum digest mismatch"
        }
        require(manifest.recipe.curriculumDigest == curriculum.canonicalDigest) {
            "training recipe does not bind native curriculum"
        }

        val shards = manifest.datasetShardIds.map { id ->
            requireNotNull(foundation.getDatasetShard(id)) {
                "native dataset shard missing: " + id.value
            }
        }
        require(shards.all { it.trainingEligible }) {
            "distillation manifest contains dataset with unknown training rights"
        }
        require(
            foundation.datasetSnapshotDigest(manifest.datasetShardIds) ==
                manifest.datasetSnapshotDigest
        ) { "native dataset snapshot digest mismatch" }

        manifest.parentCheckpointId?.let { parentId ->
            val parent = requireNotNull(foundation.getCheckpoint(parentId)) {
                "parent native checkpoint missing"
            }
            require(parent.contractId == manifest.studentContractId) {
                "parent checkpoint belongs to a different model contract"
            }
            require(parent.contractDigest == manifest.studentContractDigest) {
                "parent checkpoint contract digest mismatch"
            }
        }

        val requiredCapabilities = curriculum.stages.flatMap { it.capabilities }.toSet()
        require(requiredCapabilities.all { capability ->
            teachers.any { capability in it.capabilities }
        }) { "teacher snapshots do not cover curriculum capabilities" }
        require(requiredCapabilities.all { it in contract.capabilities }) {
            "curriculum requests capability outside student contract"
        }
        require(shards.flatMap { it.targetCapabilities }.toSet().intersect(requiredCapabilities).isNotEmpty()) {
            "dataset snapshot does not target curriculum capabilities"
        }
    }

    private fun trainingRequest(run: NativeTrainingRun): NativeTrainingRequest {
        val manifest = requireNotNull(getManifest(run.manifestId))
        require(manifest.canonicalDigest == run.manifestDigest) {
            "prepared run manifest digest no longer matches"
        }
        validateManifest(manifest)
        return NativeTrainingRequest(
            runId = run.id,
            manifest = manifest,
            teachers = manifest.teacherSnapshotIds.map { requireNotNull(getTeacher(it)) },
            contract = requireNotNull(foundation.getContract(manifest.studentContractId)),
            curriculum = requireNotNull(foundation.getCurriculum(manifest.curriculumId)),
            datasets = manifest.datasetShardIds.map {
                requireNotNull(foundation.getDatasetShard(it))
            },
            parentCheckpoint = manifest.parentCheckpointId?.let {
                requireNotNull(foundation.getCheckpoint(it))
            }
        )
    }

    private fun persistRun(run: NativeTrainingRun) {
        memory.remember(
            MemoryRecord(
                id = runMemoryId(run.id),
                kind = RUN_KIND,
                content = NativeTrainingCodec.encodeRun(run),
                importance = if (run.status == NativeTrainingRunStatus.SUCCEEDED) 0.98 else 0.82,
                provenance = Provenance(
                    source = "amper-native-training",
                    producer = "native-training-pipeline",
                    confidence = 1.0,
                    parents = setOf(manifestMemoryId(run.manifestId))
                ),
                createdAtEpochMs = run.updatedAtEpochMs
            )
        )
    }

    private fun successfulRunFor(checkpointId: NativeCheckpointId): NativeTrainingRun? {
        val query = checkpointId.value
        return memory.recall(query, MAX_RUN_LOOKBACK)
            .asSequence()
            .filter { it.kind == RUN_KIND }
            .mapNotNull { NativeTrainingCodec.decodeRun(it.content) }
            .firstOrNull {
                it.outputCheckpointId == checkpointId &&
                    it.status == NativeTrainingRunStatus.SUCCEEDED
            }
    }

    private fun metricDeltas(
        candidate: NativeCheckpointEvaluation,
        baseline: NativeCheckpointEvaluation
    ): List<Double> = listOf(
        candidate.planningProtocolPassRate - baseline.planningProtocolPassRate,
        candidate.toolContractPassRate - baseline.toolContractPassRate,
        candidate.regressionPassRate - baseline.regressionPassRate,
        candidate.heldoutGeneralizationPassRate - baseline.heldoutGeneralizationPassRate
    )

    private fun sanitizeFailure(failure: Throwable): String {
        val raw = failure::class.java.simpleName.ifBlank { "TrainingFailure" }
        return raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(128)
            .ifBlank { "TrainingFailure" }
    }

    private fun teacherMemoryId(id: NativeTeacherSnapshotId): MemoryId =
        MemoryId("native-teacher:" + id.value)

    private fun manifestMemoryId(id: NativeDistillationManifestId): MemoryId =
        MemoryId("native-distillation:" + id.value)

    private fun runMemoryId(id: NativeTrainingRunId): MemoryId =
        MemoryId("native-training-run:" + id.value)

    private fun evaluationMemoryId(id: NativeCheckpointId): MemoryId =
        MemoryId("native-training-evaluation:" + id.value)

    companion object {
        const val TEACHER_KIND = "native-teacher-snapshot"
        const val MANIFEST_KIND = "native-distillation-manifest"
        const val RUN_KIND = "native-training-run"
        const val EVALUATION_KIND = "native-training-evaluation"
        const val PROMOTION_KIND = "native-model-promotion-candidate"

        const val MAX_METRIC_REGRESSION = 0.01
        const val MIN_AGGREGATE_IMPROVEMENT = 0.005
        private const val MAX_RUN_LOOKBACK = 128
    }
}

private object NativeTrainingCodec {
    fun encodeTeacher(value: NativeTeacherSnapshot): String = listOf(
        "v=1",
        "id=" + enc(value.id.value),
        "model=" + enc(value.modelId.value),
        "sha=" + value.artifactSha256,
        "source=" + enc(value.sourceLabel),
        "rights=" + value.rights.name,
        "capabilities=" + encodeCapabilities(value.capabilities),
        "local=" + value.local,
        "created=" + value.createdAtEpochMs
    ).joinToString(";")

    fun decodeTeacher(content: String): NativeTeacherSnapshot? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId(dec(requireNotNull(f["id"]))),
            modelId = ModelId(dec(requireNotNull(f["model"]))),
            artifactSha256 = requireNotNull(f["sha"]),
            sourceLabel = dec(requireNotNull(f["source"])),
            rights = NativeTeacherRights.valueOf(requireNotNull(f["rights"])),
            capabilities = decodeCapabilities(requireNotNull(f["capabilities"])),
            local = requireNotNull(f["local"]).toBooleanStrict(),
            createdAtEpochMs = requireNotNull(f["created"]).toLong()
        )
    }.getOrNull()

    fun encodeManifest(value: NativeDistillationManifest): String = listOf(
        "v=1",
        "id=" + enc(value.id.value),
        "teachers=" + value.teacherSnapshotIds.joinToString(",") { enc(it.value) },
        "contract=" + enc(value.studentContractId.value),
        "contract_digest=" + value.studentContractDigest,
        "parent=" + (value.parentCheckpointId?.value?.let(::enc) ?: "~"),
        "datasets=" + value.datasetShardIds.joinToString(",") { enc(it.value) },
        "dataset_digest=" + value.datasetSnapshotDigest,
        "curriculum=" + enc(value.curriculumId.value),
        "curriculum_digest=" + value.curriculumDigest,
        "optimizer=" + enc(value.recipe.optimizer),
        "precision=" + enc(value.recipe.precision),
        "sequence=" + value.recipe.maxSequenceTokens,
        "learning_rate=" + enc(value.recipe.learningRate.toString()),
        "recipe_curriculum=" + value.recipe.curriculumDigest,
        "target_format=" + enc(value.target.outputFormat),
        "target_quantization=" + enc(value.target.quantization),
        "target_memory=" + value.target.maxRuntimeMemoryMb,
        "target_context=" + value.target.contextTokens,
        "target_arm64=" + value.target.androidArm64,
        "temperature=" + enc(value.teacherTemperature.toString()),
        "teacher_weight=" + enc(value.teacherLossWeight.toString()),
        "created=" + value.createdAtEpochMs
    ).joinToString(";")

    fun decodeManifest(content: String): NativeDistillationManifest? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        val curriculumDigest = requireNotNull(f["curriculum_digest"])
        NativeDistillationManifest(
            id = NativeDistillationManifestId(dec(requireNotNull(f["id"]))),
            teacherSnapshotIds = decodeList(requireNotNull(f["teachers"]))
                .map(::NativeTeacherSnapshotId),
            studentContractId = NativeModelContractId(dec(requireNotNull(f["contract"]))),
            studentContractDigest = requireNotNull(f["contract_digest"]),
            parentCheckpointId = requireNotNull(f["parent"])
                .takeUnless { it == "~" }
                ?.let { NativeCheckpointId(dec(it)) },
            datasetShardIds = decodeList(requireNotNull(f["datasets"]))
                .map(::NativeDatasetShardId),
            datasetSnapshotDigest = requireNotNull(f["dataset_digest"]),
            curriculumId = NativeCurriculumId(dec(requireNotNull(f["curriculum"]))),
            curriculumDigest = curriculumDigest,
            recipe = NativeTrainingRecipe(
                optimizer = dec(requireNotNull(f["optimizer"])),
                precision = dec(requireNotNull(f["precision"])),
                maxSequenceTokens = requireNotNull(f["sequence"]).toInt(),
                learningRate = dec(requireNotNull(f["learning_rate"])).toDouble(),
                curriculumDigest = requireNotNull(f["recipe_curriculum"])
            ),
            target = NativeMobileTargetProfile(
                outputFormat = dec(requireNotNull(f["target_format"])),
                quantization = dec(requireNotNull(f["target_quantization"])),
                maxRuntimeMemoryMb = requireNotNull(f["target_memory"]).toInt(),
                contextTokens = requireNotNull(f["target_context"]).toInt(),
                androidArm64 = requireNotNull(f["target_arm64"]).toBooleanStrict()
            ),
            teacherTemperature = dec(requireNotNull(f["temperature"])).toDouble(),
            teacherLossWeight = dec(requireNotNull(f["teacher_weight"])).toDouble(),
            createdAtEpochMs = requireNotNull(f["created"]).toLong()
        ).also {
            require(it.recipe.curriculumDigest == curriculumDigest)
        }
    }.getOrNull()

    fun encodeRun(value: NativeTrainingRun): String = listOf(
        "v=1",
        "id=" + enc(value.id.value),
        "manifest=" + enc(value.manifestId.value),
        "manifest_digest=" + value.manifestDigest,
        "checkpoint=" + enc(value.outputCheckpointId.value),
        "status=" + value.status.name,
        "created=" + value.createdAtEpochMs,
        "updated=" + value.updatedAtEpochMs,
        "weights=" + (value.weightArtifactSha256 ?: "~"),
        "backend=" + (value.trainingBackendId?.let(::enc) ?: "~"),
        "examples=" + (value.examplesSeen?.toString() ?: "~"),
        "steps=" + (value.completedSteps?.toString() ?: "~"),
        "loss=" + (value.finalLoss?.toString()?.let(::enc) ?: "~"),
        "failure=" + (value.failureCode?.let(::enc) ?: "~")
    ).joinToString(";")

    fun decodeRun(content: String): NativeTrainingRun? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        NativeTrainingRun(
            id = NativeTrainingRunId(dec(requireNotNull(f["id"]))),
            manifestId = NativeDistillationManifestId(dec(requireNotNull(f["manifest"]))),
            manifestDigest = requireNotNull(f["manifest_digest"]),
            outputCheckpointId = NativeCheckpointId(dec(requireNotNull(f["checkpoint"]))),
            status = NativeTrainingRunStatus.valueOf(requireNotNull(f["status"])),
            createdAtEpochMs = requireNotNull(f["created"]).toLong(),
            updatedAtEpochMs = requireNotNull(f["updated"]).toLong(),
            weightArtifactSha256 = requireNotNull(f["weights"]).takeUnless { it == "~" },
            trainingBackendId = requireNotNull(f["backend"]).takeUnless { it == "~" }?.let(::dec),
            examplesSeen = requireNotNull(f["examples"]).takeUnless { it == "~" }?.toLong(),
            completedSteps = requireNotNull(f["steps"]).takeUnless { it == "~" }?.toLong(),
            finalLoss = requireNotNull(f["loss"]).takeUnless { it == "~" }?.let { dec(it).toDouble() },
            failureCode = requireNotNull(f["failure"]).takeUnless { it == "~" }?.let(::dec)
        )
    }.getOrNull()

    fun encodeEvaluationRecord(value: NativeCheckpointEvaluationRecord): String = listOf(
        "v=1",
        "checkpoint=" + enc(value.checkpointId.value),
        "planning_rate=" + enc(value.evaluation.planningProtocolPassRate.toString()),
        "tool_rate=" + enc(value.evaluation.toolContractPassRate.toString()),
        "regression_rate=" + enc(value.evaluation.regressionPassRate.toString()),
        "generalization_rate=" + enc(value.evaluation.heldoutGeneralizationPassRate.toString()),
        "planning_samples=" + value.evaluation.planningSamples,
        "tool_samples=" + value.evaluation.toolContractSamples,
        "regression_samples=" + value.evaluation.regressionSamples,
        "generalization_samples=" + value.evaluation.heldoutGeneralizationSamples,
        "admission_status=" + value.admission.status.name,
        "admission_reasons=" + value.admission.reasons.joinToString(",") { enc(it) },
        "admission_time=" + value.admission.evaluatedAtEpochMs,
        "recorded=" + value.recordedAtEpochMs
    ).joinToString(";")

    fun decodeEvaluationRecord(content: String): NativeCheckpointEvaluationRecord? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        val checkpointId = NativeCheckpointId(dec(requireNotNull(f["checkpoint"])))
        NativeCheckpointEvaluationRecord(
            checkpointId = checkpointId,
            evaluation = NativeCheckpointEvaluation(
                planningProtocolPassRate = dec(requireNotNull(f["planning_rate"])).toDouble(),
                toolContractPassRate = dec(requireNotNull(f["tool_rate"])).toDouble(),
                regressionPassRate = dec(requireNotNull(f["regression_rate"])).toDouble(),
                heldoutGeneralizationPassRate =
                    dec(requireNotNull(f["generalization_rate"])).toDouble(),
                planningSamples = requireNotNull(f["planning_samples"]).toInt(),
                toolContractSamples = requireNotNull(f["tool_samples"]).toInt(),
                regressionSamples = requireNotNull(f["regression_samples"]).toInt(),
                heldoutGeneralizationSamples =
                    requireNotNull(f["generalization_samples"]).toInt()
            ),
            admission = NativeCheckpointAdmission(
                checkpointId = checkpointId,
                status = NativeCheckpointAdmissionStatus.valueOf(
                    requireNotNull(f["admission_status"])
                ),
                reasons = decodeList(requireNotNull(f["admission_reasons"])),
                evaluatedAtEpochMs = requireNotNull(f["admission_time"]).toLong()
            ),
            recordedAtEpochMs = requireNotNull(f["recorded"]).toLong()
        )
    }.getOrNull()

    fun encodePromotion(value: NativeModelPromotionCandidate): String = listOf(
        "v=1",
        "checkpoint=" + enc(value.checkpointId.value),
        "baseline=" + (value.baselineCheckpointId?.value?.let(::enc) ?: "~"),
        "promotable=" + value.promotable,
        "no_regression=" + value.comparison.noMaterialRegression,
        "aggregate_delta=" + enc(value.comparison.aggregateDelta.toString()),
        "created=" + value.createdAtEpochMs
    ).joinToString(";")

    private fun encodeCapabilities(values: Set<CapabilityId>): String =
        values.map { it.value }.sorted().joinToString(".") { enc(it) }

    private fun decodeCapabilities(value: String): Set<CapabilityId> =
        value.split('.').filter { it.isNotBlank() }.map { CapabilityId(dec(it)) }.toSet()

    private fun decodeList(value: String): List<String> =
        value.split(',').filter { it.isNotBlank() }.map(::dec)

    private fun fields(content: String): Map<String, String> =
        content.split(';').associate { field ->
            val split = field.indexOf('=')
            require(split > 0)
            field.substring(0, split) to field.substring(split + 1)
        }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}

private fun trainingSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
