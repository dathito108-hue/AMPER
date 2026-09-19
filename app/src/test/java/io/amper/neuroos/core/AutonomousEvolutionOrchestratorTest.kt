package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousEvolutionOrchestratorTest {
    @Test
    fun oneCycleClosesCampaignPromotionCanaryAndBaselineAdvance() {
        val fixture = fixture("commit")
        val orchestrator = fixture.orchestrator(
            canaryScores = { 0.99 to 0.93 }
        )

        val result = orchestrator.runBounded(
            id = EvolutionAutonomyRunId("run-commit"),
            suiteId = fixture.suite.id,
            maxCycles = 1
        )

        assertEquals(1, result.cycles.size)
        val cycle = result.cycles.single()
        assertEquals(EvolutionAutonomyCycleStage.COMMITTED, cycle.stage)
        assertEquals(1, result.committedCount)
        assertEquals(fixture.deployment.activeRevision, fixture.baseline.current().revision)
        assertEquals(cycle.nextBaselineRevision, fixture.baseline.current().revision)
        assertFalse(result.authorityBearing)
    }

    @Test
    fun canaryRegressionRollsBackAndDoesNotAdvanceBaseline() {
        val fixture = fixture("canary-rollback")
        val initial = fixture.baseline.current()
        val orchestrator = fixture.orchestrator(
            canaryScores = { 0.90 to 0.99 }
        )

        val result = orchestrator.runBounded(
            id = EvolutionAutonomyRunId("run-canary-rollback"),
            suiteId = fixture.suite.id,
            maxCycles = 1
        )

        assertEquals(EvolutionAutonomyCycleStage.ROLLED_BACK, result.terminalStage)
        assertEquals(initial, fixture.baseline.current())
        assertEquals(initial.revision, fixture.deployment.activeRevision)
        assertTrue(fixture.deployment.rollbackCalls >= 1)
    }

    @Test
    fun failedBaselineAdvanceRollsBackAlreadyCommittedDeployment() {
        val fixture = fixture("baseline-cas")
        val initial = fixture.baseline.current()
        fixture.baseline.failAdvance = true
        val orchestrator = fixture.orchestrator(
            canaryScores = { 0.99 to 0.94 }
        )

        val result = orchestrator.runBounded(
            id = EvolutionAutonomyRunId("run-baseline-cas"),
            suiteId = fixture.suite.id,
            maxCycles = 1
        )

        assertEquals(EvolutionAutonomyCycleStage.ROLLED_BACK, result.terminalStage)
        assertEquals(initial, fixture.baseline.current())
        assertEquals(initial.revision, fixture.deployment.activeRevision)
        assertTrue(fixture.deployment.rollbackCalls >= 1)
    }

    @Test
    fun noRemainingBenchmarkGapStopsBeforeGeneratorSandboxOrDeployment() {
        val fixture = fixture(
            suffix = "no-gap",
            planning = 1.0,
            generalization = 1.0
        )
        val orchestrator = fixture.orchestrator(
            canaryScores = { 1.0 to 1.0 }
        )

        val result = orchestrator.runBounded(
            id = EvolutionAutonomyRunId("run-no-gap"),
            suiteId = fixture.suite.id,
            maxCycles = 4
        )

        assertEquals(EvolutionAutonomyCycleStage.NO_GAP, result.terminalStage)
        assertEquals(0, fixture.generatorCalls)
        assertEquals(0, fixture.sandboxCalls)
        assertEquals(0, fixture.deployment.checkpointCalls)
    }

    @Test
    fun boundedRunCannotExceedHardCycleCap() {
        val fixture = fixture(
            suffix = "hard-cap",
            planning = 0.90,
            generalization = 0.80
        )
        val orchestrator = fixture.orchestrator(
            canaryScores = {
                val current = fixture.baseline.current().benchmark
                val p = requireNotNull(
                    current.metrics[EvolutionBenchmarkMetricId("planning")]
                ).score
                val g = requireNotNull(
                    current.metrics[EvolutionBenchmarkMetricId("generalization")]
                ).score
                (p + 0.015).coerceAtMost(0.99) to
                    (g + 0.025).coerceAtMost(0.99)
            }
        )

        val result = orchestrator.runBounded(
            id = EvolutionAutonomyRunId("run-hard-cap"),
            suiteId = fixture.suite.id,
            maxCycles = 2
        )

        assertEquals(2, result.cycles.size)
        assertEquals(2, result.committedCount)
        assertTrue(result.cycles.all { it.stage == EvolutionAutonomyCycleStage.COMMITTED })
    }

    @Test
    fun unresolvedRecoveryBlocksAnyNewCampaign() {
        val fixture = fixture("recovery-block")
        val seedCampaign = fixture.campaign()
        val campaignResult = seedCampaign.run(
            campaignId = EvolutionCampaignId("seed-recovery"),
            baselineRevision = fixture.baseline.current().revision,
            suiteId = fixture.suite.id,
            baseline = fixture.baseline.current().benchmark,
            allowedKinds = setOf(AutonomousEvolutionCandidateKind.CODE),
            maxCandidates = 1
        )
        val proposal = requireNotNull(campaignResult.promotionProposal)
        val prepared = fixture.executor.prepare(
            proposal,
            fixture.baseline.current().benchmark,
            fixture.deployment
        )
        fixture.executor.apply(prepared.id, proposal, fixture.deployment)
        fixture.deployment.failRollback = true
        val recoveryRequired = fixture.executor.canaryAndCommit(
            id = prepared.id,
            proposal = proposal,
            baseline = fixture.baseline.current().benchmark,
            deployment = fixture.deployment,
            canary = fixture.canaryEvaluator { 0.70 to 0.70 }
        )
        assertEquals(
            EvolutionPromotionExecutionStage.RECOVERY_REQUIRED,
            recoveryRequired.stage
        )
        val generatorCallsBefore = fixture.generatorCalls
        val orchestrator = fixture.orchestrator(
            canaryScores = { 0.99 to 0.93 }
        )

        val result = orchestrator.runBounded(
            id = EvolutionAutonomyRunId("run-recovery-block"),
            suiteId = fixture.suite.id,
            maxCycles = 1
        )

        assertEquals(EvolutionAutonomyCycleStage.RECOVERY_BLOCKED, result.terminalStage)
        assertEquals(generatorCallsBefore, fixture.generatorCalls)
    }

    @Test
    fun runtimeCanConstructBoundedAutonomyCoordinator() {
        val runtime = AmperRuntime.reference()
        val baseline = SimpleBaselineStore(
            EvolutionBaselineState(
                revision = "runtime-base",
                benchmark = EvolutionBenchmarkSnapshot(
                    subjectId = EvolutionBenchmarkSubjectId("runtime-baseline"),
                    suiteId = EvolutionBenchmarkSuiteId("runtime-suite"),
                    suiteDigest = "a".repeat(64),
                    metrics = mapOf(
                        EvolutionBenchmarkMetricId("planning") to
                            EvolutionMetricObservation(0.9, 64)
                    ),
                    artifactDigest = "b".repeat(64),
                    observedAtEpochMs = 1L
                )
            )
        )
        val inference = CognitiveInferencePort {
            Result.failure(IllegalStateException("factory-only"))
        }
        val sandbox = EvolutionSandboxRunner { _, _ ->
            Result.failure(IllegalStateException("factory-only"))
        }
        val deployment = FakeDeploymentPort("runtime-base")
        val canary = EvolutionCanaryEvaluator { _, _ ->
            Result.failure(IllegalStateException("factory-only"))
        }

        val orchestrator = runtime.evolutionAutonomy(
            inference = inference,
            sandbox = sandbox,
            baseline = baseline,
            deployment = deployment,
            canary = canary
        )

        assertNotNull(orchestrator)
    }

    private class Fixture(
        val memory: MemoryOs,
        val selfModel: CanonicalSelfModel,
        val gate: VerifiedEvolution,
        val evolution: AutonomousEvolutionModel,
        val executor: AutonomousEvolutionPromotionExecutor,
        val suite: EvolutionBenchmarkSuite,
        val baseline: SimpleBaselineStore,
        val deployment: FakeDeploymentPort
    ) {
        var generatorCalls: Int = 0
        var sandboxCalls: Int = 0
        var artifactCounter: Int = 0

        fun campaign(): MemoryBackedAutonomousEvolutionCampaignCoordinator =
            MemoryBackedAutonomousEvolutionCampaignCoordinator(
                memory = memory,
                evolution = evolution,
                generator = EvolutionCandidateGeneratorPort { request ->
                    generatorCalls += 1
                    val blueprint = EvolutionCandidateBlueprint(
                        id = (generatorCalls.toString().padStart(24, '1')).takeLast(24),
                        kind = AutonomousEvolutionCandidateKind.CODE,
                        description = "Improve current benchmark gaps",
                        payload = "bounded candidate payload " + generatorCalls,
                        targetMetrics = request.objectives.map { it.metricId }.toSet()
                    )
                    Result.success(
                        EvolutionCandidateGenerationBatch(
                            blueprints = listOf(blueprint),
                            backendId = "orchestrator-test-generator",
                            modelId = ModelId("orchestrator-test-model")
                        )
                    )
                },
                sandbox = EvolutionSandboxRunner { _, request ->
                    sandboxCalls += 1
                    artifactCounter += 1
                    val currentPlanning = requireNotNull(
                        request.baseline.metrics[EvolutionBenchmarkMetricId("planning")]
                    ).score
                    val currentGeneralization = requireNotNull(
                        request.baseline.metrics[EvolutionBenchmarkMetricId("generalization")]
                    ).score
                    Result.success(
                        EvolutionSandboxCandidateEvidence(
                            artifactDigest = artifactCounter.toString(16).padStart(64, 'c').takeLast(64),
                            proposedRevision = request.baselineRevision + "-candidate-" + artifactCounter,
                            metrics = observations(
                                planning = (currentPlanning + 0.03).coerceAtMost(1.0),
                                generalization = (currentGeneralization + 0.05).coerceAtMost(1.0),
                                samples = 64
                            ),
                            sandboxPassed = true,
                            testsPassed = true,
                            invariantResults = selfModel.snapshot().invariants.associateWith { true },
                            rollbackToken = "rollback-" + artifactCounter,
                            observedAtEpochMs = 30_000L + artifactCounter
                        )
                    )
                },
                clock = { 40_000L + generatorCalls }
            )

        fun canaryEvaluator(
            scores: () -> Pair<Double, Double>
        ): EvolutionCanaryEvaluator =
            EvolutionCanaryEvaluator { proposal, receipt ->
                val (planning, generalization) = scores()
                Result.success(
                    EvolutionBenchmarkSnapshot(
                        subjectId = EvolutionBenchmarkSubjectId(proposal.candidateId.value),
                        suiteId = suite.id,
                        suiteDigest = suite.canonicalDigest,
                        metrics = observations(planning, generalization, 64),
                        artifactDigest = proposal.artifactDigest,
                        observedAtEpochMs = receipt.appliedAtEpochMs + 1
                    )
                )
            }

        fun orchestrator(
            canaryScores: () -> Pair<Double, Double>
        ): AutonomousEvolutionOrchestrator =
            AutonomousEvolutionOrchestrator(
                evolution = evolution,
                campaign = campaign(),
                promotion = executor,
                baseline = baseline,
                deployment = deployment,
                canary = canaryEvaluator(canaryScores),
                clock = { 50_000L + generatorCalls }
            )
    }

    private fun fixture(
        suffix: String,
        planning: Double = 0.95,
        generalization: Double = 0.84
    ): Fixture {
        val memory = InMemoryMemoryOs()
        val self = CanonicalSelfModel()
        val gate = CanonicalEvolutionGate(self)
        var now = 10_000L
        val evolution = MemoryBackedAutonomousEvolutionModel(
            memory = memory,
            gate = gate,
            clock = { now++ }
        )
        val suite = EvolutionBenchmarkSuite(
            id = EvolutionBenchmarkSuiteId("autonomy-suite-" + suffix),
            metrics = listOf(
                EvolutionBenchmarkMetric(
                    id = EvolutionBenchmarkMetricId("planning"),
                    weight = 0.45,
                    minSamples = 32,
                    minCandidateScore = 0.90,
                    regressionTolerance = 0.01
                ),
                EvolutionBenchmarkMetric(
                    id = EvolutionBenchmarkMetricId("generalization"),
                    weight = 0.55,
                    minSamples = 32,
                    minCandidateScore = 0.80,
                    regressionTolerance = 0.02
                )
            ),
            createdAtEpochMs = 1_000L
        )
        evolution.putSuite(suite)
        val initial = EvolutionBaselineState(
            revision = "base-" + suffix,
            benchmark = EvolutionBenchmarkSnapshot(
                subjectId = EvolutionBenchmarkSubjectId("baseline-" + suffix),
                suiteId = suite.id,
                suiteDigest = suite.canonicalDigest,
                metrics = observations(planning, generalization, 64),
                artifactDigest = "a".repeat(64),
                observedAtEpochMs = 2_000L
            )
        )
        val baseline = SimpleBaselineStore(initial)
        val deployment = FakeDeploymentPort(initial.revision)
        val executor = MemoryBackedAutonomousEvolutionPromotionExecutor(
            memory = memory,
            evolution = evolution,
            gate = gate,
            clock = { now++ }
        )
        return Fixture(
            memory = memory,
            selfModel = self,
            gate = gate,
            evolution = evolution,
            executor = executor,
            suite = suite,
            baseline = baseline,
            deployment = deployment
        )
    }

    private class SimpleBaselineStore(
        initial: EvolutionBaselineState
    ) : EvolutionBaselinePort {
        private var state: EvolutionBaselineState = initial
        var failAdvance: Boolean = false

        override fun current(): EvolutionBaselineState = state

        override fun advance(
            expected: EvolutionBaselineState,
            proposal: EvolutionPromotionProposal,
            receipt: EvolutionDeploymentReceipt,
            canary: EvolutionBenchmarkSnapshot
        ): Result<EvolutionBaselineState> = runCatching {
            if (failAdvance) error("baseline compare-and-set failed")
            require(state == expected) { "stale baseline compare-and-set" }
            require(receipt.activeRevision == proposal.proposedRevision)
            require(receipt.artifactDigest == proposal.artifactDigest)
            require(canary.artifactDigest == proposal.artifactDigest)
            require(canary.subjectId.value == proposal.candidateId.value)
            val next = EvolutionBaselineState(
                revision = receipt.activeRevision,
                benchmark = canary.copy(
                    subjectId = EvolutionBenchmarkSubjectId(
                        "baseline-" + proposal.candidateId.value
                    )
                )
            )
            state = next
            next
        }
    }

    private class FakeDeploymentPort(
        initialRevision: String
    ) : EvolutionDeploymentPort {
        var activeRevision: String = initialRevision
        var checkpointCalls: Int = 0
        var applyCalls: Int = 0
        var rollbackCalls: Int = 0
        var failRollback: Boolean = false

        override fun checkpoint(
            proposal: EvolutionPromotionProposal
        ): Result<EvolutionDeploymentCheckpoint> = runCatching {
            checkpointCalls += 1
            EvolutionDeploymentCheckpoint(
                checkpointToken = "checkpoint-" + proposal.ticketDigest.take(24),
                baselineRevision = activeRevision,
                capturedAtEpochMs = 20_000L
            )
        }

        override fun apply(
            proposal: EvolutionPromotionProposal,
            checkpoint: EvolutionDeploymentCheckpoint
        ): Result<EvolutionDeploymentReceipt> = runCatching {
            applyCalls += 1
            activeRevision = proposal.proposedRevision
            EvolutionDeploymentReceipt(
                artifactDigest = proposal.artifactDigest,
                activeRevision = proposal.proposedRevision,
                appliedAtEpochMs = 21_000L + applyCalls
            )
        }

        override fun rollback(
            checkpoint: EvolutionDeploymentCheckpoint,
            receipt: EvolutionDeploymentReceipt?
        ): Result<Unit> = runCatching {
            rollbackCalls += 1
            if (failRollback) error("rollback failed")
            activeRevision = checkpoint.baselineRevision
        }
    }

    companion object {
        private fun observations(
            planning: Double,
            generalization: Double,
            samples: Int
        ): Map<EvolutionBenchmarkMetricId, EvolutionMetricObservation> = mapOf(
            EvolutionBenchmarkMetricId("planning") to
                EvolutionMetricObservation(planning, samples),
            EvolutionBenchmarkMetricId("generalization") to
                EvolutionMetricObservation(generalization, samples)
        )
    }
}
