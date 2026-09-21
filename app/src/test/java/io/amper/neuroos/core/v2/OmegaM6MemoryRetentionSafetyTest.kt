package io.amper.neuroos.core.v2

import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaM6MemoryRetentionSafetyTest {
    private val invariants = setOf(
        "memory-retention-preflights-provenance-safe-leaf-eviction",
        "memory-retention-never-auto-evicts-incoming-candidate",
        "memory-forget-rejects-live-parent-evidence",
        "memory-tombstone-precedes-live-index-removal",
        "memory-retention-failure-preserves-evidence-and-fails-visible",
        "memory-replay-overflow-is-reconciled-on-next-mutation",
        "physical-memory-journal-compaction-remains-logical-state-preserving"
    )

    private val criteria = setOf(
        "logical MemoryOs retention preflights the projected post-write provenance graph before durable candidate append",
        "automatic retention removes only provenance leaves and never removes the incoming candidate",
        "explicit forget refuses to tombstone a record still referenced as provenance parent by another live record",
        "retention and explicit forgetting durably tombstone before removing a record from the live in-memory index",
        "tombstone failure preserves live evidence and surfaces retention debt instead of silently deleting memory",
        "replay may preserve temporary crash-window overflow and the next mutation reconciles it through the same provenance-safe planner",
        "physical journal compaction remains unchanged and preserves the current logical live record set"
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
