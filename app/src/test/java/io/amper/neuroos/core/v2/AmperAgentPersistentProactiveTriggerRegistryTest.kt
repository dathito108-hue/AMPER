package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmperRuntime
import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.FileMemoryJournal
import io.amper.neuroos.core.InMemoryMemoryOs
import io.amper.neuroos.core.PersistentMemoryOs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AmperAgentPersistentProactiveTriggerRegistryTest {
    @Test
    fun sourceAndAcceptedObservationSurviveJournalReopen() {
        val root = Files.createTempDirectory("amper-trigger-registry").toFile()
        val journalFile = root.resolve("memory.journal")
        val firstMemory = PersistentMemoryOs(FileMemoryJournal(journalFile))
        val first = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(firstMemory)
        val source = source()

        first.upsert(source, updatedAtEpochMs = 10L)
        val accepted = first.accept(
            observation(observedAtEpochMs = 1_000_000L),
            acceptedAtEpochMs = 1_000_001L
        ).getOrThrow()

        val secondMemory = PersistentMemoryOs(FileMemoryJournal(journalFile))
        val second = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(secondMemory)
        val restored = requireNotNull(second.get(source.sourceId))

        assertEquals(source, restored.source)
        assertEquals(1_000_000L, restored.lastAcceptedObservationAtEpochMs)
        assertEquals(
            accepted.qualified.observationIdentitySha256,
            restored.lastAcceptedObservationIdentitySha256
        )
        assertTrue(restored.source.enabled)
        assertEquals(1, restored.pendingObservations.size)
        assertEquals(
            "d".repeat(64),
            restored.pendingObservations.single().observation.payloadDigest
        )
    }

    @Test
    fun pendingObservationQueueIsBoundedAndAcknowledgedInFifoOrder() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val source = source()
        registry.upsert(source, updatedAtEpochMs = 1L)

        val accepted = (0 until AmperAgentPersistedProactiveTriggerSource.MAX_PENDING_OBSERVATIONS)
            .map { index ->
                registry.accept(
                    observation(
                        observedAtEpochMs =
                            1_000_000L +
                                index *
                                AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS,
                        payloadDigest = (index + 1).toString(16).padStart(64, '0')
                    )
                ).getOrThrow()
            }

        val overflow = registry.accept(
            observation(
                observedAtEpochMs =
                    1_000_000L +
                        AmperAgentPersistedProactiveTriggerSource.MAX_PENDING_OBSERVATIONS *
                        AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS,
                payloadDigest = "f".repeat(64)
            )
        )
        assertTrue(overflow.isFailure)
        assertEquals(
            AmperAgentPersistedProactiveTriggerSource.MAX_PENDING_OBSERVATIONS,
            registry.pending(source.sourceId).size
        )

        val secondIdentity = accepted[1].qualified.observationIdentitySha256
        assertTrue(
            registry.acknowledgePending(source.sourceId, secondIdentity).isFailure
        )

        val firstIdentity = accepted.first().qualified.observationIdentitySha256
        val afterAck = registry
            .acknowledgePending(source.sourceId, firstIdentity)
            .getOrThrow()

        assertEquals(
            AmperAgentPersistedProactiveTriggerSource.MAX_PENDING_OBSERVATIONS - 1,
            afterAck.pendingObservations.size
        )
        assertEquals(
            secondIdentity,
            afterAck.pendingObservations.first().observationIdentitySha256
        )
    }

    @Test
    fun duplicateAndCooldownObservationFailWithoutMutatingCheckpoint() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val source = source()
        registry.upsert(source, updatedAtEpochMs = 1L)

        val first = registry.accept(
            observation(observedAtEpochMs = 1_000_000L),
            acceptedAtEpochMs = 1_000_000L
        ).getOrThrow()
        val duplicate = registry.accept(
            observation(observedAtEpochMs = 1_000_000L),
            acceptedAtEpochMs = 1_000_001L
        )
        val burst = registry.accept(
            observation(
                observedAtEpochMs =
                    1_000_000L +
                        AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS -
                        1L,
                payloadDigest = "e".repeat(64)
            ),
            acceptedAtEpochMs = 1_100_000L
        )

        assertTrue(duplicate.isFailure)
        assertTrue(burst.isFailure)
        val state = requireNotNull(registry.get(source.sourceId))
        assertEquals(
            first.qualified.observationIdentitySha256,
            state.lastAcceptedObservationIdentitySha256
        )
        assertEquals(1_000_000L, state.lastAcceptedObservationAtEpochMs)
    }

    @Test
    fun configurationRevisionPreservesCooldownButRotatesObservationIdentity() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val firstSource = source(configurationSha256 = "a".repeat(64))
        registry.upsert(firstSource, updatedAtEpochMs = 1L)
        val first = registry.accept(
            observation(observedAtEpochMs = 1_000_000L),
            acceptedAtEpochMs = 1_000_000L
        ).getOrThrow()

        registry.acknowledgePending(
            firstSource.sourceId,
            first.qualified.observationIdentitySha256,
            updatedAtEpochMs = 1_005_000L
        ).getOrThrow()

        val revisedSource = source(configurationSha256 = "b".repeat(64))
        val revised = registry.upsert(revisedSource, updatedAtEpochMs = 1_010_000L)

        assertEquals(1_000_000L, revised.lastAcceptedObservationAtEpochMs)
        assertEquals(
            first.qualified.observationIdentitySha256,
            revised.lastAcceptedObservationIdentitySha256
        )

        val tooSoon = registry.accept(
            observation(
                observedAtEpochMs = 1_030_000L,
                payloadDigest = "e".repeat(64)
            )
        )
        assertTrue(tooSoon.isFailure)

        val next = registry.accept(
            observation(
                observedAtEpochMs =
                    1_000_000L +
                        AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS,
                payloadDigest = "e".repeat(64)
            )
        ).getOrThrow()

        assertEquals("b".repeat(64), next.qualified.configurationSha256)
        assertTrue(
            next.qualified.observationIdentitySha256 !=
                first.qualified.observationIdentitySha256
        )
    }

    @Test
    fun disableAndReenablePreserveDedupeState() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val source = source()
        registry.upsert(source, updatedAtEpochMs = 1L)
        val accepted = registry.accept(
            observation(observedAtEpochMs = 1_000_000L)
        ).getOrThrow()

        val disabled = registry.setEnabled(
            source.sourceId,
            enabled = false,
            updatedAtEpochMs = 1_000_010L
        ).getOrThrow()

        assertFalse(disabled.source.enabled)
        assertEquals(
            accepted.qualified.observationIdentitySha256,
            disabled.lastAcceptedObservationIdentitySha256
        )
        assertTrue(
            registry.accept(
                observation(
                    observedAtEpochMs = 2_000_000L,
                    payloadDigest = "e".repeat(64)
                )
            ).isFailure
        )

        val enabled = registry.setEnabled(
            source.sourceId,
            enabled = true,
            updatedAtEpochMs = 1_000_020L
        ).getOrThrow()
        assertTrue(enabled.source.enabled)
        assertEquals(
            accepted.qualified.observationIdentitySha256,
            enabled.lastAcceptedObservationIdentitySha256
        )
    }

    @Test
    fun concurrentSameObservationIsAcceptedExactlyOnce() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        registry.upsert(source(), updatedAtEpochMs = 1L)
        val observation = observation(observedAtEpochMs = 1_000_000L)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = Collections.synchronizedList(
            mutableListOf<Result<AmperAgentAcceptedProactiveTriggerObservation>>()
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
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
    }

    @Test
    fun pendingObservationBlocksConfigurationMutationAndRemoval() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val source = source()
        registry.upsert(source, updatedAtEpochMs = 1L)
        val accepted = registry.accept(
            observation(observedAtEpochMs = 1_000_000L)
        ).getOrThrow()

        val revision = runCatching {
            registry.upsert(
                source(configurationSha256 = "b".repeat(64)),
                updatedAtEpochMs = 2L
            )
        }

        assertTrue(revision.isFailure)
        assertFalse(registry.remove(source.sourceId))
        assertNotNull(registry.get(source.sourceId))

        registry.acknowledgePending(
            source.sourceId,
            accepted.qualified.observationIdentitySha256
        ).getOrThrow()

        assertTrue(registry.remove(source.sourceId))
    }

    @Test
    fun sourceIdentityCannotSilentlyChangeKindOrConfigurationId() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        registry.upsert(source(), updatedAtEpochMs = 1L)

        val configDrift = runCatching {
            registry.upsert(
                source(configurationId = "config.other"),
                updatedAtEpochMs = 2L
            )
        }
        val kindDrift = runCatching {
            registry.upsert(
                source(
                    kind = AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW,
                    minimumIntervalMs =
                        AmperAgentProactiveTriggerSource.SCHEDULED_WINDOW_MIN_INTERVAL_MS
                ),
                updatedAtEpochMs = 2L
            )
        }

        assertTrue(configDrift.isFailure)
        assertTrue(kindDrift.isFailure)
    }

    @Test
    fun listRemoveAndCanonicalRuntimeWiringUseSameRegistryContract() {
        val runtime = AmperRuntime.reference()
        val registry = runtime.proactiveTriggerSources
        val source = source()

        registry.upsert(source, updatedAtEpochMs = 5L)

        assertNotNull(registry.get(source.sourceId))
        assertTrue(registry.list().any { it.source.sourceId == source.sourceId })
        assertTrue(registry.remove(source.sourceId))
        assertNull(registry.get(source.sourceId))
    }

    private fun source(
        configurationId: String = "config.example",
        kind: AmperAgentProactiveTriggerSourceKind =
            AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
        minimumIntervalMs: Long =
            AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS,
        configurationSha256: String = "a".repeat(64)
    ) = AmperAgentProactiveTriggerSource(
        sourceId = "monitor.example",
        configurationId = configurationId,
        kind = kind,
        objective = "Observe one bounded user-configured condition.",
        allowedCapabilities = setOf(CapabilityId("reasoning")),
        expectedRuntimeMs = 30_000L,
        minimumIntervalMs = minimumIntervalMs,
        configurationSha256 = configurationSha256,
        userConfigured = true,
        enabled = true
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
