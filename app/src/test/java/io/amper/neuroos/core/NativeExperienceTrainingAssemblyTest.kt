package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NativeExperienceTrainingAssemblyTest {
    private val reasoning = CapabilityId("reasoning")
    private val planning = CapabilityId("planning")

    @Test
    fun verifiedExperienceBundlePreparesIdempotentRunWithoutExecutingTrainer() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedNativeExperienceDatasetStore(memory, foundation)
        val curriculumPlanner = EvidenceNativeExperienceCurriculumPlanner(store, foundation)

        store.observeVerified(plan("phase406-a"), 0.93, 1_000L)
        store.observeVerified(plan("phase406-b"), 0.95, 1_100L)
        val bundle = curriculumPlanner.synthesize(
            shardId = NativeDatasetShardId("phase406-shard"),
            curriculumId = NativeCurriculumId("phase406-curriculum"),
            minExamplesPerCapability = 2,
            limit = 2
        )

        val contract = AmperNativeModelContract(
            id = NativeModelContractId("phase406-contract"),
            familyVersion = 1,
            parameterCount = 120_000_000L,
            layerCount = 24,
            hiddenSize = 1024,
            maxContextTokens = 8192,
            capabilities = setOf(reasoning, planning),
            ownedByAmper = true
        )
        foundation.putContract(contract)

        val pipeline = MemoryBackedNativeTrainingPipeline(
            memory = memory,
            foundation = foundation,
            experienceDatasets = store,
            clock = { 2_000L }
        )
        val teacher = NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId("phase406-teacher"),
            modelId = ModelId("phase406-teacher-model"),
            artifactSha256 = "8".repeat(64),
            sourceLabel = "user-owned verified teacher",
            rights = NativeTeacherRights.USER_OWNED,
            capabilities = setOf(reasoning, planning),
            local = true,
            createdAtEpochMs = 1_500L
        )
        pipeline.putTeacher(teacher)

        val assembler = CanonicalNativeExperienceTrainingAssembler(
            foundation = foundation,
            pipeline = pipeline,
            clock = { 1_900L }
        )
        val target = NativeMobileTargetProfile(
            outputFormat = "gguf",
            quantization = "Q4_K_M",
            maxRuntimeMemoryMb = 2048,
            contextTokens = 2048,
            androidArm64 = true
        )

        val first = assembler.prepare(
            bundle = bundle,
            teacherSnapshotIds = listOf(teacher.id),
            studentContractId = contract.id,
            manifestId = NativeDistillationManifestId("phase406-manifest"),
            runId = NativeTrainingRunId("phase406-run"),
            outputCheckpointId = NativeCheckpointId("phase406-output"),
            target = target
        )
        val replay = assembler.prepare(
            bundle = bundle,
            teacherSnapshotIds = listOf(teacher.id),
            studentContractId = contract.id,
            manifestId = NativeDistillationManifestId("phase406-manifest"),
            runId = NativeTrainingRunId("phase406-run"),
            outputCheckpointId = NativeCheckpointId("phase406-output"),
            target = target
        )

        assertEquals(first, replay)
        assertEquals(NativeTrainingRunStatus.PREPARED, first.run.status)
        assertEquals(bundle.shard.manifest.id, first.manifest.datasetShardIds.single())
        assertEquals(bundle.curriculum.id, first.manifest.curriculumId)
        assertEquals(bundle.datasetSnapshotDigest, first.manifest.datasetSnapshotDigest)
        assertNull(foundation.getCheckpoint(NativeCheckpointId("phase406-output")))
        assertFalse(first.authorityBearing)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTeacherRightsFailThroughExistingManifestValidation() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedNativeExperienceDatasetStore(memory, foundation)
        val curriculumPlanner = EvidenceNativeExperienceCurriculumPlanner(store, foundation)
        store.observeVerified(plan("phase409-a"), 0.93, 1_000L)
        store.observeVerified(plan("phase409-b"), 0.94, 1_100L)
        val bundle = curriculumPlanner.synthesize(
            NativeDatasetShardId("phase409-shard"),
            NativeCurriculumId("phase409-curriculum"),
            2,
            2
        )

        val contract = AmperNativeModelContract(
            id = NativeModelContractId("phase409-contract"),
            familyVersion = 1,
            parameterCount = 120_000_000L,
            layerCount = 24,
            hiddenSize = 1024,
            maxContextTokens = 4096,
            capabilities = setOf(reasoning, planning),
            ownedByAmper = true
        )
        foundation.putContract(contract)
        val pipeline = MemoryBackedNativeTrainingPipeline(
            memory = memory,
            foundation = foundation,
            experienceDatasets = store
        )
        val teacher = NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId("phase409-unknown"),
            modelId = ModelId("phase409-model"),
            artifactSha256 = "7".repeat(64),
            sourceLabel = "unknown-rights teacher",
            rights = NativeTeacherRights.UNKNOWN,
            capabilities = setOf(reasoning, planning),
            local = true,
            createdAtEpochMs = 1_500L
        )
        pipeline.putTeacher(teacher)

        CanonicalNativeExperienceTrainingAssembler(
            foundation = foundation,
            pipeline = pipeline,
            clock = { 2_000L }
        ).prepare(
            bundle = bundle,
            teacherSnapshotIds = listOf(teacher.id),
            studentContractId = contract.id,
            manifestId = NativeDistillationManifestId("phase409-manifest"),
            runId = NativeTrainingRunId("phase409-run"),
            outputCheckpointId = NativeCheckpointId("phase409-output"),
            target = NativeMobileTargetProfile(
                outputFormat = "gguf",
                quantization = "Q4_K_M",
                maxRuntimeMemoryMb = 2048,
                contextTokens = 2048
            )
        )
    }

    private fun plan(id: String): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase406-conversation"),
        goal = "verified experience native training assembly",
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId(id + "-reason"),
                capability = reasoning,
                reason = "reason",
                input = "bounded",
                status = PlanStepStatus.EXECUTED,
                boundToolId = ToolId("phase406-reason"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            ),
            SovereignPlanStep(
                index = 2,
                requestId = ActionRequestId(id + "-plan"),
                capability = planning,
                reason = "plan",
                input = "bounded",
                status = PlanStepStatus.EXECUTED,
                boundToolId = ToolId("phase406-plan"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase406-test"
    )
}
