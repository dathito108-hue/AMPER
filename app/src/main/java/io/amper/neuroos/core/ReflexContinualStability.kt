package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs

data class ReflexReplayPlan(
    val exampleIds: List<ReflexExperienceExampleId>,
    val actionExamples: Int,
    val escalationExamples: Int,
    val seenActionCapabilities: Set<CapabilityId>,
    val actionDistribution: Map<CapabilityId, Double>,
    val escalationShare: Double
) {
    init {
        require(exampleIds.distinct().size == exampleIds.size)
        require(actionExamples >= 0)
        require(escalationExamples >= 0)
        require(actionExamples + escalationExamples == exampleIds.size)
        require(actionDistribution.values.all { it in 0.0..1.0 })
        require(escalationShare in 0.0..1.0)
    }

    val authorityBearing: Boolean
        get() = false
}

data class ReflexEvidenceDriftReport(
    val freshExamples: Int,
    val novelCapabilities: Set<CapabilityId>,
    val escalationShareDelta: Double,
    val actionCapabilityDistributionDelta: Double,
    val significant: Boolean
) {
    init {
        require(freshExamples > 0)
        require(escalationShareDelta in 0.0..1.0)
        require(actionCapabilityDistributionDelta in 0.0..1.0)
    }

    val authorityBearing: Boolean
        get() = false
}

