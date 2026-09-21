package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64

enum class SemanticKnowledgeStatus {
    ACTIVE,
    RETRACTED
}

data class SemanticKnowledgePolicy(
    val maxQueryResults: Int = DEFAULT_MAX_QUERY_RESULTS,
    val minCandidateScan: Int = DEFAULT_MIN_CANDIDATE_SCAN,
    val candidateScanMultiplier: Int = DEFAULT_CANDIDATE_SCAN_MULTIPLIER,
    val maxCandidateScan: Int = DEFAULT_MAX_CANDIDATE_SCAN,
    val maxFreshnessChecks: Int = DEFAULT_MAX_FRESHNESS_CHECKS
) {
    init {
        require(maxQueryResults in 1..MAX_QUERY_RESULTS_LIMIT)
        require(minCandidateScan in 1..maxCandidateScan)
        require(candidateScanMultiplier in 1..32)
        require(maxCandidateScan in minCandidateScan..MAX_CANDIDATE_SCAN_LIMIT)
        require(maxFreshnessChecks in maxQueryResults..MAX_FRESHNESS_CHECKS_LIMIT)
    }

    fun candidateScanLimit(limit: Int): Int {
        require(limit in 0..maxQueryResults)
        if (limit == 0) return 0
        return (limit * candidateScanMultiplier)
            .coerceAtLeast(minCandidateScan)
            .coerceAtMost(maxCandidateScan)
    }

    companion object {
        const val DEFAULT_MAX_QUERY_RESULTS: Int = 16
        const val MAX_QUERY_RESULTS_LIMIT: Int = 32
        const val DEFAULT_MIN_CANDIDATE_SCAN: Int = 48
        const val DEFAULT_CANDIDATE_SCAN_MULTIPLIER: Int = 12
        const val DEFAULT_MAX_CANDIDATE_SCAN: Int = 192
        const val MAX_CANDIDATE_SCAN_LIMIT: Int = 256
        const val DEFAULT_MAX_FRESHNESS_CHECKS: Int = 64
        const val MAX_FRESHNESS_CHECKS_LIMIT: Int = 128
    }
}

enum class SemanticKnowledgeTransitionKind {
    CREATED,
    REVISED,
    RETRACTED,
    UNCHANGED,
    NO_ELIGIBLE_BELIEF
}

data class SemanticKnowledgeEntry(
    val id: MemoryId,
    val subject: String,
    val predicate: String,
    val value: String?,
    val confidence: Double,
    val status: SemanticKnowledgeStatus,
    val evidenceIds: List<MemoryId>,
    val supersedes: MemoryId?,
    val createdAtEpochMs: Long
) {
    init {
        require(subject.isNotBlank())
        require(predicate.isNotBlank())
        require(confidence in 0.0..1.0)
        require(evidenceIds.isNotEmpty())
        when (status) {
            SemanticKnowledgeStatus.ACTIVE -> require(!value.isNullOrBlank()) {
                "active semantic knowledge requires a value"
            }
            SemanticKnowledgeStatus.RETRACTED -> Unit
        }
    }
}

data class SemanticKnowledgeTransition(
    val kind: SemanticKnowledgeTransitionKind,
    val previous: SemanticKnowledgeEntry?,
    val current: SemanticKnowledgeEntry?
)

interface SemanticKnowledgeStore {
    fun consolidate(subject: String, predicate: String): SemanticKnowledgeTransition
    fun current(subject: String, predicate: String): SemanticKnowledgeEntry?
    fun reconcile(query: String, limit: Int = 6): List<SemanticKnowledgeEntry>
    fun query(query: String, limit: Int = 6): List<SemanticKnowledgeEntry>
}

/**
 * Phase188-190 durable semantic knowledge lifecycle.
 *
 * Phase188: planning-eligible epistemic assessments may become durable semantic knowledge with
 * direct parent links to the evidence that justified the belief.
 *
 * Phase189: semantic knowledge is versioned, never overwritten in place. A changed supported belief
 * creates a new version that supersedes the previous one. If the underlying belief becomes
 * contested, uncertain or stale, an immutable retraction record supersedes the last active version.
 *
 * Phase190: [reconcile] refreshes only the query-relevant belief set and returns current ACTIVE
 * semantic knowledge. Retracted/superseded versions stay in Memory OS for provenance and rollback,
 * but cannot silently re-enter planning context.
 *
 * Semantic knowledge remains descriptive evidence. It is not authority and cannot approve or
 * execute any tool action.
 */
