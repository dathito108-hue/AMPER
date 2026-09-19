package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedLoopSelfEvolutionTest {
    @Test
    fun governedExecutionWeaknessRunsFullCampaignPromotionCanaryCommit() {
        val runtime = AmperRuntime.reference()
        val capability = CapabilityId("device.status")
        repeat(4) { index ->
            runtime.competence.observe(
                ActionOutcome(
                    status = ActionStatus.FAILED,
                    proposal = ActionProposal(
                        requestId = ActionRequestId("failed-$index"),
                        capability = capability,
                        reason = "governed test failure",
                        input = ""
                    ),
                    toolId = ToolId("device-status"),
                    sideEffect = ToolSideEffect.READ_ONLY,
                    detail = "test failure"
                )
            )
        }

        val deployment = FakeDeploymentIdentityPort(
            EvolutionRuntimeIdentity(
                revision = "base-v1",
                artifactDigest = "a".repeat(64)
            )
        )
        val inference = object : CognitiveInferencePort {
            override fun infer(request: InferenceRequest): Result<InferenceResponse> {
                val metric = Regex("""- metric=([^ ]+) current=""")
                    .find(request.prompt)
                    ?.groupValues
                    ?.get(1)
                    ?: error("evolution metric missing from prompt")
                val output = buildString {
                    appendLine("AMPER_EVOLUTION_BLUEPRINTS_V1")
                    append("C|STRATEGY|")
                    append(b64("repair recurrent governed execution weakness"))
                    append("|")
                    append(b64("strategy-profile-v2"))
                    append("|")
                    append(metric)
                }
                return Result.success(
                    InferenceResponse(
                        modelId = ModelId("evolution-generator"),
                        backendId = "fake-evolution",
                        text = output,
                        selectedCapabilities = setOf(TitanCapabilities.REASONING)
                    )
                )
            }
        }
        val invariants = CanonicalSelfModel().snapshot().invariants.associateWith { true }
        val sandbox = EvolutionSandboxRunner { _, request ->
            Result.success(
                EvolutionSandboxCandidateEvidence(
                    artifactDigest = "b".repeat(64),
                    proposedRevision = "candidate-v2",
                    metrics = request.suite.metrics.associate { metric ->
                        metric.id to EvolutionMetricObservation(
                            score = 0.92,
                            samples = 4
                        )
                    },
                    sandboxPassed = true,
                    testsPassed = true,
                    invariantResults = invariants,
                    rollbackToken = "rollback-candidate-v2",
                    observedAtEpochMs = 20_000L
                )
            )
        }
        val canary = object : EvolutionCanaryEvaluator {
            override fun evaluate(
                proposal: EvolutionPromotionProposal,
                receipt: EvolutionDeploymentReceipt
            ): Result<EvolutionBenchmarkSnapshot> {
                val suite = requireNotNull(runtime.autonomousEvolution.getSuite(proposal.suiteId))
                return Result.success(
                    EvolutionBenchmarkSnapshot(
                        subjectId = EvolutionBenchmarkSubjectId(proposal.candidateId.value),
                        suiteId = suite.id,
                        suiteDigest = suite.canonicalDigest,
                        metrics = suite.metrics.associate { metric ->
                            metric.id to EvolutionMetricObservation(
                                score = 0.90,
                                samples = 4
                            )
                        },
                        artifactDigest = receipt.artifactDigest,
                        observedAtEpochMs = receipt.appliedAtEpochMs + 1
                    )
                )
            }
        }
        val port = runtime.closedLoopEvolutionExecutive(
            inference = inference,
            sandbox = sandbox,
            deployment = deployment,
            canary = canary,
            maxCandidates = 1
        )
        val directive = evolutionDirective(capability)

        val result = port.run(directive).getOrThrow()

        assertEquals(EvolutionAutonomyCycleStage.COMMITTED, result.terminalStage)
        assertEquals(1, result.committedCount)
        assertEquals("candidate-v2", deployment.current().revision)
        assertEquals("b".repeat(64), deployment.current().artifactDigest)
        assertEquals(1, deployment.checkpointCalls)
        assertEquals(1, deployment.applyCalls)
        assertEquals(0, deployment.rollbackCalls)
        val ledger = runtime.closedLoopEvolutionLedger.get(capability)
        assertNotNull(ledger)
        assertEquals(1, requireNotNull(ledger).committedCount)
        assertFalse(ledger.authorityBearing)

        val unchanged = port.run(directive).getOrThrow()

        assertEquals(EvolutionAutonomyCycleStage.NO_GAP, unchanged.terminalStage)
        assertEquals("UNCHANGED_LIVE_EVIDENCE", unchanged.cycles.single().failureCode)
        assertEquals(1, deployment.applyCalls)
    }

    @Test
    fun authorityAndEnvironmentOutcomesCannotBecomeEvolutionWeakness() {
        val runtime = AmperRuntime.reference()
        val capability = CapabilityId("device.status")
        listOf(ActionStatus.DENIED, ActionStatus.UNAVAILABLE, ActionStatus.REQUIRES_CONFIRMATION)
            .forEachIndexed { index, status ->
                runtime.competence.observe(
                    ActionOutcome(
                        status = status,
                        proposal = ActionProposal(
                            requestId = ActionRequestId("non-competence-$index"),
                            capability = capability,
                            reason = "governed non-competence outcome",
                            input = ""
                        ),
                        toolId = ToolId("device-status"),
                        sideEffect = ToolSideEffect.READ_ONLY
                    )
                )
            }

        val snapshot = requireNotNull(runtime.competence.snapshot(capability))
        assertEquals(0, snapshot.executionAttempts)
        assertNull(
            ClosedLoopEvolutionEvidencePolicy.from(
                evolutionDirective(capability),
                runtime.competence
            )
        )
    }

    @Test
    fun benchmarkIsBoundToWeakCapabilityAndRealAttemptCount() {
        val capability = CapabilityId("web.search")
        val evidence = ClosedLoopEvolutionEvidence(
            capability = capability,
            kind = ClosedLoopEvolutionEvidenceKind.EXECUTION_RELIABILITY,
            baselineScore = 0.25,
            samples = 4,
            evidenceConfidence = 0.50,
            severity = 0.90,
            evidenceDigest = closedLoopEvolutionSha256("evidence"),
            executed = 1,
            failed = 3
        )
        val identity = EvolutionRuntimeIdentity(
            revision = "runtime-v1",
            artifactDigest = "c".repeat(64)
        )

        val baseline = ClosedLoopEvolutionBenchmark.baseline(
            evidence = evidence,
            identity = identity,
            observedAtEpochMs = 123L
        )
        val suite = ClosedLoopEvolutionBenchmark.suite(capability)
        val metric = ClosedLoopEvolutionBenchmark.metricId(capability)
        val observation = requireNotNull(baseline.benchmark.metrics[metric])

        assertEquals(suite.canonicalDigest, baseline.benchmark.suiteDigest)
        assertEquals(0.25, observation.score, 0.0001)
        assertEquals(4, observation.samples)
        assertEquals(identity.artifactDigest, baseline.benchmark.artifactDigest)
        assertTrue(
            ClosedLoopEvolutionEvidencePolicy.candidateKinds(evidence)
                .contains(AutonomousEvolutionCandidateKind.ARCHITECTURE)
        )
        assertFalse(evidence.authorityBearing)
    }

    private fun evolutionDirective(capability: CapabilityId) =
        CognitiveExecutiveDirective(
            cognitiveStateDigest = "d".repeat(64),
            executionContextDigest = "e".repeat(64),
            action = CognitiveExecutiveAction.EVOLVE,
            overallReadiness = 0.30,
            uncertainty = 0.20,
            learningPressure = 0.91,
            triggeringCapability = capability,
            triggeringNeedKind = LearningNeedKind.EXECUTION_RELIABILITY,
            triggeringEvidenceConfidence = 0.50,
            rationale = "repeated governed execution weakness is eligible for evolution"
        )

    private class FakeDeploymentIdentityPort(
        initial: EvolutionRuntimeIdentity
    ) : EvolutionDeploymentIdentityPort {
        private var identity = initial
        var checkpointCalls = 0
        var applyCalls = 0
        var rollbackCalls = 0

        override fun current(): EvolutionRuntimeIdentity = identity

        override fun checkpoint(
            proposal: EvolutionPromotionProposal
        ): Result<EvolutionDeploymentCheckpoint> = runCatching {
            checkpointCalls += 1
            require(identity.revision == proposal.baseRevision)
            EvolutionDeploymentCheckpoint(
                checkpointToken = "checkpoint-" + proposal.ticketDigest.take(24),
                baselineRevision = identity.revision,
                capturedAtEpochMs = 30_000L
            )
        }

        override fun apply(
            proposal: EvolutionPromotionProposal,
            checkpoint: EvolutionDeploymentCheckpoint
        ): Result<EvolutionDeploymentReceipt> = runCatching {
            applyCalls += 1
            require(checkpoint.baselineRevision == identity.revision)
            identity = EvolutionRuntimeIdentity(
                revision = proposal.proposedRevision,
                artifactDigest = proposal.artifactDigest
            )
            EvolutionDeploymentReceipt(
                artifactDigest = identity.artifactDigest,
                activeRevision = identity.revision,
                appliedAtEpochMs = 31_000L
            )
        }

        override fun rollback(
            checkpoint: EvolutionDeploymentCheckpoint,
            receipt: EvolutionDeploymentReceipt?
        ): Result<Unit> = runCatching {
            rollbackCalls += 1
            identity = EvolutionRuntimeIdentity(
                revision = checkpoint.baselineRevision,
                artifactDigest = "a".repeat(64)
            )
        }
    }

    companion object {
        private fun b64(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}
