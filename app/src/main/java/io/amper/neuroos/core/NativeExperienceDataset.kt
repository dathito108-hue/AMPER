package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@JvmInline
value class NativeExperienceExampleId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class NativeExperienceTrainingExample(
    val id: NativeExperienceExampleId,
    val goalFingerprint: Set<String>,
    val strategy: StrategySignature,
    val verificationConfidence: Double,
    val observedAtEpochMs: Long
) {
    init {
        require(goalFingerprint.isNotEmpty())
        require(goalFingerprint.size <= GoalOutcomeFingerprint.MAX_TERMS)
        require(goalFingerprint.all { it.matches(HASHED_TERM) })
        require(strategy.capabilities.isNotEmpty())
        require(
            verificationConfidence >= GoalSatisfactionProtocol.MIN_SATISFIED_CONFIDENCE &&
                verificationConfidence <= 1.0
        )
        require(observedAtEpochMs >= 0L)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val HASHED_TERM = Regex("[0-9a-f]{16}")
    }
}

data class NativeExperienceDatasetShard(
    val manifest: NativeDatasetShardManifest,
    val exampleIds: List<NativeExperienceExampleId>,
    val payload: String
) {
    init {
        require(manifest.rights == NativeDatasetRights.GENERATED_INTERNAL)
        require(exampleIds.isNotEmpty())
        require(exampleIds.distinct().size == exampleIds.size)
        require(manifest.exampleCount == exampleIds.size.toLong())
        require(manifest.byteCount == payload.toByteArray(StandardCharsets.UTF_8).size.toLong())
        require(manifest.sha256 == nativeExperienceSha256(payload))
    }

    val authorityBearing: Boolean
        get() = false
}

interface NativeExperienceDatasetStore {
    fun observeVerified(
        plan: SovereignPlan,
        verificationConfidence: Double,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): NativeExperienceTrainingExample

    fun getExample(id: NativeExperienceExampleId): NativeExperienceTrainingExample?

    fun recentExamples(limit: Int = 64): List<NativeExperienceTrainingExample>

    fun materializeShard(
        id: NativeDatasetShardId,
        minExamples: Int = 2,
        limit: Int = 256
    ): NativeExperienceDatasetShard

    fun materializeShardFromExamples(
        id: NativeDatasetShardId,
        exampleIds: List<NativeExperienceExampleId>
    ): NativeExperienceDatasetShard =
        error("explicit native-experience shard selection is unavailable")

    fun getShard(id: NativeDatasetShardId): NativeExperienceDatasetShard?
}

/**
 * Phase396-400 verified-governed-experience bridge for AMPER-owned native-model training.
 *
 * Raw goals, reasons, tool inputs, tool outputs, approvals and tool ids never enter the dataset.
 * The retained training signal is only a hashed goal fingerprint, an ordered capability strategy
 * and the verified-satisfaction confidence. Materialized shards are immutable GENERATED_INTERNAL
 * datasets registered through the existing NativeModelFoundation.
 */
