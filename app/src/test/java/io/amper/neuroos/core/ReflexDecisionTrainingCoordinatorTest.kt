package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexDecisionTrainingCoordinatorTest {
    @Test
    fun preparedReflexRunUsesTrainingShardOnlyAndPassesActualPayloadToTrainer() {
        val fixture = fixture("phase433")
        val prepared = fixture.coordinator.prepare(fixture.spec)

        assertEquals(NativeTrainingRunStatus.PREPARED, prepared.run.status)
        assertEquals(
            listOf(prepared.partition.trainingShard.manifest.id),
            prepared.manifest.datasetShardIds
        )
        assertTrue(prepared.partition.holdoutShard.manifest.id !in prepared.manifest.datasetShardIds)
        assertNull(fixture.foundation.getCheckpoint(fixture.spec.outputCheckpointId))

        var captured: NativeTrainingRequest? = null
        val result = fixture.training.execute(
            prepared.run.id,
            NativeTrainerPort { request ->
                captured = request
                Result.success(
                    NativeTrainingArtifact(
                        runId = request.runId,
                        manifestDigest = request.manifest.canonicalDigest,
                        trainingBackendId = "reflex-test-trainer",
                        weightArtifactSha256 = "c".repeat(64),
                        outputFormat = request.manifest.target.outputFormat,
                        quantization = request.manifest.target.quantization,
                        artifactBytes = 2_048L,
                        examplesSeen = prepared.partition.trainingShard.exampleIds.size.toLong(),
                        completedSteps = 4L,
                        finalLoss = 0.20,
                        executionBindingDigest = request.executionBindingDigest,
                        datasetSnapshotDigest = request.manifest.datasetSnapshotDigest,
                        curriculumDigest = request.manifest.curriculumDigest
                    )
                )
            }
        )

        val request = requireNotNull(captured)
        assertEquals(1, request.generatedDatasetPayloads.size)
        assertEquals(
            NativeGeneratedDatasetKind.REFLEX_DECISION_EXPERIENCE,
            request.generatedDatasetPayloads.single().kind
        )
        assertEquals(
            prepared.partition.trainingShard.payload,
            request.generatedDatasetPayloads.single().payload
        )
        assertEquals(NativeTrainingRunStatus.SUCCEEDED, result.status)
        assertNotNull(fixture.foundation.getCheckpoint(fixture.spec.outputCheckpointId))
    }

    @Test
    fun reflexTrainerBindingMismatchFailsBeforeCheckpointPublication() {
        val fixture = fixture("phase435")
        val prepared = fixture.coordinator.prepare(fixture.spec)

        val result = fixture.training.execute(
            prepared.run.id,
            NativeTrainerPort { request ->
                Result.success(
                    NativeTrainingArtifact(
                        runId = request.runId,
                        manifestDigest = request.manifest.canonicalDigest,
                        trainingBackendId = "reflex-bad-trainer",
                        weightArtifactSha256 = "d".repeat(64),
                        outputFormat = request.manifest.target.outputFormat,
                        quantization = request.manifest.target.quantization,
                        artifactBytes = 2_048L,
                        examplesSeen = prepared.partition.trainingShard.exampleIds.size.toLong(),
                        completedSteps = 4L,
                        finalLoss = 0.25,
                        executionBindingDigest = "0".repeat(64),
                        datasetSnapshotDigest = request.manifest.datasetSnapshotDigest,
                        curriculumDigest = request.manifest.curriculumDigest
                    )
                )
            }
        )

        assertEquals(NativeTrainingRunStatus.FAILED, result.status)
        assertNull(fixture.foundation.getCheckpoint(fixture.spec.outputCheckpointId))
    }

    private fun fixture(prefix: String): Fixture {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedReflexExperienceDatasetStore(memory, foundation)
        seed(store, prefix)
        val partitioner = DeterministicReflexExperiencePartitioner(store)
        val curriculum = EvidenceReflexDecisionCurriculumPlanner(store, foundation)
        val training = MemoryBackedNativeTrainingPipeline(
            memory = memory,
            foundation = foundation,
            generatedDatasetResolver = ReflexExperienceGeneratedDatasetResolver(store),
            clock = { 2_000L }
        )
        val contract = AmperNativeModelContract(
            id = NativeModelContractId("$prefix-contract"),
            familyVersion = 1,
            parameterCount = 2_000_000L,
            layerCount = 4,
            hiddenSize = 256,
            maxContextTokens = 1_024,
            capabilities = setOf(TitanCapabilities.REFLEX_DECISION),
            ownedByAmper = true
        )
        foundation.putContract(contract)
        val teacher = NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId("$prefix-teacher"),
            modelId = ModelId("$prefix-teacher-model"),
            artifactSha256 = "a".repeat(64),
            sourceLabel = "reflex decision teacher",
            rights = NativeTeacherRights.INTERNAL,
            capabilities = setOf(TitanCapabilities.REFLEX_DECISION),
            local = true,
            createdAtEpochMs = 10L
        )
        training.putTeacher(teacher)
        val coordinator = CanonicalReflexDecisionTrainingCoordinator(
            partitioner = partitioner,
            curriculum = curriculum,
            foundation = foundation,
            training = training,
            clock = { 2_000L }
        )
        val spec = ReflexDecisionTrainingSpec(
            trainingShardId = NativeDatasetShardId("$prefix-train"),
            holdoutShardId = NativeDatasetShardId("$prefix-holdout"),
            curriculumId = NativeCurriculumId("$prefix-curriculum"),
            manifestId = NativeDistillationManifestId("$prefix-manifest"),
            runId = NativeTrainingRunId("$prefix-run"),
            outputCheckpointId = NativeCheckpointId("$prefix-checkpoint"),
            teacherSnapshotIds = listOf(teacher.id),
            studentContractId = contract.id,
            optimizer = "adamw",
            precision = "fp16",
            maxSequenceTokens = 512,
            learningRate = 0.0005,
            target = NativeMobileTargetProfile(
                outputFormat = "gguf",
                quantization = "q8_0",
                maxRuntimeMemoryMb = 512,
                contextTokens = 512,
                androidArm64 = true
            ),
            holdoutRatio = 0.34,
            minTrainingPerClass = 2,
            minHoldoutPerClass = 1,
            limit = 6
        )
        return Fixture(foundation, training, coordinator, spec)
    }

    private fun seed(store: ReflexExperienceDatasetStore, prefix: String) {
        val descriptor = ToolDescriptor(
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
        repeat(3) { index ->
            val proposal = ActionProposal(
                requestId = ActionRequestId("$prefix-action-$index"),
                capability = descriptor.capability,
                reason = "read status",
                input = "summary"
            )
            store.observeExecuted(
                userInput = "battery status $index",
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
                userInput = "explain a concept $index",
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

    private data class Fixture(
        val foundation: NativeModelFoundation,
        val training: NativeTrainingPipeline,
        val coordinator: ReflexDecisionTrainingCoordinator,
        val spec: ReflexDecisionTrainingSpec
    )
}
