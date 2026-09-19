package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeExperienceCurriculumTest {
    private val reasoning = CapabilityId("reasoning")
    private val planning = CapabilityId("planning")
    private val rare = CapabilityId("rare.capability")

    @Test
    fun curriculumIsDerivedOnlyFromExactShardEvidenceCoverage() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedNativeExperienceDatasetStore(memory, foundation)
        val planner = EvidenceNativeExperienceCurriculumPlanner(store, foundation)

        store.observeVerified(
            plan("phase401-a", listOf(reasoning, planning, rare)),
            0.93,
            1_000L
        )
        store.observeVerified(
            plan("phase401-b", listOf(reasoning, planning)),
            0.94,
            1_100L
        )

        val bundle = planner.synthesize(
            shardId = NativeDatasetShardId("phase401-shard"),
            curriculumId = NativeCurriculumId("phase401-curriculum"),
            minExamplesPerCapability = 2,
            limit = 2
        )

        assertEquals(2, bundle.curriculum.stages.size)
        assertEquals(setOf(reasoning), bundle.curriculum.stages[0].capabilities)
        assertEquals(setOf(planning), bundle.curriculum.stages[1].capabilities)
        assertEquals(2, bundle.capabilityExampleCounts.getValue(reasoning))
        assertEquals(2, bundle.capabilityExampleCounts.getValue(planning))
        assertEquals(1, bundle.capabilityExampleCounts.getValue(rare))
        assertTrue(bundle.curriculum.stages.none { rare in it.capabilities })
        assertEquals(bundle.curriculum, foundation.getCurriculum(bundle.curriculum.id))
        assertEquals(
            bundle.datasetSnapshotDigest,
            foundation.datasetSnapshotDigest(listOf(bundle.shard.manifest.id))
        )
        assertFalse(bundle.authorityBearing)
    }

    @Test(expected = IllegalArgumentException::class)
    fun curriculumFailsClosedWhenNoCapabilityMeetsEvidenceThreshold() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedNativeExperienceDatasetStore(memory, foundation)
        val planner = EvidenceNativeExperienceCurriculumPlanner(store, foundation)

        store.observeVerified(
            plan("phase404-reasoning", listOf(reasoning)),
            0.95,
            1_000L
        )
        store.observeVerified(
            plan("phase404-planning", listOf(planning)),
            0.96,
            1_100L
        )

        planner.synthesize(
            shardId = NativeDatasetShardId("phase404-shard"),
            curriculumId = NativeCurriculumId("phase404-curriculum"),
            minExamplesPerCapability = 2,
            limit = 2
        )
    }

    private fun plan(
        id: String,
        capabilities: List<CapabilityId>
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase401-conversation"),
        goal = "verified native experience curriculum",
        steps = capabilities.mapIndexed { index, capability ->
            SovereignPlanStep(
                index = index + 1,
                requestId = ActionRequestId(id + "-" + index),
                capability = capability,
                reason = "verified curriculum source",
                input = "bounded",
                status = PlanStepStatus.EXECUTED,
                boundToolId = ToolId("phase401-tool-" + index),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        },
        planningBackendId = "phase401-test"
    )
}
