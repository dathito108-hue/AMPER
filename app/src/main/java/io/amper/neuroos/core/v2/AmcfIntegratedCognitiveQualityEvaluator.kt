package io.amper.neuroos.core.v2

import io.amper.neuroos.core.IntegratedCognitiveStatePacket
import io.amper.neuroos.core.PerceptualFreshness

data class AmcfIntegratedCognitiveQualitySignals(
    val cognitiveStateDigest: String,
    val overallReadiness: Double,
    val epistemicConfidence: Double,
    val worldConfidence: Double,
    val skillConfidence: Double,
    val competenceConfidence: Double,
    val uncertainty: Double,
    val learningPressure: Double,
    val perceptualFreshness: Double,
    val stalePerceptFraction: Double
) {
    init {
        require(cognitiveStateDigest.matches(Regex("[0-9a-f]{64}"))) {
            "AMCF cognitive-state digest must be lowercase SHA-256"
        }
        listOf(
            overallReadiness,
            epistemicConfidence,
            worldConfidence,
            skillConfidence,
            competenceConfidence,
            uncertainty,
            learningPressure,
            perceptualFreshness,
            stalePerceptFraction
        ).forEach { require(it in 0.0..1.0) }
    }

    companion object {
        fun from(packet: IntegratedCognitiveStatePacket): AmcfIntegratedCognitiveQualitySignals {
            val percepts = packet.perceptualEvidence
            val freshness = if (percepts.isEmpty()) {
                NO_PERCEPTUAL_EVIDENCE_NEUTRAL
            } else {
                percepts.map { percept ->
                    when (percept.freshness) {
                        PerceptualFreshness.FRESH -> 1.0
                        PerceptualFreshness.RECENT -> 0.85
                        PerceptualFreshness.STALE -> 0.0
                    }
                }.average().coerceIn(0.0, 1.0)
            }
            val staleFraction = if (percepts.isEmpty()) {
                0.0
            } else {
                percepts.count { !it.planningEligible }.toDouble()
                    .div(percepts.size.toDouble())
                    .coerceIn(0.0, 1.0)
            }
            val readiness = packet.readiness
            return AmcfIntegratedCognitiveQualitySignals(
                cognitiveStateDigest = packet.canonicalDigest,
                overallReadiness = readiness.overallReadiness,
                epistemicConfidence = readiness.epistemicConfidence,
                worldConfidence = readiness.worldConfidence,
                skillConfidence = readiness.skillConfidence,
                competenceConfidence = readiness.competenceConfidence,
                uncertainty = readiness.uncertainty,
                learningPressure = readiness.learningPressure,
                perceptualFreshness = freshness,
                stalePerceptFraction = staleFraction
            )
        }

        const val NO_PERCEPTUAL_EVIDENCE_NEUTRAL: Double = 0.80
    }
}

/**
 * Deterministic Phase644 adapter from the already-existing integrated cognitive readiness packet.
 *
 * No model call, judge pass or text scoring occurs here. The mapping uses only structured signals
 * already computed by the canonical cognitive runtime. Stale perception and learning pressure are
 * penalties, so uncertain evidence can only make early exit harder.
 */
class AmcfIntegratedCognitiveQualityEvaluator(
    val signals: AmcfIntegratedCognitiveQualitySignals
) : AmcfCycleQualityEvaluator {
    override fun evaluate(input: AmcfCycleQualityInput): AmcfCycleQuality {
        val readinessConfidence = (
            0.40 * signals.overallReadiness +
                0.25 * signals.epistemicConfidence +
                0.20 * signals.worldConfidence +
                0.10 * signals.competenceConfidence +
                0.05 * signals.skillConfidence
            ).coerceIn(0.0, 1.0)

        val confidence = (
            readinessConfidence * (1.0 - 0.20 * signals.learningPressure)
            ).coerceIn(0.0, 1.0)

        val uncertainty = maxOf(
            signals.uncertainty,
            0.50 * signals.stalePerceptFraction
        ).coerceIn(0.0, 1.0)

        val evidenceSufficiency = (
            (
                0.40 * signals.epistemicConfidence +
                    0.35 * signals.worldConfidence +
                    0.15 * signals.competenceConfidence +
                    0.10 * signals.perceptualFreshness
                ) *
                (1.0 - 0.15 * signals.learningPressure)
            ).coerceIn(0.0, 1.0)

        return AmcfCycleQuality(
            confidence = confidence,
            uncertainty = uncertainty,
            evidenceSufficiency = evidenceSufficiency
        )
    }
}
