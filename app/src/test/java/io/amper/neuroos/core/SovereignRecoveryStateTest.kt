package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignRecoveryStateTest {
    @Test
    fun terminalReceiptRemovesClaimFromUnresolvedRecoveryState() {
        val runtime = AmperRuntime.reference()
        val ledger = runtime.plans.receipts!!
        val capability = CapabilityId("test.recovery.state")
        val toolId = ToolId("recovery-state-tool")
        val proposal = ActionProposal(
            requestId = ActionRequestId("recovery-state-r1"),
            capability = capability,
            reason = "Apply once",
            input = "apply"
        )
        val pendingStep = SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.REQUIRES_CONFIRMATION,
            outcome = ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = toolId,
                detail = "LOCAL_STATE action requires explicit approval"
            )
        )
        val plan = SovereignPlan(
            id = PlanId("recovery-state-plan"),
            conversationId = ConversationId("recovery-state-thread"),
            goal = "verify resolved claim classification",
            steps = listOf(pendingStep),
            planningBackendId = "planner"
        )
        val state = SovereignRecoveryState(ledger)

        ledger.claimSideEffect(plan, pendingStep).getOrThrow()
        assertEquals(1, state.unresolvedClaims().size)
        assertTrue(state.hasUnresolvedClaims())

        val executedStep = pendingStep.copy(
            status = PlanStepStatus.EXECUTED,
            outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = proposal,
                toolId = toolId,
                output = "applied"
            )
        )
        val executedPlan = plan.copy(steps = listOf(executedStep))
        ledger.recordTerminal(executedPlan, executedStep).getOrThrow()

        assertTrue(state.unresolvedClaims().isEmpty())
        assertTrue(!state.hasUnresolvedClaims())
    }
}
