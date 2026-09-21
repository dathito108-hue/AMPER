package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentMemoryRetentionSafetyTest {
    private fun record(
        id: String,
        importance: Double,
        parents: Set<MemoryId> = emptySet(),
        content: String = "payload-$id",
        createdAtEpochMs: Long = 0L
    ) = MemoryRecord(
        id = MemoryId(id),
        kind = "retention-test",
        content = content,
        importance = importance,
        provenance = Provenance(
            source = "test",
            producer = "test",
            confidence = 1.0,
            parents = parents
        ),
        createdAtEpochMs = createdAtEpochMs
    )

    @Test
    fun autoTrimNeverEvictsReferencedParent() {
        val memory = PersistentMemoryOs(InMemoryMemoryJournal(), maxRecords = 2)
        val parent = record("parent-1", 0.1, createdAtEpochMs = 1L)
        val child = record("child-2", 0.9, parents = setOf(parent.id), createdAtEpochMs = 2L)
        memory.remember(parent)
        memory.remember(child)
        val newcomer = record("new-3", 0.8, createdAtEpochMs = 3L)

        memory.remember(newcomer)

        assertEquals(parent, memory.get(parent.id))
        assertNull(memory.get(child.id))
        assertEquals(newcomer, memory.get(newcomer.id))
    }

    @Test
    fun explicitForgetRefusesReferencedParent() {
        val memory = PersistentMemoryOs(InMemoryMemoryJournal(), maxRecords = 4)
        val parent = record("evidence-1", 0.1)
        val child = record("semantic-2", 0.9, parents = setOf(parent.id))
        memory.remember(parent)
        memory.remember(child)

        assertFalse(memory.forget(parent.id))
        assertTrue(memory.forget(child.id))
        assertTrue(memory.forget(parent.id))
    }

    @Test
    fun contentLineageReferenceAlsoProtectsRecord() {
        val memory = PersistentMemoryOs(InMemoryMemoryJournal(), maxRecords = 2)
        val referenced = record("plan-1", 0.1, createdAtEpochMs = 1L)
        val recovery = record(
            id = "recovery-2",
            importance = 0.9,
            content = "recovery target plan-1 step=3",
            createdAtEpochMs = 2L
        )
        memory.remember(referenced)
        memory.remember(recovery)

        memory.remember(record("new-3", 0.8, createdAtEpochMs = 3L))

        assertEquals(referenced, memory.get(referenced.id))
        assertNull(memory.get(recovery.id))
    }

    @Test
    fun capacityFailureHappensBeforeDurableAppendWhenAllOldRecordsAreReferenced() {
        val journal = CountingMemoryJournal()
        val memory = PersistentMemoryOs(journal, maxRecords = 2)
        val first = record("first-1", 0.1, content = "ref=second-2")
        val second = record("second-2", 0.2, parents = setOf(first.id))
        memory.remember(first)
        memory.remember(second)
        val beforeAppends = journal.appendCount

        val result = runCatching { memory.remember(record("new-3", 1.0)) }

        assertTrue(result.isFailure)
        assertEquals(beforeAppends, journal.appendCount)
        assertEquals(2, memory.size())
        assertNull(memory.get(MemoryId("new-3")))
    }

    @Test
    fun compactionPreservesLogicalReferenceGraph() {
        val memory = PersistentMemoryOs(CountingMemoryJournal(), maxRecords = 4)
        val evidence = record("evidence-1", 0.2)
        val semantic = record("semantic-2", 0.9, parents = setOf(evidence.id))
        memory.remember(evidence)
        memory.remember(semantic)

        memory.compact()

        assertEquals(setOf(evidence.id, semantic.id), memory.recall("", 8).map { it.id }.toSet())
        assertFalse(memory.forget(evidence.id))
    }

    private class CountingMemoryJournal : MemoryJournal {
        private val records = linkedMapOf<MemoryId, MemoryRecord>()
        var appendCount: Int = 0
            private set

        override fun append(record: MemoryRecord) {
            appendCount += 1
            records[record.id] = record
        }

        override fun tombstone(id: MemoryId) {
            records.remove(id)
        }

        override fun replay(): List<MemoryRecord> = records.values.toList()
        override fun compact(): Int = 0
    }
}
