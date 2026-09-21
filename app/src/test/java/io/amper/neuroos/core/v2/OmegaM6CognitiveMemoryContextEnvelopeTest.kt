package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CognitiveMemoryContextEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaM6CognitiveMemoryContextEnvelopeTest {
    private val invariants = setOf(
        "cognitive-memory-context-has-one-cross-domain-mobile-envelope",
        "runtime-context-has-explicit-episodic-lane",
        "episodic-v1-is-not-duplicated-in-generic-context-memory",
        "working-episodic-semantic-procedural-context-quotas-are-bounded",
        "cognitive-memory-context-envelope-persists-no-state",
        "cognitive-memory-context-overrequests-clamp-without-expansion",
        "integrated-cognitive-digest-binds-explicit-episodic-evidence"
    )

    private val criteria = setOf(
        "canonical planning context applies one bounded envelope across working episodic semantic and procedural memory lanes",
        "runtime context retrieves at most 6 explicit episodic entries and excludes episodic-v1 from generic sovereign-memory projection",
        "working and generic memory caller over-requests are clamped to the canonical context envelope",
        "semantic context exposes at most 6 current semantic entries and 6 epistemic beliefs while procedural strategy evidence exposes at most 4 entries",
        "the cross-domain envelope persists no state and creates no memory database cache or scheduler",
        "integrated cognitive state digest binds episodic id origin importance and fingerprint without copying raw episodic content"
    )

    @Test
    fun phase676ArchitectureLocksArePresent() {
        assertTrue(OmegaArchitectureLock.invariants.containsAll(invariants))
    }

    @Test
    fun phase676M6CriteriaArePresent() {
        val actual = OmegaArchitectureLock.milestones
            .single { it.id == OmegaMilestoneId.M6_COGNITIVE_MEMORY }
            .exitCriteria
            .toSet()
        assertTrue(actual.containsAll(criteria))
    }

    @Test
    fun totalDefaultCognitiveMemoryEnvelopeIsFinite() {
        assertEquals(28, CognitiveMemoryContextEnvelope().maxTotalCognitiveItems)
    }
}
