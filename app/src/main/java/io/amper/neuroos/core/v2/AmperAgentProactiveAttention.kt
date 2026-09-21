package io.amper.neuroos.core.v2

import io.amper.neuroos.core.PlanId

enum class AmperAgentProactiveAttentionKind {
    NONE,
    APPROVAL_REQUIRED,
    COMPLETED,
    FAILED,
    PLAN_UNAVAILABLE
}

data class AmperAgentProactiveAttentionDecision(
    val planId: PlanId,
    val sourceId: String,
    val kind: AmperAgentProactiveAttentionKind,
    val goal: String?,
    val waitingApprovalStepIndex: Int?,
    val observedAtEpochMs: Long
) {
    init {
        require(sourceId.isNotBlank())
        require(observedAtEpochMs >= 0L)
        require(
            (kind == AmperAgentProactiveAttentionKind.APPROVAL_REQUIRED) ==
                (waitingApprovalStepIndex != null)
        )
        if (kind == AmperAgentProactiveAttentionKind.PLAN_UNAVAILABLE) {
            require(goal == null)
        }
    }

    val actionable: Boolean
        get() = kind == AmperAgentProactiveAttentionKind.APPROVAL_REQUIRED ||
            kind == AmperAgentProactiveAttentionKind.PLAN_UNAVAILABLE
}

/**
 * Phase663 platform-neutral attention policy.
 *
 * Attention is derived from the Phase662 lifecycle view only. It cannot mutate the plan, approve a
 * tool action, schedule execution, or manufacture task state.
 */
object AmperAgentProactiveAttentionPolicy {
    fun decide(
        view: AmperAgentProactiveTaskLifecycleView
    ): AmperAgentProactiveAttentionDecision {
        val kind = when {
            !view.planAvailable ->
                AmperAgentProactiveAttentionKind.PLAN_UNAVAILABLE
            view.taskState == AmperAgentTaskState.WAITING_APPROVAL ->
                AmperAgentProactiveAttentionKind.APPROVAL_REQUIRED
            view.taskState == AmperAgentTaskState.COMPLETED ->
                AmperAgentProactiveAttentionKind.COMPLETED
            view.taskState in setOf(
                AmperAgentTaskState.FAILED,
                AmperAgentTaskState.CANCELLED
            ) ->
                AmperAgentProactiveAttentionKind.FAILED
            else ->
                AmperAgentProactiveAttentionKind.NONE
        }
        return AmperAgentProactiveAttentionDecision(
            planId = view.binding.planId,
            sourceId = view.binding.sourceId,
            kind = kind,
            goal = view.goal,
            waitingApprovalStepIndex = view.waitingApprovalStepIndex,
            observedAtEpochMs = view.binding.trigger.observedAtEpochMs
        )
    }
}
