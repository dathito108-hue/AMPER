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

class AmperAgentCanonicalContinuationExecutionPortTest {
    @Test
    fun verifiedWakeAdvancesExactlyOneStepAndReturnsFreshCheckpoint() {
        val planPort = FakePlanPort(plan(stepCount = 2))
        val passive = AmperAgentPassiveTaskCoordinator(planPort) { 100L }
        val continuation = AmperAgentExecutionContinuationCoordinator(planPort) { 100L }
        val admissions = AmperAgentTaskAdmissionRegistry()
        val admission = admission()
        admissions.register(admission)
        val started = passive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val envelope = continuation.checkpoint(admission, started.checkpoint).getOrThrow()
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(envelope)
        val execution = AmperAgentCanonicalContinuationExecutionPort(
            admissions = admissions,
            plans = planPort,
            passive = passive,
            continuation = continuation
        )

        val result = execution.advanceOnceVerified(handoff, envelope).getOrThrow()

        assertEquals(1, planPort.advanceCalls)
        assertEquals(AmperAgentAndroidHostExecutionState.CHECKPOINTED, result.state)
        val next = requireNotNull(result.nextHandoff)
        val decoded = AmperAgentAndroidContinuationHandoffPolicy
            .verifyAndDecode(next)
            .getOrThrow()
        assertEquals(1, decoded.completedSteps)
        assertEquals(AmperAgentTaskState.CHECKPOINTED, decoded.taskState)
    }

    @Test
    fun missingRamAdmissionIsReconstructedNarrowlyFromHashBoundPlan() {
        val planPort = FakePlanPort(plan(stepCount = 2))
        val passive = AmperAgentPassiveTaskCoordinator(planPort) { 100L }
        val continuation = AmperAgentExecutionContinuationCoordinator(planPort) { 100L }
        val originalAdmission = admission()
        val started = passive.start(
            originalAdmission,
            ConversationId("conversation-1")
        ).getOrThrow()
        val envelope = continuation
            .checkpoint(originalAdmission, started.checkpoint)
            .getOrThrow()
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(envelope)
        val admissions = AmperAgentTaskAdmissionRegistry()
        val execution = AmperAgentCanonicalContinuationExecutionPort(
            admissions = admissions,
            plans = planPort,
            passive = passive,
            continuation = continuation
        )

        val result = execution.advanceOnceVerified(handoff, envelope).getOrThrow()

        assertEquals(AmperAgentAndroidHostExecutionState.CHECKPOINTED, result.state)
        assertEquals(1, planPort.advanceCalls)
        val restoredAdmission = requireNotNull(admissions.get("user-task"))
        assertEquals(
            setOf(CapabilityId("reasoning")),
            restoredAdmission.request.allowedCapabilities
        )
        assertEquals(envelope.backgroundMode, restoredAdmission.backgroundMode)
    }

    @Test
    fun durablePlanDriftFailsBeforeAdvance() {
        val planPort = FakePlanPort(plan(stepCount = 2))
        val passive = AmperAgentPassiveTaskCoordinator(planPort) { 100L }
        val continuation = AmperAgentExecutionContinuationCoordinator(planPort) { 100L }
        val admission = admission()
        val started = passive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val envelope = continuation.checkpoint(admission, started.checkpoint).getOrThrow()
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(envelope)
        val admissions = AmperAgentTaskAdmissionRegistry().also { it.register(admission) }
        val execution = AmperAgentCanonicalContinuationExecutionPort(
            admissions = admissions,
            plans = planPort,
            passive = passive,
            continuation = continuation
        )

        planPort.current = planPort.current.copy(
            steps = planPort.current.steps.map {
                if (it.index == 1) it.copy(reason = "durable state changed") else it
            }
        )

        val result = execution.advanceOnceVerified(handoff, envelope)

        assertTrue(result.isFailure)
        assertEquals(0, planPort.advanceCalls)
    }

