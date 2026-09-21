package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceSafeMemoryRetentionTest {
    @Test
    fun automaticRetentionNeverDeletesParentStillReferencedByRetainedChild() {
        val journal = InMemoryMemoryJournal()
        val memory = PersistentMemoryOs(journal, maxRecords = 2)
        val parent = record("parent", importance = 0.01, createdAt = 1L)
        val child = record(
            "child",
            importance = 0.20,
            createdAt = 2L,
            parents = setOf(parent.id)
        )
        val incoming = record("incoming", importance = 0.90, createdAt = 3L)

        memory.remember(parent)
        memory.remember(child)
        memory.remember(incoming)

        assertNotNull(memory.get(parent.id))
        assertNull(memory.get(child.id))
        assertNotNull(memory.get(incoming.id))
        assertEquals(2, memory.size())
    }

    @Test
    fun retentionCanPeelLeafThenParentToReconcileReplayOverflow() {
        val journal = InMemoryMemoryJournal()
        val parent = record("replay-parent", importance = 0.01, createdAt = 1L)
        val child = record(
            "replay-child",
            importance = 0.02,
            createdAt = 2L,
            parents = setOf(parent.id)
        )
        journal.append(parent)
        journal.append(child)

        val memory = PersistentMemoryOs(journal, maxRecords = 1)
        assertEquals(2, memory.size())

        val incoming = record("replay-incoming", importance = 0.9, createdAt = 3L)
        memory.remember(incoming)

        assertEquals(1, memory.size())
        assertNotNull(memory.get(incoming.id))
        assertNull(memory.get(child.id))
        assertNull(memory.get(parent.id))
        assertEquals(listOf(incoming.id), journal.replay().map { it.id })
    }

    @Test
    fun impossibleRetentionFailsBeforeCandidateDurableAppend() {
        val journal = InMemoryMemoryJournal()
        val parent = record("required-parent", importance = 0.01, createdAt = 1L)
        journal.append(parent)
        val memory = PersistentMemoryOs(journal, maxRecords = 1)
        val incoming = record(
            "requires-parent",
            importance = 1.0,
            createdAt = 2L,
            parents = setOf(parent.id)
        )

        val result = runCatching { memory.remember(incoming) }

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("cannot satisfy capacity")
        )
        assertNull(memory.get(incoming.id))
        assertNotNull(memory.get(parent.id))
        assertEquals(listOf(parent.id), journal.replay().map { it.id })
    }

    @Test
    fun explicitForgetRejectsLiveParentEvidenceWithoutTombstone() {
        val journal = InMemoryMemoryJournal()
        val memory = PersistentMemoryOs(journal, maxRecords = 8)
        val parent = record("forget-parent", importance = 0.2, createdAt = 1L)
        val child = record(
            "forget-child",
            importance = 0.8,
            createdAt = 2L,
            parents = setOf(parent.id)
        )
        memory.remember(parent)
        memory.remember(child)

        val result = runCatching { memory.forget(parent.id) }

        assertTrue(result.isFailure)
        assertNotNull(memory.get(parent.id))
        assertNotNull(memory.get(child.id))
        assertEquals(
            setOf(parent.id, child.id),
            journal.replay().map { it.id }.toSet()
        )
    }

    @Test
    fun explicitForgetTombstoneFailureKeepsLiveIndexRecord() {
        val target = record("forget-durable-first", importance = 0.5, createdAt = 1L)
        val journal = TombstoneFailingJournal(listOf(target))
        val memory = PersistentMemoryOs(journal, maxRecords = 4)

        val result = runCatching { memory.forget(target.id) }

        assertTrue(result.isFailure)
        assertNotNull(memory.get(target.id))
        assertNotNull(journal.replay().singleOrNull { it.id == target.id })
    }

    @Test
    fun evictionTombstoneFailureKeepsCandidateAndOldEvidenceVisible() {
        val old = record("old-safe-leaf", importance = 0.1, createdAt = 1L)
        val journal = TombstoneFailingJournal(listOf(old))
        val memory = PersistentMemoryOs(journal, maxRecords = 1)
        val incoming = record("new-candidate", importance = 0.9, createdAt = 2L)

        val result = runCatching { memory.remember(incoming) }

        assertTrue(result.isFailure)
        assertNotNull(memory.get(old.id))
        assertNotNull(memory.get(incoming.id))
        assertEquals(2, memory.size())
        assertEquals(
            setOf(old.id, incoming.id),
            journal.replay().map { it.id }.toSet()
        )
    }

    @Test
    fun incomingCandidateIsNeverSelectedAsAutomaticEvictionVictim() {
        val journal = InMemoryMemoryJournal()
        val memory = PersistentMemoryOs(journal, maxRecords = 2)
        val highA = record("high-a", importance = 1.0, createdAt = 1L)
        val highB = record("high-b", importance = 1.0, createdAt = 2L)
        val lowIncoming = record("low-incoming", importance = 0.0, createdAt = 3L)
        memory.remember(highA)
        memory.remember(highB)

        memory.remember(lowIncoming)

        assertNotNull(memory.get(lowIncoming.id))
        assertEquals(2, memory.size())
        assertEquals(1, listOf(highA.id, highB.id).count { memory.get(it) != null })
    }

    private fun record(
        id: String,
        importance: Double,
        createdAt: Long,
        parents: Set<MemoryId> = emptySet()
    ) = MemoryRecord(
        id = MemoryId(id),
        kind = "phase677-retention",
        content = "content-" + id,
        importance = importance,
        provenance = Provenance(
            source = "phase677",
            producer = "retention-test",
            observedAtEpochMs = createdAt,
            confidence = 1.0,
            parents = parents
        ),
        createdAtEpochMs = createdAt
    )

    private class TombstoneFailingJournal(
        initial: List<MemoryRecord>
    ) : MemoryJournal {
        private val records = linkedMapOf<MemoryId, MemoryRecord>()

        init {
            initial.forEach { records[it.id] = it }
        }

        override fun append(record: MemoryRecord) {
            records[record.id] = record
        }

        override fun tombstone(id: MemoryId) {
            error("simulated tombstone failure")
        }

        override fun replay(): List<MemoryRecord> = records.values.toList()
    }
}
