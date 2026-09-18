package io.amper.neuroos.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticKnowledgeContextTest {
    @Test
    fun supportedBeliefIsConsolidatedAndRenderedOnlyThroughGovernedSemanticChannel() {
        val memory = InMemoryMemoryOs()
        val epistemic = MemoryBackedEpistemicState(memory, clock = { 2_000L }, staleAfterMs = 10_000L)
        val semantic = MemoryBackedSemanticKnowledgeStore(memory, epistemic, clock = { 2_100L })
        epistemic.observe(claim("camera", "available", "true", 0.95, 1_000L, "camera-status"))

        val context = source(memory, epistemic, semantic)
        val prompt = context.groundedPrompt("camera available")

        assertTrue(prompt.contains("semantic_knowledge:"))
        assertTrue(prompt.contains("subject=camera predicate=available value=true"))
        assertTrue(prompt.contains("status=SUPPORTED"))
        assertFalse(prompt.contains("kind=epistemic-claim"))
        assertFalse(prompt.contains("kind=semantic-knowledge"))
        assertFalse(prompt.contains("payload="))
    }

    @Test
    fun newlyContestedBeliefRetractsSemanticKnowledgeAndCannotLeakOldValueThroughRawMemory() {
        val memory = InMemoryMemoryOs()
        var now = 2_000L
        val epistemic = MemoryBackedEpistemicState(memory, clock = { now }, staleAfterMs = 10_000L)
        val semantic = MemoryBackedSemanticKnowledgeStore(memory, epistemic, clock = { now })
        epistemic.observe(claim("service", "healthy", "true", 0.95, 1_000L, "probe-a"))

        val context = source(memory, epistemic, semantic)
        val first = context.groundedPrompt("service healthy")
        assertTrue(first.contains("semantic_knowledge:"))
        assertTrue(first.contains("value=true"))

        now = 2_500L
        epistemic.observe(claim("service", "healthy", "false", 0.95, 2_400L, "probe-b"))
        val second = context.groundedPrompt("service healthy")

        assertTrue(second.contains("status=CONTESTED"))
        assertTrue(second.contains("value=unknown"))
        assertTrue(second.contains("planning_eligible=false"))
        assertFalse(second.contains("semantic_knowledge:"))
        assertFalse(second.contains("kind=epistemic-claim"))
        assertFalse(second.contains("kind=semantic-knowledge"))
        assertFalse(second.contains("kind=semantic-retraction"))
        assertFalse(second.contains("payload="))
    }

    private fun source(
        memory: MemoryOs,
        epistemic: EpistemicState,
        semantic: SemanticKnowledgeStore
    ): CanonicalSovereignContextSource = CanonicalSovereignContextSource(
        workspace = InMemoryWorkspace(),
        memory = memory,
        selfModel = CanonicalSelfModel(),
        goals = CanonicalGoalSystem(),
        world = CanonicalWorldModel(),
        epistemic = epistemic,
        semanticKnowledgeStore = semantic
    )

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
            source = "context-test",
            producer = producer,
            observedAtEpochMs = observedAt,
            confidence = confidence
        )
    )
}
