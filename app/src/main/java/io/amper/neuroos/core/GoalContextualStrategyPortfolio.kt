package io.amper.neuroos.core

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

enum class GoalStrategyPortfolioMode {
    EXPLOIT,
    EXPLORE
}

data class GoalStrategyPortfolioStats(
    val contextDigest: String,
    val contextFingerprint: Set<String>,
    val strategy: StrategySignature,
    val selections: Int = 0,
    val verifiedSuccesses: Int = 0,
    val executionFailures: Int = 0,
    val goalEvidenceFailures: Int = 0,
    val authorityNeutral: Int = 0,
    val partialNeutral: Int = 0,
    val cumulativeRegretProxy: Double = 0.0,
    val lastSelectedAtEpochMs: Long = 0L,
    val lastSuccessAtEpochMs: Long = 0L,
    val lastFailureAtEpochMs: Long = 0L
) {
    init {
        require(contextDigest.matches(SHA256))
        require(contextFingerprint.isNotEmpty())
        require(contextFingerprint.size <= GoalOutcomeFingerprint.MAX_TERMS)
        require(contextFingerprint.all { it.matches(HASHED_TERM) })
        require(selections >= 0)
        require(verifiedSuccesses >= 0)
        require(executionFailures >= 0)
        require(goalEvidenceFailures >= 0)
        require(authorityNeutral >= 0)
        require(partialNeutral >= 0)
        require(cumulativeRegretProxy >= 0.0)
        require(lastSelectedAtEpochMs >= 0L)
        require(lastSuccessAtEpochMs >= 0L)
        require(lastFailureAtEpochMs >= 0L)
    }

    val comparableOutcomes: Int
        get() = verifiedSuccesses + executionFailures + goalEvidenceFailures

    val realizedRewardRate: Double?
        get() = comparableOutcomes.takeIf { it > 0 }?.let {
            verifiedSuccesses.toDouble() / it.toDouble()
        }

    val meanRegretProxy: Double
        get() = if (comparableOutcomes == 0) 0.0
        else cumulativeRegretProxy / comparableOutcomes.toDouble()

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val HASHED_TERM = Regex("[0-9a-f]{16}")
    }
}

data class GoalStrategyPortfolioCandidate(
    val assessment: GoalTransferCounterfactualAssessment,
    val contextDigest: String,
    val contextFingerprint: Set<String>,
    val similarSelections: Int,
    val contextualRewardRate: Double?,
    val contextualEvidenceConfidence: Double,
    val contextualRegretProxy: Double,
    val exploitationScore: Double,
    val explorationBonus: Double,
    val portfolioScore: Double,
    val mode: GoalStrategyPortfolioMode,
    val hierarchicalCreditScore: Double = 0.0,
    val hierarchicalCreditConfidence: Double = 0.0,
    val hierarchicalMatchedComponents: Int = 0,
    val hierarchicalCreditAdjustment: Double = 0.0,
    val repairMemorySupport: Double = 0.0,
    val repairMemoryBonus: Double = 0.0
) {
    init {
        require(contextDigest.matches(Regex("[0-9a-f]{64}")))
        require(contextFingerprint.isNotEmpty())
        require(similarSelections >= 0)
        contextualRewardRate?.let { require(it in 0.0..1.0) }
        require(contextualEvidenceConfidence in 0.0..1.0)
        require(contextualRegretProxy in 0.0..1.0)
        require(exploitationScore in 0.0..1.0)
        require(explorationBonus in 0.0..GoalContextualStrategyPortfolioPolicy.MAX_EXPLORATION_BONUS)
        require(portfolioScore in 0.0..1.0)
        require(hierarchicalCreditScore in -1.0..1.0)
        require(hierarchicalCreditConfidence in 0.0..1.0)
        require(hierarchicalMatchedComponents >= 0)
        require(
            hierarchicalCreditAdjustment >=
                -GoalHierarchicalStrategyCreditPolicy.MAX_PORTFOLIO_ADJUSTMENT &&
                hierarchicalCreditAdjustment <=
                    GoalHierarchicalStrategyCreditPolicy.MAX_PORTFOLIO_ADJUSTMENT
        )
        require(repairMemorySupport in 0.0..1.0)
        require(repairMemoryBonus in 0.0..GoalRepairStrategyMemoryPolicy.MAX_PORTFOLIO_BONUS)
    }

    val authorityBearing: Boolean
        get() = false
}

