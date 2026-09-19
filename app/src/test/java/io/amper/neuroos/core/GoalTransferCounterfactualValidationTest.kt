package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalTransferCounterfactualValidationTest {
    private val read = CapabilityId("phase341.read")
    private val write = CapabilityId("phase341.write")

    @Test
    fun validatorAcceptsWellGroundedLiveCandidateAndBindsExactCognitiveDigest() {
        val state = readyState(
            capabilityEvidence = listOf(
                CapabilityCompetenceSnapshot(
                    capability = read,
                    executed = 8,
                    failed = 1,
                    lastObservedAtEpochMs = 10L
                )
            )
        )
        val candidate = candidate(
            capabilities = listOf(read),
            transferSupport = 0.60
        )

        val validated = GoalTransferCounterfactualValidator.validate(
            candidates = listOf(candidate),
            state = state,
            descriptors = listOf(descriptor(read))
        )

        assertEquals(1, validated.size)
        val assessment = validated.single()
        assertEquals(GoalTransferValidationDecision.ACCEPT, assessment.decision)
        assertEquals(state.canonicalDigest, assessment.cognitiveStateDigest)
        assertEquals(1.0, assessment.liveCapabilityCoverage, 0.0)
        assertTrue(assessment.competenceFit > 0.5)
        assertTrue(assessment.contextFit >= GoalTransferCounterfactualValidator.MIN_CONTEXT_FIT)
        assertTrue(assessment.projectedSupport > 0.0)
        assertFalse(assessment.authorityBearing)
    }

    @Test
    fun validatorRejectsCandidateWhenCurrentLiveToolSurfaceNoLongerCoversStrategy() {
        val state = readyState()
        val candidate = candidate(
            capabilities = listOf(read, write),
            transferSupport = 0.80
        )

        val validated = GoalTransferCounterfactualValidator.validate(
            candidates = listOf(candidate),
            state = state,
            descriptors = listOf(descriptor(read))
        )

        assertTrue(validated.isEmpty())
    }

    @Test
    fun severeCurrentLearningNeedBlocksHistoricalTransfer() {
        val base = readyState()
        val state = base.copy(
            learningNeeds = listOf(
                LearningNeed(
                    capability = read,
                    kind = LearningNeedKind.EXECUTION_RELIABILITY,
                    severity = 0.95,
                    evidenceConfidence = 0.90,
                    rationale = "current execution reliability is below transfer threshold"
                )
            )
        )
        val candidate = candidate(
            capabilities = listOf(read),
            transferSupport = 0.90
        )

        val validated = GoalTransferCounterfactualValidator.validate(
            candidates = listOf(candidate),
            state = state,
            descriptors = listOf(descriptor(read))
        )

        assertTrue(validated.isEmpty())
    }

    @Test
    fun authorityBlockHistoryDoesNotChangeCounterfactualViability() {
        val state = readyState()
        val lowAuthorityHistory = candidate(
            capabilities = listOf(read),
            transferSupport = 0.55,
            authorityBlocks = 0
        )
        val highAuthorityHistory = candidate(
            capabilities = listOf(read),
            transferSupport = 0.55,
            authorityBlocks = 25
        )

        val first = GoalTransferCounterfactualValidator.validate(
            candidates = listOf(lowAuthorityHistory),
            state = state,
            descriptors = listOf(descriptor(read))
        ).single()
        val second = GoalTransferCounterfactualValidator.validate(
            candidates = listOf(highAuthorityHistory),
            state = state,
            descriptors = listOf(descriptor(read))
        ).single()

        assertEquals(first.contextFit, second.contextFit, 0.0)
        assertEquals(first.projectedSupport, second.projectedSupport, 0.0)
        assertEquals(first.mismatchRisk, second.mismatchRisk, 0.0)
    }

    @Test
    fun currentCapabilityEvidenceCanRerankEqualHistoricalTransferSupport() {
        val state = readyState(
            capabilityEvidence = listOf(
                CapabilityCompetenceSnapshot(
                    capability = read,
                    executed = 12,
                    failed = 0,
                    lastObservedAtEpochMs = 10L
                ),
                CapabilityCompetenceSnapshot(
                    capability = write,
                    executed = 0,
                    failed = 8,
                    lastObservedAtEpochMs = 10L
                )
            )
        )
        val readCandidate = candidate(
            capabilities = listOf(read),
            transferSupport = 0.50
        )
        val writeCandidate = candidate(
            capabilities = listOf(write),
            transferSupport = 0.50
        )

        val validated = GoalTransferCounterfactualValidator.validate(
            candidates = listOf(writeCandidate, readCandidate),
            state = state,
            descriptors = listOf(descriptor(read), descriptor(write))
        )

        assertEquals(2, validated.size)
        assertEquals(read, validated.first().candidate.strategy.capabilities.single())
        assertTrue(validated[0].projectedSupport > validated[1].projectedSupport)
    }

    @Test
    fun rendererContainsOnlyStructuralCurrentFitEvidence() {
        val state = readyState()
        val assessment = GoalTransferCounterfactualValidator.validate(
            candidates = listOf(
                candidate(
                    capabilities = listOf(read),
                    transferSupport = 0.65
                )
            ),
            state = state,
            descriptors = listOf(descriptor(read))
        ).single()

        val rendered = GoalTransferCounterfactualValidator.render(listOf(assessment))

        assertTrue(rendered.contains("<GOAL_OUTCOME_COUNTERFACTUAL_TRANSFER>"))
        assertTrue(rendered.contains("cognitive_state_digest=" + state.canonicalDigest))
        assertTrue(rendered.contains("candidate.1.capabilities=phase341.read"))
        assertTrue(rendered.contains("mismatch_risk="))
        assertTrue(rendered.contains("projected_support="))
        assertTrue(rendered.contains("authority=false"))
        assertFalse(rendered.contains("input="))
        assertFalse(rendered.contains("output="))
        assertFalse(rendered.contains("approval="))
        assertFalse(rendered.contains("tool_id="))
    }

    private fun candidate(
        capabilities: List<CapabilityId>,
        transferSupport: Double,
        authorityBlocks: Int = 0
    ): GoalStrategyTransferCandidate = GoalStrategyTransferCandidate(
        strategy = StrategySignature(capabilities),
        analogousSuccesses = 3,
        executionExhaustions = 1,
        evidenceExhaustions = 0,
        authorityBlocks = authorityBlocks,
        partialExecutionBlocks = 0,
        meanSimilarity = 0.75,
        analogousSuccessRate = 0.75,
        evidenceConfidence = 0.60,
        transferSupport = transferSupport,
        meanHierarchyCompletionRatio = 0.80
    )

    private fun readyState(
        capabilityEvidence: List<CapabilityCompetenceSnapshot> = emptyList()
    ): IntegratedCognitiveStatePacket {
        val runtime = AmperRuntime.reference()
        val base = runtime.integratedCognition.capture(
            query = "validate current transfer context",
            allowedCapabilities = setOf(read, write),
            descriptors = listOf(descriptor(read), descriptor(write))
        )
        return base.copy(
            context = base.context.copy(
                capabilityCompetence = capabilityEvidence
            ),
            learningNeeds = emptyList(),
            perceptualEvidence = emptyList(),
            readiness = base.readiness.copy(
                epistemicConfidence = 0.90,
                worldConfidence = 0.88,
                skillConfidence = 0.82,
                competenceConfidence = 0.80,
                uncertainty = 0.10,
                learningPressure = 0.10,
                overallReadiness = 0.86
            )
        )
    }

    private fun descriptor(capability: CapabilityId): ToolDescriptor = ToolDescriptor(
        id = ToolId("provider-" + capability.value.replace('.', '-')),
        name = "Counterfactual validation provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded counterfactual transfer test contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )
}
