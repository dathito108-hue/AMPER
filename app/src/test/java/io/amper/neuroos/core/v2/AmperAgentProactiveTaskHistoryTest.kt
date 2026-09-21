package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ActionOutcome
import io.amper.neuroos.core.ActionRequestId
import io.amper.neuroos.core.ActionStatus
import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.ConversationId
import io.amper.neuroos.core.InMemoryMemoryOs
import io.amper.neuroos.core.MemoryBackedSovereignPlanReceiptLedger
import io.amper.neuroos.core.PlanAdvanceResult
import io.amper.neuroos.core.PlanDurabilityEvidence
import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignPlanStep
import io.amper.neuroos.core.ToolId
import io.amper.neuroos.core.ToolSideEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentProactiveTaskHistoryTest {
    @Test
    fun canonicalTerminalReceiptIsProjectedWithoutDuplicatingReceiptStorage() {
        val plan = completedPlan()
        val lifecycle = lifecycle(plan)
        val receiptMemory = InMemoryMemoryOs()
        val receiptLedger = MemoryBackedSovereignPlanReceiptLedger(receiptMemory)
        receiptLedger.recordTerminal(plan, plan.steps.single()).getOrThrow()
        val recordsBeforeProjection = receiptMemory.size()
        val history = AmperAgentProactiveTaskHistoryProjection(lifecycle, receiptLedger)

        val entry = history.recent(8).getOrThrow().single()
        assertEquals(recordsBeforeProjection, receiptMemory.size())
        val receipt = entry.receipts.single()

        assertEquals(AmperAgentTaskState.COMPLETED, entry.lifecycle.taskState)
        assertTrue(entry.receiptLedgerAvailable)
        assertFalse(entry.recoveryRequired)
        assertEquals(1, entry.durableTerminalEvidenceSteps)
        assertEquals(0, entry.unresolvedClaimSteps)
        assertEquals(PlanDurabilityEvidence.RECEIPTED, receipt.durabilityEvidence)
        assertEquals(PlanStepStatus.EXECUTED, receipt.stepStatus)
        assertEquals(ActionStatus.EXECUTED, receipt.actionStatus)
        assertNotNull(receipt.receiptSha256)
        assertEquals(null, receipt.recoveryTarget)
    }

    @Test
    fun unresolvedCanonicalClaimDrivesRecoveryPresentation() {
        val plan = waitingPlan()
        val lifecycle = lifecycle(plan)
        val receiptLedger = MemoryBackedSovereignPlanReceiptLedger(InMemoryMemoryOs())
        receiptLedger.claimSideEffect(plan, plan.steps.single()).getOrThrow()
        val history = AmperAgentProactiveTaskHistoryProjection(lifecycle, receiptLedger)

        val entry = history.recent(8).getOrThrow().single()

        assertEquals(AmperAgentTaskState.WAITING_APPROVAL, entry.lifecycle.taskState)
        assertTrue(entry.recoveryRequired)
        assertEquals(1, entry.unresolvedClaimSteps)
        assertEquals(
            PlanDurabilityEvidence.CLAIMED_UNRESOLVED,
            entry.receipts.single().durabilityEvidence
        )
        val target = requireNotNull(entry.receipts.single().recoveryTarget)
        assertEquals(plan.id, target.planId)
        assertEquals(plan.steps.single().index, target.stepIndex)
        assertEquals(plan.steps.single().requestId, target.requestId)
    }

    @Test
    fun missingCanonicalPlanStaysVisibleWithoutSyntheticReceipts() {
        val lifecycle = lifecycle(null)
        val history = AmperAgentProactiveTaskHistoryProjection(
            lifecycle,
            MemoryBackedSovereignPlanReceiptLedger(InMemoryMemoryOs())
        )

        val entry = history.recent(8).getOrThrow().single()

        assertFalse(entry.lifecycle.planAvailable)
        assertTrue(entry.receiptLedgerAvailable)
        assertFalse(entry.recoveryRequired)
        assertTrue(entry.receipts.isEmpty())
    }

    @Test
    fun projectionIsBoundedAndReceiptViewExposesNoReasonInputOrOutput() {
        val lifecycle = lifecycle(completedPlan())
        val history = AmperAgentProactiveTaskHistoryProjection(lifecycle, null)

        assertTrue(history.recent(65).isFailure)
        assertTrue(history.recent(0).getOrThrow().isEmpty())

        val fields = AmperAgentProactiveTaskReceiptView::class.java.declaredFields
            .mapTo(linkedSetOf()) { it.name }
        assertFalse("reason" in fields)
        assertFalse("input" in fields)
        assertFalse("output" in fields)
        assertFalse("goal" in fields)
    }

    private fun lifecycle(
        plan: SovereignPlan?
    ): AmperAgentProactiveTaskLifecycleCoordinator {
        val ledger = MemoryBackedAmperAgentProactiveTaskLifecycleLedger(InMemoryMemoryOs())
        ledger.record(binding()).getOrThrow()
        val port = FakePlanPort(plan)
        return AmperAgentProactiveTaskLifecycleCoordinator(
            ledger = ledger,
            plans = port,
            admissions = AmperAgentTaskAdmissionRegistry(),
            proactive = AmperAgentProactiveTaskCoordinator(port),
            eventWake = AmperAgentProactiveEventWakeCoordinator(port)
        )
    }

    private fun binding(): AmperAgentProactiveTaskLifecycleBinding {
        val identity = "a".repeat(64)
        val sourceId = "monitor.example"
        return AmperAgentProactiveTaskLifecycleBinding(
            sourceId = sourceId,
            configurationSha256 = "c".repeat(64),
            observationIdentitySha256 = identity,
            taskId = "proactive:" + identity.take(48),
            planId = AmperAgentPendingTriggerDispatchCoordinator.deterministicPlanId(identity),
            trigger = AmperAgentTrigger(
                triggerId = sourceId,
                source = "user-configured:app_local_event:1234567890abcdef:cdef0123456789ab",
                observedAtEpochMs = 10L,
                payloadDigest = "b".repeat(64)
            ),
            boundAtEpochMs = 10L
        )
    }

    private fun completedPlan(): SovereignPlan {
        val proposal = io.amper.neuroos.core.ActionProposal(
            requestId = ActionRequestId("history-r1"),
            capability = CapabilityId("test.history.read"),
            reason = "Read canonical state",
            input = "status"
        )
        val step = SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = proposal.capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.EXECUTED,
            outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = proposal,
                toolId = ToolId("history-read-tool"),
                sideEffect = ToolSideEffect.READ_ONLY,
                output = "ok"
            ),
            boundToolId = ToolId("history-read-tool"),
            boundSideEffect = ToolSideEffect.READ_ONLY
        )
        return SovereignPlan(
            id = binding().planId,
            conversationId = ConversationId("primary"),
            goal = "Handle one bounded proactive event.",
            steps = listOf(step),
            planningBackendId = "amper-core",
            createdAtEpochMs = 10L
        )
    }

    private fun waitingPlan(): SovereignPlan {
        val proposal = io.amper.neuroos.core.ActionProposal(
            requestId = ActionRequestId("history-r1"),
            capability = CapabilityId("test.history.external"),
            reason = "Apply governed external change",
            input = "apply"
        )
        val step = SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = proposal.capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.REQUIRES_CONFIRMATION,
            outcome = ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = ToolId("history-external-tool"),
                sideEffect = ToolSideEffect.EXTERNAL,
                detail = "EXTERNAL action requires explicit approval"
            ),
            boundToolId = ToolId("history-external-tool"),
            boundSideEffect = ToolSideEffect.EXTERNAL
        )
        return SovereignPlan(
            id = binding().planId,
            conversationId = ConversationId("primary"),
            goal = "Handle one bounded proactive event.",
            steps = listOf(step),
            planningBackendId = "amper-core",
            createdAtEpochMs = 10L
        )
    }

    private class FakePlanPort(
        var current: SovereignPlan?
    ) : AmperAgentPersistentPlanPort {
        override fun create(
            conversationId: ConversationId,
            userGoal: String
        ): Result<SovereignPlan> =
            Result.failure(UnsupportedOperationException("not used"))

        override fun load(planId: PlanId): SovereignPlan? =
            current?.takeIf { it.id == planId }

        override fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> =
            Result.failure(UnsupportedOperationException("not used"))
    }
}
