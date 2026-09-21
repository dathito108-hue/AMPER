package io.amper.neuroos.core.v2

import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaM6CognitiveMemoryContractTest {
    private val requiredInvariants = setOf(
        "m6-cognitive-memory-has-one-durable-memory-os",
        "working-memory-is-global-workspace-plus-memory-os-continuity",
        "episodic-semantic-procedural-memory-share-canonical-memory-os",
        "cognitive-memory-domains-are-non-authority",
        "cognitive-memory-has-no-model-or-backend-specific-silo",
        "m6-memory-topology-is-contract-not-second-memory-facade"
    )

    private val requiredCriteria = setOf(
        "canonical cognitive memory topology exposes working episodic semantic and procedural domains over one durable MemoryOs",
        "working memory uses the existing GlobalWorkspace for transient cognition while durable continuity snapshots remain in the same MemoryOs",
        "episodic semantic and procedural durable memory are canonical MemoryOs domains rather than model or backend specific stores",
        "cognitive memory is evidence and state only and carries no tool approval scheduler or execution authority",
        "the M6 topology seal exposes no second remember recall or persistence facade"
    )

    @Test
    fun m6TopologyArchitectureLocksArePresent() {
        assertTrue(OmegaArchitectureLock.invariants.containsAll(requiredInvariants))
    }

    @Test
    fun m6TopologyExitCriteriaArePresent() {
        val criteria = OmegaArchitectureLock.milestones
            .single { it.id == OmegaMilestoneId.M6_COGNITIVE_MEMORY }
            .exitCriteria
            .toSet()
        assertTrue(criteria.containsAll(requiredCriteria))
    }
}