class MemoryBackedNativeExperienceDatasetStore(
    private val memory: MemoryOs,
    private val foundation: NativeModelFoundation
) : NativeExperienceDatasetStore {
    @Synchronized
    override fun observeVerified(
        plan: SovereignPlan,
        verificationConfidence: Double,
        observedAtEpochMs: Long
    ): NativeExperienceTrainingExample {
        GoalOutcomeSemantics.validate(plan, GoalOutcomeEvidenceKind.VERIFIED_SUCCESS)
        require(
            verificationConfidence >= GoalSatisfactionProtocol.MIN_SATISFIED_CONFIDENCE &&
                verificationConfidence <= 1.0
        )
        require(observedAtEpochMs >= 0L)

        val fingerprint = GoalOutcomeFingerprint.of(plan.goal)
        val strategy = StrategySignature.from(plan)
        val id = NativeExperienceExampleId(
            "verified:" + nativeExperienceSha256(plan.id.value)
        )
        getExample(id)?.let { existing ->
            require(existing.goalFingerprint == fingerprint) {
                "verified native-experience goal fingerprint changed"
            }
            require(existing.strategy == strategy) {
                "verified native-experience strategy changed"
            }
            require(existing.verificationConfidence == verificationConfidence) {
                "verified native-experience confidence changed"
            }
            return existing
        }

        val example = NativeExperienceTrainingExample(
            id = id,
            goalFingerprint = fingerprint,
            strategy = strategy,
            verificationConfidence = verificationConfidence,
            observedAtEpochMs = observedAtEpochMs
        )
        memory.transaction {
            remember(
                MemoryRecord(
                    id = exampleMemoryId(id),
                    kind = EXAMPLE_KIND,
                    content = NativeExperienceDatasetCodec.encodeExample(example),
                    importance = 0.86,
                    provenance = Provenance(
                        source = "verified-governed-experience",
                        producer = "native-experience-dataset",
                        confidence = verificationConfidence
                    ),
                    createdAtEpochMs = observedAtEpochMs
                )
            )
            updateExampleIndexLocked(id, observedAtEpochMs)
        }
        return example
    }

    override fun getExample(id: NativeExperienceExampleId): NativeExperienceTrainingExample? =
        memory.get(exampleMemoryId(id))
            ?.takeIf { it.kind == EXAMPLE_KIND }
            ?.let { NativeExperienceDatasetCodec.decodeExample(it.content) }
            ?.takeIf { it.id == id }

    override fun recentExamples(limit: Int): List<NativeExperienceTrainingExample> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.transaction {
            decodeExampleIndex(get(EXAMPLE_INDEX_ID)?.content)
                .asReversed()
                .asSequence()
                .mapNotNull { id ->
                    this@MemoryBackedNativeExperienceDatasetStore.getExample(id)
                }
                .take(limit)
                .toList()
        }
    }

    @Synchronized
    override fun materializeShard(
        id: NativeDatasetShardId,
        minExamples: Int,
        limit: Int
    ): NativeExperienceDatasetShard {
        require(minExamples > 0)
        require(limit in minExamples..MAX_SHARD_EXAMPLES)
        getShard(id)?.let { return it }
        val examples = recentExamples(limit)
            .sortedBy { it.id.value }
        require(examples.size >= minExamples) {
            "insufficient verified native-experience examples"
        }
        return materializeShardFromExamples(
            id = id,
            exampleIds = examples.map { it.id }
        )
    }

    @Synchronized
    override fun materializeShardFromExamples(
        id: NativeDatasetShardId,
        exampleIds: List<NativeExperienceExampleId>
    ): NativeExperienceDatasetShard {
        require(exampleIds.isNotEmpty())
        require(exampleIds.size <= MAX_SHARD_EXAMPLES)
        require(exampleIds.distinct().size == exampleIds.size)
        val normalizedIds = exampleIds.sortedBy { it.value }

        getShard(id)?.let { existing ->
            require(existing.exampleIds == normalizedIds) {
                "native experience shard id is already bound to different examples"
            }
            return existing
        }
        require(foundation.getDatasetShard(id) == null) {
            "native dataset id already belongs to another dataset"
        }

        val examples = normalizedIds.map { exampleId ->
            requireNotNull(getExample(exampleId)) {
                "native experience shard references missing example: " + exampleId.value
            }
        }
        val payload = examples.joinToString(
            separator = "\n",
            transform = NativeExperienceDatasetCodec::trainingLine
        )
        val capabilities = examples.flatMap { it.strategy.capabilities }.toSet()
        val createdAt = examples.maxOf { it.observedAtEpochMs }
        val manifest = NativeDatasetShardManifest(
            id = id,
            sha256 = nativeExperienceSha256(payload),
            sourceLabel = "AMPER verified governed experience",
            rights = NativeDatasetRights.GENERATED_INTERNAL,
            exampleCount = examples.size.toLong(),
            byteCount = payload.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            targetCapabilities = capabilities,
            createdAtEpochMs = createdAt
        )
        val shard = NativeExperienceDatasetShard(
            manifest = manifest,
            exampleIds = normalizedIds,
            payload = payload
        )
        memory.transaction {
            foundation.putDatasetShard(manifest)
            remember(
                MemoryRecord(
                    id = shardMemoryId(id),
                    kind = SHARD_KIND,
                    content = NativeExperienceDatasetCodec.encodeShard(shard),
                    importance = 0.94,
                    provenance = Provenance(
                        source = "verified-governed-experience",
                        producer = "native-experience-dataset-shard",
                        confidence = examples.map { it.verificationConfidence }.average(),
                        parents = examples.map { exampleMemoryId(it.id) }.toSet()
                    ),
                    createdAtEpochMs = createdAt
                )
            )
        }
        return shard
    }

    override fun getShard(id: NativeDatasetShardId): NativeExperienceDatasetShard? {
        val record = memory.get(shardMemoryId(id))
            ?.takeIf { it.kind == SHARD_KIND }
            ?: return null
        val decoded = NativeExperienceDatasetCodec.decodeShard(record.content) ?: return null
        val manifest = foundation.getDatasetShard(id) ?: return null
        return runCatching {
            NativeExperienceDatasetShard(
                manifest = manifest,
                exampleIds = decoded.first,
                payload = decoded.second
            )
        }.getOrNull()
    }

    private fun MemoryOs.updateExampleIndexLocked(
        id: NativeExperienceExampleId,
        observedAtEpochMs: Long
    ) {
        val next = (
            decodeExampleIndex(get(EXAMPLE_INDEX_ID)?.content).filterNot { it == id } + id
            ).takeLast(MAX_INDEXED_EXAMPLES)
        remember(
            MemoryRecord(
                id = EXAMPLE_INDEX_ID,
                kind = EXAMPLE_INDEX_KIND,
                content = "ids=" + next.joinToString(",") { it.value },
                importance = 0.70,
                provenance = Provenance(
                    source = "verified-governed-experience",
                    producer = "native-experience-dataset-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = observedAtEpochMs
            )
        )
    }

    private fun decodeExampleIndex(content: String?): List<NativeExperienceExampleId> {
        if (content == null || !content.startsWith("ids=")) return emptyList()
        return content.removePrefix("ids=")
            .split(',')
            .filter { it.startsWith("verified:") && it.length == 73 }
            .distinct()
            .map(::NativeExperienceExampleId)
            .takeLast(MAX_INDEXED_EXAMPLES)
    }

    private fun exampleMemoryId(id: NativeExperienceExampleId): MemoryId =
        MemoryId("native-experience-example:" + id.value.removePrefix("verified:"))

    private fun shardMemoryId(id: NativeDatasetShardId): MemoryId =
        MemoryId("native-experience-shard:" + id.value)

    companion object {
        const val EXAMPLE_KIND = "native-experience-training-example-v1"
        const val EXAMPLE_INDEX_KIND = "native-experience-training-index-v1"
        const val SHARD_KIND = "native-experience-dataset-shard-v1"
        const val MAX_INDEXED_EXAMPLES = 1_024
        const val MAX_SHARD_EXAMPLES = 256
        private val EXAMPLE_INDEX_ID = MemoryId("native-experience-training:index")
    }
}

