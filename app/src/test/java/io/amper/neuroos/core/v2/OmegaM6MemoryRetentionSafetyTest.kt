package io.amper.neuroos.core.v2

import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaM6MemoryRetentionSafetyTest {
    private val invariants = setOf(
        "memory-forgetting-is-reference-aware",
        "memory-auto-trim-never-deletes-live-lineage",
        "memory-capacity-fails-closed-when-only-referenced-records-remain",
        "memory-compaction-preserves-logical-live-record-graph",
        "memory-retention-protects-provenance-and-content-lineage",
        "memory-forgetting-does-not-create-second-compaction-store"
    )

    private val criteria = setOf(
        "PersistentMemoryOs automatic retention never tombstones records still referenced by live provenance or encoded lineage",
        "explicit MemoryOs forget refuses records still referenced by another live record",
        "memory capacity expansion fails before durable append when the only possible evictions would break referenced lineage",
        "journal compaction rewrites only the physical mutation log and preserves the complete logical live record graph",
        "working episodic semantic procedural plan recovery and conversation lineage continue to share the same MemoryOs forgetting boundary"
    )

    @Test
    fun phase677ArchitectureLocksArePresent() {
        assertTrue(OmegaArchitectureLock.invariants.containsAll(invariants))
    }

    @Test
    fun phase677M6CriteriaArePresent() {
        val actual = OmegaArchitectureLock.milestones
            .single { it.id == OmegaMilestoneId.M6_COGNITIVE_MEMORY }
            .exitCriteria
            .toSet()
        assertTrue(actual.containsAll(criteria))
    }
}
