package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ActionRequestId
import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.ConversationId
import io.amper.neuroos.core.PlanAdvanceResult
import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignPlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentExecutionContinuationTest {
    @Test
    fun foregroundContinuationRestoresExactCheckpointWithoutReplay() {
        val port = FakePlanPort(plan())
        val continuation = AmperAgentExecutionContinuationCoordinator(port) { 200L }
        val admission = admission(
            mustSurviveUiExit = true,
            canBeDeferred = false
        )
        val passive = checkpoint(
            admission = admission,
            state = AmperAgentTaskState.READY,
            plan = port.current
        )

        val envelope = continuation.checkpoint(admission, passive).getOrThrow()
        val restored = continuation.restore(admission, envelope).getOrThrow()

        assertEquals(OmegaBackgroundMode.FOREGROUND_CONTINUATION, envelope.backgroundMode)
        assertEquals(AmperAgentTaskState.CHECKPOINTED, envelope.taskState)
        assertEquals(AmperAgentTaskState.CHECKPOINTED, restored.checkpoint.taskState)
        assertFalse(restored.replayRequired)
        assertEquals(0, port.advanceCalls)
    }

    @Test
    fun persistedJobCheckpointRoundTripsThroughCodec() {
        val port = FakePlanPort(plan())
        val continuation = AmperAgentExecutionContinuationCoordinator(port) { 300L }
        val admission = admission(
            mustSurviveUiExit = true,
            canBeDeferred = true
        )
        val passive = checkpoint(admission, AmperAgentTaskState.READY, port.current)

        val envelope = continuation.checkpoint(admission, passive).getOrThrow()
        val encoded = AmperAgentContinuationEnvelopeCodec.encode(envelope)
        val decoded = AmperAgentContinuationEnvelopeCodec.decode(encoded).getOrThrow()

        assertEquals(envelope, decoded)
        assertEquals(OmegaBackgroundMode.PERSISTED_JOB, decoded.backgroundMode)
        assertEquals(64, decoded.planStateSha256.length)
    }

    @Test
    fun waitingApprovalRemainsBlockedAcrossProcessRestore() {
        val waiting = plan().copy(
            steps = listOf(
                plan().steps.single().copy(
                    status = PlanStepStatus.REQUIRES_CONFIRMATION
                )
            )
        )
        val port = FakePlanPort(waiting)
        val continuation = AmperAgentExecutionContinuationCoordinator(port)
        val admission = admission(mustSurviveUiExit = true, canBeDeferred = false)
        val passive = checkpoint(
            admission,
            AmperAgentTaskState.WAITING_APPROVAL,
            waiting
        )

        val envelope = continuation.checkpoint(admission, passive).getOrThrow()
        val restored = continuation.restore(admission, envelope).getOrThrow()

        assertEquals(AmperAgentTaskState.WAITING_APPROVAL, restored.checkpoint.taskState)
        assertEquals(1, envelope.waitingApprovalStepIndex)
        assertEquals(0, port.advanceCalls)
    }

    @Test
    fun staleEnvelopeFailsIfDurablePlanChangedAfterCheckpoint() {
        val port = FakePlanPort(plan())
        val continuation = AmperAgentExecutionContinuationCoordinator(port)
        val admission = admission(mustSurviveUiExit = true, canBeDeferred = false)
        val passive = checkpoint(admission, AmperAgentTaskState.READY, port.current)
        val envelope = continuation.checkpoint(admission, passive).getOrThrow()

        port.current = port.current.copy(
            steps = listOf(
                port.current.steps.single().copy(
                    reason = "changed durable plan state"
                )
            )
        )

        val restored = continuation.restore(admission, envelope)

        assertTrue(restored.isFailure)
        assertEquals(0, port.advanceCalls)
    }

    @Test
    fun uiBoundTaskCannotCreateContinuationEnvelope() {
        val port = FakePlanPort(plan())
        val continuation = AmperAgentExecutionContinuationCoordinator(port)
        val admission = admission(mustSurviveUiExit = false, canBeDeferred = false)
        val passive = checkpoint(admission, AmperAgentTaskState.READY, port.current)

        val result = continuation.checkpoint(admission, passive)

        assertTrue(result.isFailure)
    }

    @Test
    fun passiveCoordinatorRejectsPlanOutsideTaskCapabilityEnvelope() {
        val escaped = plan(capability = CapabilityId("internet.read"))
        val port = FakePlanPort(escaped)
        val coordinator = AmperAgentPassiveTaskCoordinator(port)
        val admission = admission(mustSurviveUiExit = false, canBeDeferred = false)

        val result = coordinator.start(
            admission,
            ConversationId("conversation-1")
        )

        assertTrue(result.isFailure)
        assertEquals(0, port.advanceCalls)
    }

    private fun admission(
        mustSurviveUiExit: Boolean,
        canBeDeferred: Boolean
    ): AmperAgentTaskAdmission = AmperAgentTaskAdmissionPolicy.admit(
        AmperAgentTaskRequest(
            taskId = "user-task",
            origin = AmperAgentTaskOrigin.USER_REQUEST,
            objective = "Perform one governed passive task.",
            allowedCapabilities = setOf(CapabilityId("reasoning")),
            expectedRuntimeMs = 30_000L,
            mustSurviveUiExit = mustSurviveUiExit,
            canBeDeferred = canBeDeferred,
            createdAtEpochMs = 1L
        )
    )

    private fun checkpoint(
        admission: AmperAgentTaskAdmission,
        state: AmperAgentTaskState,
        plan: SovereignPlan
    ) = AmperAgentPassiveTaskCheckpoint(
        taskId = admission.request.taskId,
        planId = plan.id,
        backgroundMode = admission.backgroundMode,
        taskState = state,
        completedSteps = 0,
        totalSteps = plan.steps.size,
        updatedAtEpochMs = 10L
    )

    private fun plan(
        capability: CapabilityId = CapabilityId("reasoning")
    ) = SovereignPlan(
        id = PlanId("plan-1"),
        conversationId = ConversationId("conversation-1"),
        goal = "Perform one governed passive task.",
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("request-1"),
                capability = capability,
                reason = "test",
                input = "test"
            )
        ),
        planningBackendId = "amper-core",
        createdAtEpochMs = 1L
    )

    private class FakePlanPort(
        var current: SovereignPlan
    ) : AmperAgentPersistentPlanPort {
        var advanceCalls = 0

        override fun create(
            conversationId: ConversationId,
            userGoal: String
        ): Result<SovereignPlan> = Result.success(current)

        override fun load(planId: PlanId): SovereignPlan? =
            current.takeIf { it.id == planId }

        override fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> {
            advanceCalls += 1
            return Result.success(PlanAdvanceResult.Complete(plan))
        }
    }
}
