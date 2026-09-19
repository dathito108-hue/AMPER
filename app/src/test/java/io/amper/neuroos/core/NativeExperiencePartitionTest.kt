package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeExperiencePartitionTest {
    private val reasoning = CapabilityId("reasoning")
    private val planning = CapabilityId("planning")

    @Test
    fun partitionIsDeterministicDisjointAndCoversExactSelectedSet() {
        val fixture = fixture(10)

        val first = fixture.partitioner.partition(
            trainingShardId = NativeDatasetShardId("phase416-train"),
            holdoutShardId = NativeDatasetShardId("phase416-holdout"),
            holdoutRatio = 0.20,
            minTrainingExamples = 4,
            minHoldoutExamples = 2,
            limit = 10
        )
        val second = fixture.partitioner.partition(
            trainingShardId = NativeDatasetShardId("phase416-train"),
            holdoutShardId = NativeDatasetShardId("phase416-holdout"),
            holdoutRatio = 0.20,
            minTrainingExamples = 4,
            minHoldoutExamples = 2,
            limit = 10
        )

        assertEquals(first, second)
        assertTrue(
            first.trainingShard.exampleIds.toSet()
                .intersect(first.holdoutShard.exampleIds.toSet())
                .isEmpty()
        )
        assertEquals(
            first.selectedExampleIds,
            first.trainingShard.exampleIds.toSet() +
                first.holdoutShard.exampleIds.toSet()
        )
        assertEquals(NativeDatasetRights.GENERATED_INTERNAL, first.trainingShard.manifest.rights)
        assertEquals(NativeDatasetRights.GENERATED_INTERNAL, first.holdoutShard.manifest.rights)
        assertFalse(first.authorityBearing)
    }

    @Test
    fun preMaterializedTrainingPartitionIsReusedByCurriculumWithoutHoldoutLeakage() {
        val fixture = fixture(8)
        val partition = fixture.partitioner.partition(
            trainingShardId = NativeDatasetShardId("phase418-train"),
            holdoutShardId = NativeDatasetShardId("phase418-holdout"),
            holdoutRatio = 0.25,
            minTrainingExamples = 4,
            minHoldoutExamples = 2,
            limit = 8
        )

        val bundle = EvidenceNativeExperienceCurriculumPlanner(
            store = fixture.store,
            foundation = fixture.foundation
        ).synthesize(
            shardId = partition.trainingShard.manifest.id,
            curriculumId = NativeCurriculumId("phase418-curriculum"),
            minExamplesPerCapability = 2,
            limit = 8
        )

        assertEquals(partition.trainingShard, bundle.shard)
        assertTrue(
            bundle.shard.exampleIds.toSet()
                .intersect(partition.holdoutShard.exampleIds.toSet())
                .isEmpty()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun partitionFailsClosedWhenEvidenceCannotSupportBothSides() {
        val fixture = fixture(2)
        fixture.partitioner.partition(
            trainingShardId = NativeDatasetShardId("phase420-train"),
            holdoutShardId = NativeDatasetShardId("phase420-holdout"),
            holdoutRatio = 0.50,
            minTrainingExamples = 2,
            minHoldoutExamples = 1,
            limit = 3
        )
    }

    private fun fixture(count: Int): Fixture {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedNativeExperienceDatasetStore(memory, foundation)
        repeat(count) { index ->
            store.observeVerified(
                plan = plan("phase416-example-" + index),
                verificationConfidence = 0.95,
                observedAtEpochMs = 100L + index
            )
        }
        return Fixture(
            store = store,
            foundation = foundation,
            partitioner = DeterministicNativeExperiencePartitioner(store)
        )
    }

    private fun plan(id: String): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase416-conversation"),
        goal = "partition verified experience " + id,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId(id + "-reason"),
                capability = reasoning,
                reason = "verified partition source",
                input = "private",
                status = PlanStepStatus.EXECUTED,
                boundToolId = ToolId("phase416-reason"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            ),
            SovereignPlanStep(
                index = 2,
                requestId = ActionRequestId(id + "-plan"),
                capability = planning,
                reason = "verified partition source",
                input = "private",
                status = PlanStepStatus.EXECUTED,
                boundToolId = ToolId("phase416-plan"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase416-test"
    )

    private data class Fixture(
        val store: NativeExperienceDatasetStore,
        val foundation: NativeModelFoundation,
        val partitioner: NativeExperiencePartitioner
    )
}
