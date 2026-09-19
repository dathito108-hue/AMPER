package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64

enum class PersistentGoalExecutiveStage {
    QUEUED,
    WAITING_OBSERVATION,
    LEARNING_PAUSED,
    EVOLUTION_PAUSED,
    RECOVERY_QUEUED,
    RECOVERY_BLOCKED,
    EXECUTION_PAUSED,
    PARTIAL_EXECUTION_BLOCKED,
    RECOVERY_EXHAUSTED,
    FOLLOW_UP_QUEUED,
    FOLLOW_UP_EXHAUSTED,
    PLANNED,
    COMPLETED
}

data class PersistentGoalExecutiveCheckpoint(
    val sourceGoalId: String,
    val objective: String,
    val conversationId: ConversationId,
    val priority: Double,
    val stage: PersistentGoalExecutiveStage,
    val attemptCount: Int = 0,
    val lastAction: CognitiveExecutiveAction? = null,
    val lastCognitiveStateDigest: String? = null,
    val lastExecutionContextDigest: String? = null,
    val plannedPlanId: PlanId? = null,
    val recoveryCount: Int = 0,
    val followUpCount: Int = 0,
    val lastFailureCode: String? = null,
    val lastVerificationVerdict: GoalSatisfactionVerdict? = null,
    val lastVerificationConfidence: Double? = null,
    val lastVerificationReason: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(sourceGoalId.isNotBlank() && sourceGoalId.length <= MAX_GOAL_ID_CHARS)
        require(objective.isNotBlank() && objective.length <= MAX_OBJECTIVE_CHARS)
        require(priority in 0.0..1.0)
        require(attemptCount >= 0)
        require(recoveryCount in 0..MAX_RECOVERY_GENERATIONS)
        require(followUpCount in 0..MAX_FOLLOW_UP_GENERATIONS)
        require(lastFailureCode == null || lastFailureCode.matches(FAILURE_CODE)) {
            "invalid persistent goal recovery failure code"
        }
        require(
            (lastVerificationVerdict == null) == (lastVerificationConfidence == null) &&
                (lastVerificationVerdict == null) == (lastVerificationReason == null)
        ) { "verification checkpoint requires verdict/confidence/reason together" }
        lastVerificationConfidence?.let {
            require(it in 0.0..1.0) { "invalid goal verification confidence" }
        }
        lastVerificationReason?.let {
            require(it.isNotBlank() && it.length <= 256) {
                "invalid goal verification reason"
            }
        }
        require(updatedAtEpochMs >= 0L)
        require((lastCognitiveStateDigest == null) == (lastExecutionContextDigest == null)) {
            "persistent goal executive requires both cognitive digests or neither"
        }
        lastCognitiveStateDigest?.let {
            require(it.matches(SHA256)) { "invalid persistent goal cognitive-state digest" }
        }
        lastExecutionContextDigest?.let {
            require(it.matches(SHA256)) { "invalid persistent goal execution-context digest" }
        }
        val planBoundStages = setOf(
            PersistentGoalExecutiveStage.PLANNED,
            PersistentGoalExecutiveStage.RECOVERY_BLOCKED,
            PersistentGoalExecutiveStage.EXECUTION_PAUSED,
            PersistentGoalExecutiveStage.PARTIAL_EXECUTION_BLOCKED,
            PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED,
            PersistentGoalExecutiveStage.FOLLOW_UP_EXHAUSTED,
            PersistentGoalExecutiveStage.COMPLETED
        )
        if (stage in planBoundStages) {
            require(plannedPlanId != null) {
                "plan-bound persistent goal checkpoint requires a plan id"
            }
        } else {
            require(plannedPlanId == null) {
                "non-plan-bound persistent goal checkpoint cannot retain a plan id"
            }
        }
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MAX_GOAL_ID_CHARS = 256
        const val MAX_OBJECTIVE_CHARS = 1024
        const val MAX_RECOVERY_GENERATIONS = 3
        const val MAX_FOLLOW_UP_GENERATIONS = 3
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val FAILURE_CODE = Regex("[A-Z0-9_:-]{1,128}")
    }
}

interface PersistentGoalExecutiveStore {
    fun load(): PersistentGoalExecutiveCheckpoint?
    fun save(checkpoint: PersistentGoalExecutiveCheckpoint): PersistentGoalExecutiveCheckpoint
    fun clear(): Boolean
}

/**
 * Phase281-285 restart-safe single-checkpoint goal journal.
 *
 * The checkpoint intentionally stores the selected goal objective because the in-memory GoalSystem
 * is not itself a durable scheduler. Android production Memory OS is encrypted at rest.
 */
