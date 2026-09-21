package io.amper.neuroos.core.v2

import io.amper.neuroos.core.WorkingMemoryLifecyclePolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaM6WorkingMemoryLifecycleTest {
    private val invariants = setOf(
        "working-memory-is-capacity-salience-and-expiry-bounded",
        "working-memory-capacity-evicts-lowest-salience-oldest-first",
        "working-memory-continuity-checkpoints-use-same-memory-os",
        "working-memory-checkpoints-store-digest-metadata-only",
        "working-memory-continuity-chain-is-verified",
        "working-memory-checkpoint-write-cadence-is-bounded",
        "working-memory-continuity-is-non-authority"
    )

    private val criteria = setOf(
        "canonical working memory has bounded capacity salience admission and clock-based expiry",
        "working-memory overflow evicts lowest-salience oldest events deterministically",
        "working-memory continuity uses one periodically updated digest-only checkpoint in the same MemoryOs",
        "working-memory continuity checkpoint chaining verifies sequence and previous checkpoint digest before overwrite",
        "working-memory continuity persists no CognitiveEvent topic payload or raw hidden reasoning",
        "working-memory checkpoint write cadence is bounded to reduce mobile journal write amplification"
    )

    @Test
    fun phase672ArchitectureLocksArePresent() {
        assertTrue(OmegaArchitectureLock.invariants.containsAll(invariants))
    }

    @Test
    fun phase672M6CriteriaArePresent() {
        val actual = OmegaArchitectureLock.milestones
            .single { it.id == OmegaMilestoneId.M6_COGNITIVE_MEMORY }
            .exitCriteria
            .toSet()
        assertTrue(actual.containsAll(criteria))
    }

    @Test
    fun defaultMobileWorkingMemoryBoundsStayFinite() {
        assertTrue(WorkingMemoryLifecyclePolicy.DEFAULT_MAX_EVENTS <= 128)
        assertTrue(WorkingMemoryLifecyclePolicy.DEFAULT_MAX_AGE_MS <= 15L * 60L * 1000L)
        assertTrue(WorkingMemoryLifecyclePolicy.DEFAULT_CHECKPOINT_EVERY_MUTATIONS in 2..64)
    }
}
