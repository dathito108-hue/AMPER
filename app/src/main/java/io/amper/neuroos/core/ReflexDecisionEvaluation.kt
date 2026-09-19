package io.amper.neuroos.core

import kotlin.math.abs

data class ReflexDecisionHeldoutMetrics(
    val holdoutShardId: NativeDatasetShardId,
    val holdoutPayloadSha256: String,
    val exactDecisionAccuracy: Double,
    val actionPrecision: Double,
    val escalationRecall: Double,
    val capabilityAccuracy: Double,
    val calibrationMeanAbsoluteError: Double,
    val totalSamples: Int,
    val actionSamples: Int,
    val escalationSamples: Int
) {
    init {
        require(holdoutPayloadSha256.matches(Regex("[0-9a-f]{64}")))
        require(
            listOf(
                exactDecisionAccuracy,
                actionPrecision,
                escalationRecall,
                capabilityAccuracy,
                calibrationMeanAbsoluteError
            ).all { it in 0.0..1.0 }
        )
        require(totalSamples > 0)
        require(actionSamples > 0)
        require(escalationSamples > 0)
        require(totalSamples == actionSamples + escalationSamples)
    }

    val authorityBearing: Boolean
        get() = false
}

data class ReflexDecisionPrediction(
    val exampleId: ReflexExperienceExampleId,
    val disposition: ReflexDecisionDisposition,
    val capability: CapabilityId? = null,
    val confidence: Double,
    val uncertainty: Double
) {
    init {
        require(confidence in 0.0..1.0)
        require(uncertainty in 0.0..1.0)
        when (disposition) {
            ReflexDecisionDisposition.ESCALATE_SYSTEM2 -> require(capability == null)
            ReflexDecisionDisposition.PROPOSE_ACTION -> require(capability != null)
        }
    }
}

data class ReflexDecisionEvaluationRequest(
    val checkpoint: NativeCheckpointLineage,
    val holdoutShard: ReflexExperienceDatasetShard,
    val examples: List<ReflexExperienceTrainingExample>
) {
    init {
        require(examples.isNotEmpty())
        require(examples.map { it.id } == holdoutShard.exampleIds)
        require(holdoutShard.manifest.id !in checkpoint.datasetShardIds)
    }

    val authorityBearing: Boolean
        get() = false
}

fun interface ReflexDecisionEvaluatorPort {
    fun evaluate(
        request: ReflexDecisionEvaluationRequest
    ): Result<List<ReflexDecisionPrediction>>
}

object ReflexDecisionHeldoutScorer {
    fun score(
        shard: ReflexExperienceDatasetShard,
        examples: List<ReflexExperienceTrainingExample>,
        predictions: List<ReflexDecisionPrediction>
    ): ReflexDecisionHeldoutMetrics {
        require(examples.map { it.id } == shard.exampleIds) {
            "reflex held-out examples do not match shard identity"
        }
        require(predictions.size == examples.size) {
            "reflex evaluator prediction count does not match holdout examples"
        }
        require(predictions.map { it.exampleId }.distinct().size == predictions.size) {
            "reflex evaluator returned duplicate example predictions"
        }
        val predictionById = predictions.associateBy { it.exampleId }
        require(predictionById.keys == examples.map { it.id }.toSet()) {
            "reflex evaluator predictions do not exactly cover holdout examples"
        }

        var exactCorrect = 0
        var predictedActions = 0
        var correctPredictedActions = 0
        var correctEscalations = 0
        var correctActionCapabilities = 0
        var calibrationErrorTotal = 0.0
        val actionSamples = examples.count {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalationSamples = examples.size - actionSamples
        require(actionSamples > 0 && escalationSamples > 0)

        examples.forEach { example ->
            val prediction = requireNotNull(predictionById[example.id])
            val exact = when (example.targetDisposition) {
                ReflexDecisionDisposition.ESCALATE_SYSTEM2 ->
                    prediction.disposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2

                ReflexDecisionDisposition.PROPOSE_ACTION ->
                    prediction.disposition == ReflexDecisionDisposition.PROPOSE_ACTION &&
                        prediction.capability == example.targetCapability
            }
            if (exact) exactCorrect += 1
            if (prediction.disposition == ReflexDecisionDisposition.PROPOSE_ACTION) {
                predictedActions += 1
                if (
                    example.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION &&
                    prediction.capability == example.targetCapability
                ) {
                    correctPredictedActions += 1
                }
            }
            if (
                example.targetDisposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2 &&
                prediction.disposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2
            ) {
                correctEscalations += 1
            }
            if (
                example.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION &&
                prediction.disposition == ReflexDecisionDisposition.PROPOSE_ACTION &&
                prediction.capability == example.targetCapability
            ) {
                correctActionCapabilities += 1
            }
            calibrationErrorTotal += abs(
                prediction.confidence - if (exact) 1.0 else 0.0
            )
        }

        return ReflexDecisionHeldoutMetrics(
            holdoutShardId = shard.manifest.id,
            holdoutPayloadSha256 = shard.manifest.sha256,
            exactDecisionAccuracy = exactCorrect.toDouble() / examples.size.toDouble(),
            actionPrecision = if (predictedActions == 0) {
                0.0
            } else {
                correctPredictedActions.toDouble() / predictedActions.toDouble()
            },
            escalationRecall =
                correctEscalations.toDouble() / escalationSamples.toDouble(),
            capabilityAccuracy =
                correctActionCapabilities.toDouble() / actionSamples.toDouble(),
            calibrationMeanAbsoluteError =
                calibrationErrorTotal / examples.size.toDouble(),
            totalSamples = examples.size,
            actionSamples = actionSamples,
            escalationSamples = escalationSamples
        )
    }
}

interface ReflexDecisionEvaluationCoordinator {
    fun score(
        checkpointId: NativeCheckpointId,
        holdoutShardId: NativeDatasetShardId,
        evaluator: ReflexDecisionEvaluatorPort
    ): NativeCheckpointEvaluation =
        error("ephemeral Reflex evaluation is unavailable")