class MemoryBackedPersistentGoalExecutiveStore(
    private val memory: MemoryOs
) : PersistentGoalExecutiveStore {
    override fun load(): PersistentGoalExecutiveCheckpoint? =
        memory.get(CHECKPOINT_ID)
            ?.takeIf { it.kind == CHECKPOINT_KIND }
            ?.let { PersistentGoalExecutiveCodec.decode(it.content).getOrNull() }

    override fun save(
        checkpoint: PersistentGoalExecutiveCheckpoint
    ): PersistentGoalExecutiveCheckpoint {
        memory.remember(
            MemoryRecord(
                id = CHECKPOINT_ID,
                kind = CHECKPOINT_KIND,
                content = PersistentGoalExecutiveCodec.encode(checkpoint),
                importance = 0.95,
                provenance = Provenance(
                    source = "persistent-goal-executive",
                    producer = "goal-executive-store",
                    confidence = 1.0
                ),
                createdAtEpochMs = checkpoint.updatedAtEpochMs
            )
        )
        return checkpoint
    }

    override fun clear(): Boolean = memory.forget(CHECKPOINT_ID)

    companion object {
        const val CHECKPOINT_KIND = "persistent-goal-executive-v1"
        private val CHECKPOINT_ID = MemoryId("persistent-goal-executive:current")
    }
}

sealed interface PersistentGoalExecutiveResult {
    data class NoGoal(
        val completedGoalId: String? = null
    ) : PersistentGoalExecutiveResult

    data class Deferred(
        val checkpoint: PersistentGoalExecutiveCheckpoint,
        val reason: String
    ) : PersistentGoalExecutiveResult {
        init { require(reason.isNotBlank()) }
    }

    data class Decomposed(
        val parentGoalId: String,
        val childGoalIds: List<String>
    ) : PersistentGoalExecutiveResult {
        init {
            require(parentGoalId.isNotBlank())
            require(childGoalIds.size in 2..DurableGoalRecord.MAX_DECOMPOSITION_CHILDREN)
            require(childGoalIds.all { it.isNotBlank() })
            require(childGoalIds.toSet().size == childGoalIds.size)
        }

        val authorityBearing: Boolean
            get() = false
    }

    data class Replanned(
        val parentGoalId: String,
        val supersededGoalId: String,
        val replacementGoalIds: List<String>
    ) : PersistentGoalExecutiveResult {
        init {
            require(parentGoalId.isNotBlank())
            require(supersededGoalId.isNotBlank())
            require(replacementGoalIds.size in 1..DurableGoalRecord.MAX_ADAPTIVE_REPLACEMENTS)
            require(replacementGoalIds.all { it.isNotBlank() })
            require(replacementGoalIds.toSet().size == replacementGoalIds.size)
        }

        val authorityBearing: Boolean
            get() = false
    }

    data class Ran(
        val checkpoint: PersistentGoalExecutiveCheckpoint,
        val run: CognitiveExecutiveRunResult
    ) : PersistentGoalExecutiveResult
}

/**
 * Persistent goal handoff over the Phase276-280 bounded cognitive executive.
 *
 * Phase281 selects the highest-priority active sovereign goal.
 * Phase282 persists one encrypted-memory checkpoint so the selected goal survives process restart.
 * Phase283 runs only the existing bounded cognitive executive and persists a generated plan.
 * Phase284 resumes WAITING/PRACTICE/EVOLUTION checkpoints, while PLANNED blocks duplicate planning.
 * Phase285 requires explicit plan-completion acknowledgement before the checkpoint is closed and a
 * different active goal may be selected. This coordinator has no ToolFabric/AuthorityGate handle.
 */
