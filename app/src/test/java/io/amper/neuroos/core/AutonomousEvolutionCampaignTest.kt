package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class AutonomousEvolutionCampaignTest {
    @Test
    fun gapDetectorPrioritizesWeakerWeightedMetric() {
        val suite = suite("gap-suite")
        val baseline = baseline(
            suite = suite,
            planning = 0.96,
            generalization = 0.72
        )

        val gaps = EvolutionGapDetector.detect(suite, baseline)

        assertEquals(2, gaps.size)
        assertEquals(
            EvolutionBenchmarkMetricId("generalization"),
            gaps.first().metricId
        )
        assertTrue(gaps.first().priority > gaps.last().priority)
        assertTrue(gaps.all { it.targetScore >= it.currentScore })
    }

    @Test
    fun inferenceGeneratorUsesOneInferenceAndStrictDataOnlyProtocol() {
        val request = generationRequest("generator")
        val output = listOf(
            "AMPER_EVOLUTION_BLUEPRINTS_V1",
            blueprintLine(
                kind = AutonomousEvolutionCandidateKind.CODE,
                description = "Refine planner scoring",
                payload = "PATCH planner scoring logic",
                metrics = "planning,generalization"
            )
        ).joinToString("\n")
        val inference = RecordingInference(output)
        val generator = InferenceEvolutionCandidateGenerator(inference)

        val batch = generator.generate(request).getOrThrow()

        assertEquals(1, inference.requests.size)
        assertEquals(1, batch.blueprints.size)
        assertEquals(AutonomousEvolutionCandidateKind.CODE, batch.blueprints.single().kind)
        assertEquals(
            setOf(
                EvolutionBenchmarkMetricId("planning"),
                EvolutionBenchmarkMetricId("generalization")
            ),
            batch.blueprints.single().targetMetrics
        )
        val captured = inference.requests.single()
        assertEquals(setOf(TitanCapabilities.REASONING), captured.requiredCapabilities)
        assertTrue(
            captured.preferredCapabilityProfiles.any {
                TitanCapabilities.CODE_GENERATION in it
            }
        )
    }

    @Test
    fun modelCannotAppendBuildOrBenchmarkClaimsOutsideBlueprintProtocol() {
        val request = generationRequest("protocol-reject")
        val output = listOf(
            "AMPER_EVOLUTION_BLUEPRINTS_V1",
            blueprintLine(
                AutonomousEvolutionCandidateKind.CODE,
                "Candidate",
                "patch",
                "planning"
            ),
            "TESTS=PASS"
        ).joinToString("\n")
        val generator = InferenceEvolutionCandidateGenerator(
            RecordingInference(output)
        )

        val result = generator.generate(request)

        assertTrue(result.isFailure)
    }

    @Test
    fun campaignBuildsSandboxEntriesRunsTournamentAndCreatesPromotionTicket() {
        val fixture = campaignFixture("winner")
        val first = blueprint(
            id = "1".repeat(24),
            description = "Candidate A",
            payload = "SECRET_PAYLOAD_A"
        )
        val second = blueprint(
            id = "2".repeat(24),
            description = "Candidate B",
            payload = "SECRET_PAYLOAD_B"
        )
        val generator = fixedGenerator(first, second)
        val sandbox = EvolutionSandboxRunner { bp, _ ->
            val metrics = if (bp.id == first.id) {
                observations(0.97, 0.86)
            } else {
                observations(0.98, 0.94)
            }
            Result.success(
                sandboxEvidence(
                    suffix = bp.id.take(4),
                    metrics = metrics,
                    invariants = fixture.invariants
                )
            )
        }
        val coordinator = MemoryBackedAutonomousEvolutionCampaignCoordinator(
            memory = fixture.memory,
            evolution = fixture.evolution,
            generator = generator,
            sandbox = sandbox,
            clock = { 50_000L }
        )

        val result = coordinator.run(
            campaignId = EvolutionCampaignId("campaign-winner"),
            baselineRevision = "base-winner",
            suiteId = fixture.suite.id,
            baseline = fixture.baseline,
            allowedKinds = setOf(AutonomousEvolutionCandidateKind.CODE),
            maxCandidates = 2
        )

        assertEquals(2, result.candidateIds.size)
        assertNotNull(result.tournamentDecision)
        val proposal = requireNotNull(result.promotionProposal)
        assertEquals(
            result.tournamentDecision?.winnerCandidateId,
            proposal.candidateId
        )
        assertTrue(fixture.evolution.isKnownPromotionProposal(proposal))
        assertFalse(result.livePromoted)
        assertFalse(result.authorityBearing)

        assertTrue(fixture.memory.recall("SECRET_PAYLOAD_A", 20).isEmpty())
        assertTrue(fixture.memory.recall("SECRET_PAYLOAD_B", 20).isEmpty())
    }

    @Test
    fun sandboxFailureIsExcludedAndCannotManufactureTournamentEvidence() {
        val fixture = campaignFixture("sandbox-fail")
        val candidate = blueprint(
            id = "3".repeat(24),
            description = "Broken candidate",
            payload = "BROKEN_PATCH"
        )
        val coordinator = MemoryBackedAutonomousEvolutionCampaignCoordinator(
            memory = fixture.memory,
            evolution = fixture.evolution,
            generator = fixedGenerator(candidate),
            sandbox = EvolutionSandboxRunner { _, _ ->
                Result.failure(IllegalStateException("sandbox build failed"))
            },
            clock = { 51_000L }
        )

        val result = coordinator.run(
            campaignId = EvolutionCampaignId("campaign-sandbox-fail"),
            baselineRevision = "base-sandbox-fail",
            suiteId = fixture.suite.id,
            baseline = fixture.baseline,
            allowedKinds = setOf(AutonomousEvolutionCandidateKind.CODE),
            maxCandidates = 1
        )

        assertTrue(result.candidateIds.isEmpty())
        assertNull(result.tournamentDecision)
        assertNull(result.promotionProposal)
    }

    @Test
    fun candidateIdentityIsDerivedFromBlueprintAndSandboxArtifact() {
        val fixture = campaignFixture("identity")
        val candidate = blueprint(
            id = "4".repeat(24),
            description = "Identity candidate",
            payload = "PATCH_IDENTITY"
        )
        val artifact = "c".repeat(64)
        val coordinator = MemoryBackedAutonomousEvolutionCampaignCoordinator(
            memory = fixture.memory,
            evolution = fixture.evolution,
            generator = fixedGenerator(candidate),
            sandbox = EvolutionSandboxRunner { _, _ ->
                Result.success(
                    EvolutionSandboxCandidateEvidence(
                        artifactDigest = artifact,
                        proposedRevision = "sandbox-proposed-revision",
                        metrics = observations(0.99, 0.93),
                        sandboxPassed = true,
                        testsPassed = true,
                        invariantResults = fixture.invariants,
                        rollbackToken = "rollback-identity",
                        observedAtEpochMs = 40_000L
                    )
                )
            },
            clock = { 52_000L }
        )

        val result = coordinator.run(
            campaignId = EvolutionCampaignId("campaign-identity"),
            baselineRevision = "base-identity",
            suiteId = fixture.suite.id,
            baseline = fixture.baseline,
            allowedKinds = setOf(AutonomousEvolutionCandidateKind.CODE),
            maxCandidates = 1
        )

        val candidateId = result.candidateIds.single()
        assertTrue(candidateId.value.startsWith("candidate-"))
        assertFalse(candidateId.value.contains(candidate.id))
        assertEquals(candidateId, result.promotionProposal?.candidateId)
        assertEquals(artifact, result.promotionProposal?.artifactDigest)
        assertEquals("sandbox-proposed-revision", result.promotionProposal?.proposedRevision)
    }

    @Test
    fun runtimeCanCreateEvolutionCampaignCoordinatorWithoutEmbeddingSandboxInKernel() {
        val runtime = AmperRuntime.reference()
        val inference = RecordingInference(
            listOf(
                "AMPER_EVOLUTION_BLUEPRINTS_V1",
                blueprintLine(
                    AutonomousEvolutionCandidateKind.STRATEGY,
                    "Strategy candidate",
                    "adjust strategy weights",
                    "planning"
                )
            ).joinToString("\n")
        )
        val sandbox = EvolutionSandboxRunner { _, _ ->
            Result.failure(IllegalStateException("not executed in factory test"))
        }

        val coordinator = runtime.evolutionCampaign(inference, sandbox)

        assertNotNull(coordinator)
    }

    private data class CampaignFixture(
        val memory: MemoryOs,
        val evolution: AutonomousEvolutionModel,
        val suite: EvolutionBenchmarkSuite,
        val baseline: EvolutionBenchmarkSnapshot,
        val invariants: Map<String, Boolean>
    )

    private fun campaignFixture(suffix: String): CampaignFixture {
        val memory = InMemoryMemoryOs()
        val self = CanonicalSelfModel()
        val gate = CanonicalEvolutionGate(self)
        var now = 10_000L
        val evolution = MemoryBackedAutonomousEvolutionModel(
            memory = memory,
            gate = gate,
            clock = { now++ }
        )
        val suite = suite("campaign-suite-" + suffix)
        evolution.putSuite(suite)
        return CampaignFixture(
            memory = memory,
            evolution = evolution,
            suite = suite,
            baseline = baseline(suite, 0.95, 0.80),
            invariants = self.snapshot().invariants.associateWith { true }
        )
    }

    private fun generationRequest(suffix: String): EvolutionCandidateGenerationRequest {
        val suite = suite("generation-suite-" + suffix)
        val baseline = baseline(suite, 0.94, 0.78)
        return EvolutionCandidateGenerationRequest(
            campaignId = EvolutionCampaignId("generation-" + suffix),
            baselineRevision = "base-" + suffix,
            suite = suite,
            baseline = baseline,
            objectives = EvolutionGapDetector.detect(suite, baseline),
            allowedKinds = setOf(
                AutonomousEvolutionCandidateKind.CODE,
                AutonomousEvolutionCandidateKind.STRATEGY
            ),
            maxCandidates = 2
        )
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
            createdAtEpochMs = 1_000L
        )

    private fun baseline(
        suite: EvolutionBenchmarkSuite,
        planning: Double,
        generalization: Double
    ): EvolutionBenchmarkSnapshot =
        EvolutionBenchmarkSnapshot(
            subjectId = EvolutionBenchmarkSubjectId("baseline-" + suite.id.value),
            suiteId = suite.id,
            suiteDigest = suite.canonicalDigest,
            metrics = observations(planning, generalization),
            artifactDigest = "a".repeat(64),
            observedAtEpochMs = 2_000L
        )

    private fun blueprint(
        id: String,
        description: String,
        payload: String
    ): EvolutionCandidateBlueprint =
        EvolutionCandidateBlueprint(
            id = id,
            kind = AutonomousEvolutionCandidateKind.CODE,
            description = description,
            payload = payload,
            targetMetrics = setOf(
                EvolutionBenchmarkMetricId("planning"),
                EvolutionBenchmarkMetricId("generalization")
            )
        )

    private fun fixedGenerator(
        vararg candidates: EvolutionCandidateBlueprint
    ): EvolutionCandidateGeneratorPort =
        EvolutionCandidateGeneratorPort {
            Result.success(
                EvolutionCandidateGenerationBatch(
                    blueprints = candidates.toList(),
                    backendId = "fake-generator",
                    modelId = ModelId("fake-generator-model")
                )
            )
        }

    private fun sandboxEvidence(
        suffix: String,
        metrics: Map<EvolutionBenchmarkMetricId, EvolutionMetricObservation>,
        invariants: Map<String, Boolean>
    ): EvolutionSandboxCandidateEvidence =
        EvolutionSandboxCandidateEvidence(
            artifactDigest = suffix.padEnd(64, 'b').take(64),
            proposedRevision = "sandbox-revision-" + suffix,
            metrics = metrics,
            sandboxPassed = true,
            testsPassed = true,
            invariantResults = invariants,
            rollbackToken = "rollback-" + suffix,
            observedAtEpochMs = 30_000L
        )

    private fun observations(
        planning: Double,
        generalization: Double,
        samples: Int = 64
    ): Map<EvolutionBenchmarkMetricId, EvolutionMetricObservation> = mapOf(
        EvolutionBenchmarkMetricId("planning") to
            EvolutionMetricObservation(planning, samples),
        EvolutionBenchmarkMetricId("generalization") to
            EvolutionMetricObservation(generalization, samples)
    )

    private fun blueprintLine(
        kind: AutonomousEvolutionCandidateKind,
        description: String,
        payload: String,
        metrics: String
    ): String =
        "C|" + kind.name + "|" + b64(description) + "|" + b64(payload) + "|" + metrics

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private class RecordingInference(
        private val output: String
    ) : CognitiveInferencePort {
        val requests = mutableListOf<InferenceRequest>()

        override fun infer(request: InferenceRequest): Result<InferenceResponse> {
            requests += request
            return Result.success(
                InferenceResponse(
                    modelId = ModelId("generator-model"),
                    backendId = "generator-backend",
                    text = output
                )
            )
        }
    }
}
