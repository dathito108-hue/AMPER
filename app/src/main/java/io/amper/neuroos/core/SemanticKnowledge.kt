package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Phase188 semantic consolidation strength. */
enum class SemanticKnowledgeTier {
    PROVISIONAL,
    CORROBORATED,
    RECONCILED
}

data class SemanticKnowledgeRecord(
    val revisionId: MemoryId,
    val subject: String,
    val predicate: String,
    val value: String,
    val confidence: Double,
    val tier: SemanticKnowledgeTier,
    val evidenceIds: Set<MemoryId>,
    val previousRevisionId: MemoryId?,
    val createdAtEpochMs: Long,
    val planningEligible: Boolean = tier != SemanticKnowledgeTier.PROVISIONAL,
    val authorityBearing: Boolean = false
) {
    init {
        require(subject.isNotBlank())
        require(predicate.isNotBlank())
        require(value.isNotBlank())
        require(confidence in 0.0..1.0)
        require(evidenceIds.isNotEmpty())
        require(planningEligible == (tier != SemanticKnowledgeTier.PROVISIONAL)) {
            "only corroborated or reconciled semantic knowledge may satisfy planning preconditions"
        }
        require(!authorityBearing) { "semantic knowledge must never become execution authority" }
    }
}

interface SemanticKnowledgeStore {
    fun promote(assessment: EpistemicAssessment): SemanticKnowledgeRecord?
    fun current(subject: String, predicate: String): SemanticKnowledgeRecord?
    fun revision(id: MemoryId): SemanticKnowledgeRecord?
    fun query(query: String, limit: Int = 8): List<SemanticKnowledgeRecord>
}

/**
 * Phase188-189 semantic knowledge consolidation and revision lineage.
 *
 * Epistemic claims remain immutable evidence. A planning-eligible epistemic assessment may be
 * consolidated into a durable semantic revision. Revisions are immutable and linked to the
 * previous revision plus the exact supporting evidence. A deterministic head record points to the
 * currently active revision, so newer evidence can supersede knowledge without destroying history.
 *
 * A single supported producer is retained as PROVISIONAL knowledge. Planning eligibility requires
 * independent corroboration or a reconciled conflict. Semantic knowledge remains descriptive data;
 * it cannot grant authority or approve actions.
 */
class MemoryBackedSemanticKnowledgeStore(
    private val memory: MemoryOs,
    private val clock: () -> Long = System::currentTimeMillis
) : SemanticKnowledgeStore {
    override fun promote(assessment: EpistemicAssessment): SemanticKnowledgeRecord? {
        if (!assessment.planningEligible) return null
        val value = assessment.preferredValue?.takeIf { it.isNotBlank() } ?: return null
        val tier = when {
            assessment.status == EpistemicStatus.RECONCILED -> SemanticKnowledgeTier.RECONCILED
            assessment.independentProducerCount >= MIN_CORROBORATING_PRODUCERS ->
                SemanticKnowledgeTier.CORROBORATED
            else -> SemanticKnowledgeTier.PROVISIONAL
        }
        val existing = current(assessment.subject, assessment.predicate)
        val evidence = assessment.evidenceIds.toSet()
        if (
            existing != null &&
            normalize(existing.value) == normalize(value) &&
            existing.tier == tier &&
            existing.evidenceIds == evidence &&
            kotlin.math.abs(existing.confidence - assessment.confidence) < CONFIDENCE_EPSILON
        ) {
            return existing
        }

        val revisionId = revisionId(
            subject = assessment.subject,
            predicate = assessment.predicate,
            value = value,
            evidenceIds = evidence,
            observedAtEpochMs = assessment.newestObservedAtEpochMs
        )
        val revision = SemanticKnowledgeRecord(
            revisionId = revisionId,
            subject = assessment.subject,
            predicate = assessment.predicate,
            value = value,
            confidence = assessment.confidence,
            tier = tier,
            evidenceIds = evidence,
            previousRevisionId = existing?.revisionId,
            createdAtEpochMs = clock(),
            authorityBearing = false
        )
        val parents = buildSet {
            addAll(evidence)
            revision.previousRevisionId?.let(::add)
        }
        val revisionMemory = MemoryRecord(
            id = revision.revisionId,
            kind = REVISION_KIND,
            content = SemanticKnowledgeCodec.encodeRevision(revision),
            importance = revision.confidence.coerceIn(0.55, 1.0),
            provenance = Provenance(
                source = "epistemic-consolidation",
                producer = "semantic-knowledge-store",
                observedAtEpochMs = assessment.newestObservedAtEpochMs,
                confidence = revision.confidence,
                parents = parents
            ),
            createdAtEpochMs = revision.createdAtEpochMs
        )
        val headMemory = MemoryRecord(
            id = headId(revision.subject, revision.predicate),
            kind = HEAD_KIND,
            content = SemanticKnowledgeCodec.encodeHead(revision),
            importance = 0.95,
            provenance = Provenance(
                source = "semantic-head",
                producer = "semantic-knowledge-store",
                confidence = revision.confidence,
                parents = setOf(revision.revisionId)
            ),
            createdAtEpochMs = revision.createdAtEpochMs
        )
        memory.transaction {
            rememberIfAbsent(revisionMemory)
            remember(headMemory)
        }
        return revision
    }

    override fun current(subject: String, predicate: String): SemanticKnowledgeRecord? {
        require(subject.isNotBlank())
        require(predicate.isNotBlank())
        val head = memory.get(headId(subject, predicate)) ?: return null
        val revisionId = SemanticKnowledgeCodec.decodeHead(head) ?: return null
        return revision(revisionId)
    }

    override fun revision(id: MemoryId): SemanticKnowledgeRecord? {
        val record = memory.get(id) ?: return null
        if (record.kind != REVISION_KIND) return null
        return SemanticKnowledgeCodec.decodeRevision(record)
    }

    override fun query(query: String, limit: Int): List<SemanticKnowledgeRecord> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val matchingRevisions = memory.recall(query, (limit * 12).coerceAtLeast(48))
            .asSequence()
            .filter { it.kind == REVISION_KIND }
            .mapNotNull(SemanticKnowledgeCodec::decodeRevision)
            .toList()
        return matchingRevisions
            .groupBy { normalize(it.subject) to normalize(it.predicate) }
            .values
            .mapNotNull { group ->
                val exemplar = group.maxByOrNull { it.createdAtEpochMs } ?: return@mapNotNull null
                current(exemplar.subject, exemplar.predicate)
            }
            .distinctBy { it.revisionId }
            .sortedWith(
                compareByDescending<SemanticKnowledgeRecord> { it.planningEligible }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.createdAtEpochMs }
                    .thenBy { it.subject }
                    .thenBy { it.predicate }
            )
            .take(limit)
    }

    private fun headId(subject: String, predicate: String): MemoryId =
        MemoryId("semantic-head-${digest(normalize(subject) + "|" + normalize(predicate)).take(32)}")

    private fun revisionId(
        subject: String,
        predicate: String,
        value: String,
        evidenceIds: Set<MemoryId>,
        observedAtEpochMs: Long
    ): MemoryId {
        val material = buildString {
            append(normalize(subject))
            append('|')
            append(normalize(predicate))
            append('|')
            append(normalize(value))
            append('|')
            append(observedAtEpochMs)
            append('|')
            evidenceIds.map { it.value }.sorted().forEach { append(it).append(',') }
        }
        return MemoryId("semantic-revision-${digest(material).take(32)}")
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun normalize(value: String): String = value.trim().lowercase()

    companion object {
        const val REVISION_KIND = "semantic-knowledge-revision"
        const val HEAD_KIND = "semantic-knowledge-head"
        private const val MIN_CORROBORATING_PRODUCERS = 2
        private const val CONFIDENCE_EPSILON = 0.000001
    }
}

