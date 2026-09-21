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

class AmperAgentProactiveEventWakeTest {
    @Test
    fun eventWakeRoundTripBindsTriggerAndExactDurablePlan() {
        val port = FakePlanPort(plan())
        val admission = admission()
        val proactive = AmperAgentProactiveTaskCoordinator(port) { 100L }
        val started = proactive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val wake = AmperAgentProactiveEventWakeCoordinator(port) { 200L }

        val handoff = wake.handoff(admission, started.checkpoint).getOrThrow()
        val decoded = AmperAgentEventWakeHandoffPolicy.verifyAndDecode(handoff).getOrThrow()
        val restored = wake.restore(admission, decoded).getOrThrow()

        assertEquals("monitor.example", decoded.trigger.triggerId)
        assertEquals("canonical-monitor", decoded.trigger.source)
        assertEquals("a".repeat(64), decoded.trigger.payloadDigest)
        assertEquals(AmperAgentTaskState.CHECKPOINTED, decoded.taskState)
        assertEquals(AmperAgentEventWakeDisposition.READY_FOR_EXPLICIT_ADVANCE, handoff.disposition)
        assertTrue(handoff.runnable)
        assertTrue(restored.runnable)
        assertEquals(0, port.advanceCalls)
    }

    @Test
    fun waitingApprovalEventWakeIsNeverRunnable() {
        val waiting = plan().copy(
            steps = listOf(
                plan().steps.single().copy(status = PlanStepStatus.REQUIRES_CONFIRMATION)
            )
        )
        val port = FakePlanPort(waiting)
        val admission = admission()
        val checkpoint = AmperAgentPlanTaskCheckpoint(
            taskId = admission.request.taskId,
            planId = waiting.id,
            backgroundMode = OmegaBackgroundMode.EVENT_WAKE,
            taskState = AmperAgentTaskState.WAITING_APPROVAL,
            completedSteps = 0,
            totalSteps = 1,
            updatedAtEpochMs = 100L
        )
        val wake = AmperAgentProactiveEventWakeCoordinator(port)

        val handoff = wake.handoff(admission, checkpoint).getOrThrow()
        val restored = wake.restore(
            admission,
            AmperAgentEventWakeHandoffPolicy.verifyAndDecode(handoff).getOrThrow()
        ).getOrThrow()

        assertEquals(
            AmperAgentEventWakeDisposition.WAITING_GOVERNED_APPROVAL,
            handoff.disposition
        )
        assertFalse(handoff.runnable)
        assertFalse(restored.runnable)
        assertEquals(1, restored.checkpoint.totalSteps)
        assertEquals(0, port.advanceCalls)
    }

    @Test
    fun staleDurablePlanFailsRestoreBeforeAnyAdvance() {
        val port = FakePlanPort(plan())
        val admission = admission()
        val proactive = AmperAgentProactiveTaskCoordinator(port) { 100L }
        val started = proactive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val wake = AmperAgentProactiveEventWakeCoordinator(port)
        val envelope = wake.checkpoint(admission, started.checkpoint).getOrThrow()

        port.current = port.current.copy(
            steps = port.current.steps.map { it.copy(reason = "durable state changed") }
        )

        val result = wake.restore(admission, envelope)

        assertTrue(result.isFailure)
        assertEquals(0, port.advanceCalls)
    }

    @Test
    fun triggerProvenanceDriftFailsRestore() {
        val port = FakePlanPort(plan())
        val admission = admission()
        val proactive = AmperAgentProactiveTaskCoordinator(port) { 100L }
        val started = proactive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val wake = AmperAgentProactiveEventWakeCoordinator(port)
        val envelope = wake.checkpoint(admission, started.checkpoint).getOrThrow()
        val drifted = admission(
            trigger = AmperAgentTrigger(
                triggerId = "monitor.example",
                source = "canonical-monitor",
                observedAtEpochMs = 1L,
                payloadDigest = "b".repeat(64)
            )
        )

        val result = wake.restore(drifted, envelope)

        assertTrue(result.isFailure)
        assertEquals(0, port.advanceCalls)
    }

    @Test
    fun tamperedTransportCannotReachRestore() {
        val port = FakePlanPort(plan())
        val admission = admission()
        val proactive = AmperAgentProactiveTaskCoordinator(port) { 100L }
        val started = proactive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val handoff = AmperAgentProactiveEventWakeCoordinator(port)
            .handoff(admission, started.checkpoint)
            .getOrThrow()

        val result = runCatching {
            handoff.copy(encodedEnvelope = handoff.encodedEnvelope + "\n")
        }

        assertTrue(result.isFailure)
        assertEquals(0, port.advanceCalls)
    }

    @Test
    fun userRequestCannotCreateEventWakeCheckpoint() {
        val port = FakePlanPort(plan())
        val userAdmission = AmperAgentTaskAdmissionPolicy.admit(
            AmperAgentTaskRequest(
                taskId = "user-task",
                origin = AmperAgentTaskOrigin.USER_REQUEST,
                objective = "Observe the trigger and perform one governed task.",
                allowedCapabilities = setOf(CapabilityId("reasoning")),
                expectedRuntimeMs = 1_000L,
                mustSurviveUiExit = true,
                canBeDeferred = true,
                createdAtEpochMs = 1L
            )
        )
        val checkpoint = AmperAgentPlanTaskCheckpoint(
            taskId = "user-task",
            planId = port.current.id,
            backgroundMode = userAdmission.backgroundMode,
            taskState = AmperAgentTaskState.READY,
            completedSteps = 0,
            totalSteps = 1,
            updatedAtEpochMs = 1L
        )

        val result = AmperAgentProactiveEventWakeCoordinator(port)
            .checkpoint(userAdmission, checkpoint)

        assertTrue(result.isFailure)
    }

    private fun admission(
        trigger: AmperAgentTrigger = AmperAgentTrigger(
            triggerId = "monitor.example",
            source = "canonical-monitor",
            observedAtEpochMs = 1L,
            payloadDigest = "a".repeat(64)
        )
    ): AmperAgentTaskAdmission =
        AmperAgentTaskAdmissionPolicy.admit(
            AmperAgentTaskRequest(
                taskId = "proactive-task",
                origin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER,
                objective = "Observe the trigger and perform one governed task.",
                allowedCapabilities = setOf(CapabilityId("reasoning")),
                expectedRuntimeMs = 1_000L,
                mustSurviveUiExit = true,
                canBeDeferred = true,
                createdAtEpochMs = 1L,
                trigger = trigger
            )
        )

    private fun plan() = SovereignPlan(
        id = PlanId("proactive-plan"),
        conversationId = ConversationId("conversation-1"),
        goal = "Observe the trigger and perform one governed task.",
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("request-1"),
                capability = CapabilityId("reasoning"),
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
        var advanceCalls: Int = 0

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
