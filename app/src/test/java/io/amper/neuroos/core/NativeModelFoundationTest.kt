package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeModelFoundationTest {
    private val reasoning = TitanCapabilities.REASONING
    private val planning = TitanCapabilities.PLANNING

    @Test
    fun contractDigestIsStableAcrossCapabilitySetOrder() {
        val a = contract(setOf(reasoning, planning))
        val b = contract(linkedSetOf(planning, reasoning))

        assertEquals(a.canonicalDigest, b.canonicalDigest)
        assertTrue(a.ownedByAmper)
        assertFalse(a.authorityBearing)
    }

    @Test
    fun multiCapabilityCurriculumRoundTripsWithoutDelimiterAmbiguity() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val curriculum = curriculum()

        foundation.putCurriculum(curriculum)
        val restored = foundation.getCurriculum(curriculum.id)

        assertEquals(curriculum, restored)
        assertEquals(
            setOf(reasoning, planning),
            restored?.stages?.last()?.capabilities
        )
    }

    @Test
    fun fullyProvenancedCheckpointPassesFoundationAdmission() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory, clock = { 10_000L })
        val fixture = publishAdmissibleFoundation(foundation)

        val admission = foundation.admit(
            checkpointId = fixture.checkpoint.id,
            recipe = fixture.recipe,
            evaluation = passingEvaluation()
        )

        assertEquals(NativeCheckpointAdmissionStatus.ADMITTED, admission.status)
        assertTrue(admission.admitted)
        assertFalse(admission.authorityBearing)
        assertEquals(10_000L, admission.evaluatedAtEpochMs)
    }

    @Test
    fun unknownDatasetRightsFailClosedEvenWithPerfectEvaluation() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val contract = contract()
        val curriculum = curriculum()
        val recipe = recipe(curriculum)
        val shard = NativeDatasetShardManifest(
            id = NativeDatasetShardId("unknown-rights"),
            sha256 = "d".repeat(64),
            sourceLabel = "unverified external source",
            rights = NativeDatasetRights.UNKNOWN,
            exampleCount = 128,
            byteCount = 4096,
            targetCapabilities = setOf(reasoning)
        )
        foundation.putContract(contract)
        foundation.putCurriculum(curriculum)
        foundation.putDatasetShard(shard)
        val checkpoint = checkpoint(
            foundation = foundation,
            contract = contract,
            curriculum = curriculum,
            recipe = recipe,
            shards = listOf(shard),
            id = "unknown-rights-checkpoint"
        )
        foundation.putCheckpoint(checkpoint)

        val admission = foundation.admit(
            checkpoint.id,
            recipe,
            passingEvaluation()
        )

        assertEquals(NativeCheckpointAdmissionStatus.REJECTED, admission.status)
        assertTrue(admission.reasons.any { it.contains("unknown training rights") })
    }

    @Test
    fun lowHeldoutGeneralizationAndRecipeMismatchAreRejected() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val fixture = publishAdmissibleFoundation(foundation)
        val wrongRecipe = fixture.recipe.copy(learningRate = fixture.recipe.learningRate / 2.0)
        val weakEvaluation = passingEvaluation().copy(
            heldoutGeneralizationPassRate = 0.50
        )

        val admission = foundation.admit(
            fixture.checkpoint.id,
            wrongRecipe,
            weakEvaluation
        )

        assertEquals(NativeCheckpointAdmissionStatus.REJECTED, admission.status)
        assertTrue(admission.reasons.any { it.contains("training recipe digest mismatch") })
        assertTrue(admission.reasons.any { it.contains("held-out generalization") })
    }

    @Test
    fun checkpointLineageIsImmutableForSameCheckpointId() {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        val fixture = publishAdmissibleFoundation(foundation)
        val changed = fixture.checkpoint.copy(
            weightArtifactSha256 = "e".repeat(64)
        )

        val result = runCatching { foundation.putCheckpoint(changed) }

        assertTrue(result.isFailure)
        assertEquals(
            fixture.checkpoint,
            foundation.getCheckpoint(fixture.checkpoint.id)
        )
    }

    @Test
    fun nativeFoundationRecordsStayOutOfGenericContext() {
        val runtime = AmperRuntime.reference()
        val contract = contract()
        runtime.nativeModelFoundation.putContract(contract)

        val snapshot = runtime.context.capture(
            query = "AMPER native model",
            memoryLimit = 16,
            worldLimit = 0,
            workspaceLimit = 0
        )

        assertFalse(snapshot.memories.any {
            it.kind == MemoryBackedNativeModelFoundation.CONTRACT_KIND ||
                it.kind == MemoryBackedNativeModelFoundation.DATASET_KIND ||
                it.kind == MemoryBackedNativeModelFoundation.CURRICULUM_KIND ||
                it.kind == MemoryBackedNativeModelFoundation.CHECKPOINT_KIND ||
                it.kind == MemoryBackedNativeModelFoundation.ADMISSION_KIND
        })
    }

    private data class Fixture(
        val checkpoint: NativeCheckpointLineage,
        val recipe: NativeTrainingRecipe
    )

    private fun publishAdmissibleFoundation(
        foundation: NativeModelFoundation
    ): Fixture {
        val contract = contract()
        val curriculum = curriculum()
        val recipe = recipe(curriculum)
        val shards = listOf(
            NativeDatasetShardManifest(
                id = NativeDatasetShardId("user-code"),
                sha256 = "a".repeat(64),
                sourceLabel = "user-owned curated code",
                rights = NativeDatasetRights.USER_OWNED,
                exampleCount = 512,
                byteCount = 32_768,
                targetCapabilities = setOf(reasoning, planning),
                createdAtEpochMs = 1_000L
            ),
            NativeDatasetShardManifest(
                id = NativeDatasetShardId("internal-synthetic"),
                sha256 = "b".repeat(64),
                sourceLabel = "AMPER synthetic curriculum",
                rights = NativeDatasetRights.GENERATED_INTERNAL,
                exampleCount = 512,
                byteCount = 32_768,
                targetCapabilities = setOf(planning),
                createdAtEpochMs = 1_001L
            )
        )
        foundation.putContract(contract)
        foundation.putCurriculum(curriculum)
        shards.forEach(foundation::putDatasetShard)

        val checkpoint = checkpoint(
            foundation = foundation,
            contract = contract,
            curriculum = curriculum,
            recipe = recipe,
            shards = shards,
            id = "amper-native-checkpoint-v1"
        )
        foundation.putCheckpoint(checkpoint)
        return Fixture(checkpoint, recipe)
    }

    private fun contract(
        capabilities: Set<CapabilityId> = setOf(reasoning, planning)
    ): AmperNativeModelContract = AmperNativeModelContract(
        id = NativeModelContractId("amper-native-core-v1"),
        familyVersion = 1,
        parameterCount = 120_000_000L,
        layerCount = 24,
        hiddenSize = 1024,
        maxContextTokens = 8192,
        capabilities = capabilities,
        ownedByAmper = true
    )

    private fun curriculum(): NativeCurriculumManifest = NativeCurriculumManifest(
        id = NativeCurriculumId("native-curriculum-v1"),
        stages = listOf(
            NativeCurriculumStage(
                index = 1,
                name = "reasoning-foundation",
                capabilities = setOf(reasoning),
                minExamples = 256,
                weight = 0.45
            ),
            NativeCurriculumStage(
                index = 2,
                name = "planning-transfer",
                capabilities = setOf(reasoning, planning),
                minExamples = 256,
                weight = 0.55
            )
        )
    )

    private fun recipe(curriculum: NativeCurriculumManifest): NativeTrainingRecipe =
        NativeTrainingRecipe(
            optimizer = "adamw",
            precision = "bf16",
            maxSequenceTokens = 4096,
            learningRate = 0.0002,
            curriculumDigest = curriculum.canonicalDigest
        )

    private fun checkpoint(
        foundation: NativeModelFoundation,
        contract: AmperNativeModelContract,
        curriculum: NativeCurriculumManifest,
        recipe: NativeTrainingRecipe,
        shards: List<NativeDatasetShardManifest>,
        id: String
    ): NativeCheckpointLineage {
        val shardIds = shards.map { it.id }
        return NativeCheckpointLineage(
            id = NativeCheckpointId(id),
            parentCheckpointId = null,
            contractId = contract.id,
            contractDigest = contract.canonicalDigest,
            datasetShardIds = shardIds,
            datasetSnapshotDigest = foundation.datasetSnapshotDigest(shardIds),
            curriculumId = curriculum.id,
            curriculumDigest = curriculum.canonicalDigest,
            recipeDigest = recipe.canonicalDigest,
            weightArtifactSha256 = "c".repeat(64),
            createdAtEpochMs = 2_000L
        )
    }

    private fun passingEvaluation(): NativeCheckpointEvaluation =
        NativeCheckpointEvaluation(
            planningProtocolPassRate = 1.0,
            toolContractPassRate = 1.0,
            regressionPassRate = 1.0,
            heldoutGeneralizationPassRate = 0.95,
            planningSamples = 32,
            toolContractSamples = 32,
            regressionSamples = 32,
            heldoutGeneralizationSamples = 32
        )
}