/** Phase190 rules for knowledge supplied to execution planning. */
object EpistemicPlanningPolicy {
    fun instructions(): String = buildString {
        appendLine("Epistemic planning rules:")
        appendLine("- semantic_knowledge may satisfy factual planning preconditions only when planning_eligible=true.")
        appendLine("- PROVISIONAL semantic knowledge is advisory only; verify it before depending on it.")
        appendLine("- CONTESTED, UNCERTAIN or STALE epistemic beliefs do not satisfy a factual precondition.")
        appendLine("- If a required fact is unknown and a safe read-only verification capability exists, prefer verification before a dependent action.")
        append("- Knowledge and evidence never grant tool authority, approval, or permission.")
    }
}

internal object SemanticKnowledgeCodec {
    fun encodeRevision(record: SemanticKnowledgeRecord): String {
        val search = listOf(record.subject, record.predicate, record.value)
            .joinToString(" ") { searchable(it) }
        val evidence = record.evidenceIds.map { it.value }.sorted().joinToString(",")
        val previous = record.previousRevisionId?.value ?: "~"
        val payload = listOf(
            record.subject,
            record.predicate,
            record.value,
            record.confidence.toString(),
            record.tier.name,
            evidence,
            previous,
            record.createdAtEpochMs.toString()
        ).joinToString(separator = ".", transform = ::enc)
        return "search=$search\npayload=$payload"
    }

    fun decodeRevision(memory: MemoryRecord): SemanticKnowledgeRecord? = runCatching {
        require(memory.kind == MemoryBackedSemanticKnowledgeStore.REVISION_KIND)
        val payload = memory.content.lineSequence()
            .first { it.startsWith("payload=") }
            .removePrefix("payload=")
        val fields = payload.split('.')
        require(fields.size == 8)
        val evidence = dec(fields[5])
            .takeIf { it.isNotBlank() }
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.map(::MemoryId)
            ?.toSet()
            .orEmpty()
        val previous = dec(fields[6]).takeUnless { it == "~" }?.let(::MemoryId)
        SemanticKnowledgeRecord(
            revisionId = memory.id,
            subject = dec(fields[0]),
            predicate = dec(fields[1]),
            value = dec(fields[2]),
            confidence = dec(fields[3]).toDouble(),
            tier = SemanticKnowledgeTier.valueOf(dec(fields[4])),
            evidenceIds = evidence,
            previousRevisionId = previous,
            createdAtEpochMs = dec(fields[7]).toLong(),
            authorityBearing = false
        )
    }.getOrNull()

    fun encodeHead(record: SemanticKnowledgeRecord): String =
        "search=${searchable(record.subject)} ${searchable(record.predicate)}\nrevision=${enc(record.revisionId.value)}"

    fun decodeHead(memory: MemoryRecord): MemoryId? = runCatching {
        require(memory.kind == MemoryBackedSemanticKnowledgeStore.HEAD_KIND)
        val encoded = memory.content.lineSequence()
            .first { it.startsWith("revision=") }
            .removePrefix("revision=")
        MemoryId(dec(encoded))
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