object ReflexContinualDriftAnalyzer {
    fun analyze(
        fresh: List<ReflexExperienceTrainingExample>,
        replay: ReflexReplayPlan
    ): ReflexEvidenceDriftReport {
        require(fresh.isNotEmpty())
        val freshActions = fresh.filter {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val freshEscalationShare =
            fresh.count {
                it.targetDisposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2
            }.toDouble() / fresh.size.toDouble()
        val freshDistribution = actionDistribution(freshActions)
        val capabilities = freshDistribution.keys + replay.actionDistribution.keys
        val distributionDelta = (
            capabilities.sumOf { capability ->
                abs(
                    (freshDistribution[capability] ?: 0.0) -
                        (replay.actionDistribution[capability] ?: 0.0)
                )
            } / 2.0
            ).coerceIn(0.0, 1.0)
        val novel = freshActions.mapNotNull { it.targetCapability }
            .toSet() - replay.seenActionCapabilities
        val escalationDelta = abs(
            freshEscalationShare - replay.escalationShare
        ).coerceIn(0.0, 1.0)
        return ReflexEvidenceDriftReport(
            freshExamples = fresh.size,
            novelCapabilities = novel,
            escalationShareDelta = escalationDelta,
            actionCapabilityDistributionDelta = distributionDelta,
            significant =
                replay.exampleIds.isEmpty() ||
                    novel.isNotEmpty() ||
                    escalationDelta >= SIGNIFICANT_ESCALATION_SHARE_DELTA ||
                    distributionDelta >= SIGNIFICANT_CAPABILITY_DISTRIBUTION_DELTA
        )
    }

    private fun actionDistribution(
        actions: List<ReflexExperienceTrainingExample>
    ): Map<CapabilityId, Double> {
        if (actions.isEmpty()) return emptyMap()
        val total = actions.size.toDouble()
        return actions
            .mapNotNull { it.targetCapability }
            .groupingBy { it }
            .eachCount()
            .mapValues { (_, count) -> count.toDouble() / total }
    }

    const val SIGNIFICANT_ESCALATION_SHARE_DELTA = 0.20
    const val SIGNIFICANT_CAPABILITY_DISTRIBUTION_DELTA = 0.35
}

/**
 * Phase496-499 bounded anti-forgetting planner for AMPER Reflex continual learning.
 *
 * Replay candidates come only from historical TRAINING shards. Historical held-out examples are
 * reserved exclusively for an independent stability holdout and are never replayed into training.
 * Selection is deterministic and bounded so process restarts do not alter the evidence set.
 */
class ReflexContinualStabilityPlanner(
    private val datasets: ReflexExperienceDatasetStore,
    private val foundation: NativeModelFoundation,
    private val training: NativeTrainingPipeline
) {
    fun replayPlan(
        championCheckpointId: NativeCheckpointId,
        maxExamples: Int = DEFAULT_MAX_REPLAY_EXAMPLES
    ): ReflexReplayPlan {
        require(maxExamples in 1..MemoryBackedReflexExperienceDatasetStore.MAX_SHARD_EXAMPLES)
        val historical = lineageTrainingExamples(championCheckpointId)
        if (historical.isEmpty()) {
            return ReflexReplayPlan(
                exampleIds = emptyList(),
                actionExamples = 0,
                escalationExamples = 0,
                seenActionCapabilities = emptySet(),
                actionDistribution = emptyMap(),
                escalationShare = 0.0
            )
        }
        val selected = balancedSelect(
            checkpointId = championCheckpointId,
            purpose = "replay",
            examples = historical,
            maxExamples = maxExamples,
            preserveActionCapabilities = true
        )
        val selectedActions = selected.filter {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val historicalActions = historical.filter {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        return ReflexReplayPlan(
            exampleIds = selected.map { it.id },
            actionExamples = selectedActions.size,
            escalationExamples = selected.size - selectedActions.size,
            seenActionCapabilities = historicalActions.mapNotNull { it.targetCapability }.toSet(),
            actionDistribution = distribution(historicalActions),
            escalationShare = historical.count {
                it.targetDisposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2
            }.toDouble() / historical.size.toDouble()
        )
    }

    fun materializeStabilityHoldout(
        championCheckpointId: NativeCheckpointId,
        shardId: NativeDatasetShardId,
        maxExamples: Int = DEFAULT_MAX_STABILITY_EXAMPLES
    ): ReflexExperienceDatasetShard {
        require(maxExamples in 2..MemoryBackedReflexExperienceDatasetStore.MAX_SHARD_EXAMPLES)
        datasets.getShard(shardId)?.let { return it }
        val historical = lineageHoldoutExamples(championCheckpointId)
        val actions = historical.count {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalations = historical.size - actions
        require(actions > 0 && escalations > 0) {
            "Reflex stability holdout lacks both decision classes"
        }
        val selected = balancedSelect(
            checkpointId = championCheckpointId,
            purpose = "stability",
            examples = historical,
            maxExamples = maxExamples,
            preserveActionCapabilities = true
        )
        require(
            selected.any {
                it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
            } &&
                selected.any {
                    it.targetDisposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2
                }
        ) {
            "bounded Reflex stability holdout lost class coverage"
        }
        return datasets.materializeShardFromExamples(
            id = shardId,
            exampleIds = selected.map { it.id }
        )
    }

    private fun lineageTrainingExamples(
        checkpointId: NativeCheckpointId
    ): List<ReflexExperienceTrainingExample> {
        val ids = linkedSetOf<ReflexExperienceExampleId>()
        walkLineage(checkpointId) { checkpoint ->
            checkpoint.datasetShardIds.forEach { shardId ->
                datasets.getShard(shardId)?.exampleIds?.forEach(ids::add)
            }
        }
        return ids.mapNotNull(datasets::getExample)
    }

    private fun lineageHoldoutExamples(
        checkpointId: NativeCheckpointId
    ): List<ReflexExperienceTrainingExample> {
        val ids = linkedSetOf<ReflexExperienceExampleId>()
        walkLineage(checkpointId) { checkpoint ->
            training.getEvaluation(checkpoint.id)
                ?.evaluation
                ?.reflexDecision
                ?.holdoutShardId
                ?.let(datasets::getShard)
                ?.exampleIds
                ?.forEach(ids::add)
        }
        return ids.mapNotNull(datasets::getExample)
    }

    private fun walkLineage(
        checkpointId: NativeCheckpointId,
        visit: (NativeCheckpointLineage) -> Unit
    ) {
        val visited = linkedSetOf<NativeCheckpointId>()
        var cursor: NativeCheckpointId? = checkpointId
        var depth = 0
        while (
            cursor != null &&
            depth < MAX_LINEAGE_DEPTH &&
            visited.add(cursor)
        ) {
            val checkpoint = requireNotNull(foundation.getCheckpoint(cursor)) {
                "Reflex lineage checkpoint is unavailable: " + cursor.value
            }
            visit(checkpoint)
            cursor = checkpoint.parentCheckpointId
            depth += 1
        }
    }

    private fun balancedSelect(
        checkpointId: NativeCheckpointId,
        purpose: String,
        examples: List<ReflexExperienceTrainingExample>,
        maxExamples: Int,
        preserveActionCapabilities: Boolean
    ): List<ReflexExperienceTrainingExample> {
        if (examples.size <= maxExamples) {
            return examples.sortedBy { stableKey(checkpointId, purpose, it.id) }
        }

        val actions = examples.filter {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalations = examples.filter {
            it.targetDisposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2
        }
        val actionBudget = maxExamples / 2
        val escalationBudget = maxExamples - actionBudget
        val chosen = linkedMapOf<ReflexExperienceExampleId, ReflexExperienceTrainingExample>()

        if (preserveActionCapabilities && actions.isNotEmpty()) {
            val groups = actions
                .groupBy { requireNotNull(it.targetCapability) }
                .toSortedMap(compareBy { it.value })
                .mapValues { (_, group) ->
                    group.sortedBy { stableKey(checkpointId, purpose, it.id) }.toMutableList()
                }
            var remaining = actionBudget
            var progressed = true
            while (remaining > 0 && progressed) {
                progressed = false
                groups.forEach { (_, group) ->
                    if (remaining > 0 && group.isNotEmpty()) {
                        val next = group.removeAt(0)
                        chosen[next.id] = next
                        remaining -= 1
                        progressed = true
                    }
                }
            }
        } else {
            actions
                .sortedBy { stableKey(checkpointId, purpose, it.id) }
                .take(actionBudget)
                .forEach { chosen[it.id] = it }
        }

        escalations
            .sortedBy { stableKey(checkpointId, purpose, it.id) }
            .take(escalationBudget)
            .forEach { chosen[it.id] = it }

        if (chosen.size < maxExamples) {
            examples
                .filterNot { it.id in chosen }
                .sortedBy { stableKey(checkpointId, purpose, it.id) }
                .take(maxExamples - chosen.size)
                .forEach { chosen[it.id] = it }
        }
        return chosen.values.toList()
    }

    private fun distribution(
        actions: List<ReflexExperienceTrainingExample>
    ): Map<CapabilityId, Double> {
        if (actions.isEmpty()) return emptyMap()
        val total = actions.size.toDouble()
        return actions
            .mapNotNull { it.targetCapability }
            .groupingBy { it }
            .eachCount()
            .mapValues { (_, count) -> count.toDouble() / total }
    }

    private fun stableKey(
        checkpointId: NativeCheckpointId,
        purpose: String,
        exampleId: ReflexExperienceExampleId
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                listOf(
                    "AMPER_REFLEX_STABILITY_V1",
                    purpose,
                    checkpointId.value,
                    exampleId.value
                ).joinToString("|").toByteArray(StandardCharsets.UTF_8)
            )
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_MAX_REPLAY_EXAMPLES = 96
        const val DEFAULT_MAX_STABILITY_EXAMPLES = 96
        const val MAX_LINEAGE_DEPTH = 8
    }
}
