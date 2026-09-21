package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ProceduralMemoryReadPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaM6ProceduralMemoryPolicyTest {
    private val invariants = setOf(
        "procedural-memory-public-recent-reads-are-bounded",
        "procedural-memory-index-and-snapshot-corruption-fails-visible",
        "skill-guidance-requires-live-tool-and-world-qualification",
        "generalization-guidance-requires-active-skill-and-live-context",
        "repair-strategy-support-requires-live-requalification",
        "procedural-memory-reuses-existing-memory-os-stores",
        "procedural-memory-remains-advisory-and-non-authority"
    )

    private val criteria = setOf(
        "procedural recent reads are bounded to 64 skill contracts 64 generalization profiles and 32 repair patterns",
        "procedural index entries must resolve to correct-kind decodable signature-matching snapshots or fail visible",
        "skill guidance remains qualified by current ToolDescriptor capability surface and live world-state preconditions",
        "generalization guidance remains qualified by transferable evidence active base skill live tools and current preconditions",
        "repair strategy support remains gated by live GoalRepairValidation requalification",
        "procedural hardening reuses existing SkillGenesis SkillGeneralization and GoalRepairStrategyMemory stores with no second procedural database"
    )

    @Test
    fun phase675ArchitectureLocksArePresent() {
        assertTrue(OmegaArchitectureLock.invariants.containsAll(invariants))
    }

    @Test
    fun phase675M6CriteriaArePresent() {
        val actual = OmegaArchitectureLock.milestones
            .single { it.id == OmegaMilestoneId.M6_COGNITIVE_MEMORY }
            .exitCriteria
            .toSet()
        assertTrue(actual.containsAll(criteria))
    }

    @Test
    fun proceduralReadBudgetsRemainMobileFinite() {
        assertTrue(ProceduralMemoryReadPolicy.MAX_SKILL_RECENT <= 64)
        assertTrue(ProceduralMemoryReadPolicy.MAX_GENERALIZATION_RECENT <= 64)
        assertTrue(ProceduralMemoryReadPolicy.MAX_REPAIR_RECENT <= 32)
    }
}
