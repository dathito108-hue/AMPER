package io.amper.neuroos.core

import java.security.MessageDigest
import kotlin.math.abs

enum class GoalStrategyCreditComponentKind {
    STEP,
    PREFIX,
    SEQUENCE
}

data class GoalStrategyCreditStats(
    val contextDigest: String,
    val goalFingerprint: Set<String>,
    val rootFingerprint: Set<String>,
    val hierarchyDepth: Int,
    val componentKind: GoalStrategyCreditComponentKind,
    val componentIndex: Int,
    val strategy: StrategySignature,
    val observations: Int = 0,
    val executionSuccesses: Int = 0,
    val executionFailures: Int = 0,
    val goalSuccesses: Int = 0,
    val goalEvidenceFailures: Int = 0,
    val authorityNeutral: Int = 0,
    val partialNeutral: Int = 0,
    val cumulativeCredit: Double = 0.0,
    val lastObservedAtEpochMs: Long = 0L
) {
    init {
        require(contextDigest.matches(SHA256))
        require(goalFingerprint.isNotEmpty())
        require(rootFingerprint.isNotEmpty())
        require(goalFingerprint.size <= GoalOutcomeFingerprint.MAX_TERMS)
        require(rootFingerprint.size <= GoalOutcomeFingerprint.MAX_TERMS)
        require(goalFingerprint.all { it.matches(HASHED_TERM) })
        require(rootFingerprint.all { it.matches(HASHED_TERM) })
        require(hierarchyDepth in 0..DurableGoalRecord.MAX_DECOMPOSITION_DEPTH)
        require(componentIndex >= 1)
        require(strategy.capabilities.isNotEmpty())
        require(observations >= 0)
        require(executionSuccesses >= 0)
        require(executionFailures >= 0)
        require(goalSuccesses >= 0)
        require(goalEvidenceFailures >= 0)
        require(authorityNeutral >= 0)
        require(partialNeutral >= 0)
        require(lastObservedAtEpochMs >= 0L)
        require(comparableObservations >= 0)
        require(abs(cumulativeCredit) <= comparableObservations.toDouble() + 1e-9)
    }

    val comparableObservations: Int
        get() = observations - authorityNeutral - partialNeutral

    val meanCredit: Double
        get() = if (comparableObservations == 0) 0.0
        else (
            cumulativeCredit / comparableObservations.toDouble()
            ).coerceIn(-1.0, 1.0)

    val executionReliability: Double?
        get() = (executionSuccesses + executionFailures).takeIf { it > 0 }?.let {
            executionSuccesses.toDouble() / it.toDouble()
        }

    val goalContributionRate: Double?
        get() = (goalSuccesses + goalEvidenceFailures).takeIf { it > 0 }?.let {
            goalSuccesses.toDouble() / it.toDouble()
        }

    val evidenceConfidence: Double
        get() = comparableObservations.toDouble() /
            (comparableObservations.toDouble() + 4.0)

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val HASHED_TERM = Regex("[0-9a-f]{16}")
    }
}

data class GoalHierarchicalStrategyCreditAssessment(
    val strategy: StrategySignature,
    val creditScore: Double,
    val evidenceConfidence: Double,
    val matchedComponents: Int,
    val matchedHierarchyDepths: Set<Int>
) {
    init {
        require(creditScore in -1.0..1.0)
        require(evidenceConfidence in 0.0..1.0)
        require(matchedComponents >= 0)
        require(matchedHierarchyDepths.all {
            it in 0..DurableGoalRecord.MAX_DECOMPOSITION_DEPTH
        })
    }

    val authorityBearing: Boolean
        get() = false
}

interface GoalHierarchicalStrategyCreditModel {
    fun observe(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        portfolioRecords: Collection<DurableGoalRecord>,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): List<GoalStrategyCreditStats>

    fun observeTerminalPlan(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        plan: SovereignPlan,
        portfolioRecords: Collection<DurableGoalRecord>,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): List<GoalStrategyCreditStats>

