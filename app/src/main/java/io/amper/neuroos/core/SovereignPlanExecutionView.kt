package io.amper.neuroos.core

enum class PlanDurabilityEvidence {
    LEDGER_UNAVAILABLE,
    NONE,
    CLAIMED_UNRESOLVED,
    RECEIPTED,
    RECONCILED
}

enum class PlanToolBindingState {
    BOUND,
    UNBOUND_LEGACY
}

data class SovereignPlanStepView(
    val index: Int,
    val requestId: ActionRequestId,
    val shortRequestId: String,
    val status: PlanStepStatus,
    val capability: CapabilityId,
    val reason: String,
    val input: String,
    val bindingState: PlanToolBindingState,
    val boundToolId: ToolId?,
    val boundSideEffect: ToolSideEffect?,
    val bindingLabel: String,
    val actionStatus: ActionStatus?,
    val durabilityEvidence: PlanDurabilityEvidence,
    val durabilityLabel: String,
    val claimToolId: ToolId?,
    val claimSideEffect: ToolSideEffect?,
    val receiptSha256: String?,
    val reconciliationDecision: PlanClaimDecision?
)

data class SovereignPlanExecutionView(
    val planId: PlanId,
    val goal: String,
    val complete: Boolean,
    val planningBackendId: String,
    val planningModelId: ModelId?,
    val planningSelectedCapabilities: Set<CapabilityId>,
    val receiptLedgerAvailable: Boolean,
    val recoveryRequired: Boolean,
    val steps: List<SovereignPlanStepView>
)

/**
 * Read-only projection of one persisted sovereign plan for operator/user interfaces.
 *
 * The inspector never advances a plan, approves a side effect, mutates the receipt
 * ledger, invokes a provider, or infers a missing plan-time binding. V1/V2/V3 steps
 * without Phase-127 binding remain explicitly UNBOUND_LEGACY.
 */
class SovereignPlanExecutionInspector(
    private val ledger: SovereignPlanReceiptLedger?
) {
    fun inspect(plan: SovereignPlan): SovereignPlanExecutionView {
        var recoveryRequired = false
        val steps = plan.steps.map { step ->
            val claim = ledger?.claim(plan.id, step.requestId)
            val receipt = ledger?.receipt(plan.id, step.requestId)
            val reconciliation = ledger?.reconciliation(plan.id, step.requestId)
            val evidence = when {
                ledger == null -> PlanDurabilityEvidence.LEDGER_UNAVAILABLE
                reconciliation != null -> PlanDurabilityEvidence.RECONCILED
                receipt != null -> PlanDurabilityEvidence.RECEIPTED
                claim != null -> PlanDurabilityEvidence.CLAIMED_UNRESOLVED
                else -> PlanDurabilityEvidence.NONE
            }
            if (evidence == PlanDurabilityEvidence.CLAIMED_UNRESOLVED) {
                recoveryRequired = true
            }

            val bindingState = if (step.boundToolId != null && step.boundSideEffect != null) {
                PlanToolBindingState.BOUND
            } else {
                PlanToolBindingState.UNBOUND_LEGACY
            }
            val bindingLabel = when (bindingState) {
                PlanToolBindingState.BOUND ->
                    "${step.boundToolId!!.value} / ${step.boundSideEffect!!.name}"
                PlanToolBindingState.UNBOUND_LEGACY -> "unbound/legacy"
            }

            SovereignPlanStepView(
                index = step.index,
                requestId = step.requestId,
                shortRequestId = abbreviate(step.requestId.value),
                status = step.status,
                capability = step.capability,
                reason = step.reason,
                input = step.input,
                bindingState = bindingState,
                boundToolId = step.boundToolId,
                boundSideEffect = step.boundSideEffect,
                bindingLabel = bindingLabel,
                actionStatus = step.outcome?.status,
                durabilityEvidence = evidence,
                durabilityLabel = evidence.label(),
                claimToolId = claim?.toolId,
                claimSideEffect = claim?.sideEffect,
                receiptSha256 = receipt?.receiptSha256,
                reconciliationDecision = reconciliation?.decision
            )
        }
        return SovereignPlanExecutionView(
            planId = plan.id,
            goal = plan.goal,
            complete = plan.complete,
            planningBackendId = plan.planningBackendId,
            planningModelId = plan.planningModelId,
            planningSelectedCapabilities = plan.planningSelectedCapabilities,
            receiptLedgerAvailable = ledger != null,
            recoveryRequired = recoveryRequired,
            steps = steps
        )
    }

    private fun abbreviate(value: String): String =
        if (value.length <= 16) value else "${value.take(8)}…${value.takeLast(4)}"

    private fun PlanDurabilityEvidence.label(): String = when (this) {
        PlanDurabilityEvidence.LEDGER_UNAVAILABLE -> "receipt ledger unavailable"
        PlanDurabilityEvidence.NONE -> "no durable evidence"
        PlanDurabilityEvidence.CLAIMED_UNRESOLVED -> "durable side-effect claim unresolved"
        PlanDurabilityEvidence.RECEIPTED -> "terminal receipt exists"
        PlanDurabilityEvidence.RECONCILED -> "manual reconciliation exists"
    }
}
