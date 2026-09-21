package io.amper.neuroos.core.v2

import io.amper.neuroos.core.PlanId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentProactiveAttentionPolicyTest {
    @Test
    fun checkpointedWorkProducesNoExternalAttentionSignal() {
        assertNull(
            AmperAgentProactiveAttentionPolicy.signal(
                view(AmperAgentTaskState.CHECKPOINTED)
            )
        )
    }

    @Test
    fun missingCanonicalPlanProducesNoExternalAttentionSignal() {
        val binding = binding()
        val missing = AmperAgentProactiveTaskLifecycleView(
            binding = binding,
            taskState = null,
            completedSteps = 0,
            totalSteps = 0,
            waitingApprovalStepIndex = null,
            goal = null,
            planAvailable = false
        )

        assertNull(AmperAgentProactiveAttentionPolicy.signal(missing))
    }

    @Test
    fun approvalAttentionIsStableAndDoesNotExposePlanContent() {
        val secretGoal = "SECRET-GOAL-never-in-notification"
        val waiting = view(
            state = AmperAgentTaskState.WAITING_APPROVAL,
            waitingStep = 2,
            goal = secretGoal
        )

        val first = requireNotNull(
            AmperAgentProactiveAttentionPolicy.signal(waiting)
        )
        val same = requireNotNull(
            AmperAgentProactiveAttentionPolicy.signal(waiting)
        )

        assertEquals(
            AmperAgentProactiveAttentionKind.GOVERNED_APPROVAL_REQUIRED,
            first.kind
        )
        assertEquals(2, first.waitingApprovalStepIndex)
        assertEquals(first.fingerprintSha256, same.fingerprintSha256)
        assertFalse(first.title.contains(secretGoal))
        assertFalse(first.message.contains(secretGoal))
        assertFalse(first.message.contains(binding().trigger.payloadDigest))
        assertTrue(first.message.contains("governed approval"))
    }

    @Test
    fun terminalStateChangesAttentionFingerprintAndMeaning() {
        val waiting = requireNotNull(
            AmperAgentProactiveAttentionPolicy.signal(
                view(
                    state = AmperAgentTaskState.WAITING_APPROVAL,
                    waitingStep = 1
                )
            )
        )
        val completed = requireNotNull(
            AmperAgentProactiveAttentionPolicy.signal(
                view(AmperAgentTaskState.COMPLETED)
            )
        )
        val failed = requireNotNull(
            AmperAgentProactiveAttentionPolicy.signal(
                view(AmperAgentTaskState.FAILED)
            )
        )

        assertEquals(AmperAgentProactiveAttentionKind.COMPLETED, completed.kind)
        assertEquals(AmperAgentProactiveAttentionKind.FAILED, failed.kind)
        assertNotEquals(waiting.fingerprintSha256, completed.fingerprintSha256)
        assertNotEquals(completed.fingerprintSha256, failed.fingerprintSha256)
    }

    private fun view(
        state: AmperAgentTaskState,
        waitingStep: Int? = null,
        goal: String = "Handle a bounded proactive task."
    ): AmperAgentProactiveTaskLifecycleView =
        AmperAgentProactiveTaskLifecycleView(
            binding = binding(),
            taskState = state,
            completedSteps = when (state) {
                AmperAgentTaskState.COMPLETED,
                AmperAgentTaskState.FAILED,
                AmperAgentTaskState.CANCELLED -> 2
                else -> 1
            },
            totalSteps = 2,
            waitingApprovalStepIndex =
                if (state == AmperAgentTaskState.WAITING_APPROVAL) {
                    requireNotNull(waitingStep)
                } else {
                    null
                },
            goal = goal,
            planAvailable = true
        )

    private fun binding(): AmperAgentProactiveTaskLifecycleBinding {
        val identity = "a".repeat(64)
        return AmperAgentProactiveTaskLifecycleBinding(
            sourceId = "monitor.example",
            configurationSha256 = "b".repeat(64),
            observationIdentitySha256 = identity,
            taskId = "proactive:" + identity.take(48),
            planId = PlanId("agent-trigger-plan:$identity"),
            trigger = AmperAgentTrigger(
                triggerId = "monitor.example",
                source = "user-configured:app_local_event:source",
                observedAtEpochMs = 10L,
                payloadDigest = "c".repeat(64)
            ),
            boundAtEpochMs = 10L
        )
    }
}
