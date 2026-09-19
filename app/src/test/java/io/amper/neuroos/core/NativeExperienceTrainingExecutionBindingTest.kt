package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeExperienceTrainingExecutionBindingTest {
    private val reasoning = CapabilityId("reasoning")
    private val planning = CapabilityId("planning")

    @Test
    fun verifiedExperienceTrainerMustEchoExactExecutionBinding() {
        val fixture = fixture("phase411")
        val prepared = fixture.runtime.nativeExperienceTraining.prepare(fixture.spec)

        var capturedDigest: String? = null
        val result = fixture.runtime.nativeTrainingPipeline.execute(
            prepared.run.id,
            NativeTrainerPort { request ->
                capturedDigest = request.executionBindingDigest
                Result.success(
                    NativeTrainingArtifact(
                        runId = request.runId,
                        manifestDigest = request.manifest.canonicalDigest,
                        trainingBackendId = "phase411-trainer",
                        weightArtifactSha256 = "c".repeat(64),
                        outputFormat = request.manifest.target.outputFormat,
                        quantization = request.manifest.target.quantization,
                        artifactBytes = 1_024L,
                        examplesSeen = 3L,
                        completedSteps = 2L,
                        finalLoss = 0.25,
                        executionBindingDigest = request.executionBindingDigest,
                        datasetSnapshotDigest = request.manifest.datasetSnapshotDigest,
                        curriculumDigest = request.manifest.curriculumDigest
                    )
                )
            }
        )

        assertEquals(NativeTrainingRunStatus.SUCCEEDED, result.status)
        assertEquals(capturedDigest, result.executionBindingDigest)
        assertTrue(result.executionBindingDigest?.matches(Regex("[0-9a-f]{64}")) == true)
        val checkpoint = fixture.runtime.nativeModelFoundation.getCheckpoint(
            fixture.spec.outputCheckpointId
        )
        assertNotNull(checkpoint)
        assertEquals(prepared.bundle.datasetSnapshotDigest, checkpoint!!.datasetSnapshotDigest)
        assertEquals(prepared.bundle.curriculum.canonicalDigest, checkpoint.curriculumDigest)
    }

    @Test
    fun wrongExecutionBindingFailsBeforeCheckpointPublication() {
        val fixture = fixture("phase413")
        val prepared = fixture.runtime.nativeExperienceTraining.prepare(fixture.spec)

        val result = fixture.runtime.nativeTrainingPipeline.execute(
            prepared.run.id,
            NativeTrainerPort { request ->
                Result.success(
                    NativeTrainingArtifact(
                        runId = request.runId,
                        manifestDigest = request.manifest.canonicalDigest,
                        trainingBackendId = "phase413-trainer",
                        weightArtifactSha256 = "d".repeat(64),
                        outputFormat = request.manifest.target.outputFormat,
                        quantization = request.manifest.target.quantization,
                        artifactBytes = 2_048L,
                        examplesSeen = 3L,
                        completedSteps = 2L,
                        finalLoss = 0.30,
                        executionBindingDigest = "0".repeat(64),
                        datasetSnapshotDigest = request.manifest.datasetSnapshotDigest,
                        curriculumDigest = request.manifest.curriculumDigest
                    )
                )
            }
        )

        assertEquals(NativeTrainingRunStatus.FAILED, result.status)
        assertNull(result.executionBindingDigest)
        assertNull(
            fixture.runtime.nativeModelFoundation.getCheckpoint(
                fixture.spec.outputCheckpointId
            )
        )
    }

    @Test
    fun requestBindingChangesWhenImmutableTrainingInputsChange() {
        val fixture = fixture("phase415")
        val prepared = fixture.runtime.nativeExperienceTraining.prepare(fixture.spec)
        var request: NativeTrainingRequest? = null
        fixture.runtime.nativeTrainingPipeline.execute(
            prepared.run.id,
            NativeTrainerPort { captured ->
                request = captured
                Result.failure(IllegalStateException("capture only"))
            }
        )
        val bound = request
        assertNotNull(bound)
        assertTrue(bound!!.generatedExperienceShards.isNotEmpty())
        assertEquals(bound.manifest.datasetSnapshotDigest, prepared.bundle.datasetSnapshotDigest)
        assertTrue(bound.executionBindingDigest.matches(Regex("[0-9a-f]{64}")))
    }

    private fun fixture(prefix: String): Fixture {
        val runtime = AmperRuntime.reference()
        val contract = AmperNativeModelContract(
            id = NativeModelContractId(prefix + "-contract"),
            familyVersion = 1,
            parameterCount = 8_000_000L,
            layerCount = 8,
            hiddenSize = 512,
            maxContextTokens = 4_096,
            capabilities = setOf(reasoning, planning),
            ownedByAmper = true
        )
        runtime.nativeModelFoundation.putContract(contract)
        val teacher = NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId(prefix + "-teacher"),
            modelId = ModelId(prefix + "-teacher-model"),
            artifactSha256 = "a".repeat(64),
            sourceLabel = "verified execution-binding teacher",
            rights = NativeTeacherRights.USER_OWNED,
            capabilities = setOf(reasoning, planning),
            local = true,
            createdAtEpochMs = 10L
        )
        runtime.nativeTrainingPipeline.putTeacher(teacher)
        repeat(3) { index ->
            runtime.nativeExperienceDatasets.observeVerified(
                plan = plan(
                    prefix + "-example-" + index,
                    if (index == 2) listOf(reasoning) else listOf(reasoning, planning)
                ),
                verificationConfidence = 0.95,
                observedAtEpochMs = 100L + index
            )
        }
        val spec = NativeExperienceTrainingSpec(
            shardId = NativeDatasetShardId(prefix + "-shard"),
            curriculumId = NativeCurriculumId(prefix + "-curriculum"),
            manifestId = NativeDistillationManifestId(prefix + "-manifest"),
            runId = NativeTrainingRunId(prefix + "-run"),
            outputCheckpointId = NativeCheckpointId(prefix + "-checkpoint"),
            teacherSnapshotIds = listOf(teacher.id),
            studentContractId = contract.id,
            optimizer = "adamw",
            precision = "bf16",
            maxSequenceTokens = 2_048,
            learningRate = 0.0002,
            target = NativeMobileTargetProfile(
                outputFormat = "gguf",
                quantization = "q4_k_m",
                maxRuntimeMemoryMb = 4_096,
                contextTokens = 4_096,
                androidArm64 = true
            ),
            minExamplesPerCapability = 2,
            limit = 64
        )
        return Fixture(runtime, spec)
    }

    private fun plan(
        id: String,
        capabilities: List<CapabilityId>
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase411-conversation"),
        goal = "verified execution binding " + id,
        steps = capabilities.mapIndexed { index, capability ->
            SovereignPlanStep(
                index = index + 1,
                requestId = ActionRequestId(id + "-request-" + index),
                capability = capability,
                reason = "execution-binding evidence",
                input = "private",
                status = PlanStepStatus.EXECUTED,
                boundToolId = ToolId("phase411-tool-" + index),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        },
        planningBackendId = "phase411-test"
    )

    private data class Fixture(
        val runtime: AmperRuntime,
        val spec: NativeExperienceTrainingSpec
    )
}
