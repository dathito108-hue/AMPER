package io.amper.neuroos.core.v2

import io.amper.neuroos.core.InMemoryMemoryOs
import io.amper.neuroos.core.MemoryRecord
import io.amper.neuroos.core.Provenance
import io.amper.neuroos.core.PlanId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentProactiveAttentionTest {
    @Test
    fun waitingApprovalBecomesActionableAttention() {
        val decision = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.WAITING_APPROVAL,
                completedSteps = 1,
                totalSteps = 2,
                waitingStep = 2
            )
        )

        assertEquals(
            AmperAgentProactiveAttentionKind.APPROVAL_REQUIRED,
            decision.kind
        )
        assertEquals(2, decision.waitingApprovalStepIndex)
        assertTrue(decision.actionable)
    }

    @Test
    fun checkpointedWorkNeedsNoUserAttention() {
        val decision = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.CHECKPOINTED,
                completedSteps = 1,
                totalSteps = 2
            )
        )

        assertEquals(AmperAgentProactiveAttentionKind.NONE, decision.kind)
        assertFalse(decision.actionable)
    }

    @Test
    fun terminalStatesMapToCompletionOrReview() {
        val completed = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.COMPLETED,
                completedSteps = 2,
                totalSteps = 2
            )
        )
        val failed = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.FAILED,
                completedSteps = 2,
                totalSteps = 2
            )
        )
        val cancelled = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.CANCELLED,
                completedSteps = 1,
                totalSteps = 2
            )
        )

        assertEquals(AmperAgentProactiveAttentionKind.COMPLETED, completed.kind)
        assertEquals(AmperAgentProactiveAttentionKind.FAILED, failed.kind)
        assertEquals(AmperAgentProactiveAttentionKind.FAILED, cancelled.kind)
        assertFalse(completed.actionable)
        assertFalse(failed.actionable)
    }

    @Test
    fun missingCanonicalPlanIsFailClosedReviewAttention() {
        val decision = AmperAgentProactiveAttentionPolicy.decide(
            AmperAgentProactiveTaskLifecycleView(
                binding = binding(),
                taskState = null,
                completedSteps = 0,
                totalSteps = 0,
                waitingApprovalStepIndex = null,
                goal = null,
                planAvailable = false
            )
        )

        assertEquals(
            AmperAgentProactiveAttentionKind.PLAN_UNAVAILABLE,
            decision.kind
        )
        assertTrue(decision.actionable)
        assertEquals(null, decision.goal)
    }

    @Test
    fun attentionRevisionChangesOnlyForCanonicalAttentionIdentityInputs() {
        val first = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.WAITING_APPROVAL,
                completedSteps = 0,
                totalSteps = 2,
                waitingStep = 1
            )
        )
        val same = first.copy(
            sourceId = "different-source",
            goal = "Different sensitive goal text"
        )
        val nextStep = first.copy(waitingApprovalStepIndex = 2)
        val terminal = first.copy(
            kind = AmperAgentProactiveAttentionKind.COMPLETED,
            waitingApprovalStepIndex = null
        )
        val laterObservation = first.copy(observedAtEpochMs = first.observedAtEpochMs + 1)

        assertEquals(
            AmperAgentProactiveAttentionRevision.sha256(first),
            AmperAgentProactiveAttentionRevision.sha256(same)
        )
        assertTrue(
            AmperAgentProactiveAttentionRevision.sha256(first) !=
                AmperAgentProactiveAttentionRevision.sha256(nextStep)
        )
        assertTrue(
            AmperAgentProactiveAttentionRevision.sha256(first) !=
                AmperAgentProactiveAttentionRevision.sha256(terminal)
        )
        assertTrue(
            AmperAgentProactiveAttentionRevision.sha256(first) !=
                AmperAgentProactiveAttentionRevision.sha256(laterObservation)
        )
    }

    @Test
    fun staleRevisionCannotAcknowledgeNewCanonicalAttentionState() {
        val approval = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.WAITING_APPROVAL,
                completedSteps = 0,
                totalSteps = 2,
                waitingStep = 1
            )
        )
        val revision = AmperAgentProactiveAttentionRevision.sha256(approval)
        val completed = approval.copy(
            kind = AmperAgentProactiveAttentionKind.COMPLETED,
            waitingApprovalStepIndex = null
        )

        assertTrue(
            AmperAgentProactiveAttentionAcknowledgementPolicy.matchesCurrentRevision(
                approval,
                revision
            )
        )
        assertFalse(
            AmperAgentProactiveAttentionAcknowledgementPolicy.matchesCurrentRevision(
                completed,
                revision
            )
        )
        assertFalse(
            AmperAgentProactiveAttentionAcknowledgementPolicy.matchesCurrentRevision(
                approval,
                revision.uppercase()
            )
        )
    }

    @Test
    fun durableAcknowledgementIsIdempotentAndRevisionScoped() {
        val memory = InMemoryMemoryOs()
        val ledger = MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger(memory)
        val approval = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.WAITING_APPROVAL,
                completedSteps = 0,
                totalSteps = 2,
                waitingStep = 1
            )
        )
        val completed = approval.copy(
            kind = AmperAgentProactiveAttentionKind.COMPLETED,
            waitingApprovalStepIndex = null
        )

        val first = ledger.acknowledge(approval, acknowledgedAtEpochMs = 20L).getOrThrow()
        val replay = ledger.acknowledge(approval, acknowledgedAtEpochMs = 30L).getOrThrow()

        assertEquals(first, replay)
        assertTrue(ledger.isAcknowledged(approval).getOrThrow())
        assertFalse(ledger.isAcknowledged(completed).getOrThrow())

        ledger.acknowledge(completed, acknowledgedAtEpochMs = 40L).getOrThrow()
        assertFalse(ledger.isAcknowledged(approval).getOrThrow())
        assertTrue(ledger.isAcknowledged(completed).getOrThrow())
        assertEquals(1, ledger.recent(8).getOrThrow().size)
    }

    @Test
    fun acknowledgementLedgerPrunesOnlyUntrackedPlanIds() {
        val memory = InMemoryMemoryOs()
        val ledger = MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger(memory)
        val first = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.WAITING_APPROVAL,
                completedSteps = 0,
                totalSteps = 2,
                waitingStep = 1
            )
        )
        val secondPlan = PlanId("agent-trigger-plan:" + "d".repeat(64))
        val second = first.copy(planId = secondPlan)

        ledger.acknowledge(first, acknowledgedAtEpochMs = 20L).getOrThrow()
        ledger.acknowledge(second, acknowledgedAtEpochMs = 21L).getOrThrow()

        assertEquals(
            1,
            ledger.retainPlanIds(setOf(first.planId)).getOrThrow()
        )
        assertTrue(ledger.isAcknowledged(first).getOrThrow())
        assertFalse(ledger.isAcknowledged(second).getOrThrow())
    }

    @Test
    fun acknowledgementLedgerIsBoundedWithoutSilentEviction() {
        val memory = InMemoryMemoryOs()
        val ledger = MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger(memory)
        val template = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.WAITING_APPROVAL,
                completedSteps = 0,
                totalSteps = 2,
                waitingStep = 1
            )
        )
        repeat(MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger.MAX_ACKNOWLEDGEMENTS) {
            index ->
            val planId = PlanId(
                "agent-trigger-plan:" + index.toString(16).padStart(64, '0')
            )
            ledger.acknowledge(
                template.copy(planId = planId),
                acknowledgedAtEpochMs = index.toLong()
            ).getOrThrow()
        }
        val overflow = template.copy(
            planId = PlanId("agent-trigger-plan:" + "f".repeat(64))
        )

        assertTrue(ledger.acknowledge(overflow, 100L).isFailure)
        assertEquals(
            MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger.MAX_ACKNOWLEDGEMENTS,
            ledger.recent(128).getOrThrow().size
        )
        assertTrue(
            ledger.isAcknowledged(
                template.copy(
                    planId = PlanId("agent-trigger-plan:" + "0".repeat(64))
                )
            ).getOrThrow()
        )
    }

    @Test
    fun corruptedAcknowledgementLedgerFailsVisibleInsteadOfSuppressingAttention() {
        val memory = InMemoryMemoryOs()
        memory.remember(
            MemoryRecord(
                id = MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger.RECORD_ID,
                kind = MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger.KIND,
                content = "corrupt",
                importance = 0.72,
                provenance = Provenance(
                    source = "test",
                    producer = "test",
                    observedAtEpochMs = 1L,
                    confidence = 1.0
                ),
                createdAtEpochMs = 1L
            )
        )
        val ledger = MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger(memory)
        val approval = AmperAgentProactiveAttentionPolicy.decide(
            view(
                state = AmperAgentTaskState.WAITING_APPROVAL,
                completedSteps = 0,
                totalSteps = 2,
                waitingStep = 1
            )
        )

        assertTrue(ledger.isAcknowledged(approval).isFailure)
        assertFalse(ledger.isAcknowledged(approval).getOrDefault(false))
    }

    private fun view(
        state: AmperAgentTaskState,
        completedSteps: Int,
        totalSteps: Int,
        waitingStep: Int? = null
    ): AmperAgentProactiveTaskLifecycleView =
        AmperAgentProactiveTaskLifecycleView(
            binding = binding(),
            taskState = state,
            completedSteps = completedSteps,
            totalSteps = totalSteps,
            waitingApprovalStepIndex = waitingStep,
            goal = "Handle one bounded proactive event.",
            planAvailable = true
        )

    private fun binding(): AmperAgentProactiveTaskLifecycleBinding {
        val identity = "a".repeat(64)
        val sourceId = "monitor.example"
        return AmperAgentProactiveTaskLifecycleBinding(
            sourceId = sourceId,
            configurationSha256 = "c".repeat(64),
            observationIdentitySha256 = identity,
            taskId = "proactive:" + identity.take(48),
            planId = AmperAgentPendingTriggerDispatchCoordinator
                .deterministicPlanId(identity),
            trigger = AmperAgentTrigger(
                triggerId = sourceId,
                source =
                    "user-configured:app_local_event:1234567890abcdef:cdef0123456789ab",
                observedAtEpochMs = 10L,
                payloadDigest = "b".repeat(64)
            ),
            boundAtEpochMs = 10L
        )
    }
}
