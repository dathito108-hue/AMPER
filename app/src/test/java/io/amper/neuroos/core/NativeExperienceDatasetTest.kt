package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeExperienceDatasetTest {
    private val reasoning = CapabilityId("reasoning")
    private val planning = CapabilityId("planning")

    @Test
    fun verifiedExperienceIsIdempotentAndStoresOnlyStructuralTrainingSignal() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedNativeExperienceDatasetStore(memory, foundation)
        val plan = verifiedPlan(
            id = "phase396-private-plan",
            goal = "Private customer objective alpha",
            secretInput = "secret-input-alpha"
        )

        val first = store.observeVerified(plan, 0.93, 1_000L)
        val replay = store.observeVerified(plan, 0.93, 2_000L)

        assertEquals(first, replay)
        assertEquals(setOf(reasoning, planning), first.strategy.capabilities.toSet())
        assertTrue(first.goalFingerprint.all { it.matches(Regex("[0-9a-f]{16}")) })
        assertFalse(first.authorityBearing)
        assertEquals(1, store.recentExamples().size)
    }

    @Test
    fun materializedShardHasRealPayloadDigestAndGeneratedInternalRights() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedNativeExperienceDatasetStore(memory, foundation)
        store.observeVerified(
            verifiedPlan("phase398-a", "Inspect bounded system state", "private-a"),
            0.91,
            1_000L
        )
        store.observeVerified(
            verifiedPlan("phase398-b", "Plan bounded system update", "private-b"),
            0.94,
            1_100L
        )

        val shard = store.materializeShard(
            id = NativeDatasetShardId("amper-experience-phase398"),
            minExamples = 2,
            limit = 2
        )

        assertEquals(NativeDatasetRights.GENERATED_INTERNAL, shard.manifest.rights)
        assertEquals(2L, shard.manifest.exampleCount)
        assertEquals(setOf(reasoning, planning), shard.manifest.targetCapabilities)
        assertTrue(shard.payload.contains("goal_fingerprint="))
        assertTrue(shard.payload.contains("strategy=reasoning>planning"))
        assertFalse(shard.payload.contains("private-a"))
        assertFalse(shard.payload.contains("private-b"))
        assertEquals(shard.manifest, foundation.getDatasetShard(shard.manifest.id))
        assertEquals(shard, store.getShard(shard.manifest.id))
    }

    @Test
    fun nativeTrainerReceivesGeneratedExperiencePayloadThroughExistingPipeline() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val store = MemoryBackedNativeExperienceDatasetStore(memory, foundation)

        store.observeVerified(
            verifiedPlan("phase399-a", "Reason about bounded state", "private-a"),
            0.92,
            1_000L
        )
        store.observeVerified(
            verifiedPlan("phase399-b", "Plan bounded action", "private-b"),
            0.95,
            1_100L
        )
        val shard = store.materializeShard(
            NativeDatasetShardId("amper-experience-phase399"),
            minExamples = 2,
            limit = 2
        )

        val contract = AmperNativeModelContract(
            id = NativeModelContractId("phase399-contract"),
            familyVersion = 1,
            parameterCount = 120_000_000L,
            layerCount = 24,
            hiddenSize = 1024,
            maxContextTokens = 8192,
            capabilities = setOf(reasoning, planning),
            ownedByAmper = true
        )
        val curriculum = NativeCurriculumManifest(
            id = NativeCurriculumId("phase399-curriculum"),
            stages = listOf(
                NativeCurriculumStage(
                    index = 1,
                    name = "verified-governed-experience",
                    capabilities = setOf(reasoning, planning),
                    minExamples = 2,
                    weight = 1.0
                )
            )
        )
        val recipe = NativeTrainingRecipe(
            optimizer = "adamw",
            precision = "bf16",
            maxSequenceTokens = 2048,
            learningRate = 0.0002,
            curriculumDigest = curriculum.canonicalDigest
        )
        foundation.putContract(contract)
        foundation.putCurriculum(curriculum)

        val pipeline = MemoryBackedNativeTrainingPipeline(
            memory = memory,
            foundation = foundation,
            clock = { 2_000L },
            experienceDatasets = store
        )
        val teacher = NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId("phase399-teacher"),
            modelId = ModelId("phase399-teacher-model"),
            artifactSha256 = "9".repeat(64),
            sourceLabel = "user-owned phase399 teacher",
            rights = NativeTeacherRights.USER_OWNED,
            capabilities = setOf(reasoning, planning),
            local = true,
            createdAtEpochMs = 1_500L
        )
        pipeline.putTeacher(teacher)

        val manifest = NativeDistillationManifest(
            id = NativeDistillationManifestId("phase399-manifest"),
            teacherSnapshotIds = listOf(teacher.id),
            studentContractId = contract.id,
            studentContractDigest = contract.canonicalDigest,
            parentCheckpointId = null,
            datasetShardIds = listOf(shard.manifest.id),
            datasetSnapshotDigest = foundation.datasetSnapshotDigest(
                listOf(shard.manifest.id)
            ),
            curriculumId = curriculum.id,
            curriculumDigest = curriculum.canonicalDigest,
            recipe = recipe,
            target = NativeMobileTargetProfile(
                outputFormat = "gguf",
                quantization = "Q4_K_M",
                maxRuntimeMemoryMb = 2048,
                contextTokens = 2048,
                androidArm64 = true
            ),
            teacherTemperature = 2.0,
            teacherLossWeight = 0.65,
            createdAtEpochMs = 1_800L
        )
        pipeline.putManifest(manifest)
        pipeline.prepareRun(
            id = NativeTrainingRunId("phase399-run"),
            manifestId = manifest.id,
            outputCheckpointId = NativeCheckpointId("phase399-checkpoint")
        )

        var captured: NativeTrainingRequest? = null
        val result = pipeline.execute(
            NativeTrainingRunId("phase399-run"),
            NativeTrainerPort { request ->
                captured = request
                Result.failure(IllegalStateException("intentional test stop"))
            }
        )

        assertEquals(NativeTrainingRunStatus.FAILED, result.status)
        val request = captured
        assertNotNull(request)
        assertEquals(1, request!!.generatedExperienceShards.size)
        assertEquals(shard, request.generatedExperienceShards.single())
    }

    private fun verifiedPlan(
        id: String,
        goal: String,
        secretInput: String
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase396-conversation"),
        goal = goal,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId(id + "-reason"),
                capability = reasoning,
                reason = "private reason that must not enter training payload",
                input = secretInput,
                status = PlanStepStatus.EXECUTED,
                boundToolId = ToolId("phase396-reason-tool"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            ),
            SovereignPlanStep(
                index = 2,
                requestId = ActionRequestId(id + "-plan"),
                capability = planning,
                reason = "private planning reason",
                input = secretInput + "-plan",
                status = PlanStepStatus.EXECUTED,
                boundToolId = ToolId("phase396-plan-tool"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase396-test"
    )
}
