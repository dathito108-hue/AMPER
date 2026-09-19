package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeExperienceTrainingCoordinatorTest {
    private val reasoning = CapabilityId("reasoning")
    private val planning = CapabilityId("planning")

    @Test
    fun verifiedExperienceBundleCompilesIntoPreparedExistingPipelineRun() {
        val fixture = fixture()
        val spec = fixture.spec("phase406")

        val prepared = fixture.coordinator.prepare(spec)

        assertEquals(NativeTrainingRunStatus.PREPARED, prepared.run.status)
        assertEquals(prepared.manifest.id, prepared.run.manifestId)
        assertEquals(prepared.manifest.canonicalDigest, prepared.run.manifestDigest)
        assertEquals(prepared.bundle.shard.manifest.id, prepared.manifest.datasetShardIds.single())
        assertEquals(prepared.bundle.curriculum.id, prepared.manifest.curriculumId)
        assertEquals(prepared.bundle.datasetSnapshotDigest, prepared.manifest.datasetSnapshotDigest)
        assertNull(fixture.foundation.getCheckpoint(spec.outputCheckpointId))
        assertTrue(prepared.authorityBearing.not())
    }

    @Test
    fun repeatedPreparationIsRestartSafeAndDoesNotCreateTrainerOutput() {
        val fixture = fixture()
        val spec = fixture.spec("phase408")

        val first = fixture.coordinator.prepare(spec)
        val second = fixture.coordinator.prepare(spec)

        assertEquals(first, second)
        assertEquals(NativeTrainingRunStatus.PREPARED, second.run.status)
        assertNull(second.run.trainingBackendId)
        assertNull(second.run.weightArtifactSha256)
        assertNull(fixture.foundation.getCheckpoint(spec.outputCheckpointId))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTeacherRightsFailClosedBeforePreparedRun() {
        val fixture = fixture()
        val unknown = NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId("phase410-unknown"),
            modelId = ModelId("teacher-unknown"),
            artifactSha256 = "b".repeat(64),
            sourceLabel = "unknown teacher",
            rights = NativeTeacherRights.UNKNOWN,
            capabilities = setOf(reasoning, planning),
            local = true,
            createdAtEpochMs = 10L
        )
        fixture.training.putTeacher(unknown)
        val spec = fixture.spec("phase410").copy(
            teacherSnapshotIds = listOf(unknown.id)
        )

        fixture.coordinator.prepare(spec)
    }

    private fun fixture(): Fixture {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val datasets = MemoryBackedNativeExperienceDatasetStore(memory, foundation)
        val curriculum = EvidenceNativeExperienceCurriculumPlanner(datasets, foundation)
        val training = MemoryBackedNativeTrainingPipeline(
            memory = memory,
            foundation = foundation,
            experienceDatasets = datasets,
            clock = { 2_000L }
        )
        val contract = AmperNativeModelContract(
            id = NativeModelContractId("phase406-contract"),
            familyVersion = 1,
            parameterCount = 8_000_000L,
            layerCount = 8,
            hiddenSize = 512,
            maxContextTokens = 4_096,
            capabilities = setOf(reasoning, planning),
            ownedByAmper = true
        )
        foundation.putContract(contract)
        val teacher = NativeTeacherSnapshot(
            id = NativeTeacherSnapshotId("phase406-teacher"),
            modelId = ModelId("teacher-local"),
            artifactSha256 = "a".repeat(64),
            sourceLabel = "eligible local teacher",
            rights = NativeTeacherRights.USER_OWNED,
            capabilities = setOf(reasoning, planning),
            local = true,
            createdAtEpochMs = 10L
        )
        training.putTeacher(teacher)

        repeat(3) { index ->
            datasets.observeVerified(
                plan = plan(
                    id = "phase406-example-" + index,
                    capabilities = if (index == 2) {
                        listOf(reasoning)
                    } else {
                        listOf(reasoning, planning)
                    }
                ),
                verificationConfidence = 0.95,
                observedAtEpochMs = 100L + index
            )
        }

        return Fixture(
            foundation = foundation,
            training = training,
            coordinator = CanonicalNativeExperienceTrainingCoordinator(
                datasets = datasets,
                curriculum = curriculum,
                foundation = foundation,
                training = training,
                clock = { 2_000L }
            ),
            teacher = teacher,
            contract = contract
        )
    }

    private fun plan(
        id: String,
        capabilities: List<CapabilityId>
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase406-conversation"),
        goal = "verified training preparation " + id,
        steps = capabilities.mapIndexed { index, capability ->
            SovereignPlanStep(
                index = index + 1,
                requestId = ActionRequestId(id + "-request-" + index),
                capability = capability,
                reason = "verified experience",
                input = "private",
                status = PlanStepStatus.EXECUTED,
                boundToolId = ToolId("phase406-tool-" + index),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        },
        planningBackendId = "phase406-test"
    )

    private data class Fixture(
        val foundation: NativeModelFoundation,
        val training: NativeTrainingPipeline,
        val coordinator: NativeExperienceTrainingCoordinator,
        val teacher: NativeTeacherSnapshot,
        val contract: AmperNativeModelContract
    ) {
        fun spec(prefix: String): NativeExperienceTrainingSpec =
            NativeExperienceTrainingSpec(
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
    }
}
