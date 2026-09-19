package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousEvolutionTournamentTest {
    private val selfModel = CanonicalSelfModel()

    @Test
    fun objectivelyBetterCandidateWinsAndProducesNonLivePromotionProposal() {
        val fixture = fixture()
        val candidate = candidate(
            id = "candidate-better",
            artifact = "b".repeat(64),
            scores = mapOf(
                "planning" to 0.98,
                "generalization" to 0.88
            )
        )

        val decision = fixture.model.runTournament(
            id = EvolutionTournamentId("tournament-better"),
            baseline = fixture.baseline,
            candidates = listOf(candidate)
        )

        assertEquals(EvolutionId("candidate-better"), decision.winnerCandidateId)
        val outcome = decision.outcomes.single()
        assertTrue(outcome.tournamentEligible)
        assertTrue(outcome.noMaterialRegression)
        assertTrue(outcome.aggregateDelta > MemoryBackedAutonomousEvolutionModel.MIN_AGGREGATE_IMPROVEMENT)

        val proposal = requireNotNull(fixture.model.promotionProposal(decision))
        assertEquals(EvolutionId("candidate-better"), proposal.candidateId)
        assertEquals(EvolutionStage.VERIFIED, proposal.verifiedDecision.stage)
        assertFalse(proposal.livePromoted)
        assertFalse(proposal.authorityBearing)
    }

    @Test
    fun materialRegressionBlocksCandidateEvenWhenAggregateScoreImproves() {
        val fixture = fixture()
        val candidate = candidate(
            id = "candidate-regression",
            artifact = "c".repeat(64),
            scores = mapOf(
                "planning" to 0.92,
                "generalization" to 0.99
            )
        )

        val decision = fixture.model.runTournament(
            EvolutionTournamentId("tournament-regression"),
            fixture.baseline,
            listOf(candidate)
        )

        assertNull(decision.winnerCandidateId)
        val outcome = decision.outcomes.single()
        assertFalse(outcome.noMaterialRegression)
        assertFalse(outcome.tournamentEligible)
        assertTrue(outcome.aggregateDelta > 0.0)
        assertTrue(outcome.reasons.any { it.contains("regression tolerance") })
        assertNull(fixture.model.promotionProposal(decision))
    }

    @Test
    fun canonicalInvariantFailureBlocksOtherwiseBetterCandidate() {
        val fixture = fixture()
        val failedInvariant = selfModel.snapshot().invariants.first()
        val candidate = candidate(
            id = "candidate-invariant",
            artifact = "d".repeat(64),
            scores = mapOf(
                "planning" to 0.99,
                "generalization" to 0.91
            ),
            invariantOverrides = mapOf(failedInvariant to false)
        )

        val decision = fixture.model.runTournament(
            EvolutionTournamentId("tournament-invariant"),
            fixture.baseline,
            listOf(candidate)
        )

        val outcome = decision.outcomes.single()
        assertNull(decision.winnerCandidateId)
        assertFalse(outcome.tournamentEligible)
        assertEquals(EvolutionStage.REJECTED, outcome.verificationDecision.stage)
        assertTrue(
            outcome.reasons.any { it.contains("canonical invariant failed") }
        )
    }

    @Test
    fun missingRollbackTokenBlocksPromotionEligibility() {
        val fixture = fixture()
        val candidate = candidate(
            id = "candidate-no-rollback",
            artifact = "e".repeat(64),
            scores = mapOf(
                "planning" to 0.99,
                "generalization" to 0.92
            ),
            rollbackToken = null
        )

        val decision = fixture.model.runTournament(
            EvolutionTournamentId("tournament-no-rollback"),
            fixture.baseline,
            listOf(candidate)
        )

        assertNull(decision.winnerCandidateId)
        val outcome = decision.outcomes.single()
        assertFalse(outcome.tournamentEligible)
        assertTrue(outcome.reasons.any { it.contains("rollback token missing") })
    }

    @Test
    fun insufficientCandidateSamplesFailBenchmarkGate() {
        val fixture = fixture()
        val candidate = candidate(
            id = "candidate-sparse",
            artifact = "f".repeat(64),
            scores = mapOf(
                "planning" to 1.0,
                "generalization" to 1.0
            ),
            samples = 4
        )

        val decision = fixture.model.runTournament(
            EvolutionTournamentId("tournament-sparse"),
            fixture.baseline,
            listOf(candidate)
        )

        assertNull(decision.winnerCandidateId)
        val outcome = decision.outcomes.single()
        assertFalse(outcome.benchmarkEligible)
        assertTrue(outcome.reasons.any { it.contains("insufficient candidate samples") })
    }

    @Test
    fun tournamentChoosesLargestEligibleWeightedImprovementDeterministically() {
        val fixture = fixture()
        val first = candidate(
            id = "candidate-a",
            artifact = "1".repeat(64),
            scores = mapOf(
                "planning" to 0.98,
                "generalization" to 0.86
            )
        )
        val second = candidate(
            id = "candidate-b",
            artifact = "2".repeat(64),
            scores = mapOf(
                "planning" to 0.97,
                "generalization" to 0.93
            )
        )

        val decision = fixture.model.runTournament(
            EvolutionTournamentId("tournament-multi"),
            fixture.baseline,
            listOf(first, second)
        )

        assertEquals(EvolutionId("candidate-b"), decision.winnerCandidateId)
        val firstOutcome = decision.outcomes.single { it.candidateId.value == "candidate-a" }
        val secondOutcome = decision.outcomes.single { it.candidateId.value == "candidate-b" }
        assertTrue(secondOutcome.aggregateDelta > firstOutcome.aggregateDelta)
        assertTrue(firstOutcome.tournamentEligible)
        assertTrue(secondOutcome.tournamentEligible)
    }

    @Test
    fun baselineWithInsufficientSamplesCannotStartTournament() {
        val fixture = fixture()
        val sparseBaseline = fixture.baseline.copy(
            metrics = fixture.baseline.metrics.mapValues { (_, observation) ->
                observation.copy(samples = 1)
            }
        )
        val candidate = candidate(
            id = "candidate-baseline-check",
            artifact = "3".repeat(64),
            scores = mapOf(
                "planning" to 0.99,
                "generalization" to 0.90
            )
        )

        val result = runCatching {
            fixture.model.runTournament(
                EvolutionTournamentId("tournament-invalid-baseline"),
                sparseBaseline,
                listOf(candidate)
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun baselineWithoutArtifactDigestCannotStartTournament() {
        val fixture = fixture()
        val unboundBaseline = fixture.baseline.copy(artifactDigest = null)
        val candidate = candidate(
            id = "candidate-unbound-baseline",
            artifact = "4".repeat(64),
            scores = mapOf(
                "planning" to 0.99,
                "generalization" to 0.90
            )
        )

        val result = runCatching {
            fixture.model.runTournament(
                EvolutionTournamentId("tournament-unbound-baseline"),
                unboundBaseline,
                listOf(candidate)
            )
        }

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("content-addressed") == true
        )
    }

    @Test
    fun runtimeExposesEvolutionTournamentSubsystem() {
        val runtime = AmperRuntime.reference()
        val suite = suite("runtime-suite")

        runtime.autonomousEvolution.putSuite(suite)

        assertEquals(
            suite,
            runtime.autonomousEvolution.getSuite(suite.id)
        )
    }

    private data class Fixture(
        val model: AutonomousEvolutionModel,
        val suite: EvolutionBenchmarkSuite,
        val baseline: EvolutionBenchmarkSnapshot
    )

    private fun fixture(): Fixture {
        val memory = InMemoryMemoryOs()
        var now = 1000L
        val model = MemoryBackedAutonomousEvolutionModel(
            memory = memory,
            gate = CanonicalEvolutionGate(selfModel),
            clock = { now++ }
        )
        val suite = suite("canonical-evolution-suite")
        model.putSuite(suite)
        val baseline = EvolutionBenchmarkSnapshot(
            subjectId = EvolutionBenchmarkSubjectId("canonical-baseline"),
            suiteId = suite.id,
            suiteDigest = suite.canonicalDigest,
            metrics = observations(
                scores = mapOf(
                    "planning" to 0.95,
                    "generalization" to 0.80
                ),
                samples = 64
            ),
            artifactDigest = "a".repeat(64),
            observedAtEpochMs = 900L
        )
        return Fixture(model, suite, baseline)
    }

    private fun suite(id: String): EvolutionBenchmarkSuite =
        EvolutionBenchmarkSuite(
            id = EvolutionBenchmarkSuiteId(id),
            metrics = listOf(
                EvolutionBenchmarkMetric(
                    id = EvolutionBenchmarkMetricId("planning"),
                    weight = 0.4,
                    minSamples = 32,
                    minCandidateScore = 0.90,
                    regressionTolerance = 0.01
                ),
                EvolutionBenchmarkMetric(
                    id = EvolutionBenchmarkMetricId("generalization"),
                    weight = 0.6,
                    minSamples = 32,
                    minCandidateScore = 0.80,
                    regressionTolerance = 0.02
                )
            ),
            createdAtEpochMs = 500L
        )

    private fun candidate(
        id: String,
        artifact: String,
        scores: Map<String, Double>,
        samples: Int = 64,
        invariantOverrides: Map<String, Boolean> = emptyMap(),
        rollbackToken: String? = "rollback-$id"
    ): EvolutionTournamentEntry {
        val suite = suite("canonical-evolution-suite")
        val invariants = selfModel.snapshot().invariants
            .associateWith { true }
            .toMutableMap()
            .also { it.putAll(invariantOverrides) }

        val candidate = EvolutionCandidate(
            id = EvolutionId(id),
            description = "bounded autonomous evolution candidate",
            baseRevision = "canonical-base",
            proposedRevision = "candidate-$id",
            artifactDigest = artifact
        )
        return EvolutionTournamentEntry(
            candidate = candidate,
            kind = AutonomousEvolutionCandidateKind.CODE,
            benchmark = EvolutionBenchmarkSnapshot(
                subjectId = EvolutionBenchmarkSubjectId(id),
                suiteId = suite.id,
                suiteDigest = suite.canonicalDigest,
                metrics = observations(scores, samples),
                artifactDigest = artifact,
                observedAtEpochMs = 950L
            ),
            verification = VerificationEvidence(
                sandboxPassed = true,
                testsPassed = true,
                invariantResults = invariants,
                rollbackToken = rollbackToken
            )
        )
    }

    private fun observations(
        scores: Map<String, Double>,
        samples: Int
    ): Map<EvolutionBenchmarkMetricId, EvolutionMetricObservation> =
        scores.mapKeys { EvolutionBenchmarkMetricId(it.key) }
            .mapValues { EvolutionMetricObservation(it.value, samples) }
}