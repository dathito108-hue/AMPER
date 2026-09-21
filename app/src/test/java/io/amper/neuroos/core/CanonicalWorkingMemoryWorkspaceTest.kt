package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalWorkingMemoryWorkspaceTest {
    @Test
    fun capacityKeepsMostSalientAndEvictsOldestOnTie() {
        var now = 1_000L
        val memory = InMemoryMemoryOs()
        val workspace = CanonicalWorkingMemoryWorkspace(
            continuity = MemoryBackedWorkingMemoryContinuityStore(memory),
            policy = WorkingMemoryLifecyclePolicy(
                maxEvents = 3,
                maxAgeMs = 10_000L,
                minSalience = 0.0,
                checkpointEveryMutations = 8
            ),
            clock = { now++ }
        )

        workspace.publish(event("low-old", 0.1))
        workspace.publish(event("high", 0.9))
        workspace.publish(event("mid", 0.5))
        workspace.publish(event("low-new", 0.1))

        val topics = workspace.snapshot().map { it.topic }
        assertEquals(3, topics.size)
        assertFalse("low-old" in topics)
        assertTrue("low-new" in topics)
        assertTrue("high" in topics)
        assertTrue("mid" in topics)
    }

    @Test
    fun salienceFloorRejectsNoiseWithoutChangingVisibleState() {
        val memory = InMemoryMemoryOs()
        val workspace = CanonicalWorkingMemoryWorkspace(
            continuity = MemoryBackedWorkingMemoryContinuityStore(memory),
            policy = WorkingMemoryLifecyclePolicy(
                maxEvents = 4,
                maxAgeMs = 10_000L,
                minSalience = 0.4,
                checkpointEveryMutations = 8
            ),
            clock = { 10L }
        )

        workspace.publish(event("noise", 0.2))
        workspace.publish(event("signal", 0.8))

        assertEquals(listOf("signal"), workspace.snapshot().map { it.topic })
    }

    @Test
    fun expiryRemovesOldTransientEvents() {
        var now = 100L
        val workspace = CanonicalWorkingMemoryWorkspace(
            continuity = MemoryBackedWorkingMemoryContinuityStore(InMemoryMemoryOs()),
            policy = WorkingMemoryLifecyclePolicy(
                maxEvents = 4,
                maxAgeMs = 50L,
                minSalience = 0.0,
                checkpointEveryMutations = 8
            ),
            clock = { now }
        )
        workspace.publish(event("old", 0.8))
        now = 149L
        assertEquals(1, workspace.snapshot().size)
        now = 150L
        assertTrue(workspace.snapshot().isEmpty())
    }

    @Test
    fun continuityCheckpointStoresDigestMetadataOnlyAndChainsAcrossWorkspaceRestart() {
        var now = 1_000L
        val memory = InMemoryMemoryOs()
        val store = MemoryBackedWorkingMemoryContinuityStore(memory)
        val policy = WorkingMemoryLifecyclePolicy(
            maxEvents = 4,
            maxAgeMs = 10_000L,
            minSalience = 0.0,
            checkpointEveryMutations = 1
        )
        val first = CanonicalWorkingMemoryWorkspace(store, policy) { now++ }
        val secret = "raw-hidden-reasoning-must-not-persist"
        first.publish(
            CognitiveEvent(
                id = "e1",
                topic = "private.reasoning",
                payload = secret,
                salience = 0.9
            )
        )
        val checkpoint1 = store.latest().getOrThrow()
        assertNotNull(checkpoint1)
        val stored1 = requireNotNull(
            memory.get(MemoryBackedWorkingMemoryContinuityStore.RECORD_ID)
        )
        assertFalse(stored1.content.contains(secret))
        assertFalse(stored1.content.contains("private.reasoning"))
        assertEquals(1, memory.size())

        val second = CanonicalWorkingMemoryWorkspace(store, policy) { now++ }
        second.publish(event("new-session", 0.7))
        val checkpoint2 = requireNotNull(store.latest().getOrThrow())

        assertEquals(requireNotNull(checkpoint1).sequence + 1L, checkpoint2.sequence)
        assertEquals(checkpoint1.checkpointSha256, checkpoint2.previousCheckpointSha256)
        assertEquals(1, memory.size())
    }

    @Test
    fun continuityStoreRejectsBrokenCheckpointChain() {
        val memory = InMemoryMemoryOs()
        val store = MemoryBackedWorkingMemoryContinuityStore(memory)
        val first = WorkingMemoryContinuityCodec.create(
            sequence = 1L,
            previousCheckpointSha256 = null,
            workspaceStateSha256 = "a".repeat(64),
            retainedEvents = 1,
            capturedAtEpochMs = 10L
        )
        assertTrue(store.save(first).isSuccess)

        val broken = WorkingMemoryContinuityCodec.create(
            sequence = 2L,
            previousCheckpointSha256 = "b".repeat(64),
            workspaceStateSha256 = "c".repeat(64),
            retainedEvents = 1,
            capturedAtEpochMs = 20L
        )
        assertTrue(store.save(broken).isFailure)
        assertEquals(first, store.latest().getOrThrow())
    }

    @Test
    fun continuityFailureDoesNotBlockTransientWorkingMemory() {
        val failure = IllegalStateException("continuity unavailable")
        val workspace = CanonicalWorkingMemoryWorkspace(
            continuity = object : WorkingMemoryContinuityStore {
                override fun latest(): Result<WorkingMemoryContinuityCheckpoint?> =
                    Result.failure(failure)

                override fun save(
                    checkpoint: WorkingMemoryContinuityCheckpoint
                ): Result<Unit> =
                    Result.failure(failure)
            },
            policy = WorkingMemoryLifecyclePolicy(
                maxEvents = 4,
                maxAgeMs = 1_000L,
                minSalience = 0.0,
                checkpointEveryMutations = 1
            ),
            clock = { 10L }
        )

        workspace.publish(event("still-visible", 0.8))

        assertEquals(listOf("still-visible"), workspace.snapshot().map { it.topic })
        assertTrue(workspace.continuityStatus().isFailure)
    }

    @Test
    fun wrongKindAtContinuityRecordIdFailsClosedWithoutOverwrite() {
        val memory = InMemoryMemoryOs()
        memory.remember(
            MemoryRecord(
                id = MemoryBackedWorkingMemoryContinuityStore.RECORD_ID,
                kind = "unexpected-kind",
                content = "do-not-overwrite",
                importance = 0.5,
                provenance = Provenance(source = "test", producer = "test")
            )
        )
        val store = MemoryBackedWorkingMemoryContinuityStore(memory)

        assertTrue(store.latest().isFailure)

        val checkpoint = WorkingMemoryContinuityCodec.create(
            sequence = 1L,
            previousCheckpointSha256 = null,
            workspaceStateSha256 = "d".repeat(64),
            retainedEvents = 0,
            capturedAtEpochMs = 1L
        )
        assertTrue(store.save(checkpoint).isFailure)
        assertEquals("unexpected-kind", memory.get(
            MemoryBackedWorkingMemoryContinuityStore.RECORD_ID
        )?.kind)
    }

    @Test
    fun defaultCheckpointCadenceAvoidsFirstTickWriteAmplification() {
        assertTrue(WorkingMemoryLifecyclePolicy.DEFAULT_CHECKPOINT_EVERY_MUTATIONS > 6)
    }

    private fun event(topic: String, salience: Double) =
        CognitiveEvent(
            id = topic,
            topic = topic,
            payload = "payload-$topic",
            salience = salience
        )
}
