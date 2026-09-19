package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

@JvmInline
value class ReflexExperienceExampleId(val value: String) {
    init {
        require(value.matches(Regex("(?:action|escalate):[0-9a-f]{64}")))
    }
}

enum class ReflexExperienceSource {
    REFLEX_CORTEX,
    SYSTEM2_TEACHER
}

data class ReflexExperienceTrainingExample(
    val id: ReflexExperienceExampleId,
    val featureHashes: Set<String>,
    val availableCapabilities: Set<CapabilityId>,
    val targetDisposition: ReflexDecisionDisposition,
    val targetCapability: CapabilityId? = null,
    val targetSideEffect: ToolSideEffect? = null,
    val source: ReflexExperienceSource,
    val labelConfidence: Double,
    val observedAtEpochMs: Long
) {
    init {
        require(featureHashes.isNotEmpty())
        require(featureHashes.size <= ReflexDecisionFeatureEncoder.MAX_FEATURES)
        require(featureHashes.all { it.matches(FEATURE_HASH) })
        require(availableCapabilities.size <= MAX_AVAILABLE_CAPABILITIES)
        require(labelConfidence in 0.0..1.0)
        require(observedAtEpochMs >= 0L)
        when (targetDisposition) {
            ReflexDecisionDisposition.ESCALATE_SYSTEM2 -> {
                require(targetCapability == null && targetSideEffect == null)
            }
            ReflexDecisionDisposition.PROPOSE_ACTION -> {
                require(targetCapability != null && targetSideEffect != null)
                require(targetCapability in availableCapabilities)
            }
        }
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MAX_AVAILABLE_CAPABILITIES = 32
        private val FEATURE_HASH = Regex("[ub]:[0-9a-f]{16}")
    }
}

data class ReflexExperienceDatasetShard(
    val manifest: NativeDatasetShardManifest,
    val exampleIds: List<ReflexExperienceExampleId>,
    val payload: String
) {
    init {
        require(manifest.rights == NativeDatasetRights.GENERATED_INTERNAL)
        require(manifest.targetCapabilities == setOf(TitanCapabilities.REFLEX_DECISION))
        require(exampleIds.isNotEmpty())
        require(exampleIds.distinct().size == exampleIds.size)
        require(manifest.exampleCount == exampleIds.size.toLong())
        require(manifest.byteCount == payload.toByteArray(StandardCharsets.UTF_8).size.toLong())
        require(manifest.sha256 == reflexExperienceSha256(payload))
    }

    val authorityBearing: Boolean
        get() = false
}

interface ReflexExperienceDatasetStore {
    fun observeExecuted(
        userInput: String,
        descriptors: Collection<ToolDescriptor>,
        action: ActionOutcome,
        source: ReflexExperienceSource,
        labelConfidence: Double,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): ReflexExperienceTrainingExample

    fun observeEscalation(
        conversationId: ConversationId,
        userInput: String,
        descriptors: Collection<ToolDescriptor>,
        response: InferenceResponse,
        labelConfidence: Double = 0.80,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): ReflexExperienceTrainingExample

    fun getExample(id: ReflexExperienceExampleId): ReflexExperienceTrainingExample?

    fun recentExamples(limit: Int = 64): List<ReflexExperienceTrainingExample>

    fun materializeShard(
        id: NativeDatasetShardId,
        minExamples: Int = 4,
        limit: Int = 256
    ): ReflexExperienceDatasetShard

    fun getShard(id: NativeDatasetShardId): ReflexExperienceDatasetShard?
}

/**
 * Phase426-430 privacy-preserving System-1 decision experience.
 *
 * Raw user text, action reasons, tool inputs, tool outputs, tool ids, approvals and conversation ids
 * never enter the retained training example. The input side is represented by bounded hashed lexical
 * unigram/bigram features plus the set of live advertised capabilities. The target side is only
 * ESCALATE_SYSTEM2 or one executed capability and its side-effect class.
 */
