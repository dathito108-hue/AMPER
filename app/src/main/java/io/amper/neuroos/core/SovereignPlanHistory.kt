package io.amper.neuroos.core

data class SovereignPlanHistoryEntry(
    val planId: PlanId,
    val conversationId: ConversationId,
    val goal: String,
    val createdAtEpochMs: Long,
    val complete: Boolean,
    val stepCount: Int,
    val nextActiveStepIndex: Int?,
    val planningBackendId: String,
    val planningModelId: ModelId?,
    val recoveryRequired: Boolean,
    val cancellationBlockedByDurableClaim: Boolean
)

/**
 * User-facing browser and explicit lifecycle controls for persistent sovereign plans.
 *
 * Assistant one-shot action transactions share the Plan OS storage internally but are
 * deliberately hidden from this surface. Opening a plan only reloads persisted state.
 * Cancellation delegates to SovereignPlanCancellation, which never invokes providers
 * and fails closed when active durable claim evidence exists.
 */
class SovereignPlanHistory(
    private val store: SovereignPlanStore
) {
    private val inspector = SovereignPlanExecutionInspector(store.receipts)
    private val cancellation = SovereignPlanCancellation(store)

    fun recent(
        limit: Int = 8,
        conversationId: ConversationId? = null
    ): List<SovereignPlanHistoryEntry> {
        require(limit >= 0)
        if (limit == 0) return emptyList()

        val scanLimit = (limit * 8).coerceAtLeast(64).coerceAtMost(512)
        return store.list(scanLimit)
            .asSequence()
            .filterNot(AssistantActionTransaction::isTransaction)
            .filter { conversationId == null || it.conversationId == conversationId }
            .map(::entry)
            .take(limit)
            .toList()
    }

    fun open(planId: PlanId): SovereignPlan? = store.load(planId)
        ?.takeUnless(AssistantActionTransaction::isTransaction)

    fun cancelRemaining(planId: PlanId): Result<SovereignPlanCancellationResult> {
        val plan = open(planId)
            ?: return Result.failure(IllegalArgumentException("sovereign plan ${planId.value} is unavailable"))
        return cancellation.cancelRemaining(plan)
    }

    private fun entry(plan: SovereignPlan): SovereignPlanHistoryEntry {
        val view = inspector.inspect(plan)
        val nextActive = plan.steps.firstOrNull {
            it.status == PlanStepStatus.PLANNED || it.status == PlanStepStatus.REQUIRES_CONFIRMATION
        }?.index
        val cancellationBlocked = view.steps.any { step ->
            (step.status == PlanStepStatus.PLANNED || step.status == PlanStepStatus.REQUIRES_CONFIRMATION) &&
                step.claimToolId != null
        }
        return SovereignPlanHistoryEntry(
            planId = plan.id,
            conversationId = plan.conversationId,
            goal = plan.goal,
            createdAtEpochMs = plan.createdAtEpochMs,
            complete = plan.complete,
            stepCount = plan.steps.size,
            nextActiveStepIndex = nextActive,
            planningBackendId = plan.planningBackendId,
            planningModelId = plan.planningModelId,
            recoveryRequired = view.recoveryRequired,
            cancellationBlockedByDurableClaim = cancellationBlocked
        )
    }
}
