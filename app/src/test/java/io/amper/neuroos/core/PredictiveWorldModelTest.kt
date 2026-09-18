package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictiveWorldModelTest {
    @Test
    fun structuredStateRecordsTemporalTransitionAndCurrentState() {
        val memory = InMemoryMemoryOs()
        val model = MemoryBackedPredictiveWorldModel(memory, clock = { 2_100L })
        val key = WorldStateKey("device", "mode")

        val first = model.observe(observation(key, "wifi", 0.90, 1_000L, "e1"))
        val second = model.observe(observation(key, "cellular", 0.95, 2_000L, "e2"))

        assertEquals("cellular", model.current(key)?.value)
        val transitions = model.transitions(key)
        assertEquals(1, transitions.size)
        assertEquals("wifi", transitions.single().fromValue)
        assertEquals("cellular", transitions.single().toValue)
        assertEquals(first.id, transitions.single().sourceStateId)
        assertEquals(second.id, transitions.single().targetStateId)
    }

    @Test
    fun invalidationTurnsCurrentStateUnknownAndStopsPrediction() {
        val memory = InMemoryMemoryOs()
        val model = MemoryBackedPredictiveWorldModel(memory, clock = { 2_100L })
        val key = WorldStateKey("network", "reachable")

        model.observe(observation(key, "true", 0.95, 1_000L, "e1"))
        val invalid = model.invalidate(
            key = key,
            evidenceIds = listOf(MemoryId("conflict")),
            observedAtEpochMs = 2_000L
        )

        assertEquals(StructuredWorldStateStatus.UNKNOWN, invalid.status)
        assertNull(invalid.value)
        assertEquals(StructuredWorldStateStatus.UNKNOWN, model.current(key)?.status)
        assertNull(model.predict(key))
        assertEquals(1, model.transitions(key).size)
        assertNull(model.transitions(key).single().toValue)
    }

    @Test
    fun repeatedTemporalOrderProducesExplicitCausalHypothesisNotCausalFact() {
        val memory = InMemoryMemoryOs()
        var now = 700L
        val model = MemoryBackedPredictiveWorldModel(memory, clock = { now })
        val cause = WorldStateKey("charger", "connected")
        val effect = WorldStateKey("battery", "charging")

        model.observe(observation(cause, "false", 0.95, 100L, "c0"))
        model.observe(observation(effect, "false", 0.95, 150L, "b0"))

        model.observe(observation(cause, "true", 0.95, 200L, "c1"))
        model.observe(observation(effect, "true", 0.95, 250L, "b1"))
        model.observe(observation(cause, "false", 0.95, 300L, "c2"))
        model.observe(observation(effect, "false", 0.95, 350L, "b2"))

        model.observe(observation(cause, "true", 0.95, 400L, "c3"))
        model.observe(observation(effect, "true", 0.95, 450L, "b3"))
        model.observe(observation(cause, "false", 0.95, 500L, "c4"))
        model.observe(observation(effect, "false", 0.95, 550L, "b4"))

        model.observe(observation(cause, "true", 0.95, 600L, "c5"))
        model.observe(observation(effect, "true", 0.95, 650L, "b5"))

        val hypotheses = model.causalHypotheses(effect, limit = 16)
        val matching = hypotheses.firstOrNull {
            it.causeKey.canonical == cause.canonical &&
                it.causeValue == "true" &&
                it.effectValue == "true"
        }

        assertTrue(matching != null)
        assertTrue(requireNotNull(matching).support >= 2)
        assertTrue(matching.confidence in 0.0..1.0)
        assertTrue(matching.meanLagMs > 0L)
    }

    @Test
    fun temporalPredictionIsEvaluatedAgainstLaterObservation() {
        val memory = InMemoryMemoryOs()
        var now = 550L
        val model = MemoryBackedPredictiveWorldModel(memory, clock = { now })
        val key = WorldStateKey("workload", "state")

        model.observe(observation(key, "idle", 0.90, 100L, "s1"))
        model.observe(observation(key, "busy", 0.90, 200L, "s2"))
        model.observe(observation(key, "idle", 0.90, 300L, "s3"))
        model.observe(observation(key, "busy", 0.90, 400L, "s4"))
        model.observe(observation(key, "idle", 0.90, 500L, "s5"))

        val prediction = requireNotNull(model.predict(key, horizonMs = 1_000L))
        assertEquals(WorldPredictionBasis.TEMPORAL_TRANSITION, prediction.basis)
        assertEquals("busy", prediction.predictedValue)

        now = 600L
        model.observe(observation(key, "busy", 0.90, 600L, "s6"))
        val outcome = model.predictionOutcomes().first { it.predictionId == prediction.id }

        assertEquals(WorldPredictionOutcomeStatus.CONFIRMED, outcome.status)
        assertEquals("busy", outcome.actualValue)
    }

    @Test
    fun disconfirmedPredictionCalibratesLaterConfidenceDownward() {
        val memory = InMemoryMemoryOs()
        var now = 550L
        val model = MemoryBackedPredictiveWorldModel(memory, clock = { now })
        val key = WorldStateKey("runtime", "load")

        model.observe(observation(key, "idle", 0.90, 100L, "r1"))
        model.observe(observation(key, "busy", 0.90, 200L, "r2"))
        model.observe(observation(key, "idle", 0.90, 300L, "r3"))
        model.observe(observation(key, "busy", 0.90, 400L, "r4"))
        model.observe(observation(key, "idle", 0.90, 500L, "r5"))

        val first = requireNotNull(model.predict(key, horizonMs = 1_000L))
        assertEquals("busy", first.predictedValue)

        now = 600L
        model.observe(observation(key, "offline", 0.90, 600L, "r6"))
        assertEquals(
            WorldPredictionOutcomeStatus.DISCONFIRMED,
            model.predictionOutcomes().first { it.predictionId == first.id }.status
        )

        model.observe(observation(key, "idle", 0.90, 700L, "r7"))
        now = 750L
        val second = requireNotNull(model.predict(key, horizonMs = 1_000L))

        assertEquals(WorldPredictionBasis.TEMPORAL_TRANSITION, second.basis)
        assertEquals("busy", second.predictedValue)
        assertTrue(second.confidence < first.confidence)
    }

    @Test
    fun repeatedPredictionOnSameStateIsIdempotentUntilResolvedOrExpired() {
        val memory = InMemoryMemoryOs()
        val model = MemoryBackedPredictiveWorldModel(memory, clock = { 1_100L })
        val key = WorldStateKey("screen", "orientation")
        model.observe(observation(key, "portrait", 0.90, 1_000L, "o1"))

        val first = requireNotNull(model.predict(key, horizonMs = 1_000L))
        val before = memory.size()
        val second = requireNotNull(model.predict(key, horizonMs = 1_000L))

        assertEquals(first.id, second.id)
        assertEquals(before, memory.size())
        assertEquals(WorldPredictionBasis.PERSISTENCE_PRIOR, first.basis)
        assertEquals("portrait", first.predictedValue)
    }

    private fun observation(
        key: WorldStateKey,
        value: String,
        confidence: Double,
        observedAt: Long,
        evidence: String
    ): WorldStateObservation = WorldStateObservation(
        key = key,
        value = value,
        confidence = confidence,
        evidenceIds = listOf(MemoryId(evidence)),
        observedAtEpochMs = observedAt
    )
}
