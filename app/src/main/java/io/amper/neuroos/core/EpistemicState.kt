package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.max

enum class EpistemicStatus {
    SUPPORTED,
    UNCERTAIN,
    CONTESTED,
    RECONCILED,
    STALE
}

enum class EpistemicResolutionReason {
    CONSISTENT_EVIDENCE,
    LOW_CONFIDENCE,
    UNRESOLVED_CONFLICT,
    INDEPENDENT_CORROBORATION,
    EXPIRED_EVIDENCE
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
    val resolutionReason: EpistemicResolutionReason = EpistemicResolutionReason.CONSISTENT_EVIDENCE,
    val independentProducerCount: Int = 1,
    val winningSupport: Double = 1.0,
    val competingSupport: Double = 0.0,
    val planningEligible: Boolean =
        status == EpistemicStatus.SUPPORTED || status == EpistemicStatus.RECONCILED,
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
        require(independentProducerCount > 0)
        require(winningSupport >= 0.0)
        require(competingSupport >= 0.0)
        require(!authorityBearing) { "epistemic evidence must never become execution authority" }
        require(planningEligible == (
            status == EpistemicStatus.SUPPORTED || status == EpistemicStatus.RECONCILED
        )) { "only supported or reconciled beliefs may be planning-eligible" }
        require(status != EpistemicStatus.RECONCILED || competingValues.size > 1) {
            "reconciled status requires retained competing evidence"
        }
    }
}

interface EpistemicState {
    fun observe(claim: EpistemicClaim): MemoryId
    fun assess(subject: String, predicate: String): EpistemicAssessment?
    fun query(query: String, limit: Int = 8): List<EpistemicAssessment>
}

/**
 * Phase186-187 epistemic state and contradiction reconciliation.
 *
 * Claims remain immutable provenance-bearing evidence in Memory OS. Phase187 adds bounded,
 * deterministic reconciliation:
 * - evidence decays with freshness instead of remaining equally current forever;
 * - repeated claims from one producer are capped to that producer's strongest claim per value;
 * - contradictory beliefs are RECONCILED only with a strong weighted margin and corroboration from
 *   multiple independent producers;
 * - unresolved conflicts remain CONTESTED and all competing evidence is retained.
 *
 * Reconciliation is descriptive only. It cannot grant permission, approve side effects, execute
 * tools, or expand the external authority boundary.
 */