class MemoryBackedSemanticKnowledgeStore(
    private val memory: MemoryOs,
    private val epistemic: EpistemicState,
    private val clock: () -> Long = System::currentTimeMillis,
    private val policy: SemanticKnowledgePolicy = SemanticKnowledgePolicy()
) : SemanticKnowledgeStore {
    override fun consolidate(subject: String, predicate: String): SemanticKnowledgeTransition {
        require(subject.isNotBlank())
        require(predicate.isNotBlank())

        val assessment = epistemic.assess(subject, predicate)
        return consolidateAssessment(subject, predicate, assessment)
    }

    override fun current(subject: String, predicate: String): SemanticKnowledgeEntry? {
        require(subject.isNotBlank())
        require(predicate.isNotBlank())
        val latest = history(subject, predicate).maxWithOrNull(ENTRY_ORDER) ?: return null
        val active = latest.takeIf { it.status == SemanticKnowledgeStatus.ACTIVE } ?: return null
        return active.takeIf(::isFreshAgainstCurrentEpistemicEvidence)
    }

    override fun reconcile(query: String, limit: Int): List<SemanticKnowledgeEntry> {
        require(query.isNotBlank())
        require(limit in 0..policy.maxQueryResults)
        if (limit == 0) return emptyList()

        epistemic.query(query, limit).forEach { assessment ->
            consolidateAssessment(
                subject = assessment.subject,
                predicate = assessment.predicate,
                assessment = assessment
            )
        }
        return query(query, limit)
    }

    override fun query(query: String, limit: Int): List<SemanticKnowledgeEntry> {
        require(query.isNotBlank())
        require(limit in 0..policy.maxQueryResults)
        if (limit == 0) return emptyList()

        val records = memory.recall(query, policy.candidateScanLimit(limit))
            .asSequence()
            .filter { it.kind == KNOWLEDGE_KIND || it.kind == RETRACTION_KIND }
            .map(::decodeStrict)
            .toList()

        return records
            .groupBy { normalize(it.subject) to normalize(it.predicate) }
            .values
            .mapNotNull { versions ->
                versions.maxWithOrNull(ENTRY_ORDER)
                    ?.takeIf { it.status == SemanticKnowledgeStatus.ACTIVE }
            }
            .sortedWith(
                compareByDescending<SemanticKnowledgeEntry> { it.createdAtEpochMs }
                    .thenByDescending { it.confidence }
                    .thenBy { it.subject }
                    .thenBy { it.predicate }
            )
            .take(policy.maxFreshnessChecks)
            .filter(::isFreshAgainstCurrentEpistemicEvidence)
            .take(limit)
    }

    private fun consolidateAssessment(
        subject: String,
        predicate: String,
        assessment: EpistemicAssessment?
    ): SemanticKnowledgeTransition {
        val latest = history(subject, predicate).maxWithOrNull(ENTRY_ORDER)
        val active = latest?.takeIf { it.status == SemanticKnowledgeStatus.ACTIVE }

        if (assessment == null || !assessment.planningEligible || assessment.preferredValue.isNullOrBlank()) {
            if (active == null) {
                return SemanticKnowledgeTransition(
                    kind = SemanticKnowledgeTransitionKind.NO_ELIGIBLE_BELIEF,
                    previous = latest,
                    current = null
                )
            }
            val evidenceIds = assessment?.evidenceIds.orEmpty()
                .ifEmpty { active.evidenceIds }
            val retraction = persist(
                subject = subject,
                predicate = predicate,
                value = active.value,
                confidence = assessment?.confidence ?: 0.0,
                status = SemanticKnowledgeStatus.RETRACTED,
                evidenceIds = evidenceIds.distinct(),
                supersedes = active.id,
                createdAtEpochMs = maxOf(clock(), active.createdAtEpochMs + 1L)
            )
            require(retraction.status == SemanticKnowledgeStatus.RETRACTED)
            return SemanticKnowledgeTransition(
                kind = SemanticKnowledgeTransitionKind.RETRACTED,
                previous = active,
                current = null
            )
        }

        val evidenceIds = assessment.evidenceIds.distinct().sortedBy { it.value }
        val value = requireNotNull(assessment.preferredValue)
        if (
            active != null &&
            normalize(active.value.orEmpty()) == normalize(value) &&
            active.evidenceIds.toSet() == evidenceIds.toSet()
        ) {
            return SemanticKnowledgeTransition(
                kind = SemanticKnowledgeTransitionKind.UNCHANGED,
                previous = active,
                current = active
            )
        }

        val next = persist(
            subject = subject,
            predicate = predicate,
            value = value,
            confidence = assessment.confidence,
            status = SemanticKnowledgeStatus.ACTIVE,
            evidenceIds = evidenceIds,
            supersedes = latest?.id,
            createdAtEpochMs = maxOf(clock(), (latest?.createdAtEpochMs ?: Long.MIN_VALUE) + 1L)
        )
        return SemanticKnowledgeTransition(
            kind = if (latest == null) {
                SemanticKnowledgeTransitionKind.CREATED
            } else {
                SemanticKnowledgeTransitionKind.REVISED
            },
            previous = latest,
            current = next
        )
    }

    private fun persist(
        subject: String,
        predicate: String,
        value: String?,
        confidence: Double,
        status: SemanticKnowledgeStatus,
        evidenceIds: List<MemoryId>,
        supersedes: MemoryId?,
        createdAtEpochMs: Long
    ): SemanticKnowledgeEntry {
        val createdAt = createdAtEpochMs
        val provisional = SemanticKnowledgeEntry(
            id = MemoryId("pending"),
            subject = subject,
            predicate = predicate,
            value = value,
            confidence = confidence.coerceIn(0.0, 1.0),
            status = status,
            evidenceIds = evidenceIds.distinct().sortedBy { it.value },
            supersedes = supersedes,
            createdAtEpochMs = createdAt
        )
        val record = MemoryRecord(
            kind = if (status == SemanticKnowledgeStatus.ACTIVE) KNOWLEDGE_KIND else RETRACTION_KIND,
            content = SemanticKnowledgeCodec.encode(provisional),
            importance = when (status) {
                SemanticKnowledgeStatus.ACTIVE -> confidence.coerceIn(0.55, 1.0)
                SemanticKnowledgeStatus.RETRACTED -> 0.75
            },
            provenance = Provenance(
                source = "epistemic-semantic-consolidation",
                producer = "semantic-knowledge-store",
                observedAtEpochMs = createdAt,
                confidence = confidence.coerceIn(0.0, 1.0),
                parents = (evidenceIds + listOfNotNull(supersedes)).toSet()
            ),
            createdAtEpochMs = createdAt
        )
        memory.remember(record)
        return provisional.copy(id = record.id)
    }

    private fun history(subject: String, predicate: String): List<SemanticKnowledgeEntry> {
        val subjectKey = normalize(subject)
        val predicateKey = normalize(predicate)
        return memory.recall("$subject $predicate", MAX_HISTORY)
            .asSequence()
            .filter { it.kind == KNOWLEDGE_KIND || it.kind == RETRACTION_KIND }
            .map(::decodeStrict)
            .filter { normalize(it.subject) == subjectKey && normalize(it.predicate) == predicateKey }
            .toList()
    }

    private fun isFreshAgainstCurrentEpistemicEvidence(
        entry: SemanticKnowledgeEntry
    ): Boolean {
        val assessment = epistemic.assess(entry.subject, entry.predicate) ?: return false
        return assessment.planningEligible &&
            normalize(assessment.preferredValue.orEmpty()) == normalize(entry.value.orEmpty()) &&
            assessment.evidenceIds.toSet() == entry.evidenceIds.toSet()
    }

    private fun decodeStrict(record: MemoryRecord): SemanticKnowledgeEntry =
        requireNotNull(SemanticKnowledgeCodec.decode(record)) {
            "corrupt semantic knowledge record: " + record.id.value
        }

    private fun normalize(value: String): String = value.trim().lowercase()

    companion object {
        const val KNOWLEDGE_KIND = "semantic-knowledge"
        const val RETRACTION_KIND = "semantic-retraction"
        private const val MAX_HISTORY = 128

        private val ENTRY_ORDER = compareBy<SemanticKnowledgeEntry> { it.createdAtEpochMs }
            .thenBy { it.id.value }
    }
}

