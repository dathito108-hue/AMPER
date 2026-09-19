package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.roundToInt

data class ReflexExperiencePartition(
    val trainingShard: ReflexExperienceDatasetShard,
    val holdoutShard: ReflexExperienceDatasetShard,
    val selectedExampleIds: Set<ReflexExperienceExampleId>,
    val holdoutRatio: Double,
    val trainingActionExamples: Int,
    val trainingEscalationExamples: Int,
    val holdoutActionExamples: Int,
    val holdoutEscalationExamples: Int
) {
    init {
        require(trainingShard.manifest.id != holdoutShard.manifest.id)
        val trainingIds = trainingShard.exampleIds.toSet()
        val holdoutIds = holdoutShard.exampleIds.toSet()
        require(trainingIds.intersect(holdoutIds).isEmpty())
        require(trainingIds + holdoutIds == selectedExampleIds)
        require(holdoutRatio in 0.0..1.0)
        require(trainingActionExamples > 0)
        require(trainingEscalationExamples > 0)
        require(holdoutActionExamples > 0)
        require(holdoutEscalationExamples > 0)
    }

    val authorityBearing: Boolean
        get() = false
}

interface ReflexExperiencePartitioner {
    fun partition(
        trainingShardId: NativeDatasetShardId,
        holdoutShardId: NativeDatasetShardId,
        holdoutRatio: Double = 0.20,
        minTrainingPerClass: Int = 2,
        minHoldoutPerClass: Int = 1,
        limit: Int = 256,
        selectedExampleIds: List<ReflexExperienceExampleId>? = null
    ): ReflexExperiencePartition
}

/**
 * Phase431 deterministic stratified train/holdout split for System-1 decision experience.
 *
 * ACTION and ESCALATE_SYSTEM2 are partitioned independently by immutable example identity so both
 * training and holdout retain class coverage. Insertion order never affects membership.
 */
class DeterministicReflexExperiencePartitioner(
    private val store: ReflexExperienceDatasetStore
) : ReflexExperiencePartitioner {
    override fun partition(
        trainingShardId: NativeDatasetShardId,
        holdoutShardId: NativeDatasetShardId,
        holdoutRatio: Double,
        minTrainingPerClass: Int,
        minHoldoutPerClass: Int,
        limit: Int,
        selectedExampleIds: List<ReflexExperienceExampleId>?
    ): ReflexExperiencePartition {
        require(trainingShardId != holdoutShardId)
        require(holdoutRatio > 0.0 && holdoutRatio < 1.0)
        require(minTrainingPerClass > 0)
        require(minHoldoutPerClass > 0)
        require(limit in 1..MemoryBackedReflexExperienceDatasetStore.MAX_SHARD_EXAMPLES)

        val selected = selectedExampleIds?.let { ids ->
            require(ids.isNotEmpty()) {
                "explicit Reflex partition selection cannot be empty"
            }
            require(ids.size <= limit) {
                "explicit Reflex partition selection exceeds limit"
            }
            require(ids.distinct().size == ids.size) {
                "explicit Reflex partition selection contains duplicate example ids"
            }
            ids.map { id ->
                requireNotNull(store.getExample(id)) {
                    "explicit Reflex partition references missing example: " + id.value
                }
            }
        } ?: store.recentExamples(limit)
        val actions = selected.filter {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalations = selected.filter {
            it.targetDisposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2
        }
        val minimumPerClass = minTrainingPerClass + minHoldoutPerClass
        require(actions.size >= minimumPerClass) {
            "insufficient ACTION reflex experience for train/holdout partition"
        }
        require(escalations.size >= minimumPerClass) {
            "insufficient ESCALATE reflex experience for train/holdout partition"
        }

        val actionSplit = splitClass(
            actions,
            holdoutRatio,
            minTrainingPerClass,
            minHoldoutPerClass
        )
        val escalationSplit = splitClass(
            escalations,
            holdoutRatio,
            minTrainingPerClass,
            minHoldoutPerClass
        )
        val trainingIds = (actionSplit.first + escalationSplit.first)
            .sortedBy { it.value }
        val holdoutIds = (actionSplit.second + escalationSplit.second)
            .sortedBy { it.value }
        require(trainingIds.size + holdoutIds.size <=
            MemoryBackedReflexExperienceDatasetStore.MAX_SHARD_EXAMPLES)

        val trainingShard = store.materializeShardFromExamples(
            id = trainingShardId,
            exampleIds = trainingIds
        )
        val holdoutShard = store.materializeShardFromExamples(
            id = holdoutShardId,
            exampleIds = holdoutIds
        )
        return ReflexExperiencePartition(
            trainingShard = trainingShard,
            holdoutShard = holdoutShard,
            selectedExampleIds = (trainingIds + holdoutIds).toSet(),
            holdoutRatio = holdoutIds.size.toDouble() /
                (trainingIds.size + holdoutIds.size).toDouble(),
            trainingActionExamples = actionSplit.first.size,
            trainingEscalationExamples = escalationSplit.first.size,
            holdoutActionExamples = actionSplit.second.size,
            holdoutEscalationExamples = escalationSplit.second.size
        )
    }

    private fun splitClass(
        examples: List<ReflexExperienceTrainingExample>,
        holdoutRatio: Double,
        minTraining: Int,
        minHoldout: Int
    ): Pair<List<ReflexExperienceExampleId>, List<ReflexExperienceExampleId>> {
        val ordered = examples.sortedWith(
            compareBy<ReflexExperienceTrainingExample> { partitionKey(it.id) }
                .thenBy { it.id.value }
        )
        val desiredHoldout = (ordered.size.toDouble() * holdoutRatio)
            .roundToInt()
            .coerceAtLeast(minHoldout)
            .coerceAtMost(ordered.size - minTraining)
        val holdout = ordered.take(desiredHoldout).map { it.id }
        val training = ordered.drop(desiredHoldout).map { it.id }
        require(training.size >= minTraining)
        require(holdout.size >= minHoldout)
        return training to holdout
    }

    private fun partitionKey(id: ReflexExperienceExampleId): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                ("AMPER_REFLEX_HOLDOUT_V1|" + id.value)
                    .toByteArray(StandardCharsets.UTF_8)
            )
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