private object NativeExperienceDatasetCodec {
    private const val EXAMPLE_VERSION = "AMPER_NATIVE_EXPERIENCE_EXAMPLE_V1"
    private const val SHARD_VERSION = "AMPER_NATIVE_EXPERIENCE_SHARD_V1"

    fun encodeExample(value: NativeExperienceTrainingExample): String = listOf(
        EXAMPLE_VERSION,
        enc(value.id.value),
        value.goalFingerprint.sorted().joinToString(","),
        value.strategy.capabilities.joinToString(",") { enc(it.value) },
        value.verificationConfidence.toString(),
        value.observedAtEpochMs.toString()
    ).joinToString("\t")

    fun decodeExample(content: String): NativeExperienceTrainingExample? = runCatching {
        val p = content.split('\t')
        require(p.size == 6 && p[0] == EXAMPLE_VERSION)
        NativeExperienceTrainingExample(
            id = NativeExperienceExampleId(dec(p[1])),
            goalFingerprint = p[2].split(',').filter { it.isNotBlank() }.toSortedSet(),
            strategy = StrategySignature(
                p[3].split(',').filter { it.isNotBlank() }.map { CapabilityId(dec(it)) }
            ),
            verificationConfidence = p[4].toDouble(),
            observedAtEpochMs = p[5].toLong()
        )
    }.getOrNull()

    fun trainingLine(value: NativeExperienceTrainingExample): String = listOf(
        "goal_fingerprint=" + value.goalFingerprint.sorted().joinToString(","),
        "strategy=" + value.strategy.capabilities.joinToString(">") { it.value },
        "verified_confidence=" + "%.6f".format(java.util.Locale.US, value.verificationConfidence)
    ).joinToString("\t")

    fun encodeShard(value: NativeExperienceDatasetShard): String = listOf(
        SHARD_VERSION,
        value.exampleIds.joinToString(",") { enc(it.value) },
        Base64.getEncoder().encodeToString(value.payload.toByteArray(StandardCharsets.UTF_8))
    ).joinToString("\t")

    fun decodeShard(content: String): Pair<List<NativeExperienceExampleId>, String>? = runCatching {
        val p = content.split('\t')
        require(p.size == 3 && p[0] == SHARD_VERSION)
        val ids = p[1].split(',')
            .filter { it.isNotBlank() }
            .map { NativeExperienceExampleId(dec(it)) }
        val payload = String(Base64.getDecoder().decode(p[2]), StandardCharsets.UTF_8)
        ids to payload
    }.getOrNull()

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}

private fun nativeExperienceSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
