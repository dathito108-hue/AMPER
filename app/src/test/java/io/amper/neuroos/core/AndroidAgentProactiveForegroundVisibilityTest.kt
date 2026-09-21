package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentProactiveForegroundVisibilityTest {
    @Test
    fun lifecycleFailureFailsClosedAndSkipsAttentionReconcile() {
        var attentionCalls = 0
        val coordinator = AndroidAgentProactiveForegroundVisibilityCoordinator(
            reconcileLifecycle = {
                Result.failure(IllegalStateException("lifecycle unavailable"))
            },
            reconcileAttention = {
                attentionCalls += 1
                Result.success(attentionReport())
            }
        )

        val result = coordinator.reconcileOnResume()

        assertTrue(result.isFailure)
        assertEquals(0, attentionCalls)
    }

    @Test
    fun attentionFailureNeverGatesCanonicalLifecycleRefresh() {
        var lifecycleCalls = 0
        var attentionCalls = 0
        val coordinator = AndroidAgentProactiveForegroundVisibilityCoordinator(
            reconcileLifecycle = {
                lifecycleCalls += 1
                Result.success(lifecycleReport())
            },
            reconcileAttention = {
                attentionCalls += 1
                Result.failure(IllegalStateException("notification unavailable"))
            }
        )

        val report = coordinator.reconcileOnResume().getOrThrow()

        assertEquals(1, lifecycleCalls)
        assertEquals(1, attentionCalls)
        assertFalse(report.attentionReconciled)
        assertEquals(null, report.attention)
        assertEquals(3, report.lifecycle.tracked)
    }

    @Test
    fun successfulResumeReusesExistingLifecycleAndAttentionReports() {
        val coordinator = AndroidAgentProactiveForegroundVisibilityCoordinator(
            reconcileLifecycle = { Result.success(lifecycleReport()) },
            reconcileAttention = { Result.success(attentionReport()) }
        )

        val report = coordinator.reconcileOnResume().getOrThrow()

        assertTrue(report.attentionReconciled)
        assertEquals(3, report.lifecycle.tracked)
        assertEquals(2, report.attention?.tracked)
        assertEquals(1, report.attention?.cancelled)
    }

    @Test
    fun coordinatorExposesResumeEntryPointOnlyAndNoPollingApi() {
        val names = AndroidAgentProactiveForegroundVisibilityCoordinator::class.java
            .declaredMethods
            .map { it.name.lowercase() }

        assertTrue(names.any { it.contains("reconcileonresume") })
        assertTrue(names.none { it.contains("poll") })
        assertTrue(names.none { it.contains("timer") })
        assertTrue(names.none { it.contains("interval") })
        assertTrue(names.none { it.contains("schedule") })
    }

    private fun lifecycleReport() =
        AndroidAgentProactiveTaskLifecycleReconcileReport(
            tracked = 3,
            runnableScheduled = 1,
            nonRunnableCancelled = 1,
            missingPlans = 1
        )

    private fun attentionReport() =
        AndroidAgentProactiveAttentionReconcileReport(
            tracked = 2,
            posted = 0,
            cancelled = 1,
            suppressed = 1,
            acknowledged = 0
        )
}