    @Test
    fun concurrentDuplicateWakeAdvancesDurablePlanOnlyOnce() {
        val planPort = FakePlanPort(plan(stepCount = 2))
        val passive = AmperAgentPassiveTaskCoordinator(planPort) { 100L }
        val continuation = AmperAgentExecutionContinuationCoordinator(planPort) { 100L }
        val admission = admission()
        val started = passive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val envelope = continuation.checkpoint(admission, started.checkpoint).getOrThrow()
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(envelope)
        val admissions = AmperAgentTaskAdmissionRegistry().also { it.register(admission) }
        val execution = AmperAgentCanonicalContinuationExecutionPort(
            admissions = admissions,
            plans = planPort,
            passive = passive,
            continuation = continuation
        )

        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = Collections.synchronizedList(
            mutableListOf<Result<AmperAgentAndroidHostExecutionResult>>()
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

    @Test
    fun pendingApprovalStopsChainWithoutNextHandoff() {
        val planPort = FakePlanPort(plan(stepCount = 1))
        val passive = AmperAgentPassiveTaskCoordinator(planPort) { 100L }
        val continuation = AmperAgentExecutionContinuationCoordinator(planPort) { 100L }
        val admission = admission()
        val started = passive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val envelope = continuation.checkpoint(admission, started.checkpoint).getOrThrow()
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(envelope)
        val admissions = AmperAgentTaskAdmissionRegistry().also { it.register(admission) }
        planPort.advanceMode = FakeAdvanceMode.WAIT_APPROVAL
        val execution = AmperAgentCanonicalContinuationExecutionPort(
            admissions = admissions,
            plans = planPort,
            passive = passive,
            continuation = continuation
        )

        val result = execution.advanceOnceVerified(handoff, envelope).getOrThrow()

        assertEquals(1, planPort.advanceCalls)
        assertEquals(AmperAgentAndroidHostExecutionState.WAITING_APPROVAL, result.state)
        assertEquals(null, result.nextHandoff)
    }

    @Test
    fun terminalTaskRemovesProcessAdmission() {
        val planPort = FakePlanPort(plan(stepCount = 1))
        val passive = AmperAgentPassiveTaskCoordinator(planPort) { 100L }
        val continuation = AmperAgentExecutionContinuationCoordinator(planPort) { 100L }
        val admission = admission()
        val started = passive.start(admission, ConversationId("conversation-1")).getOrThrow()
        val envelope = continuation.checkpoint(admission, started.checkpoint).getOrThrow()
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(envelope)
        val admissions = AmperAgentTaskAdmissionRegistry().also { it.register(admission) }
        val execution = AmperAgentCanonicalContinuationExecutionPort(
            admissions = admissions,
            plans = planPort,
            passive = passive,
            continuation = continuation
        )

        val result = execution.advanceOnceVerified(handoff, envelope).getOrThrow()

        assertEquals(AmperAgentAndroidHostExecutionState.TERMINAL_NOOP, result.state)
        assertEquals(0, admissions.size())
    }

    private fun admission() = AmperAgentTaskAdmissionPolicy.admit(
        AmperAgentTaskRequest(
            taskId = "user-task",
            origin = AmperAgentTaskOrigin.USER_REQUEST,
            objective = "Perform the canonical user task.",
            allowedCapabilities = setOf(CapabilityId("reasoning")),
            expectedRuntimeMs = 30_000L,
            mustSurviveUiExit = true,
            canBeDeferred = false,
            createdAtEpochMs = 1L
        )
    )

    private fun plan(stepCount: Int): SovereignPlan = SovereignPlan(
        id = PlanId("plan-1"),
        conversationId = ConversationId("conversation-1"),
        goal = "Perform the canonical user task.",
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

    private enum class FakeAdvanceMode {
        EXECUTE,
        WAIT_APPROVAL
    }

    private class FakePlanPort(
        var current: SovereignPlan
    ) : AmperAgentPersistentPlanPort {
        var advanceCalls: Int = 0
        var advanceMode: FakeAdvanceMode = FakeAdvanceMode.EXECUTE

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
                FakeAdvanceMode.EXECUTE -> {
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

                FakeAdvanceMode.WAIT_APPROVAL -> {
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
