package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentProactiveTriggerSourceTest {
    @Test
    fun scheduledWindowQualifiesToCanonicalEventWakeAdmission() {
        val source = source(
            kind = AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW,
            minimumIntervalMs =
                AmperAgentProactiveTriggerSource.SCHEDULED_WINDOW_MIN_INTERVAL_MS
        )
        val observation = observation()

        val qualified = AmperAgentProactiveTriggerSourcePolicy
            .qualify(source, observation)
            .getOrThrow()

        assertEquals(AmperAgentTaskOrigin.PROACTIVE_TRIGGER, qualified.admission.request.origin)
        assertEquals(OmegaBackgroundMode.EVENT_WAKE, qualified.admission.backgroundMode)
        assertEquals(source.objective, qualified.admission.request.objective)
        assertEquals(source.allowedCapabilities, qualified.admission.request.allowedCapabilities)
        assertEquals(source.sourceId, qualified.admission.request.trigger?.triggerId)
        assertEquals(observation.payloadDigest, qualified.admission.request.trigger?.payloadDigest)
        assertTrue(qualified.admission.checkpointRequired)
        assertTrue(qualified.admission.toolAuthorityRemainsExternal)
        assertTrue(qualified.admission.auditRequired)
    }

    @Test
    fun appLocalEventUsesExplicitCooldownWithoutPollingKind() {
        assertEquals(
            setOf(
                AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW,
                AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT
            ),
            AmperAgentProactiveTriggerSourceKind.entries.toSet()
        )
        val source = source(
            kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
            minimumIntervalMs =
                AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS
        )

        val qualified = AmperAgentProactiveTriggerSourcePolicy
            .qualify(source, observation())
            .getOrThrow()

        assertEquals(
            AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
            qualified.sourceKind
        )
        assertTrue(
            qualified.admission.request.trigger
                ?.source
                ?.startsWith("user-configured:app_local_event:") == true
        )
    }

    @Test
    fun cooldownRejectsBurstObservation() {
        val source = source(
            kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
            minimumIntervalMs = 60_000L
        )
        val observation = observation(observedAtEpochMs = 100_000L)

        val result = AmperAgentProactiveTriggerSourcePolicy.qualify(
            source = source,
            observation = observation,
            lastAcceptedObservationAtEpochMs = 50_001L
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun cooldownBoundaryIsAccepted() {
        val source = source(
            kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
            minimumIntervalMs = 60_000L
        )
        val observation = observation(observedAtEpochMs = 110_000L)

        val result = AmperAgentProactiveTriggerSourcePolicy.qualify(
            source = source,
            observation = observation,
            lastAcceptedObservationAtEpochMs = 50_000L
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun scheduledWindowCannotConfigureHighFrequencyCadence() {
        val result = runCatching {
            source(
                kind = AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW,
                minimumIntervalMs = 60_000L
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun appLocalEventCannotConfigureSubMinuteBurstCadence() {
        val result = runCatching {
            source(
                kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
                minimumIntervalMs = 59_999L
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun disabledOrNonUserConfiguredSourceFailsClosed() {
        val disabled = source(enabled = false)
        val disabledResult = AmperAgentProactiveTriggerSourcePolicy
            .qualify(disabled, observation())
        assertTrue(disabledResult.isFailure)

        val nonUser = runCatching {
            source(userConfigured = false)
        }
        assertTrue(nonUser.isFailure)
    }

    @Test
    fun observationMustMatchConfiguredSource() {
        val result = AmperAgentProactiveTriggerSourcePolicy.qualify(
            source = source(),
            observation = observation(sourceId = "different.source")
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun identicalObservationIsDeterministicAndConfigRevisionChangesIdentity() {
        val source = source(configurationSha256 = "a".repeat(64))
        val observation = observation()
        val first = AmperAgentProactiveTriggerSourcePolicy
            .qualify(source, observation)
            .getOrThrow()
        val second = AmperAgentProactiveTriggerSourcePolicy
            .qualify(source, observation)
            .getOrThrow()

        assertEquals(first.observationIdentitySha256, second.observationIdentitySha256)
        assertEquals(first.admission.request.taskId, second.admission.request.taskId)

        val revised = AmperAgentProactiveTriggerSourcePolicy
            .qualify(
                source(configurationSha256 = "b".repeat(64)),
                observation
            )
            .getOrThrow()

        assertTrue(first.observationIdentitySha256 != revised.observationIdentitySha256)
        assertTrue(first.admission.request.taskId != revised.admission.request.taskId)
        assertTrue(first.admission.request.trigger != revised.admission.request.trigger)
    }

    @Test
    fun taskRuntimeBudgetIsBoundedForMobileTriggerSource() {
        val result = runCatching {
            source(
                expectedRuntimeMs =
                    AmperAgentProactiveTriggerSource.MAX_EXPECTED_RUNTIME_MS + 1L
            )
        }

        assertTrue(result.isFailure)
    }

    private fun source(
        kind: AmperAgentProactiveTriggerSourceKind =
            AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW,
        minimumIntervalMs: Long =
            AmperAgentProactiveTriggerSource.SCHEDULED_WINDOW_MIN_INTERVAL_MS,
        expectedRuntimeMs: Long = 30_000L,
        configurationSha256: String = "a".repeat(64),
        userConfigured: Boolean = true,
        enabled: Boolean = true
    ) = AmperAgentProactiveTriggerSource(
        sourceId = "monitor.example",
        configurationId = "config.example",
        kind = kind,
        objective = "Observe one bounded user-configured condition.",
        allowedCapabilities = setOf(CapabilityId("reasoning")),
        expectedRuntimeMs = expectedRuntimeMs,
        minimumIntervalMs = minimumIntervalMs,
        configurationSha256 = configurationSha256,
        userConfigured = userConfigured,
        enabled = enabled
    )

    private fun observation(
        sourceId: String = "monitor.example",
        observedAtEpochMs: Long = 1_000_000L,
        payloadDigest: String = "d".repeat(64)
    ) = AmperAgentProactiveTriggerObservation(
        sourceId = sourceId,
        observedAtEpochMs = observedAtEpochMs,
        payloadDigest = payloadDigest
    )
}
