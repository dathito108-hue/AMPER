package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRepairMemoryRegretCalibrationTest {
    private val capability = CapabilityId("phase391.repair")
    private val strategy = StrategySignature(listOf(capability))

    @Test
    fun onlyBoundRepairMemoryBonusReceivesOutcomeAttribution() {
        val fixture = fixture()
        val plan = terminalPlan("phase391-neutral", PlanStepStatus.FAILED)
        val noInfluence = decision(plan.id, repairBonus = 0.0)

        val observed = fixture.memory.observeAttributedOutcome(
            plan,
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
            noInfluence,
            1_000L
        )

        assertEquals(null, observed)
        assertEquals(null, fixture.memory.calibration(strategy))
    }

    @Test
    fun twoAttributedFailuresDemoteRepairMemorySupportToZero() {
        val fixture = fixture()
        fixture.activatePattern()

        repeat(2) { index ->
            val plan = terminalPlan("phase393-failure-" + index, PlanStepStatus.FAILED)
            fixture.memory.observeAttributedOutcome(
                plan,
                GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
                decision(plan.id, GoalRepairStrategyMemoryPolicy.MAX_PORTFOLIO_BONUS),
                2_000L + index
            )
        }

        val calibration = fixture.memory.calibration(strategy)
        assertTrue(calibration?.demoted == true)
        assertEquals(2, calibration?.consecutiveAttributedFailures)
        assertEquals(0.0, calibration?.calibrationMultiplier ?: -1.0, 0.0)
        assertEquals(0.0, fixture.memory.support(strategy), 0.0)
        assertFalse(calibration?.authorityBearing ?: true)
    }

    @Test
    fun newerRealQualifiedSuccessRestoresOnlyProbationarySupport() {
        val fixture = fixture()
        fixture.activatePattern()
        repeat(2) { index ->
            val plan = terminalPlan("phase394-failure-" + index, PlanStepStatus.FAILED)
            fixture.memory.observeAttributedOutcome(
                plan,
                GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
                decision(plan.id, GoalRepairStrategyMemoryPolicy.MAX_PORTFOLIO_BONUS),
                2_000L + index
            )
        }
        assertEquals(0.0, fixture.memory.support(strategy), 0.0)

        val recovery = terminalPlan("phase394-recovery", PlanStepStatus.EXECUTED)
        fixture.repair.observeGovernedOutcome(
            recovery,
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            3_000L
        )
        fixture.memory.observe(
            recovery,
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            3_000L
        )

        val calibration = fixture.memory.calibration(strategy)
        assertTrue(calibration?.probationary == true)
        assertFalse(calibration?.demoted ?: true)
        assertTrue((calibration?.calibrationMultiplier ?: 0.0) in 0.0..0.25)
        assertTrue(fixture.memory.support(strategy) > 0.0)

        val attributedSuccess = terminalPlan("phase394-attributed-success", PlanStepStatus.EXECUTED)
        fixture.memory.observeAttributedOutcome(
            attributedSuccess,
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            decision(attributedSuccess.id, GoalRepairStrategyMemoryPolicy.MAX_PORTFOLIO_BONUS),
            3_100L
        )
        val promoted = fixture.memory.calibration(strategy)
        assertFalse(promoted?.probationary ?: true)
        assertTrue((promoted?.calibrationMultiplier ?: 0.0) > 0.25)
    }

    private fun fixture(): Fixture {
        val rawMemory = InMemoryMemoryOs()
        val credit = MemoryBackedGoalHierarchicalStrategyCreditModel(rawMemory)
        repeat(3) { index ->
            val failed = terminalPlan("phase391-seed-" + index, PlanStepStatus.FAILED)
            credit.observeTerminalPlan(
                checkpoint = checkpoint(failed),
                plan = failed,
                portfolioRecords = emptyList(),
                observedAtEpochMs = 100L + index
            )
        }
        val repair = MemoryBackedGoalRepairValidationModel(
            memory = rawMemory,
            credit = credit,
            clock = { 3_100L }
        )
        validatePractice(repair)
        return Fixture(
            repair = repair,
            memory = MemoryBackedGoalRepairStrategyMemory(rawMemory, repair)
        )
    }

    private fun Fixture.activatePattern() {
        repeat(2) { index ->
            val plan = terminalPlan("phase391-success-" + index, PlanStepStatus.EXECUTED)
            repair.observeGovernedOutcome(
                plan,
                GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
                1_000L + index
            )
            memory.observe(
                plan,
                GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
                1_000L + index
            )
        }
        assertTrue(memory.snapshot(strategy)?.active == true)
        assertTrue(memory.support(strategy) > 0.0)
    }

    private fun validatePractice(repair: GoalRepairValidationModel) {
        val task = AutonomousPracticeTask(
            id = PracticeTaskId("phase391-practice"),
            capability = capability,
            kind = AutonomousPracticeKind.CONTRACT_PLAN,
            objective = "Validate repair for regret calibration.",
            priority = 0.9,
            sourceNeeds = setOf(LearningNeedKind.HIERARCHICAL_STRATEGY_REPAIR)
        )
        repeat(2) { index ->
            repair.observe(
                task,
                AutonomousPracticeEvidence(
                    id = MemoryId("phase391-practice-evidence-" + index),
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

    private fun checkpoint(plan: SovereignPlan) = PersistentGoalExecutiveCheckpoint(
        sourceGoalId = "phase391-goal",
        objective = "Calibrate repair memory regret",
        conversationId = plan.conversationId,
        priority = 0.8,
        stage = PersistentGoalExecutiveStage.PLANNED,
        plannedPlanId = plan.id,
        updatedAtEpochMs = 10L
    )

    private fun terminalPlan(id: String, status: PlanStepStatus): SovereignPlan =
        SovereignPlan(
            id = PlanId(id),
            conversationId = ConversationId("phase391-conversation"),
            goal = "Calibrate repair memory regret",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId(id + "-request"),
                    capability = capability,
                    reason = "repair-memory attribution",
                    input = "run",
                    status = status,
                    boundToolId = ToolId("phase391-tool"),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "phase391-test"
        )

    private fun decision(
        planId: PlanId,
        repairBonus: Double
    ): GoalStrategyPortfolioDecision {
        val fingerprint = GoalOutcomeFingerprint.of("Calibrate repair memory regret")
        return GoalStrategyPortfolioDecision(
            planId = planId,
            contextDigest = GoalContextualStrategyPortfolioPolicy.contextDigest(fingerprint),
            contextFingerprint = fingerprint,
            strategy = strategy,
            mode = GoalStrategyPortfolioMode.EXPLOIT,
            exploitationScore = 0.60,
            explorationBonus = 0.0,
            portfolioScore = 0.60 + repairBonus,
            bestExploitationScore = 0.60,
            selectedAtEpochMs = 900L,
            repairMemorySupport = if (repairBonus > 0.0) 1.0 else 0.0,
            repairMemoryBonus = repairBonus
        )
    }

    private data class Fixture(
        val repair: GoalRepairValidationModel,
        val memory: GoalRepairStrategyMemory
    )
}
