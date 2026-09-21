package io.amper.neuroos.core.v2

import io.amper.neuroos.core.EpisodicMemoryPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaM6EpisodicMemoryPolicyTest {
    private val invariants = setOf(
        "episodic-memory-uses-canonical-memory-os-only",
        "episodic-memory-admission-is-importance-bounded",
        "episodic-memory-duplicate-suppression-is-window-bounded",
        "episodic-memory-retention-is-importance-and-recency-bounded",
        "episodic-memory-index-is-read-only-memory-os-projection",
        "episodic-memory-does-not-copy-conversation-semantic-or-procedural-stores",
        "episodic-memory-corruption-and-scan-overflow-fail-visible"
    )

    private val criteria = setOf(
        "canonical episodic admission writes only episodic-v1 records into the existing MemoryOs",
        "episodic admission applies importance threshold bounded content and duplicate-window suppression",
        "episodic retention keeps at most 256 episodes and prefers higher importance then newer evidence",
        "episodic retrieval is a bounded read-only projection over MemoryOs and creates no second index database",
        "conversation turns semantic knowledge and procedural memory are disallowed episodic origins and are never copied",
        "episodic corruption or MemoryOs scan-bound overflow fails visible instead of returning partial history"
    )

    @Test
    fun phase673ArchitectureLocksArePresent() {
        assertTrue(OmegaArchitectureLock.invariants.containsAll(invariants))
    }

    @Test
    fun phase673M6CriteriaArePresent() {
        val actual = OmegaArchitectureLock.milestones
            .single { it.id == OmegaMilestoneId.M6_COGNITIVE_MEMORY }
            .exitCriteria
            .toSet()
        assertTrue(actual.containsAll(criteria))
    }

    @Test
    fun defaultEpisodicBoundsRemainMobileFinite() {
        assertTrue(EpisodicMemoryPolicy.DEFAULT_MAX_EPISODES <= 256)
        assertTrue(EpisodicMemoryPolicy.DEFAULT_MAX_SCAN_RECORDS <= 4096)
        assertTrue(EpisodicMemoryPolicy.DEFAULT_MAX_CONTENT_CHARS <= 1024)
    }
}
