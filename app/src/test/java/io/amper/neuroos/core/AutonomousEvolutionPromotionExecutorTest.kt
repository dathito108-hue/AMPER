package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousEvolutionPromotionExecutorTest {
    @Test
    fun successfulPromotionPersistsPreparedAppliedThenCommitsAfterCanary() {
        val fixture = fixture("success")
        val deployment = FakeDeploymentPort("base-success")
        val prepared = fixture.executor.prepare(
            fixture.proposal,
            fixture.baseline,
            deployment
        )

        assertEquals(EvolutionPromotionExecutionStage.PREPARED, prepared.stage)
        assertEquals("base-success", deployment.activeRevision)
        var observedPreparedBeforeApply = false
        deployment.beforeApply = {
            observedPreparedBeforeApply =
                fixture.executor.get(prepared.id)?.stage ==
                    EvolutionPromotionExecutionStage.PREPARED
        }

        val applied = fixture.executor.apply(
            prepared.id,
            fixture.proposal,
            deployment
        )
        assertTrue(observedPreparedBeforeApply)
        assertEquals(EvolutionPromotionExecutionStage.APPLIED, applied.stage)
        assertEquals(fixture.proposal.proposedRevision, deployment.activeRevision)

        val committed = fixture.executor.canaryAndCommit(
            id = prepared.id,
            proposal = fixture.proposal,
            baseline = fixture.baseline,
            deployment = deployment,
            canary = canary(
                fixture,
                planning = 0.99,
                generalization = 0.92
            )
        )

        assertEquals(EvolutionPromotionExecutionStage.COMMITTED, committed.stage)
        assertTrue(committed.liveCommitted)
        assertTrue(requireNotNull(committed.canaryAggregateDelta) >= 0.0)
        assertEquals(0, deployment.rollbackCalls)
        assertFalse(committed.authorityBearing)
    }

    @Test
    fun canaryRegressionAutomaticallyRollsBackToCapturedBaseline() {
        val fixture = fixture("canary-regression")
        val deployment = FakeDeploymentPort(fixture.proposal.baseRevision)
        val prepared = fixture.executor.prepare(
            fixture.proposal,
            fixture.baseline,
            deployment
        )
        fixture.executor.apply(prepared.id, fixture.proposal, deployment)

        val result = fixture.executor.canaryAndCommit(
            id = prepared.id,
            proposal = fixture.proposal,
            baseline = fixture.baseline,
            deployment = deployment,
            canary = canary(
                fixture,
                planning = 0.90,
                generalization = 0.99
            )
        )

        assertEquals(EvolutionPromotionExecutionStage.ROLLED_BACK, result.stage)
        assertEquals("CANARY_REGRESSION", result.failureCode)
        assertEquals(fixture.proposal.baseRevision, deployment.activeRevision)
        assertEquals(1, deployment.rollbackCalls)
        assertFalse(result.liveCommitted)
    }

    @Test
    fun deploymentIdentityMismatchRollsBackBeforeCanary() {
        val fixture = fixture("identity-mismatch")
        val deployment = FakeDeploymentPort(fixture.proposal.baseRevision).apply {
            receiptArtifactOverride = "7".repeat(64)
        }
        val prepared = fixture.executor.prepare(
            fixture.proposal,
            fixture.baseline,
            deployment
        )

        val result = fixture.executor.apply(
            prepared.id,
            fixture.proposal,
            deployment
        )

        assertEquals(EvolutionPromotionExecutionStage.ROLLED_BACK, result.stage)
        assertEquals("DEPLOYMENT_IDENTITY_MISMATCH", result.failureCode)
        assertEquals(fixture.proposal.baseRevision, deployment.activeRevision)
        assertEquals(1, deployment.rollbackCalls)
    }

    @Test
    fun forgedPromotionTicketIsRejectedBeforeCheckpointMutation() {
        val fixture = fixture("forged-ticket")
        val deployment = FakeDeploymentPort(fixture.proposal.baseRevision)
        val forged = fixture.proposal.copy(
            proposedRevision = fixture.proposal.proposedRevision + "-forged"
        )

        val result = runCatching {
            fixture.executor.prepare(forged, fixture.baseline, deployment)
        }

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message
                ?.contains("not issued by canonical evolution tournament") == true
        )
        assertEquals(0, deployment.checkpointCalls)
        assertEquals(fixture.proposal.baseRevision, deployment.activeRevision)
    }

    @Test
    fun restartRecoveryRollsBackPersistedAppliedTransaction() {
        val fixture = fixture("restart-recovery")
        val deployment = FakeDeploymentPort(fixture.proposal.baseRevision)
        val prepared = fixture.executor.prepare(
            fixture.proposal,
            fixture.baseline,
            deployment
        )
        val applied = fixture.executor.apply(
            prepared.id,
            fixture.proposal,
            deployment
        )
        assertEquals(EvolutionPromotionExecutionStage.APPLIED, applied.stage)
        assertEquals(fixture.proposal.proposedRevision, deployment.activeRevision)

        val restarted = MemoryBackedAutonomousEvolutionPromotionExecutor(
            memory = fixture.memory,
            evolution = fixture.evolution,
            gate = fixture.gate,
            clock = { 50_000L }
        )
        val recovered = restarted.recoverIncomplete(deployment)

        assertEquals(1, recovered.size)
        assertEquals(EvolutionPromotionExecutionStage.ROLLED_BACK, recovered.single().stage)
        assertEquals("CRASH_RECOVERY", recovered.single().failureCode)
        assertEquals(fixture.proposal.baseRevision, deployment.activeRevision)
        assertEquals(
            EvolutionPromotionExecutionStage.ROLLED_BACK,
            restarted.get(prepared.id)?.stage
        )
    }

    @Test
    fun rollbackFailureBecomesRecoveryRequiredAndCanRecoverLater() {
        val fixture = fixture("rollback-retry")
        val deployment = FakeDeploymentPort(fixture.proposal.baseRevision)
        val prepared = fixture.executor.prepare(
            fixture.proposal,
            fixture.baseline,
            deployment
        )
        fixture.executor.apply(prepared.id, fixture.proposal, deployment)
        deployment.failRollback = true

        val first = fixture.executor.canaryAndCommit(
            id = prepared.id,
            proposal = fixture.proposal,
            baseline = fixture.baseline,
            deployment = deployment,
            canary = canary(
                fixture,
                planning = 0.80,
                generalization = 0.80
            )
        )

        assertEquals(EvolutionPromotionExecutionStage.RECOVERY_REQUIRED, first.stage)
        assertEquals(fixture.proposal.proposedRevision, deployment.activeRevision)

        deployment.failRollback = false
        val recovered = fixture.executor.recoverIncomplete(deployment)

        assertEquals(1, recovered.size)
        assertEquals(EvolutionPromotionExecutionStage.ROLLED_BACK, recovered.single().stage)
        assertEquals(fixture.proposal.baseRevision, deployment.activeRevision)
    }

    @Test
    fun committedPromotionCanRollbackAfterLaterRegression() {
        val fixture = fixture("post-commit")
        val deployment = FakeDeploymentPort(fixture.proposal.baseRevision)
        val prepared = fixture.executor.prepare(
            fixture.proposal,
            fixture.baseline,
            deployment
        )
        fixture.executor.apply(prepared.id, fixture.proposal, deployment)
        val committed = fixture.executor.canaryAndCommit(
            prepared.id,
            fixture.proposal,
            fixture.baseline,
            deployment,
            canary(fixture, planning = 0.99, generalization = 0.93)
        )
        assertEquals(EvolutionPromotionExecutionStage.COMMITTED, committed.stage)

        val rolledBack = fixture.executor.rollbackCommitted(
            prepared.id,
            fixture.proposal,
            deployment
        )

        assertEquals(EvolutionPromotionExecutionStage.ROLLED_BACK, rolledBack.stage)
        assertEquals("POST_COMMIT_ROLLBACK", rolledBack.failureCode)
        assertEquals(fixture.proposal.baseRevision, deployment.activeRevision)
    }

    @Test
    fun insufficientCanarySamplesRollbackInsteadOfCommitting() {
        val fixture = fixture("canary-samples")
        val deployment = FakeDeploymentPort(fixture.proposal.baseRevision)
        val prepared = fixture.executor.prepare(
            fixture.proposal,
            fixture.baseline,
            deployment
        )
        fixture.executor.apply(prepared.id, fixture.proposal, deployment)

        val result = fixture.executor.canaryAndCommit(
            prepared.id,
            fixture.proposal,
            fixture.baseline,
            deployment,
            canary(
                fixture,
                planning = 1.0,
                generalization = 1.0,
                samples = 4
            )
        )

        assertEquals(EvolutionPromotionExecutionStage.ROLLED_BACK, result.stage)
        assertEquals(fixture.proposal.baseRevision, deployment.activeRevision)
    }

    @Test
    fun runtimeExposesPromotionExecutor() {
        val runtime = AmperRuntime.reference()
        assertNotNull(runtime.autonomousEvolutionPromotionExecutor)
    }

    private data class Fixture(
        val memory: MemoryOs,
        val gate: VerifiedEvolution,
        val evolution: AutonomousEvolutionModel,
        val executor: AutonomousEvolutionPromotionExecutor,
        val suite: EvolutionBenchmarkSuite,
        val baseline: EvolutionBenchmarkSnapshot,
        val proposal: EvolutionPromotionProposal
    )

    private fun fixture(suffix: String): Fixture {
        val memory = InMemoryMemoryOs()
        val selfModel = CanonicalSelfModel()
        val gate = CanonicalEvolutionGate(selfModel)
        var now = 10_000L
        val evolution = MemoryBackedAutonomousEvolutionModel(
            memory = memory,
            gate = gate,
            clock = { now++ }
        )
        val suite = EvolutionBenchmarkSuite(
            id = EvolutionBenchmarkSuiteId("promotion-suite-" + suffix),
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

        val baseline = EvolutionBenchmarkSnapshot(
            subjectId = EvolutionBenchmarkSubjectId("baseline-" + suffix),
            suiteId = suite.id,
            suiteDigest = suite.canonicalDigest,
            metrics = observations(
                planning = 0.95,
                generalization = 0.84,
                samples = 64
            ),
            artifactDigest = "a".repeat(64),
            observedAtEpochMs = 2_000L
        )
        val candidate = EvolutionCandidate(
            id = EvolutionId("candidate-" + suffix),
            description = "promotion executor candidate",
            baseRevision = "base-" + suffix,
            proposedRevision = "candidate-revision-" + suffix,
            artifactDigest = "b".repeat(64)
        )
        val benchmark = EvolutionBenchmarkSnapshot(
            subjectId = EvolutionBenchmarkSubjectId(candidate.id.value),
            suiteId = suite.id,
            suiteDigest = suite.canonicalDigest,
            metrics = observations(
                planning = 0.99,
                generalization = 0.91,
                samples = 64
            ),
            artifactDigest = candidate.artifactDigest,
            observedAtEpochMs = 3_000L
        )
        val verification = VerificationEvidence(
            sandboxPassed = true,
            testsPassed = true,
            invariantResults = selfModel.snapshot().invariants.associateWith { true },
            rollbackToken = "rollback-" + suffix
        )
        val decision = evolution.runTournament(
            id = EvolutionTournamentId("tournament-" + suffix),
            baseline = baseline,
            candidates = listOf(
                EvolutionTournamentEntry(
                    candidate = candidate,
                    kind = AutonomousEvolutionCandidateKind.CODE,
                    benchmark = benchmark,
                    verification = verification
                )
            )
        )
        val proposal = requireNotNull(evolution.promotionProposal(decision))
        val executor = MemoryBackedAutonomousEvolutionPromotionExecutor(
            memory = memory,
            evolution = evolution,
            gate = gate,
            clock = { now++ }
        )
        return Fixture(
            memory = memory,
            gate = gate,
            evolution = evolution,
            executor = executor,
            suite = suite,
            baseline = baseline,
            proposal = proposal
        )
    }

    private fun canary(
        fixture: Fixture,
        planning: Double,
        generalization: Double,
        samples: Int = 64
    ): EvolutionCanaryEvaluator = object : EvolutionCanaryEvaluator {
        override fun evaluate(
            proposal: EvolutionPromotionProposal,
            receipt: EvolutionDeploymentReceipt
        ): Result<EvolutionBenchmarkSnapshot> = Result.success(
            EvolutionBenchmarkSnapshot(
                subjectId = EvolutionBenchmarkSubjectId(proposal.candidateId.value),
                suiteId = fixture.suite.id,
                suiteDigest = fixture.suite.canonicalDigest,
                metrics = observations(planning, generalization, samples),
                artifactDigest = proposal.artifactDigest,
                observedAtEpochMs = receipt.appliedAtEpochMs + 1
            )
        )
    }

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

    private class FakeDeploymentPort(
        initialRevision: String
    ) : EvolutionDeploymentPort {
        var activeRevision: String = initialRevision
        var checkpointCalls: Int = 0
        var applyCalls: Int = 0
        var rollbackCalls: Int = 0
        var failApply: Boolean = false
        var failRollback: Boolean = false
        var receiptArtifactOverride: String? = null
        var receiptRevisionOverride: String? = null
        var beforeApply: (() -> Unit)? = null

        override fun checkpoint(
            proposal: EvolutionPromotionProposal
        ): Result<EvolutionDeploymentCheckpoint> = runCatching {
            checkpointCalls += 1
            EvolutionDeploymentCheckpoint(
                checkpointToken = "checkpoint-" + proposal.tournamentId.value,
                baselineRevision = activeRevision,
                capturedAtEpochMs = 20_000L
            )
        }

        override fun apply(
            proposal: EvolutionPromotionProposal,
            checkpoint: EvolutionDeploymentCheckpoint
        ): Result<EvolutionDeploymentReceipt> = runCatching {
            beforeApply?.invoke()
            applyCalls += 1
            if (failApply) error("apply failed")
            activeRevision = proposal.proposedRevision
            EvolutionDeploymentReceipt(
                artifactDigest = receiptArtifactOverride ?: proposal.artifactDigest,
                activeRevision = receiptRevisionOverride ?: proposal.proposedRevision,
                appliedAtEpochMs = 21_000L
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
}