class MemoryBackedReflexExperienceDatasetStore(
    private val memory: MemoryOs,
    private val foundation: NativeModelFoundation
) : ReflexExperienceDatasetStore {
    @Synchronized
    override fun observeExecuted(
        userInput: String,
        descriptors: Collection<ToolDescriptor>,
        action: ActionOutcome,
        source: ReflexExperienceSource,
        labelConfidence: Double,
        observedAtEpochMs: Long
    ): ReflexExperienceTrainingExample {
        require(userInput.isNotBlank())
        require(action.status == ActionStatus.EXECUTED) {
            "reflex decision training admits only terminal executed actions"
        }
        require(labelConfidence in 0.0..1.0)
        require(observedAtEpochMs >= 0L)
        val proposal = requireNotNull(action.proposal)
        val toolId = requireNotNull(action.toolId)
        val sideEffect = requireNotNull(action.sideEffect)
        val normalizedDescriptors = normalizeDescriptors(descriptors)
        val bound = requireNotNull(normalizedDescriptors.firstOrNull { it.id == toolId }) {
            "executed reflex experience has no matching live tool descriptor"
        }
        require(bound.capability == proposal.capability) {
            "executed reflex experience capability changed from live descriptor"
        }
        require(bound.sideEffect == sideEffect) {
            "executed reflex experience side-effect changed from live descriptor"
        }

        val example = ReflexExperienceTrainingExample(
            id = ReflexExperienceExampleId(
                "action:" + reflexExperienceSha256(proposal.requestId.value)
            ),
            featureHashes = ReflexDecisionFeatureEncoder.encode(userInput),
            availableCapabilities = normalizedDescriptors.mapTo(linkedSetOf()) { it.capability },
            targetDisposition = ReflexDecisionDisposition.PROPOSE_ACTION,
            targetCapability = proposal.capability,
            targetSideEffect = sideEffect,
            source = source,
            labelConfidence = labelConfidence,
            observedAtEpochMs = observedAtEpochMs
        )
        return saveIdempotent(example)
    }

    @Synchronized
    override fun observeEscalation(
        conversationId: ConversationId,
        userInput: String,
        descriptors: Collection<ToolDescriptor>,
        response: InferenceResponse,
        labelConfidence: Double,
        observedAtEpochMs: Long
    ): ReflexExperienceTrainingExample {
        require(userInput.isNotBlank())
        require(labelConfidence in 0.0..1.0)
        require(observedAtEpochMs >= 0L)
        val features = ReflexDecisionFeatureEncoder.encode(userInput)
        val normalizedDescriptors = normalizeDescriptors(descriptors)
        val identityMaterial = listOf(
            conversationId.value,
            features.sorted().joinToString(","),
            response.modelId.value,
            response.backendId,
            reflexExperienceSha256(response.text)
        ).joinToString("|")
        val example = ReflexExperienceTrainingExample(
            id = ReflexExperienceExampleId(
                "escalate:" + reflexExperienceSha256(identityMaterial)
            ),
            featureHashes = features,
            availableCapabilities = normalizedDescriptors.mapTo(linkedSetOf()) { it.capability },
            targetDisposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
            source = ReflexExperienceSource.SYSTEM2_TEACHER,
            labelConfidence = labelConfidence,
            observedAtEpochMs = observedAtEpochMs
        )
        return saveIdempotent(example)
    }

    override fun getExample(id: ReflexExperienceExampleId): ReflexExperienceTrainingExample? =
        memory.get(exampleMemoryId(id))
            ?.takeIf { it.kind == EXAMPLE_KIND }
            ?.let { ReflexExperienceDatasetCodec.decodeExample(it.content) }
            ?.takeIf { it.id == id }

    override fun recentExamples(limit: Int): List<ReflexExperienceTrainingExample> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.transaction {
            decodeIndex(get(INDEX_ID)?.content)
                .asReversed()
                .asSequence()
                .mapNotNull(this@MemoryBackedReflexExperienceDatasetStore::getExample)
                .take(limit)
                .toList()
        }
    }

    @Synchronized
    override fun materializeShard(
        id: NativeDatasetShardId,
        minExamples: Int,
        limit: Int
    ): ReflexExperienceDatasetShard {
        require(minExamples > 0)
        require(limit in minExamples..MAX_SHARD_EXAMPLES)
        getShard(id)?.let { return it }
        require(foundation.getDatasetShard(id) == null) {
            "native dataset id already belongs to another dataset"
        }
        val examples = recentExamples(limit)
            .sortedBy { it.id.value }
        require(examples.size >= minExamples) {
            "insufficient reflex decision experience examples"
        }
        val payload = examples.joinToString(
            separator = "\n",
            transform = ReflexExperienceDatasetCodec::trainingLine
        )
        val manifest = NativeDatasetShardManifest(
            id = id,
            sha256 = reflexExperienceSha256(payload),
            sourceLabel = "AMPER reflex decision experience",
            rights = NativeDatasetRights.GENERATED_INTERNAL,
            exampleCount = examples.size.toLong(),
            byteCount = payload.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            targetCapabilities = setOf(TitanCapabilities.REFLEX_DECISION),
            createdAtEpochMs = examples.maxOf { it.observedAtEpochMs }
        )
        val shard = ReflexExperienceDatasetShard(
            manifest = manifest,
            exampleIds = examples.map { it.id },
            payload = payload
        )
        memory.transaction {
            foundation.putDatasetShard(manifest)
            remember(
                MemoryRecord(
                    id = shardMemoryId(id),
                    kind = SHARD_KIND,
                    content = ReflexExperienceDatasetCodec.encodeShard(shard),
                    importance = 0.92,
                    provenance = Provenance(
                        source = "governed-reflex-decision-experience",
                        producer = "reflex-experience-dataset-shard",
                        confidence = examples.map { it.labelConfidence }.average(),
                        parents = examples.map { exampleMemoryId(it.id) }.toSet()
                    ),
                    createdAtEpochMs = manifest.createdAtEpochMs
                )
            )
        }
        return shard
    }

    override fun getShard(id: NativeDatasetShardId): ReflexExperienceDatasetShard? {
        val record = memory.get(shardMemoryId(id))
            ?.takeIf { it.kind == SHARD_KIND }
            ?: return null
        val decoded = ReflexExperienceDatasetCodec.decodeShard(record.content) ?: return null
        val manifest = foundation.getDatasetShard(id) ?: return null
        return runCatching {
            ReflexExperienceDatasetShard(
                manifest = manifest,
                exampleIds = decoded.first,
                payload = decoded.second
            )
        }.getOrNull()
    }

    private fun saveIdempotent(
        example: ReflexExperienceTrainingExample
    ): ReflexExperienceTrainingExample {
        getExample(example.id)?.let { existing ->
            require(existing.copy(observedAtEpochMs = example.observedAtEpochMs) == example) {
                "reflex experience identity changed on replay"
            }
            return existing
        }
        memory.transaction {
            remember(
                MemoryRecord(
                    id = exampleMemoryId(example.id),
                    kind = EXAMPLE_KIND,
                    content = ReflexExperienceDatasetCodec.encodeExample(example),
                    importance = if (
                        example.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
                    ) 0.84 else 0.68,
                    provenance = Provenance(
                        source = "governed-reflex-decision-experience",
                        producer = "reflex-experience-dataset",
                        confidence = example.labelConfidence
                    ),
                    createdAtEpochMs = example.observedAtEpochMs
                )
            )
            val next = (
                decodeIndex(get(INDEX_ID)?.content)
                    .filterNot { it == example.id } + example.id
                ).takeLast(MAX_INDEXED_EXAMPLES)
            remember(
                MemoryRecord(
                    id = INDEX_ID,
                    kind = INDEX_KIND,
                    content = "ids=" + next.joinToString(",") { it.value },
                    importance = 0.66,
                    provenance = Provenance(
                        source = "governed-reflex-decision-experience",
                        producer = "reflex-experience-dataset-index",
                        confidence = 1.0
                    ),
                    createdAtEpochMs = example.observedAtEpochMs
                )
            )
        }
        return example
    }

    private fun normalizeDescriptors(
        descriptors: Collection<ToolDescriptor>
    ): List<ToolDescriptor> {
        val normalized = descriptors
            .distinctBy { it.capability }
            .sortedBy { it.capability.value }
        require(normalized.size <= ReflexExperienceTrainingExample.MAX_AVAILABLE_CAPABILITIES)
        return normalized
    }

    private fun decodeIndex(content: String?): List<ReflexExperienceExampleId> {
        if (content == null || !content.startsWith("ids=")) return emptyList()
        return content.removePrefix("ids=")
            .split(',')
            .filter { it.matches(Regex("(?:action|escalate):[0-9a-f]{64}")) }
            .distinct()
            .map(::ReflexExperienceExampleId)
            .takeLast(MAX_INDEXED_EXAMPLES)
    }

    private fun exampleMemoryId(id: ReflexExperienceExampleId): MemoryId =
        MemoryId("reflex-experience-example:" + reflexExperienceSha256(id.value))

    private fun shardMemoryId(id: NativeDatasetShardId): MemoryId =
        MemoryId("reflex-experience-shard:" + id.value)

    companion object {
        const val EXAMPLE_KIND = "reflex-experience-training-example-v1"
        const val INDEX_KIND = "reflex-experience-training-index-v1"
        const val SHARD_KIND = "reflex-experience-dataset-shard-v1"
        const val MAX_INDEXED_EXAMPLES = 1_024
        const val MAX_SHARD_EXAMPLES = 256
        private val INDEX_ID = MemoryId("reflex-experience-training:index")
    }
}

