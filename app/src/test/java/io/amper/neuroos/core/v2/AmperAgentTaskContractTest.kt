package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentTaskContractTest {
    @Test
    fun shortUserTaskRemainsUiBoundWithoutCheckpoint() {
        val admission = AmperAgentTaskAdmissionPolicy.admit(
            userTask(mustSurviveUiExit = false, canBeDeferred = false)
        )

        assertEquals(OmegaBackgroundMode.UI_BOUND, admission.backgroundMode)
        assertFalse(admission.checkpointRequired)
        assertTrue(admission.toolAuthorityRemainsExternal)
        assertTrue(admission.auditRequired)
    }

    @Test
    fun longUserTaskCanContinueAfterUiExitAndMustCheckpoint() {
        val admission = AmperAgentTaskAdmissionPolicy.admit(
            userTask(mustSurviveUiExit = true, canBeDeferred = false)
        )

        assertEquals(
            OmegaBackgroundMode.FOREGROUND_CONTINUATION,
            admission.backgroundMode
        )
        assertTrue(admission.checkpointRequired)
    }

    @Test
    fun deferredUserTaskUsesPersistedJob() {
        val admission = AmperAgentTaskAdmissionPolicy.admit(
            userTask(mustSurviveUiExit = true, canBeDeferred = true)
        )

        assertEquals(OmegaBackgroundMode.PERSISTED_JOB, admission.backgroundMode)
        assertTrue(admission.checkpointRequired)
    }

    @Test
    fun proactiveTaskRequiresTriggerAndUsesEventWake() {
        val request = AmperAgentTaskRequest(
            taskId = "proactive-weather-check",
            origin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER,
            objective = "Check the trigger condition and execute only governed allowed work.",
            allowedCapabilities = setOf(CapabilityId("internet.read")),
            expectedRuntimeMs = 5_000L,
            mustSurviveUiExit = true,
            canBeDeferred = true,
            createdAtEpochMs = 100L,
            trigger = AmperAgentTrigger(
                triggerId = "weather.condition",
                source = "scheduler",
                observedAtEpochMs = 100L,
                payloadDigest = "a".repeat(64)
            )
        )

        val admission = AmperAgentTaskAdmissionPolicy.admit(request)

        assertEquals(OmegaBackgroundMode.EVENT_WAKE, admission.backgroundMode)
        assertTrue(admission.checkpointRequired)
        assertFalse(request.userInitiated)
    }

    @Test
    fun proactiveOriginWithoutTriggerFailsClosed() {
        val result = runCatching {
            AmperAgentTaskRequest(
                taskId = "invalid-proactive",
                origin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER,
                objective = "Do not admit without trigger provenance.",
                allowedCapabilities = emptySet(),
                expectedRuntimeMs = 1_000L,
                mustSurviveUiExit = true,
                canBeDeferred = true,
                createdAtEpochMs = 1L
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun lifecycleCannotEscapeTerminalStateOrSkipGovernedFlow() {
        assertTrue(
            AmperAgentTaskLifecycle.canTransition(
                AmperAgentTaskState.RUNNING,
                AmperAgentTaskState.WAITING_APPROVAL
            )
        )
        assertTrue(
            AmperAgentTaskLifecycle.canTransition(
                AmperAgentTaskState.WAITING_APPROVAL,
                AmperAgentTaskState.READY
            )
        )
        assertFalse(
            AmperAgentTaskLifecycle.canTransition(
                AmperAgentTaskState.COMPLETED,
                AmperAgentTaskState.RUNNING
            )
        )
        assertFalse(
            AmperAgentTaskLifecycle.canTransition(
                AmperAgentTaskState.ADMITTED,
                AmperAgentTaskState.COMPLETED
            )
        )
    }

    private fun userTask(
        mustSurviveUiExit: Boolean,
        canBeDeferred: Boolean
    ) = AmperAgentTaskRequest(
        taskId = "user-task",
        origin = AmperAgentTaskOrigin.USER_REQUEST,
        objective = "Perform the user requested governed task.",
        allowedCapabilities = setOf(CapabilityId("reasoning")),
        expectedRuntimeMs = 30_000L,
        mustSurviveUiExit = mustSurviveUiExit,
        canBeDeferred = canBeDeferred,
        createdAtEpochMs = 1L
    )
}
