package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@JvmInline
value class EvolutionBenchmarkSuiteId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 128) }
}

@JvmInline
value class EvolutionBenchmarkMetricId(val value: String) {
    init { require(value.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
}

@JvmInline
value class EvolutionBenchmarkSubjectId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 256) }
}

@JvmInline
value class EvolutionTournamentId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 128) }
}

data class EvolutionBenchmarkMetric(
    val id: EvolutionBenchmarkMetricId,
    val weight: Double,
    val minSamples: Int,
    val minCandidateScore: Double,
    val regressionTolerance: Double
) {
    init {
        require(weight > 0.0 && weight.isFinite())
        require(minSamples > 0)
        require(minCandidateScore in 0.0..1.0)
        require(regressionTolerance in 0.0..1.0)
    }
}

data class EvolutionBenchmarkSuite(
    val id: EvolutionBenchmarkSuiteId,
    val metrics: List<EvolutionBenchmarkMetric>,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(metrics.isNotEmpty())
        require(metrics.size <= MAX_METRICS)
        require(metrics.map { it.id }.distinct().size == metrics.size)
        require(createdAtEpochMs >= 0L)
    }

    val canonicalDigest: String
        get() = evolutionSha256(
            metrics.sortedBy { it.id.value }.joinToString("||") { metric ->
                listOf(
                    metric.id.value,
                    metric.weight.toString(),
                    metric.minSamples.toString(),
                    metric.minCandidateScore.toString(),
                    metric.regressionTolerance.toString()
                ).joinToString("|")
            }
        )

    companion object {
        const val MAX_METRICS = 32
    }
}

data class EvolutionMetricObservation(
    val score: Double,
    val samples: Int
) {
    init {
        require(score in 0.0..1.0)
        require(samples >= 0)
    }
}

