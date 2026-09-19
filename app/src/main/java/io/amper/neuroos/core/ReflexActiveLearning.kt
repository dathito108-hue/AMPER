package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.max

data class ReflexHardExampleAssessment(
    val exampleId: ReflexExperienceExampleId,
    val priority: Double,
    val labelConfidence: Double,
    val exactChampionMatch: Boolean,
    val championConfidence: Double,
    val championUncertainty: Double,
    val novelCapability: Boolean
) {
    init {
        require(priority in 0.0..1.0)
        require(labelConfidence in 0.0..1.0)
        require(championConfidence in 0.0..1.0)
        require(championUncertainty in 0.0..1.0)
    }

    val hard: Boolean
        get() =
            !exactChampionMatch ||
                novelCapability ||
                championUncertainty >= HARD_UNCERTAINTY ||
                championConfidence <= LOW_CONFIDENCE

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val HARD_UNCERTAINTY = 0.20
        const val LOW_CONFIDENCE = 0.80
    }
}

data class ReflexActiveLearningBatch(
    val selectedExampleIds: List<ReflexExperienceExampleId>,
    val actionExamples: Int,
    val escalationExamples: Int,
    val hardExamples: Int,
    val disagreementExamples: Int,
    val uncertainExamples: Int,
    val novelCapabilities: Set<CapabilityId>,
    val meanPriority: Double,
    val eligibleExamples: Int,
    val rejectedLowQualityExamples: Int
) {
    init {
        require(selectedExampleIds.distinct().size == selectedExampleIds.size)
        require(actionExamples >= 0)
        require(escalationExamples >= 0)
        require(actionExamples + escalationExamples == selectedExampleIds.size)
        require(hardExamples in 0..eligibleExamples)
        require(disagreementExamples in 0..eligibleExamples)
        require(uncertainExamples in 0..eligibleExamples)
        require(meanPriority in 0.0..1.0)
        require(eligibleExamples >= selectedExampleIds.size)
        require(rejectedLowQualityExamples >= 0)
    }

    val highValueSignal: Boolean
        get() =
            novelCapabilities.isNotEmpty() ||
                disagreementExamples >= MIN_DISAGREEMENT_TRIGGER ||
                hardExamples >= MIN_HARD_TRIGGER ||
                meanPriority >= MEAN_PRIORITY_TRIGGER

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MIN_DISAGREEMENT_TRIGGER = 8
        const val MIN_HARD_TRIGGER = 12
        const val MEAN_PRIORITY_TRIGGER = 0.40
    }
}

/**
 * Phase501-505 active-learning miner for AMPER Reflex.
 *
 * It runs only over already-retained privacy-preserving Reflex examples. Raw user text is unavailable
 * here by construction. The current champion is scored against governed labels, then hard,
 * uncertain, novel-capability and high-quality examples are deterministically prioritized.
 *
 * This component chooses training evidence only. It cannot execute tools, alter authority, promote a
 * checkpoint or manufacture a label.
 */
object ReflexActiveLearningMiner {
    const val MIN_LABEL_CONFIDENCE = 0.75

