package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@JvmInline
value class NativeModelContractId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class NativeDatasetShardId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class NativeCurriculumId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class NativeCheckpointId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class NativeDatasetRights {
    USER_OWNED,
    GENERATED_INTERNAL,
    PERMISSIVE_EXTERNAL,
    UNKNOWN
}

data class AmperNativeModelContract(
    val id: NativeModelContractId,
    val familyVersion: Int,
    val parameterCount: Long,
    val layerCount: Int,
    val hiddenSize: Int,
    val maxContextTokens: Int,
    val capabilities: Set<CapabilityId>,
    val ownedByAmper: Boolean = true
) {
    init {
        require(familyVersion > 0)
        require(parameterCount > 0L)
        require(layerCount > 0)
        require(hiddenSize > 0)
        require(maxContextTokens in 512..1_048_576)
        require(capabilities.isNotEmpty())
    }

    val canonicalDigest: String
        get() = nativeSha256(
            listOf(
                id.value,
                familyVersion.toString(),
                parameterCount.toString(),
                layerCount.toString(),
                hiddenSize.toString(),
                maxContextTokens.toString(),
                capabilities.map { it.value }.sorted().joinToString(","),
                ownedByAmper.toString()
            ).joinToString("|")
        )

    val authorityBearing: Boolean
        get() = false
}

data class NativeDatasetShardManifest(
    val id: NativeDatasetShardId,
    val sha256: String,
    val sourceLabel: String,
    val rights: NativeDatasetRights,
    val exampleCount: Long,
    val byteCount: Long,
    val targetCapabilities: Set<CapabilityId>,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(sha256.matches(SHA256))
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 256)
        require(exampleCount > 0L)
        require(byteCount > 0L)
        require(targetCapabilities.isNotEmpty())
        require(createdAtEpochMs >= 0L)
    }

    val trainingEligible: Boolean
        get() = rights != NativeDatasetRights.UNKNOWN

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class NativeCurriculumStage(
    val index: Int,
    val name: String,
    val capabilities: Set<CapabilityId>,
    val minExamples: Long,
    val weight: Double
) {
    init {
        require(index > 0)
        require(name.isNotBlank() && name.length <= 128)
        require(capabilities.isNotEmpty())
        require(minExamples > 0L)
        require(weight in 0.0..1.0)
    }
}

data class NativeCurriculumManifest(
    val id: NativeCurriculumId,
    val stages: List<NativeCurriculumStage>
) {
    init {
        require(stages.isNotEmpty())
        require(stages.size <= MAX_STAGES)
        require(stages.map { it.index } == (1..stages.size).toList())
    }

    val canonicalDigest: String
        get() = nativeSha256(
            stages.joinToString("||") { stage ->
                listOf(
                    stage.index.toString(),
                    stage.name,
                    stage.capabilities.map { it.value }.sorted().joinToString(","),
                    stage.minExamples.toString(),
                    stage.weight.toString()
                ).joinToString("|")
            }
        )

    companion object {
        const val MAX_STAGES = 16
    }
}

