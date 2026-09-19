package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexDecisionHeldoutEvaluationTest {
    @Test
    fun perfectHeldoutPredictionsAdmitReflexOnlyCheckpoint() {
        val fixture = fixture("phase441-pass")
        val record = fixture.runtime.reflexDecisionEvaluation.evaluate(
            checkpointId = fixture.checkpointId,
            holdoutShardId = fixture.holdoutShardId,
            evaluator = ReflexDecisionEvaluatorPort { request ->
                Result.success(
                    request.examples.map { example ->
                        ReflexDecisionPrediction(
                            exampleId = example.id,
                            disposition = example.targetDisposition,
                            capability = example.targetCapability,
                            confidence = 0.999,
                            uncertainty = 0.001
                        )
                    }
                )
            }
        )

        assertTrue(record.admission.admitted)
        assertEquals(0, record.evaluation.planningSamples)
        val reflex = requireNotNull(record.evaluation.reflexDecision)
        assertEquals(32, reflex.totalSamples)
        assertEquals(16, reflex.actionSamples)
        assertEquals(16, reflex.escalationSamples)
        assertEquals(1.0, reflex.exactDecisionAccuracy, 0.0)
        assertEquals(1.0, reflex.actionPrecision, 0.0)
        assertEquals(1.0, reflex.escalationRecall, 0.0)
        assertEquals(1.0, reflex.capabilityAccuracy, 0.0)
        assertTrue(reflex.calibrationMeanAbsoluteError < 0.01)
        assertNotNull(fixture.runtime.nativeTrainingPipeline.getEvaluation(fixture.checkpointId))
    }

    @Test
    fun oneHighConfidenceFalseActionRejectsCheckpoint() {
        val fixture = fixture("phase444-reject")
        val record = fixture.runtime.reflexDecisionEvaluation.evaluate(
            checkpointId = fixture.checkpointId,
            holdoutShardId = fixture.holdoutShardId,
            evaluator = ReflexDecisionEvaluatorPort { request ->
                var corrupted = false
                Result.success(
                    request.examples.map { example ->
                        if (
                            !corrupted &&
                            example.targetDisposition ==
                                ReflexDecisionDisposition.ESCALATE_SYSTEM2
                        ) {
                            corrupted = true
                            ReflexDecisionPrediction(
                                exampleId = example.id,
                                disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                                capability = DeviceStatusToolContract.capability,
                                confidence = 0.999,
                                uncertainty = 0.001
                            )
                        } else {
                            ReflexDecisionPrediction(
                                exampleId = example.id,
                                disposition = example.targetDisposition,
                                capability = example.targetCapability,
                                confidence = 0.999,
                                uncertainty = 0.001
                            )
                        }
                    }
                )
            }
        )

        assertEquals(NativeCheckpointAdmissionStatus.REJECTED, record.admission.status)
        assertTrue(
            record.admission.reasons.any {
                it.contains("action precision") || it.contains("exact-decision")
            }
        )
    }

    private fun fixture(prefix: String): Fixture {
        val runtime = AmperRuntime.reference()
        seed(runtime.reflexExperienceDatasets, prefix)
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
        runtime.nativeModelFoundation.putContract(contract)
        val teacher = NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId("$prefix-teacher"),
            modelId = ModelId("$prefix-teacher-model"),
            artifactSha256 = "a".repeat(64),
            sourceLabel = "reflex heldout teacher",
            rights = NativeTeacherRights.INTERNAL,
            capabilities = setOf(TitanCapabilities.REFLEX_DECISION),
            local = true,
            createdAtEpochMs = 1_000L
        )
        runtime.nativeTrainingPipeline.putTeacher(teacher)
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
                outputFormat = "bin",
                quantization = "int8",
                maxRuntimeMemoryMb = 256,
                contextTokens = 512,
                androidArm64 = true
            ),
            teacherTemperature = 1.0,
            teacherLossWeight = 0.5,
            holdoutRatio = 0.40,
            minTrainingPerClass = 2,
            minHoldoutPerClass = 1,
            limit = 80
        )
        val prepared = runtime.reflexDecisionTraining.prepare(spec)
        val finished = runtime.nativeTrainingPipeline.execute(
            prepared.run.id,
            NativeTrainerPort { request ->
                Result.success(
                    NativeTrainingArtifact(
                        runId = request.runId,
                        manifestDigest = request.manifest.canonicalDigest,
                        trainingBackendId = "reflex-heldout-test",
                        weightArtifactSha256 = "b".repeat(64),
                        outputFormat = request.manifest.target.outputFormat,
                        quantization = request.manifest.target.quantization,
                        artifactBytes = 4_096L,
                        examplesSeen = prepared.partition.trainingShard.exampleIds.size.toLong(),
                        completedSteps = 8L,
                        finalLoss = 0.1,
                        executionBindingDigest = request.executionBindingDigest,
                        datasetSnapshotDigest = request.manifest.datasetSnapshotDigest,
                        curriculumDigest = request.manifest.curriculumDigest
                    )
                )
            }
        )
        assertEquals(NativeTrainingRunStatus.SUCCEEDED, finished.status)
        return Fixture(
            runtime = runtime,
            checkpointId = spec.outputCheckpointId,
            holdoutShardId = prepared.partition.holdoutShard.manifest.id
        )
    }

    private fun seed(store: ReflexExperienceDatasetStore, prefix: String) {
        val descriptor = ToolDescriptor(
            id = DeviceStatusToolContract.toolId,
            name = "device status",
            capability = DeviceStatusToolContract.capability,
            sideEffect = ToolSideEffect.READ_ONLY,
            inputContract = ToolInputContract(
                description = "status",
                acceptedValues = setOf("summary"),
                maxLength = 16
            )
        )
        repeat(40) { index ->
            val proposal = ActionProposal(
                requestId = ActionRequestId("$prefix-action-$index"),
                capability = DeviceStatusToolContract.capability,
                reason = "read device status",
                input = "summary"
            )
            store.observeExecuted(
                userInput = "check battery and ram now $index",
                descriptors = listOf(descriptor),
                action = ActionOutcome(
                    status = ActionStatus.EXECUTED,
                    proposal = proposal,
                    toolId = descriptor.id,
                    sideEffect = descriptor.sideEffect,
                    output = "ok"
                ),
                source = ReflexExperienceSource.SYSTEM2_TEACHER,
                labelConfidence = 0.99,
                observedAtEpochMs = 1_000L + index
            )
        }
        repeat(40) { index ->
            store.observeEscalation(
                conversationId = ConversationId("$prefix-escalation-$index"),
                userInput = "explain a difficult concept number $index",
                descriptors = listOf(descriptor),
                response = InferenceResponse(
                    modelId = ModelId("teacher"),
                    backendId = "teacher-backend",
                    text = "reasoned answer $index"
                ),
                labelConfidence = 0.90,
                observedAtEpochMs = 2_000L + index
            )
        }
    }

    private data class Fixture(
        val runtime: AmperRuntime,
        val checkpointId: NativeCheckpointId,
        val holdoutShardId: NativeDatasetShardId
    )
}