    fun mine(
        fresh: List<ReflexExperienceTrainingExample>,
        champion: NativeReflexDecisionPort,
        seenActionCapabilities: Set<CapabilityId>,
        curriculum: ReflexAdaptiveCurriculum? = null,
        minActionExamples: Int,
        minEscalationExamples: Int,
        maxExamples: Int
    ): ReflexActiveLearningBatch? {
        require(fresh.isNotEmpty())
        require(minActionExamples > 0)
        require(minEscalationExamples > 0)
        require(maxExamples >= minActionExamples + minEscalationExamples)
        require(maxExamples <= MemoryBackedReflexExperienceDatasetStore.MAX_SHARD_EXAMPLES)

        val eligible = fresh.filter { it.labelConfidence >= MIN_LABEL_CONFIDENCE }
        val actionEligible = eligible.filter {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalationEligible = eligible.filter {
            it.targetDisposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2
        }
        if (
            actionEligible.size < minActionExamples ||
            escalationEligible.size < minEscalationExamples
        ) {
            return null
        }

        val assessments = eligible.associate { example ->
            example.id to assess(
                example = example,
                champion = champion,
                seenActionCapabilities = seenActionCapabilities,
                curriculum = curriculum
            )
        }
        val orderedActions = actionEligible.sortedWith(
            compareByDescending<ReflexExperienceTrainingExample> {
                requireNotNull(assessments[it.id]).priority
            }.thenBy { stableKey(it.id) }
        )
        val orderedEscalations = escalationEligible.sortedWith(
            compareByDescending<ReflexExperienceTrainingExample> {
                requireNotNull(assessments[it.id]).priority
            }.thenBy { stableKey(it.id) }
        )

        val selected = linkedMapOf<ReflexExperienceExampleId, ReflexExperienceTrainingExample>()
        orderedActions.take(minActionExamples).forEach { selected[it.id] = it }
        orderedEscalations.take(minEscalationExamples).forEach { selected[it.id] = it }

        val novelCapabilities = actionEligible
            .mapNotNull { it.targetCapability }
            .filterNot { it in seenActionCapabilities }
            .toSet()
        novelCapabilities
            .sortedBy { it.value }
            .forEach { capability ->
                orderedActions
                    .asSequence()
                    .filter { it.targetCapability == capability }
                    .take(NOVEL_CAPABILITY_FLOOR)
                    .forEach { selected[it.id] = it }
            }

        val remaining = eligible
            .asSequence()
            .filterNot { it.id in selected }
            .sortedWith(
                compareByDescending<ReflexExperienceTrainingExample> {
                    requireNotNull(assessments[it.id]).priority
                }.thenBy { stableKey(it.id) }
            )
            .take((maxExamples - selected.size).coerceAtLeast(0))
        remaining.forEach { selected[it.id] = it }

        val bounded = selected.values.take(maxExamples)
        val actionCount = bounded.count {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalationCount = bounded.size - actionCount
        require(actionCount >= minActionExamples)
        require(escalationCount >= minEscalationExamples)

        val allAssessments = eligible.map { requireNotNull(assessments[it.id]) }
        val selectedAssessments = bounded.map { requireNotNull(assessments[it.id]) }
        return ReflexActiveLearningBatch(
            selectedExampleIds = bounded.map { it.id },
            actionExamples = actionCount,
            escalationExamples = escalationCount,
            hardExamples = allAssessments.count { it.hard },
            disagreementExamples = allAssessments.count { !it.exactChampionMatch },
            uncertainExamples = allAssessments.count {
                it.championUncertainty >= ReflexHardExampleAssessment.HARD_UNCERTAINTY
            },
            novelCapabilities = novelCapabilities,
            meanPriority = if (selectedAssessments.isEmpty()) {
                0.0
            } else {
                selectedAssessments.sumOf { it.priority } / selectedAssessments.size.toDouble()
            },
            eligibleExamples = eligible.size,
            rejectedLowQualityExamples = fresh.size - eligible.size
        )
    }

    private fun assess(
        example: ReflexExperienceTrainingExample,
        champion: NativeReflexDecisionPort,
        seenActionCapabilities: Set<CapabilityId>,
        curriculum: ReflexAdaptiveCurriculum?
    ): ReflexHardExampleAssessment {
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
        val confidence = prediction?.confidence ?: 0.0
        val uncertainty = prediction?.uncertainty ?: 1.0
        val novel = example.targetCapability?.let { it !in seenActionCapabilities } == true

        val modelDifficulty = if (!exact) {
            (0.78 + 0.22 * confidence).coerceIn(0.0, 1.0)
        } else {
            max(uncertainty, 1.0 - confidence).coerceIn(0.0, 1.0)
        }
        val noveltyFloor = if (novel) 0.95 else 0.0
        val basePriority = max(modelDifficulty, noveltyFloor)
            .times(example.labelConfidence)
            .coerceIn(0.0, 1.0)
        val curriculumWeight = curriculum?.weightFor(example) ?: 1.0
        val priority = (
            1.0 - Math.pow(1.0 - basePriority, curriculumWeight)
            ).coerceIn(0.0, 1.0)

        return ReflexHardExampleAssessment(
            exampleId = example.id,
            priority = priority,
            labelConfidence = example.labelConfidence,
            exactChampionMatch = exact,
            championConfidence = confidence,
            championUncertainty = uncertainty,
            novelCapability = novel
        )
    }

    private fun stableKey(id: ReflexExperienceExampleId): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                ("AMPER_REFLEX_ACTIVE_LEARNING_V1|" + id.value)
                    .toByteArray(StandardCharsets.UTF_8)
            )
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val NOVEL_CAPABILITY_FLOOR = 4
}
