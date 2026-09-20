package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ActionOutcome
import io.amper.neuroos.core.ActionRequestId
import io.amper.neuroos.core.ActionStatus
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

class AmperAgentPassiveTaskCoordinatorTest {
    @Test
    fun startBindsUserTaskToDurableSovereignPlanCheckpoint() {
        val port = FakePlanPort()
        val coordinator = AmperAgentPassiveTaskCoordinator(port) { 100L }
        val admission = admission(
            AmperAgentTaskAdmissionPolicy.admit(
                request(mustSurviveUiExit = true)
            )
        )

        val result = coordinator.start(
            admission = admission,
            conversationId = ConversationId("conversation-1")
        ).getOrThrow()

        assertEquals(1, port.createCalls)
        assertEquals("user-task", result.checkpoint.taskId)
        assertEquals(port.current.id, result.checkpoint.planId)
        assertEquals(AmperAgentTaskState.READY, result.checkpoint.taskState)
        assertEquals(
            OmegaBackgroundMode.FOREGROUND_CONTINUATION,
            result.checkpoint.backgroundMode
        )
        assertTrue(result.diagnostics.checkpointRequired)
    }

    @Test
    fun pendingApprovalStopsWithoutAgentCoreApprovalBypass() {
        val port = FakePlanPort()
        val coordinator = AmperAgentPassiveTaskCoordinator(port) { 100L }
        val admission = admission(AmperAgentTaskAdmissionPolicy.admit(request()))
        val started = coordinator.start(
            admission,
            ConversationId("conversation-1")
        ).getOrThrow()

        port.nextAdvance = {
            val waitingPlan = port.current.copy(
                steps = port.current.steps.map { step ->
                    step.copy(status = PlanStepStatus.REQUIRES_CONFIRMATION)
                }
            )
            port.current = waitingPlan
            PlanAdvanceResult.PendingApproval(
                plan = waitingPlan,
                step = waitingPlan.steps.first(),
                proposal = waitingPlan.steps.first().proposal()
            )
        }

        val waiting = coordinator.advance(admission, started.checkpoint).getOrThrow()

        assertEquals(AmperAgentTaskState.WAITING_APPROVAL, waiting.checkpoint.taskState)
        assertEquals(1, port.advanceCalls)

        val prematureResume = coordinator.resumeAfterGovernedApproval(
            admission,
            waiting.checkpoint
        )
        assertTrue(prematureResume.isFailure)
        assertEquals(1, port.advanceCalls)
    }

    @Test
    fun governedExternalApprovalCanResumeTaskWithoutReplayingApproval() {
        val port = FakePlanPort()
        val coordinator = AmperAgentPassiveTaskCoordinator(port) { 100L }
        val admission = admission(AmperAgentTaskAdmissionPolicy.admit(request()))
        val started = coordinator.start(
            admission,
            ConversationId("conversation-1")
        ).getOrThrow()

        val waitingPlan = port.current.copy(
            steps = port.current.steps.map {
                it.copy(status = PlanStepStatus.REQUIRES_CONFIRMATION)
            }
        )
        port.current = waitingPlan
        val waiting = AmperAgentPassiveTaskCheckpoint(
            taskId = admission.request.taskId,
            planId = waitingPlan.id,
            backgroundMode = admission.backgroundMode,
            taskState = AmperAgentTaskState.WAITING_APPROVAL,
            completedSteps = 0,
            totalSteps = 1,
            updatedAtEpochMs = 100L
        )

        val executedStep = waitingPlan.steps.single().copy(
            status = PlanStepStatus.EXECUTED,
            outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = waitingPlan.steps.single().proposal(),
                output = "ok"
            )
        )
        port.current = waitingPlan.copy(steps = listOf(executedStep))

        val resumed = coordinator.resumeAfterGovernedApproval(
            admission,
            waiting
        ).getOrThrow()

