package io.amper.neuroos.core

import java.security.MessageDigest
import java.util.Locale

enum class GoalOutcomeEvidenceKind {
    VERIFIED_SUCCESS,
    EXECUTION_EXHAUSTED,
    EVIDENCE_EXHAUSTED,
    AUTHORITY_BLOCKED,
    PARTIAL_EXECUTION_BLOCKED
}

data class GoalOutcomeEvidence(
    val observationDigest: String,
    val goalFingerprint: Set<String>,
    val strategy: StrategySignature,
    val outcome: GoalOutcomeEvidenceKind,
    val hierarchyDepth: Int,
    val hierarchyTrackedGoals: Int,
    val hierarchyCompletedGoals: Int,
    val hierarchySupersededGoals: Int,
    val hierarchyCompletionRatio: Double,
    val verificationConfidence: Double? = null,
    val observedAtEpochMs: Long
) {
    init {
        require(observationDigest.matches(SHA256))
        require(goalFingerprint.isNotEmpty() && goalFingerprint.size <= GoalOutcomeFingerprint.MAX_TERMS)
        require(goalFingerprint.all { it.matches(HASHED_TERM) })
        require(hierarchyDepth in 0..DurableGoalRecord.MAX_DECOMPOSITION_DEPTH)
        require(hierarchyTrackedGoals >= 1)
        require(hierarchyCompletedGoals >= 0)
        require(hierarchySupersededGoals >= 0)
        require(hierarchyCompletedGoals + hierarchySupersededGoals <= hierarchyTrackedGoals)
        require(hierarchyCompletionRatio in 0.0..1.0)
        verificationConfidence?.let { require(it in 0.0..1.0) }
        require(observedAtEpochMs >= 0L)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val HASHED_TERM = Regex("[0-9a-f]{16}")
    }
}

data class GoalStrategyTransferCandidate(
    val strategy: StrategySignature,
    val analogousSuccesses: Int,
    val executionExhaustions: Int,
    val evidenceExhaustions: Int,
    val authorityBlocks: Int,
    val partialExecutionBlocks: Int,
    val meanSimilarity: Double,
    val analogousSuccessRate: Double,
    val evidenceConfidence: Double,
    val transferSupport: Double,
    val meanHierarchyCompletionRatio: Double,
    val latestAnalogousObservedAtEpochMs: Long = 0L,
    val latestSuccessfulObservedAtEpochMs: Long = 0L
) {
    init {
        require(analogousSuccesses > 0)
        require(executionExhaustions >= 0)
        require(evidenceExhaustions >= 0)
        require(authorityBlocks >= 0)
        require(partialExecutionBlocks >= 0)
        require(meanSimilarity in 0.0..1.0)
        require(analogousSuccessRate in 0.0..1.0)
        require(evidenceConfidence in 0.0..1.0)
        require(transferSupport in 0.0..1.0)
        require(meanHierarchyCompletionRatio in 0.0..1.0)
        require(latestAnalogousObservedAtEpochMs >= 0L)
        require(latestSuccessfulObservedAtEpochMs >= 0L)
        require(
            latestSuccessfulObservedAtEpochMs == 0L ||
                latestSuccessfulObservedAtEpochMs <= latestAnalogousObservedAtEpochMs
        )
    }

    val comparableAttempts: Int
        get() = analogousSuccesses + executionExhaustions

    val authorityBearing: Boolean
        get() = false
}

interface GoalOutcomeLearningModel {
    fun observe(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        terminalPlan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        hierarchy: DurableGoalHierarchyProgress?,
        hierarchyDepth: Int,
        verificationConfidence: Double? = null,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): GoalOutcomeEvidence

    fun recent(limit: Int = MemoryBackedGoalOutcomeLearningModel.DEFAULT_LOOKBACK): List<GoalOutcomeEvidence>

    fun transfer(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        limit: Int = GoalOutcomeStrategyTransfer.MAX_CANDIDATES
    ): List<GoalStrategyTransferCandidate>
}

