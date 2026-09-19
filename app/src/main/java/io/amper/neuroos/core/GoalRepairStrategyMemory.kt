package io.amper.neuroos.core

data class GoalRepairStrategyPattern(
    val strategy: StrategySignature,
    val verifiedSuccesses: Int = 0,
    val consecutiveVerifiedSuccesses: Int = 0,
    val realFailures: Int = 0,
    val lastSuccessAtEpochMs: Long = 0L,
    val lastFailureAtEpochMs: Long = 0L
) {
    init {
        require(verifiedSuccesses >= 0)
        require(consecutiveVerifiedSuccesses >= 0)
        require(consecutiveVerifiedSuccesses <= verifiedSuccesses)
        require(realFailures >= 0)
        require(lastSuccessAtEpochMs >= 0L)
        require(lastFailureAtEpochMs >= 0L)
    }

    val active: Boolean
        get() = consecutiveVerifiedSuccesses >=
            MemoryBackedGoalRepairStrategyMemory.MIN_CONSECUTIVE_VERIFIED_SUCCESSES &&
            (lastFailureAtEpochMs == 0L || lastSuccessAtEpochMs > lastFailureAtEpochMs)

    val evidenceConfidence: Double
        get() = if (active) {
            consecutiveVerifiedSuccesses.toDouble() /
                (consecutiveVerifiedSuccesses.toDouble() + 2.0)
        } else {
            0.0
        }

    val authorityBearing: Boolean
        get() = false
}

interface GoalRepairStrategyMemory {
    fun observe(
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): GoalRepairStrategyPattern?

    fun snapshot(strategy: StrategySignature): GoalRepairStrategyPattern?

    fun recent(limit: Int = 16): List<GoalRepairStrategyPattern>

    fun support(strategy: StrategySignature): Double
}

/**
 * Phase381-385 distilled structural repair memory.
 *
 * Admission requires live real-world requalification from GoalRepairValidationModel. The durable
 * payload contains only ordered capability sequences and governed outcome counters: no raw goal,
 * tool input/output, approval state or tool id. Two consecutive verified successes are required
 * before a pattern can influence portfolio ranking. Real execution/evidence failure immediately
 * resets the streak; authority/user and partial-execution outcomes are neutral.
 */
