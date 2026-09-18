package io.amper.neuroos.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictiveWorldContextTest {
    @Test
    fun semanticKnowledgeFeedsStructuredWorldStateAndBoundedPredictionContext() {
        val memory = InMemoryMemoryOs()
        var now = 2_000L
        val epistemic = MemoryBackedEpistemicState(memory, clock = { now }, staleAfterMs = 10_000L)
        val semantic = MemoryBackedSemanticKnowledgeStore(memory, epistemic, clock = { now })
        val predictive = MemoryBackedPredictiveWorldModel(memory, clock = { now })

        epistemic.observe(claim("device", "mode", "mobile", 0.95, 1_000L, "sensor-a"))
        val context = source(memory, epistemic, semantic, predictive)
        val prompt = context.groundedPrompt("device mode")

        assertTrue(prompt.contains("semantic_knowledge:"))
        assertTrue(prompt.contains("predictive_world_state:"))
        assertTrue(prompt.contains("entity=device attribute=mode value=mobile status=KNOWN"))
        assertTrue(prompt.contains("world_predictions:"))
        assertTrue(prompt.contains("predicted=mobile"))
        assertTrue(prompt.contains("basis=PERSISTENCE_PRIOR"))
        assertFalse(prompt.contains("kind=predictive-world-state"))
        assertFalse(prompt.contains("kind=predictive-world-prediction"))
        assertFalse(prompt.contains("payload="))
    }

    @Test
    fun contestedBeliefInvalidatesStructuredStateAndRemovesPredictionFromContext() {
        val memory = InMemoryMemoryOs()
        var now = 2_000L
        val epistemic = MemoryBackedEpistemicState(memory, clock = { now }, staleAfterMs = 10_000L)
        val semantic = MemoryBackedSemanticKnowledgeStore(memory, epistemic, clock = { now })
        val predictive = MemoryBackedPredictiveWorldModel(memory, clock = { now })
        val context = source(memory, epistemic, semantic, predictive)

        epistemic.observe(claim("network", "reachable", "true", 0.95, 1_000L, "probe-a"))
        val first = context.groundedPrompt("network reachable")
        assertTrue(first.contains("status=KNOWN"))
        assertTrue(first.contains("predicted=true"))

        now = 2_500L
        epistemic.observe(claim("network", "reachable", "false", 0.95, 2_400L, "probe-b"))
        val second = context.groundedPrompt("network reachable")

        assertTrue(second.contains("status=CONTESTED"))
        assertTrue(second.contains("planning_eligible=false"))
        assertTrue(second.contains("predictive_world_state:"))
        assertTrue(second.contains("entity=network attribute=reachable value=unknown status=UNKNOWN"))
        assertFalse(second.contains("semantic_knowledge:"))
        assertFalse(second.contains("world_predictions:"))
        assertFalse(second.contains("kind=predictive-world-state"))
        assertFalse(second.contains("kind=predictive-world-transition"))
        assertFalse(second.contains("kind=predictive-world-prediction"))
        assertFalse(second.contains("kind=predictive-world-prediction-outcome"))
    }

    private fun source(
        memory: MemoryOs,
        epistemic: EpistemicState,
        semantic: SemanticKnowledgeStore,
        predictive: PredictiveWorldModel
    ): CanonicalSovereignContextSource = CanonicalSovereignContextSource(
        workspace = InMemoryWorkspace(),
        memory = memory,
        selfModel = CanonicalSelfModel(),
        goals = CanonicalGoalSystem(),
        world = CanonicalWorldModel(),
        epistemic = epistemic,
        semanticKnowledgeStore = semantic,
        predictiveWorld = predictive
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
            source = "predictive-context-test",
            producer = producer,
            observedAtEpochMs = observedAt,
            confidence = confidence
        )
    )
}
