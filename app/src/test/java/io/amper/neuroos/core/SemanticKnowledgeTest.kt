package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticKnowledgeTest {
    @Test
    fun supportedBeliefConsolidatesIntoDurableSemanticKnowledgeWithEvidenceLineage() {
        val memory = InMemoryMemoryOs()
        val epistemic = MemoryBackedEpistemicState(memory, clock = { 2_000L }, staleAfterMs = 10_000L)
        val evidenceA = epistemic.observe(claim("device", "mode", "mobile", 0.95, 1_000L, "sensor-a"))
        val evidenceB = epistemic.observe(claim("device", "mode", "mobile", 0.90, 1_500L, "sensor-b"))
        val knowledge = MemoryBackedSemanticKnowledgeStore(memory, epistemic, clock = { 2_100L })

        val transition = knowledge.consolidate("device", "mode")
        val current = requireNotNull(transition.current)

        assertEquals(SemanticKnowledgeTransitionKind.CREATED, transition.kind)
        assertEquals("mobile", current.value)
        assertEquals(SemanticKnowledgeStatus.ACTIVE, current.status)
        assertEquals(setOf(evidenceA, evidenceB), current.evidenceIds.toSet())
        val record = memory.get(current.id)
        assertNotNull(record)
        assertEquals(MemoryBackedSemanticKnowledgeStore.KNOWLEDGE_KIND, record?.kind)
        assertEquals(setOf(evidenceA, evidenceB), record?.provenance?.parents)
    }

    @Test
    fun repeatedReconcileIsIdempotentWhenEvidenceHasNotChanged() {
        val memory = InMemoryMemoryOs()
        val epistemic = MemoryBackedEpistemicState(memory, clock = { 2_000L }, staleAfterMs = 10_000L)
        epistemic.observe(claim("camera", "available", "true", 0.90, 1_000L, "device-status"))
        var time = 2_100L
        val knowledge = MemoryBackedSemanticKnowledgeStore(memory, epistemic, clock = { time++ })

        val first = knowledge.consolidate("camera", "available")
        val beforeSize = memory.size()
        val second = knowledge.consolidate("camera", "available")

        assertEquals(SemanticKnowledgeTransitionKind.CREATED, first.kind)
        assertEquals(SemanticKnowledgeTransitionKind.UNCHANGED, second.kind)
        assertEquals(beforeSize, memory.size())
        assertEquals(first.current?.id, second.current?.id)
    }

    @Test
    fun changedSupportedBeliefCreatesNewVersionThatSupersedesOldKnowledge() {
        val memory = InMemoryMemoryOs()
        var now = 1_200L
        val epistemic = MemoryBackedEpistemicState(memory, clock = { now }, staleAfterMs = 10_000L)
        epistemic.observe(claim("network", "route", "wifi", 0.90, 1_000L, "route-a"))
        epistemic.observe(claim("network", "route", "wifi", 0.90, 1_100L, "route-b"))
        val knowledge = MemoryBackedSemanticKnowledgeStore(memory, epistemic, clock = { now })

        val first = requireNotNull(knowledge.consolidate("network", "route").current)
        now = 10_000L
        epistemic.observe(claim("network", "route", "cellular", 0.99, 9_900L, "route-c"))
        epistemic.observe(claim("network", "route", "cellular", 0.99, 9_800L, "route-d"))

        val revised = knowledge.consolidate("network", "route")
        val current = requireNotNull(revised.current)

        assertEquals(SemanticKnowledgeTransitionKind.REVISED, revised.kind)
        assertEquals(first.id, current.supersedes)
        assertEquals("cellular", current.value)
        assertEquals(current.id, knowledge.current("network", "route")?.id)
        assertTrue(memory.get(current.id)?.provenance?.parents?.contains(first.id) == true)
    }

    @Test
    fun contestedBeliefRetractsPreviouslyActiveSemanticKnowledge() {
        val memory = InMemoryMemoryOs()
        var now = 2_000L
        val epistemic = MemoryBackedEpistemicState(memory, clock = { now }, staleAfterMs = 10_000L)
        val firstEvidence = epistemic.observe(claim("service", "healthy", "true", 0.95, 1_000L, "probe-a"))
        val knowledge = MemoryBackedSemanticKnowledgeStore(memory, epistemic, clock = { now })
        val active = requireNotNull(knowledge.consolidate("service", "healthy").current)

        now = 2_500L
        val conflict = epistemic.observe(claim("service", "healthy", "false", 0.95, 2_400L, "probe-b"))
        val transition = knowledge.consolidate("service", "healthy")

        assertEquals(SemanticKnowledgeTransitionKind.RETRACTED, transition.kind)
        assertEquals(active.id, transition.previous?.id)
        assertNull(transition.current)
        assertNull(knowledge.current("service", "healthy"))

        val retractionRecords = memory.recall("service healthy", 16)
            .filter { it.kind == MemoryBackedSemanticKnowledgeStore.RETRACTION_KIND }
        assertEquals(1, retractionRecords.size)
        assertTrue(retractionRecords.single().provenance.parents.contains(active.id))
        assertTrue(retractionRecords.single().provenance.parents.contains(firstEvidence))
        assertTrue(retractionRecords.single().provenance.parents.contains(conflict))
    }

    @Test
    fun reconcileReturnsOnlyCurrentActiveKnowledgeAndNeverRetractedVersion() {
        val memory = InMemoryMemoryOs()
        var now = 2_000L
        val epistemic = MemoryBackedEpistemicState(memory, clock = { now }, staleAfterMs = 10_000L)
        epistemic.observe(claim("battery", "charging", "true", 0.95, 1_000L, "battery-a"))
        val knowledge = MemoryBackedSemanticKnowledgeStore(memory, epistemic, clock = { now })

        val active = knowledge.reconcile("battery charging", 4)
        assertEquals(1, active.size)
        assertEquals("true", active.single().value)

        now = 2_500L
        epistemic.observe(claim("battery", "charging", "false", 0.95, 2_400L, "battery-b"))
        val afterConflict = knowledge.reconcile("battery charging", 4)

        assertTrue(afterConflict.isEmpty())
        assertFalse(memory.recall("battery charging", 16).isEmpty())
    }

    private fun claim(
        subject: String,
        predicate: String,
        value: String,
        confidence: Double,
        observedAt: Long,
        producer: String
    ): EpistemicClaim = EpistemicClaim(
        subject = subject,
        predicate = predicate,
        value = value,
        confidence = confidence,
        provenance = Provenance(
            source = "semantic-test",
            producer = producer,
            observedAtEpochMs = observedAt,
            confidence = confidence
        )
    )
}
