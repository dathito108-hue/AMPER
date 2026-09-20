package io.amper.neuroos.core.v2

import io.amper.neuroos.core.InferenceResponse
import io.amper.neuroos.core.ModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmcfIntegratedCognitiveQualityEvaluatorTest {
    private val foundation = AmcfFoundationBinding(
        foundationId = "amper-foundation",
        semanticSha256 = "1".repeat(64)
    )

    @Test
    fun strongGroundedReadinessCrossesReasonEarlyExitThreshold() {
        val evaluator = AmcfIntegratedCognitiveQualityEvaluator(
            strongSignals()
        )
        val plan = reasonPlan()
        val cycle = plan.cycles[1]
        val quality = evaluator.evaluate(input(cycle, "candidate-a"))
        val state = AmcfRecurrentStateTransition.record(
            cycle = cycle,
            previous = AmcfRecurrentStateTransition.record(
                cycle = plan.cycles[0],
                previous = null,
                candidateDigest = "2".repeat(64),
                evidenceDigest = "3".repeat(64),
                confidence = 0.70,
                uncertainty = 0.30,
                evidenceSufficiency = 0.70
            ),
            candidateDigest = "4".repeat(64),
            evidenceDigest = "5".repeat(64),
            confidence = quality.confidence,
            uncertainty = quality.uncertainty,
            evidenceSufficiency = quality.evidenceSufficiency
        )

        val decision = AmcfEarlyExitGate.decide(plan, cycle, state)

        assertTrue(quality.confidence >= 0.84)
        assertTrue(quality.uncertainty <= 0.18)
        assertTrue(quality.evidenceSufficiency >= 0.80)
        assertEquals(AmcfEarlyExitAction.ADVANCE_TO_VERIFY, decision.action)
    }

    @Test
    fun stalePerceptionBlocksEarlyExitEvenWithOtherwiseStrongReadiness() {
        val evaluator = AmcfIntegratedCognitiveQualityEvaluator(
            strongSignals().copy(
                perceptualFreshness = 0.0,
                stalePerceptFraction = 1.0
            )
        )
        val quality = evaluator.evaluate(input(reasonPlan().cycles[1], "candidate"))

        assertTrue(quality.uncertainty >= 0.50)
        assertTrue(quality.evidenceSufficiency < 0.90)
        assertTrue(quality.uncertainty > AmcfEarlyExitGate.threshold(OmegaComputeMode.REASON).maximumUncertainty)
    }

    @Test
    fun highLearningPressureReducesConfidenceAndEvidence() {
        val lowPressure = AmcfIntegratedCognitiveQualityEvaluator(
            strongSignals().copy(learningPressure = 0.0)
        ).evaluate(input(reasonPlan().cycles[1], "candidate"))
        val highPressure = AmcfIntegratedCognitiveQualityEvaluator(
            strongSignals().copy(learningPressure = 1.0)
        ).evaluate(input(reasonPlan().cycles[1], "candidate"))

        assertTrue(highPressure.confidence < lowPressure.confidence)
        assertTrue(highPressure.evidenceSufficiency < lowPressure.evidenceSufficiency)
        assertEquals(lowPressure.uncertainty, highPressure.uncertainty, 0.0)
    }

    @Test
    fun qualityDoesNotScoreGeneratedProse() {
        val evaluator = AmcfIntegratedCognitiveQualityEvaluator(strongSignals())
        val cycle = reasonPlan().cycles[1]

        val first = evaluator.evaluate(input(cycle, "short"))
        val second = evaluator.evaluate(
            input(
                cycle,
                "This is a much longer and more persuasive sounding answer that must not change quality."
            )
        )

        assertEquals(first, second)
    }

    @Test
    fun noPerceptualEvidenceUsesExplicitNeutralFreshness() {
        assertEquals(
            0.80,
            AmcfIntegratedCognitiveQualitySignals.NO_PERCEPTUAL_EVIDENCE_NEUTRAL,
            0.0
        )
    }

    private fun strongSignals() = AmcfIntegratedCognitiveQualitySignals(
        cognitiveStateDigest = "a".repeat(64),
        overallReadiness = 0.95,
        epistemicConfidence = 0.95,
        worldConfidence = 0.94,
        skillConfidence = 0.90,
        competenceConfidence = 0.92,
        uncertainty = 0.05,
        learningPressure = 0.05,
        perceptualFreshness = 1.0,
        stalePerceptFraction = 0.0
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

    private fun input(
        cycle: AmcfComputeCycle,
        text: String
    ): AmcfCycleQualityInput =
        AmcfCycleQualityInput(
            cycle = cycle,
            previousState = null,
            response = InferenceResponse(
                modelId = ModelId("foundation"),
                backendId = "amper-core",
                text = text
            ),
            candidateDigest = "b".repeat(64),
            evidenceDigest = "c".repeat(64)
        )
}
