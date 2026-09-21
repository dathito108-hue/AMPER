package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.InMemoryMemoryOs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AmperAgentProactiveTriggerSourceRegistryTest {
    @Test
    fun sourceRoundTripsThroughCanonicalMemoryOs() {
        val memory = InMemoryMemoryOs()
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(memory) { 100L }

        val saved = registry.upsert(source())
        val restored = registry.get("monitor.example")

        assertEquals(saved, restored)
        assertEquals(1, registry.list().size)
        assertTrue(memory.size() >= 1)
    }

    @Test
    fun acceptedObservationPersistsCooldownAndIdentity() {
        val memory = InMemoryMemoryOs()
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(memory) { 100L }
        registry.upsert(source(kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT))

        val accepted = registry.accept(observation(1_000_000L)).getOrThrow()

        assertTrue(accepted is AmperAgentProactiveTriggerAcceptance.Accepted)
        val state = accepted.state
        assertEquals(1_000_000L, state.lastAcceptedObservationAtEpochMs)
        assertEquals("d".repeat(64), state.lastAcceptedPayloadDigest)
        assertTrue(state.lastAcceptedObservationIdentitySha256?.length == 64)
        assertEquals(OmegaBackgroundMode.EVENT_WAKE, (accepted as AmperAgentProactiveTriggerAcceptance.Accepted).qualified.admission.backgroundMode)
    }

    @Test
    fun exactDuplicateIsIdempotentWithoutNewAdmission() {
        val memory = InMemoryMemoryOs()
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(memory) { 100L }
        registry.upsert(source(kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT))
        val observation = observation(1_000_000L)

        val first = registry.accept(observation).getOrThrow()
        val duplicate = registry.accept(observation).getOrThrow()

        assertTrue(first is AmperAgentProactiveTriggerAcceptance.Accepted)
        assertTrue(duplicate is AmperAgentProactiveTriggerAcceptance.Duplicate)
        assertEquals(first.state, duplicate.state)
    }

    @Test
    fun cooldownFailureDoesNotMutateDurableAcceptanceState() {
        val memory = InMemoryMemoryOs()
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(memory) { 100L }
        registry.upsert(source(kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT))
        registry.accept(observation(1_000_000L)).getOrThrow()
        val before = registry.get("monitor.example")

        val result = registry.accept(
            observation(
                observedAtEpochMs = 1_000_001L,
                payloadDigest = "e".repeat(64)
            )
        )

        assertTrue(result.isFailure)
        assertEquals(before, registry.get("monitor.example"))
    }

    @Test
    fun configurationRevisionChangeResetsAcceptanceState() {
        val memory = InMemoryMemoryOs()
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(memory) { 100L }
        registry.upsert(source(configurationSha256 = "a".repeat(64)))
        registry.accept(observation(1_000_000L)).getOrThrow()

        val revised = registry.upsert(source(configurationSha256 = "b".repeat(64)))

        assertNull(revised.lastAcceptedObservationAtEpochMs)
        assertNull(revised.lastAcceptedPayloadDigest)
        assertNull(revised.lastAcceptedObservationIdentitySha256)
    }

    @Test
    fun sameRevisionEnableTogglePreservesCooldownState() {
        val memory = InMemoryMemoryOs()
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(memory) { 100L }
        registry.upsert(source())
        registry.accept(observation(1_000_000L)).getOrThrow()
        val accepted = registry.get("monitor.example")!!

        val disabled = registry.setEnabled("monitor.example", false)!!
        val enabled = registry.setEnabled("monitor.example", true)!!

        assertTrue(!disabled.source.enabled)
        assertTrue(enabled.source.enabled)
        assertEquals(
            accepted.lastAcceptedObservationIdentitySha256,
            enabled.lastAcceptedObservationIdentitySha256
        )
    }

    @Test
    fun disabledSourceCannotAcceptObservation() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(InMemoryMemoryOs())
        registry.upsert(source(enabled = false))

        val result = registry.accept(observation(1_000_000L))

        assertTrue(result.isFailure)
    }

    @Test
    fun secondRegistryRestoresStateLikeProcessRestart() {
        val memory = InMemoryMemoryOs()
        val first = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(memory) { 100L }
        first.upsert(source())
        first.accept(observation(1_000_000L)).getOrThrow()

        val restored = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(memory) { 200L }
        val state = restored.get("monitor.example")

        assertEquals(1_000_000L, state?.lastAcceptedObservationAtEpochMs)
        assertEquals("d".repeat(64), state?.lastAcceptedPayloadDigest)
    }

    @Test
    fun concurrentDuplicateAcceptanceProducesOneAcceptedAndOneDuplicate() {
        val memory = InMemoryMemoryOs()
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(memory) { 100L }
        registry.upsert(source(kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT))
        val observation = observation(1_000_000L)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = Collections.synchronizedList(
            mutableListOf<Result<AmperAgentProactiveTriggerAcceptance>>()
        )

        repeat(2) {
            Thread {
                try {
                    start.await()
                    results += registry.accept(observation)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()

        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(2, results.size)
        assertTrue(results.all { it.isSuccess })
        assertEquals(
            1,
            results.count {
                it.getOrNull() is AmperAgentProactiveTriggerAcceptance.Accepted
            }
        )
        assertEquals(
            1,
            results.count {
                it.getOrNull() is AmperAgentProactiveTriggerAcceptance.Duplicate
            }
        )
    }

    @Test
    fun deleteRemovesOnlyRegisteredSourceRecord() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(InMemoryMemoryOs())
        registry.upsert(source())

        assertTrue(registry.delete("monitor.example"))
        assertNull(registry.get("monitor.example"))
        assertTrue(!registry.delete("monitor.example"))
    }

    private fun source(
        kind: AmperAgentProactiveTriggerSourceKind =
            AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW,
        configurationSha256: String = "a".repeat(64),
        enabled: Boolean = true
    ) = AmperAgentProactiveTriggerSource(
        sourceId = "monitor.example",
        configurationId = "config.example",
        kind = kind,
        objective = "Observe one bounded user-configured condition.",
        allowedCapabilities = setOf(CapabilityId("reasoning")),
        expectedRuntimeMs = 30_000L,
        minimumIntervalMs =
            AmperAgentProactiveTriggerSource.minimumIntervalFor(kind),
        configurationSha256 = configurationSha256,
        enabled = enabled
    )

    private fun observation(
        observedAtEpochMs: Long,
        payloadDigest: String = "d".repeat(64)
    ) = AmperAgentProactiveTriggerObservation(
        sourceId = "monitor.example",
        observedAtEpochMs = observedAtEpochMs,
        payloadDigest = payloadDigest
    )
}