class MemoryBackedGoalRepairStrategyMemory(
    private val memory: MemoryOs,
    private val repairValidation: GoalRepairValidationModel
) : GoalRepairStrategyMemory {
    @Synchronized
    override fun observe(
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long
    ): GoalRepairStrategyPattern? {
        require(plan.complete)
        require(observedAtEpochMs >= 0L)
        if (
            outcome == GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED ||
            outcome == GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED
        ) {
            return snapshot(StrategySignature.from(plan))
        }

        val strategy = StrategySignature.from(plan)
        val markerId = markerId(plan.id)
        memory.get(markerId)
            ?.takeIf { it.kind == OUTCOME_MARKER_KIND }
            ?.let { marker ->
                val decoded = GoalRepairStrategyMemoryCodec.decodeMarker(marker.content)
                require(decoded != null)
                require(decoded.strategyDigest == strategy.digest)
                require(decoded.outcome == outcome)
                return snapshot(strategy)
            }

        val previous = snapshot(strategy)
        val admitted = when (outcome) {
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS ->
                repairValidation.requalifiedTransferConfidence(strategy) > 0.0
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
            GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED -> previous != null
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED,
            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED -> false
        }
        val updated = when {
            !admitted -> null
            outcome == GoalOutcomeEvidenceKind.VERIFIED_SUCCESS ->
                (previous ?: GoalRepairStrategyPattern(strategy)).copy(
                    verifiedSuccesses = (previous?.verifiedSuccesses ?: 0) + 1,
                    consecutiveVerifiedSuccesses =
                        (previous?.consecutiveVerifiedSuccesses ?: 0) + 1,
                    lastSuccessAtEpochMs = maxOf(
                        previous?.lastSuccessAtEpochMs ?: 0L,
                        observedAtEpochMs
                    )
                )
            else -> requireNotNull(previous).copy(
                consecutiveVerifiedSuccesses = 0,
                realFailures = previous.realFailures + 1,
                lastFailureAtEpochMs = maxOf(
                    previous.lastFailureAtEpochMs,
                    observedAtEpochMs
                )
            )
        }

        memory.transaction {
            remember(
                MemoryRecord(
                    id = markerId,
                    kind = OUTCOME_MARKER_KIND,
                    content = GoalRepairStrategyMemoryCodec.encodeMarker(
                        strategyDigest = strategy.digest,
                        outcome = outcome,
                        admitted = admitted
                    ),
                    importance = 0.48,
                    provenance = Provenance(
                        source = "governed-repair-strategy-distillation",
                        producer = "goal-repair-strategy-memory",
                        confidence = 1.0
                    ),
                    createdAtEpochMs = observedAtEpochMs
                )
            )
            updated?.let { pattern ->
                remember(
                    MemoryRecord(
                        id = snapshotId(strategy),
                        kind = SNAPSHOT_KIND,
                        content = GoalRepairStrategyMemoryCodec.encodePattern(pattern),
                        importance = if (pattern.active) 0.80 else 0.66,
                        provenance = Provenance(
                            source = "governed-repair-strategy-distillation",
                            producer = "goal-repair-strategy-memory",
                            confidence = pattern.evidenceConfidence,
                            parents = setOf(markerId)
                        ),
                        createdAtEpochMs = observedAtEpochMs
                    )
                )
                updateIndexLocked(strategy.digest, observedAtEpochMs)
            }
        }
        return updated
    }

    override fun snapshot(strategy: StrategySignature): GoalRepairStrategyPattern? =
        memory.get(snapshotId(strategy))
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { GoalRepairStrategyMemoryCodec.decodePattern(it.content) }
            ?.takeIf { it.strategy == strategy }

    override fun recent(limit: Int): List<GoalRepairStrategyPattern> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.transaction {
            decodeIndex(get(INDEX_ID)?.content)
                .asReversed()
                .asSequence()
                .mapNotNull { digest ->
                    get(MemoryId("goal-repair-strategy-memory:" + digest))
                        ?.takeIf { it.kind == SNAPSHOT_KIND }
                        ?.let { GoalRepairStrategyMemoryCodec.decodePattern(it.content) }
                }
                .take(limit)
                .toList()
        }
    }

    override fun support(strategy: StrategySignature): Double {
        val liveRequalification = repairValidation.requalifiedTransferConfidence(strategy)
        if (liveRequalification <= 0.0) return 0.0
        return recent(MAX_INDEXED_PATTERNS)
            .asSequence()
            .filter { it.active }
            .mapNotNull { pattern ->
                val similarity = structuralSimilarity(pattern.strategy, strategy)
                if (similarity < MIN_PATTERN_SIMILARITY) null else (
                    pattern.evidenceConfidence *
                        similarity *
                        liveRequalification
                    ).coerceIn(0.0, 1.0)
            }
            .maxOrNull()
            ?: 0.0
    }

    private fun structuralSimilarity(
        source: StrategySignature,
        target: StrategySignature
    ): Double {
        val sourceSet = source.capabilities.toSet()
        val targetSet = target.capabilities.toSet()
        val union = sourceSet union targetSet
        val jaccard = if (union.isEmpty()) 0.0 else {
            sourceSet.intersect(targetSet).size.toDouble() / union.size.toDouble()
        }
        val longest = maxOf(source.capabilities.size, target.capabilities.size)
            .coerceAtLeast(1)
        val prefix = source.capabilities.zip(target.capabilities)
            .takeWhile { (left, right) -> left == right }
            .size.toDouble() / longest.toDouble()
        return (0.65 * jaccard + 0.35 * prefix).coerceIn(0.0, 1.0)
    }

    private fun MemoryOs.updateIndexLocked(digest: String, now: Long) {
        val next = (
            decodeIndex(get(INDEX_ID)?.content).filterNot { it == digest } + digest
            ).takeLast(MAX_INDEXED_PATTERNS)
        remember(
            MemoryRecord(
                id = INDEX_ID,
                kind = INDEX_KIND,
                content = "digests=" + next.joinToString(","),
                importance = 0.62,
                provenance = Provenance(
                    source = "governed-repair-strategy-distillation",
                    producer = "goal-repair-strategy-memory-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = now
            )
        )
    }

    private fun decodeIndex(content: String?): List<String> {
        if (content == null || !content.startsWith("digests=")) return emptyList()
        return content.removePrefix("digests=")
            .split(',')
            .filter { it.matches(Regex("[0-9a-f]{64}")) }
            .distinct()
            .takeLast(MAX_INDEXED_PATTERNS)
    }

    private fun snapshotId(strategy: StrategySignature): MemoryId =
        MemoryId("goal-repair-strategy-memory:" + strategy.digest)

    private fun markerId(planId: PlanId): MemoryId =
        MemoryId("goal-repair-strategy-memory-outcome:" + planId.value)

    companion object {
        const val SNAPSHOT_KIND = "goal-repair-strategy-memory-v1"
        const val OUTCOME_MARKER_KIND = "goal-repair-strategy-memory-outcome-v1"
        const val INDEX_KIND = "goal-repair-strategy-memory-index-v1"
        const val MIN_CONSECUTIVE_VERIFIED_SUCCESSES = 2
        const val MIN_PATTERN_SIMILARITY = 0.75
        const val MAX_INDEXED_PATTERNS = 32
        private val INDEX_ID = MemoryId("goal-repair-strategy-memory:index")
    }
}