data class NativeTrainingRecipe(
    val optimizer: String,
    val precision: String,
    val maxSequenceTokens: Int,
    val learningRate: Double,
    val curriculumDigest: String
) {
    init {
        require(optimizer.isNotBlank() && optimizer.length <= 64)
        require(precision.isNotBlank() && precision.length <= 32)
        require(maxSequenceTokens in 128..1_048_576)
        require(learningRate > 0.0 && learningRate <= 1.0)
        require(curriculumDigest.matches(SHA256))
    }

    val canonicalDigest: String
        get() = nativeSha256(
            listOf(
                optimizer,
                precision,
                maxSequenceTokens.toString(),
                learningRate.toString(),
                curriculumDigest
            ).joinToString("|")
        )

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class NativeCheckpointLineage(
    val id: NativeCheckpointId,
    val parentCheckpointId: NativeCheckpointId?,
    val contractId: NativeModelContractId,
    val contractDigest: String,
    val datasetShardIds: List<NativeDatasetShardId>,
    val datasetSnapshotDigest: String,
    val curriculumId: NativeCurriculumId,
    val curriculumDigest: String,
    val recipeDigest: String,
    val weightArtifactSha256: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(contractDigest.matches(SHA256))
        require(datasetShardIds.isNotEmpty())
        require(datasetShardIds.distinct().size == datasetShardIds.size)
        require(datasetSnapshotDigest.matches(SHA256))
        require(curriculumDigest.matches(SHA256))
        require(recipeDigest.matches(SHA256))
        require(weightArtifactSha256.matches(SHA256))
        require(createdAtEpochMs >= 0L)
        require(parentCheckpointId?.value != id.value)
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class NativeCheckpointEvaluation(
    val planningProtocolPassRate: Double,
    val toolContractPassRate: Double,
    val regressionPassRate: Double,
    val heldoutGeneralizationPassRate: Double,
    val planningSamples: Int,
    val toolContractSamples: Int,
    val regressionSamples: Int,
    val heldoutGeneralizationSamples: Int
) {
    init {
        require(
            listOf(
                planningProtocolPassRate,
                toolContractPassRate,
                regressionPassRate,
                heldoutGeneralizationPassRate
            ).all { it in 0.0..1.0 }
        )
        require(
            listOf(
                planningSamples,
                toolContractSamples,
                regressionSamples,
                heldoutGeneralizationSamples
            ).all { it >= 0 }
        )
    }
}

enum class NativeCheckpointAdmissionStatus {
    ADMITTED,
    REJECTED
}

data class NativeCheckpointAdmission(
    val checkpointId: NativeCheckpointId,
    val status: NativeCheckpointAdmissionStatus,
    val reasons: List<String>,
    val evaluatedAtEpochMs: Long
) {
    init {
        require(reasons.isNotEmpty())
        require(evaluatedAtEpochMs >= 0L)
    }

    val admitted: Boolean
        get() = status == NativeCheckpointAdmissionStatus.ADMITTED

    val authorityBearing: Boolean
        get() = false
}

interface NativeModelFoundation {
    fun putContract(contract: AmperNativeModelContract)
    fun getContract(id: NativeModelContractId): AmperNativeModelContract?

    fun putDatasetShard(shard: NativeDatasetShardManifest)
    fun getDatasetShard(id: NativeDatasetShardId): NativeDatasetShardManifest?

    fun putCurriculum(curriculum: NativeCurriculumManifest)
    fun getCurriculum(id: NativeCurriculumId): NativeCurriculumManifest?

    fun putCheckpoint(checkpoint: NativeCheckpointLineage)
    fun getCheckpoint(id: NativeCheckpointId): NativeCheckpointLineage?

    fun datasetSnapshotDigest(ids: List<NativeDatasetShardId>): String

    fun admit(
        checkpointId: NativeCheckpointId,
        recipe: NativeTrainingRecipe,
        evaluation: NativeCheckpointEvaluation
    ): NativeCheckpointAdmission
}

/**
 * Phase211-215 foundation for an AMPER-owned native model family.
 *
 * Phase211 defines an explicit AMPER-owned architecture/capability contract. The contract is model
 * identity data only and cannot grant external/tool authority.
 *
 * Phase212 tracks dataset shards by content digest, declared source label and rights classification.
 * UNKNOWN rights are retained for provenance but fail closed for native checkpoint admission.
 *
 * Phase213 freezes a bounded capability curriculum and canonical digest so a checkpoint can be tied
 * to the exact learning program that produced it.
 *
 * Phase214 stores immutable checkpoint lineage across parent checkpoint, contract, dataset snapshot,
 * curriculum, recipe and weight artifact digests.
 *
 * Phase215 admits a checkpoint only when lineage is internally consistent and held-out evaluation
 * clears fixed gates. Admission does NOT register the checkpoint into the live ModelRegistry; native
 * inference routing remains a later phase.
 */
class MemoryBackedNativeModelFoundation(
    private val memory: MemoryOs,
    private val clock: () -> Long = System::currentTimeMillis
) : NativeModelFoundation {
    override fun putContract(contract: AmperNativeModelContract) {
        memory.remember(
            MemoryRecord(
                id = MemoryId("native-contract:" + contract.id.value),
                kind = CONTRACT_KIND,
                content = NativeFoundationCodec.encodeContract(contract),
                importance = 0.96,
                provenance = Provenance(
                    source = "amper-native-model-foundation",
                    producer = "native-contract-store",
                    confidence = 1.0
                )
            )
        )
    }

    override fun getContract(id: NativeModelContractId): AmperNativeModelContract? =
        memory.get(MemoryId("native-contract:" + id.value))
            ?.takeIf { it.kind == CONTRACT_KIND }
            ?.let { NativeFoundationCodec.decodeContract(it.content) }

    override fun putDatasetShard(shard: NativeDatasetShardManifest) {
        memory.remember(
            MemoryRecord(
                id = MemoryId("native-dataset:" + shard.id.value),
                kind = DATASET_KIND,
                content = NativeFoundationCodec.encodeDataset(shard),
                importance = 0.90,
                provenance = Provenance(
                    source = "amper-native-dataset-manifest",
                    producer = "native-dataset-store",
                    confidence = if (shard.trainingEligible) 1.0 else 0.5
                ),
                createdAtEpochMs = shard.createdAtEpochMs
            )
        )
    }

    override fun getDatasetShard(id: NativeDatasetShardId): NativeDatasetShardManifest? =
        memory.get(MemoryId("native-dataset:" + id.value))
            ?.takeIf { it.kind == DATASET_KIND }
            ?.let { NativeFoundationCodec.decodeDataset(it.content) }

    override fun putCurriculum(curriculum: NativeCurriculumManifest) {
        memory.remember(
            MemoryRecord(
                id = MemoryId("native-curriculum:" + curriculum.id.value),
                kind = CURRICULUM_KIND,
                content = NativeFoundationCodec.encodeCurriculum(curriculum),
                importance = 0.94,
                provenance = Provenance(
                    source = "amper-native-curriculum",
                    producer = "native-curriculum-store",
                    confidence = 1.0
                )
            )
        )
    }

    override fun getCurriculum(id: NativeCurriculumId): NativeCurriculumManifest? =
        memory.get(MemoryId("native-curriculum:" + id.value))
            ?.takeIf { it.kind == CURRICULUM_KIND }
            ?.let { NativeFoundationCodec.decodeCurriculum(it.content) }

    override fun putCheckpoint(checkpoint: NativeCheckpointLineage) {
        memory.rememberIfAbsent(
            MemoryRecord(
                id = MemoryId("native-checkpoint:" + checkpoint.id.value),
                kind = CHECKPOINT_KIND,
                content = NativeFoundationCodec.encodeCheckpoint(checkpoint),
                importance = 0.98,
                provenance = Provenance(
                    source = "amper-native-checkpoint-lineage",
                    producer = "native-checkpoint-store",
                    confidence = 1.0,
                    parents = buildSet {
                        checkpoint.parentCheckpointId?.let {
                            add(MemoryId("native-checkpoint:" + it.value))
                        }
                        add(MemoryId("native-contract:" + checkpoint.contractId.value))
                        add(MemoryId("native-curriculum:" + checkpoint.curriculumId.value))
                        checkpoint.datasetShardIds.forEach {
                            add(MemoryId("native-dataset:" + it.value))
                        }
                    }
                ),
                createdAtEpochMs = checkpoint.createdAtEpochMs
            )
        ).also { inserted ->
            if (!inserted) {
                val existing = requireNotNull(getCheckpoint(checkpoint.id)) {
                    "native checkpoint id exists with malformed lineage"
                }
                require(existing == checkpoint) {
                    "native checkpoint lineage is immutable"
                }
            }
        }
    }

    override fun getCheckpoint(id: NativeCheckpointId): NativeCheckpointLineage? =
        memory.get(MemoryId("native-checkpoint:" + id.value))
            ?.takeIf { it.kind == CHECKPOINT_KIND }
            ?.let { NativeFoundationCodec.decodeCheckpoint(it.content) }

    override fun datasetSnapshotDigest(ids: List<NativeDatasetShardId>): String {
        require(ids.isNotEmpty())
        require(ids.distinct().size == ids.size)
        val shards = ids.map { id ->
            requireNotNull(getDatasetShard(id)) { "dataset shard missing: " + id.value }
        }
        return nativeSha256(
            shards.sortedBy { it.id.value }.joinToString("||") { shard ->
                listOf(
                    shard.id.value,
                    shard.sha256,
                    shard.rights.name,
                    shard.exampleCount.toString(),
                    shard.byteCount.toString(),
                    shard.targetCapabilities.map { it.value }.sorted().joinToString(",")
                ).joinToString("|")
            }
        )
    }

    override fun admit(
        checkpointId: NativeCheckpointId,
        recipe: NativeTrainingRecipe,
        evaluation: NativeCheckpointEvaluation
    ): NativeCheckpointAdmission {
        val checkpoint = getCheckpoint(checkpointId)
        val reasons = mutableListOf<String>()

        if (checkpoint == null) {
            reasons += "checkpoint lineage missing"
        } else {
            val contract = getContract(checkpoint.contractId)
            if (contract == null) {
                reasons += "model contract missing"
            } else {
                if (!contract.ownedByAmper) reasons += "model contract is not AMPER-owned"
                if (contract.canonicalDigest != checkpoint.contractDigest) {
                    reasons += "model contract digest mismatch"
                }
            }

            val curriculum = getCurriculum(checkpoint.curriculumId)
            if (curriculum == null) {
                reasons += "curriculum missing"
            } else {
                if (curriculum.canonicalDigest != checkpoint.curriculumDigest) {
                    reasons += "curriculum digest mismatch"
                }
                if (recipe.curriculumDigest != curriculum.canonicalDigest) {
                    reasons += "training recipe references a different curriculum"
                }
            }

            val shards = checkpoint.datasetShardIds.mapNotNull(::getDatasetShard)
            if (shards.size != checkpoint.datasetShardIds.size) {
                reasons += "dataset lineage is incomplete"
            } else {
                if (shards.any { !it.trainingEligible }) {
                    reasons += "dataset contains shard with unknown training rights"
                }
                val actualDatasetDigest = runCatching {
                    datasetSnapshotDigest(checkpoint.datasetShardIds)
                }.getOrNull()
                if (actualDatasetDigest != checkpoint.datasetSnapshotDigest) {
                    reasons += "dataset snapshot digest mismatch"
                }
            }

            if (recipe.canonicalDigest != checkpoint.recipeDigest) {
                reasons += "training recipe digest mismatch"
            }

            checkpoint.parentCheckpointId?.let { parentId ->
                if (getCheckpoint(parentId) == null) {
                    reasons += "parent checkpoint lineage missing"
                }
            }

            if (evaluation.planningSamples < MIN_EVALUATION_SAMPLES ||
                evaluation.planningProtocolPassRate < MIN_PLANNING_PASS_RATE
            ) {
                reasons += "planning protocol evaluation below admission gate"
            }
            if (evaluation.toolContractSamples < MIN_EVALUATION_SAMPLES ||
                evaluation.toolContractPassRate < MIN_TOOL_CONTRACT_PASS_RATE
            ) {
                reasons += "tool-contract evaluation below admission gate"
            }
            if (evaluation.regressionSamples < MIN_EVALUATION_SAMPLES ||
                evaluation.regressionPassRate < MIN_REGRESSION_PASS_RATE
            ) {
                reasons += "regression evaluation below admission gate"
            }
            if (evaluation.heldoutGeneralizationSamples < MIN_EVALUATION_SAMPLES ||
                evaluation.heldoutGeneralizationPassRate < MIN_GENERALIZATION_PASS_RATE
            ) {
                reasons += "held-out generalization evaluation below admission gate"
            }
        }

        val admitted = reasons.isEmpty()
        val finalReasons = if (admitted) {
            listOf("checkpoint satisfies native-model foundation admission gates")
        } else {
            reasons.distinct()
        }
        val admission = NativeCheckpointAdmission(
            checkpointId = checkpointId,
            status = if (admitted) {
                NativeCheckpointAdmissionStatus.ADMITTED
            } else {
                NativeCheckpointAdmissionStatus.REJECTED
            },
            reasons = finalReasons,
            evaluatedAtEpochMs = clock()
        )
        memory.remember(
            MemoryRecord(
                kind = ADMISSION_KIND,
                content = NativeFoundationCodec.encodeAdmission(admission),
                importance = if (admitted) 0.95 else 0.82,
                provenance = Provenance(
                    source = "amper-native-checkpoint-admission",
                    producer = "native-model-foundation",
                    confidence = 1.0,
                    parents = checkpoint?.let {
                        setOf(MemoryId("native-checkpoint:" + it.id.value))
                    }.orEmpty()
                ),
                createdAtEpochMs = admission.evaluatedAtEpochMs
            )
        )
        return admission
    }

    companion object {
        const val CONTRACT_KIND = "native-model-contract"
        const val DATASET_KIND = "native-dataset-shard"
        const val CURRICULUM_KIND = "native-curriculum"
        const val CHECKPOINT_KIND = "native-checkpoint-lineage"
        const val ADMISSION_KIND = "native-checkpoint-admission"

        const val MIN_EVALUATION_SAMPLES = 32
        const val MIN_PLANNING_PASS_RATE = 0.98
        const val MIN_TOOL_CONTRACT_PASS_RATE = 0.98
        const val MIN_REGRESSION_PASS_RATE = 0.95
        const val MIN_GENERALIZATION_PASS_RATE = 0.85
    }
}

private object NativeFoundationCodec {
    fun encodeContract(value: AmperNativeModelContract): String = listOf(
        "v=1",
        "id=" + enc(value.id.value),
        "family=" + value.familyVersion,
        "parameters=" + value.parameterCount,
        "layers=" + value.layerCount,
        "hidden=" + value.hiddenSize,
        "context=" + value.maxContextTokens,
        "capabilities=" + encodeCapabilities(value.capabilities),
        "owned=" + value.ownedByAmper
    ).joinToString(";")

    fun decodeContract(content: String): AmperNativeModelContract? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        AmperNativeModelContract(
            id = NativeModelContractId(dec(requireNotNull(f["id"]))),
            familyVersion = requireNotNull(f["family"]).toInt(),
            parameterCount = requireNotNull(f["parameters"]).toLong(),
            layerCount = requireNotNull(f["layers"]).toInt(),
            hiddenSize = requireNotNull(f["hidden"]).toInt(),
            maxContextTokens = requireNotNull(f["context"]).toInt(),
            capabilities = decodeCapabilities(requireNotNull(f["capabilities"])),
            ownedByAmper = requireNotNull(f["owned"]).toBooleanStrict()
        )
    }.getOrNull()

    fun encodeDataset(value: NativeDatasetShardManifest): String = listOf(
        "v=1",
        "id=" + enc(value.id.value),
        "sha=" + value.sha256,
        "source=" + enc(value.sourceLabel),
        "rights=" + value.rights.name,
        "examples=" + value.exampleCount,
        "bytes=" + value.byteCount,
        "capabilities=" + encodeCapabilities(value.targetCapabilities),
        "created=" + value.createdAtEpochMs
    ).joinToString(";")

    fun decodeDataset(content: String): NativeDatasetShardManifest? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        NativeDatasetShardManifest(
            id = NativeDatasetShardId(dec(requireNotNull(f["id"]))),
            sha256 = requireNotNull(f["sha"]),
            sourceLabel = dec(requireNotNull(f["source"])),
            rights = NativeDatasetRights.valueOf(requireNotNull(f["rights"])),
            exampleCount = requireNotNull(f["examples"]).toLong(),
            byteCount = requireNotNull(f["bytes"]).toLong(),
            targetCapabilities = decodeCapabilities(requireNotNull(f["capabilities"])),
            createdAtEpochMs = requireNotNull(f["created"]).toLong()
        )
    }.getOrNull()

    fun encodeCurriculum(value: NativeCurriculumManifest): String = buildString {
        append("v=1;id=")
        append(enc(value.id.value))
        append(";stages=")
        append(
            value.stages.joinToString(",") { stage ->
                listOf(
                    stage.index.toString(),
                    enc(stage.name),
                    encodeCapabilities(stage.capabilities),
                    stage.minExamples.toString(),
                    stage.weight.toString()
                ).joinToString("~")
            }
        )
    }

    fun decodeCurriculum(content: String): NativeCurriculumManifest? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        val stages = requireNotNull(f["stages"]).split(',').map { encoded ->
            val p = encoded.split('~')
            require(p.size == 5)
            NativeCurriculumStage(
                index = p[0].toInt(),
                name = dec(p[1]),
                capabilities = decodeCapabilities(p[2]),
                minExamples = p[3].toLong(),
                weight = p[4].toDouble()
            )
        }
        NativeCurriculumManifest(
            id = NativeCurriculumId(dec(requireNotNull(f["id"]))),
            stages = stages
        )
    }.getOrNull()

    fun encodeCheckpoint(value: NativeCheckpointLineage): String = listOf(
        "v=1",
        "id=" + enc(value.id.value),
        "parent=" + (value.parentCheckpointId?.value?.let(::enc) ?: "~"),
        "contract=" + enc(value.contractId.value),
        "contract_digest=" + value.contractDigest,
        "datasets=" + value.datasetShardIds.joinToString(",") { enc(it.value) },
        "dataset_digest=" + value.datasetSnapshotDigest,
        "curriculum=" + enc(value.curriculumId.value),
        "curriculum_digest=" + value.curriculumDigest,
        "recipe_digest=" + value.recipeDigest,
        "weights=" + value.weightArtifactSha256,
        "created=" + value.createdAtEpochMs
    ).joinToString(";")

    fun decodeCheckpoint(content: String): NativeCheckpointLineage? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        NativeCheckpointLineage(
            id = NativeCheckpointId(dec(requireNotNull(f["id"]))),
            parentCheckpointId = requireNotNull(f["parent"])
                .takeUnless { it == "~" }
                ?.let { NativeCheckpointId(dec(it)) },
            contractId = NativeModelContractId(dec(requireNotNull(f["contract"]))),
            contractDigest = requireNotNull(f["contract_digest"]),
            datasetShardIds = requireNotNull(f["datasets"])
                .split(',')
                .filter { it.isNotBlank() }
                .map { NativeDatasetShardId(dec(it)) },
            datasetSnapshotDigest = requireNotNull(f["dataset_digest"]),
            curriculumId = NativeCurriculumId(dec(requireNotNull(f["curriculum"]))),
            curriculumDigest = requireNotNull(f["curriculum_digest"]),
            recipeDigest = requireNotNull(f["recipe_digest"]),
            weightArtifactSha256 = requireNotNull(f["weights"]),
            createdAtEpochMs = requireNotNull(f["created"]).toLong()
        )
    }.getOrNull()

    fun encodeAdmission(value: NativeCheckpointAdmission): String = listOf(
        "v=1",
        "checkpoint=" + enc(value.checkpointId.value),
        "status=" + value.status.name,
        "reasons=" + value.reasons.joinToString(",") { enc(it) },
        "evaluated=" + value.evaluatedAtEpochMs
    ).joinToString(";")

    private fun encodeCapabilities(values: Set<CapabilityId>): String =
        values.map { it.value }.sorted().joinToString(".") { enc(it) }

    private fun decodeCapabilities(value: String): Set<CapabilityId> =
        value.split('.')
            .filter { it.isNotBlank() }
            .map { CapabilityId(dec(it)) }
            .toSet()

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

private fun nativeSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }