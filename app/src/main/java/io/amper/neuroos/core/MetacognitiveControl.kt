package io.amper.neuroos.core

import java.util.Locale
import kotlin.math.roundToInt

enum class MetacognitiveControlMode {
    DIRECT,
    DELIBERATE,
    CAUTIOUS
}

data class MetacognitiveControlDirective(
    val cognitiveStateDigest: String,
    val mode: MetacognitiveControlMode,
    val requestedCandidateCount: Int,
    val planningMaxOutputTokens: Int,
    val planningTemperature: Double,
    val criticMaxOutputTokens: Int,
    val criticTemperature: Double,
    val evidenceCaution: Double,
    val sideEffectCaution: Double,
    val learningPressure: Double
) {
    init {
        require(cognitiveStateDigest.matches(SHA256))
        require(requestedCandidateCount in 1..TitanDeliberationProtocol.MAX_CANDIDATES)
        require(planningMaxOutputTokens > 0)
        require(planningTemperature in 0.0..2.0)
        require(criticMaxOutputTokens > 0)
        require(criticTemperature in 0.0..2.0)
        require(evidenceCaution in 0.0..1.0)
        require(sideEffectCaution in 0.0..1.0)
        require(learningPressure in 0.0..1.0)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Phase256-260 metacognitive inference control.
 *
 * Phase256 classifies the current integrated cognitive state into DIRECT, DELIBERATE or CAUTIOUS.
 * Phase257 adapts inference budget and sampling downward/within the frozen user profile; it never
 * exceeds the user's bound token budget or temperature.
 * Phase258 exposes a bounded target deliberation depth and evidence caution to the planner.
 * Phase259 gives the independent critic a stricter budget/temperature under uncertainty.
 * Phase260 freezes one directive per decision and binds planner plus critic to the exact same
 * cognitive-state digest, preventing metacognitive policy drift inside one decision.
 *
 * The directive is diagnostic/control data only. It cannot grant authority, approve a side effect,
 * change a ToolDescriptor, execute a tool, or create an autonomous inference loop.
 */
object MetacognitiveInferenceControlPolicy {
    fun derive(
        state: IntegratedCognitiveStatePacket,
        profile: BoundConversationInferenceProfile
    ): MetacognitiveControlDirective {
        val readiness = state.readiness
        val mode = when {
            readiness.overallReadiness < 0.45 || readiness.uncertainty >= 0.55 ->
                MetacognitiveControlMode.CAUTIOUS
            readiness.overallReadiness >= 0.72 &&
                readiness.uncertainty <= 0.20 &&
                readiness.learningPressure < 0.55 ->
                MetacognitiveControlMode.DIRECT
            else ->
                MetacognitiveControlMode.DELIBERATE
        }

        val planningScale = when (mode) {
            MetacognitiveControlMode.DIRECT -> 0.75
            MetacognitiveControlMode.DELIBERATE -> 0.90
            MetacognitiveControlMode.CAUTIOUS -> 1.00
        }
        val planningTemperatureCap = when (mode) {
            MetacognitiveControlMode.DIRECT -> 0.65
            MetacognitiveControlMode.DELIBERATE -> 0.40
            MetacognitiveControlMode.CAUTIOUS -> 0.20
        }
        val criticTokenCap = when (mode) {
            MetacognitiveControlMode.DIRECT -> 192
            MetacognitiveControlMode.DELIBERATE -> 256
            MetacognitiveControlMode.CAUTIOUS -> 384
        }
        val criticTemperatureCap = when (mode) {
            MetacognitiveControlMode.DIRECT -> 0.25
            MetacognitiveControlMode.DELIBERATE -> 0.18
            MetacognitiveControlMode.CAUTIOUS -> 0.10
        }
        val requestedCandidates = when (mode) {
            MetacognitiveControlMode.DIRECT -> 1
            MetacognitiveControlMode.DELIBERATE -> 2
            MetacognitiveControlMode.CAUTIOUS -> TitanDeliberationProtocol.MAX_CANDIDATES
        }

        val evidenceCaution = (
            0.55 * readiness.uncertainty +
                0.25 * (1.0 - readiness.epistemicConfidence) +
                0.20 * (1.0 - readiness.worldConfidence)
            ).coerceIn(0.0, 1.0)
        val sideEffectCaution = when (mode) {
            MetacognitiveControlMode.DIRECT -> 0.25
            MetacognitiveControlMode.DELIBERATE -> 0.60
            MetacognitiveControlMode.CAUTIOUS -> 0.90
        }

        return MetacognitiveControlDirective(
            cognitiveStateDigest = state.canonicalDigest,
            mode = mode,
            requestedCandidateCount = requestedCandidates,
            planningMaxOutputTokens = scaledTokens(
                profile.maxOutputTokens,
                planningScale
            ),
            planningTemperature = minOf(profile.temperature, planningTemperatureCap),
            criticMaxOutputTokens = minOf(profile.maxOutputTokens, criticTokenCap),
            criticTemperature = minOf(profile.temperature, criticTemperatureCap),
            evidenceCaution = evidenceCaution,
            sideEffectCaution = sideEffectCaution,
            learningPressure = readiness.learningPressure
        )
    }

    private fun scaledTokens(maxTokens: Int, scale: Double): Int =
        (maxTokens * scale)
            .roundToInt()
            .coerceIn(1, maxTokens)
}

object MetacognitiveControlRenderer {
    fun render(directive: MetacognitiveControlDirective): String = buildString {
        appendLine("<METACOGNITIVE_CONTROL>")
        appendLine("cognitive_state_digest=" + directive.cognitiveStateDigest)
        appendLine("mode=" + directive.mode.name)
        appendLine("requested_candidate_count=" + directive.requestedCandidateCount)
        appendLine("evidence_caution=" + fmt(directive.evidenceCaution))
        appendLine("side_effect_caution=" + fmt(directive.sideEffectCaution))
        appendLine("learning_pressure=" + fmt(directive.learningPressure))
        appendLine("authority=false")
        appendLine(
            "instruction=Use the requested deliberation depth when distinct valid alternatives exist; " +
                "never invent evidence, tool success, approval or authority."
        )
        appendLine(
            "instruction=Higher caution means prefer evidence-supported, reversible and read-only " +
                "strategies when they still satisfy the user goal; live tool contracts remain authoritative."
        )
        append("</METACOGNITIVE_CONTROL>")
    }

    private fun fmt(value: Double): String =
        "%.3f".format(Locale.US, value)
}