object GoalRepairStrategyMemoryPolicy {
    const val MAX_PORTFOLIO_BONUS = 0.03

    fun apply(
        candidates: List<GoalStrategyPortfolioCandidate>,
        memory: GoalRepairStrategyMemory
    ): List<GoalStrategyPortfolioCandidate> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.map { candidate ->
            if (candidate.assessment.decision != GoalTransferValidationDecision.ACCEPT) {
                return@map candidate
            }
            val support = memory.support(candidate.assessment.candidate.strategy)
            if (support <= 0.0) return@map candidate
            val bonus = (support * MAX_PORTFOLIO_BONUS)
                .coerceIn(0.0, MAX_PORTFOLIO_BONUS)
            candidate.copy(
                repairMemorySupport = support,
                repairMemoryBonus = bonus,
                portfolioScore = (candidate.portfolioScore + bonus).coerceIn(0.0, 1.0)
            )
        }.sortedWith(
            compareByDescending<GoalStrategyPortfolioCandidate> { it.portfolioScore }
                .thenByDescending { it.exploitationScore }
                .thenByDescending { it.assessment.calibratedSupport }
                .thenBy { it.assessment.candidate.strategy.canonical }
        )
    }
}

private object GoalRepairStrategyMemoryCodec {
    private const val PATTERN_VERSION = "AMPER_GOAL_REPAIR_STRATEGY_MEMORY_V1"
    private const val MARKER_VERSION = "AMPER_GOAL_REPAIR_STRATEGY_MEMORY_OUTCOME_V1"

    data class Marker(
        val strategyDigest: String,
        val outcome: GoalOutcomeEvidenceKind,
        val admitted: Boolean
    )

    fun encodePattern(pattern: GoalRepairStrategyPattern): String = listOf(
        PATTERN_VERSION,
        pattern.strategy.capabilities.joinToString(",") { it.value },
        pattern.verifiedSuccesses.toString(),
        pattern.consecutiveVerifiedSuccesses.toString(),
        pattern.realFailures.toString(),
        pattern.lastSuccessAtEpochMs.toString(),
        pattern.lastFailureAtEpochMs.toString()
    ).joinToString("\t")

    fun decodePattern(content: String): GoalRepairStrategyPattern? = runCatching {
        val p = content.split('\t')
        require(p.size == 7 && p[0] == PATTERN_VERSION)
        GoalRepairStrategyPattern(
            strategy = StrategySignature(
                p[1].split(',').filter { it.isNotBlank() }.map(::CapabilityId)
            ),
            verifiedSuccesses = p[2].toInt(),
            consecutiveVerifiedSuccesses = p[3].toInt(),
            realFailures = p[4].toInt(),
            lastSuccessAtEpochMs = p[5].toLong(),
            lastFailureAtEpochMs = p[6].toLong()
        )
    }.getOrNull()

    fun encodeMarker(
        strategyDigest: String,
        outcome: GoalOutcomeEvidenceKind,
        admitted: Boolean
    ): String = listOf(
        MARKER_VERSION,
        strategyDigest,
        outcome.name,
        admitted.toString()
    ).joinToString("\t")

    fun decodeMarker(content: String): Marker? = runCatching {
        val p = content.split('\t')
        require(p.size == 4 && p[0] == MARKER_VERSION)
        require(p[1].matches(Regex("[0-9a-f]{64}")))
        val admitted = when (p[3]) {
            "true" -> true
            "false" -> false
            else -> error("invalid repair-strategy admission marker")
        }
        Marker(
            strategyDigest = p[1],
            outcome = GoalOutcomeEvidenceKind.valueOf(p[2]),
            admitted = admitted
        )
    }.getOrNull()
}
