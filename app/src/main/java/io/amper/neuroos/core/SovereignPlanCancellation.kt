package io.amper.neuroos.core

data class SovereignPlanCancellationResult(
    val plan: SovereignPlan,
    val cancelledStepIndices: List<Int>
)

/**
 * Explicit user cancellation for the remaining active portion of a persistent plan.
 *
 * Cancellation is a Plan OS state transition only: it never routes a capability,
 * invokes a provider, grants authority, or creates a side-effect claim. Any active
 * step that already has a durable claim is fail-closed and must be resolved through
 * Recovery Console before the plan can be changed further.
 *
 * Terminal receipts are written before the updated plan snapshot. This makes a retry
 * after interruption idempotent: recordTerminal accepts the same immutable receipt,
 * while a conflicting receipt fails closed.
 */
class SovereignPlanCancellation(
    private val store: SovereignPlanStore
) {
    private val ledger = requireNotNull(store.receipts) {
        "persistent plan cancellation requires a durable receipt ledger"
    }

    fun cancelRemaining(plan: SovereignPlan): Result<SovereignPlanCancellationResult> = runCatching {
        ledger.verifyCompletedPrefix(plan).getOrThrow()

        val active = plan.steps.filter { step ->
            step.status == PlanStepStatus.PLANNED ||
                step.status == PlanStepStatus.REQUIRES_CONFIRMATION
        }
        if (active.isEmpty()) {
            return@runCatching SovereignPlanCancellationResult(plan, emptyList())
        }

        active.forEach { step ->
            val claim = ledger.claim(plan.id, step.requestId)
            require(claim == null) {
                val reconciliation = ledger.reconciliation(plan.id, step.requestId)
                if (reconciliation == null) {
                    "plan step ${step.index} has an unresolved durable side-effect claim; use Recovery Console before cancellation"
                } else {
                    "plan step ${step.index} has a reconciled durable claim whose terminal plan state must be recovered before cancellation"
                }
            }
        }

        val cancelledIndices = active.map { it.index }
        val cancelled = plan.copy(
            steps = plan.steps.map { step ->
                if (step.index in cancelledIndices) {
                    step.copy(status = PlanStepStatus.REJECTED)
                } else {
                    step
                }
            }
        )

        cancelled.steps
            .filter { it.index in cancelledIndices }
            .forEach { step -> ledger.recordTerminal(cancelled, step).getOrThrow() }

        ledger.verifyCompletedPrefix(cancelled).getOrThrow()
        store.save(cancelled)
        SovereignPlanCancellationResult(cancelled, cancelledIndices)
    }
}
