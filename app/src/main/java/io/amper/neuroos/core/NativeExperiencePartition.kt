package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.roundToInt

data class NativeExperiencePartition(
    val trainingShard: NativeExperienceDatasetShard,
    val holdoutShard: NativeExperienceDatasetShard,
    val selectedExampleIds: Set<NativeExperienceExampleId>,
    val holdoutRatio: Double
) {
    init {
        require(trainingShard.manifest.id != holdoutShard.manifest.id)
        require(trainingShard.exampleIds.toSet().intersect(holdoutShard.exampleIds.toSet()).isEmpty())
        require(
            trainingShard.exampleIds.toSet() + holdoutShard.exampleIds.toSet() ==
                selectedExampleIds
        )
        require(holdoutRatio in 0.0..1.0)
    }

    val authorityBearing: Boolean
        get() = false
}

interface NativeExperiencePartitioner {
    fun partition(
        trainingShardId: NativeDatasetShardId,
        holdoutShardId: NativeDatasetShardId,
        holdoutRatio: Double = 0.20,
        minTrainingExamples: Int = 2,
        minHoldoutExamples: Int = 1,
        limit: Int = 256
    ): NativeExperiencePartition
}

/**
 * Phase416-420 deterministic train/holdout partition over verified AMPER experience.
 *
 * Membership is derived from immutable example identity, never from insertion order. Training and
 * holdout shards are materialized through the canonical NativeExperienceDatasetStore so the existing
 * dataset provenance, rights and SHA-256 invariants remain intact.
 */
class DeterministicNativeExperiencePartitioner(
    private val store: NativeExperienceDatasetStore
) : NativeExperiencePartitioner {
    override fun partition(
        trainingShardId: NativeDatasetShardId,
        holdoutShardId: NativeDatasetShardId,
        holdoutRatio: Double,
        minTrainingExamples: Int,
        minHoldoutExamples: Int,
        limit: Int
    ): NativeExperiencePartition {
        require(trainingShardId != holdoutShardId)
        require(holdoutRatio > 0.0 && holdoutRatio < 1.0)
        require(minTrainingExamples > 0)
        require(minHoldoutExamples > 0)
        require(limit in (minTrainingExamples + minHoldoutExamples)..
            MemoryBackedNativeExperienceDatasetStore.MAX_SHARD_EXAMPLES)

        val examples = store.recentExamples(limit)
        require(examples.size >= minTrainingExamples + minHoldoutExamples) {
            "insufficient verified experience for disjoint train/holdout partition"
        }

        val ordered = examples.sortedWith(
            compareBy<NativeExperienceTrainingExample> { partitionKey(it.id) }
                .thenBy { it.id.value }
        )
        val desiredHoldout = (ordered.size.toDouble() * holdoutRatio)
            .roundToInt()
            .coerceAtLeast(minHoldoutExamples)
            .coerceAtMost(ordered.size - minTrainingExamples)

        val holdoutIds = ordered.take(desiredHoldout).map { it.id }
        val trainingIds = ordered.drop(desiredHoldout).map { it.id }
        require(trainingIds.size >= minTrainingExamples)
        require(holdoutIds.size >= minHoldoutExamples)

        val trainingShard = store.materializeShardFromExamples(
            id = trainingShardId,
            exampleIds = trainingIds
        )
        val holdoutShard = store.materializeShardFromExamples(
            id = holdoutShardId,
            exampleIds = holdoutIds
        )
        return NativeExperiencePartition(
            trainingShard = trainingShard,
            holdoutShard = holdoutShard,
            selectedExampleIds = ordered.map { it.id }.toSet(),
            holdoutRatio = holdoutIds.size.toDouble() / ordered.size.toDouble()
        )
    }

    private fun partitionKey(id: NativeExperienceExampleId): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                ("AMPER_NATIVE_HOLDOUT_V1|" + id.value)
                    .toByteArray(StandardCharsets.UTF_8)
            )
            .joinToString("") { "%02x".format(it) }
}
