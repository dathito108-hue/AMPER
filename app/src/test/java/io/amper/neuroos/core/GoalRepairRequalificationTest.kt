package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRepairRequalificationTest {
    private val capability = CapabilityId("phase371.repair")
    private val followCapability = CapabilityId("phase371.follow")
    private val goal = "Repair recurrent governed execution"

    @Test
    fun verifiedRealSuccessRequalifiesValidatedRepairAndTransfersStructurally() {
        val fixture = fixture()
        validatePractice(fixture.repair)
        val observed = fixture.repair.observeGovernedOutcome(
            plan = terminalPlan("phase371-success", listOf(capability), PlanStepStatus.EXECUTED),
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            observedAtEpochMs = 1_000L
        )
        val snapshot = observed.single()
        assertEquals(GoalRepairRequalificationState.REQUALIFIED, snapshot.state)
        assertTrue(snapshot.evidenceConfidence > 0.0)
        assertFalse(snapshot.authorityBearing)
        assertTrue(
            fixture.repair.requalifiedTransferConfidence(
                StrategySignature(listOf(capability))
            ) > 0.0
        )
        assertEquals(
            0.0,
            fixture.repair.requalifiedTransferConfidence(
                StrategySignature(listOf(capability, followCapability))
            ),
            0.0
        )
    }

    @Test
    fun laterRealFailureInvalidatesRequalificationImmediately() {
        val fixture = fixture()
        validatePractice(fixture.repair)
        fixture.repair.observeGovernedOutcome(
            terminalPlan("phase373-success", listOf(capability), PlanStepStatus.EXECUTED),
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            1_000L
        )
        assertTrue(fixture.repair.requalifiedConfidence(capability) > 0.0)
        fixture.repair.observeGovernedOutcome(
            terminalPlan("phase373-failure", listOf(capability), PlanStepStatus.FAILED),
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
            2_000L
        )
        assertEquals(0.0, fixture.repair.requalifiedConfidence(capability), 0.0)
        assertEquals(
            GoalRepairRequalificationState.INVALIDATED,
            fixture.repair.requalificationSnapshot(capability)?.state
        )
    }

    @Test
    fun authorityOutcomeIsNeutralToRequalifiedRepair() {
        val fixture = fixture()
        validatePractice(fixture.repair)
        fixture.repair.observeGovernedOutcome(
            terminalPlan("phase373-base-success", listOf(capability), PlanStepStatus.EXECUTED),
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            1_000L
        )
        val before = fixture.repair.requalifiedConfidence(capability)
        val neutral = fixture.repair.observeGovernedOutcome(
            terminalPlan("phase373-authority", listOf(capability), PlanStepStatus.DENIED),
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED,
            2_000L
        )
        assertTrue(neutral.isEmpty())
        assertEquals(before, fixture.repair.requalifiedConfidence(capability), 0.0)
    }

    private fun fixture(): Fixture {
        val memory = InMemoryMemoryOs()
        val credit = MemoryBackedGoalHierarchicalStrategyCreditModel(memory)
        repeat(3) { index ->
            val failed = terminalPlan(
                "phase371-seed-" + index,
                listOf(capability),
                PlanStepStatus.FAILED
            )
            credit.observeTerminalPlan(
                checkpoint = checkpoint(failed),
                plan = failed,
                portfolioRecords = emptyList(),
                observedAtEpochMs = 100L + index
            )
        }
        return Fixture(
            MemoryBackedGoalRepairValidationModel(
                memory = memory,
                credit = credit,
                clock = { 1_000L }
            )
        )
    }

    private fun validatePractice(repair: GoalRepairValidationModel) {
        val task = AutonomousPracticeTask(
            id = PracticeTaskId("phase371-practice"),
            capability = capability,
            kind = AutonomousPracticeKind.CONTRACT_PLAN,
            objective = "Repair the recurrent hierarchical strategy weakness.",
            priority = 0.9,
            sourceNeeds = setOf(LearningNeedKind.HIERARCHICAL_STRATEGY_REPAIR)
        )
        repeat(2) { index ->
            repair.observe(
                task,
                AutonomousPracticeEvidence(
                    id = MemoryId("phase371-practice-evidence-" + index),
                    taskId = task.id,
                    capability = capability,
                    kind = task.kind,
                    verdict = AutonomousPracticeVerdict.PASS,
                    validatedStepCount = 1,
                    observedAtEpochMs = 500L + index
                )
            )
        }
        assertEquals(GoalRepairValidationState.VALIDATED, repair.snapshot(capability)?.state)
    }

    private fun checkpoint(plan: SovereignPlan): PersistentGoalExecutiveCheckpoint =
        PersistentGoalExecutiveCheckpoint(
            sourceGoalId = "phase371-goal",
            objective = goal,
            conversationId = plan.conversationId,
            priority = 0.8,
            stage = PersistentGoalExecutiveStage.PLANNED,
            plannedPlanId = plan.id,
            updatedAtEpochMs = 10L
        )

    private fun terminalPlan(
        id: String,
        capabilities: List<CapabilityId>,
        status: PlanStepStatus
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase371-conversation"),
        goal = goal,
        steps = capabilities.mapIndexed { index, item ->
            SovereignPlanStep(
                index = index + 1,
                requestId = ActionRequestId(id + "-request-" + (index + 1)),
                capability = item,
                reason = "governed repair requalification",
                input = "run",
                status = status,
                boundToolId = ToolId("phase371-tool-" + (index + 1)),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        },
        planningBackendId = "phase371-test"
    )

    private data class Fixture(val repair: GoalRepairValidationModel)
}
