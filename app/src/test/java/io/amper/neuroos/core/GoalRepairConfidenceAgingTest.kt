package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRepairConfidenceAgingTest {
    private val capability = CapabilityId("phase376.repair")
    private val goal = "Keep repaired strategy evidence fresh"

    @Test
    fun requalifiedConfidenceAgesThenExpiresAndLearningPressureReturns() {
        val fixture = fixture()
        fixture.validatePractice(500L)
        val successful = terminalPlan("phase376-success", PlanStepStatus.EXECUTED)
        fixture.repair.observeGovernedOutcome(
            plan = successful,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            observedAtEpochMs = 1_000L
        )
        fixture.credit.observe(
            checkpoint = checkpoint(successful),
            plan = successful,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            portfolioRecords = emptyList(),
            observedAtEpochMs = 1_000L
        )

        fixture.now = 1_000L
        val fresh = fixture.repair.requalifiedConfidence(capability)
        assertTrue(fresh > 0.0)

        fixture.now = 1_000L +
            MemoryBackedGoalRepairValidationModel.FRESH_REQUALIFICATION_MS +
            1L
        val aging = fixture.repair.requalificationAssessment(capability, fixture.now)
        assertEquals(GoalRepairRequalificationFreshness.AGING, aging?.freshness)
        val agingConfidence = aging?.effectiveConfidence ?: 0.0
        assertTrue(agingConfidence > 0.0 && agingConfidence < fresh)

        fixture.now = 1_000L +
            MemoryBackedGoalRepairValidationModel.MAX_REQUALIFICATION_AGE_MS +
            1L
        val expired = fixture.repair.requalificationAssessment(capability, fixture.now)
        assertEquals(GoalRepairRequalificationFreshness.EXPIRED, expired?.freshness)
        assertEquals(0.0, fixture.repair.requalifiedConfidence(capability), 0.0)

        val signal = fixture.credit.learningSignals(setOf(capability), 1).single()
        assertEquals(1.0, fixture.repair.pressureMultiplier(signal), 0.0)
    }

    @Test
    fun laterVerifiedSuccessRenewsAgingEvidence() {
        val fixture = fixture()
        fixture.validatePractice(500L)
        fixture.repair.observeGovernedOutcome(
            terminalPlan("phase378-success-1", PlanStepStatus.EXECUTED),
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            1_000L
        )
        fixture.now = 1_000L + 1_728_000_000L
        val aged = fixture.repair.requalifiedConfidence(capability)

        fixture.repair.observeGovernedOutcome(
            terminalPlan("phase378-success-2", PlanStepStatus.EXECUTED),
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            fixture.now
        )
        val renewed = fixture.repair.requalificationAssessment(capability, fixture.now)

        assertEquals(GoalRepairRequalificationFreshness.FRESH, renewed?.freshness)
        assertTrue((renewed?.effectiveConfidence ?: 0.0) > aged)
        assertEquals(2, renewed?.snapshot?.verifiedSuccesses)
    }

    @Test
    fun realFailureRequiresFreshPracticeBeforeRequalificationCanReturn() {
        val fixture = fixture()
        fixture.validatePractice(500L)
        fixture.repair.observeGovernedOutcome(
            terminalPlan("phase379-success", PlanStepStatus.EXECUTED),
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            1_000L
        )

        val failure = terminalPlan("phase379-failure", PlanStepStatus.FAILED)
        fixture.credit.observeTerminalPlan(
            checkpoint = checkpoint(failure),
            plan = failure,
            portfolioRecords = emptyList(),
            observedAtEpochMs = 2_000L
        )
        fixture.repair.observeGovernedOutcome(
            failure,
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
            2_000L
        )
        fixture.now = 2_500L

        val rejected = fixture.repair.observeGovernedOutcome(
            terminalPlan("phase379-success-without-practice", PlanStepStatus.EXECUTED),
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            2_500L
        )
        assertTrue(rejected.isEmpty())
        assertEquals(0.0, fixture.repair.requalifiedConfidence(capability), 0.0)

        fixture.validatePractice(2_600L)
        val restored = fixture.repair.observeGovernedOutcome(
            terminalPlan("phase379-success-after-practice", PlanStepStatus.EXECUTED),
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            3_000L
        )
        fixture.now = 3_000L
        assertTrue(restored.isNotEmpty())
        assertTrue(fixture.repair.requalifiedConfidence(capability) > 0.0)
    }

    private fun fixture(): Fixture {
        val memory = InMemoryMemoryOs()
        val credit = MemoryBackedGoalHierarchicalStrategyCreditModel(memory)
        repeat(3) { index ->
            val failed = terminalPlan(
                "phase376-seed-" + index,
                PlanStepStatus.FAILED
            )
            credit.observeTerminalPlan(
                checkpoint = checkpoint(failed),
                plan = failed,
                portfolioRecords = emptyList(),
                observedAtEpochMs = 100L + index
            )
        }
        val fixture = Fixture(memory, credit)
        fixture.repair = MemoryBackedGoalRepairValidationModel(
            memory = memory,
            credit = credit,
            clock = { fixture.now }
        )
        return fixture
    }

    private fun checkpoint(plan: SovereignPlan): PersistentGoalExecutiveCheckpoint =
        PersistentGoalExecutiveCheckpoint(
            sourceGoalId = "phase376-goal",
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
        conversationId = ConversationId("phase376-conversation"),
        goal = goal,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId(id + "-request"),
                capability = capability,
                reason = "governed repair aging",
                input = "run",
                status = status,
                boundToolId = ToolId("phase376-tool"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase376-test"
    )

    private inner class Fixture(
        private val memory: MemoryOs,
        val credit: GoalHierarchicalStrategyCreditModel
    ) {
        var now: Long = 1_000L
        lateinit var repair: GoalRepairValidationModel

        fun validatePractice(startAt: Long) {
            val task = AutonomousPracticeTask(
                id = PracticeTaskId("phase376-practice-" + startAt),
                capability = capability,
                kind = AutonomousPracticeKind.CONTRACT_PLAN,
                objective = "Renew the repaired strategy contract.",
                priority = 0.9,
                sourceNeeds = setOf(LearningNeedKind.HIERARCHICAL_STRATEGY_REPAIR)
            )
            repeat(2) { index ->
                repair.observe(
                    task,
                    AutonomousPracticeEvidence(
                        id = MemoryId("phase376-practice-evidence-" + startAt + "-" + index),
                        taskId = task.id,
                        capability = capability,
                        kind = task.kind,
                        verdict = AutonomousPracticeVerdict.PASS,
                        validatedStepCount = 1,
                        observedAtEpochMs = startAt + index
                    )
                )
            }
            assertEquals(
                GoalRepairValidationState.VALIDATED,
                repair.snapshot(capability)?.state
            )
        }
    }
}
