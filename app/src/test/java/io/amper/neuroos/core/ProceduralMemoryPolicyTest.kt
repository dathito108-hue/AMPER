package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProceduralMemoryPolicyTest {
    @Test
    fun proceduralReadBudgetsAreFiniteAndEnforced() {
        val memory = InMemoryMemoryOs()
        val skills = MemoryBackedSkillGenesisModel(memory)
        val generalization = MemoryBackedSkillGeneralizationModel(memory, skills)
        val repair = MemoryBackedGoalRepairStrategyMemory(
            memory = memory,
            repairValidation = object : GoalRepairValidationModel {
                override fun observe(
                    task: AutonomousPracticeTask,
                    evidence: AutonomousPracticeEvidence
                ): GoalRepairValidationSnapshot? = null

                override fun snapshot(
                    capability: CapabilityId
                ): GoalRepairValidationSnapshot? = null

                override fun validatedConfidence(capability: CapabilityId): Double = 0.0

                override fun pressureMultiplier(
                    signal: GoalHierarchicalLearningSignal
                ): Double = 1.0
            }
        )

        assertTrue(
            runCatching {
                skills.recent(ProceduralMemoryPolicy.MAX_SKILL_RECENT + 1)
            }.isFailure
        )
        assertTrue(
            runCatching {
                generalization.recent(
                    ProceduralMemoryPolicy.MAX_GENERALIZATION_RECENT + 1
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                repair.recent(ProceduralMemoryPolicy.MAX_REPAIR_RECENT + 1)
            }.isFailure
        )
    }

    @Test
    fun proceduralPolicyMatchesExistingMobileGuidanceBudgets() {
        assertEquals(4, ProceduralMemoryPolicy.MAX_SKILL_GUIDANCE)
        assertEquals(3, ProceduralMemoryPolicy.MAX_SKILL_COMPOSITIONS)
        assertEquals(4, ProceduralMemoryPolicy.MAX_GENERALIZATION_GUIDANCE)
        assertEquals(3, ProceduralMemoryPolicy.MAX_GENERALIZATION_CHAINS)
        assertEquals(16, ProceduralMemoryPolicy.SKILL_GUIDANCE_LOOKBACK)
        assertEquals(24, ProceduralMemoryPolicy.GENERALIZATION_LOOKBACK)
        assertEquals(32, ProceduralMemoryPolicy.MAX_REPAIR_RECENT)
    }
}