class PersistentGoalExecutiveCoordinator(
    private val context: SovereignContextSource,
    private val executive: AutonomousCognitiveExecutive,
    private val store: PersistentGoalExecutiveStore,
    private val plans: SovereignPlanStore,
    private val portfolio: DurableGoalPortfolio? = null,
    private val decomposer: GoalDecomposer? = null,
    private val adaptiveReplanner: GoalAdaptiveReplanner? = null,
    private val outcomeLearning: GoalOutcomeLearningModel? = null,
    private val transferCalibration: GoalTransferCalibrationModel? = null,
    private val strategyPortfolio: GoalContextualStrategyPortfolio? = null,
    private val hierarchicalStrategyCredit: GoalHierarchicalStrategyCreditModel? = null,
    private val repairValidation: GoalRepairValidationModel? = null,
    private val repairStrategyMemory: GoalRepairStrategyMemory? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun runNext(
        conversationId: ConversationId
    ): Result<PersistentGoalExecutiveResult> = runCatching {
        val existing = store.load()
        if (existing != null && existing.stage in BLOCKED_STAGES) {
            maybeAdaptiveReplan(existing)?.let { replanned ->
                return@runCatching replanned
            }
            return@runCatching PersistentGoalExecutiveResult.Deferred(
                checkpoint = existing,
                reason = when (existing.stage) {
                    PersistentGoalExecutiveStage.PLANNED ->
                        "planned goal is already handed off; resolve that terminal plan before replanning"
                    PersistentGoalExecutiveStage.RECOVERY_BLOCKED ->
                        "goal recovery is blocked by authority/user state or an unresolved side-effect claim"
                    PersistentGoalExecutiveStage.EXECUTION_PAUSED ->
                        "autonomous plan execution paused after a nonterminal step failure"
                    PersistentGoalExecutiveStage.PARTIAL_EXECUTION_BLOCKED ->
                        "goal recovery is blocked because part of the plan already executed"
                    PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED ->
                        "goal recovery reached the bounded retry limit"
                    PersistentGoalExecutiveStage.FOLLOW_UP_EXHAUSTED ->
                        "goal satisfaction follow-up reached the bounded verification limit"
                    else -> error("unexpected blocked persistent goal stage")
                }
            )
        }

        val checkpoint = when {
            existing == null -> selectActiveGoal(conversationId, excludedGoalId = null)
            existing.stage == PersistentGoalExecutiveStage.COMPLETED ->
                selectActiveGoal(conversationId, excludedGoalId = existing.sourceGoalId)
            else -> existing
        } ?: return@runCatching PersistentGoalExecutiveResult.NoGoal(
            completedGoalId = existing
                ?.takeIf { it.stage == PersistentGoalExecutiveStage.COMPLETED }
                ?.sourceGoalId
        )

        maybeDecompose(checkpoint)?.let { decomposition ->
            return@runCatching decomposition
        }

        val run = executive.runBounded(
            conversationId = checkpoint.conversationId,
            userGoal = checkpoint.objective
        ).getOrThrow()
        val terminal = run.cycles.last()
        val planned = terminal as? CognitiveExecutiveCycleResult.Planned
        planned?.let { plans.save(it.plan) }

        val nextStage = when (terminal.directive.action) {
            CognitiveExecutiveAction.PLAN -> PersistentGoalExecutiveStage.PLANNED
            CognitiveExecutiveAction.OBSERVE -> PersistentGoalExecutiveStage.WAITING_OBSERVATION
            CognitiveExecutiveAction.PRACTICE -> PersistentGoalExecutiveStage.LEARNING_PAUSED
            CognitiveExecutiveAction.EVOLVE -> PersistentGoalExecutiveStage.EVOLUTION_PAUSED
        }
        val next = checkpoint.copy(
            stage = nextStage,
            attemptCount = checkpoint.attemptCount + 1,
            lastAction = terminal.directive.action,
            lastCognitiveStateDigest = terminal.directive.cognitiveStateDigest,
            lastExecutionContextDigest = terminal.directive.executionContextDigest,
            plannedPlanId = planned?.plan?.id,
            updatedAtEpochMs = clock().coerceAtLeast(checkpoint.updatedAtEpochMs)
        )
        store.save(next)
        PersistentGoalExecutiveResult.Ran(next, run)
    }

    @Synchronized
    fun pausePlannedExecution(
        planId: PlanId,
        failureCode: String
    ): Result<PersistentGoalExecutiveCheckpoint> = runCatching {
        require(failureCode.matches(Regex("[A-Z0-9_:-]{1,128}"))) {
            "invalid autonomous execution failure code"
        }
        val current = requireNotNull(store.load()) {
            "persistent goal executive has no active checkpoint"
        }
        require(current.stage == PersistentGoalExecutiveStage.PLANNED) {
            "persistent goal executive is not awaiting autonomous plan execution"
        }
        require(current.plannedPlanId == planId) {
            "paused plan does not match persistent goal handoff"
        }
        val plan = requireNotNull(plans.load(planId)) {
            "paused persistent goal plan is unavailable from plan store"
        }
        require(!plan.complete) {
            "terminal plan must be resolved instead of execution-paused"
        }

        store.save(
            current.copy(
                stage = PersistentGoalExecutiveStage.EXECUTION_PAUSED,
                lastFailureCode = failureCode,
                updatedAtEpochMs = clock().coerceAtLeast(current.updatedAtEpochMs)
            )
        )
    }

    @Synchronized
    fun rebindPlannedHandoff(
        previousPlanId: PlanId,
        replacementPlanId: PlanId
    ): Result<PersistentGoalExecutiveCheckpoint> = runCatching {
        val current = requireNotNull(store.load()) {
            "persistent goal executive has no active checkpoint"
        }
        require(current.stage == PersistentGoalExecutiveStage.PLANNED) {
            "persistent goal executive is not awaiting a planned handoff"
        }
        require(current.plannedPlanId == previousPlanId) {
            "replaced plan does not match persistent goal handoff"
        }
        val replacement = requireNotNull(plans.load(replacementPlanId)) {
            "replacement persistent goal plan is unavailable from plan store"
        }
        require(replacement.parentPlanId == previousPlanId) {
            "replacement plan is not a direct context-refresh child of the current handoff"
        }
        require(!replacement.complete) {
            "replacement persistent goal plan is already terminal"
        }

        store.save(
            current.copy(
                plannedPlanId = replacementPlanId,
                updatedAtEpochMs = clock().coerceAtLeast(current.updatedAtEpochMs)
            )
        )
    }

    @Synchronized
    fun resolveVerifiedSuccess(
        planId: PlanId,
        assessment: GoalSatisfactionAssessment
    ): Result<PersistentGoalExecutiveCheckpoint> = runCatching {
        val current = requireNotNull(store.load()) {
            "persistent goal executive has no active checkpoint"
        }
        require(current.stage == PersistentGoalExecutiveStage.PLANNED) {
            "persistent goal executive is not awaiting verified plan completion"
        }
        require(current.plannedPlanId == planId && assessment.planId == planId) {
            "goal verification does not match the exact persistent goal handoff"
        }
        val terminalPlan = requireNotNull(plans.load(planId)) {
            "verified persistent goal plan is unavailable from plan store"
        }
        require(terminalPlan.complete && terminalPlan.steps.all { it.status == PlanStepStatus.EXECUTED }) {
            "goal verification requires an all-executed terminal plan"
        }

        val now = clock().coerceAtLeast(current.updatedAtEpochMs)
        val verified = current.copy(
            lastVerificationVerdict = assessment.verdict,
            lastVerificationConfidence = assessment.confidence,
            lastVerificationReason = assessment.reason,
            lastCognitiveStateDigest = assessment.cognitiveStateDigest,
            lastExecutionContextDigest = assessment.executionContextDigest,
            updatedAtEpochMs = now
        )
        val next = when (assessment.verdict) {
            GoalSatisfactionVerdict.SATISFIED -> verified.copy(
                stage = PersistentGoalExecutiveStage.COMPLETED,
                lastFailureCode = null
            )
            GoalSatisfactionVerdict.FOLLOW_UP_REQUIRED -> {
                if (current.followUpCount >=
                    PersistentGoalExecutiveCheckpoint.MAX_FOLLOW_UP_GENERATIONS
                ) {
                    verified.copy(
                        stage = PersistentGoalExecutiveStage.FOLLOW_UP_EXHAUSTED,
                        lastFailureCode = "GOAL_FOLLOW_UP_LIMIT"
                    )
                } else {
                    verified.copy(
                        stage = PersistentGoalExecutiveStage.FOLLOW_UP_QUEUED,
                        plannedPlanId = null,
                        followUpCount = current.followUpCount + 1,
                        lastFailureCode = null
                    )
                }
            }
        }
        val saved = store.save(next)
        if (saved.stage == PersistentGoalExecutiveStage.COMPLETED) {
            markPortfolioCompleted(saved, now)
            observeGoalOutcome(
                checkpoint = saved,
                terminalPlan = terminalPlan,
                outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
                verificationConfidence = assessment.confidence,
                observedAtEpochMs = now
            )
        } else if (saved.stage == PersistentGoalExecutiveStage.FOLLOW_UP_EXHAUSTED) {
            observeGoalOutcome(
                checkpoint = saved,
                terminalPlan = terminalPlan,
                outcome = GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED,
                verificationConfidence = assessment.confidence,
                observedAtEpochMs = now
            )
        }
        saved
    }

    @Synchronized
    fun resolveTerminalPlan(
        planId: PlanId
    ): Result<PersistentGoalExecutiveCheckpoint> = runCatching {
        val current = requireNotNull(store.load()) {
            "persistent goal executive has no active checkpoint"
        }
        require(current.stage in PLAN_RESOLUTION_STAGES) {
            "persistent goal executive is not awaiting terminal plan resolution"
        }
        require(current.plannedPlanId == planId) {
            "resolved plan does not match persistent goal handoff"
        }
        val terminalPlan = requireNotNull(plans.load(planId)) {
            "persistent goal plan is unavailable from plan store"
        }
        require(terminalPlan.complete) {
            "persistent goal plan is not terminal"
        }

        val now = clock().coerceAtLeast(current.updatedAtEpochMs)
        val statuses = terminalPlan.steps.map { it.status }
        val successful = statuses.all { it == PlanStepStatus.EXECUTED }
        val executedCount = statuses.count { it == PlanStepStatus.EXECUTED }
        val authorityOrUserBlocked = statuses.any {
            it == PlanStepStatus.DENIED || it == PlanStepStatus.REJECTED
        }
        val unresolvedClaims = plans.receipts
            ?.let { SovereignRecoveryState(it).hasUnresolvedClaims() }
            ?: false

        val next = when {
            successful -> current.copy(
                stage = PersistentGoalExecutiveStage.COMPLETED,
                lastFailureCode = null,
                updatedAtEpochMs = now
            )
            executedCount > 0 -> current.copy(
                stage = PersistentGoalExecutiveStage.PARTIAL_EXECUTION_BLOCKED,
                lastFailureCode = "PARTIAL_EXECUTION",
                updatedAtEpochMs = now
            )
            authorityOrUserBlocked -> current.copy(
                stage = PersistentGoalExecutiveStage.RECOVERY_BLOCKED,
                lastFailureCode = "AUTHORITY_OR_USER_BLOCK",
                updatedAtEpochMs = now
            )
            unresolvedClaims -> current.copy(
                stage = PersistentGoalExecutiveStage.RECOVERY_BLOCKED,
                lastFailureCode = "UNRESOLVED_SIDE_EFFECT_CLAIM",
                updatedAtEpochMs = now
            )
            current.recoveryCount >=
                PersistentGoalExecutiveCheckpoint.MAX_RECOVERY_GENERATIONS -> current.copy(
                stage = PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED,
                lastFailureCode = "RECOVERY_LIMIT_REACHED",
                updatedAtEpochMs = now
            )
            else -> current.copy(
                stage = PersistentGoalExecutiveStage.RECOVERY_QUEUED,
                plannedPlanId = null,
                recoveryCount = current.recoveryCount + 1,
                lastFailureCode = terminalFailureCode(statuses),
                updatedAtEpochMs = now
            )
        }
        val saved = store.save(next)
        runCatching {
            transferCalibration?.observeTerminalPlan(
                plan = terminalPlan,
                observedAtEpochMs = now
            )
        }
        runCatching {
            strategyPortfolio?.observeTerminalPlan(
                plan = terminalPlan,
                observedAtEpochMs = now
            )
        }
        runCatching {
            hierarchicalStrategyCredit?.observeTerminalPlan(
                checkpoint = current,
                plan = terminalPlan,
                portfolioRecords = portfolio?.snapshot().orEmpty(),
                observedAtEpochMs = now
            )
        }
        if (saved.stage == PersistentGoalExecutiveStage.COMPLETED) {
            markPortfolioCompleted(saved, now)
        } else {
            val learnedOutcome = when (saved.stage) {
                PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED ->
                    GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED
                PersistentGoalExecutiveStage.PARTIAL_EXECUTION_BLOCKED ->
                    GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED
                PersistentGoalExecutiveStage.RECOVERY_BLOCKED ->
                    if (saved.lastFailureCode == "AUTHORITY_OR_USER_BLOCK") {
                        GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED
                    } else {
                        null
                    }
                else -> null
            }
            learnedOutcome?.let { outcome ->
                observeGoalOutcome(
                    checkpoint = saved,
                    terminalPlan = terminalPlan,
                    outcome = outcome,
                    verificationConfidence = null,
                    observedAtEpochMs = now
                )
            }
        }
        saved
    }

    @Synchronized
    fun completePlanned(
        planId: PlanId
    ): Result<PersistentGoalExecutiveCheckpoint> = runCatching {
        val resolved = resolveTerminalPlan(planId).getOrThrow()
        require(resolved.stage == PersistentGoalExecutiveStage.COMPLETED) {
            "persistent goal plan did not complete successfully"
        }
        resolved
    }

    fun current(): PersistentGoalExecutiveCheckpoint? = store.load()

    private fun observeGoalOutcome(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        terminalPlan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        verificationConfidence: Double?,
        observedAtEpochMs: Long
    ) {
        val learner = outcomeLearning
        if (learner != null) {
            val records = portfolio?.snapshot().orEmpty()
            val durable = records.singleOrNull { it.sourceGoalId == checkpoint.sourceGoalId }
            val hierarchy = durable?.let {
                hierarchyRootId(records, it.sourceGoalId)?.let { rootId ->
                    runCatching {
                        DurableGoalHierarchyProgressPolicy.snapshot(records, rootId)
                    }.getOrNull()
                }
            }
            runCatching {
                learner.observe(
                    checkpoint = checkpoint,
                    terminalPlan = terminalPlan,
                    outcome = outcome,
                    hierarchy = hierarchy,
                    hierarchyDepth = durable?.decompositionDepth ?: 0,
                    verificationConfidence = verificationConfidence,
                    observedAtEpochMs = observedAtEpochMs
                )
            }
        }
        runCatching {
            repairValidation?.observeGovernedOutcome(
                plan = terminalPlan,
                outcome = outcome,
                observedAtEpochMs = observedAtEpochMs
            )
        }
        runCatching {
            repairStrategyMemory?.observe(
                plan = terminalPlan,
                outcome = outcome,
                observedAtEpochMs = observedAtEpochMs
            )
        }
        runCatching {
            transferCalibration?.observe(
                plan = terminalPlan,
                outcome = outcome,
                observedAtEpochMs = observedAtEpochMs
            )
        }
        runCatching {
            strategyPortfolio?.observe(
                plan = terminalPlan,
                outcome = outcome,
                observedAtEpochMs = observedAtEpochMs
            )
        }
        runCatching {
            hierarchicalStrategyCredit?.observe(
                checkpoint = checkpoint,
                plan = terminalPlan,
                outcome = outcome,
                portfolioRecords = portfolio?.snapshot().orEmpty(),
                observedAtEpochMs = observedAtEpochMs
            )
        }
    }

    private fun hierarchyRootId(
        records: Collection<DurableGoalRecord>,
        sourceGoalId: String
    ): String? {
        val byId = records.associateBy { it.sourceGoalId }
        var current = byId[sourceGoalId] ?: return null
        val visited = linkedSetOf<String>()
        while (true) {
            if (!visited.add(current.sourceGoalId)) return null
            val parentId = current.parentGoalId ?: return current.sourceGoalId
            current = byId[parentId] ?: return current.sourceGoalId
        }
    }

    private fun markPortfolioCompleted(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        completedAtEpochMs: Long
    ) {
        val goalPortfolio = portfolio ?: return
        goalPortfolio.observe(
            candidates = listOf(
                DurableGoalCandidate(
                    sourceGoalId = checkpoint.sourceGoalId,
                    objective = checkpoint.objective,
                    priority = checkpoint.priority
                )
            ),
            observedAtEpochMs = completedAtEpochMs
        )
        goalPortfolio.markCompleted(
            sourceGoalId = checkpoint.sourceGoalId,
            completedAtEpochMs = completedAtEpochMs
        )
    }

    private fun maybeDecompose(
        checkpoint: PersistentGoalExecutiveCheckpoint
    ): PersistentGoalExecutiveResult.Decomposed? {
        if (checkpoint.stage != PersistentGoalExecutiveStage.QUEUED) return null
        val goalPortfolio = portfolio ?: return null
        val goalDecomposer = decomposer ?: return null
        val durable = goalPortfolio.get(checkpoint.sourceGoalId) ?: return null
        if (durable.decompositionState != DurableGoalDecompositionState.NONE) return null

        val now = clock().coerceAtLeast(checkpoint.updatedAtEpochMs)
        if (durable.decompositionDepth >= DurableGoalRecord.MAX_DECOMPOSITION_DEPTH) {
            requireNotNull(
                goalPortfolio.markAtomic(
                    sourceGoalId = durable.sourceGoalId,
                    updatedAtEpochMs = now
                )
            ) { "maximum-depth durable goal disappeared before atomic marking" }
            return null
        }

        val assessment = goalDecomposer.decompose(durable).getOrThrow()
        return when (assessment.verdict) {
            GoalDecompositionVerdict.ATOMIC -> {
                requireNotNull(
                    goalPortfolio.markAtomic(
                        sourceGoalId = durable.sourceGoalId,
                        updatedAtEpochMs = now
                    )
                ) { "durable goal disappeared before atomic decomposition marking" }
                null
            }
            GoalDecompositionVerdict.DECOMPOSED -> {
                val application = requireNotNull(
                    goalPortfolio.applyDecomposition(
                        sourceGoalId = durable.sourceGoalId,
                        specs = assessment.subgoals,
                        updatedAtEpochMs = now
                    )
                ) { "durable goal disappeared before decomposition application" }
                require(store.clear()) {
                    "decomposed parent checkpoint could not be released"
                }
                PersistentGoalExecutiveResult.Decomposed(
                    parentGoalId = application.parent.sourceGoalId,
                    childGoalIds = application.children.map { it.sourceGoalId }
                )
            }
        }
    }

    private fun maybeAdaptiveReplan(
        checkpoint: PersistentGoalExecutiveCheckpoint
    ): PersistentGoalExecutiveResult.Replanned? {
        if (checkpoint.stage != PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED) return null
        val goalPortfolio = portfolio ?: return null
        val replanner = adaptiveReplanner ?: return null
        val records = goalPortfolio.snapshot()
        val durable = records.singleOrNull {
            it.sourceGoalId == checkpoint.sourceGoalId
        } ?: return null
        val planId = checkpoint.plannedPlanId ?: return null
        val failedPlan = plans.load(planId) ?: return null

        if (
            !GoalAdaptiveReplanningEligibility.isEligible(
                checkpoint = checkpoint,
                failedPlan = failedPlan,
                goal = durable,
                records = records
            )
        ) {
            return null
        }
        val unresolvedClaims = plans.receipts
            ?.let { SovereignRecoveryState(it).hasUnresolvedClaims() }
            ?: false
        if (unresolvedClaims) return null

        val now = clock().coerceAtLeast(checkpoint.updatedAtEpochMs)
        val marked = requireNotNull(
            goalPortfolio.markAdaptiveReplanAttempt(
                sourceGoalId = durable.sourceGoalId,
                attemptedAtEpochMs = now
            )
        ) { "adaptive-replan leaf disappeared before attempt persistence" }
        val parentGoalId = requireNotNull(marked.parentGoalId)
        val progress = DurableGoalHierarchyProgressPolicy.snapshot(
            records = goalPortfolio.snapshot(),
            rootGoalId = parentGoalId
        )
        val assessment = replanner.replan(
            checkpoint = checkpoint,
            failedPlan = failedPlan,
            goal = marked,
            progress = progress
        ).getOrThrow()

        if (assessment.verdict == GoalAdaptiveReplanVerdict.KEEP_BLOCKED) {
            return null
        }

        val replacement = requireNotNull(
            goalPortfolio.replacePendingLeaf(
                sourceGoalId = marked.sourceGoalId,
                specs = assessment.replacements,
                updatedAtEpochMs = now
            )
        ) { "adaptive-replan leaf disappeared before replacement application" }
        require(store.clear()) {
            "adaptive-replanned leaf checkpoint could not be released"
        }
        return PersistentGoalExecutiveResult.Replanned(
            parentGoalId = replacement.parentGoalId,
            supersededGoalId = replacement.superseded.sourceGoalId,
            replacementGoalIds = replacement.replacements.map { it.sourceGoalId }
        )
    }

    private fun terminalFailureCode(statuses: List<PlanStepStatus>): String {
        val material = statuses
            .filterNot { it == PlanStepStatus.EXECUTED }
            .map { it.name }
            .distinct()
            .sorted()
            .joinToString("_")
            .ifBlank { "UNKNOWN" }
        return ("TERMINAL_PLAN_" + material).take(128)
    }

    private fun selectActiveGoal(
        conversationId: ConversationId,
        excludedGoalId: String?
    ): PersistentGoalExecutiveCheckpoint? {
        val snapshot = context.capture(
            query = GOAL_SCAN_QUERY,
            memoryLimit = 0,
            worldLimit = 0,
            workspaceLimit = 0
        )
        val now = clock().coerceAtLeast(0L)
        val durable = portfolio?.let { goalPortfolio ->
            goalPortfolio.observe(
                candidates = snapshot.goals.map { goal ->
                    DurableGoalCandidate(
                        sourceGoalId = goal.id.value.take(DurableGoalRecord.MAX_GOAL_ID_CHARS),
                        objective = goal.objective.take(DurableGoalRecord.MAX_OBJECTIVE_CHARS),
                        priority = goal.priority
                    )
                },
                observedAtEpochMs = now
            )
            goalPortfolio.selectNext(
                excludedGoalId = excludedGoalId,
                selectedAtEpochMs = now
            )
        }

        val sourceGoalId: String
        val objective: String
        val priority: Double
        if (durable != null) {
            sourceGoalId = durable.sourceGoalId
            objective = durable.objective
            priority = durable.priority
        } else if (portfolio != null) {
            return null
        } else {
            val goal = snapshot.goals
                .asSequence()
                .filterNot { it.id.value == excludedGoalId }
                .sortedWith(
                    compareByDescending<GoalState> { it.priority }
                        .thenBy { it.id.value }
                )
                .firstOrNull()
                ?: return null
            sourceGoalId =
                goal.id.value.take(PersistentGoalExecutiveCheckpoint.MAX_GOAL_ID_CHARS)
            objective =
                goal.objective.take(PersistentGoalExecutiveCheckpoint.MAX_OBJECTIVE_CHARS)
            priority = goal.priority
        }

        return store.save(
            PersistentGoalExecutiveCheckpoint(
                sourceGoalId = sourceGoalId,
                objective = objective,
                conversationId = conversationId,
                priority = priority,
                stage = PersistentGoalExecutiveStage.QUEUED,
                updatedAtEpochMs = now
            )
        )
    }

    companion object {
        private const val GOAL_SCAN_QUERY = "active sovereign goals"
        private val BLOCKED_STAGES = setOf(
            PersistentGoalExecutiveStage.PLANNED,
            PersistentGoalExecutiveStage.RECOVERY_BLOCKED,
            PersistentGoalExecutiveStage.EXECUTION_PAUSED,
            PersistentGoalExecutiveStage.PARTIAL_EXECUTION_BLOCKED,
            PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED,
            PersistentGoalExecutiveStage.FOLLOW_UP_EXHAUSTED
        )
        private val PLAN_RESOLUTION_STAGES = setOf(
            PersistentGoalExecutiveStage.PLANNED,
            PersistentGoalExecutiveStage.RECOVERY_BLOCKED,
            PersistentGoalExecutiveStage.EXECUTION_PAUSED,
            PersistentGoalExecutiveStage.PARTIAL_EXECUTION_BLOCKED,
            PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED,
            PersistentGoalExecutiveStage.FOLLOW_UP_EXHAUSTED
        )
    }
}

