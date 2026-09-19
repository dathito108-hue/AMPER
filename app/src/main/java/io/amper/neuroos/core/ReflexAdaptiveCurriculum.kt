package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ReflexAdaptiveCurriculum(
    val capabilityWeights: Map<CapabilityId, Double>,
    val escalationWeight: Double,
    val weakCapabilities: Set<CapabilityId>,
    val actionErrorRate: Double,
    val escalationErrorRate: Double,
    val meanUncertainty: Double,
    val difficulty: Double,
    val learningRate: Double
) {
    init {
        require(capabilityWeights.values.all { it in MIN_WEIGHT..MAX_WEIGHT })
        require(escalationWeight in MIN_WEIGHT..MAX_WEIGHT)
        require(weakCapabilities.all { it in capabilityWeights })
        require(actionErrorRate in 0.0..1.0)
        require(escalationErrorRate in 0.0..1.0)
        require(meanUncertainty in 0.0..1.0)
        require(difficulty in 0.0..1.0)
        require(learningRate in MIN_LEARNING_RATE..MAX_LEARNING_RATE)
    }

    fun weightFor(example: ReflexExperienceTrainingExample): Double =
        when (example.targetDisposition) {
            ReflexDecisionDisposition.PROPOSE_ACTION ->
                capabilityWeights[requireNotNull(example.targetCapability)] ?: MIN_WEIGHT

            ReflexDecisionDisposition.ESCALATE_SYSTEM2 -> escalationWeight
        }

    val canonicalDigest: String
        get() {
            val material = buildString {
                append("AMPER_REFLEX_ADAPTIVE_CURRICULUM_V1|")
                capabilityWeights.entries
                    .sortedBy { it.key.value }
                    .forEach { (capability, weight) ->
                        append(capability.value)
                        append('=')
                        append(java.lang.String.format(java.util.Locale.ROOT, "%.8f", weight))
                        append(';')
                    }
                append("|esc=")
                append(java.lang.String.format(java.util.Locale.ROOT, "%.8f", escalationWeight))
                append("|action_error=")
                append(java.lang.String.format(java.util.Locale.ROOT, "%.8f", actionErrorRate))
                append("|esc_error=")
                append(java.lang.String.format(java.util.Locale.ROOT, "%.8f", escalationErrorRate))
                append("|uncertainty=")
                append(java.lang.String.format(java.util.Locale.ROOT, "%.8f", meanUncertainty))
                append("|difficulty=")
                append(java.lang.String.format(java.util.Locale.ROOT, "%.8f", difficulty))
                append("|lr=")
                append(java.lang.String.format(java.util.Locale.ROOT, "%.8f", learningRate))
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(material.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MIN_WEIGHT = 1.0
        const val MAX_WEIGHT = 2.5
        const val MIN_LEARNING_RATE = 0.018
        const val MAX_LEARNING_RATE = 0.040
    }
}

/**
 * Phase506-508 deterministic curriculum planner.
 *
 * It measures the active champion only on already-governed historical training evidence. This is a
 * scheduling signal, not a validation metric: held-out admission and anti-forgetting gates remain
 * independent downstream checks. Weak action capabilities and weak escalation behavior receive
 * higher selection priority, while the learning rate changes only inside a conservative mobile-safe
 * band.
 */
object ReflexAdaptiveCurriculumPlanner {
    fun plan(
        champion: NativeReflexDecisionPort,
        historicalTraining: List<ReflexExperienceTrainingExample>,
        baseLearningRate: Double = 0.03
    ): ReflexAdaptiveCurriculum {
        require(baseLearningRate in ReflexAdaptiveCurriculum.MIN_LEARNING_RATE..
            ReflexAdaptiveCurriculum.MAX_LEARNING_RATE)

        if (historicalTraining.isEmpty()) {
            return ReflexAdaptiveCurriculum(
                capabilityWeights = emptyMap(),
                escalationWeight = 1.0,
                weakCapabilities = emptySet(),
                actionErrorRate = 0.0,
                escalationErrorRate = 0.0,
                meanUncertainty = 0.0,
                difficulty = 0.0,
                learningRate = baseLearningRate
            )
        }

        val scored = historicalTraining.map { example ->
            val prediction = champion.predict(
                NativeReflexDecisionInput(
                    featureHashes = example.featureHashes,
                    availableCapabilities = example.availableCapabilities
                )
            ).getOrNull()
            val exact = prediction != null && when (example.targetDisposition) {
                ReflexDecisionDisposition.ESCALATE_SYSTEM2 ->
                    prediction.disposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2

                ReflexDecisionDisposition.PROPOSE_ACTION ->
                    prediction.disposition == ReflexDecisionDisposition.PROPOSE_ACTION &&
                        prediction.capability == example.targetCapability
            }
            CurriculumScore(
                example = example,
                exact = exact,
                uncertainty = prediction?.uncertainty ?: 1.0
            )
        }

        val actions = scored.filter {
            it.example.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalations = scored.filter {
            it.example.targetDisposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2
        }
        val capabilityWeights = actions
            .groupBy { requireNotNull(it.example.targetCapability) }
            .mapValues { (_, values) -> skillWeight(values) }
        val weakCapabilities = actions
            .groupBy { requireNotNull(it.example.targetCapability) }
            .filterValues { values ->
                errorRate(values) >= WEAK_ERROR_RATE ||
                    values.map { it.uncertainty }.average() >= WEAK_UNCERTAINTY
            }
            .keys

        val actionError = errorRate(actions)
        val escalationError = errorRate(escalations)
        val uncertainty = scored.map { it.uncertainty }.average().coerceIn(0.0, 1.0)
        val escalationWeight = skillWeight(escalations)
        val difficulty = (
            0.45 * actionError +
                0.35 * escalationError +
                0.20 * uncertainty
            ).coerceIn(0.0, 1.0)
        val adaptiveRate = (
            baseLearningRate * (0.75 + 0.50 * difficulty)
            ).coerceIn(
                ReflexAdaptiveCurriculum.MIN_LEARNING_RATE,
                ReflexAdaptiveCurriculum.MAX_LEARNING_RATE
            )

        return ReflexAdaptiveCurriculum(
            capabilityWeights = capabilityWeights,
            escalationWeight = escalationWeight,
            weakCapabilities = weakCapabilities,
            actionErrorRate = actionError,
            escalationErrorRate = escalationError,
            meanUncertainty = uncertainty,
            difficulty = difficulty,
            learningRate = adaptiveRate
        )
    }

    private fun skillWeight(values: List<CurriculumScore>): Double {
        if (values.isEmpty()) return 1.0
        val errors = errorRate(values)
        val uncertainty = values.map { it.uncertainty }.average().coerceIn(0.0, 1.0)
        return (
            1.0 +
                1.15 * errors +
                0.35 * uncertainty
            ).coerceIn(
                ReflexAdaptiveCurriculum.MIN_WEIGHT,
                ReflexAdaptiveCurriculum.MAX_WEIGHT
            )
    }

    private fun errorRate(values: List<CurriculumScore>): Double {
        if (values.isEmpty()) return 0.0
        return (
            values.count { !it.exact }.toDouble() / values.size.toDouble()
            ).coerceIn(0.0, 1.0)
    }

    private data class CurriculumScore(
        val example: ReflexExperienceTrainingExample,
        val exact: Boolean,
        val uncertainty: Double
    ) {
        init {
            require(uncertainty in 0.0..1.0)
        }
    }

    const val WEAK_ERROR_RATE = 0.10
    const val WEAK_UNCERTAINTY = 0.15
}
