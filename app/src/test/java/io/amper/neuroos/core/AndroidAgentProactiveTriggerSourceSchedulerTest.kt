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
    fun pendingDispatchJobIdentityIsStableAndSeparateFromSourceAndEventWakeNamespaces() {
        val first = AndroidAgentPendingTriggerDispatchJobIdentity.jobIdFor("monitor.example")
        val same = AndroidAgentPendingTriggerDispatchJobIdentity.jobIdFor("monitor.example")
        val other = AndroidAgentPendingTriggerDispatchJobIdentity.jobIdFor("monitor.other")

        assertEquals(first, same)
        assertTrue(first != other)
        assertTrue(AndroidAgentPendingTriggerDispatchJobIdentity.owns(first))
        assertFalse(AndroidAgentPendingTriggerDispatchJobIdentity.owns(
            AndroidAgentTriggerSourceJobIdentity.jobIdFor("monitor.example")
        ))
        assertFalse(AndroidAgentPendingTriggerDispatchJobIdentity.owns(0x32000001))
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
        val pendingDispatch = FakePendingDispatch()
        val controller = AndroidAgentProactiveTriggerSourceController(
            registry = registry,
            scheduler = scheduler,
            pendingDispatch = pendingDispatch
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
        assertEquals(listOf(local.sourceId to 0), pendingDispatch.requested)

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
    fun foregroundReconciliationRequestsPendingWorkAndEnableResumesIt() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val local = source(
            sourceId = "local.recovery",
            kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
            minimumIntervalMs =
                AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS
        )
        registry.upsert(local, updatedAtEpochMs = 1L)
        registry.accept(
            io.amper.neuroos.core.v2.AmperAgentProactiveTriggerObservation(
                sourceId = local.sourceId,
                observedAtEpochMs = 1_000_000L,
                payloadDigest = "f".repeat(64)
            )
        ).getOrThrow()

        val scheduler = FakeScheduler()
        val pendingDispatch = FakePendingDispatch()
        val controller = AndroidAgentProactiveTriggerSourceController(
            registry = registry,
            scheduler = scheduler,
            pendingDispatch = pendingDispatch
        )

        controller.reconcileAll().getOrThrow()
        assertEquals(listOf(local.sourceId to 0), pendingDispatch.requested)

        controller
            .setEnabled(local.sourceId, false, updatedAtEpochMs = 2_000_000L)
            .getOrThrow()
        assertTrue(local.sourceId in pendingDispatch.cancelled)

        controller
            .setEnabled(local.sourceId, true, updatedAtEpochMs = 3_000_000L)
            .getOrThrow()
        assertEquals(
            listOf(local.sourceId to 0, local.sourceId to 0),
            pendingDispatch.requested
        )
    }

    @Test
    fun pendingFifoBlocksRemovalAndSuccessfulRemovalCancelsBothAndroidJobs() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val local = source(
            sourceId = "local.remove",
            kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
            minimumIntervalMs =
                AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS
        )
        registry.upsert(local, updatedAtEpochMs = 1L)
        val accepted = registry.accept(
            io.amper.neuroos.core.v2.AmperAgentProactiveTriggerObservation(
                sourceId = local.sourceId,
                observedAtEpochMs = 1_000_000L,
                payloadDigest = "9".repeat(64)
            )
        ).getOrThrow()

        val scheduler = FakeScheduler()
        val pendingDispatch = FakePendingDispatch()
        val controller = AndroidAgentProactiveTriggerSourceController(
            registry = registry,
            scheduler = scheduler,
            pendingDispatch = pendingDispatch
        )

        assertFalse(controller.remove(local.sourceId).getOrThrow())
        assertTrue(scheduler.cancelled.isEmpty())
        assertTrue(pendingDispatch.cancelled.isEmpty())

        registry.acknowledgePending(
            local.sourceId,
            accepted.qualified.observationIdentitySha256,
            updatedAtEpochMs = 2_000_000L
        ).getOrThrow()

        assertTrue(controller.remove(local.sourceId).getOrThrow())
        assertEquals(listOf(local.sourceId), scheduler.cancelled)
        assertEquals(listOf(local.sourceId), pendingDispatch.cancelled)
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

    private class FakePendingDispatch : AndroidAgentPendingTriggerDispatchRequester {
        val requested = mutableListOf<Pair<String, Int>>()
        val cancelled = mutableListOf<String>()

        override fun request(sourceId: String, attempt: Int): Result<Boolean> {
            requested += sourceId to attempt
            return Result.success(true)
        }

        override fun cancel(sourceId: String) {
            cancelled += sourceId
        }
    }

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
