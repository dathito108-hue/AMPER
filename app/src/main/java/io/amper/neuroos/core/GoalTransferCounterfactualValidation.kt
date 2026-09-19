package io.amper.neuroos.core

import java.util.Locale

enum class GoalTransferValidationDecision {
    ACCEPT,
    REJECT
}

data class GoalTransferCounterfactualAssessment(
    val candidate: GoalStrategyTransferCandidate,
    val cognitiveStateDigest: String,
    val liveCapabilityCoverage: Double,
    val competenceFit: Double,
    val worldFit: Double,
    val epistemicFit: Double,
    val perceptionFit: Double,
    val learningRisk: Double,
    val contextFit: Double,
    val mismatchRisk: Double,
    val projectedSupport: Double,
    val calibrationMultiplier: Double,
    val calibratedSupport: Double,
    val staleHistoricalEvidence: Boolean,
    val decision: GoalTransferValidationDecision
) {
    init {
        require(cognitiveStateDigest.matches(Regex("[0-9a-f]{64}")))
        listOf(
            liveCapabilityCoverage,
            competenceFit,
            worldFit,
            epistemicFit,
            perceptionFit,
            learningRisk,
            contextFit,
            mismatchRisk,
            projectedSupport,
            calibratedSupport
        ).forEach { require(it in 0.0..1.0) }
        require(calibrationMultiplier in 0.0..1.25)
        require(kotlin.math.abs((1.0 - contextFit) - mismatchRisk) < 1e-9)
    }

    val authorityBearing: Boolean
        get() = false
}

/**
 * Phase341-345 deterministic counterfactual validation for historical goal-strategy transfer.
 *
 * This evaluator performs no inference and no tool execution. It binds historical transfer support
 * to the exact current integrated cognitive-state digest and live tool surface, then discounts or
 * rejects candidates whose required capabilities are unavailable, whose present competence/learning
 * evidence is poor, or whose world/epistemic/perceptual grounding is too weak.
 *
 * Authority history is intentionally absent from the viability formula. Permission/approval remains
 * external and authoritative, so counterfactual transfer cannot learn to route around governance.
 */
object GoalTransferCounterfactualValidator {
    const val MAX_CANDIDATES = GoalOutcomeStrategyTransfer.MAX_CANDIDATES
    const val MIN_CONTEXT_FIT = 0.35
    const val MIN_PROJECTED_SUPPORT = 0.025
    const val HARD_LEARNING_RISK = 0.90

    fun validate(
        candidates: Collection<GoalStrategyTransferCandidate>,
        state: IntegratedCognitiveStatePacket,
        descriptors: Collection<ToolDescriptor>,
        calibration: GoalTransferCalibrationModel? = null,
        limit: Int = MAX_CANDIDATES
    ): List<GoalTransferCounterfactualAssessment> {
        require(limit in 0..MAX_CANDIDATES)
        if (limit == 0 || candidates.isEmpty()) return emptyList()

        val descriptorCapabilities = descriptors.map { it.capability }.toSet()
        val competenceByCapability = state.context.capabilityCompetence
            .associateBy { it.capability }
        val learningSeverityByCapability = state.learningNeeds
            .groupBy { it.capability }
            .mapValues { (_, needs) -> needs.maxOfOrNull { it.severity } ?: 0.0 }

        return candidates
            .take(MAX_CANDIDATES)
            .map { candidate ->
                val required = candidate.strategy.capabilities.distinct()
                val liveCount = required.count { it in descriptorCapabilities }
                val coverage = if (required.isEmpty()) {
                    0.0
                } else {
                    liveCount.toDouble() / required.size.toDouble()
                }

                val competenceFit = if (required.isEmpty()) {
                    0.0
                } else {
                    required.map { capability ->
                        val snapshot = competenceByCapability[capability]
                        if (snapshot == null) {
                            0.50
                        } else {
                            val rate = snapshot.executionSuccessRate ?: 0.50
                            val confidence = snapshot.evidenceConfidence
                            (0.50 * (1.0 - confidence) + rate * confidence)
                                .coerceIn(0.0, 1.0)
                        }
                    }.average().coerceIn(0.0, 1.0)
                }

                val learningRisk = required
                    .map { capability -> learningSeverityByCapability[capability] ?: 0.0 }
                    .maxOrNull()
                    ?.coerceIn(0.0, 1.0)
                    ?: 0.0

                val perceptionFit = relevantPerceptionFit(state)
                val worldFit = state.readiness.worldConfidence
                val epistemicFit = state.readiness.epistemicConfidence
                val baseContextFit = (
                    worldFit * 0.25 +
                        epistemicFit * 0.20 +
                        competenceFit * 0.25 +
                        perceptionFit * 0.15 +
                        state.readiness.overallReadiness * 0.15
                    ).coerceIn(0.0, 1.0)
                val contextFit = (
                    baseContextFit * (1.0 - learningRisk * 0.35)
                    ).coerceIn(0.0, 1.0)
                val mismatchRisk = (1.0 - contextFit).coerceIn(0.0, 1.0)
                val projectedSupport = (
                    candidate.transferSupport * (0.50 + 0.50 * contextFit)
                    ).coerceIn(0.0, 1.0)
                val calibrationAdjustment = GoalTransferCalibrationPolicy.adjust(
                    candidate = candidate,
                    snapshot = calibration?.snapshot(candidate.strategy),
                    nowEpochMs = state.capturedAtEpochMs
                )
                val calibratedSupport = (
                    projectedSupport * calibrationAdjustment.multiplier
                    ).coerceIn(0.0, 1.0)

                val decision = if (
                    coverage == 1.0 &&
                    learningRisk < HARD_LEARNING_RISK &&
                    !calibrationAdjustment.suppressedByFailureStreak &&
                    contextFit >= MIN_CONTEXT_FIT &&
                    calibratedSupport >= MIN_PROJECTED_SUPPORT
                ) {
                    GoalTransferValidationDecision.ACCEPT
                } else {
                    GoalTransferValidationDecision.REJECT
                }

                GoalTransferCounterfactualAssessment(
                    candidate = candidate,
                    cognitiveStateDigest = state.canonicalDigest,
                    liveCapabilityCoverage = coverage,
                    competenceFit = competenceFit,
                    worldFit = worldFit,
                    epistemicFit = epistemicFit,
                    perceptionFit = perceptionFit,
                    learningRisk = learningRisk,
                    contextFit = contextFit,
                    mismatchRisk = mismatchRisk,
                    projectedSupport = projectedSupport,
                    calibrationMultiplier = calibrationAdjustment.multiplier,
                    calibratedSupport = calibratedSupport,
                    staleHistoricalEvidence = calibrationAdjustment.staleHistoricalEvidence,
                    decision = decision
                )
            }
            .filter { it.decision == GoalTransferValidationDecision.ACCEPT }
            .sortedWith(
                compareByDescending<GoalTransferCounterfactualAssessment> {
                    it.calibratedSupport
                }
                    .thenByDescending { it.projectedSupport }
                    .thenByDescending { it.contextFit }
                    .thenByDescending { it.candidate.transferSupport }
                    .thenBy { it.candidate.strategy.canonical }
            )
            .take(limit)
    }

