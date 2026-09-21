package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ActionStatus
import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.PlanClaimDecision
import io.amper.neuroos.core.PlanDurabilityEvidence
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.SovereignPlanExecutionInspector
import io.amper.neuroos.core.SovereignPlanReceiptLedger

data class AmperAgentProactiveTaskReceiptView(
    val stepIndex: Int,
    val capability: CapabilityId,
    val stepStatus: PlanStepStatus,
    val actionStatus: ActionStatus?,
    val durabilityEvidence: PlanDurabilityEvidence,
    val receiptSha256: String?,
    val reconciliationDecision: PlanClaimDecision?
) {
    init {
        require(stepIndex > 0)
        receiptSha256?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
    }
}

data class AmperAgentProactiveTaskHistoryEntry(
    val lifecycle: AmperAgentProactiveTaskLifecycleView,
    val receiptLedgerAvailable: Boolean,
    val recoveryRequired: Boolean,
    val receipts: List<AmperAgentProactiveTaskReceiptView>
) {
    init {
        if (!lifecycle.planAvailable) {
            require(receipts.isEmpty())
            require(!recoveryRequired)
        } else {
            require(receipts.size == lifecycle.totalSteps)
        }
    }

    val durableTerminalEvidenceSteps: Int
        get() = receipts.count {
            it.durabilityEvidence == PlanDurabilityEvidence.RECEIPTED ||
                it.durabilityEvidence == PlanDurabilityEvidence.RECONCILED
        }

    val unresolvedClaimSteps: Int
        get() = receipts.count {
            it.durabilityEvidence == PlanDurabilityEvidence.CLAIMED_UNRESOLVED
        }
}

/**
 * Phase665 bounded read-only proactive history/receipt projection.
 *
 * No task state, receipt, claim, reconciliation, plan, or history record is persisted here. Task
 * state still comes from Phase662 lifecycle inspection, and durability evidence comes only from the
 * canonical SovereignPlanExecutionInspector over the existing SovereignPlanReceiptLedger.
 */
class AmperAgentProactiveTaskHistoryProjection(
    private val lifecycle: AmperAgentProactiveTaskLifecycleCoordinator,
    receipts: SovereignPlanReceiptLedger?
) {
    private val receiptLedgerAvailable = receipts != null
    private val inspector = SovereignPlanExecutionInspector(receipts)

    fun recent(limit: Int = 8): Result<List<AmperAgentProactiveTaskHistoryEntry>> = runCatching {
        require(limit in 0..MAX_HISTORY_ENTRIES)
        if (limit == 0) return@runCatching emptyList()

        lifecycle.inspect(limit).map { lifecycleView ->
            if (!lifecycleView.planAvailable) {
                return@map AmperAgentProactiveTaskHistoryEntry(
                    lifecycle = lifecycleView,
                    receiptLedgerAvailable = receiptLedgerAvailable,
                    recoveryRequired = false,
                    receipts = emptyList()
                )
            }

            val plan = requireNotNull(
                lifecycle.openPlan(lifecycleView.binding.planId)
            ) {
                "proactive history canonical plan disappeared after lifecycle inspection"
            }
            val execution = inspector.inspect(plan)
            require(execution.planId == lifecycleView.binding.planId) {
                "proactive history plan identity drifted from lifecycle provenance"
            }
            require(execution.steps.size == lifecycleView.totalSteps) {
                "proactive history step count drifted from canonical lifecycle state"
            }

            AmperAgentProactiveTaskHistoryEntry(
                lifecycle = lifecycleView,
                receiptLedgerAvailable = execution.receiptLedgerAvailable,
                recoveryRequired = execution.recoveryRequired,
                receipts = execution.steps.map { step ->
                    AmperAgentProactiveTaskReceiptView(
                        stepIndex = step.index,
                        capability = step.capability,
                        stepStatus = step.status,
                        actionStatus = step.actionStatus,
                        durabilityEvidence = step.durabilityEvidence,
                        receiptSha256 = step.receiptSha256,
                        reconciliationDecision = step.reconciliationDecision
                    )
                }
            )
        }
    }

    companion object {
        const val MAX_HISTORY_ENTRIES: Int = 64
    }
}
