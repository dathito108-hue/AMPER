package io.amper.neuroos.core

import android.os.PowerManager
import io.amper.neuroos.core.v2.AmperAgentEventWakeEnvelope
import io.amper.neuroos.core.v2.AmperAgentEventWakeHandoffPolicy
import io.amper.neuroos.core.v2.AmperAgentTaskState
import io.amper.neuroos.core.v2.AmperAgentTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentEventWakeSchedulerTest {
    @Test
    fun freshCheckpointKeepsStableJobIdentityForSameTriggerObservation() {
        val first = AmperAgentEventWakeHandoffPolicy.create(
            envelope(
                planStateSha256 = "a".repeat(64),
                completedSteps = 0,
                checkpointedAtEpochMs = 100L
            )
        )
        val next = AmperAgentEventWakeHandoffPolicy.create(
            envelope(
                planStateSha256 = "b".repeat(64),
                completedSteps = 1,
                checkpointedAtEpochMs = 200L
            )
        )

        assertEquals(first.dedupeKey, next.dedupeKey)
        assertEquals(
            AndroidAgentEventWakeJobIdentity.jobIdFor(first),
            AndroidAgentEventWakeJobIdentity.jobIdFor(next)
        )
    }

    @Test
    fun differentTriggerPayloadGetsDifferentJobIdentity() {
        val first = AmperAgentEventWakeHandoffPolicy.create(
            envelope(planStateSha256 = "a".repeat(64))
        )
        val second = AmperAgentEventWakeHandoffPolicy.create(
            envelope(
                planStateSha256 = "a".repeat(64),
                payloadDigest = "c".repeat(64)
            )
        )

        assertTrue(first.dedupeKey != second.dedupeKey)
        assertTrue(
            AndroidAgentEventWakeJobIdentity.jobIdFor(first) !=
                AndroidAgentEventWakeJobIdentity.jobIdFor(second)
        )
    }

    @Test
    fun normalResourcesAllowImmediatePersistedBatteryGuardedWake() {
        val plan = AndroidAgentEventWakeResourcePolicy.plan(
            ResourceBudget(
                maxConcurrentAgents = 2,
                memoryMb = 1024,
                thermalClass = PowerManager.THERMAL_STATUS_LIGHT
            )
        )

        assertEquals(0L, plan.minimumLatencyMs)
        assertTrue(plan.requiresBatteryNotLow)
        assertTrue(plan.persistedAcrossReboot)
    }

    @Test
    fun lowMemoryDelaysProactiveWake() {
        val plan = AndroidAgentEventWakeResourcePolicy.plan(
            ResourceBudget(
                maxConcurrentAgents = 1,
                memoryMb = 320,
                thermalClass = PowerManager.THERMAL_STATUS_LIGHT
            )
        )

        assertEquals(2L * 60L * 1000L, plan.minimumLatencyMs)
    }

    @Test
    fun severeAndCriticalThermalPressureBackOffWakeCadence() {
        val severe = AndroidAgentEventWakeResourcePolicy.plan(
            ResourceBudget(
                maxConcurrentAgents = 1,
                memoryMb = 1024,
                thermalClass = PowerManager.THERMAL_STATUS_SEVERE
            )
        )
        val critical = AndroidAgentEventWakeResourcePolicy.plan(
            ResourceBudget(
                maxConcurrentAgents = 1,
                memoryMb = 1024,
                thermalClass = PowerManager.THERMAL_STATUS_CRITICAL
            )
        )

        assertEquals(5L * 60L * 1000L, severe.minimumLatencyMs)
        assertEquals(15L * 60L * 1000L, critical.minimumLatencyMs)
    }

    private fun envelope(
        planStateSha256: String,
        completedSteps: Int = 0,
        checkpointedAtEpochMs: Long = 100L,
        payloadDigest: String = "d".repeat(64)
    ) = AmperAgentEventWakeEnvelope(
        taskId = "proactive-task",
        planId = PlanId("proactive-plan"),
        trigger = AmperAgentTrigger(
            triggerId = "monitor.example",
            source = "canonical-monitor",
            observedAtEpochMs = 1L,
            payloadDigest = payloadDigest
        ),
        taskState = AmperAgentTaskState.CHECKPOINTED,
        completedSteps = completedSteps,
        totalSteps = 2,
        planStateSha256 = planStateSha256,
        checkpointedAtEpochMs = checkpointedAtEpochMs
    )
}
