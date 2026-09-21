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
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentProactiveTaskCoordinatorTest {
    @Test
    fun startBindsTriggerToCanonicalEventWakePersistentPlan() {
        val port = FakePlanPort()
        val coordinator = AmperAgentProactiveTaskCoordinator(port) { 100L }
        val admission = proactiveAdmission()

        val result = coordinator.start(
            admission = admission,
            conversationId = ConversationId("conversation-1")
        ).getOrThrow()

        assertEquals(1, port.createCalls)
        assertEquals("proactive-task", result.checkpoint.taskId)
        assertEquals(port.current.id, result.checkpoint.planId)
        assertEquals(OmegaBackgroundMode.EVENT_WAKE, result.checkpoint.backgroundMode)
        assertEquals(AmperAgentTaskState.READY, result.checkpoint.taskState)
        assertTrue(result.diagnostics.checkpointRequired)
    }

    @Test
    fun proactiveAdvanceCallsCanonicalPlanPortOnlyOncePerWake() {
        val port = FakePlanPort(stepCount = 2)
        val coordinator = AmperAgentProactiveTaskCoordinator(port) { 100L }
        val admission = proactiveAdmission()
        val started = coordinator.start(
            admission,
            ConversationId("conversation-1")
        ).getOrThrow()

        val advanced = coordinator.advance(admission, started.checkpoint).getOrThrow()

        assertEquals(1, port.advanceCalls)
        assertEquals(1, advanced.checkpoint.completedSteps)
        assertEquals(2, advanced.checkpoint.totalSteps)
        assertEquals(AmperAgentTaskState.READY, advanced.checkpoint.taskState)
    }

    @Test
    fun proactiveSideEffectStopsAtGovernedApprovalBoundary() {
        val port = FakePlanPort()
        val coordinator = AmperAgentProactiveTaskCoordinator(port) { 100L }
        val admission = proactiveAdmission()
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

        val automaticAdvance = coordinator.advance(admission, waiting.checkpoint)
        assertTrue(automaticAdvance.isFailure)
        assertEquals(1, port.advanceCalls)

        val prematureResume = coordinator.resumeAfterGovernedApproval(
            admission,
            waiting.checkpoint
        )
        assertTrue(prematureResume.isFailure)
        assertEquals(1, port.advanceCalls)
    }

    @Test
    fun proactiveCoordinatorRejectsUserRequestAdmission() {
        val port = FakePlanPort()
        val coordinator = AmperAgentProactiveTaskCoordinator(port)
        val userAdmission = AmperAgentTaskAdmissionPolicy.admit(
            AmperAgentTaskRequest(
                taskId = "user-task",
                origin = AmperAgentTaskOrigin.USER_REQUEST,
                objective = "Explicit user work.",
                allowedCapabilities = setOf(CapabilityId("reasoning")),
                expectedRuntimeMs = 1_000L,
                mustSurviveUiExit = false,
                canBeDeferred = false,
                createdAtEpochMs = 1L
            )
        )

        val result = coordinator.start(
            userAdmission,
            ConversationId("conversation-1")
        )

        assertTrue(result.isFailure)
        assertEquals(0, port.createCalls)
    }

    @Test
    fun proactivePlanCannotEscapeDeclaredCapabilityEnvelope() {
        val port = FakePlanPort(planCapability = CapabilityId("device.admin"))
        val coordinator = AmperAgentProactiveTaskCoordinator(port)
        val admission = proactiveAdmission()

        val result = coordinator.start(
            admission,
            ConversationId("conversation-1")
        )

        assertTrue(result.isFailure)
        assertEquals(1, port.createCalls)
        assertEquals(0, port.advanceCalls)
    }

    private fun proactiveAdmission(): AmperAgentTaskAdmission =
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
                trigger = AmperAgentTrigger(
                    triggerId = "monitor.example",
                    source = "canonical-monitor",
                    observedAtEpochMs = 1L,
                    payloadDigest = "a".repeat(64)
                )
            )
        )

    private class FakePlanPort(
        private val stepCount: Int = 1,
        private val planCapability: CapabilityId = CapabilityId("reasoning")
    ) : AmperAgentPersistentPlanPort {
        var createCalls = 0
        var advanceCalls = 0
        var current: SovereignPlan = plan(
            goal = "Observe the trigger and perform one governed task."
        )
        var nextAdvance: () -> PlanAdvanceResult = {
            val active = current.steps.first { it.status == PlanStepStatus.PLANNED }
            val outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = active.proposal(),
                output = "ok"
            )
            val executed = active.copy(
                status = PlanStepStatus.EXECUTED,
                outcome = outcome
            )
            current = current.copy(
                steps = current.steps.map { step ->
                    if (step.index == executed.index) executed else step
                }
            )
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

        private fun plan(
            conversationId: ConversationId = ConversationId("conversation-1"),
            goal: String
        ) = SovereignPlan(
            id = PlanId("proactive-plan"),
            conversationId = conversationId,
            goal = goal,
            steps = (1..stepCount).map { index ->
                SovereignPlanStep(
                    index = index,
                    requestId = ActionRequestId("request-$index"),
                    capability = planCapability,
                    reason = "test",
                    input = "step-$index"
                )
            },
            planningBackendId = "amper-core",
            createdAtEpochMs = 1L
        )
    }
}