    fun render(
        assessments: List<GoalTransferCounterfactualAssessment>
    ): String {
        if (assessments.isEmpty()) return ""
        require(assessments.size <= MAX_CANDIDATES)
        require(assessments.all { it.decision == GoalTransferValidationDecision.ACCEPT })

        return buildString {
            appendLine("<GOAL_OUTCOME_COUNTERFACTUAL_TRANSFER>")
            appendLine(
                "Historical goal-strategy transfer has been revalidated against the exact current " +
                    "cognitive state and live capability surface. It remains advisory only."
            )
            appendLine(
                "Never reuse old inputs, outputs, request IDs, approvals or tool bindings. " +
                    "Authority history is diagnostic only and is excluded from transfer viability."
            )
            appendLine("cognitive_state_digest=" + assessments.first().cognitiveStateDigest)
            assessments.forEachIndexed { index, assessment ->
                val candidate = assessment.candidate
                appendLine(
                    "candidate." + (index + 1) + ".capabilities=" +
                        candidate.strategy.capabilities.joinToString(">") { it.value } +
                        " historical_support=" + fmt(candidate.transferSupport) +
                        " live_coverage=" + fmt(assessment.liveCapabilityCoverage) +
                        " competence_fit=" + fmt(assessment.competenceFit) +
                        " world_fit=" + fmt(assessment.worldFit) +
                        " epistemic_fit=" + fmt(assessment.epistemicFit) +
                        " perception_fit=" + fmt(assessment.perceptionFit) +
                        " learning_risk=" + fmt(assessment.learningRisk) +
                        " context_fit=" + fmt(assessment.contextFit) +
                        " mismatch_risk=" + fmt(assessment.mismatchRisk) +
                        " projected_support=" + fmt(assessment.projectedSupport) +
                        " calibration_multiplier=" + fmt(assessment.calibrationMultiplier) +
                        " calibrated_support=" + fmt(assessment.calibratedSupport) +
                        " stale_history=" + assessment.staleHistoricalEvidence +
                        " authority=false"
                )
            }
            append("</GOAL_OUTCOME_COUNTERFACTUAL_TRANSFER>")
        }
    }

    private fun relevantPerceptionFit(
        state: IntegratedCognitiveStatePacket
    ): Double {
        val relevant = state.perceptualEvidence
            .filter { it.planningEligible }
            .filter { it.queryRelevance > 0.0 }
        if (relevant.isEmpty()) return 0.50
        return relevant
            .map { evidence ->
                (evidence.confidence * evidence.queryRelevance).coerceIn(0.0, 1.0)
            }
            .average()
            .coerceIn(0.0, 1.0)
    }

    private fun fmt(value: Double): String =
        "%.3f".format(Locale.US, value)
}
