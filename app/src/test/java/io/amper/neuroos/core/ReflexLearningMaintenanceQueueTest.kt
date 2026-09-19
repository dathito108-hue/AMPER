package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexLearningMaintenanceQueueTest {
    @Test
    fun queueCoalescesSameEvidenceAndSurvivesReconstruction() {
        val memory = InMemoryMemoryOs()
        val queue = MemoryBackedReflexLearningMaintenanceQueue(memory)
        val digest = reflexLinearSha256("maintenance-evidence-a")

        queue.enqueue(
            evidenceDigest = digest,
            reason = ReflexLearningMaintenanceReason.FRESH_EVIDENCE,
            priority = 0.35,
            notBeforeEpochMs = 2_000L,
            nowEpochMs = 1_000L
        )
        queue.enqueue(
            evidenceDigest = digest,
            reason = ReflexLearningMaintenanceReason.RESOURCE_DEFERRED,
            priority = 0.80,
            notBeforeEpochMs = 3_000L,
            nowEpochMs = 1_500L
        )
        queue.recordAttempt(
            expectedEvidenceDigest = digest,
            nextNotBeforeEpochMs = 4_000L,
            nowEpochMs = 2_000L
        )

        val restored = MemoryBackedReflexLearningMaintenanceQueue(memory).pending()
        requireNotNull(restored)
        assertEquals(digest, restored.evidenceDigest)
        assertEquals(1_000L, restored.firstQueuedAtEpochMs)
        assertEquals(1_500L, restored.lastQueuedAtEpochMs)
        assertEquals(4_000L, restored.notBeforeEpochMs)
        assertEquals(1, restored.attempts)
        assertEquals(0.80, restored.priority, 0.0001)
        assertTrue(ReflexLearningMaintenanceReason.FRESH_EVIDENCE in restored.reasons)
        assertTrue(ReflexLearningMaintenanceReason.RESOURCE_DEFERRED in restored.reasons)
        assertFalse(restored.authorityBearing)
    }

    @Test
    fun newerEvidenceSupersedesOldTicketWithoutDuplicatingQueue() {
        val memory = InMemoryMemoryOs()
        val queue = MemoryBackedReflexLearningMaintenanceQueue(memory)
        val first = reflexLinearSha256("maintenance-evidence-old")
        val next = reflexLinearSha256("maintenance-evidence-new")

        queue.enqueue(
            evidenceDigest = first,
            reason = ReflexLearningMaintenanceReason.FRESH_EVIDENCE,
            priority = 0.60,
            notBeforeEpochMs = 2_000L,
            nowEpochMs = 1_000L
        )
        queue.recordAttempt(first, 5_000L, 2_000L)

        val replaced = queue.enqueue(
            evidenceDigest = next,
            reason = ReflexLearningMaintenanceReason.HARD_EVIDENCE,
            priority = 0.90,
            notBeforeEpochMs = 3_500L,
            nowEpochMs = 3_000L
        )

        assertEquals(next, replaced.evidenceDigest)
        assertEquals(3_000L, replaced.firstQueuedAtEpochMs)
        assertEquals(0, replaced.attempts)
        assertEquals(setOf(ReflexLearningMaintenanceReason.HARD_EVIDENCE), replaced.reasons)
        assertFalse(queue.clear(first))
        assertTrue(queue.clear(next))
        assertNull(queue.pending())
    }

    @Test
    fun inMemoryArtifactPruneKeepsOnlyProtectedDigests() {
        val store = InMemoryReflexLinearArtifactStore()
        val artifacts = (0 until 5).map { index ->
            store.put(byteArrayOf((index + 1).toByte())).sha256
        }
        val protected = setOf(artifacts[1], artifacts[3])

        val pruned = store.prune(protectedSha256 = protected, maxDelete = 8)

        assertEquals(3, pruned)
        assertEquals(protected, store.storedDigests())
        assertEquals(2, store.size())
    }
}