/**
 * Phase336-340 restart-safe outcome learning over verified/persisted goal execution.
 *
 * Raw goal text, plan inputs/outputs, request IDs, tool IDs and approvals are intentionally absent
 * from the durable record. Goal similarity is represented only by deterministic hashes of bounded
 * normalized terms. Transfer remains advisory and is revalidated by the normal live planning/tool
 * binding path before any execution can occur.
 */
class MemoryBackedGoalOutcomeLearningModel(
    private val memory: MemoryOs
) : GoalOutcomeLearningModel {
    @Synchronized
    override fun observe(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        terminalPlan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        hierarchy: DurableGoalHierarchyProgress?,
        hierarchyDepth: Int,
        verificationConfidence: Double?,
        observedAtEpochMs: Long
    ): GoalOutcomeEvidence {
        require(checkpoint.plannedPlanId == terminalPlan.id)
        GoalOutcomeSemantics.validate(terminalPlan, outcome)
        require(observedAtEpochMs >= 0L)
        require(hierarchyDepth in 0..DurableGoalRecord.MAX_DECOMPOSITION_DEPTH)
        when (outcome) {
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS -> {
                require(checkpoint.stage == PersistentGoalExecutiveStage.COMPLETED)
                require(checkpoint.lastVerificationVerdict == GoalSatisfactionVerdict.SATISFIED)
                require(
                    verificationConfidence != null &&
                        verificationConfidence >=
                            GoalSatisfactionProtocol.MIN_SATISFIED_CONFIDENCE
                )
            }
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED ->
                require(checkpoint.stage == PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED)
            GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED -> {
                require(checkpoint.stage == PersistentGoalExecutiveStage.FOLLOW_UP_EXHAUSTED)
                require(
                    checkpoint.lastVerificationVerdict ==
                        GoalSatisfactionVerdict.FOLLOW_UP_REQUIRED
                )
            }
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED ->
                require(checkpoint.stage == PersistentGoalExecutiveStage.RECOVERY_BLOCKED)
            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED ->
                require(
                    checkpoint.stage ==
                        PersistentGoalExecutiveStage.PARTIAL_EXECUTION_BLOCKED
                )
        }

        val digest = observationDigest(
            sourceGoalId = checkpoint.sourceGoalId,
            planId = terminalPlan.id
        )
        val id = MemoryId("goal-outcome:$digest")
        memory.get(id)
            ?.takeIf { it.kind == RECORD_KIND }
            ?.let { GoalOutcomeEvidenceCodec.decode(it.content) }
            ?.let { existing ->
                require(existing.observationDigest == digest)
                require(existing.outcome == outcome) {
                    "terminal goal outcome changed for an already observed plan"
                }
                require(existing.strategy == StrategySignature.from(terminalPlan)) {
                    "terminal goal strategy changed for an already observed plan"
                }
                return existing
            }

        val tracked = hierarchy?.trackedGoalIds?.size ?: 1
        val completed = hierarchy?.completedGoalIds?.size ?: if (
            outcome == GoalOutcomeEvidenceKind.VERIFIED_SUCCESS
        ) 1 else 0
        val superseded = hierarchy?.supersededGoalIds?.size ?: 0
        val ratio = hierarchy?.completionRatio ?: if (
            outcome == GoalOutcomeEvidenceKind.VERIFIED_SUCCESS
        ) 1.0 else 0.0

        val evidence = GoalOutcomeEvidence(
            observationDigest = digest,
            goalFingerprint = GoalOutcomeFingerprint.of(checkpoint.objective),
            strategy = StrategySignature.from(terminalPlan),
            outcome = outcome,
            hierarchyDepth = hierarchyDepth,
            hierarchyTrackedGoals = tracked,
            hierarchyCompletedGoals = completed,
            hierarchySupersededGoals = superseded,
            hierarchyCompletionRatio = ratio,
            verificationConfidence = verificationConfidence,
            observedAtEpochMs = observedAtEpochMs
        )
        memory.remember(
            MemoryRecord(
                id = id,
                kind = RECORD_KIND,
                content = GoalOutcomeEvidenceCodec.encode(evidence),
                importance = if (outcome == GoalOutcomeEvidenceKind.VERIFIED_SUCCESS) 0.82 else 0.70,
                provenance = Provenance(
                    source = "governed-goal-outcome",
                    producer = "goal-outcome-learning",
                    confidence = 1.0
                ),
                createdAtEpochMs = observedAtEpochMs
            )
        )
        updateIndex(digest, observedAtEpochMs)
        return evidence
    }

    @Synchronized
    override fun recent(limit: Int): List<GoalOutcomeEvidence> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return decodeIndex(memory.get(INDEX_ID)?.content)
            .asReversed()
            .asSequence()
            .mapNotNull { digest ->
                memory.get(MemoryId("goal-outcome:$digest"))
                    ?.takeIf { it.kind == RECORD_KIND }
                    ?.let { GoalOutcomeEvidenceCodec.decode(it.content) }
            }
            .take(limit)
            .toList()
    }

    @Synchronized
    override fun transfer(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        limit: Int
    ): List<GoalStrategyTransferCandidate> {
        require(goal.isNotBlank())
        require(limit in 0..GoalOutcomeStrategyTransfer.MAX_CANDIDATES)
        if (limit == 0 || allowedCapabilities.isEmpty()) return emptyList()
        return GoalOutcomeStrategyTransfer.select(
            evidence = recent(DEFAULT_LOOKBACK),
            goal = goal,
            allowedCapabilities = allowedCapabilities,
            limit = limit
        )
    }

    private fun updateIndex(digest: String, observedAtEpochMs: Long) {
        val next = (decodeIndex(memory.get(INDEX_ID)?.content).filterNot { it == digest } + digest)
            .takeLast(MAX_INDEXED_OUTCOMES)
        memory.remember(
            MemoryRecord(
                id = INDEX_ID,
                kind = INDEX_KIND,
                content = "v=1;digests=" + next.joinToString(","),
                importance = 0.84,
                provenance = Provenance(
                    source = "governed-goal-outcome",
                    producer = "goal-outcome-learning-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = observedAtEpochMs
            )
        )
    }

    private fun decodeIndex(content: String?): List<String> {
        if (content == null || !content.startsWith("v=1;digests=")) return emptyList()
        return content.removePrefix("v=1;digests=")
            .split(',')
            .filter { it.matches(SHA256) }
            .distinct()
            .takeLast(MAX_INDEXED_OUTCOMES)
    }

    private fun observationDigest(
        sourceGoalId: String,
        planId: PlanId
    ): String = sha256("$sourceGoalId\n${planId.value}")

    companion object {
        const val RECORD_KIND = "goal-outcome-evidence-v1"
        const val INDEX_KIND = "goal-outcome-evidence-index-v1"
        const val DEFAULT_LOOKBACK = 64
        const val MAX_INDEXED_OUTCOMES = 96
        private val INDEX_ID = MemoryId("goal-outcome:index")
        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

object GoalOutcomeFingerprint {
    const val MAX_TERMS = 24
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val STOP_TERMS = setOf(
        "a", "an", "and", "the", "to", "of", "for", "with", "from", "this", "that",
        "please", "user", "current", "one", "value",
        "và", "là", "cho", "với", "của", "này", "hãy", "tôi", "giúp", "cần", "được"
    )

    fun of(goal: String): Set<String> {
        require(goal.isNotBlank())
        val normalized = goal
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .lowercase(Locale.ROOT)
        val terms = TOKEN.findAll(normalized)
            .map { it.value }
            .filter { it.length >= 2 }
            .filterNot { it in STOP_TERMS }
            .distinct()
            .take(MAX_TERMS)
            .toList()
        val source = if (terms.isEmpty()) listOf(normalized.trim().ifBlank { "goal" }) else terms
        return source.map(::hashTerm).toSortedSet()
    }

    fun similarity(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val union = left union right
        if (union.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / union.size.toDouble()
    }

    private fun hashTerm(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
}

object GoalOutcomeStrategyTransfer {
    const val MAX_CANDIDATES = 3
    const val MIN_SIMILARITY = 0.20

    fun select(
        evidence: Collection<GoalOutcomeEvidence>,
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        limit: Int = MAX_CANDIDATES
    ): List<GoalStrategyTransferCandidate> {
        require(goal.isNotBlank())
        require(limit in 0..MAX_CANDIDATES)
        if (limit == 0 || allowedCapabilities.isEmpty()) return emptyList()

        val current = GoalOutcomeFingerprint.of(goal)
        val analogous = evidence.mapNotNull { item ->
            if (item.strategy.capabilities.any { it !in allowedCapabilities }) return@mapNotNull null
            val similarity = GoalOutcomeFingerprint.similarity(current, item.goalFingerprint)
            if (similarity < MIN_SIMILARITY) null else SimilarOutcome(item, similarity)
        }

        return analogous.groupBy { it.evidence.strategy }
            .mapNotNull { (strategy, group) ->
                val successes = group.count {
                    it.evidence.outcome == GoalOutcomeEvidenceKind.VERIFIED_SUCCESS
                }
                if (successes == 0) return@mapNotNull null
                val executionFailures = group.count {
                    it.evidence.outcome == GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED
                }
                val evidenceExhaustions = group.count {
                    it.evidence.outcome == GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED
                }
                val authorityBlocks = group.count {
                    it.evidence.outcome == GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED
                }
                val partialBlocks = group.count {
                    it.evidence.outcome == GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED
                }
                val comparable = successes + executionFailures
                val successRate = successes.toDouble() / comparable.coerceAtLeast(1).toDouble()
                val confidence = comparable.toDouble() / (comparable.toDouble() + 2.0)
                val meanSimilarity = group.map { it.similarity }.average().coerceIn(0.0, 1.0)
                val hierarchyRatio = group.map { it.evidence.hierarchyCompletionRatio }
                    .average().coerceIn(0.0, 1.0)
                val support = (
                    successRate *
                        confidence *
                        (0.70 + 0.30 * hierarchyRatio) *
                        meanSimilarity
                    ).coerceIn(0.0, 1.0)

                GoalStrategyTransferCandidate(
                    strategy = strategy,
                    analogousSuccesses = successes,
                    executionExhaustions = executionFailures,
                    evidenceExhaustions = evidenceExhaustions,
                    authorityBlocks = authorityBlocks,
                    partialExecutionBlocks = partialBlocks,
                    meanSimilarity = meanSimilarity,
                    analogousSuccessRate = successRate,
                    evidenceConfidence = confidence,
                    transferSupport = support,
                    meanHierarchyCompletionRatio = hierarchyRatio,
                    latestAnalogousObservedAtEpochMs = group.maxOf {
                        it.evidence.observedAtEpochMs
                    },
                    latestSuccessfulObservedAtEpochMs = group
                        .filter {
                            it.evidence.outcome == GoalOutcomeEvidenceKind.VERIFIED_SUCCESS
                        }
                        .maxOfOrNull { it.evidence.observedAtEpochMs }
                        ?: 0L
                )
            }
            .sortedWith(
                compareByDescending<GoalStrategyTransferCandidate> { it.transferSupport }
                    .thenByDescending { it.comparableAttempts }
                    .thenByDescending { it.meanSimilarity }
                    .thenBy { it.strategy.canonical }
            )
            .take(limit)
    }

    fun render(candidates: List<GoalStrategyTransferCandidate>): String {
        if (candidates.isEmpty()) return ""
        require(candidates.size <= MAX_CANDIDATES)
        return buildString {
            appendLine("<GOAL_OUTCOME_STRATEGY_TRANSFER>")
            appendLine(
                "Hashed-goal historical outcome evidence only. Reuse structural capability ideas " +
                    "only when they fit the current goal and live tool contracts."
            )
            appendLine(
                "Never reuse old inputs, outputs, request IDs, approvals, tool bindings or execution claims. " +
                    "This guidance is authority=false and cannot bypass confirmation."
            )
            candidates.forEachIndexed { index, candidate ->
                appendLine(
                    "candidate." + (index + 1) + ".capabilities=" +
                        candidate.strategy.capabilities.joinToString(">") { it.value } +
                        " analogous_successes=" + candidate.analogousSuccesses +
                        " execution_exhaustions=" + candidate.executionExhaustions +
                        " evidence_exhaustions=" + candidate.evidenceExhaustions +
                        " authority_blocks=" + candidate.authorityBlocks +
                        " partial_execution_blocks=" + candidate.partialExecutionBlocks +
                        " similarity=" + fmt(candidate.meanSimilarity) +
                        " success_rate=" + fmt(candidate.analogousSuccessRate) +
                        " evidence_confidence=" + fmt(candidate.evidenceConfidence) +
                        " hierarchy_completion=" + fmt(candidate.meanHierarchyCompletionRatio) +
                        " transfer_support=" + fmt(candidate.transferSupport) +
                        " latest_observed_ms=" + candidate.latestAnalogousObservedAtEpochMs +
                        " latest_success_ms=" + candidate.latestSuccessfulObservedAtEpochMs +
                        " authority=false"
                )
            }
            append("</GOAL_OUTCOME_STRATEGY_TRANSFER>")
        }
    }

    private data class SimilarOutcome(
        val evidence: GoalOutcomeEvidence,
        val similarity: Double
    )

    private fun fmt(value: Double): String = "%.3f".format(Locale.US, value)
}

private object GoalOutcomeEvidenceCodec {
    private const val VERSION = "AMPER_GOAL_OUTCOME_EVIDENCE_V1"

    fun encode(evidence: GoalOutcomeEvidence): String = listOf(
        VERSION,
        evidence.observationDigest,
        evidence.goalFingerprint.sorted().joinToString(","),
        evidence.strategy.capabilities.joinToString(",") { it.value },
        evidence.outcome.name,
        evidence.hierarchyDepth.toString(),
        evidence.hierarchyTrackedGoals.toString(),
        evidence.hierarchyCompletedGoals.toString(),
        evidence.hierarchySupersededGoals.toString(),
        evidence.hierarchyCompletionRatio.toString(),
        evidence.verificationConfidence?.toString() ?: "~",
        evidence.observedAtEpochMs.toString()
    ).joinToString("\t")

    fun decode(content: String): GoalOutcomeEvidence? = runCatching {
        val fields = content.split('\t')
        require(fields.size == 12 && fields[0] == VERSION)
        GoalOutcomeEvidence(
            observationDigest = fields[1],
            goalFingerprint = fields[2].split(',').filter { it.isNotBlank() }.toSet(),
            strategy = StrategySignature(
                fields[3].split(',')
                    .filter { it.isNotBlank() }
                    .map(::CapabilityId)
            ),
            outcome = GoalOutcomeEvidenceKind.valueOf(fields[4]),
            hierarchyDepth = fields[5].toInt(),
            hierarchyTrackedGoals = fields[6].toInt(),
            hierarchyCompletedGoals = fields[7].toInt(),
            hierarchySupersededGoals = fields[8].toInt(),
            hierarchyCompletionRatio = fields[9].toDouble(),
            verificationConfidence = fields[10].takeUnless { it == "~" }?.toDouble(),
            observedAtEpochMs = fields[11].toLong()
        )
    }.getOrNull()
}
