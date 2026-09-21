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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AmperAgentCanonicalEventWakeExecutionPortTest {
    @Test
    fun verifiedReadyWakeAdvancesExactlyOneProactiveStepAndReturnsFreshHandoff() {
        val planPort = FakePlanPort(plan(stepCount = 2))
        val admission = admission()
        val proactive = AmperAgentProactiveTaskCoordinator(planPort) { 100L }
        val wake = AmperAgentProactiveEventWakeCoordinator(planPort) { 100L }
        val started = proactive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val handoff = wake.handoff(admission, started.checkpoint).getOrThrow()
        val envelope = AmperAgentEventWakeHandoffPolicy.verifyAndDecode(handoff).getOrThrow()
        val execution = AmperAgentCanonicalEventWakeExecutionPort(
            admissions = AmperAgentTaskAdmissionRegistry(),
            plans = planPort,
            proactive = proactive,
            eventWake = wake
        )

        val result = execution.advanceOnceVerified(handoff, envelope).getOrThrow()

        assertEquals(1, planPort.advanceCalls)
        assertEquals(AmperAgentEventWakeHostExecutionState.CHECKPOINTED, result.state)
        val next = requireNotNull(result.nextHandoff)
        val decoded = AmperAgentEventWakeHandoffPolicy.verifyAndDecode(next).getOrThrow()
        assertEquals(1, decoded.completedSteps)
        assertEquals(AmperAgentTaskState.CHECKPOINTED, decoded.taskState)
        assertEquals(admission.request.trigger, decoded.trigger)
    }

    @Test
    fun proactiveWakeThatReachesApprovalStopsWithoutNextHandoff() {
        val planPort = FakePlanPort(plan(stepCount = 1))
        planPort.advanceMode = AdvanceMode.WAIT_APPROVAL
        val admission = admission()
        val proactive = AmperAgentProactiveTaskCoordinator(planPort) { 100L }
        val wake = AmperAgentProactiveEventWakeCoordinator(planPort) { 100L }
        val started = proactive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val handoff = wake.handoff(admission, started.checkpoint).getOrThrow()
        val envelope = AmperAgentEventWakeHandoffPolicy.verifyAndDecode(handoff).getOrThrow()
        val execution = AmperAgentCanonicalEventWakeExecutionPort(
            admissions = AmperAgentTaskAdmissionRegistry(),
            plans = planPort,
            proactive = proactive,
            eventWake = wake
        )

        val result = execution.advanceOnceVerified(handoff, envelope).getOrThrow()

        assertEquals(1, planPort.advanceCalls)
        assertEquals(AmperAgentEventWakeHostExecutionState.WAITING_APPROVAL, result.state)
        assertEquals(null, result.nextHandoff)
    }

    @Test
    fun waitingApprovalHandoffNeverCallsExecutionOrColdFallback() {
        val waiting = plan(stepCount = 1).copy(
            steps = listOf(
                plan(stepCount = 1).steps.single().copy(
                    status = PlanStepStatus.REQUIRES_CONFIRMATION
                )
            )
        )
        val planPort = FakePlanPort(waiting)
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
        val handoff = AmperAgentProactiveEventWakeCoordinator(planPort)
            .handoff(admission, checkpoint)
            .getOrThrow()
        var executionCalls = 0
        var fallbackCalls = 0
        val execution = AmperAgentEventWakeExecutionPort { _, _ ->
            executionCalls += 1
            error("approval-blocked EVENT_WAKE must not execute")
        }

        val result = AmperAgentEventWakeHostDispatcher
            .dispatch(
                handoff = handoff,
                execution = execution,
                executionFallback = {
                    fallbackCalls += 1
                    error("approval-blocked EVENT_WAKE must not cold-bootstrap")
                }
            )
            .getOrThrow()

        assertEquals(0, executionCalls)
        assertEquals(0, fallbackCalls)
        assertEquals(AmperAgentEventWakeHostExecutionState.WAITING_APPROVAL, result.state)
    }

    @Test
    fun tamperedDispositionFailsBeforeColdFallback() {
        val planPort = FakePlanPort(plan(stepCount = 1))
        val admission = admission()
        val proactive = AmperAgentProactiveTaskCoordinator(planPort) { 100L }
        val wake = AmperAgentProactiveEventWakeCoordinator(planPort) { 100L }
        val started = proactive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val original = wake.handoff(admission, started.checkpoint).getOrThrow()
        val tampered = original.copy(
            disposition = AmperAgentEventWakeDisposition.TERMINAL_NOOP
        )
        var fallbackCalls = 0

        val result = AmperAgentEventWakeHostDispatcher.dispatch(
            handoff = tampered,
            execution = null,
            executionFallback = {
                fallbackCalls += 1
                error("tampered EVENT_WAKE must not acquire execution")
            }
        )

        assertTrue(result.isFailure)
        assertEquals(0, fallbackCalls)
        assertEquals(0, planPort.advanceCalls)
    }

    @Test
    fun unavailableReadyWakeRequestsRetryWithoutAdvancing() {
        val planPort = FakePlanPort(plan(stepCount = 1))
        val admission = admission()
        val proactive = AmperAgentProactiveTaskCoordinator(planPort) { 100L }
        val wake = AmperAgentProactiveEventWakeCoordinator(planPort) { 100L }
        val started = proactive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val handoff = wake.handoff(admission, started.checkpoint).getOrThrow()

        val result = AmperAgentEventWakeHostDispatcher
            .dispatch(handoff, null)
            .getOrThrow()

        assertEquals(AmperAgentEventWakeHostExecutionState.RETRY_LATER, result.state)
        assertTrue(result.shouldReschedule)
        assertEquals(0, planPort.advanceCalls)
    }

    @Test
    fun concurrentDuplicateEventWakeAdvancesDurablePlanOnlyOnce() {
        val planPort = FakePlanPort(plan(stepCount = 2))
        val admission = admission()
        val proactive = AmperAgentProactiveTaskCoordinator(planPort) { 100L }
        val wake = AmperAgentProactiveEventWakeCoordinator(planPort) { 100L }
        val started = proactive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val handoff = wake.handoff(admission, started.checkpoint).getOrThrow()
        val envelope = AmperAgentEventWakeHandoffPolicy.verifyAndDecode(handoff).getOrThrow()
        val execution = AmperAgentCanonicalEventWakeExecutionPort(
            admissions = AmperAgentTaskAdmissionRegistry().also { it.register(admission) },
            plans = planPort,
            proactive = proactive,
            eventWake = wake
        )

        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = Collections.synchronizedList(
            mutableListOf<Result<AmperAgentEventWakeHostExecutionResult>>()
        )
        repeat(2) {
            Thread {
                try {
                    start.await()
                    results += execution.advanceOnceVerified(handoff, envelope)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()

        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(2, results.size)
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertEquals(1, planPort.advanceCalls)
    }

    private fun admission() = AmperAgentTaskAdmissionPolicy.admit(
        AmperAgentTaskRequest(
            taskId = "proactive-task",
            origin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER,
            objective = "Observe and handle one governed proactive event.",
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

    private fun plan(stepCount: Int) = SovereignPlan(
        id = PlanId("proactive-plan"),
        conversationId = ConversationId("conversation-1"),
        goal = "Observe and handle one governed proactive event.",
        steps = (1..stepCount).map { index ->
            SovereignPlanStep(
                index = index,
                requestId = ActionRequestId("request-$index"),
                capability = CapabilityId("reasoning"),
                reason = "step $index",
                input = "input-$index"
            )
        },
        planningBackendId = "amper-core",
        createdAtEpochMs = 1L
    )

    private enum class AdvanceMode {
        EXECUTE,
        WAIT_APPROVAL
    }

    private class FakePlanPort(
        var current: SovereignPlan
    ) : AmperAgentPersistentPlanPort {
        var advanceCalls: Int = 0
        var advanceMode: AdvanceMode = AdvanceMode.EXECUTE

        override fun create(
            conversationId: ConversationId,
            userGoal: String
        ): Result<SovereignPlan> {
            current = current.copy(
                conversationId = conversationId,
                goal = userGoal
            )
            return Result.success(current)
        }

        override fun load(planId: PlanId): SovereignPlan? =
            current.takeIf { it.id == planId }

        override fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> {
            advanceCalls += 1
            val step = plan.steps.first {
                it.status == PlanStepStatus.PLANNED ||
                    it.status == PlanStepStatus.REQUIRES_CONFIRMATION
            }
            return when (advanceMode) {
                AdvanceMode.EXECUTE -> {
                    val outcome = ActionOutcome(
                        status = ActionStatus.EXECUTED,
                        proposal = step.proposal(),
                        output = "ok"
                    )
                    val updatedStep = step.copy(
                        status = PlanStepStatus.EXECUTED,
                        outcome = outcome
                    )
                    current = plan.copy(
                        steps = plan.steps.map {
                            if (it.index == step.index) updatedStep else it
                        }
                    )
                    Result.success(
                        PlanAdvanceResult.StepProcessed(
                            plan = current,
                            step = updatedStep,
                            outcome = outcome
                        )
                    )
                }

                AdvanceMode.WAIT_APPROVAL -> {
                    val waitingStep = step.copy(
                        status = PlanStepStatus.REQUIRES_CONFIRMATION
                    )
                    current = plan.copy(
                        steps = plan.steps.map {
                            if (it.index == step.index) waitingStep else it
                        }
                    )
                    Result.success(
                        PlanAdvanceResult.PendingApproval(
                            plan = current,
                            step = waitingStep,
                            proposal = waitingStep.proposal()
                        )
                    )
                }
            }
        }
    }
}
