package io.amper.neuroos.core

data class RecoveryClaimItem(
    val claim: PlanSideEffectClaim,
    val plan: SovereignPlan,
    val step: SovereignPlanStep
)

data class RecoveryClaimTarget(
    val planId: PlanId,
    val stepIndex: Int,
    val requestId: ActionRequestId
) {
    init {
        require(stepIndex > 0)
    }
}


/**
 * Read/resolve facade for Android recovery UI. It never invokes a tool provider.
 * Every displayed claim must still bind to the persisted plan snapshot before it
 * can be presented or reconciled.
 */
class SovereignRecoveryConsole(
    private val store: SovereignPlanStore,
    private val planner: PersistentSovereignPlanCoordinator
) {
    private val recoveryState = SovereignRecoveryState(store.receipts)

    fun pending(limit: Int = 16): Result<List<RecoveryClaimItem>> = runCatching {
        require(limit in 0..64) { "recovery claim limit must be between 0 and 64" }
        recoveryState.unresolvedClaims(limit).map { claim ->
            val plan = requireNotNull(store.load(claim.planId)) {
                "pending claim ${claim.requestId.value} has no persisted plan"
            }
            val step = plan.steps.singleOrNull { it.index == claim.stepIndex }
                ?: error("pending claim ${claim.requestId.value} has no matching plan step")
            require(step.status == PlanStepStatus.REQUIRES_CONFIRMATION) {
                "pending claim ${claim.requestId.value} is not awaiting reconciliation"
            }
            require(step.requestId == claim.requestId) { "pending claim request id mismatch" }
            require(step.capability == claim.capability) { "pending claim capability mismatch" }
            require(step.outcome?.toolId == claim.toolId) { "pending claim tool id mismatch" }
            RecoveryClaimItem(claim = claim, plan = plan, step = step)
        }
    }

    /**
     * Resolve an exact read-only navigation target against current canonical recovery evidence.
     *
     * A stale or already-resolved target returns null. Any corrupted/orphan recovery evidence keeps
     * the result failed closed through pending().
     */
    fun locate(target: RecoveryClaimTarget): Result<RecoveryClaimItem?> = runCatching {
        val matches = pending(limit = 64)
            .getOrThrow()
            .filter { item ->
                item.plan.id == target.planId &&
                    item.step.index == target.stepIndex &&
                    item.claim.requestId == target.requestId
            }
        require(matches.size <= 1) {
            "duplicate recovery claim target for " + target.requestId.value
        }
        matches.singleOrNull()
    }

    fun reconcile(
        item: RecoveryClaimItem,
        decision: PlanClaimDecision,
        note: String
    ): Result<PlanAdvanceResult.StepProcessed> {
        if (note.isBlank()) {
            return Result.failure(IllegalArgumentException("reconciliation note is required"))
        }
        if (note.length > 512) {
            return Result.failure(IllegalArgumentException("reconciliation note exceeds 512 characters"))
        }
        return planner.reconcile(
            plan = item.plan,
            stepIndex = item.step.index,
            decision = decision,
            note = note
        )
    }
}
