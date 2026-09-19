package io.amper.neuroos.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalDirectedAutonomousLearningTest {
    private val capability = CapabilityId("phase361.repair")

    @Test
    fun negativeHierarchicalCreditBecomesBoundedRepairCurriculum() {
        val memory = InMemoryMemoryOs()
        val competence = MemoryBackedCapabilityCompetenceModel(memory)
        val skills = MemoryBackedSkillGenesisModel(memory)
        val generalization = MemoryBackedSkillGeneralizationModel(memory, skills)
        val credit = MemoryBackedGoalHierarchicalStrategyCreditModel(memory)

        repeat(3) { index ->
            val failed = terminalPlan(
                id = "phase361-failure-$index",
                status = PlanStepStatus.FAILED
            )
            credit.observeTerminalPlan(
                checkpoint = checkpoint(failed),
                plan = failed,
                portfolioRecords = emptyList(),
                observedAtEpochMs = 100L + index
            )
        }

        val signals = credit.learningSignals(setOf(capability), limit = 4)
        assertTrue(signals.isNotEmpty())
        assertTrue(signals.single().meanCredit < 0.0)
        assertTrue(signals.single().evidenceConfidence > 0.0)
        assertFalse(signals.single().authorityBearing)

        val learning = MemoryBackedAutonomousLearningModel(
            memory = memory,
            competence = competence,
            skills = skills,
            generalization = generalization,
            hierarchicalCredit = credit
        )
        val descriptor = descriptor()
        val needs = learning.diagnose(
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor),
            limit = 8
        )
        assertTrue(needs.any {
            it.kind == LearningNeedKind.HIERARCHICAL_STRATEGY_REPAIR
        })

        val task = learning.curriculum(
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor),
            limit = 1
        ).single()
        assertTrue(
            LearningNeedKind.HIERARCHICAL_STRATEGY_REPAIR in task.sourceNeeds
        )
        assertTrue(task.objective.contains("repairing a recurrent hierarchical strategy weakness"))
        assertFalse(task.authorityBearing)
    }

    @Test
    fun authorityNeutralCreditNeverCreatesRepairPressure() {
        val memory = InMemoryMemoryOs()
        val credit = MemoryBackedGoalHierarchicalStrategyCreditModel(memory)
        val denied = terminalPlan(
            id = "phase362-authority",
            status = PlanStepStatus.DENIED
        )

        credit.observeTerminalPlan(
            checkpoint = checkpoint(denied),
            plan = denied,
            portfolioRecords = emptyList(),
            observedAtEpochMs = 200L
        )

        assertTrue(credit.learningSignals(setOf(capability), limit = 4).isEmpty())
    }

    private fun checkpoint(plan: SovereignPlan): PersistentGoalExecutiveCheckpoint =
        PersistentGoalExecutiveCheckpoint(
            sourceGoalId = "phase361-goal",
            objective = "Repair a repeated planning weakness",
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
        conversationId = ConversationId("phase361-learning"),
        goal = "Repair a repeated planning weakness",
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("$id-request"),
                capability = capability,
                reason = "bounded governed evidence",
                input = "run",
                status = status,
                boundToolId = ToolId("phase361-tool"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase361-test"
    )

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase361-tool"),
        name = "phase361 repair tool",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded repair contract",
            acceptedValues = setOf("run"),
            maxLength = 32
        )
    )
}