object ReflexDecisionFeatureEncoder {
    const val MAX_FEATURES = 64
    private const val MAX_TOKENS = 32
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val TOKEN = Regex("[a-z0-9._:+#-]+")
    private val WHITESPACE = Regex("\\s+")

    fun encode(userInput: String): Set<String> {
        require(userInput.isNotBlank())
        val normalized = Normalizer.normalize(
            userInput.lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        )
            .replace(COMBINING_MARKS, "")
            .replace('đ', 'd')
            .replace(WHITESPACE, " ")
            .trim()
        val tokens = TOKEN.findAll(normalized)
            .map { it.value }
            .filter { it.isNotBlank() }
            .take(MAX_TOKENS)
            .toList()
        val features = linkedSetOf<String>()
        tokens.forEach { token ->
            features += "u:" + reflexExperienceSha256("u|$token").take(16)
        }
        tokens.zipWithNext().forEach { (left, right) ->
            features += "b:" + reflexExperienceSha256("b|$left|$right").take(16)
        }
        if (features.isEmpty()) {
            features += "u:" + reflexExperienceSha256("fallback|$normalized").take(16)
        }
        return features.take(MAX_FEATURES).toCollection(linkedSetOf())
    }
}

private object ReflexExperienceDatasetCodec {
    private const val EXAMPLE_VERSION = "AMPER_REFLEX_EXPERIENCE_EXAMPLE_V1"
    private const val SHARD_VERSION = "AMPER_REFLEX_EXPERIENCE_SHARD_V1"

