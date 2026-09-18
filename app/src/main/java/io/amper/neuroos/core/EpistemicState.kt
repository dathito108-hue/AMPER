package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64

enum class EpistemicStatus {
    SUPPORTED,
    UNCERTAIN,
    CONTESTED,
    STALE
}

data class EpistemicClaim(
    val subject: String,
    val predicate: String,
    val value: String,
    val confidence: Double,
    val provenance: Provenance
) {
    init {
        require(subject.isNotBlank())
        require(predicate.isNotBlank())
        require(value.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

data class EpistemicAssessment(
    val subject: String,
    val predicate: String,
    val preferredValue: String?,
    val status: EpistemicStatus,
    val confidence: Double,
    val evidenceCount: Int,
    val competingValues: Set<String>,
    val evidenceIds: List<MemoryId>,
    val newestObservedAtEpochMs: Long,
    /**
     * Epistemic evidence is descriptive data only. It can never grant tool authority,
     * approve a side effect, or widen the execution boundary.
     */
    val authorityBearing: Boolean = false
) {
    init {
        require(subject.isNotBlank())
        require(predicate.isNotBlank())
        require(confidence in 0.0..1.0)
        require(evidenceCount > 0)
        require(competingValues.isNotEmpty())
        require(evidenceIds.isNotEmpty())
        require(!authorityBearing) { "epistemic evidence must never become execution authority" }
    }
}

interface EpistemicState {
    fun observe(claim: EpistemicClaim): MemoryId
    fun assess(subject: String, predicate: String): EpistemicAssessment?
    fun query(query: String, limit: Int = 8): List<EpistemicAssessment>
}

/**
 * Phase186 epistemic foundation.
 *
 * Claims remain immutable provenance-bearing evidence in Memory OS. Resolution is deterministic:
 * identical subject/predicate claims support one belief value, conflicting values become CONTESTED,
 * low-confidence evidence remains UNCERTAIN and sufficiently old evidence becomes STALE.
 *
 * This state is deliberately non-authoritative: no assessment can grant permission or execute tools.
 */
class MemoryBackedEpistemicState(
    private val memory: MemoryOs,
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS
) : EpistemicState {
    init {
        require(staleAfterMs > 0L)
    }

    override fun observe(claim: EpistemicClaim): MemoryId {
        val record = MemoryRecord(
            kind = CLAIM_KIND,
            content = EpistemicClaimCodec.encode(claim),
            importance = claim.confidence.coerceIn(0.35, 1.0),
            provenance = claim.provenance
        )
        memory.remember(record)
        return record.id
    }

    override fun assess(subject: String, predicate: String): EpistemicAssessment? {
        require(subject.isNotBlank())
        require(predicate.isNotBlank())
        val subjectKey = normalize(subject)
        val predicateKey = normalize(predicate)
        val evidence = memory.recall("$subject $predicate", MAX_EVIDENCE)
            .asSequence()
            .filter { it.kind == CLAIM_KIND }
            .mapNotNull(::decode)
            .filter { (claim, _) ->
                normalize(claim.subject) == subjectKey && normalize(claim.predicate) == predicateKey
            }
            .toList()
        return resolve(evidence)
    }

    override fun query(query: String, limit: Int): List<EpistemicAssessment> {
        require(limit >= 0)
        if (limit == 0) return emptyList()

        val records = memory.recall(query, (limit * 12).coerceAtLeast(48))
            .asSequence()
            .filter { it.kind == CLAIM_KIND }
            .mapNotNull(::decode)
            .toList()

        return records
            .groupBy { (claim, _) -> normalize(claim.subject) to normalize(claim.predicate) }
            .values
            .mapNotNull(::resolve)
            .sortedWith(
                compareByDescending<EpistemicAssessment> { it.newestObservedAtEpochMs }
                    .thenByDescending { it.confidence }
                    .thenBy { it.subject }
                    .thenBy { it.predicate }
            )
            .take(limit)
    }

    private fun resolve(evidence: List<Pair<EpistemicClaim, MemoryRecord>>): EpistemicAssessment? {
        if (evidence.isEmpty()) return null
        val newest = evidence.maxOf { (claim, _) -> claim.provenance.observedAtEpochMs }
        val byValue = evidence.groupBy { (claim, _) -> normalize(claim.value) }
        val scored = byValue.mapValues { (_, entries) ->
            entries.sumOf { (claim, _) -> claim.confidence }
        }
        val preferredKey = scored.maxWithOrNull(
            compareBy<Map.Entry<String, Double>> { it.value }.thenBy { it.key }
        )?.key
        val preferredEntries = preferredKey?.let(byValue::get).orEmpty()
        val preferredValue = preferredEntries
            .maxByOrNull { (claim, _) -> claim.provenance.observedAtEpochMs }
            ?.first
            ?.value
        val totalWeight = scored.values.sum().coerceAtLeast(0.000001)
        val preferredWeight = preferredKey?.let(scored::get) ?: 0.0
        val resolvedConfidence = (preferredWeight / totalWeight).coerceIn(0.0, 1.0)
        val status = when {
            clock() - newest > staleAfterMs -> EpistemicStatus.STALE
            byValue.size > 1 -> EpistemicStatus.CONTESTED
            preferredEntries.maxOf { (claim, _) -> claim.confidence } < MIN_SUPPORTED_CONFIDENCE ->
                EpistemicStatus.UNCERTAIN
            else -> EpistemicStatus.SUPPORTED
        }
        val exemplar = evidence.first().first
        return EpistemicAssessment(
            subject = exemplar.subject,
            predicate = exemplar.predicate,
            preferredValue = preferredValue,
            status = status,
            confidence = resolvedConfidence,
            evidenceCount = evidence.size,
            competingValues = byValue.values
                .mapNotNull { entries ->
                    entries.maxByOrNull { (claim, _) -> claim.provenance.observedAtEpochMs }?.first?.value
                }
                .toSortedSet(),
            evidenceIds = evidence.map { it.second.id }
                .sortedBy { it.value },
            newestObservedAtEpochMs = newest,
            authorityBearing = false
        )
    }

    private fun decode(record: MemoryRecord): Pair<EpistemicClaim, MemoryRecord>? =
        EpistemicClaimCodec.decode(record)?.let { it to record }

    private fun normalize(value: String): String = value.trim().lowercase()

    companion object {
        const val CLAIM_KIND = "epistemic-claim"
        const val DEFAULT_STALE_AFTER_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MIN_SUPPORTED_CONFIDENCE = 0.60
        private const val MAX_EVIDENCE = 128
    }
}

internal object EpistemicClaimCodec {
    fun encode(claim: EpistemicClaim): String {
        val search = listOf(claim.subject, claim.predicate, claim.value)
            .joinToString(" ") { searchable(it) }
        val payload = listOf(
            claim.subject,
            claim.predicate,
            claim.value,
            claim.confidence.toString()
        ).joinToString(separator = ".", transform = ::enc)
        return "search=$search\npayload=$payload"
    }

    fun decode(record: MemoryRecord): EpistemicClaim? = runCatching {
        val payload = record.content.lineSequence()
            .first { it.startsWith("payload=") }
            .removePrefix("payload=")
        val fields = payload.split('.')
        require(fields.size == 4)
        EpistemicClaim(
            subject = dec(fields[0]),
            predicate = dec(fields[1]),
            value = dec(fields[2]),
            confidence = dec(fields[3]).toDouble(),
            provenance = record.provenance
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