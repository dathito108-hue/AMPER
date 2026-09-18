package io.amper.neuroos.core

data class RecoveryClaimItem(
    val claim: PlanSideEffectClaim,
    val plan: SovereignPlan,
    val step: SovereignPlanStep
)

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