data class EvolutionBenchmarkSnapshot(
    val subjectId: EvolutionBenchmarkSubjectId,
    val suiteId: EvolutionBenchmarkSuiteId,
    val suiteDigest: String,
    val metrics: Map<EvolutionBenchmarkMetricId, EvolutionMetricObservation>,
    val artifactDigest: String? = null,
    val observedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(suiteDigest.matches(SHA256))
        require(metrics.isNotEmpty())
        require(metrics.size <= EvolutionBenchmarkSuite.MAX_METRICS)
        require(artifactDigest == null || artifactDigest.matches(SHA256))
        require(observedAtEpochMs >= 0L)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

enum class AutonomousEvolutionCandidateKind {
    MODEL,
    CODE,
    STRATEGY,
    ARCHITECTURE
}

data class EvolutionTournamentEntry(
    val candidate: EvolutionCandidate,
    val kind: AutonomousEvolutionCandidateKind,
    val benchmark: EvolutionBenchmarkSnapshot,
    val verification: VerificationEvidence
) {
    init {
        require(candidate.artifactDigest.matches(Regex("[0-9a-f]{64}"))) {
            "evolution tournament candidate artifact digest must be SHA-256"
        }
        require(benchmark.subjectId.value == candidate.id.value) {
            "candidate benchmark subject must equal candidate id"
        }
        require(benchmark.artifactDigest == candidate.artifactDigest) {
            "candidate benchmark artifact digest mismatch"
        }
    }

    val authorityBearing: Boolean
        get() = false
}

data class EvolutionTournamentOutcome(
    val candidateId: EvolutionId,
    val kind: AutonomousEvolutionCandidateKind,
    val aggregateDelta: Double,
    val noMaterialRegression: Boolean,
    val benchmarkEligible: Boolean,
    val verificationDecision: EvolutionDecision,
    val tournamentEligible: Boolean,
    val reasons: List<String>
) {
    init {
        require(aggregateDelta.isFinite())
        require(reasons.isNotEmpty())
        if (tournamentEligible) {
            require(noMaterialRegression)
            require(benchmarkEligible)
            require(verificationDecision.stage == EvolutionStage.VERIFIED)
            require(verificationDecision.promotable)
        }
    }

    val authorityBearing: Boolean
        get() = false
}

data class EvolutionTournamentDecision(
    val id: EvolutionTournamentId,
    val suiteId: EvolutionBenchmarkSuiteId,
    val suiteDigest: String,
    val baselineSubjectId: EvolutionBenchmarkSubjectId,
    val winnerCandidateId: EvolutionId?,
    val outcomes: List<EvolutionTournamentOutcome>,
    val createdAtEpochMs: Long
) {
    init {
        require(suiteDigest.matches(Regex("[0-9a-f]{64}")))
        require(outcomes.isNotEmpty())
        require(outcomes.size <= MAX_CANDIDATES)
        require(outcomes.map { it.candidateId }.distinct().size == outcomes.size)
        require(
            winnerCandidateId == null ||
                outcomes.any { it.candidateId == winnerCandidateId && it.tournamentEligible }
        )
        require(createdAtEpochMs >= 0L)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MAX_CANDIDATES = 8
    }
}

data class EvolutionPromotionProposal(
    val tournamentId: EvolutionTournamentId,
    val candidateId: EvolutionId,
    val verifiedDecision: EvolutionDecision,
    val suiteDigest: String,
    val aggregateDelta: Double,
    val rollbackToken: String,
    val createdAtEpochMs: Long
) {
    init {
        require(verifiedDecision.candidateId == candidateId)
        require(verifiedDecision.stage == EvolutionStage.VERIFIED)
        require(verifiedDecision.promotable)
        require(suiteDigest.matches(Regex("[0-9a-f]{64}")))
        require(aggregateDelta > 0.0 && aggregateDelta.isFinite())
        require(rollbackToken.isNotBlank())
        require(createdAtEpochMs >= 0L)
    }

    val livePromoted: Boolean
        get() = false

    val authorityBearing: Boolean
        get() = false
}

interface AutonomousEvolutionModel {
    fun putSuite(suite: EvolutionBenchmarkSuite)
    fun getSuite(id: EvolutionBenchmarkSuiteId): EvolutionBenchmarkSuite?

    fun runTournament(
        id: EvolutionTournamentId,
        baseline: EvolutionBenchmarkSnapshot,
        candidates: List<EvolutionTournamentEntry>
    ): EvolutionTournamentDecision

    fun promotionProposal(
        decision: EvolutionTournamentDecision
    ): EvolutionPromotionProposal?
}

/**
 * Phase231-235 autonomous-evolution tournament foundation.
 *
 * Phase231 freezes a bounded normalized benchmark suite and exact baseline snapshot.
 * Phase232 accepts content-addressed MODEL/CODE/STRATEGY/ARCHITECTURE candidates.
 * Phase233 compares sandbox benchmark evidence only; the tournament has no ToolFabric/AuthorityGate.
 * Phase234 requires per-metric score floors, sample floors and no regression beyond each metric's
 * tolerance in addition to the existing CanonicalEvolutionGate invariants/tests/rollback contract.
 * Phase235 selects one deterministic objectively-better candidate and emits a non-live promotion
 * proposal. Deployment/promotion execution remains Phase236+.
 */
class MemoryBackedAutonomousEvolutionModel(
    private val memory: MemoryOs,
    private val gate: VerifiedEvolution,
    private val clock: () -> Long = System::currentTimeMillis
) : AutonomousEvolutionModel {
    override fun putSuite(suite: EvolutionBenchmarkSuite) {
        val record = MemoryRecord(
            id = suiteMemoryId(suite.id),
            kind = SUITE_KIND,
            content = EvolutionTournamentCodec.encodeSuite(suite),
            importance = 0.96,
            provenance = Provenance(
                source = "autonomous-evolution-benchmark",
                producer = "evolution-tournament",
                confidence = 1.0
            ),
            createdAtEpochMs = suite.createdAtEpochMs
        )
        memory.rememberIfAbsent(record).also { inserted ->
            if (!inserted) {
                require(getSuite(suite.id) == suite) {
                    "evolution benchmark suite identity is immutable"
                }
            }
        }
    }

    override fun getSuite(id: EvolutionBenchmarkSuiteId): EvolutionBenchmarkSuite? =
        memory.get(suiteMemoryId(id))
            ?.takeIf { it.kind == SUITE_KIND }
            ?.let { EvolutionTournamentCodec.decodeSuite(it.content) }

    override fun runTournament(
        id: EvolutionTournamentId,
        baseline: EvolutionBenchmarkSnapshot,
        candidates: List<EvolutionTournamentEntry>
    ): EvolutionTournamentDecision {
        require(candidates.isNotEmpty())
        require(candidates.size <= EvolutionTournamentDecision.MAX_CANDIDATES)
        require(candidates.map { it.candidate.id }.distinct().size == candidates.size)

        val suite = requireNotNull(getSuite(baseline.suiteId)) {
            "evolution benchmark suite unavailable"
        }
        require(suite.canonicalDigest == baseline.suiteDigest) {
            "baseline benchmark suite digest mismatch"
        }
        validateSnapshot(suite, baseline, requireSampleFloor = true)

        val outcomes = candidates.map { entry ->
            evaluateCandidate(suite, baseline, entry)
        }
        val winner = outcomes
            .asSequence()
            .filter { it.tournamentEligible }
            .sortedWith(
                compareByDescending<EvolutionTournamentOutcome> { it.aggregateDelta }
                    .thenBy { it.candidateId.value }
            )
            .firstOrNull()
            ?.candidateId

        val decision = EvolutionTournamentDecision(
            id = id,
            suiteId = suite.id,
            suiteDigest = suite.canonicalDigest,
            baselineSubjectId = baseline.subjectId,
            winnerCandidateId = winner,
            outcomes = outcomes.sortedBy { it.candidateId.value },
            createdAtEpochMs = clock()
        )
        memory.rememberIfAbsent(
            MemoryRecord(
                id = tournamentMemoryId(id),
                kind = DECISION_KIND,
                content = EvolutionTournamentCodec.encodeDecision(decision),
                importance = if (winner == null) 0.82 else 0.97,
                provenance = Provenance(
                    source = "autonomous-evolution-tournament",
                    producer = "evolution-tournament",
                    confidence = 1.0,
                    parents = setOf(suiteMemoryId(suite.id))
                ),
                createdAtEpochMs = decision.createdAtEpochMs
            )
        ).also { inserted ->
            require(inserted) { "evolution tournament id already exists" }
        }
        return decision
    }

    override fun promotionProposal(
        decision: EvolutionTournamentDecision
    ): EvolutionPromotionProposal? {
        val winnerId = decision.winnerCandidateId ?: return null
        val outcome = decision.outcomes.single { it.candidateId == winnerId }
        require(outcome.tournamentEligible) {
            "evolution tournament winner is no longer eligible"
        }
        val rollbackToken = requireNotNull(outcome.verificationDecision.rollbackToken)
        return EvolutionPromotionProposal(
            tournamentId = decision.id,
            candidateId = winnerId,
            verifiedDecision = outcome.verificationDecision,
            suiteDigest = decision.suiteDigest,
            aggregateDelta = outcome.aggregateDelta,
            rollbackToken = rollbackToken,
            createdAtEpochMs = clock().coerceAtLeast(decision.createdAtEpochMs)
        )
    }

    private fun evaluateCandidate(
        suite: EvolutionBenchmarkSuite,
        baseline: EvolutionBenchmarkSnapshot,
        entry: EvolutionTournamentEntry
    ): EvolutionTournamentOutcome {
        val reasons = mutableListOf<String>()
        if (entry.benchmark.suiteId != suite.id ||
            entry.benchmark.suiteDigest != suite.canonicalDigest
        ) {
            reasons += "candidate benchmark suite identity mismatch"
        }

        val snapshotValid = runCatching {
            validateSnapshot(suite, entry.benchmark, requireSampleFloor = false)
        }.isSuccess
        if (!snapshotValid) {
            reasons += "candidate benchmark snapshot is incomplete"
        }

        var weightedDelta = 0.0
        var totalWeight = 0.0
        var noMaterialRegression = true
        var benchmarkEligible = snapshotValid

        if (snapshotValid) {
            suite.metrics.forEach { metric ->
                val base = requireNotNull(baseline.metrics[metric.id])
                val candidate = requireNotNull(entry.benchmark.metrics[metric.id])
                if (candidate.samples < metric.minSamples) {
                    reasons += "metric ${metric.id.value} has insufficient candidate samples"
                    benchmarkEligible = false
                }
                if (candidate.score < metric.minCandidateScore) {
                    reasons += "metric ${metric.id.value} is below candidate score floor"
                    benchmarkEligible = false
                }
                val delta = candidate.score - base.score
                if (delta < -metric.regressionTolerance) {
                    reasons += "metric ${metric.id.value} exceeds regression tolerance"
                    noMaterialRegression = false
                    benchmarkEligible = false
                }
                weightedDelta += delta * metric.weight
                totalWeight += metric.weight
            }
        }

        val aggregateDelta = if (totalWeight > 0.0) weightedDelta / totalWeight else 0.0
        if (aggregateDelta <= MIN_AGGREGATE_IMPROVEMENT) {
            reasons += "aggregate benchmark improvement is insufficient"
            benchmarkEligible = false
        }

        val verificationDecision = gate.evaluate(entry.candidate, entry.verification)
        if (!verificationDecision.promotable) {
            reasons += verificationDecision.reasons
                .map { "evolution gate: $it" }
        }

        val eligible = benchmarkEligible &&
            noMaterialRegression &&
            verificationDecision.stage == EvolutionStage.VERIFIED &&
            verificationDecision.promotable

        if (eligible) {
            reasons += "candidate improves benchmark without material regression and passes evolution gate"
        }

        return EvolutionTournamentOutcome(
            candidateId = entry.candidate.id,
            kind = entry.kind,
            aggregateDelta = aggregateDelta,
            noMaterialRegression = noMaterialRegression,
            benchmarkEligible = benchmarkEligible,
            verificationDecision = verificationDecision,
            tournamentEligible = eligible,
            reasons = reasons.distinct()
        )
    }

    private fun validateSnapshot(
        suite: EvolutionBenchmarkSuite,
        snapshot: EvolutionBenchmarkSnapshot,
        requireSampleFloor: Boolean
    ) {
        require(snapshot.suiteId == suite.id)
        require(snapshot.suiteDigest == suite.canonicalDigest)
        val expectedIds = suite.metrics.map { it.id }.toSet()
        require(snapshot.metrics.keys == expectedIds) {
            "benchmark snapshot metric set does not exactly match suite"
        }
        if (requireSampleFloor) {
            suite.metrics.forEach { metric ->
                require(requireNotNull(snapshot.metrics[metric.id]).samples >= metric.minSamples) {
                    "baseline metric ${metric.id.value} has insufficient samples"
                }
            }
        }
    }

    private fun suiteMemoryId(id: EvolutionBenchmarkSuiteId): MemoryId =
        MemoryId("evolution-benchmark-suite:" + id.value)

    private fun tournamentMemoryId(id: EvolutionTournamentId): MemoryId =
        MemoryId("evolution-tournament:" + id.value)

    companion object {
        const val SUITE_KIND = "evolution-benchmark-suite"
        const val DECISION_KIND = "evolution-tournament-decision"
        const val MIN_AGGREGATE_IMPROVEMENT = 0.005
    }
}

private object EvolutionTournamentCodec {
    fun encodeSuite(suite: EvolutionBenchmarkSuite): String = listOf(
        "v=1",
        "id=" + enc(suite.id.value),
        "created=" + suite.createdAtEpochMs,
        "metrics=" + suite.metrics
            .sortedBy { it.id.value }
            .joinToString(",") { metric ->
                listOf(
                    enc(metric.id.value),
                    enc(metric.weight.toString()),
                    metric.minSamples.toString(),
                    enc(metric.minCandidateScore.toString()),
                    enc(metric.regressionTolerance.toString())
                ).joinToString(".")
            }
    ).joinToString(";")

    fun decodeSuite(content: String): EvolutionBenchmarkSuite? = runCatching {
        val fields = fields(content)
        require(fields["v"] == "1")
        val metrics = requireNotNull(fields["metrics"])
            .split(',')
            .filter { it.isNotBlank() }
            .map { encoded ->
                val parts = encoded.split('.')
                require(parts.size == 5)
                EvolutionBenchmarkMetric(
                    id = EvolutionBenchmarkMetricId(dec(parts[0])),
                    weight = dec(parts[1]).toDouble(),
                    minSamples = parts[2].toInt(),
                    minCandidateScore = dec(parts[3]).toDouble(),
                    regressionTolerance = dec(parts[4]).toDouble()
                )
            }
        EvolutionBenchmarkSuite(
            id = EvolutionBenchmarkSuiteId(dec(requireNotNull(fields["id"]))),
            metrics = metrics,
            createdAtEpochMs = requireNotNull(fields["created"]).toLong()
        )
    }.getOrNull()

    fun encodeDecision(decision: EvolutionTournamentDecision): String = listOf(
        "v=1",
        "id=" + enc(decision.id.value),
        "suite=" + enc(decision.suiteId.value),
        "suite_digest=" + decision.suiteDigest,
        "baseline=" + enc(decision.baselineSubjectId.value),
        "winner=" + (decision.winnerCandidateId?.value?.let(::enc) ?: "~"),
        "created=" + decision.createdAtEpochMs,
        "outcomes=" + decision.outcomes.joinToString(",") { outcome ->
            listOf(
                enc(outcome.candidateId.value),
                outcome.kind.name,
                enc(outcome.aggregateDelta.toString()),
                outcome.noMaterialRegression.toString(),
                outcome.benchmarkEligible.toString(),
                outcome.verificationDecision.stage.name,
                outcome.tournamentEligible.toString()
            ).joinToString(".")
        }
    ).joinToString(";")

    private fun fields(content: String): Map<String, String> =
        content.split(';').associate { field ->
            val separator = field.indexOf('=')
            require(separator > 0)
            field.substring(0, separator) to field.substring(separator + 1)
        }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}

private fun evolutionSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
