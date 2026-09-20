package io.amper.neuroos.core.v2

import io.amper.neuroos.core.InferenceCancellationSignal
import io.amper.neuroos.core.InferenceRequest
import io.amper.neuroos.core.InferenceResponse
import io.amper.neuroos.core.ModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmcfFrozenCognitiveRunCoordinatorTest {
    private val foundation = AmcfFoundationBinding(
        foundationId = "amper-foundation",
        semanticSha256 = "1".repeat(64)
    )

    @Test
    fun completeRunCapturesCognitiveSnapshotExactlyOnce() {
        val provider = RecordingSnapshotProvider(snapshot("a"))
        val endpoint = RecordingEndpoint(foundation)
        val coordinator = AmcfFrozenCognitiveRunCoordinator(
            snapshotProvider = provider,
            endpoint = endpoint
        )

        val result = coordinator.run(
            plan = reasonPlan(),
            context = AmcfFoundationInferenceContext("Solve with grounded evidence.")
        ).getOrThrow()

        assertEquals(1, provider.captureCount)
        assertEquals("Solve with grounded evidence.", provider.lastPrompt)
        assertTrue(endpoint.requests.size >= 4)
        assertTrue(endpoint.requests.all {
            it.prompt.contains("cognitive_state_digest=" + "a".repeat(64))
        })
        assertTrue(endpoint.requests.all {
            it.prompt.contains("<FROZEN_COGNITIVE_STATE>")
        })
        assertTrue(endpoint.requests.all {
            it.prompt.contains("grounded snapshot guidance")
        })
        assertEquals("a".repeat(64), result.diagnostics.cognitiveStateDigest)
        assertEquals(result.snapshot.queryDigest, result.diagnostics.queryDigest)
        assertEquals(
            result.cycleRun.terminalState.stateDigest,
            result.diagnostics.terminalStateDigest
        )
    }

    @Test
    fun strongFrozenSnapshotCanEarlyExitWithoutRecapture() {
        val provider = RecordingSnapshotProvider(snapshot("b"))
        val endpoint = RecordingEndpoint(foundation)
        val result = AmcfFrozenCognitiveRunCoordinator(
            snapshotProvider = provider,
            endpoint = endpoint
        ).run(
            plan = reasonPlan(),
            context = AmcfFoundationInferenceContext("Answer efficiently.")
        ).getOrThrow()

        assertEquals(1, provider.captureCount)
        assertTrue(result.cycleRun.committedStates.size < result.cycleRun.plan.maximumTotalCycles)
        assertEquals(
            AmcfEarlyExitAction.ADVANCE_TO_VERIFY,
            result.cycleRun.earlyExitDecisions.last().action
        )
    }

    @Test
    fun frozenSnapshotDiagnosticsExposeOnlyBoundedIdentityAndCounts() {
        val snapshot = snapshot("c")
        val result = AmcfFrozenCognitiveRunCoordinator(
            snapshotProvider = AmcfCognitiveSnapshotProvider { snapshot },
            endpoint = RecordingEndpoint(foundation)
        ).run(
            plan = reasonPlan(),
            context = AmcfFoundationInferenceContext("Check the result.")
        ).getOrThrow()

        val diagnostics = result.diagnostics
        assertEquals(foundation.foundationId, diagnostics.foundationId)
        assertEquals(foundation.semanticSha256, diagnostics.foundationSemanticSha256)
        assertEquals(snapshot.cognitiveStateDigest, diagnostics.cognitiveStateDigest)
        assertEquals(snapshot.queryDigest, diagnostics.queryDigest)
        assertTrue(diagnostics.plannedCycles <= AmcfComputeCyclePlanner.MAX_TOTAL_CYCLES)
        assertTrue(diagnostics.committedCycles <= diagnostics.plannedCycles)
    }

    @Test
    fun foundationMismatchFailsBeforeSnapshotCapture() {
        val provider = RecordingSnapshotProvider(snapshot("d"))
        val endpoint = RecordingEndpoint(
            foundation.copy(semanticSha256 = "2".repeat(64))
        )

        val result = AmcfFrozenCognitiveRunCoordinator(
            snapshotProvider = provider,
            endpoint = endpoint
        ).run(
            plan = reasonPlan(),
            context = AmcfFoundationInferenceContext("Answer.")
        )

        assertTrue(result.isFailure)
        assertEquals(0, provider.captureCount)
        assertTrue(endpoint.requests.isEmpty())
    }

    private fun snapshot(digestNibble: String): AmcfFrozenCognitiveSnapshot =
        AmcfFrozenCognitiveSnapshot(
            cognitiveStateDigest = digestNibble.repeat(64),
            queryDigest = "e".repeat(64),
            capturedAtEpochMs = 123L,
            qualitySignals = AmcfIntegratedCognitiveQualitySignals(
                cognitiveStateDigest = digestNibble.repeat(64),
                overallReadiness = 0.95,
                epistemicConfidence = 0.95,
                worldConfidence = 0.94,
                skillConfidence = 0.90,
                competenceConfidence = 0.92,
                uncertainty = 0.05,
                learningPressure = 0.05,
                perceptualFreshness = 1.0,
                stalePerceptFraction = 0.0
            ),
            boundedGuidance = "<INTEGRATED_COGNITIVE_STATE>\ngrounded snapshot guidance\n</INTEGRATED_COGNITIVE_STATE>"
        )

    private fun reasonPlan(): AmcfComputeCyclePlan =
        AmcfComputeCyclePlanner.plan(
            foundation = foundation,
            budget = OmegaComputeBudget(
                mode = OmegaComputeMode.REASON,
                recurrentCycles = 3,
                verifyPasses = 1,
                targetFirstTokenMs = 4_000L,
                allowInternetVerification = false,
                allowToolUse = false
            )
        )

    private class RecordingSnapshotProvider(
        private val snapshot: AmcfFrozenCognitiveSnapshot
    ) : AmcfCognitiveSnapshotProvider {
        var captureCount: Int = 0
        var lastPrompt: String? = null

        override fun capture(userPrompt: String): AmcfFrozenCognitiveSnapshot {
            captureCount += 1
            lastPrompt = userPrompt
            return snapshot
        }
    }

    private class RecordingEndpoint(
        override val foundation: AmcfFoundationBinding
    ) : AmcfFoundationInferenceEndpoint {
        val requests = mutableListOf<InferenceRequest>()

        override fun infer(
            request: InferenceRequest,
            cancellation: InferenceCancellationSignal
        ): Result<InferenceResponse> = runCatching {
            cancellation.throwIfCancelled()
            requests += request
            InferenceResponse(
                modelId = ModelId("foundation"),
                backendId = "amper-core",
                text = "candidate-" + requests.size
            )
        }
    }
}
