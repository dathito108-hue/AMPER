package io.amper.neuroos.core

import org.junit.Assert.assertTrue
import org.junit.Test

class ProceduralMemoryReadPolicyTest {
    @Test
    fun publicRecentReadsHaveHardBounds() {
        val skills = MemoryBackedSkillGenesisModel(InMemoryMemoryOs())
        assertTrue(
            runCatching {
                skills.recent(ProceduralMemoryReadPolicy.MAX_SKILL_RECENT + 1)
            }.isFailure
        )

        val generalization = MemoryBackedSkillGeneralizationModel(
            InMemoryMemoryOs(),
            MemoryBackedSkillGenesisModel(InMemoryMemoryOs())
        )
        assertTrue(
            runCatching {
                generalization.recent(
                    ProceduralMemoryReadPolicy.MAX_GENERALIZATION_RECENT + 1
                )
            }.isFailure
        )

        val repair = MemoryBackedGoalRepairStrategyMemory(
            InMemoryMemoryOs(),
            NoopRepairValidation
        )
        assertTrue(
            runCatching {
                repair.recent(ProceduralMemoryReadPolicy.MAX_REPAIR_RECENT + 1)
            }.isFailure
        )
    }

    @Test
    fun skillIndexMissingSnapshotFailsVisible() {
        val memory = InMemoryMemoryOs()
        memory.remember(
            indexRecord(
                id = "skill:index",
                kind = MemoryBackedSkillGenesisModel.INDEX_KIND
            )
        )

        assertTrue(
            runCatching { MemoryBackedSkillGenesisModel(memory).recent(1) }.isFailure
        )
    }

    @Test
    fun generalizationIndexMissingSnapshotFailsVisible() {
        val memory = InMemoryMemoryOs()
        memory.remember(
            indexRecord(
                id = "skill-generalization:index",
                kind = MemoryBackedSkillGeneralizationModel.INDEX_KIND
            )
        )
        val model = MemoryBackedSkillGeneralizationModel(
            memory,
            MemoryBackedSkillGenesisModel(memory)
        )

        assertTrue(runCatching { model.recent(1) }.isFailure)
    }

    @Test
    fun repairIndexMissingSnapshotFailsVisible() {
        val memory = InMemoryMemoryOs()
        memory.remember(
            indexRecord(
                id = "goal-repair-strategy-memory:index",
                kind = MemoryBackedGoalRepairStrategyMemory.INDEX_KIND
            )
        )
        val model = MemoryBackedGoalRepairStrategyMemory(
            memory,
            NoopRepairValidation
        )

        assertTrue(runCatching { model.recent(1) }.isFailure)
    }

    @Test
    fun malformedOrWrongKindIndexFailsVisible() {
        val memory = InMemoryMemoryOs()
        memory.remember(
            MemoryRecord(
                id = MemoryId("skill:index"),
                kind = "unexpected",
                content = "digests=" + "a".repeat(64),
                importance = 0.5,
                provenance = Provenance(source = "test", producer = "test")
            )
        )

        assertTrue(
            runCatching { MemoryBackedSkillGenesisModel(memory).recent(1) }.isFailure
        )
    }

    private fun indexRecord(id: String, kind: String) =
        MemoryRecord(
            id = MemoryId(id),
            kind = kind,
            content = "digests=" + "a".repeat(64),
            importance = 0.5,
            provenance = Provenance(source = "test", producer = "test")
        )

    private object NoopRepairValidation : GoalRepairValidationModel {
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
}