class MemoryBackedEpistemicState(
    private val memory: MemoryOs,
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    private val semantic: SemanticKnowledgeStore? = null
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
        // Semantic memory is a derived projection. Failure to refresh it must not destroy the
        // immutable primary epistemic observation that was already persisted above.
        semantic?.let { sink ->
            runCatching {
                assess(claim.subject, claim.predicate)?.let(sink::promote)
            }
        }
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

        val now = clock()
        val weighted = evidence.map { (claim, record) ->
            WeightedEvidence(
                claim = claim,
                record = record,
                freshness = freshness(now, claim.provenance.observedAtEpochMs),
                weight = evidenceWeight(claim, now)
            )
        }
        val newest = weighted.maxOf { it.claim.provenance.observedAtEpochMs }
        val allStale = weighted.all {
            ageMs(now, it.claim.provenance.observedAtEpochMs) > staleAfterMs
        }

        // One producer cannot dominate reconciliation merely by repeating the same value.
        val producerCapped = weighted
            .groupBy { normalize(it.claim.value) to normalizeProducer(it.claim.provenance.producer) }
            .values
            .map { sameProducerValue ->
                sameProducerValue.maxWithOrNull(
                    compareBy<WeightedEvidence> { it.weight }
                        .thenBy { it.claim.provenance.observedAtEpochMs }
                )!!
            }

        val byValue = producerCapped.groupBy { normalize(it.claim.value) }
        val support = byValue.mapValues { (_, entries) -> entries.sumOf { it.weight } }
        val ranked = support.entries.sortedWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }
                .thenBy { it.key }
        )
        val preferredKey = ranked.first().key
        val preferredEntries = byValue.getValue(preferredKey)
        val preferredValue = preferredEntries
            .maxByOrNull { it.claim.provenance.observedAtEpochMs }
            ?.claim
            ?.value
        val winningSupport = ranked.first().value
        val competingSupport = ranked.drop(1).sumOf { it.value }
        val totalSupport = max(winningSupport + competingSupport, MIN_WEIGHT)
        val dominance = (winningSupport / totalSupport).coerceIn(0.0, 1.0)
        val runnerUpSupport = ranked.getOrNull(1)?.value ?: 0.0
        val margin = ((winningSupport - runnerUpSupport) / totalSupport).coerceIn(0.0, 1.0)
        val winningProducerCount = preferredEntries
            .map { normalizeProducer(it.claim.provenance.producer) }
            .distinct()
            .size
        val preferredEvidenceConfidence = preferredEntries
            .map { evidenceConfidence(it.claim) }
            .average()
        val resolvedConfidence = (preferredEvidenceConfidence * dominance).coerceIn(0.0, 1.0)
        val hasFreshWinner = preferredEntries.any {
            ageMs(now, it.claim.provenance.observedAtEpochMs) <= staleAfterMs
        }

        val status: EpistemicStatus
        val reason: EpistemicResolutionReason
        when {
            allStale -> {
                status = EpistemicStatus.STALE
                reason = EpistemicResolutionReason.EXPIRED_EVIDENCE
            }
            byValue.size == 1 && preferredEvidenceConfidence < MIN_SUPPORTED_CONFIDENCE -> {
                status = EpistemicStatus.UNCERTAIN
                reason = EpistemicResolutionReason.LOW_CONFIDENCE
            }
            byValue.size == 1 -> {
                status = EpistemicStatus.SUPPORTED
                reason = EpistemicResolutionReason.CONSISTENT_EVIDENCE
            }
            winningProducerCount >= MIN_RECONCILIATION_PRODUCERS &&
                dominance >= MIN_RECONCILIATION_DOMINANCE &&
                margin >= MIN_RECONCILIATION_MARGIN &&
                hasFreshWinner -> {
                status = EpistemicStatus.RECONCILED
                reason = EpistemicResolutionReason.INDEPENDENT_CORROBORATION
            }
            else -> {
                status = EpistemicStatus.CONTESTED
                reason = EpistemicResolutionReason.UNRESOLVED_CONFLICT
            }
        }

        val exemplar = evidence.first().first
        return EpistemicAssessment(
            subject = exemplar.subject,
            predicate = exemplar.predicate,
            preferredValue = preferredValue,
            status = status,
            confidence = resolvedConfidence,
            evidenceCount = evidence.size,
            competingValues = weighted
                .groupBy { normalize(it.claim.value) }
                .values
                .mapNotNull { entries ->
                    entries.maxByOrNull { it.claim.provenance.observedAtEpochMs }?.claim?.value
                }
                .toSortedSet(),
            evidenceIds = evidence.map { it.second.id }.sortedBy { it.value },
            newestObservedAtEpochMs = newest,
            resolutionReason = reason,
            independentProducerCount = winningProducerCount,
            winningSupport = winningSupport,
            competingSupport = competingSupport,
            authorityBearing = false
        )
    }

    private fun freshness(now: Long, observedAt: Long): Double {
        val age = ageMs(now, observedAt)
        if (age <= 0L) return 1.0
        return (1.0 - age.toDouble() / staleAfterMs.toDouble())
            .coerceIn(MIN_FRESHNESS_WEIGHT, 1.0)
    }

    private fun evidenceWeight(claim: EpistemicClaim, now: Long): Double =
        evidenceConfidence(claim) * freshness(now, claim.provenance.observedAtEpochMs)

    private fun evidenceConfidence(claim: EpistemicClaim): Double =
        ((claim.confidence + claim.provenance.confidence) / 2.0).coerceIn(0.0, 1.0)

    private fun ageMs(now: Long, observedAt: Long): Long =
        (now - observedAt).coerceAtLeast(0L)

    private fun decode(record: MemoryRecord): Pair<EpistemicClaim, MemoryRecord>? =
        EpistemicClaimCodec.decode(record)?.let { it to record }

    private fun normalize(value: String): String = value.trim().lowercase()
    private fun normalizeProducer(value: String): String = value.trim().lowercase()

    private data class WeightedEvidence(
        val claim: EpistemicClaim,
        val record: MemoryRecord,
        val freshness: Double,
        val weight: Double
    )

    companion object {
        const val CLAIM_KIND = "epistemic-claim"
        const val DEFAULT_STALE_AFTER_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MIN_SUPPORTED_CONFIDENCE = 0.60
        private const val MIN_RECONCILIATION_PRODUCERS = 2
        private const val MIN_RECONCILIATION_DOMINANCE = 0.72
        private const val MIN_RECONCILIATION_MARGIN = 0.35
        private const val MIN_FRESHNESS_WEIGHT = 0.10
        private const val MIN_WEIGHT = 0.000001
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