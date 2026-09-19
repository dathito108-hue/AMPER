package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexDeferredJobPlanningTest {
    @Test
    fun pendingTicketPlansPersistentChargingIdleJob() {
        val ticket = ReflexLearningMaintenanceTicket(
            evidenceDigest = reflexLinearSha256("os-job-evidence"),
            reasons = setOf(ReflexLearningMaintenanceReason.RESOURCE_DEFERRED),
            priority = 0.8,
            firstQueuedAtEpochMs = 1_000L,
            lastQueuedAtEpochMs = 2_000L,
            notBeforeEpochMs = 7_500L,
            attempts = 1
        )

        val plan = ReflexDeferredJobPlanner.plan(ticket, nowEpochMs = 5_000L)

        assertTrue(plan.scheduled)
        assertEquals(2_500L, plan.minimumLatencyMs)
        assertTrue(plan.requiresCharging)
        assertTrue(plan.requiresDeviceIdle)
        assertTrue(plan.requiresBatteryNotLow)
        assertTrue(plan.requiresStorageNotLow)
        assertTrue(plan.persistedAcrossReboot)
    }

    @Test
    fun overdueTicketSchedulesImmediatelyButKeepsAllResourceConstraints() {
        val ticket = ReflexLearningMaintenanceTicket(
            evidenceDigest = reflexLinearSha256("os-job-overdue"),
            reasons = setOf(ReflexLearningMaintenanceReason.HARD_EVIDENCE),
            priority = 1.0,
            firstQueuedAtEpochMs = 1_000L,
            lastQueuedAtEpochMs = 2_000L,
            notBeforeEpochMs = 3_000L,
            attempts = 2
        )

        val plan = ReflexDeferredJobPlanner.plan(ticket, nowEpochMs = 10_000L)

        assertTrue(plan.scheduled)
        assertEquals(0L, plan.minimumLatencyMs)
        assertTrue(plan.requiresCharging)
        assertTrue(plan.requiresDeviceIdle)
        assertTrue(plan.requiresBatteryNotLow)
        assertTrue(plan.requiresStorageNotLow)
    }

    @Test
    fun emptyQueueCancelsOsJobPlan() {
        val plan = ReflexDeferredJobPlanner.plan(
            ticket = null,
            nowEpochMs = 10_000L
        )

        assertFalse(plan.scheduled)
        assertEquals(0L, plan.minimumLatencyMs)
        assertTrue(plan.persistedAcrossReboot)
    }
}
