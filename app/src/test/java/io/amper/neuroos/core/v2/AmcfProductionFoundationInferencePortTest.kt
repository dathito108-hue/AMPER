package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.InferenceCancellationSignal
import io.amper.neuroos.core.InferenceRequest
import io.amper.neuroos.core.InferenceResponse
import io.amper.neuroos.core.ModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmcfProductionFoundationInferencePortTest {
    private val foundation = AmcfFoundationBinding(
        foundationId = "amper-foundation",
        semanticSha256 = "1".repeat(64)
    )

    @Test
    fun productionPortUsesBoundedReasoningOnlyRequestsOnSameFoundation() {
        val endpoint = RecordingEndpoint(foundation)
        val port = AmcfProductionFoundationInferencePort(
            endpoint = endpoint,
            context = AmcfFoundationInferenceContext(
                userPrompt = "Explain the result clearly.",
                maxOutputTokensPerCycle = 384,
                temperature = 0.2,
                conversationSessionId = "conversation-1"
            )
        )
        val plan = reasonPlan()

        val first = port.execute(
            AmcfCycleExecutionRequest(plan, plan.cycles[0], null),
            InferenceCancellationSignal()
        ).getOrThrow()
        val firstState = AmcfRecurrentStateTransition.record(
            cycle = plan.cycles[0],
            previous = null,
            candidateDigest = first.candidateDigest,
            evidenceDigest = first.evidenceDigest,
            confidence = first.confidence,
            uncertainty = first.uncertainty,
            evidenceSufficiency = first.evidenceSufficiency
        )
        val second = port.execute(
            AmcfCycleExecutionRequest(plan, plan.cycles[1], firstState),
            InferenceCancellationSignal()
        ).getOrThrow()

        assertEquals(2, endpoint.requests.size)
        assertEquals(setOf(CapabilityId("reasoning")), endpoint.requests[0].requiredCapabilities)
        assertEquals(256, endpoint.requests[0].maxOutputTokens)
        assertEquals("conversation-1", endpoint.requests[0].conversationSessionId)
        assertTrue(endpoint.requests[1].prompt.contains("<PREVIOUS_CANDIDATE>"))
        assertTrue(endpoint.requests[1].prompt.contains("candidate-1"))
        assertFalse(endpoint.requests[1].prompt.contains("chain-of-thought="))
        assertTrue(first.candidateDigest.matches(Regex("[0-9a-f]{64}")))
        assertTrue(second.evidenceDigest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(0.60, first.confidence, 0.0)
        assertEquals(0.40, first.uncertainty, 0.0)
    }

    @Test
    fun conservativeProductionQualitySpendsFullReasonPlan() {
        val endpoint = RecordingEndpoint(foundation)
        val orchestrator = AmcfCycleOrchestrator(
            AmcfProductionFoundationInferencePort(
                endpoint = endpoint,
                context = AmcfFoundationInferenceContext("Solve carefully.")
            )
        )

        val result = orchestrator.run(reasonPlan()).getOrThrow()

        assertEquals(6, result.committedStates.size)
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6),
            result.committedStates.map { it.completedCycleIndex }
        )
        assertEquals(6, endpoint.requests.size)
        assertEquals(AmcfComputeCycleKind.FINALIZE, result.terminalState.completedCycleKind)
    }

    @Test
    fun injectedStructuredQualityCanActivateExistingEarlyExitGate() {
        val endpoint = RecordingEndpoint(foundation)
        val evaluator = AmcfCycleQualityEvaluator { input ->
            if (
                input.cycle.kind == AmcfComputeCycleKind.DELIBERATE &&
                input.cycle.index >= 2
            ) {
                AmcfCycleQuality(
                    confidence = 0.95,
                    uncertainty = 0.05,
                    evidenceSufficiency = 0.95
                )
            } else {
                AmcfCycleQuality(
                    confidence = 0.60,
                    uncertainty = 0.40,
                    evidenceSufficiency = 0.60
                )
            }
        }
        val orchestrator = AmcfCycleOrchestrator(
            AmcfProductionFoundationInferencePort(
                endpoint = endpoint,
                context = AmcfFoundationInferenceContext("Solve carefully."),
                qualityEvaluator = evaluator
            )
        )

        val result = orchestrator.run(reasonPlan()).getOrThrow()

        assertEquals(
            listOf(1, 2, 4, 5, 6),
            result.committedStates.map { it.completedCycleIndex }
        )
        assertEquals(5, endpoint.requests.size)
        assertEquals(
            AmcfEarlyExitAction.ADVANCE_TO_VERIFY,
            result.earlyExitDecisions.last().action
        )
    }

    @Test
    fun foundationMismatchFailsBeforeProductionInference() {
        val endpoint = RecordingEndpoint(
            foundation.copy(semanticSha256 = "2".repeat(64))
        )
        val port = AmcfProductionFoundationInferencePort(
            endpoint = endpoint,
            context = AmcfFoundationInferenceContext("Solve carefully.")
        )
        val plan = reasonPlan()

        val result = port.execute(
            AmcfCycleExecutionRequest(plan, plan.cycles[0], null),
            InferenceCancellationSignal()
        )

        assertTrue(result.isFailure)
        assertTrue(endpoint.requests.isEmpty())
    }

    @Test
    fun cycleBudgetsRemainHardBounded() {
        val endpoint = RecordingEndpoint(foundation)
        val port = AmcfProductionFoundationInferencePort(
            endpoint = endpoint,
            context = AmcfFoundationInferenceContext(
                userPrompt = "Answer.",
                maxOutputTokensPerCycle = 512
            )
        )
        val plan = AmcfComputeCyclePlanner.plan(
            foundation = foundation,
            budget = io.amper.neuroos.core.v2.OmegaComputeBudget(
                mode = OmegaComputeMode.FAST,
                recurrentCycles = 0,
                verifyPasses = 0,
                targetFirstTokenMs = 1_500L,
                allowInternetVerification = false,
                allowToolUse = false
            )
        )

        port.execute(
            AmcfCycleExecutionRequest(plan, plan.cycles.single(), null),
            InferenceCancellationSignal()
        ).getOrThrow()

        assertEquals(512, endpoint.requests.single().maxOutputTokens)
        assertTrue(
            endpoint.requests.single().maxOutputTokens <=
                AmcfFoundationInferenceContext.MAX_OUTPUT_TOKENS_PER_CYCLE
        )
    }

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
