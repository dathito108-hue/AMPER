package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ActionStatus
import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.PlanClaimDecision
import io.amper.neuroos.core.PlanDurabilityEvidence
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.RecoveryClaimTarget
import io.amper.neuroos.core.SovereignPlanExecutionInspector
import io.amper.neuroos.core.SovereignPlanReceiptLedger

data class AmperAgentProactiveTaskReceiptView(
    val stepIndex: Int,
    val capability: CapabilityId,
    val stepStatus: PlanStepStatus,
    val actionStatus: ActionStatus?,
    val durabilityEvidence: PlanDurabilityEvidence,
    val receiptSha256: String?,
    val reconciliationDecision: PlanClaimDecision?,
    val recoveryTarget: RecoveryClaimTarget?
) {
    init {
        require(stepIndex > 0)
        receiptSha256?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
        require(
            (durabilityEvidence == PlanDurabilityEvidence.CLAIMED_UNRESOLVED) ==
                (recoveryTarget != null)
        )
        recoveryTarget?.let { target ->
            require(target.stepIndex == stepIndex)
        }
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

        var lastProjectionFailure: Throwable? = null
        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val before = lifecycle.inspect(limit)
            val projected = runCatching {
                project(before)
            }
            val after = lifecycle.inspect(limit)

            if (projected.isSuccess && before == after) {
                return@runCatching projected.getOrThrow()
            }
            lastProjectionFailure = projected.exceptionOrNull()
        }

        throw IllegalStateException(
            "coherent proactive history snapshot unavailable after " +
                "$MAX_SNAPSHOT_ATTEMPTS bounded attempts",
            lastProjectionFailure
        )
    }

    private fun project(
        lifecycleViews: List<AmperAgentProactiveTaskLifecycleView>
    ): List<AmperAgentProactiveTaskHistoryEntry> =
        lifecycleViews.map { lifecycleView ->
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
                "proactive history canonical plan disappeared during snapshot read"
            }
            val loadedLifecycle = requireNotNull(
                lifecycle.inspectLoadedPlan(plan)
            ) {
                "proactive history lifecycle binding disappeared during snapshot read"
            }
            require(loadedLifecycle == lifecycleView) {
                "proactive history lifecycle changed while reading canonical plan"
            }

            val execution = inspector.inspect(plan)
            val executionVerification = inspector.inspect(plan)
            require(execution == executionVerification) {
                "proactive history receipt evidence changed during snapshot read"
            }
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
                        reconciliationDecision = step.reconciliationDecision,
                        recoveryTarget = if (
                            step.durabilityEvidence ==
                            PlanDurabilityEvidence.CLAIMED_UNRESOLVED
                        ) {
                            RecoveryClaimTarget(
                                planId = lifecycleView.binding.planId,
                                stepIndex = step.index,
                                requestId = step.requestId
                            )
                        } else {
                            null
                        }
                    )
                }
            )
        }

    companion object {
        const val MAX_HISTORY_ENTRIES: Int = 64
        const val MAX_SNAPSHOT_ATTEMPTS: Int = 3
    }
}
