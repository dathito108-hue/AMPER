package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticKnowledgeTest {
    @Test
    fun supportedSingleSourceConsolidatesAsProvisionalOnly() {
        val memory = InMemoryMemoryOs()
        val semantic = MemoryBackedSemanticKnowledgeStore(memory, clock = { 2_000L })
        val epistemic = MemoryBackedEpistemicState(
            memory = memory,
            clock = { 2_000L },
            staleAfterMs = 10_000L,
            semantic = semantic
        )

        epistemic.observe(claim("device", "mode", "ready", 0.95, 1_500L, "sensor-a"))

        val current = requireNotNull(semantic.current("device", "mode"))
        assertEquals("ready", current.value)
        assertEquals(SemanticKnowledgeTier.PROVISIONAL, current.tier)
        assertFalse(current.planningEligible)
        assertFalse(current.authorityBearing)
    }

    @Test
    fun independentConsistentEvidencePromotesCorroboratedKnowledgeWithLineage() {
        val memory = InMemoryMemoryOs()
        val semantic = MemoryBackedSemanticKnowledgeStore(memory, clock = { 3_000L })
        val epistemic = MemoryBackedEpistemicState(
            memory = memory,
            clock = { 3_000L },
            staleAfterMs = 10_000L,
            semantic = semantic
        )

        val first = epistemic.observe(claim("device", "mode", "ready", 0.95, 1_500L, "sensor-a"))
        val provisional = requireNotNull(semantic.current("device", "mode"))
        val second = epistemic.observe(claim("device", "mode", "ready", 0.90, 2_000L, "sensor-b"))

        val current = requireNotNull(semantic.current("device", "mode"))
        assertEquals(SemanticKnowledgeTier.CORROBORATED, current.tier)
        assertTrue(current.planningEligible)
        assertEquals(setOf(first, second), current.evidenceIds)
        assertEquals(provisional.revisionId, current.previousRevisionId)
        assertNotEquals(provisional.revisionId, current.revisionId)
        assertNotNull(semantic.revision(provisional.revisionId))
    }

    @Test
    fun unresolvedConflictDoesNotOverwriteCurrentSemanticKnowledge() {
        val memory = InMemoryMemoryOs()
        var now = 3_000L
        val semantic = MemoryBackedSemanticKnowledgeStore(memory, clock = { now })
        val epistemic = MemoryBackedEpistemicState(
            memory = memory,
            clock = { now },
            staleAfterMs = 10_000L,
            semantic = semantic
        )

        epistemic.observe(claim("network", "reachable", "true", 0.95, 1_500L, "probe-a"))
        epistemic.observe(claim("network", "reachable", "true", 0.90, 2_000L, "probe-b"))
        val beforeConflict = requireNotNull(semantic.current("network", "reachable"))

        epistemic.observe(claim("network", "reachable", "false", 0.95, 2_500L, "probe-c"))
        val assessment = requireNotNull(epistemic.assess("network", "reachable"))
        assertEquals(EpistemicStatus.CONTESTED, assessment.status)

        val afterConflict = requireNotNull(semantic.current("network", "reachable"))
        assertEquals(beforeConflict.revisionId, afterConflict.revisionId)
        assertEquals("true", afterConflict.value)
    }

    @Test
    fun reconciledNewEvidenceSupersedesKnowledgeWithoutDestroyingOldRevision() {
        val memory = InMemoryMemoryOs()
        var now = 3_000L
        val semantic = MemoryBackedSemanticKnowledgeStore(memory, clock = { now })
        val epistemic = MemoryBackedEpistemicState(
            memory = memory,
            clock = { now },
            staleAfterMs = 10_000L,
            semantic = semantic
        )

        epistemic.observe(claim("service", "reachable", "true", 0.95, 1_500L, "probe-a"))
        epistemic.observe(claim("service", "reachable", "true", 0.90, 2_000L, "probe-b"))
        val old = requireNotNull(semantic.current("service", "reachable"))
        assertEquals(SemanticKnowledgeTier.CORROBORATED, old.tier)

        now = 11_000L
        val falseA = epistemic.observe(claim("service", "reachable", "false", 0.97, 10_500L, "probe-c"))
        val falseB = epistemic.observe(claim("service", "reachable", "false", 0.96, 10_600L, "probe-d"))

        val assessment = requireNotNull(epistemic.assess("service", "reachable"))
        assertEquals(EpistemicStatus.RECONCILED, assessment.status)
        assertEquals("false", assessment.preferredValue)

        val current = requireNotNull(semantic.current("service", "reachable"))
        assertEquals("false", current.value)
        assertEquals(SemanticKnowledgeTier.RECONCILED, current.tier)
        assertEquals(old.revisionId, current.previousRevisionId)
        assertTrue(current.evidenceIds.contains(falseA))
        assertTrue(current.evidenceIds.contains(falseB))
        assertEquals("true", requireNotNull(semantic.revision(old.revisionId)).value)
    }

    @Test
    fun sovereignContextSeparatesSemanticKnowledgeFromRawEpistemicEvidence() {
        val memory = InMemoryMemoryOs()
        val semantic = MemoryBackedSemanticKnowledgeStore(memory, clock = { 3_000L })
        val epistemic = MemoryBackedEpistemicState(
            memory = memory,
            clock = { 3_000L },
            staleAfterMs = 10_000L,
            semantic = semantic
        )
        epistemic.observe(claim("camera", "available", "true", 0.95, 1_500L, "device-a"))
        epistemic.observe(claim("camera", "available", "true", 0.90, 2_000L, "device-b"))

        val context = CanonicalSovereignContextSource(
            workspace = InMemoryWorkspace(),
            memory = memory,
            selfModel = CanonicalSelfModel(),
            goals = CanonicalGoalSystem(),
            world = CanonicalWorldModel(),
            epistemic = epistemic,
            semantic = semantic
        )

        val snapshot = context.capture("camera", memoryLimit = 0, worldLimit = 0, workspaceLimit = 0)
        assertEquals(1, snapshot.semanticKnowledge.size)
        assertTrue(snapshot.semanticKnowledge.single().planningEligible)

        val prompt = context.groundedPrompt("camera available")
        assertTrue(prompt.contains("semantic_knowledge:"))
        assertTrue(prompt.contains("tier=CORROBORATED"))
        assertTrue(prompt.contains("planning_eligible=true"))
        assertTrue(prompt.contains("authority=false"))
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
            source = "test-evidence",
            producer = producer,
            observedAtEpochMs = observedAt,
            confidence = confidence
        )
    )
}