internal object PersistentGoalExecutiveCodec {
    private const val VERSION_V1 = "AMPER_PERSISTENT_GOAL_EXECUTIVE_V1"
    private const val VERSION_V2 = "AMPER_PERSISTENT_GOAL_EXECUTIVE_V2"
    private const val VERSION = "AMPER_PERSISTENT_GOAL_EXECUTIVE_V3"

    fun encode(checkpoint: PersistentGoalExecutiveCheckpoint): String = buildString {
        appendLine(VERSION)
        appendLine("GOAL_ID\t" + enc(checkpoint.sourceGoalId))
        appendLine("OBJECTIVE\t" + enc(checkpoint.objective))
        appendLine("CONVERSATION\t" + enc(checkpoint.conversationId.value))
        appendLine("PRIORITY\t" + checkpoint.priority)
        appendLine("STAGE\t" + checkpoint.stage.name)
        appendLine("ATTEMPTS\t" + checkpoint.attemptCount)
        appendLine("LAST_ACTION\t" + (checkpoint.lastAction?.name ?: "~"))
        appendLine("COGNITIVE_DIGEST\t" + (checkpoint.lastCognitiveStateDigest ?: "~"))
        appendLine("EXECUTION_DIGEST\t" + (checkpoint.lastExecutionContextDigest ?: "~"))
        appendLine("PLAN_ID\t" + (checkpoint.plannedPlanId?.value?.let(::enc) ?: "~"))
        appendLine("RECOVERY_COUNT\t" + checkpoint.recoveryCount)
        appendLine("FOLLOW_UP_COUNT\t" + checkpoint.followUpCount)
        appendLine("FAILURE_CODE\t" + (checkpoint.lastFailureCode ?: "~"))
        appendLine("VERIFY_VERDICT\t" + (checkpoint.lastVerificationVerdict?.name ?: "~"))
        appendLine("VERIFY_CONFIDENCE\t" + (checkpoint.lastVerificationConfidence?.toString() ?: "~"))
        appendLine("VERIFY_REASON\t" + (checkpoint.lastVerificationReason?.let(::enc) ?: "~"))
        append("UPDATED\t" + checkpoint.updatedAtEpochMs)
    }

