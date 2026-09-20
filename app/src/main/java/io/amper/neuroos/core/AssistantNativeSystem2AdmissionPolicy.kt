package io.amper.neuroos.core

import java.text.Normalizer
import java.util.Locale

enum class AssistantTurnStage {
    RUNTIME_TICK,
    REFLEX,
    NATIVE_SYSTEM2,
    TITAN_INFERENCE,
    ACTION_EVALUATION,
    FINALIZING
}

/**
 * Keeps AMPER's Native System-2 on turns that materially benefit from bounded deliberation while
 * allowing ordinary tool-free conversational turns to reach Titan without first constructing the
 * full integrated cognitive packet.
 *
 * This is only a pre-inference scheduling/admission decision. Titan capability/resource admission,
 * ToolDescriptor binding, parsing and AuthorityGate behavior are unchanged.
 */
object AssistantNativeSystem2AdmissionPolicy {
    private const val LONG_PROMPT_CHARS = 320

    fun requiresDeliberation(
        userPrompt: String,
        attachments: List<InferenceAttachment>,
        explicitProfiles: List<Set<CapabilityId>>
    ): Boolean {
        require(userPrompt.isNotBlank())

        if (attachments.isNotEmpty()) return true
        if (explicitProfiles.any { TitanCapabilities.PLANNING in it }) return true
        if (userPrompt.length >= LONG_PROMPT_CHARS) return true

        val normalized = normalize(userPrompt)
        return DELIBERATION_PHRASES.any { phrase ->
            containsPhrase(normalized, phrase)
        }
    }

    private fun containsPhrase(value: String, phrase: String): Boolean =
        Regex("(^|\\s)" + Regex.escape(phrase) + "(\\s|$)")
            .containsMatchIn(value)

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace('đ', 'd')
            .replace(NON_WORD, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private val DELIBERATION_PHRASES = setOf(
        "analyze",
        "analyse",
        "compare",
        "trade off",
        "root cause",
        "step by step",
        "debug",
        "design",
        "architecture",
        "strategy",
        "plan",
        "reason carefully",
        "evaluate",
        "counterfactual",
        "phan tich",
        "so sanh",
        "nguyen nhan",
        "tung buoc",
        "go loi",
        "thiet ke",
        "kien truc",
        "chien luoc",
        "lap ke hoach",
        "ke hoach",
        "danh gia"
    )

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
}
