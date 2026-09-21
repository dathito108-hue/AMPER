package io.amper.neuroos.core.v2

import io.amper.neuroos.core.PlanId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentAndroidContinuationDispatchTest {
    @Test
    fun readyWakeCallsExecutionPortExactlyOnce() {
        var calls = 0
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.FOREGROUND_CONTINUATION,
                state = AmperAgentTaskState.CHECKPOINTED
            )
        )
        val port = AmperAgentAndroidContinuationExecutionPort { observed, decoded ->
            calls += 1
            assertEquals(handoff, observed)
            assertEquals("user-task", decoded.taskId)
            Result.success(
                AmperAgentAndroidHostExecutionResult(
                    state = AmperAgentAndroidHostExecutionState.ADVANCED,
                    detail = "one canonical step advanced"
                )
            )
        }

        val result = AmperAgentAndroidContinuationHostDispatcher
            .dispatch(handoff, port)
            .getOrThrow()

        assertEquals(1, calls)
        assertEquals(AmperAgentAndroidHostExecutionState.ADVANCED, result.state)
    }

    @Test
    fun waitingApprovalNeverCallsExecutionPort() {
        var calls = 0
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.PERSISTED_JOB,
                state = AmperAgentTaskState.WAITING_APPROVAL,
                waitingApprovalStepIndex = 1
            )
        )
        val port = AmperAgentAndroidContinuationExecutionPort { _, _ ->
            calls += 1
            error("approval-blocked continuation must not execute")
        }

        val result = AmperAgentAndroidContinuationHostDispatcher
            .dispatch(handoff, port)
            .getOrThrow()

        assertEquals(0, calls)
        assertEquals(
            AmperAgentAndroidHostExecutionState.WAITING_APPROVAL,
            result.state
        )
    }

    @Test
    fun terminalWakeNeverCallsExecutionPort() {
        var calls = 0
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.PERSISTED_JOB,
                state = AmperAgentTaskState.COMPLETED
            )
        )
        val port = AmperAgentAndroidContinuationExecutionPort { _, _ ->
            calls += 1
            error("terminal continuation must not execute")
        }

        val result = AmperAgentAndroidContinuationHostDispatcher
            .dispatch(handoff, port)
            .getOrThrow()

        assertEquals(0, calls)
        assertEquals(
            AmperAgentAndroidHostExecutionState.TERMINAL_NOOP,
            result.state
        )
    }

    @Test
    fun unavailableCanonicalExecutionPortRequestsRetryOnlyForReadyWake() {
        val ready = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.PERSISTED_JOB,
                state = AmperAgentTaskState.CHECKPOINTED
            )
        )

        val result = AmperAgentAndroidContinuationHostDispatcher
            .dispatch(ready, null)
            .getOrThrow()

        assertEquals(
            AmperAgentAndroidHostExecutionState.RETRY_LATER,
            result.state
        )
        assertTrue(result.shouldReschedule)
    }

    @Test
    fun tamperedHandoffFailsBeforeExecutionPort() {
        var calls = 0
        val original = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.FOREGROUND_CONTINUATION,
                state = AmperAgentTaskState.CHECKPOINTED
            )
        )
        val port = AmperAgentAndroidContinuationExecutionPort { _, _ ->
            calls += 1
            Result.success(
                AmperAgentAndroidHostExecutionResult(
                    state = AmperAgentAndroidHostExecutionState.ADVANCED,
                    detail = "must not happen"
                )
            )
        }

        val result = runCatching {
            val tampered = original.copy(
                encodedEnvelope = original.encodedEnvelope + "\n"
            )
            AmperAgentAndroidContinuationHostDispatcher
                .dispatch(tampered, port)
                .getOrThrow()
        }

        assertTrue(result.isFailure)
        assertEquals(0, calls)
    }

    @Test
    fun readyWakeMayAcquireCanonicalColdExecutionOnlyAfterVerification() {
        var fallbackCalls = 0
        var executionCalls = 0
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.PERSISTED_JOB,
                state = AmperAgentTaskState.CHECKPOINTED
            )
        )
        val coldPort = AmperAgentAndroidContinuationExecutionPort { _, decoded ->
            executionCalls += 1
            assertEquals("user-task", decoded.taskId)
            Result.success(
                AmperAgentAndroidHostExecutionResult(
                    state = AmperAgentAndroidHostExecutionState.ADVANCED,
                    detail = "cold canonical graph advanced one step"
                )
            )
        }

        val result = AmperAgentAndroidContinuationHostDispatcher
            .dispatch(
                handoff = handoff,
                execution = null,
                executionFallback = {
                    fallbackCalls += 1
                    Result.success(coldPort)
                }
            )
            .getOrThrow()

        assertEquals(1, fallbackCalls)
        assertEquals(1, executionCalls)
        assertEquals(AmperAgentAndroidHostExecutionState.ADVANCED, result.state)
    }

    @Test
    fun waitingApprovalNeverAcquiresColdExecutionFallback() {
        var fallbackCalls = 0
        val handoff = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.PERSISTED_JOB,
                state = AmperAgentTaskState.WAITING_APPROVAL,
                waitingApprovalStepIndex = 1
            )
        )

        val result = AmperAgentAndroidContinuationHostDispatcher
            .dispatch(
                handoff = handoff,
                execution = null,
                executionFallback = {
                    fallbackCalls += 1
                    error("WAITING_APPROVAL must not bootstrap canonical execution")
                }
            )
            .getOrThrow()

        assertEquals(0, fallbackCalls)
        assertEquals(
            AmperAgentAndroidHostExecutionState.WAITING_APPROVAL,
            result.state
        )
    }

    @Test
    fun tamperedReadyHandoffNeverAcquiresColdExecutionFallback() {
        var fallbackCalls = 0
        val original = AmperAgentAndroidContinuationHandoffPolicy.create(
            envelope(
                mode = OmegaBackgroundMode.PERSISTED_JOB,
                state = AmperAgentTaskState.CHECKPOINTED
            )
        )
        val tampered = original.copy(
            encodedEnvelope = original.encodedEnvelope + "\n"
        )

        val result = AmperAgentAndroidContinuationHostDispatcher.dispatch(
            handoff = tampered,
            execution = null,
            executionFallback = {
                fallbackCalls += 1
                error("unverified handoff must not bootstrap canonical execution")
            }
        )

        assertTrue(result.isFailure)
        assertEquals(0, fallbackCalls)
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
