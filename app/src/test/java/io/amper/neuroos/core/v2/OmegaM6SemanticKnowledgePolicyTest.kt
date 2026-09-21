package io.amper.neuroos.core.v2

import io.amper.neuroos.core.SemanticKnowledgePolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaM6SemanticKnowledgePolicyTest {
    private val invariants = setOf(
        "semantic-memory-query-and-reconcile-are-mobile-bounded",
        "semantic-memory-current-view-requires-fresh-epistemic-lineage",
        "semantic-memory-stale-evidence-is-hidden-before-reconciliation",
        "semantic-memory-changed-evidence-requires-new-version",
        "semantic-memory-corruption-fails-visible",
        "semantic-memory-uses-existing-memory-os-without-second-index",
        "semantic-memory-remains-non-authority"
    )

    private val criteria = setOf(
        "semantic query and reconcile expose at most 16 results with at most 192 candidate semantic records scanned",
        "semantic current and query results require planning-eligible current epistemic evidence with matching value and evidence lineage",
        "stale contested uncertain or changed evidence hides stored active semantic knowledge until canonical reconcile revises or retracts it",
        "semantic record corruption fails visible instead of being silently skipped",
        "semantic freshness hardening reuses MemoryBackedSemanticKnowledgeStore and MemoryOs with no second semantic index or model store"
    )

    @Test
    fun phase674ArchitectureLocksArePresent() {
        assertTrue(OmegaArchitectureLock.invariants.containsAll(invariants))
    }

    @Test
    fun phase674M6CriteriaArePresent() {
        val actual = OmegaArchitectureLock.milestones
            .single { it.id == OmegaMilestoneId.M6_COGNITIVE_MEMORY }
            .exitCriteria
            .toSet()
        assertTrue(actual.containsAll(criteria))
    }

    @Test
    fun semanticBudgetsRemainMobileFinite() {
        assertTrue(SemanticKnowledgePolicy.DEFAULT_MAX_QUERY_RESULTS <= 16)
        assertTrue(SemanticKnowledgePolicy.DEFAULT_MAX_CANDIDATE_SCAN <= 192)
        assertTrue(SemanticKnowledgePolicy.DEFAULT_MAX_FRESHNESS_CHECKS <= 64)
    }
}
