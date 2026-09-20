package io.amper.neuroos.core

/**
 * Mobile latency policy for the first assistant inference pass.
 *
 * Ordinary short text turns can use the minimum production prompt budget while complex,
 * specialist, multimodal, VERIFY and explicit user-configured prompt-budget turns retain the
 * caller's complete budget. This changes only prompt construction; Titan routing, capability
 * admission, action parsing and authority remain unchanged.
 */
object AssistantInteractivePromptBudgetPolicy {
    const val COMPACT_PROMPT_CHARS: Int = ConversationInferenceProfile.MIN_PROMPT_CHARS
    private const val MAX_SHORT_USER_PROMPT_CHARS = 240

    fun firstPassBudget(
        configuredPromptChars: Int,
        userPrompt: String,
        attachments: List<InferenceAttachment>,
        preferredProfiles: List<Set<CapabilityId>>,
        reflectionMode: ConversationReflectionMode,
        requiresSystem2: Boolean,
        explicitPromptOverride: Boolean
    ): Int {
        require(
            configuredPromptChars in
                ConversationInferenceProfile.MIN_PROMPT_CHARS..ConversationInferenceProfile.MAX_PROMPT_CHARS
        )
        require(userPrompt.isNotBlank())

        val compactEligible =
            !explicitPromptOverride &&
                !requiresSystem2 &&
                reflectionMode == ConversationReflectionMode.STANDARD &&
                attachments.isEmpty() &&
                preferredProfiles.isEmpty() &&
                userPrompt.length <= MAX_SHORT_USER_PROMPT_CHARS

        return if (compactEligible) {
            minOf(configuredPromptChars, COMPACT_PROMPT_CHARS)
        } else {
            configuredPromptChars
        }
    }
}