    fun assess(
        goal: String,
        strategy: StrategySignature
    ): GoalHierarchicalStrategyCreditAssessment
}

/**
 * Phase356-360 hierarchical strategy credit assignment.
 *
 * Credit is split across concrete step capabilities, bounded prefixes and the complete sequence.
 * Durable goal hierarchy contributes only hashed objective fingerprints and depth. Raw goal ids,
 * tool payloads, approvals and tool ids are never stored in this channel.
 *
 * Execution evidence and goal-satisfaction evidence remain separate:
 * - verified success credits executed steps, prefixes and the complete sequence;
 * - execution failure penalizes only failed execution components/prefixes and the sequence;
 * - evidence exhaustion preserves execution success for steps/prefixes while penalizing only the
 *   complete sequence's goal contribution;
 * - authority and partial-execution outcomes are diagnostic-neutral.
 */
class MemoryBackedGoalHierarchicalStrategyCreditModel(
    private val memory: MemoryOs
) : GoalHierarchicalStrategyCreditModel {
    @Synchronized
    override fun observe(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        portfolioRecords: Collection<DurableGoalRecord>,
        observedAtEpochMs: Long
    ): List<GoalStrategyCreditStats> {
        require(observedAtEpochMs >= 0L)
        require(plan.complete)
        require(checkpoint.plannedPlanId == plan.id)
        require(plan.steps.isNotEmpty())
        validateOutcome(plan, outcome)

        val markerId = markerId(plan.id)
        memory.get(markerId)
            ?.takeIf { it.kind == OUTCOME_MARKER_KIND }
            ?.let { CreditCodec.decodeOutcomeMarker(it.content) }
            ?.let { existing ->
                require(existing == outcome) {
                    "hierarchical strategy credit outcome changed for an existing plan"
                }
                return loadPlanComponents(plan.id)
            }

        val context = contextFor(
            checkpoint = checkpoint,
            records = portfolioRecords
        )
        val components = components(plan)
        val updated = components.map { component ->
            val previous = snapshot(context.contextDigest, component)
                ?: GoalStrategyCreditStats(
                    contextDigest = context.contextDigest,
                    goalFingerprint = context.goalFingerprint,
                    rootFingerprint = context.rootFingerprint,
                    hierarchyDepth = context.hierarchyDepth,
                    componentKind = component.kind,
                    componentIndex = component.index,
                    strategy = component.strategy
                )
            applyOutcome(
                previous = previous,
                component = component,
                plan = plan,
                outcome = outcome,
                observedAtEpochMs = observedAtEpochMs
            ).also { saveStats(it, observedAtEpochMs) }
        }

        memory.remember(
            MemoryRecord(
                id = markerId,
                kind = OUTCOME_MARKER_KIND,
                content = CreditCodec.encodeOutcomeMarker(outcome),
                importance = 0.54,
                provenance = Provenance(
                    source = "governed-hierarchical-strategy-outcome",
                    producer = "goal-hierarchical-strategy-credit",
                    confidence = 1.0
                ),
                createdAtEpochMs = observedAtEpochMs
            )
        )
        memory.remember(
            MemoryRecord(
                id = planComponentsId(plan.id),
                kind = PLAN_COMPONENT_INDEX_KIND,
                content = CreditCodec.encodePlanComponents(updated.map(::statsKey)),
                importance = 0.48,
                provenance = Provenance(
                    source = "governed-hierarchical-strategy-outcome",
                    producer = "goal-hierarchical-strategy-credit",
                    confidence = 1.0,
                    parents = setOf(markerId)
                ),
                createdAtEpochMs = observedAtEpochMs
            )
        )
        return updated
    }

    @Synchronized
    override fun observeTerminalPlan(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        plan: SovereignPlan,
        portfolioRecords: Collection<DurableGoalRecord>,
        observedAtEpochMs: Long
    ): List<GoalStrategyCreditStats> {
        require(plan.complete)
        val statuses = plan.steps.map { it.status }
        val outcome = when {
            statuses.all { it == PlanStepStatus.EXECUTED } -> return emptyList()
            statuses.any { it == PlanStepStatus.EXECUTED } ->
                GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED
            statuses.any { it == PlanStepStatus.DENIED || it == PlanStepStatus.REJECTED } ->
                GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED
            statuses.any {
                it == PlanStepStatus.FAILED ||
                    it == PlanStepStatus.MALFORMED ||
                    it == PlanStepStatus.UNAVAILABLE
            } -> GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED
            else -> return emptyList()
        }
        return observe(
            checkpoint = checkpoint,
            plan = plan,
            outcome = outcome,
            portfolioRecords = portfolioRecords,
            observedAtEpochMs = observedAtEpochMs
        )
    }

    @Synchronized
    override fun assess(
        goal: String,
        strategy: StrategySignature
    ): GoalHierarchicalStrategyCreditAssessment {
        require(goal.isNotBlank())
        val fingerprint = GoalOutcomeFingerprint.of(goal)
        val candidateCapabilities = strategy.capabilities
        val matches = recentStats(MAX_INDEXED_STATS).mapNotNull { stats ->
            val similarity = GoalOutcomeFingerprint.similarity(
                fingerprint,
                stats.goalFingerprint
            )
            if (similarity < MIN_GOAL_SIMILARITY) return@mapNotNull null
            if (!componentApplies(stats, candidateCapabilities)) return@mapNotNull null

            val kindWeight = when (stats.componentKind) {
                GoalStrategyCreditComponentKind.STEP -> STEP_WEIGHT
                GoalStrategyCreditComponentKind.PREFIX -> PREFIX_WEIGHT
                GoalStrategyCreditComponentKind.SEQUENCE -> SEQUENCE_WEIGHT
            }
            val weight = (
                similarity * kindWeight * stats.evidenceConfidence
                ).coerceAtLeast(0.0)
            WeightedCredit(stats, weight)
        }

        val weightTotal = matches.sumOf { it.weight }
        val score = if (weightTotal <= 0.0) {
            0.0
        } else {
            (
                matches.sumOf { it.stats.meanCredit * it.weight } / weightTotal
                ).coerceIn(-1.0, 1.0)
        }
        val confidence = if (matches.isEmpty()) {
            0.0
        } else {
            (
                matches.sumOf { it.stats.evidenceConfidence * it.weight } /
                    weightTotal.coerceAtLeast(1e-12)
                ).coerceIn(0.0, 1.0)
        }

        return GoalHierarchicalStrategyCreditAssessment(
            strategy = strategy,
            creditScore = score,
            evidenceConfidence = confidence,
            matchedComponents = matches.size,
            matchedHierarchyDepths = matches.mapTo(sortedSetOf()) {
                it.stats.hierarchyDepth
            }
        )
    }

    private fun applyOutcome(
        previous: GoalStrategyCreditStats,
        component: CreditComponent,
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long
    ): GoalStrategyCreditStats {
        val coveredSteps = plan.steps
            .sortedBy { it.index }
            .filter { it.index in component.coveredStepIndices }
        val allExecuted = coveredSteps.all { it.status == PlanStepStatus.EXECUTED }
        val executionBad = coveredSteps.any {
            it.status == PlanStepStatus.FAILED ||
                it.status == PlanStepStatus.MALFORMED ||
                it.status == PlanStepStatus.UNAVAILABLE
        }

        var executionSuccessDelta = 0
        var executionFailureDelta = 0
        var goalSuccessDelta = 0
        var goalEvidenceFailureDelta = 0
        var authorityNeutralDelta = 0
        var partialNeutralDelta = 0
        var creditDelta = 0.0

        when (outcome) {
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS -> {
                require(allExecuted)
                executionSuccessDelta = 1
                goalSuccessDelta = 1
                creditDelta = when (component.kind) {
                    GoalStrategyCreditComponentKind.STEP -> 0.60
                    GoalStrategyCreditComponentKind.PREFIX -> 0.80
                    GoalStrategyCreditComponentKind.SEQUENCE -> 1.00
                }
            }
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED -> {
                if (executionBad) {
                    executionFailureDelta = 1
                    creditDelta = when (component.kind) {
                        GoalStrategyCreditComponentKind.STEP -> -1.00
                        GoalStrategyCreditComponentKind.PREFIX -> -0.80
                        GoalStrategyCreditComponentKind.SEQUENCE -> -1.00
                    }
                }
            }
            GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED -> {
                if (allExecuted) {
                    executionSuccessDelta = 1
                    if (component.kind == GoalStrategyCreditComponentKind.SEQUENCE) {
                        goalEvidenceFailureDelta = 1
                        creditDelta = -0.60
                    } else {
                        creditDelta = 0.20
                    }
                }
            }
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED -> {
                authorityNeutralDelta = 1
            }
            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED -> {
                partialNeutralDelta = 1
            }
        }

        return previous.copy(
            observations = previous.observations + 1,
            executionSuccesses = previous.executionSuccesses + executionSuccessDelta,
            executionFailures = previous.executionFailures + executionFailureDelta,
            goalSuccesses = previous.goalSuccesses + goalSuccessDelta,
            goalEvidenceFailures =
                previous.goalEvidenceFailures + goalEvidenceFailureDelta,
            authorityNeutral = previous.authorityNeutral + authorityNeutralDelta,
            partialNeutral = previous.partialNeutral + partialNeutralDelta,
            cumulativeCredit = (
                previous.cumulativeCredit + creditDelta
                ).coerceIn(
                    -(previous.observations + 1).toDouble(),
                    (previous.observations + 1).toDouble()
                ),
            lastObservedAtEpochMs = maxOf(
                previous.lastObservedAtEpochMs,
                observedAtEpochMs
            )
        )
    }

    private fun contextFor(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        records: Collection<DurableGoalRecord>
    ): CreditContext {
        val byId = records.associateBy { it.sourceGoalId }
        val current = byId[checkpoint.sourceGoalId]
        var root = current
        val visited = linkedSetOf<String>()
        while (true) {
            val node = root ?: break
            val parentId = node.parentGoalId ?: break
            if (!visited.add(node.sourceGoalId)) break
            root = byId[parentId] ?: break
        }
        val goalFingerprint = GoalOutcomeFingerprint.of(checkpoint.objective)
        val rootFingerprint = GoalOutcomeFingerprint.of(
            root?.objective ?: checkpoint.objective
        )
        val depth = current?.decompositionDepth ?: 0
        val material = buildString {
            append(rootFingerprint.sorted().joinToString(","))
            append('|')
            append(goalFingerprint.sorted().joinToString(","))
            append('|')
            append(depth)
        }
        return CreditContext(
            contextDigest = sha256(material),
            goalFingerprint = goalFingerprint,
            rootFingerprint = rootFingerprint,
            hierarchyDepth = depth
        )
    }

    private fun components(plan: SovereignPlan): List<CreditComponent> {
        val ordered = plan.steps.sortedBy { it.index }
        val result = mutableListOf<CreditComponent>()
        ordered.forEachIndexed { zeroIndex, step ->
            result += CreditComponent(
                kind = GoalStrategyCreditComponentKind.STEP,
                index = zeroIndex + 1,
                strategy = StrategySignature(listOf(step.capability)),
                coveredStepIndices = setOf(step.index)
            )
        }
        if (ordered.size > 1) {
            for (count in 2 until ordered.size) {
                result += CreditComponent(
                    kind = GoalStrategyCreditComponentKind.PREFIX,
                    index = count,
                    strategy = StrategySignature(
                        ordered.take(count).map { it.capability }
                    ),
                    coveredStepIndices = ordered.take(count).mapTo(linkedSetOf()) { it.index }
                )
            }
        }
        result += CreditComponent(
            kind = GoalStrategyCreditComponentKind.SEQUENCE,
            index = ordered.size,
            strategy = StrategySignature(ordered.map { it.capability }),
            coveredStepIndices = ordered.mapTo(linkedSetOf()) { it.index }
        )
        return result
    }

    private fun componentApplies(
        stats: GoalStrategyCreditStats,
        candidateCapabilities: List<CapabilityId>
    ): Boolean = when (stats.componentKind) {
        GoalStrategyCreditComponentKind.STEP ->
            stats.strategy.capabilities.singleOrNull()?.let {
                it in candidateCapabilities
            } == true
        GoalStrategyCreditComponentKind.PREFIX ->
            candidateCapabilities.size >= stats.strategy.capabilities.size &&
                candidateCapabilities.take(stats.strategy.capabilities.size) ==
                    stats.strategy.capabilities
        GoalStrategyCreditComponentKind.SEQUENCE ->
            candidateCapabilities == stats.strategy.capabilities
    }

    private fun validateOutcome(
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind
    ) {
        val statuses = plan.steps.map { it.status }
        when (outcome) {
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS ->
                require(statuses.all { it == PlanStepStatus.EXECUTED }) {
                    "hierarchical verified success requires an all-executed terminal plan"
                }
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED ->
                require(statuses.all {
                    it == PlanStepStatus.FAILED ||
                        it == PlanStepStatus.MALFORMED ||
                        it == PlanStepStatus.UNAVAILABLE
                }) {
                    "hierarchical execution failure requires zero executed/authority steps"
                }
            GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED ->
                require(statuses.all { it == PlanStepStatus.EXECUTED }) {
                    "hierarchical evidence failure requires an all-executed terminal plan"
                }
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED -> {
                require(statuses.none { it == PlanStepStatus.EXECUTED })
                require(statuses.any {
                    it == PlanStepStatus.DENIED || it == PlanStepStatus.REJECTED
                })
            }
            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED -> {
                require(statuses.any { it == PlanStepStatus.EXECUTED })
                require(statuses.any { it != PlanStepStatus.EXECUTED })
            }
        }
    }

    private fun saveStats(
        stats: GoalStrategyCreditStats,
        observedAtEpochMs: Long
    ) {
        val key = statsKey(stats)
        memory.remember(
            MemoryRecord(
                id = MemoryId(STATS_PREFIX + key),
                kind = STATS_KIND,
                content = CreditCodec.encodeStats(stats),
                importance = 0.73,
                provenance = Provenance(
                    source = "governed-hierarchical-strategy-outcome",
                    producer = "goal-hierarchical-strategy-credit",
                    confidence = 1.0
                ),
                createdAtEpochMs = observedAtEpochMs
            )
        )
        val existing = decodeIndex(memory.get(INDEX_ID)?.content)
        val next = (existing.filterNot { it == key } + key).takeLast(MAX_INDEXED_STATS)
        memory.remember(
            MemoryRecord(
                id = INDEX_ID,
                kind = INDEX_KIND,
                content = "v=1;keys=" + next.joinToString(","),
                importance = 0.70,
                provenance = Provenance(
                    source = "governed-hierarchical-strategy-outcome",
                    producer = "goal-hierarchical-strategy-credit-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = observedAtEpochMs
            )
        )
    }

    private fun snapshot(
        contextDigest: String,
        component: CreditComponent
    ): GoalStrategyCreditStats? {
        val key = statsKey(
            contextDigest = contextDigest,
            kind = component.kind,
            index = component.index,
            strategy = component.strategy
        )
        return memory.get(MemoryId(STATS_PREFIX + key))
            ?.takeIf { it.kind == STATS_KIND }
            ?.let { CreditCodec.decodeStats(it.content) }
    }

    private fun loadPlanComponents(planId: PlanId): List<GoalStrategyCreditStats> =
        memory.get(planComponentsId(planId))
            ?.takeIf { it.kind == PLAN_COMPONENT_INDEX_KIND }
            ?.let { CreditCodec.decodePlanComponents(it.content) }
            .orEmpty()
            .mapNotNull { key ->
                memory.get(MemoryId(STATS_PREFIX + key))
                    ?.takeIf { it.kind == STATS_KIND }
                    ?.let { CreditCodec.decodeStats(it.content) }
            }

    private fun recentStats(limit: Int): List<GoalStrategyCreditStats> =
        decodeIndex(memory.get(INDEX_ID)?.content)
            .asReversed()
            .asSequence()
            .mapNotNull { key ->
                memory.get(MemoryId(STATS_PREFIX + key))
                    ?.takeIf { it.kind == STATS_KIND }
                    ?.let { CreditCodec.decodeStats(it.content) }
            }
            .take(limit)
            .toList()

    private fun statsKey(stats: GoalStrategyCreditStats): String = statsKey(
        contextDigest = stats.contextDigest,
        kind = stats.componentKind,
        index = stats.componentIndex,
        strategy = stats.strategy
    )

    private fun statsKey(
        contextDigest: String,
        kind: GoalStrategyCreditComponentKind,
        index: Int,
        strategy: StrategySignature
    ): String = sha256(
        listOf(
            contextDigest,
            kind.name,
            index.toString(),
            strategy.digest
        ).joinToString("|")
    )

    private fun markerId(planId: PlanId): MemoryId =
        MemoryId("goal-hierarchical-credit-outcome:" + planId.value)

    private fun planComponentsId(planId: PlanId): MemoryId =
        MemoryId("goal-hierarchical-credit-plan:" + planId.value)

    private fun decodeIndex(content: String?): List<String> {
        if (content == null || !content.startsWith("v=1;keys=")) return emptyList()
        return content.removePrefix("v=1;keys=")
            .split(',')
            .filter { it.matches(SHA256) }
            .distinct()
            .takeLast(MAX_INDEXED_STATS)
    }

    private data class CreditContext(
        val contextDigest: String,
        val goalFingerprint: Set<String>,
        val rootFingerprint: Set<String>,
        val hierarchyDepth: Int
    )

    private data class CreditComponent(
        val kind: GoalStrategyCreditComponentKind,
        val index: Int,
        val strategy: StrategySignature,
        val coveredStepIndices: Set<Int>
    ) {
        init {
            require(coveredStepIndices.isNotEmpty())
        }
    }

    private data class WeightedCredit(
        val stats: GoalStrategyCreditStats,
        val weight: Double
    )

    companion object {
        const val STATS_KIND = "goal-hierarchical-strategy-credit-v1"
        const val OUTCOME_MARKER_KIND = "goal-hierarchical-strategy-credit-outcome-v1"
        const val PLAN_COMPONENT_INDEX_KIND = "goal-hierarchical-strategy-credit-plan-v1"
        const val INDEX_KIND = "goal-hierarchical-strategy-credit-index-v1"
        const val MAX_INDEXED_STATS = 192
        const val MIN_GOAL_SIMILARITY = 0.20
        private const val STEP_WEIGHT = 0.30
        private const val PREFIX_WEIGHT = 0.30
        private const val SEQUENCE_WEIGHT = 0.40
        private const val STATS_PREFIX = "goal-hierarchical-strategy-credit:"
        private val INDEX_ID = MemoryId("goal-hierarchical-strategy-credit:index")
        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

object GoalHierarchicalStrategyCreditPolicy {
    const val MAX_PORTFOLIO_ADJUSTMENT = 0.10

    fun apply(
        goal: String,
        candidates: List<GoalStrategyPortfolioCandidate>,
        credit: GoalHierarchicalStrategyCreditModel
    ): List<GoalStrategyPortfolioCandidate> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.map { candidate ->
            val assessment = credit.assess(
                goal = goal,
                strategy = candidate.assessment.candidate.strategy
            )
            val adjustment = (
                assessment.creditScore *
                    assessment.evidenceConfidence *
                    MAX_PORTFOLIO_ADJUSTMENT
                ).coerceIn(-MAX_PORTFOLIO_ADJUSTMENT, MAX_PORTFOLIO_ADJUSTMENT)
            candidate.copy(
                hierarchicalCreditScore = assessment.creditScore,
                hierarchicalCreditConfidence = assessment.evidenceConfidence,
                hierarchicalMatchedComponents = assessment.matchedComponents,
                hierarchicalCreditAdjustment = adjustment,
                portfolioScore = (candidate.portfolioScore + adjustment).coerceIn(0.0, 1.0)
            )
        }.sortedWith(
            compareByDescending<GoalStrategyPortfolioCandidate> { it.portfolioScore }
                .thenByDescending { it.exploitationScore }
                .thenByDescending { it.assessment.calibratedSupport }
                .thenBy { it.assessment.candidate.strategy.canonical }
        )
    }
}

private object CreditCodec {
    private const val STATS_VERSION = "AMPER_GOAL_HIERARCHICAL_STRATEGY_CREDIT_V1"
    private const val OUTCOME_VERSION = "AMPER_GOAL_HIERARCHICAL_STRATEGY_CREDIT_OUTCOME_V1"
    private const val PLAN_VERSION = "AMPER_GOAL_HIERARCHICAL_STRATEGY_CREDIT_PLAN_V1"

    fun encodeStats(stats: GoalStrategyCreditStats): String = listOf(
        STATS_VERSION,
        stats.contextDigest,
        stats.goalFingerprint.sorted().joinToString(","),
        stats.rootFingerprint.sorted().joinToString(","),
        stats.hierarchyDepth.toString(),
        stats.componentKind.name,
        stats.componentIndex.toString(),
        stats.strategy.capabilities.joinToString(",") { it.value },
        stats.observations.toString(),
        stats.executionSuccesses.toString(),
        stats.executionFailures.toString(),
        stats.goalSuccesses.toString(),
        stats.goalEvidenceFailures.toString(),
        stats.authorityNeutral.toString(),
        stats.partialNeutral.toString(),
        stats.cumulativeCredit.toString(),
        stats.lastObservedAtEpochMs.toString()
    ).joinToString("\t")

    fun decodeStats(content: String): GoalStrategyCreditStats? = runCatching {
        val p = content.split('\t')
        require(p.size == 17 && p[0] == STATS_VERSION)
        GoalStrategyCreditStats(
            contextDigest = p[1],
            goalFingerprint = p[2].split(',').filter { it.isNotBlank() }.toSet(),
            rootFingerprint = p[3].split(',').filter { it.isNotBlank() }.toSet(),
            hierarchyDepth = p[4].toInt(),
            componentKind = GoalStrategyCreditComponentKind.valueOf(p[5]),
            componentIndex = p[6].toInt(),
            strategy = StrategySignature(
                p[7].split(',').filter { it.isNotBlank() }.map(::CapabilityId)
            ),
            observations = p[8].toInt(),
            executionSuccesses = p[9].toInt(),
            executionFailures = p[10].toInt(),
            goalSuccesses = p[11].toInt(),
            goalEvidenceFailures = p[12].toInt(),
            authorityNeutral = p[13].toInt(),
            partialNeutral = p[14].toInt(),
            cumulativeCredit = p[15].toDouble(),
            lastObservedAtEpochMs = p[16].toLong()
        )
    }.getOrNull()

    fun encodeOutcomeMarker(outcome: GoalOutcomeEvidenceKind): String =
        OUTCOME_VERSION + "\t" + outcome.name

    fun decodeOutcomeMarker(content: String): GoalOutcomeEvidenceKind? = runCatching {
        val p = content.split('\t')
        require(p.size == 2 && p[0] == OUTCOME_VERSION)
        GoalOutcomeEvidenceKind.valueOf(p[1])
    }.getOrNull()

    fun encodePlanComponents(keys: List<String>): String =
        PLAN_VERSION + "\t" + keys.joinToString(",")

    fun decodePlanComponents(content: String): List<String> = runCatching {
        val p = content.split('\t')
        require(p.size == 2 && p[0] == PLAN_VERSION)
        p[1].split(',')
            .filter { it.matches(Regex("[0-9a-f]{64}")) }
            .distinct()
    }.getOrDefault(emptyList())
}
