package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantInteractivePromptBudgetPolicyTest {
    @Test
    fun ordinaryShortTurnUsesCompactMobilePromptBudget() {
        assertEquals(
            AssistantInteractivePromptBudgetPolicy.COMPACT_PROMPT_CHARS,
            AssistantInteractivePromptBudgetPolicy.firstPassBudget(
                configuredPromptChars = 9000,
                userPrompt = "Bạn là ai?",
                attachments = emptyList(),
                preferredProfiles = emptyList(),
                reflectionMode = ConversationReflectionMode.STANDARD,
                requiresSystem2 = false,
                explicitPromptOverride = false
            )
        )
    }

    @Test
    fun explicitUserPromptBudgetIsNeverSilentlyReduced() {
        assertEquals(
            12000,
            AssistantInteractivePromptBudgetPolicy.firstPassBudget(
                configuredPromptChars = 12000,
                userPrompt = "Bạn là ai?",
                attachments = emptyList(),
                preferredProfiles = emptyList(),
                reflectionMode = ConversationReflectionMode.STANDARD,
                requiresSystem2 = false,
                explicitPromptOverride = true
            )
        )
    }

    @Test
    fun specialistTurnKeepsFullPromptBudget() {
        assertEquals(
            9000,
            AssistantInteractivePromptBudgetPolicy.firstPassBudget(
                configuredPromptChars = 9000,
                userPrompt = "Tiếp tục sửa code",
                attachments = emptyList(),
                preferredProfiles = listOf(
                    setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)
                ),
                reflectionMode = ConversationReflectionMode.STANDARD,
                requiresSystem2 = false,
                explicitPromptOverride = false
            )
        )
    }

    @Test
    fun verifyAndSystem2TurnsKeepFullPromptBudget() {
        assertEquals(
            9000,
            AssistantInteractivePromptBudgetPolicy.firstPassBudget(
                configuredPromptChars = 9000,
                userPrompt = "Phân tích kỹ nguyên nhân",
                attachments = emptyList(),
                preferredProfiles = emptyList(),
                reflectionMode = ConversationReflectionMode.VERIFY,
                requiresSystem2 = true,
                explicitPromptOverride = false
            )
        )
    }
}
