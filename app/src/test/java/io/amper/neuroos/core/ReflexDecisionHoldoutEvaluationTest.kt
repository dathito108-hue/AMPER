package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexDecisionHoldoutEvaluationTest {
    @Test
    fun perfectHeldoutClassifierPassesSpecializedAndCanonicalAdmission() {
        val fixture = fixture("phase436")
        val checkpoint = fixture.trainCheckpoint()

        val record = fixture.evaluator.evaluate(
            checkpointId = checkpoint,
            holdoutShardId = fixture.prepared.partition.holdoutShard.manifest.id,
            inference = ReflexCheckpointInferencePort { _, example ->
                Result.success(perfectPrediction(example))
            }
        )

        assertTrue(record.specializedPassed)
        assertEquals(1.0, record.metrics.exactDecisionAccuracy, 0.0)
        assertEquals(1.0, record.metrics.actionPrecision, 0.0)
        assertEquals(1.0, record.metrics.escalationRecall, 0.0)
        assertTrue(record.nativeEvaluation.admission.admitted)
        assertTrue(
            fixture.training.promotionCandidate(
                candidateCheckpointId = checkpoint,
                baselineCheckpointId = null
            ).promotable
        )
    }

    @Test
    fun unsafeActionFalsePositivesFailAdmissionAndPromotion() {
        val fixture = fixture("phase438")
        val checkpoint = fixture.trainCheckpoint()

        val record = fixture.evaluator.evaluate(
            checkpointId = checkpoint,
            holdoutShardId = fixture.prepared.partition.holdoutShard.manifest.id,
            inference = ReflexCheckpointInferencePort { _, example ->
                if (example.targetDisposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2) {
                    Result.success(
                        ReflexDecisionPrediction(
                            disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                            capability = DeviceStatusToolContract.capability,
                            sideEffect = ToolSideEffect.READ_ONLY,
                            confidence = 0.99
                        )
                    )
                } else {
                    Result.success(perfectPrediction(example))
                }
            }
        )

        assertFalse(record.specializedPassed)
        assertTrue(record.metrics.unsafeActionFalsePositiveRate > 0.0)
        assertFalse(record.nativeEvaluation.admission.admitted)
        assertFalse(
            fixture.training.promotionCandidate(
                candidateCheckpointId = checkpoint,
                baselineCheckpointId = null
            ).promotable
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun genericEvaluationCannotBypassReflexHoldoutGate() {
        val fixture = fixture("phase439")
        val checkpoint = fixture.trainCheckpoint()

        fixture.training.recordEvaluation(
            checkpointId = checkpoint,
            evaluation = NativeCheckpointEvaluation(
                planningProtocolPassRate = 1.0,
                toolContractPassRate = 1.0,
                regressionPassRate = 1.0,
                heldoutGeneralizationPassRate = 1.0,
                planningSamples = 64,
                toolContractSamples = 64,
                regressionSamples = 64,
                heldoutGeneralizationSamples = 64
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun trainingShardCannotBeReusedAsReflexHoldout() {
        val fixture = fixture("phase440")
        val checkpoint = fixture.trainCheckpoint()

        fixture.evaluator.evaluate(
            checkpointId = checkpoint,
            holdoutShardId = fixture.prepared.partition.trainingShard.manifest.id,
            inference = ReflexCheckpointInferencePort { _, example ->
                Result.success(perfectPrediction(example))
            }
        )
    }

    private fun perfectPrediction(
        example: ReflexExperienceTrainingExample
    ): ReflexDecisionPrediction = when (example.targetDisposition) {
        ReflexDecisionDisposition.ESCALATE_SYSTEM2 ->
            ReflexDecisionPrediction(
                disposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
                confidence = 0.999
            )
        ReflexDecisionDisposition.PROPOSE_ACTION ->
            ReflexDecisionPrediction(
                disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                capability = requireNotNull(example.targetCapability),
                sideEffect = requireNotNull(example.targetSideEffect),
                confidence = 0.999
            )
    }

    private fun fixture(prefix: String): Fixture {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedReflexExperienceDatasetStore(memory, foundation)
        seed(store, prefix, 32)
        val partitioner = DeterministicReflexExperiencePartitioner(store)
        val curriculum = EvidenceReflexDecisionCurriculumPlanner(store, foundation)
        val training = MemoryBackedNativeTrainingPipeline(
            memory = memory,
            foundation = foundation,
            reflexExperienceDatasets = store,
            clock = { 4_000L }
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
            sourceLabel = "reflex holdout teacher",
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
            clock = { 3_000L }
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
            holdoutRatio = 0.50,
            minTrainingPerClass = 8,
            minHoldoutPerClass = 8,
            limit = 64
        )
        val prepared = coordinator.prepare(spec)
        val evaluator = MemoryBackedReflexDecisionCheckpointEvaluator(
            memory = memory,
            foundation = foundation,
            datasets = store,
            training = training,
            clock = { 5_000L }
        )
        return Fixture(foundation, training, prepared, evaluator)
    }

    private fun seed(
        store: ReflexExperienceDatasetStore,
        prefix: String,
        perClass: Int
    ) {
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
        repeat(perClass) { index ->
            val proposal = ActionProposal(
                requestId = ActionRequestId("$prefix-action-$index"),
                capability = descriptor.capability,
                reason = "read status",
                input = "summary"
            )
            store.observeExecuted(
                userInput = "battery status sample $index",
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
        repeat(perClass) { index ->
            store.observeEscalation(
                conversationId = ConversationId("$prefix-thread-$index"),
                userInput = "explain a novel concept sample $index",
                descriptors = listOf(descriptor),
                response = InferenceResponse(
                    modelId = ModelId("teacher-$index"),
                    backendId = "teacher-backend",
                    text = "answer-$index"
                ),
                observedAtEpochMs = 1_000L + index
            )
        }
    }

    private data class Fixture(
        val foundation: NativeModelFoundation,
        val training: NativeTrainingPipeline,
        val prepared: PreparedReflexDecisionTraining,
        val evaluator: ReflexDecisionCheckpointEvaluator
    ) {
        fun trainCheckpoint(): NativeCheckpointId {
            val result = training.execute(
                prepared.run.id,
                NativeTrainerPort { request ->
                    Result.success(
                        NativeTrainingArtifact(
                            runId = request.runId,
                            manifestDigest = request.manifest.canonicalDigest,
                            trainingBackendId = "reflex-holdout-test-trainer",
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
            assertEquals(NativeTrainingRunStatus.SUCCEEDED, result.status)
            return prepared.run.outputCheckpointId
        }
    }
}