data class GoalStrategyPortfolioDecision(
    val planId: PlanId,
    val contextDigest: String,
    val contextFingerprint: Set<String>,
    val strategy: StrategySignature,
    val mode: GoalStrategyPortfolioMode,
    val exploitationScore: Double,
    val explorationBonus: Double,
    val portfolioScore: Double,
    val bestExploitationScore: Double,
    val selectedAtEpochMs: Long
) {
    init {
        require(contextDigest.matches(Regex("[0-9a-f]{64}")))
        require(contextFingerprint.isNotEmpty())
        require(contextFingerprint.size <= GoalOutcomeFingerprint.MAX_TERMS)
        require(exploitationScore in 0.0..1.0)
        require(explorationBonus in 0.0..GoalContextualStrategyPortfolioPolicy.MAX_EXPLORATION_BONUS)
        require(portfolioScore in 0.0..1.0)
        require(bestExploitationScore in 0.0..1.0)
        require(selectedAtEpochMs >= 0L)
    }

    val authorityBearing: Boolean
        get() = false
}

interface GoalContextualStrategyPortfolio {
    fun rank(
        goal: String,
        candidates: List<GoalTransferCounterfactualAssessment>,
        nowEpochMs: Long
    ): List<GoalStrategyPortfolioCandidate>

    fun bind(
        plan: SovereignPlan,
        rankedCandidates: List<GoalStrategyPortfolioCandidate>,
        boundAtEpochMs: Long
    ): GoalStrategyPortfolioDecision?

    fun observe(
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): GoalStrategyPortfolioStats?

    fun observeTerminalPlan(
        plan: SovereignPlan,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): GoalStrategyPortfolioStats?

    fun decision(planId: PlanId): GoalStrategyPortfolioDecision?
    fun snapshot(contextDigest: String, strategy: StrategySignature): GoalStrategyPortfolioStats?
}

/**
 * Phase351-355 bounded contextual strategy portfolio.
 *
 * The portfolio never invokes inference or tools. It only reorders already-validated structural
 * transfer guidance. Exploration is deterministic, bounded by a narrow exploitation-quality gap and
 * decays with prior contextual selections. Authority/user outcomes are neutral diagnostics.
 */
