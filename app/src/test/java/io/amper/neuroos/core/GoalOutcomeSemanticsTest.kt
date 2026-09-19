package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalOutcomeSemanticsTest {
    private val capability = CapabilityId("phase391.outcome")

    @Test
    fun allExecutedWaitsForVerificationAndSupportsBothVerifiedTerminalOutcomes() {
        val plan = plan("all-executed", listOf(PlanStepStatus.EXECUTED))
        assertNull(GoalOutcomeSemantics.classifyPreVerification(plan))
        GoalOutcomeSemantics.validate(plan, GoalOutcomeEvidenceKind.VERIFIED_SUCCESS)
        GoalOutcomeSemantics.validate(plan, GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED)
    }

    @Test
    fun malformedAndUnavailableShareCanonicalExecutionExhaustionSemantics() {
        val malformed = plan("malformed", listOf(PlanStepStatus.MALFORMED))
        val unavailable = plan("unavailable", listOf(PlanStepStatus.UNAVAILABLE))
        assertEquals(
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
            GoalOutcomeSemantics.classifyPreVerification(malformed)
        )
        assertEquals(
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
            GoalOutcomeSemantics.classifyPreVerification(unavailable)
        )
        GoalOutcomeSemantics.validate(malformed, GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED)
        GoalOutcomeSemantics.validate(unavailable, GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED)
    }

    @Test
    fun partialExecutionTakesPrecedenceOverAuthorityBlock() {
        val plan = plan(
            "partial-authority",
            listOf(PlanStepStatus.EXECUTED, PlanStepStatus.DENIED)
        )
        assertEquals(
            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED,
            GoalOutcomeSemantics.classifyPreVerification(plan)
        )
        GoalOutcomeSemantics.validate(plan, GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED)
    }

    @Test(expected = IllegalArgumentException::class)
    fun authorityOutcomeCannotBeAppliedToExecutedPlan() {
        GoalOutcomeSemantics.validate(
            plan("invalid-authority", listOf(PlanStepStatus.EXECUTED)),
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED
        )
    }

    private fun plan(id: String, statuses: List<PlanStepStatus>): SovereignPlan =
        SovereignPlan(
            id = PlanId(id),
            conversationId = ConversationId("phase391-conversation"),
            goal = "canonical governed outcome semantics",
            steps = statuses.mapIndexed { index, status ->
                SovereignPlanStep(
                    index = index + 1,
                    requestId = ActionRequestId(id + "-" + index),
                    capability = capability,
                    reason = "canonical outcome test",
                    input = "run",
                    status = status,
                    boundToolId = ToolId("phase391-tool"),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            },
            planningBackendId = "phase391-test"
        )
}
