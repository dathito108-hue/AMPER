package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ProceduralMemoryPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaM6ProceduralMemoryPolicyTest {
    private val invariants = setOf(
        "procedural-memory-retrieval-is-mobile-bounded",
        "skill-guidance-requires-current-active-skill-and-live-capabilities",
        "generalization-guidance-rebinds-current-skill-snapshot",
        "generalization-guidance-requires-transfer-qualified-profile",
        "repair-strategy-support-requires-live-requalification",
        "procedural-memory-remains-advisory-and-non-authority",
        "procedural-memory-reuses-existing-memory-os-stores"
    )

    private val criteria = setOf(
        "skill and generalization recent retrieval expose at most 64 records while repair-strategy retrieval exposes at most 32 patterns",
        "skill guidance requires an ACTIVE non-authority skill whose capabilities remain allowed and present on the live tool surface",
        "generalization guidance and chains require a TRANSFERABLE or GENERALIZED profile and rebind the current ACTIVE skill snapshot before use",
        "repair-strategy support is zero unless live GoalRepairValidation requalification remains positive and structural similarity is at least 0.75",
        "procedural memory hardening reuses existing skill generalization and repair stores in MemoryOs without a second procedural database"
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
    fun proceduralBudgetsStayMobileFinite() {
        assertTrue(ProceduralMemoryPolicy.MAX_SKILL_RECENT <= 64)
        assertTrue(ProceduralMemoryPolicy.MAX_GENERALIZATION_RECENT <= 64)
        assertTrue(ProceduralMemoryPolicy.MAX_REPAIR_RECENT <= 32)
    }
}
