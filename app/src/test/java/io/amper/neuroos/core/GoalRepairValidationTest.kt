package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRepairValidationTest {
    private val capability = CapabilityId("phase366.repair")
    private val goal = "Repair repeated alpha planning failure"

    @Test
    fun validatedRepairReducesLearningPressureWithoutCreatingExecutionCompetence() {
        val fixture = fixture()
        val baselineNeed = fixture.learning.diagnose(
            setOf(capability),
            listOf(fixture.descriptor),
            limit = 8
        ).single { it.kind == LearningNeedKind.HIERARCHICAL_STRATEGY_REPAIR }
        val task = fixture.learning.curriculum(
            setOf(capability),
            listOf(fixture.descriptor),
            limit = 1
        ).single()

        repeat(2) {
            fixture.learning.assess(
                task = task,
                modelOutput = validPlan(),
                descriptors = listOf(fixture.descriptor)
            )
        }

        val validation = fixture.repair.snapshot(capability)
        assertEquals(GoalRepairValidationState.VALIDATED, validation?.state)
        assertTrue((validation?.validatedConfidence ?: 0.0) > 0.0)
        assertFalse(validation?.authorityBearing ?: true)

        val repairedNeed = fixture.learning.diagnose(
            setOf(capability),
            listOf(fixture.descriptor),
            limit = 8
        ).single { it.kind == LearningNeedKind.HIERARCHICAL_STRATEGY_REPAIR }
        assertTrue(repairedNeed.severity < baselineNeed.severity)
        assertNull(fixture.competence.snapshot(capability))
    }

    @Test
    fun newerNegativeExecutionEvidenceInvalidatesOlderRepairValidation() {
        val fixture = fixture()
        val task = fixture.learning.curriculum(
            setOf(capability),
            listOf(fixture.descriptor),
            limit = 1
        ).single()
        repeat(2) {
            fixture.learning.assess(task, validPlan(), listOf(fixture.descriptor))
        }
        assertTrue(fixture.repair.validatedConfidence(capability) > 0.0)

        val newerFailure = terminalPlan(
            id = "phase369-new-failure",
            status = PlanStepStatus.FAILED
        )
        fixture.credit.observeTerminalPlan(
            checkpoint = checkpoint(newerFailure),
            plan = newerFailure,
            portfolioRecords = emptyList(),
            observedAtEpochMs = 5_000L
        )

        assertEquals(0.0, fixture.repair.validatedConfidence(capability), 0.0)
        val currentSignal = fixture.credit.learningSignals(setOf(capability), 1).single()
        assertEquals(1.0, fixture.repair.pressureMultiplier(currentSignal), 0.0)
    }

    @Test
    fun validatedRepairCanOnlyAttenuateNegativePortfolioPenalty() {
        val fixture = fixture()
        val task = fixture.learning.curriculum(
            setOf(capability),
            listOf(fixture.descriptor),
            limit = 1
        ).single()
        repeat(2) {
            fixture.learning.assess(task, validPlan(), listOf(fixture.descriptor))
        }

        val base = portfolioCandidate()
        val credited = GoalHierarchicalStrategyCreditPolicy.apply(
            goal = goal,
            candidates = listOf(base),
            credit = fixture.credit
        ).single()
        val refined = GoalRepairRefinementPolicy.apply(
            candidates = listOf(credited),
            validation = fixture.repair
        ).single()

        assertTrue(credited.hierarchicalCreditAdjustment < 0.0)
        assertTrue(refined.hierarchicalCreditAdjustment > credited.hierarchicalCreditAdjustment)
        assertTrue(refined.hierarchicalCreditAdjustment <= 0.0)
        assertTrue(refined.portfolioScore > credited.portfolioScore)
        assertTrue(refined.portfolioScore <= base.portfolioScore)
    }

    private fun fixture(): Fixture {
        val memory = InMemoryMemoryOs()
        val competence = MemoryBackedCapabilityCompetenceModel(memory)
        val skills = MemoryBackedSkillGenesisModel(memory)
        val generalization = MemoryBackedSkillGeneralizationModel(memory, skills)
        val credit = MemoryBackedGoalHierarchicalStrategyCreditModel(memory)
        repeat(3) { index ->
            val failed = terminalPlan(
                id = "phase366-failure-$index",
                status = PlanStepStatus.FAILED
            )
            credit.observeTerminalPlan(
                checkpoint = checkpoint(failed),
                plan = failed,
                portfolioRecords = emptyList(),
                observedAtEpochMs = 100L + index
            )
        }
        val repair = MemoryBackedGoalRepairValidationModel(memory, credit)
        var now = 1_000L
        val learning = MemoryBackedAutonomousLearningModel(
            memory = memory,
            competence = competence,
            skills = skills,
            generalization = generalization,
            clock = { now++ },
            hierarchicalCredit = credit,
            repairValidation = repair
        )
        return Fixture(
            competence = competence,
            credit = credit,
            repair = repair,
            learning = learning,
            descriptor = descriptor()
        )
    }

    private fun checkpoint(plan: SovereignPlan): PersistentGoalExecutiveCheckpoint =
        PersistentGoalExecutiveCheckpoint(
            sourceGoalId = "phase366-goal",
            objective = goal,
            conversationId = plan.conversationId,
            priority = 0.8,
            stage = PersistentGoalExecutiveStage.PLANNED,
            plannedPlanId = plan.id,
            updatedAtEpochMs = 10L
        )

    private fun terminalPlan(
        id: String,
        status: PlanStepStatus
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase366-conversation"),
        goal = goal,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("$id-request"),
                capability = capability,
                reason = "governed repair evidence",
                input = "run",
                status = status,
                boundToolId = ToolId("phase366-tool"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase366-test"
    )

    private fun validPlan(): String = """
        <AMPER_PLAN_V1>
        step.1.capability=phase366.repair
        step.1.reason=Practice the repaired live contract
        step.1.input=run
        </AMPER_PLAN_V1>
    """.trimIndent()

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase366-tool"),
        name = "phase366 repair tool",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded repair contract",
            acceptedValues = setOf("run"),
            maxLength = 32
        )
    )

    private fun portfolioCandidate(): GoalStrategyPortfolioCandidate {
        val strategy = StrategySignature(listOf(capability))
        val candidate = GoalStrategyTransferCandidate(
            strategy = strategy,
            analogousSuccesses = 2,
            executionExhaustions = 1,
            evidenceExhaustions = 0,
            authorityBlocks = 0,
            partialExecutionBlocks = 0,
            meanSimilarity = 0.8,
            analogousSuccessRate = 0.67,
            evidenceConfidence = 0.6,
            transferSupport = 0.60,
            meanHierarchyCompletionRatio = 0.7,
            latestAnalogousObservedAtEpochMs = 10L,
            latestSuccessfulObservedAtEpochMs = 10L
        )
        val assessment = GoalTransferCounterfactualAssessment(
            candidate = candidate,
            cognitiveStateDigest = "d".repeat(64),
            liveCapabilityCoverage = 1.0,
            competenceFit = 0.7,
            worldFit = 0.8,
            epistemicFit = 0.8,
            perceptionFit = 0.7,
            learningRisk = 0.2,
            contextFit = 0.75,
            mismatchRisk = 0.25,
            projectedSupport = 0.60,
            calibrationMultiplier = 1.0,
            calibratedSupport = 0.60,
            staleHistoricalEvidence = false,
            decision = GoalTransferValidationDecision.ACCEPT
        )
        val fingerprint = GoalOutcomeFingerprint.of(goal)
        return GoalStrategyPortfolioCandidate(
            assessment = assessment,
            contextDigest = GoalContextualStrategyPortfolioPolicy.contextDigest(fingerprint),
            contextFingerprint = fingerprint,
            similarSelections = 0,
            contextualRewardRate = null,
            contextualEvidenceConfidence = 0.0,
            contextualRegretProxy = 0.0,
            exploitationScore = 0.60,
            explorationBonus = 0.0,
            portfolioScore = 0.60,
            mode = GoalStrategyPortfolioMode.EXPLOIT
        )
    }

    private data class Fixture(
        val competence: CapabilityCompetenceModel,
        val credit: GoalHierarchicalStrategyCreditModel,
        val repair: GoalRepairValidationModel,
        val learning: AutonomousLearningModel,
        val descriptor: ToolDescriptor
    )
}
