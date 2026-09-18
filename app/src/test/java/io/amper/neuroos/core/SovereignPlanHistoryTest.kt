package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignPlanHistoryTest {
    @Test
    fun recentPlansAreNewestFirstAndHideAssistantTransactions() {
        val runtime = AmperRuntime.reference()
        val history = SovereignPlanHistory(runtime.plans)
        val thread = ConversationId("history-thread")

        runtime.plans.save(plan("older-plan", thread, 100L, "older goal"))
        runtime.plans.save(
            plan(
                id = "assistant-action-plan",
                conversation = thread,
                createdAt = 300L,
                goal = "internal action transaction",
                backend = "${AssistantActionTransaction.BACKEND_PREFIX}backend"
            )
        )
        runtime.plans.save(plan("newer-plan", thread, 200L, "newer goal"))

        val entries = history.recent(limit = 8)

        assertEquals(listOf(PlanId("newer-plan"), PlanId("older-plan")), entries.map { it.planId })
        assertEquals("newer goal", entries.first().goal)
        assertEquals(1, entries.first().stepCount)
        assertEquals(1, entries.first().nextActiveStepIndex)
        assertFalse(entries.first().complete)
    }

    @Test
    fun conversationFilterAndOpenReloadPersistedStateWithoutOpeningTransactions() {
        val runtime = AmperRuntime.reference()
        val history = SovereignPlanHistory(runtime.plans)
        val threadA = ConversationId("history-a")
        val threadB = ConversationId("history-b")
        val planA = plan("plan-a", threadA, 100L, "goal a")
        val planB = plan("plan-b", threadB, 200L, "goal b")
        val transaction = plan(
            id = "transaction-b",
            conversation = threadB,
            createdAt = 300L,
            goal = "internal",
            backend = "${AssistantActionTransaction.BACKEND_PREFIX}backend"
        )
        runtime.plans.save(planA)
        runtime.plans.save(planB)
        runtime.plans.save(transaction)

        val entries = history.recent(limit = 4, conversationId = threadB)

        assertEquals(listOf(PlanId("plan-b")), entries.map { it.planId })
        assertEquals(planB, history.open(planB.id))
        assertNull(history.open(transaction.id))
    }

    @Test
    fun historySurfacesUnresolvedRecoveryStateWithoutMutatingLedger() {
        val runtime = AmperRuntime.reference()
        val ledger = requireNotNull(runtime.plans.receipts)
        val capability = CapabilityId("history.local")
        val proposal = ActionProposal(
            requestId = ActionRequestId("history-recovery-request"),
            capability = capability,
            reason = "Apply exact local history state",
            input = "apply"
        )
        val pending = SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.REQUIRES_CONFIRMATION,
            outcome = ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = ToolId("history-local-tool"),
                sideEffect = ToolSideEffect.LOCAL_STATE,
                detail = "LOCAL_STATE action requires explicit approval"
            ),
            boundToolId = ToolId("history-local-tool"),
            boundSideEffect = ToolSideEffect.LOCAL_STATE
        )
        val plan = SovereignPlan(
            id = PlanId("history-recovery-plan"),
            conversationId = ConversationId("history-recovery-thread"),
            goal = "recover interrupted state",
            steps = listOf(pending),
            planningBackendId = "planner",
            createdAtEpochMs = 400L
        )
        runtime.plans.save(plan)
        ledger.claimSideEffect(plan, pending).getOrThrow()

        val entry = SovereignPlanHistory(runtime.plans).recent(limit = 1).single()

        assertTrue(entry.recoveryRequired)
        assertEquals(1, entry.nextActiveStepIndex)
        assertEquals(plan.id, entry.planId)
        assertEquals(1, ledger.pendingClaims().size)
    }

    private fun plan(
        id: String,
        conversation: ConversationId,
        createdAt: Long,
        goal: String,
        backend: String = "planner"
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = conversation,
        goal = goal,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("request-$id"),
                capability = CapabilityId("history.read"),
                reason = "Read history state",
                input = "status",
                boundToolId = ToolId("history-read-tool"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = backend,
        createdAtEpochMs = createdAt,
        planningModelId = ModelId("history-model")
    )
}