    fun encodeExample(value: ReflexExperienceTrainingExample): String = listOf(
        EXAMPLE_VERSION,
        value.id.value,
        value.featureHashes.sorted().joinToString(","),
        value.availableCapabilities.map { it.value }.sorted().joinToString(","),
        value.targetDisposition.name,
        value.targetCapability?.value ?: "-",
        value.targetSideEffect?.name ?: "-",
        value.source.name,
        value.labelConfidence.toString(),
        value.observedAtEpochMs.toString()
    ).joinToString("\t")

    fun decodeExample(content: String): ReflexExperienceTrainingExample? = runCatching {
        val p = content.split('\t')
        require(p.size == 10 && p[0] == EXAMPLE_VERSION)
        ReflexExperienceTrainingExample(
            id = ReflexExperienceExampleId(p[1]),
            featureHashes = p[2].split(',').filter { it.isNotBlank() }.toSortedSet(),
            availableCapabilities = p[3].split(',')
                .filter { it.isNotBlank() }
                .mapTo(linkedSetOf(), ::CapabilityId),
            targetDisposition = ReflexDecisionDisposition.valueOf(p[4]),
            targetCapability = p[5].takeUnless { it == "-" }?.let(::CapabilityId),
            targetSideEffect = p[6].takeUnless { it == "-" }?.let(ToolSideEffect::valueOf),
            source = ReflexExperienceSource.valueOf(p[7]),
            labelConfidence = p[8].toDouble(),
            observedAtEpochMs = p[9].toLong()
        )
    }.getOrNull()

    fun trainingLine(value: ReflexExperienceTrainingExample): String = buildString {
        append("features=")
        append(value.featureHashes.sorted().joinToString(","))
        append("\tavailable=")
        append(value.availableCapabilities.map { it.value }.sorted().joinToString(","))
        append("\ttarget=")
        when (value.targetDisposition) {
            ReflexDecisionDisposition.ESCALATE_SYSTEM2 -> append("ESCALATE_SYSTEM2")
            ReflexDecisionDisposition.PROPOSE_ACTION -> {
                append("ACTION:")
                append(requireNotNull(value.targetCapability).value)
                append(":")
                append(requireNotNull(value.targetSideEffect).name)
            }
        }
        append("\tconfidence=")
        append("%.6f".format(Locale.US, value.labelConfidence))
        append("\tsource=")
        append(value.source.name)
    }

    fun encodeShard(value: ReflexExperienceDatasetShard): String = listOf(
        SHARD_VERSION,
        value.exampleIds.joinToString(",") { it.value },
        Base64.getEncoder().encodeToString(value.payload.toByteArray(StandardCharsets.UTF_8))
    ).joinToString("\t")

    fun decodeShard(content: String): Pair<List<ReflexExperienceExampleId>, String>? = runCatching {
        val p = content.split('\t')
        require(p.size == 3 && p[0] == SHARD_VERSION)
        val ids = p[1].split(',')
            .filter { it.isNotBlank() }
            .map(::ReflexExperienceExampleId)
        val payload = String(Base64.getDecoder().decode(p[2]), StandardCharsets.UTF_8)
        ids to payload
    }.getOrNull()
}

private fun reflexExperienceSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
