package io.amper.neuroos.core

import io.amper.neuroos.core.v2.AmperAgentPersistedProactiveTriggerSource
import io.amper.neuroos.core.v2.AmperAgentProactiveTriggerSource
import io.amper.neuroos.core.v2.AmperAgentProactiveTriggerSourceKind
import io.amper.neuroos.core.v2.AmperAgentTaskOrigin
import io.amper.neuroos.core.v2.MemoryBackedAmperAgentProactiveTriggerSourceRegistry
import io.amper.neuroos.core.v2.OmegaBackgroundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentProactiveTriggerSourceSchedulerTest {
    @Test
    fun onlyEnabledScheduledWindowReceivesAndroidSchedule() {
        val scheduled = state(source())
        val appLocal = state(
            source(
                sourceId = "local.example",
                kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
                minimumIntervalMs =
                    AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS
            )
        )
        val disabled = state(source(enabled = false))

        val plan = AndroidAgentScheduledTriggerSourcePolicy.plan(scheduled)

        assertEquals(scheduled.source.sourceId, requireNotNull(plan).sourceId)
        assertEquals(scheduled.source.minimumIntervalMs, plan.intervalMs)
        assertTrue(plan.persistedAcrossReboot)
        assertTrue(plan.requiresBatteryNotLow)
        assertTrue(plan.requiresStorageNotLow)
        assertEquals(null, AndroidAgentScheduledTriggerSourcePolicy.plan(appLocal))
        assertEquals(null, AndroidAgentScheduledTriggerSourcePolicy.plan(disabled))
    }

    @Test
    fun scheduledObservationIdentityIsDeterministicAndRevisionBound() {
        val first = state(source(configurationSha256 = "a".repeat(64)))
        val sameA = AndroidAgentScheduledTriggerSourcePolicy
            .observation(first, 1_000_000L)
            .getOrThrow()
        val sameB = AndroidAgentScheduledTriggerSourcePolicy
            .observation(first, 1_000_000L)
            .getOrThrow()
        val revised = state(source(configurationSha256 = "b".repeat(64)))
        val revisedObservation = AndroidAgentScheduledTriggerSourcePolicy
            .observation(revised, 1_000_000L)
            .getOrThrow()
        val later = AndroidAgentScheduledTriggerSourcePolicy
            .observation(first, 2_000_000L)
            .getOrThrow()

        assertEquals(sameA, sameB)
        assertEquals(64, sameA.payloadDigest.length)
        assertTrue(sameA.payloadDigest != revisedObservation.payloadDigest)
        assertTrue(sameA.payloadDigest != later.payloadDigest)
    }

    @Test
    fun triggerSourceJobIdentityIsStableNamespacedAndSourceSpecific() {
        val first = AndroidAgentTriggerSourceJobIdentity.jobIdFor("monitor.example")
        val same = AndroidAgentTriggerSourceJobIdentity.jobIdFor("monitor.example")
        val other = AndroidAgentTriggerSourceJobIdentity.jobIdFor("monitor.other")

        assertEquals(first, same)
        assertTrue(first != other)
        assertTrue(AndroidAgentTriggerSourceJobIdentity.owns(first))
        assertTrue(AndroidAgentTriggerSourceJobIdentity.owns(other))
        assertFalse(AndroidAgentTriggerSourceJobIdentity.owns(0x32000001))
        assertFalse(AndroidAgentTriggerSourceJobIdentity.owns(0x21000001))
    }

    @Test
    fun controllerReconcilesUpsertDisableAndRemoveWithoutExecutionAuthority() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val scheduler = FakeScheduler()
        val controller = AndroidAgentProactiveTriggerSourceController(
            registry = registry,
            scheduler = scheduler
        )
        val source = source()

        val stored = controller.upsert(source, updatedAtEpochMs = 10L).getOrThrow()

        assertEquals(source, stored.source)
        assertEquals(1, scheduler.reconcileCalls)
        assertEquals(1, scheduler.lastStates.size)
        assertEquals(source.sourceId, scheduler.lastStates.single().source.sourceId)

        val disabled = controller
            .setEnabled(source.sourceId, false, updatedAtEpochMs = 20L)
            .getOrThrow()

        assertFalse(disabled.source.enabled)
        assertEquals(2, scheduler.reconcileCalls)
        assertTrue(scheduler.lastStates.none { it.source.enabled })

        assertTrue(controller.remove(source.sourceId).getOrThrow())
        assertEquals(listOf(source.sourceId), scheduler.cancelled)
        assertEquals(3, scheduler.reconcileCalls)
        assertTrue(scheduler.lastStates.isEmpty())
    }

    @Test
    fun appLocalEventIsAcceptedOnlyThroughEventDrivenAdapter() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val scheduler = FakeScheduler()
        val controller = AndroidAgentProactiveTriggerSourceController(
            registry = registry,
            scheduler = scheduler
        )
        val local = source(
            sourceId = "local.example",
            kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
            minimumIntervalMs =
                AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS
        )
        controller.upsert(local, updatedAtEpochMs = 1L).getOrThrow()

        val accepted = controller.observeAppLocalEvent(
            sourceId = local.sourceId,
            observedAtEpochMs = 1_000_000L,
            payloadDigest = "d".repeat(64)
        ).getOrThrow()

        assertEquals(AmperAgentTaskOrigin.PROACTIVE_TRIGGER, accepted.qualified.admission.request.origin)
        assertEquals(OmegaBackgroundMode.EVENT_WAKE, accepted.qualified.admission.backgroundMode)
        assertEquals(local.sourceId, accepted.qualified.sourceId)
        assertEquals(0, scheduler.lastDesiredScheduled)

        val scheduled = source(sourceId = "scheduled.example")
        controller.upsert(scheduled, updatedAtEpochMs = 2L).getOrThrow()

        val wrongAdapter = controller.observeAppLocalEvent(
            sourceId = scheduled.sourceId,
            observedAtEpochMs = 2_000_000L,
            payloadDigest = "e".repeat(64)
        )
        assertTrue(wrongAdapter.isFailure)
    }

    @Test
    fun disabledScheduledSourceProducesNoObservation() {
        val disabled = state(source(enabled = false))

        val result = AndroidAgentScheduledTriggerSourcePolicy
            .observation(disabled, 1_000_000L)

        assertTrue(result.isFailure)
    }

    private fun state(
        source: AmperAgentProactiveTriggerSource
    ) = AmperAgentPersistedProactiveTriggerSource(
        source = source,
        updatedAtEpochMs = 1L
    )

    private fun source(
        sourceId: String = "monitor.example",
        kind: AmperAgentProactiveTriggerSourceKind =
            AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW,
        minimumIntervalMs: Long =
            AmperAgentProactiveTriggerSource.SCHEDULED_WINDOW_MIN_INTERVAL_MS,
        configurationSha256: String = "a".repeat(64),
        enabled: Boolean = true
    ) = AmperAgentProactiveTriggerSource(
        sourceId = sourceId,
        configurationId = "config." + sourceId,
        kind = kind,
        objective = "Observe one bounded user-configured condition.",
        allowedCapabilities = setOf(CapabilityId("reasoning")),
        expectedRuntimeMs = 30_000L,
        minimumIntervalMs = minimumIntervalMs,
        configurationSha256 = configurationSha256,
        userConfigured = true,
        enabled = enabled
    )

    private class FakeScheduler : AndroidAgentTriggerSourceScheduleReconciler {
        var reconcileCalls: Int = 0
        var lastStates: List<AmperAgentPersistedProactiveTriggerSource> = emptyList()
        var lastDesiredScheduled: Int = 0
        val cancelled = mutableListOf<String>()

        override fun reconcile(
            states: List<AmperAgentPersistedProactiveTriggerSource>
        ): Result<AndroidAgentTriggerSourceReconcileReport> {
            reconcileCalls += 1
            lastStates = states
            lastDesiredScheduled = states.count {
                AndroidAgentScheduledTriggerSourcePolicy.plan(it) != null
            }
            return Result.success(
                AndroidAgentTriggerSourceReconcileReport(
                    registeredSources = states.size,
                    desiredScheduledSources = lastDesiredScheduled,
                    scheduledSources = lastDesiredScheduled,
                    cancelledStaleJobs = 0,
                    scheduleFailures = 0
                )
            )
        }

        override fun cancelSource(sourceId: String) {
            cancelled += sourceId
        }
    }
}
