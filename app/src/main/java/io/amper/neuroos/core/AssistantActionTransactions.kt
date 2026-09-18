package io.amper.neuroos.core

/**
 * Reuses the persistent sovereign Plan OS ledger for one-step assistant side effects.
 * This gives chat approvals the same durable pre-execution claim, terminal receipt,
 * Recovery Console and global recovery-debt semantics as governed plans.
 */
object AssistantActionTransaction {
    const val BACKEND_PREFIX = "assistant-action:"

    fun planId(requestId: ActionRequestId): PlanId =
        PlanId("$BACKEND_PREFIX${requestId.value}")

    fun isTransaction(plan: SovereignPlan): Boolean =
        plan.planningBackendId.startsWith(BACKEND_PREFIX)
}

class DurableAssistantActionExecutor(
    private val store: SovereignPlanStore,
    private val actions: SovereignActionLoop
) {
    private val ledger = requireNotNull(store.receipts) {
        "durable assistant side-effect execution requires a persistent receipt ledger"
    }

    fun approve(pending: SovereignAssistantTurnResult.PendingApproval): Result<ActionOutcome> = runCatching {
        val live = requireNotNull(actions.descriptorFor(pending.proposal.capability)) {
            "approved assistant capability has no live tool provider"
        }
        require(live.id == pending.toolId) {
            "approved assistant tool binding changed from ${pending.toolId.value} to ${live.id.value}"
        }
        require(live.sideEffect == pending.sideEffect) {
            "approved assistant side-effect class changed from ${pending.sideEffect} to ${live.sideEffect}"
        }

        if (pending.sideEffect == ToolSideEffect.READ_ONLY) {
            return@runCatching actions.approveBound(
                pending.proposal,
                pending.toolId,
                pending.sideEffect
            )
        }

        val planId = AssistantActionTransaction.planId(pending.proposal.requestId)
        require(ledger.receipt(planId, pending.proposal.requestId) == null) {
            "assistant action ${pending.proposal.requestId.value} is already finalized; replay denied"
        }
        require(store.load(planId) == null) {
            "assistant action ${pending.proposal.requestId.value} already has durable transaction state"
        }

        val pendingStep = SovereignPlanStep(
            index = 1,
            requestId = pending.proposal.requestId,
            capability = pending.proposal.capability,
            reason = pending.proposal.reason,
            input = pending.proposal.input,
            status = PlanStepStatus.REQUIRES_CONFIRMATION,
            outcome = ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = pending.proposal,
                toolId = pending.toolId,
                sideEffect = pending.sideEffect,
                detail = "${pending.sideEffect} action requires explicit approval"
            )
        )
        val transaction = SovereignPlan(
            id = planId,
            conversationId = pending.conversationId,
            goal = pending.userPrompt,
            steps = listOf(pendingStep),
            planningBackendId = "${AssistantActionTransaction.BACKEND_PREFIX}${pending.firstResponse.backendId}"
        )

        // Persist the recoverable pending snapshot before the claim. If the process
        // stops after claim creation, Recovery Console can resolve this exact plan.
        store.save(transaction)
        ledger.claimSideEffect(transaction, pendingStep).getOrThrow()

        val action = actions.approveBound(
            pending.proposal,
            pending.toolId,
            pending.sideEffect
        )
        require(action.status != ActionStatus.REQUIRES_CONFIRMATION && action.status != ActionStatus.NO_ACTION) {
            "approved assistant action did not reach a terminal governed outcome"
        }
        require(action.toolId == pending.toolId) {
            "executed assistant action returned a different tool binding"
        }
        require(action.sideEffect == pending.sideEffect) {
            "executed assistant action returned a different side-effect binding"
        }

        val terminalStep = pendingStep.copy(
            status = action.status.toPlanStatus(),
            outcome = action
        )
        val terminalPlan = transaction.copy(steps = listOf(terminalStep))
        ledger.recordTerminal(terminalPlan, terminalStep).getOrThrow()
        store.save(terminalPlan)
        action
    }

    private fun ActionStatus.toPlanStatus(): PlanStepStatus = when (this) {
        ActionStatus.EXECUTED -> PlanStepStatus.EXECUTED
        ActionStatus.DENIED -> PlanStepStatus.DENIED
        ActionStatus.FAILED -> PlanStepStatus.FAILED
        ActionStatus.MALFORMED -> PlanStepStatus.MALFORMED
        ActionStatus.UNAVAILABLE -> PlanStepStatus.UNAVAILABLE
        ActionStatus.REQUIRES_CONFIRMATION -> PlanStepStatus.REQUIRES_CONFIRMATION
        ActionStatus.NO_ACTION -> PlanStepStatus.MALFORMED
    }
}
