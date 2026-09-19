package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRepairStrategyMemoryTest {
    private val capability = CapabilityId("phase381.repair")
    private val goal = "Distill repeated real repair success"

    @Test
    fun twoConsecutiveRealQualifiedSuccessesActivateDistilledPattern() {
        val fixture = fixture()
        fixture.validatePractice()

        val first = terminalPlan("phase381-success-1", PlanStepStatus.EXECUTED)
        fixture.repair.observeGovernedOutcome(
            first,
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            1_000L
        )
        val firstPattern = fixture.strategyMemory.observe(
            first,
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            1_000L
        )
        assertFalse(firstPattern?.active ?: true)
        assertEquals(0.0, fixture.strategyMemory.support(StrategySignature.from(first)), 0.0)

        val second = terminalPlan("phase381-success-2", PlanStepStatus.EXECUTED)
        fixture.repair.observeGovernedOutcome(
            second,
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            1_100L
        )
        val active = fixture.strategyMemory.observe(
            second,
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            1_100L
        )
        assertTrue(active?.active == true)
        assertFalse(active?.authorityBearing ?: true)
        assertTrue(fixture.strategyMemory.support(StrategySignature.from(second)) > 0.0)
    }

    @Test
    fun realFailureResetsDistilledSuccessStreakAndAuthorityBlockIsNeutral() {
        val fixture = fixture()
        fixture.validatePractice()
        repeat(2) { index ->
            val plan = terminalPlan("phase383-success-" + index, PlanStepStatus.EXECUTED)
            fixture.repair.observeGovernedOutcome(
                plan,
                GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
                1_000L + index
            )
            fixture.strategyMemory.observe(
                plan,
                GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
                1_000L + index
            )
        }
        val signature = StrategySignature(listOf(capability))
        assertTrue(fixture.strategyMemory.snapshot(signature)?.active == true)

        val authority = terminalPlan("phase383-authority", PlanStepStatus.DENIED)
        val neutral = fixture.strategyMemory.observe(
            authority,
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED,
            1_500L
        )
        assertTrue(neutral?.active == true)

        val failure = terminalPlan("phase383-failure", PlanStepStatus.FAILED)
        fixture.repair.observeGovernedOutcome(
            failure,
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
            2_000L
        )
        val reset = fixture.strategyMemory.observe(
            failure,
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
            2_000L
        )
        assertFalse(reset?.active ?: true)
        assertEquals(0, reset?.consecutiveVerifiedSuccesses)
        assertEquals(0.0, fixture.strategyMemory.support(signature), 0.0)
    }

    @Test
    fun portfolioBonusIsStrictlyBoundedAndAuthorityNeutral() {
        val memory = object : GoalRepairStrategyMemory {
            override fun observe(
                plan: SovereignPlan,
                outcome: GoalOutcomeEvidenceKind,
                observedAtEpochMs: Long
            ): GoalRepairStrategyPattern? = null
            override fun snapshot(strategy: StrategySignature): GoalRepairStrategyPattern? = null
            override fun recent(limit: Int): List<GoalRepairStrategyPattern> = emptyList()
            override fun support(strategy: StrategySignature): Double = 1.0
        }
        val base = portfolioCandidate()
        val ranked = GoalRepairStrategyMemoryPolicy.apply(listOf(base), memory).single()
        assertEquals(1.0, ranked.repairMemorySupport, 0.0)
        assertEquals(GoalRepairStrategyMemoryPolicy.MAX_PORTFOLIO_BONUS, ranked.repairMemoryBonus, 0.0)
        assertEquals(base.portfolioScore + GoalRepairStrategyMemoryPolicy.MAX_PORTFOLIO_BONUS, ranked.portfolioScore, 1e-9)
        assertFalse(ranked.authorityBearing)
    }

    private fun fixture(): Fixture {
        val memory = InMemoryMemoryOs()
        val credit = MemoryBackedGoalHierarchicalStrategyCreditModel(memory)
        repeat(3) { index ->
            val failed = terminalPlan("phase381-seed-" + index, PlanStepStatus.FAILED)
            credit.observeTerminalPlan(
                checkpoint = checkpoint(failed),
                plan = failed,
                portfolioRecords = emptyList(),
                observedAtEpochMs = 100L + index
            )
        }
        val repair = MemoryBackedGoalRepairValidationModel(
            memory = memory,
            credit = credit,
            clock = { 1_100L }
        )
        return Fixture(
            repair = repair,
            strategyMemory = MemoryBackedGoalRepairStrategyMemory(memory, repair)
        )
    }

    private fun Fixture.validatePractice() {
        val task = AutonomousPracticeTask(
            id = PracticeTaskId("phase381-practice"),
            capability = capability,
            kind = AutonomousPracticeKind.CONTRACT_PLAN,
            objective = "Validate repair before real strategy distillation.",
            priority = 0.9,
            sourceNeeds = setOf(LearningNeedKind.HIERARCHICAL_STRATEGY_REPAIR)
        )
        repeat(2) { index ->
            repair.observe(
                task,
                AutonomousPracticeEvidence(
                    id = MemoryId("phase381-practice-evidence-" + index),
                    taskId = task.id,
                    capability = capability,
                    kind = task.kind,
                    verdict = AutonomousPracticeVerdict.PASS,
                    validatedStepCount = 1,
                    observedAtEpochMs = 500L + index
                )
            )
        }
    }

    private fun checkpoint(plan: SovereignPlan): PersistentGoalExecutiveCheckpoint =
        PersistentGoalExecutiveCheckpoint(
            sourceGoalId = "phase381-goal",
            objective = goal,
            conversationId = plan.conversationId,
            priority = 0.8,
            stage = PersistentGoalExecutiveStage.PLANNED,
            plannedPlanId = plan.id,
            updatedAtEpochMs = 10L
        )

    private fun terminalPlan(id: String, status: PlanStepStatus): SovereignPlan =
        SovereignPlan(
            id = PlanId(id),
            conversationId = ConversationId("phase381-conversation"),
            goal = goal,
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId(id + "-request"),
                    capability = capability,
                    reason = "governed repair strategy distillation",
                    input = "run",
                    status = status,
                    boundToolId = ToolId("phase381-tool"),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "phase381-test"
        )

    private fun portfolioCandidate(): GoalStrategyPortfolioCandidate {
        val strategy = StrategySignature(listOf(capability))
        val candidate = GoalStrategyTransferCandidate(
            strategy = strategy,
            analogousSuccesses = 2,
            executionExhaustions = 0,
            evidenceExhaustions = 0,
            authorityBlocks = 0,
            partialExecutionBlocks = 0,
            meanSimilarity = 0.9,
            analogousSuccessRate = 1.0,
            evidenceConfidence = 0.5,
            transferSupport = 0.6,
            meanHierarchyCompletionRatio = 1.0,
            latestAnalogousObservedAtEpochMs = 10L,
            latestSuccessfulObservedAtEpochMs = 10L
        )
        val assessment = GoalTransferCounterfactualAssessment(
            candidate = candidate,
            cognitiveStateDigest = "e".repeat(64),
            liveCapabilityCoverage = 1.0,
            competenceFit = 0.8,
            worldFit = 0.8,
            epistemicFit = 0.8,
            perceptionFit = 0.8,
            learningRisk = 0.1,
            contextFit = 0.8,
            mismatchRisk = 0.2,
            projectedSupport = 0.6,
            calibrationMultiplier = 1.0,
            calibratedSupport = 0.6,
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
        val repair: GoalRepairValidationModel,
        val strategyMemory: GoalRepairStrategyMemory
    )
}
