package io.amper.neuroos.core.v2

import io.amper.neuroos.core.PlanId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentAndroidContinuationHandoffTest {
    @Test
    fun foregroundContinuationRequiresVisibleForegroundHost() {
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.FOREGROUND_CONTINUATION,
                state = AmperAgentTaskState.CHECKPOINTED
            )
        )

        assertEquals(
            AmperAgentAndroidExecutionHost.VISIBLE_FOREGROUND_SERVICE,
            handoff.executionHost
        )
        assertEquals(
            AmperAgentContinuationWakeDisposition.READY_FOR_EXPLICIT_ADVANCE,
            handoff.wakeDisposition
        )
        assertTrue(handoff.requiresVisibleNotification)
        assertFalse(handoff.persistedAcrossReboot)
    }

    @Test
    fun persistedJobUsesRebootPersistentSchedulerHandoff() {
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.PERSISTED_JOB,
                state = AmperAgentTaskState.CHECKPOINTED
            )
        )

        assertEquals(
            AmperAgentAndroidExecutionHost.PERSISTED_JOB_SCHEDULER,
            handoff.executionHost
        )
        assertFalse(handoff.requiresVisibleNotification)
        assertTrue(handoff.persistedAcrossReboot)
    }

    @Test
    fun waitingApprovalWakeCannotBecomeAutomaticAdvance() {
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.FOREGROUND_CONTINUATION,
                state = AmperAgentTaskState.WAITING_APPROVAL,
                waitingApprovalStepIndex = 1
            )
        )

        assertEquals(
            AmperAgentContinuationWakeDisposition.WAITING_GOVERNED_APPROVAL,
            handoff.wakeDisposition
        )
    }

    @Test
    fun terminalWakeIsNoOp() {
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.PERSISTED_JOB,
                state = AmperAgentTaskState.COMPLETED
            )
        )

        assertEquals(
            AmperAgentContinuationWakeDisposition.TERMINAL_NOOP,
            handoff.wakeDisposition
        )
    }

    @Test
    fun encodedHandoffRoundTripIsIdentityBound() {
        val original = envelope(
            mode = OmegaBackgroundMode.PERSISTED_JOB,
            state = AmperAgentTaskState.CHECKPOINTED
        )
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(original)
        val decoded = AmperAgentAndroidContinuationHandoffPolicy
            .verifyAndDecode(handoff)
            .getOrThrow()

        assertEquals(original, decoded)
        assertEquals(64, handoff.envelopeSha256.length)
        assertTrue(handoff.dedupeKey.contains(original.taskId))
        assertTrue(handoff.dedupeKey.contains(original.planId.value))
    }

    @Test
    fun tamperedTransportEnvelopeFailsBeforeAndroidRestore() {
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.FOREGROUND_CONTINUATION,
                state = AmperAgentTaskState.CHECKPOINTED
            )
        )
        val tampered = handoff.copy(
            encodedEnvelope = handoff.encodedEnvelope + "\n"
        )

        val result = runCatching {
            AmperAgentAndroidContinuationHandoffPolicy.verifyAndDecode(tampered)
                .getOrThrow()
        }

        assertTrue(result.isFailure)
    }

    private fun envelope(
        mode: OmegaBackgroundMode,
        state: AmperAgentTaskState,
        waitingApprovalStepIndex: Int? = null
    ) = AmperAgentContinuationEnvelope(
        taskId = "user-task",
        planId = PlanId("plan-1"),
        backgroundMode = mode,
        taskState = state,
        completedSteps = if (state == AmperAgentTaskState.COMPLETED) 1 else 0,
        totalSteps = 1,
        planStateSha256 = "a".repeat(64),
        waitingApprovalStepIndex = waitingApprovalStepIndex,
        checkpointedAtEpochMs = 100L
    )
}
