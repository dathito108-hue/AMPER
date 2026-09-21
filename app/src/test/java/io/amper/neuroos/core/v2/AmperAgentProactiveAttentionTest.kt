package io.amper.neuroos.core.v2

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
