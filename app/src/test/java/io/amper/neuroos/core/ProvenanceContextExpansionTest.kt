package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProvenanceContextExpansionTest {
    @Test
    fun contextReconnectsLexicalSeedToBoundedProvenanceAncestors() {
        val memory = InMemoryMemoryOs()
        val root = record("root", "The original architecture decision", emptySet(), 10L)
        val middle = record("middle", "Derived implementation evidence", setOf(root.id), 20L)
        val leaf = record("leaf", "Rotation barrier verification", setOf(middle.id), 30L)
        memory.remember(root)
        memory.remember(middle)
        memory.remember(leaf)

        val context = source(memory).capture(
            query = "rotation",
            memoryLimit = 3,
            worldLimit = 0,
            workspaceLimit = 0
        )

        assertEquals(listOf("leaf", "middle", "root"), context.memories.map { it.id.value })
    }

    @Test
    fun provenanceExpansionHonorsLimitAndNeverPullsUnrelatedMemory() {
        val memory = InMemoryMemoryOs()
        val parentB = record("b-parent", "Parent B context", emptySet(), 10L)
        val parentA = record("a-parent", "Parent A context", emptySet(), 11L)
        val unrelated = record("unrelated", "Completely separate history", emptySet(), 12L)
        val leaf = record(
            "leaf",
            "Recovery authority checkpoint",
            setOf(parentB.id, parentA.id),
            20L
        )
        memory.remember(parentB)
        memory.remember(parentA)
        memory.remember(unrelated)
        memory.remember(leaf)

        val context = source(memory).capture(
            query = "checkpoint",
            memoryLimit = 2,
            worldLimit = 0,
            workspaceLimit = 0
        )

        assertEquals(listOf("leaf", "a-parent"), context.memories.map { it.id.value })
        assertFalse(context.memories.any { it.id == unrelated.id })
    }

    private fun source(memory: MemoryOs): CanonicalSovereignContextSource =
        CanonicalSovereignContextSource(
            workspace = InMemoryWorkspace(),
            memory = memory,
            selfModel = CanonicalSelfModel(),
            goals = CanonicalGoalSystem(),
            world = CanonicalWorldModel()
        )

    private fun record(
        id: String,
        content: String,
        parents: Set<MemoryId>,
        createdAt: Long
    ): MemoryRecord = MemoryRecord(
        id = MemoryId(id),
        kind = "episodic",
        content = content,
        importance = 0.7,
        provenance = Provenance(
            source = "test",
            producer = "phase67",
            confidence = 0.9,
            parents = parents
        ),
        createdAtEpochMs = createdAt
    )
}
