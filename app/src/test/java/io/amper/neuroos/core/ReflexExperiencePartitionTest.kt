package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexExperiencePartitionTest {
    @Test
    fun stratifiedPartitionKeepsBothDecisionClassesDisjoint() {
        val fixture = fixture("phase431")
        val partition = fixture.partitioner.partition(
            trainingShardId = NativeDatasetShardId("phase431-train"),
            holdoutShardId = NativeDatasetShardId("phase431-holdout"),
            holdoutRatio = 0.34,
            minTrainingPerClass = 2,
            minHoldoutPerClass = 1,
            limit = 6
        )

        assertTrue(
            partition.trainingShard.exampleIds.toSet()
                .intersect(partition.holdoutShard.exampleIds.toSet())
                .isEmpty()
        )
        assertEquals(2, partition.trainingActionExamples)
        assertEquals(2, partition.trainingEscalationExamples)
        assertEquals(1, partition.holdoutActionExamples)
        assertEquals(1, partition.holdoutEscalationExamples)
        assertEquals(6, partition.selectedExampleIds.size)
        assertTrue(partition.authorityBearing.not())
    }

    private fun fixture(prefix: String): Fixture {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedReflexExperienceDatasetStore(memory, foundation)
        seed(store, prefix)
        return Fixture(
            partitioner = DeterministicReflexExperiencePartitioner(store)
        )
    }

    private fun seed(store: ReflexExperienceDatasetStore, prefix: String) {
        val descriptor = descriptor()
        repeat(3) { index ->
            val proposal = ActionProposal(
                requestId = ActionRequestId("$prefix-action-$index"),
                capability = descriptor.capability,
                reason = "read status",
                input = "summary"
            )
            store.observeExecuted(
                userInput = "check battery status $index",
                descriptors = listOf(descriptor),
                action = ActionOutcome(
                    status = ActionStatus.EXECUTED,
                    proposal = proposal,
                    toolId = descriptor.id,
                    sideEffect = descriptor.sideEffect,
                    output = "battery_percent=50"
                ),
                source = ReflexExperienceSource.SYSTEM2_TEACHER,
                labelConfidence = 0.98,
                observedAtEpochMs = 100L + index
            )
        }
        repeat(3) { index ->
            store.observeEscalation(
                conversationId = ConversationId("$prefix-thread-$index"),
                userInput = "explain concept $index",
                descriptors = listOf(descriptor),
                response = InferenceResponse(
                    modelId = ModelId("teacher-$index"),
                    backendId = "teacher-backend",
                    text = "answer-$index"
                ),
                observedAtEpochMs = 200L + index
            )
        }
    }

    private fun descriptor() = ToolDescriptor(
        id = DeviceStatusToolContract.toolId,
        name = "status",
        capability = DeviceStatusToolContract.capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "status",
            acceptedValues = setOf("summary", "status"),
            maxLength = 16
        )
    )

    private data class Fixture(
        val partitioner: ReflexExperiencePartitioner
    )
}
