package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignPlanCancellationTest {
    @Test
    fun cancellationRejectsAllRemainingStepsPersistsThemAndCreatesTerminalReceipts() {
        val runtime = AmperRuntime.reference()
        val ledger = requireNotNull(runtime.plans.receipts)
        val cancellation = SovereignPlanCancellation(runtime.plans)
        val completed = terminalReadStep(1, "cancel-completed-request")
        val planned2 = plannedReadStep(2, "cancel-planned-2")
        val planned3 = plannedReadStep(3, "cancel-planned-3")
        val initial = SovereignPlan(
            id = PlanId("cancel-plan"),
            conversationId = ConversationId("cancel-thread"),
            goal = "stop remaining governed work",
            steps = listOf(completed, planned2, planned3),
            planningBackendId = "cancel-planner"
        )
        ledger.recordTerminal(initial, completed).getOrThrow()
        runtime.plans.save(initial)

        val result = cancellation.cancelRemaining(initial).getOrThrow()
        val restored = runtime.plans.load(initial.id)!!

        assertEquals(listOf(2, 3), result.cancelledStepIndices)
        assertEquals(PlanStepStatus.EXECUTED, restored.steps[0].status)
        assertEquals(PlanStepStatus.REJECTED, restored.steps[1].status)
        assertEquals(PlanStepStatus.REJECTED, restored.steps[2].status)
        assertTrue(restored.complete)
        assertNotNull(ledger.receipt(initial.id, planned2.requestId))
        assertNotNull(ledger.receipt(initial.id, planned3.requestId))
        assertNull(ledger.claim(initial.id, planned2.requestId))
        assertNull(ledger.claim(initial.id, planned3.requestId))
    }

    @Test
    fun pendingSideEffectWithoutClaimCanBeCancelledWithoutCreatingClaim() {
        val runtime = AmperRuntime.reference()
        val ledger = requireNotNull(runtime.plans.receipts)
        val cancellation = SovereignPlanCancellation(runtime.plans)
        val pending = pendingLocalStep("cancel-pending-request")
        val plan = SovereignPlan(
            id = PlanId("cancel-pending-plan"),
            conversationId = ConversationId("cancel-pending-thread"),
            goal = "cancel before approval",
            steps = listOf(pending),
            planningBackendId = "cancel-planner"
        )
        runtime.plans.save(plan)

        val result = cancellation.cancelRemaining(plan).getOrThrow()
        val step = result.plan.steps.single()

        assertEquals(PlanStepStatus.REJECTED, step.status)
        assertTrue(result.plan.complete)
        assertNull(ledger.claim(plan.id, pending.requestId))
        val receipt = ledger.receipt(plan.id, pending.requestId)
        assertNotNull(receipt)
        assertEquals(PlanStepStatus.REJECTED, receipt?.stepStatus)
        assertEquals(ActionStatus.REQUIRES_CONFIRMATION, receipt?.actionStatus)
    }

    @Test
    fun unresolvedDurableClaimBlocksCancellationAndLeavesSnapshotActive() {
        val runtime = AmperRuntime.reference()
        val ledger = requireNotNull(runtime.plans.receipts)
        val cancellation = SovereignPlanCancellation(runtime.plans)
        val pending = pendingLocalStep("cancel-claimed-request")
        val plan = SovereignPlan(
            id = PlanId("cancel-claimed-plan"),
            conversationId = ConversationId("cancel-claimed-thread"),
            goal = "claimed work must recover first",
            steps = listOf(pending),
            planningBackendId = "cancel-planner"
        )
        runtime.plans.save(plan)
        ledger.claimSideEffect(plan, pending).getOrThrow()

        val result = cancellation.cancelRemaining(plan)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Recovery Console"))
        assertEquals(PlanStepStatus.REQUIRES_CONFIRMATION, runtime.plans.load(plan.id)?.steps?.single()?.status)
        assertNull(ledger.receipt(plan.id, pending.requestId))
        assertNotNull(ledger.claim(plan.id, pending.requestId))
    }

    @Test
    fun reconciledClaimWithStaleActiveSnapshotStillBlocksCancellation() {
        val runtime = AmperRuntime.reference()
        val ledger = requireNotNull(runtime.plans.receipts)
        val cancellation = SovereignPlanCancellation(runtime.plans)
        val pending = pendingLocalStep("cancel-reconciled-request")
        val plan = SovereignPlan(
            id = PlanId("cancel-reconciled-plan"),
            conversationId = ConversationId("cancel-reconciled-thread"),
            goal = "do not overwrite reconciliation",
            steps = listOf(pending),
            planningBackendId = "cancel-planner"
        )
        runtime.plans.save(plan)
        ledger.claimSideEffect(plan, pending).getOrThrow()
        ledger.reconcileClaim(
            plan,
            pending,
            PlanClaimDecision.CONFIRMED_NOT_EXECUTED,
            "Verified no local state change occurred"
        ).getOrThrow()

        val result = cancellation.cancelRemaining(plan)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("terminal plan state must be recovered"))
        assertEquals(PlanStepStatus.REQUIRES_CONFIRMATION, runtime.plans.load(plan.id)?.steps?.single()?.status)
        assertNotNull(ledger.reconciliation(plan.id, pending.requestId))
    }

    @Test
    fun cancellationIsIdempotentForAlreadyCompletedPlan() {
        val runtime = AmperRuntime.reference()
        val ledger = requireNotNull(runtime.plans.receipts)
        val cancellation = SovereignPlanCancellation(runtime.plans)
        val terminal = terminalReadStep(1, "cancel-idempotent-request")
        val plan = SovereignPlan(
            id = PlanId("cancel-idempotent-plan"),
            conversationId = ConversationId("cancel-idempotent-thread"),
            goal = "already complete",
            steps = listOf(terminal),
            planningBackendId = "cancel-planner"
        )
        ledger.recordTerminal(plan, terminal).getOrThrow()
        runtime.plans.save(plan)

        val result = cancellation.cancelRemaining(plan).getOrThrow()

        assertTrue(result.cancelledStepIndices.isEmpty())
        assertEquals(plan, result.plan)
        assertTrue(result.plan.complete)
    }

    @Test
    fun legacyUnboundPlannedStepCanBeCancelledWithoutInventingProviderBinding() {
        val runtime = AmperRuntime.reference()
        val cancellation = SovereignPlanCancellation(runtime.plans)
        val legacy = SovereignPlan(
            id = PlanId("cancel-legacy-plan"),
            conversationId = ConversationId("cancel-legacy-thread"),
            goal = "cancel legacy plan safely",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId("cancel-legacy-request"),
                    capability = CapabilityId("legacy.read"),
                    reason = "Legacy read",
                    input = "status"
                )
            ),
            planningBackendId = "legacy-planner"
        )
        runtime.plans.save(legacy)

        val result = cancellation.cancelRemaining(legacy).getOrThrow()
        val step = result.plan.steps.single()

        assertEquals(PlanStepStatus.REJECTED, step.status)
        assertNull(step.boundToolId)
        assertNull(step.boundSideEffect)
        assertFalse(result.cancelledStepIndices.isEmpty())
    }

    private fun plannedReadStep(index: Int, requestId: String): SovereignPlanStep = SovereignPlanStep(
        index = index,
        requestId = ActionRequestId(requestId),
        capability = CapabilityId("cancel.read"),
        reason = "Read governed status",
        input = "status",
        boundToolId = ToolId("cancel-read-tool"),
        boundSideEffect = ToolSideEffect.READ_ONLY
    )

    private fun terminalReadStep(index: Int, requestId: String): SovereignPlanStep {
        val proposal = ActionProposal(
            requestId = ActionRequestId(requestId),
            capability = CapabilityId("cancel.read"),
            reason = "Read completed status",
            input = "status"
        )
        return SovereignPlanStep(
            index = index,
            requestId = proposal.requestId,
            capability = proposal.capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.EXECUTED,
            outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = proposal,
                toolId = ToolId("cancel-read-tool"),
                sideEffect = ToolSideEffect.READ_ONLY,
                output = "ok"
            ),
            boundToolId = ToolId("cancel-read-tool"),
            boundSideEffect = ToolSideEffect.READ_ONLY
        )
    }

    private fun pendingLocalStep(requestId: String): SovereignPlanStep {
        val proposal = ActionProposal(
            requestId = ActionRequestId(requestId),
            capability = CapabilityId("cancel.local"),
            reason = "Apply governed local state",
            input = "apply"
        )
        return SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = proposal.capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.REQUIRES_CONFIRMATION,
            outcome = ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = ToolId("cancel-local-tool"),
                sideEffect = ToolSideEffect.LOCAL_STATE,
                detail = "LOCAL_STATE action requires explicit approval"
            ),
            boundToolId = ToolId("cancel-local-tool"),
            boundSideEffect = ToolSideEffect.LOCAL_STATE
        )
    }
}