class MemoryBackedGoalContextualStrategyPortfolio(
    private val memory: MemoryOs,
    private val hierarchicalCredit: GoalHierarchicalStrategyCreditModel? = null,
    private val repairValidation: GoalRepairValidationModel? = null,
    private val repairStrategyMemory: GoalRepairStrategyMemory? = null
) : GoalContextualStrategyPortfolio {
    @Synchronized
    override fun rank(
        goal: String,
        candidates: List<GoalTransferCounterfactualAssessment>,
        nowEpochMs: Long
    ): List<GoalStrategyPortfolioCandidate> {
        require(goal.isNotBlank())
        require(nowEpochMs >= 0L)
        if (candidates.isEmpty()) return emptyList()

        val fingerprint = GoalOutcomeFingerprint.of(goal)
        val contextDigest = GoalContextualStrategyPortfolioPolicy.contextDigest(fingerprint)
        val indexed = recentStats(MAX_INDEXED_STATS)

        val ranked = GoalContextualStrategyPortfolioPolicy.rank(
            candidates = candidates,
            contextDigest = contextDigest,
            contextFingerprint = fingerprint,
            historical = indexed,
            nowEpochMs = nowEpochMs
        )
        val credited = hierarchicalCredit?.let { credit ->
            GoalHierarchicalStrategyCreditPolicy.apply(
                goal = goal,
                candidates = ranked,
                credit = credit
            )
        } ?: ranked
        val repaired = repairValidation?.let { validation ->
            GoalRepairRefinementPolicy.apply(
                candidates = credited,
                validation = validation
            )
        } ?: credited
        return repairStrategyMemory?.let { repairMemory ->
            GoalRepairStrategyMemoryPolicy.apply(
                candidates = repaired,
                memory = repairMemory
            )
        } ?: repaired
    }

    @Synchronized
    override fun bind(
        plan: SovereignPlan,
        rankedCandidates: List<GoalStrategyPortfolioCandidate>,
        boundAtEpochMs: Long
    ): GoalStrategyPortfolioDecision? {
        require(boundAtEpochMs >= 0L)
        val transferBinding = plan.goalTransferBinding ?: return null
        if (rankedCandidates.isEmpty()) return null
        val selected = rankedCandidates.firstOrNull {
            it.assessment.candidate.strategy == transferBinding.strategy
        } ?: return null
        require(selected.assessment.cognitiveStateDigest == transferBinding.cognitiveStateDigest)
        require(StrategySignature.from(plan) == transferBinding.strategy)

        decision(plan.id)?.let { existing ->
            require(existing.strategy == selected.assessment.candidate.strategy) {
                "contextual portfolio decision changed strategy for an existing plan"
            }
            return existing
        }

        val bestExploitation = rankedCandidates.maxOf { it.exploitationScore }
        val decision = GoalStrategyPortfolioDecision(
            planId = plan.id,
            contextDigest = selected.contextDigest,
            contextFingerprint = selected.contextFingerprint,
            strategy = selected.assessment.candidate.strategy,
            mode = selected.mode,
            exploitationScore = selected.exploitationScore,
            explorationBonus = selected.explorationBonus,
            portfolioScore = selected.portfolioScore,
            bestExploitationScore = bestExploitation,
            selectedAtEpochMs = boundAtEpochMs
        )
        memory.remember(
            MemoryRecord(
                id = decisionId(plan.id),
                kind = DECISION_KIND,
                content = PortfolioCodec.encodeDecision(decision),
                importance = 0.72,
                provenance = Provenance(
                    source = "governed-contextual-strategy-selection",
                    producer = "goal-contextual-strategy-portfolio",
                    confidence = 1.0
                ),
                createdAtEpochMs = boundAtEpochMs
            )
        )

        val previous = snapshot(decision.contextDigest, decision.strategy)
            ?: GoalStrategyPortfolioStats(
                contextDigest = decision.contextDigest,
                contextFingerprint = decision.contextFingerprint,
                strategy = decision.strategy
            )
        val selectedStats = previous.copy(
            selections = previous.selections + 1,
            lastSelectedAtEpochMs = maxOf(previous.lastSelectedAtEpochMs, boundAtEpochMs)
        )
        saveStats(selectedStats, boundAtEpochMs)
        return decision
    }

    @Synchronized
    override fun observe(
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long
    ): GoalStrategyPortfolioStats? =
        observeOutcome(plan, outcome, observedAtEpochMs)

    @Synchronized
    override fun observeTerminalPlan(
        plan: SovereignPlan,
        observedAtEpochMs: Long
    ): GoalStrategyPortfolioStats? {
        if (decision(plan.id) == null) return null
        val outcome = GoalOutcomeSemantics.classifyPreVerification(plan) ?: return null
        return observeOutcome(plan, outcome, observedAtEpochMs)
    }

    private fun observeOutcome(
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long
    ): GoalStrategyPortfolioStats? {
        require(observedAtEpochMs >= 0L)
        val decision = decision(plan.id) ?: return null
        GoalOutcomeSemantics.validate(plan, outcome)
        require(StrategySignature.from(plan) == decision.strategy) {
            "contextual portfolio terminal plan changed selected strategy"
        }

        val marker = outcomeMarkerId(plan.id)
        memory.get(marker)
            ?.takeIf { it.kind == OUTCOME_MARKER_KIND }
            ?.let { PortfolioCodec.decodeOutcomeMarker(it.content) }
            ?.let { observed ->
                require(observed == outcome) {
                    "contextual portfolio terminal outcome changed for an existing plan"
                }
                return snapshot(decision.contextDigest, decision.strategy)
            }

        val previous = snapshot(decision.contextDigest, decision.strategy)
            ?: GoalStrategyPortfolioStats(
                contextDigest = decision.contextDigest,
                contextFingerprint = decision.contextFingerprint,
                strategy = decision.strategy,
                selections = 1,
                lastSelectedAtEpochMs = decision.selectedAtEpochMs
            )
        val now = maxOf(observedAtEpochMs, previous.lastSelectedAtEpochMs)
        val regretProxy = when (outcome) {
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS -> 0.0
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
            GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED ->
                decision.bestExploitationScore.coerceIn(0.0, 1.0)
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED,
            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED -> 0.0
        }

        val updated = when (outcome) {
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS -> previous.copy(
                verifiedSuccesses = previous.verifiedSuccesses + 1,
                cumulativeRegretProxy = previous.cumulativeRegretProxy + regretProxy,
                lastSuccessAtEpochMs = maxOf(previous.lastSuccessAtEpochMs, now)
            )
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED -> previous.copy(
                executionFailures = previous.executionFailures + 1,
                cumulativeRegretProxy = previous.cumulativeRegretProxy + regretProxy,
                lastFailureAtEpochMs = maxOf(previous.lastFailureAtEpochMs, now)
            )
            GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED -> previous.copy(
                goalEvidenceFailures = previous.goalEvidenceFailures + 1,
                cumulativeRegretProxy = previous.cumulativeRegretProxy + regretProxy,
                lastFailureAtEpochMs = maxOf(previous.lastFailureAtEpochMs, now)
            )
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED -> previous.copy(
                authorityNeutral = previous.authorityNeutral + 1
            )
            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED -> previous.copy(
                partialNeutral = previous.partialNeutral + 1
            )
        }

        memory.remember(
            MemoryRecord(
                id = marker,
                kind = OUTCOME_MARKER_KIND,
                content = PortfolioCodec.encodeOutcomeMarker(outcome),
                importance = 0.50,
                provenance = Provenance(
                    source = "governed-contextual-strategy-outcome",
                    producer = "goal-contextual-strategy-portfolio",
                    confidence = 1.0
                ),
                createdAtEpochMs = now
            )
        )
        saveStats(updated, now)
        return updated
    }

    @Synchronized
    override fun decision(planId: PlanId): GoalStrategyPortfolioDecision? =
        memory.get(decisionId(planId))
            ?.takeIf { it.kind == DECISION_KIND }
            ?.let { PortfolioCodec.decodeDecision(it.content) }

    @Synchronized
    override fun snapshot(
        contextDigest: String,
        strategy: StrategySignature
    ): GoalStrategyPortfolioStats? =
        memory.get(statsId(contextDigest, strategy))
            ?.takeIf { it.kind == STATS_KIND }
            ?.let { PortfolioCodec.decodeStats(it.content) }
            ?.takeIf {
                it.contextDigest == contextDigest &&
                    it.strategy == strategy
            }

    private fun saveStats(
        stats: GoalStrategyPortfolioStats,
        createdAtEpochMs: Long
    ) {
        val id = statsId(stats.contextDigest, stats.strategy)
        memory.remember(
            MemoryRecord(
                id = id,
                kind = STATS_KIND,
                content = PortfolioCodec.encodeStats(stats),
                importance = 0.76,
                provenance = Provenance(
                    source = "governed-contextual-strategy-outcome",
                    producer = "goal-contextual-strategy-portfolio",
                    confidence = 1.0
                ),
                createdAtEpochMs = createdAtEpochMs
            )
        )
        val existing = decodeIndex(memory.get(INDEX_ID)?.content)
        val key = id.value.removePrefix(STATS_PREFIX)
        val next = (existing.filterNot { it == key } + key).takeLast(MAX_INDEXED_STATS)
        memory.remember(
            MemoryRecord(
                id = INDEX_ID,
                kind = INDEX_KIND,
                content = "v=1;keys=" + next.joinToString(","),
                importance = 0.74,
                provenance = Provenance(
                    source = "governed-contextual-strategy-outcome",
                    producer = "goal-contextual-strategy-portfolio-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = createdAtEpochMs
            )
        )
    }

    private fun recentStats(limit: Int): List<GoalStrategyPortfolioStats> =
        decodeIndex(memory.get(INDEX_ID)?.content)
            .asReversed()
            .asSequence()
            .mapNotNull { key ->
                memory.get(MemoryId(STATS_PREFIX + key))
                    ?.takeIf { it.kind == STATS_KIND }
                    ?.let { PortfolioCodec.decodeStats(it.content) }
            }
            .take(limit)
            .toList()

    private fun decodeIndex(content: String?): List<String> {
        if (content == null || !content.startsWith("v=1;keys=")) return emptyList()
        return content.removePrefix("v=1;keys=")
            .split(',')
            .filter { it.matches(INDEX_KEY) }
            .distinct()
            .takeLast(MAX_INDEXED_STATS)
    }

    private fun decisionId(planId: PlanId): MemoryId =
        MemoryId("goal-strategy-portfolio-decision:" + planId.value)

    private fun outcomeMarkerId(planId: PlanId): MemoryId =
        MemoryId("goal-strategy-portfolio-outcome:" + planId.value)

    private fun statsId(
        contextDigest: String,
        strategy: StrategySignature
    ): MemoryId = MemoryId(STATS_PREFIX + contextDigest + ":" + strategy.digest)

    companion object {
        const val DECISION_KIND = "goal-strategy-portfolio-decision-v1"
        const val OUTCOME_MARKER_KIND = "goal-strategy-portfolio-outcome-v1"
        const val STATS_KIND = "goal-strategy-portfolio-stats-v1"
        const val INDEX_KIND = "goal-strategy-portfolio-index-v1"
        const val MAX_INDEXED_STATS = 96
        private const val STATS_PREFIX = "goal-strategy-portfolio-stats:"
        private val INDEX_ID = MemoryId("goal-strategy-portfolio:index")
        private val INDEX_KEY = Regex("[0-9a-f]{64}:[0-9a-f]{64}")
    }
}

object GoalContextualStrategyPortfolioPolicy {
    const val MIN_CONTEXT_SIMILARITY = 0.20
    const val MAX_EXPLORATION_BONUS = 0.12
    const val MAX_EXPLORATION_GAP = 0.12
    private const val MAX_CONTEXTUAL_REGRET_PENALTY = 0.15

    fun rank(
        candidates: List<GoalTransferCounterfactualAssessment>,
        contextDigest: String,
        contextFingerprint: Set<String>,
        historical: Collection<GoalStrategyPortfolioStats>,
        nowEpochMs: Long
    ): List<GoalStrategyPortfolioCandidate> {
        require(contextDigest.matches(Regex("[0-9a-f]{64}")))
        require(contextFingerprint.isNotEmpty())
        require(nowEpochMs >= 0L)
        if (candidates.isEmpty()) return emptyList()

        val provisional = candidates.map { assessment ->
            val related = historical.filter { stats ->
                stats.strategy == assessment.candidate.strategy &&
                    GoalOutcomeFingerprint.similarity(
                        contextFingerprint,
                        stats.contextFingerprint
                    ) >= MIN_CONTEXT_SIMILARITY
            }
            val comparable = related.sumOf { it.comparableOutcomes }
            val selections = related.sumOf { it.selections }
            val weightedComparable = related.sumOf { stats ->
                val similarity = GoalOutcomeFingerprint.similarity(
                    contextFingerprint,
                    stats.contextFingerprint
                )
                stats.comparableOutcomes.toDouble() * similarity
            }
            val weightedSuccesses = related.sumOf { stats ->
                val similarity = GoalOutcomeFingerprint.similarity(
                    contextFingerprint,
                    stats.contextFingerprint
                )
                stats.verifiedSuccesses.toDouble() * similarity
            }
            val rewardRate = weightedComparable.takeIf { it > 0.0 }?.let {
                (weightedSuccesses / it).coerceIn(0.0, 1.0)
            }
            val confidence = (
                comparable.toDouble() / (comparable.toDouble() + 4.0)
                ).coerceIn(0.0, 1.0)
            val regret = if (comparable == 0) {
                0.0
            } else {
                related.sumOf { it.cumulativeRegretProxy } /
                    comparable.toDouble()
            }.coerceIn(0.0, 1.0)
            val learnedReward = rewardRate ?: assessment.calibratedSupport
            val regretPenalty = (
                regret * confidence * MAX_CONTEXTUAL_REGRET_PENALTY
                ).coerceIn(0.0, MAX_CONTEXTUAL_REGRET_PENALTY)
            val exploitation = (
                assessment.calibratedSupport * (1.0 - confidence) +
                    learnedReward * confidence -
                    regretPenalty
                ).coerceIn(0.0, 1.0)

            ProvisionalCandidate(
                assessment = assessment,
                contextDigest = contextDigest,
                contextFingerprint = contextFingerprint,
                selections = selections,
                rewardRate = rewardRate,
                confidence = confidence,
                regret = regret,
                exploitation = exploitation
            )
        }

        val bestExploitation = provisional.maxOf { it.exploitation }
        return provisional.map { item ->
            val withinExplorationBand =
                bestExploitation - item.exploitation <= MAX_EXPLORATION_GAP
            val bonus = if (withinExplorationBand) {
                (
                    MAX_EXPLORATION_BONUS / sqrt(1.0 + item.selections.toDouble())
                    ).coerceIn(0.0, MAX_EXPLORATION_BONUS)
            } else {
                0.0
            }
            val score = (item.exploitation + bonus).coerceIn(0.0, 1.0)
            GoalStrategyPortfolioCandidate(
                assessment = item.assessment,
                contextDigest = item.contextDigest,
                contextFingerprint = item.contextFingerprint,
                similarSelections = item.selections,
                contextualRewardRate = item.rewardRate,
                contextualEvidenceConfidence = item.confidence,
                contextualRegretProxy = item.regret,
                exploitationScore = item.exploitation,
                explorationBonus = bonus,
                portfolioScore = score,
                mode = if (bonus > 0.0 && item.exploitation < bestExploitation) {
                    GoalStrategyPortfolioMode.EXPLORE
                } else {
                    GoalStrategyPortfolioMode.EXPLOIT
                }
            )
        }.sortedWith(
            compareByDescending<GoalStrategyPortfolioCandidate> { it.portfolioScore }
                .thenByDescending { it.exploitationScore }
                .thenByDescending { it.assessment.calibratedSupport }
                .thenBy { it.assessment.candidate.strategy.canonical }
        )
    }

    fun render(
        candidates: List<GoalStrategyPortfolioCandidate>
    ): String {
        if (candidates.isEmpty()) return ""
        require(candidates.size <= GoalOutcomeStrategyTransfer.MAX_CANDIDATES)
        return buildString {
            appendLine("<GOAL_CONTEXTUAL_STRATEGY_PORTFOLIO>")
            appendLine(
                "Already validated strategy-transfer candidates are ordered by bounded contextual " +
                    "exploitation plus deterministic exploration. Ordering is advisory only."
            )
            appendLine(
                "Exploration never widens capabilities, reuses approval, executes a tool, or bypasses " +
                    "live binding, authority, continuity, confirmation, or goal verification."
            )
            appendLine(
                "cognitive_state_digest=" +
                    candidates.first().assessment.cognitiveStateDigest
            )
            candidates.forEachIndexed { index, item ->
                appendLine(
                    "candidate." + (index + 1) + ".capabilities=" +
                        item.assessment.candidate.strategy.capabilities.joinToString(">") {
                            it.value
                        } +
                        " mode=" + item.mode.name +
                        " calibrated_support=" + fmt(item.assessment.calibratedSupport) +
                        " context_fit=" + fmt(item.assessment.contextFit) +
                        " projected_support=" + fmt(item.assessment.projectedSupport) +
                        " contextual_reward=" +
                        (item.contextualRewardRate?.let(::fmt) ?: "~") +
                        " contextual_confidence=" + fmt(item.contextualEvidenceConfidence) +
                        " regret_proxy=" + fmt(item.contextualRegretProxy) +
                        " exploitation=" + fmt(item.exploitationScore) +
                        " exploration_bonus=" + fmt(item.explorationBonus) +
                        " hierarchical_credit=" + fmt(item.hierarchicalCreditScore) +
                        " hierarchical_confidence=" + fmt(item.hierarchicalCreditConfidence) +
                        " hierarchical_components=" + item.hierarchicalMatchedComponents +
                        " hierarchical_adjustment=" + fmt(item.hierarchicalCreditAdjustment) +
                        " portfolio_score=" + fmt(item.portfolioScore) +
                        " authority=false"
                )
            }
            append("</GOAL_CONTEXTUAL_STRATEGY_PORTFOLIO>")
        }
    }

    fun contextDigest(fingerprint: Set<String>): String {
        require(fingerprint.isNotEmpty())
        return MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.sorted().joinToString("|").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private data class ProvisionalCandidate(
        val assessment: GoalTransferCounterfactualAssessment,
        val contextDigest: String,
        val contextFingerprint: Set<String>,
        val selections: Int,
        val rewardRate: Double?,
        val confidence: Double,
        val regret: Double,
        val exploitation: Double
    )

    private fun fmt(value: Double): String =
        "%.3f".format(Locale.US, value)
}

private object PortfolioCodec {
    private const val STATS_VERSION = "AMPER_GOAL_STRATEGY_PORTFOLIO_STATS_V1"
    private const val DECISION_VERSION = "AMPER_GOAL_STRATEGY_PORTFOLIO_DECISION_V1"
    private const val OUTCOME_VERSION = "AMPER_GOAL_STRATEGY_PORTFOLIO_OUTCOME_V1"

    fun encodeStats(stats: GoalStrategyPortfolioStats): String = listOf(
        STATS_VERSION,
        stats.contextDigest,
        stats.contextFingerprint.sorted().joinToString(","),
        stats.strategy.capabilities.joinToString(",") { it.value },
        stats.selections.toString(),
        stats.verifiedSuccesses.toString(),
        stats.executionFailures.toString(),
        stats.goalEvidenceFailures.toString(),
        stats.authorityNeutral.toString(),
        stats.partialNeutral.toString(),
        stats.cumulativeRegretProxy.toString(),
        stats.lastSelectedAtEpochMs.toString(),
        stats.lastSuccessAtEpochMs.toString(),
        stats.lastFailureAtEpochMs.toString()
    ).joinToString("\t")

    fun decodeStats(content: String): GoalStrategyPortfolioStats? = runCatching {
        val p = content.split('\t')
        require(p.size == 14 && p[0] == STATS_VERSION)
        GoalStrategyPortfolioStats(
            contextDigest = p[1],
            contextFingerprint = p[2].split(',').filter { it.isNotBlank() }.toSet(),
            strategy = StrategySignature(
                p[3].split(',').filter { it.isNotBlank() }.map(::CapabilityId)
            ),
            selections = p[4].toInt(),
            verifiedSuccesses = p[5].toInt(),
            executionFailures = p[6].toInt(),
            goalEvidenceFailures = p[7].toInt(),
            authorityNeutral = p[8].toInt(),
            partialNeutral = p[9].toInt(),
            cumulativeRegretProxy = p[10].toDouble(),
            lastSelectedAtEpochMs = p[11].toLong(),
            lastSuccessAtEpochMs = p[12].toLong(),
            lastFailureAtEpochMs = p[13].toLong()
        )
    }.getOrNull()

    fun encodeDecision(decision: GoalStrategyPortfolioDecision): String = listOf(
        DECISION_VERSION,
        decision.planId.value,
        decision.contextDigest,
        decision.contextFingerprint.sorted().joinToString(","),
        decision.strategy.capabilities.joinToString(",") { it.value },
        decision.mode.name,
        decision.exploitationScore.toString(),
        decision.explorationBonus.toString(),
        decision.portfolioScore.toString(),
        decision.bestExploitationScore.toString(),
        decision.selectedAtEpochMs.toString()
    ).joinToString("\t")

    fun decodeDecision(content: String): GoalStrategyPortfolioDecision? = runCatching {
        val p = content.split('\t')
        require(p.size == 11 && p[0] == DECISION_VERSION)
        GoalStrategyPortfolioDecision(
            planId = PlanId(p[1]),
            contextDigest = p[2],
            contextFingerprint = p[3].split(',').filter { it.isNotBlank() }.toSet(),
            strategy = StrategySignature(
                p[4].split(',').filter { it.isNotBlank() }.map(::CapabilityId)
            ),
            mode = GoalStrategyPortfolioMode.valueOf(p[5]),
            exploitationScore = p[6].toDouble(),
            explorationBonus = p[7].toDouble(),
            portfolioScore = p[8].toDouble(),
            bestExploitationScore = p[9].toDouble(),
            selectedAtEpochMs = p[10].toLong()
        )
    }.getOrNull()

    fun encodeOutcomeMarker(outcome: GoalOutcomeEvidenceKind): String =
        OUTCOME_VERSION + "\t" + outcome.name

    fun decodeOutcomeMarker(content: String): GoalOutcomeEvidenceKind? = runCatching {
        val p = content.split('\t')
        require(p.size == 2 && p[0] == OUTCOME_VERSION)
        GoalOutcomeEvidenceKind.valueOf(p[1])
    }.getOrNull()
}
