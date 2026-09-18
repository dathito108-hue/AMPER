package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpistemicStateTest {
    @Test
    fun compatibleEvidenceBecomesSupportedAndKeepsProvenance() {
        val memory = InMemoryMemoryOs()
        val state = MemoryBackedEpistemicState(memory, clock = { 2_000L }, staleAfterMs = 10_000L)

        val first = state.observe(claim("device", "thermal", "normal", 0.90, 1_000L, "sensor-a"))
        val second = state.observe(claim("device", "thermal", "normal", 0.80, 1_500L, "sensor-b"))

        val assessment = requireNotNull(state.assess("device", "thermal"))
        assertEquals(EpistemicStatus.SUPPORTED, assessment.status)
        assertEquals("normal", assessment.preferredValue)
        assertEquals(2, assessment.evidenceCount)
        assertEquals(setOf(first, second), assessment.evidenceIds.toSet())
        assertTrue(assessment.planningEligible)
        assertFalse(assessment.authorityBearing)
    }

    @Test
    fun conflictingValuesBecomeContestedRatherThanFabricatedCertainty() {
        val memory = InMemoryMemoryOs()
        val state = MemoryBackedEpistemicState(memory, clock = { 3_000L }, staleAfterMs = 10_000L)

        state.observe(claim("network", "reachable", "true", 0.95, 1_000L, "probe-a"))
        state.observe(claim("network", "reachable", "false", 0.90, 2_000L, "probe-b"))

        val assessment = requireNotNull(state.assess("network", "reachable"))
        assertEquals(EpistemicStatus.CONTESTED, assessment.status)
        assertEquals(setOf("false", "true"), assessment.competingValues)
        assertFalse(assessment.planningEligible)
        assertTrue(assessment.confidence in 0.0..1.0)
    }

    @Test
    fun independentFreshCorroborationReconcilesConflictAndRetainsCompetingEvidence() {
        val memory = InMemoryMemoryOs()
        val state = MemoryBackedEpistemicState(memory, clock = { 10_000L }, staleAfterMs = 10_000L)

        state.observe(claim("service", "reachable", "true", 0.95, 9_500L, "probe-a"))
        state.observe(claim("service", "reachable", "true", 0.90, 9_000L, "probe-b"))
        state.observe(claim("service", "reachable", "false", 0.60, 1_000L, "legacy-probe"))

        val assessment = requireNotNull(state.assess("service", "reachable"))
        assertEquals(EpistemicStatus.RECONCILED, assessment.status)
        assertEquals(EpistemicResolutionReason.INDEPENDENT_CORROBORATION, assessment.resolutionReason)
        assertEquals("true", assessment.preferredValue)
        assertEquals(2, assessment.independentProducerCount)
        assertEquals(setOf("false", "true"), assessment.competingValues)
        assertTrue(assessment.winningSupport > assessment.competingSupport)
        assertTrue(assessment.planningEligible)
        assertFalse(assessment.authorityBearing)
    }

    @Test
    fun repeatedClaimsFromOneProducerCannotManufactureIndependentConsensus() {
        val memory = InMemoryMemoryOs()
        val state = MemoryBackedEpistemicState(memory, clock = { 10_000L }, staleAfterMs = 10_000L)

        repeat(12) { index ->
            state.observe(
                claim(
                    "sensor",
                    "healthy",
                    "false",
                    0.95,
                    9_900L - index,
                    "single-repeater"
                )
            )
        }
        state.observe(claim("sensor", "healthy", "true", 0.80, 9_700L, "independent-a"))
        state.observe(claim("sensor", "healthy", "true", 0.80, 9_600L, "independent-b"))

        val assessment = requireNotNull(state.assess("sensor", "healthy"))
        assertEquals(EpistemicStatus.CONTESTED, assessment.status)
        assertEquals(EpistemicResolutionReason.UNRESOLVED_CONFLICT, assessment.resolutionReason)
        assertEquals(2, assessment.independentProducerCount)
        assertEquals(14, assessment.evidenceCount)
        assertFalse(assessment.planningEligible)
    }

    @Test
    fun lowConfidenceAndOldEvidenceAreNotPromotedToCurrentFact() {
        val memory = InMemoryMemoryOs()
        var now = 2_000L
        val state = MemoryBackedEpistemicState(memory, clock = { now }, staleAfterMs = 5_000L)

        state.observe(claim("battery", "health", "good", 0.40, 1_000L, "estimate"))
        assertEquals(EpistemicStatus.UNCERTAIN, state.assess("battery", "health")?.status)

        now = 8_000L
        assertEquals(EpistemicStatus.STALE, state.assess("battery", "health")?.status)
    }

    @Test
    fun sovereignContextExposesEpistemicStateAsNonAuthoritativeData() {
        val memory = InMemoryMemoryOs()
        val epistemic = MemoryBackedEpistemicState(memory, clock = { 2_000L }, staleAfterMs = 10_000L)
        epistemic.observe(claim("camera", "available", "true", 0.90, 1_000L, "device-status"))

        val context = CanonicalSovereignContextSource(
            workspace = InMemoryWorkspace(),
            memory = memory,
            selfModel = CanonicalSelfModel(),
            goals = CanonicalGoalSystem(),
            world = CanonicalWorldModel(),
            epistemic = epistemic
        )
        val snapshot = context.capture("camera", memoryLimit = 0, worldLimit = 0, workspaceLimit = 0)
        assertEquals(1, snapshot.epistemicBeliefs.size)
        assertFalse(snapshot.epistemicBeliefs.single().authorityBearing)

        val prompt = context.groundedPrompt("camera status")
        assertTrue(prompt.contains("epistemic_beliefs:"))
        assertTrue(prompt.contains("authority=false"))
        assertTrue(prompt.contains("status=SUPPORTED"))
    }

    @Test
    fun unresolvedConflictIsRenderedAsUnknownForPlanning() {
        val memory = InMemoryMemoryOs()
        val epistemic = MemoryBackedEpistemicState(memory, clock = { 3_000L }, staleAfterMs = 10_000L)
        epistemic.observe(claim("network", "reachable", "true", 0.95, 1_000L, "probe-a"))
        epistemic.observe(claim("network", "reachable", "false", 0.90, 2_000L, "probe-b"))

        val context = CanonicalSovereignContextSource(
            workspace = InMemoryWorkspace(),
            memory = memory,
            selfModel = CanonicalSelfModel(),
            goals = CanonicalGoalSystem(),
            world = CanonicalWorldModel(),
            epistemic = epistemic
        )

        val prompt = context.groundedPrompt("network reachable")
        assertTrue(prompt.contains("status=CONTESTED"))
        assertTrue(prompt.contains("resolution=UNRESOLVED_CONFLICT"))
        assertTrue(prompt.contains("value=unknown"))
        assertTrue(prompt.contains("planning_eligible=false"))
        assertTrue(prompt.contains("alternatives=false|true"))
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