    fun decode(content: String): Result<PersistentGoalExecutiveCheckpoint> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        val version = lines.firstOrNull()
        require(version == VERSION || version == VERSION_V2 || version == VERSION_V1) {
            "unsupported persistent goal executive state"
        }
        val fields = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val parts = line.split('\t')
            require(parts.size == 2) { "invalid persistent goal executive field" }
            require(fields.put(parts[0], parts[1]) == null) {
                "duplicate persistent goal executive field"
            }
        }
        val required = setOf(
            "GOAL_ID",
            "OBJECTIVE",
            "CONVERSATION",
            "PRIORITY",
            "STAGE",
            "ATTEMPTS",
            "LAST_ACTION",
            "COGNITIVE_DIGEST",
            "EXECUTION_DIGEST",
            "PLAN_ID",
            "UPDATED"
        )
        require(fields.keys.containsAll(required)) { "persistent goal executive state is incomplete" }
        if (version == VERSION || version == VERSION_V2) {
            require(fields.keys.containsAll(setOf("RECOVERY_COUNT", "FAILURE_CODE"))) {
                "persistent goal executive recovery state is incomplete"
            }
        }
        if (version == VERSION) {
            require(
                fields.keys.containsAll(
                    setOf(
                        "FOLLOW_UP_COUNT",
                        "VERIFY_VERDICT",
                        "VERIFY_CONFIDENCE",
                        "VERIFY_REASON"
                    )
                )
            ) { "persistent goal executive verification state is incomplete" }
        }

        PersistentGoalExecutiveCheckpoint(
            sourceGoalId = dec(fields.getValue("GOAL_ID")),
            objective = dec(fields.getValue("OBJECTIVE")),
            conversationId = ConversationId(dec(fields.getValue("CONVERSATION"))),
            priority = fields.getValue("PRIORITY").toDouble(),
            stage = PersistentGoalExecutiveStage.valueOf(fields.getValue("STAGE")),
            attemptCount = fields.getValue("ATTEMPTS").toInt(),
            lastAction = fields.getValue("LAST_ACTION")
                .takeUnless { it == "~" }
                ?.let(CognitiveExecutiveAction::valueOf),
            lastCognitiveStateDigest = fields.getValue("COGNITIVE_DIGEST")
                .takeUnless { it == "~" },
            lastExecutionContextDigest = fields.getValue("EXECUTION_DIGEST")
                .takeUnless { it == "~" },
            plannedPlanId = fields.getValue("PLAN_ID")
                .takeUnless { it == "~" }
                ?.let(::dec)
                ?.let(::PlanId),
            recoveryCount = fields["RECOVERY_COUNT"]?.toInt() ?: 0,
            followUpCount = fields["FOLLOW_UP_COUNT"]?.toInt() ?: 0,
            lastFailureCode = fields["FAILURE_CODE"]?.takeUnless { it == "~" },
            lastVerificationVerdict = fields["VERIFY_VERDICT"]
                ?.takeUnless { it == "~" }
                ?.let(GoalSatisfactionVerdict::valueOf),
            lastVerificationConfidence = fields["VERIFY_CONFIDENCE"]
                ?.takeUnless { it == "~" }
                ?.toDouble(),
            lastVerificationReason = fields["VERIFY_REASON"]
                ?.takeUnless { it == "~" }
                ?.let(::dec),
            updatedAtEpochMs = fields.getValue("UPDATED").toLong()
        )
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