internal object SemanticKnowledgeCodec {
    fun encode(entry: SemanticKnowledgeEntry): String {
        val search = listOf(entry.subject, entry.predicate, entry.value.orEmpty())
            .joinToString(" ") { searchable(it) }
        val evidence = entry.evidenceIds.joinToString(",") { enc(it.value) }.ifEmpty { "~" }
        val fields = listOf(
            enc(entry.subject),
            enc(entry.predicate),
            entry.value?.let(::enc) ?: "~",
            enc(entry.confidence.toString()),
            entry.status.name,
            evidence,
            entry.supersedes?.value?.let(::enc) ?: "~",
            entry.createdAtEpochMs.toString()
        )
        return "search=$search\npayload=${fields.joinToString(".")}"
    }

    fun decode(record: MemoryRecord): SemanticKnowledgeEntry? = runCatching {
        val payload = record.content.lineSequence()
            .first { it.startsWith("payload=") }
            .removePrefix("payload=")
        val fields = payload.split('.')
        require(fields.size == 8)
        val evidenceIds = if (fields[5] == "~") {
            emptyList()
        } else {
            fields[5].split(',').map { MemoryId(dec(it)) }
        }
        SemanticKnowledgeEntry(
            id = record.id,
            subject = dec(fields[0]),
            predicate = dec(fields[1]),
            value = fields[2].takeUnless { it == "~" }?.let(::dec),
            confidence = dec(fields[3]).toDouble(),
            status = SemanticKnowledgeStatus.valueOf(fields[4]),
            evidenceIds = evidenceIds,
            supersedes = fields[6].takeUnless { it == "~" }?.let { MemoryId(dec(it)) },
            createdAtEpochMs = fields[7].toLong()
        )
    }.getOrNull()

    private fun searchable(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(160)

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}