    fun evaluate(
        checkpointId: NativeCheckpointId,
        holdoutShardId: NativeDatasetShardId,
        evaluator: ReflexDecisionEvaluatorPort
    ): NativeCheckpointEvaluationRecord
}

/**
 * Held-out evaluation bridge for a Reflex-only AMPER checkpoint.
 *
 * The evaluator receives only the immutable checkpoint lineage and privacy-preserving hashed decision
 * examples. The coordinator computes metrics itself and submits them through the canonical native
 * evaluation/admission path. It has no model-promotion or tool authority.
 */
class CanonicalReflexDecisionEvaluationCoordinator(
    private val datasets: ReflexExperienceDatasetStore,
    private val foundation: NativeModelFoundation,
    private val training: NativeTrainingPipeline
) : ReflexDecisionEvaluationCoordinator {
    override fun score(
        checkpointId: NativeCheckpointId,
        holdoutShardId: NativeDatasetShardId,
        evaluator: ReflexDecisionEvaluatorPort
    ): NativeCheckpointEvaluation {
        val checkpoint = requireNotNull(foundation.getCheckpoint(checkpointId)) {
            "reflex evaluation checkpoint is unavailable"
        }
        val contract = requireNotNull(foundation.getContract(checkpoint.contractId)) {
            "reflex evaluation contract is unavailable"
        }
        require(contract.capabilities == setOf(TitanCapabilities.REFLEX_DECISION)) {
            "reflex evaluation coordinator requires a reflex-decision-only checkpoint"
        }
        require(holdoutShardId !in checkpoint.datasetShardIds) {
            "reflex holdout shard is part of checkpoint training lineage"
        }
        val holdout = requireNotNull(datasets.getShard(holdoutShardId)) {
            "reflex holdout shard is unavailable"
        }
        val trainingShards = checkpoint.datasetShardIds.map { id ->
            requireNotNull(datasets.getShard(id)) {
                "reflex checkpoint training shard is unavailable: " + id.value
            }
        }
        val trainingIds = trainingShards.flatMap { it.exampleIds }.toSet()
        require(trainingIds.intersect(holdout.exampleIds.toSet()).isEmpty()) {
            "reflex holdout overlaps checkpoint training examples"
        }
        val examples = holdout.exampleIds.map { id ->
            requireNotNull(datasets.getExample(id)) {
                "reflex holdout references a missing example: " + id.value
            }
        }
        val request = ReflexDecisionEvaluationRequest(
            checkpoint = checkpoint,
            holdoutShard = holdout,
            examples = examples
        )
        val predictions = evaluator.evaluate(request).getOrThrow()
        val metrics = ReflexDecisionHeldoutScorer.score(
            shard = holdout,
            examples = examples,
            predictions = predictions
        )
        return NativeCheckpointEvaluation(
            planningProtocolPassRate = 0.0,
            toolContractPassRate = 0.0,
            regressionPassRate = 0.0,
            heldoutGeneralizationPassRate = 0.0,
            planningSamples = 0,
            toolContractSamples = 0,
            regressionSamples = 0,
            heldoutGeneralizationSamples = 0,
            reflexDecision = metrics
        )
    }

    override fun evaluate(
        checkpointId: NativeCheckpointId,
        holdoutShardId: NativeDatasetShardId,
        evaluator: ReflexDecisionEvaluatorPort
    ): NativeCheckpointEvaluationRecord =
        training.recordEvaluation(
            checkpointId = checkpointId,
            evaluation = score(
                checkpointId = checkpointId,
                holdoutShardId = holdoutShardId,
                evaluator = evaluator
            )
        )

}