        assertEquals(AmperAgentTaskState.COMPLETED, resumed.checkpoint.taskState)
        assertEquals(1, resumed.checkpoint.completedSteps)
        assertEquals(0, port.advanceCalls)
    }

    @Test
    fun proactiveTaskIsRejectedByPassiveCoordinator() {
        val port = FakePlanPort()
        val coordinator = AmperAgentPassiveTaskCoordinator(port)
        val proactive = AmperAgentTaskAdmissionPolicy.admit(
            AmperAgentTaskRequest(
                taskId = "proactive-task",
                origin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER,
                objective = "Triggered work.",
                allowedCapabilities = setOf(CapabilityId("reasoning")),
                expectedRuntimeMs = 1_000L,
                mustSurviveUiExit = true,
                canBeDeferred = true,
                createdAtEpochMs = 1L,
                trigger = AmperAgentTrigger(
                    triggerId = "event.test",
                    source = "scheduler",
                    observedAtEpochMs = 1L,
                    payloadDigest = "a".repeat(64)
                )
            )
        )

        val result = coordinator.start(
            proactive,
            ConversationId("conversation-1")
        )

        assertTrue(result.isFailure)
        assertEquals(0, port.createCalls)
    }

    @Test
    fun completedTaskCannotAdvanceAgain() {
        val port = FakePlanPort()
        val coordinator = AmperAgentPassiveTaskCoordinator(port)
        val admission = admission(AmperAgentTaskAdmissionPolicy.admit(request()))
        val terminal = AmperAgentPassiveTaskCheckpoint(
            taskId = admission.request.taskId,
            planId = port.current.id,
            backgroundMode = admission.backgroundMode,
            taskState = AmperAgentTaskState.COMPLETED,
            completedSteps = 1,
            totalSteps = 1,
            updatedAtEpochMs = 1L
        )

        val result = coordinator.advance(admission, terminal)

        assertTrue(result.isFailure)
        assertEquals(0, port.advanceCalls)
    }

    private fun request(
        mustSurviveUiExit: Boolean = false
    ) = AmperAgentTaskRequest(
        taskId = "user-task",
        origin = AmperAgentTaskOrigin.USER_REQUEST,
        objective = "Perform one governed passive task.",
        allowedCapabilities = setOf(CapabilityId("reasoning")),
        expectedRuntimeMs = 1_000L,
        mustSurviveUiExit = mustSurviveUiExit,
        canBeDeferred = false,
        createdAtEpochMs = 1L
    )

    private fun admission(
        value: AmperAgentTaskAdmission
    ): AmperAgentTaskAdmission = value

    private class FakePlanPort : AmperAgentPersistentPlanPort {
        var createCalls = 0
        var advanceCalls = 0
        var current: SovereignPlan = plan()
        var nextAdvance: () -> PlanAdvanceResult = {
            val step = current.steps.single()
            val outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = step.proposal(),
                output = "ok"
            )
            val executed = step.copy(
                status = PlanStepStatus.EXECUTED,
                outcome = outcome
            )
            current = current.copy(steps = listOf(executed))
            PlanAdvanceResult.StepProcessed(current, executed, outcome)
        }

        override fun create(
            conversationId: ConversationId,
            userGoal: String
        ): Result<SovereignPlan> {
            createCalls += 1
            current = plan(
                conversationId = conversationId,
                goal = userGoal
            )
            return Result.success(current)
        }

        override fun load(planId: PlanId): SovereignPlan? =
            current.takeIf { it.id == planId }

        override fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> {
            advanceCalls += 1
            assertEquals(current.id, plan.id)
            return Result.success(nextAdvance())
        }

        companion object {
            private fun plan(
                conversationId: ConversationId = ConversationId("conversation-1"),
                goal: String = "Perform one governed passive task."
            ) = SovereignPlan(
                id = PlanId("plan-1"),
                conversationId = conversationId,
                goal = goal,
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
        }
    }
}
