package io.amper.neuroos.core.v2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Amne2ContextPressurePolicyTest {
    private val identity = Amne2ConversationHotIdentity(
        conversationSessionId = "conversation-1",
        modelId = "model-1",
        foundationId = "amper-foundation",
        semanticSha256 = "1".repeat(64),
        artifactSha256 = "2".repeat(64)
    )

    @Test
    fun exactPrefixWithinBudgetReusesOnlyUnevaluatedSuffix() {
        val decision = Amne2ContextPressurePolicy.decide(
            cachedIdentity = identity,
            requestedIdentity = identity,
            committedTokenIds = intArrayOf(10, 11, 12),
            fullPromptTokenIds = intArrayOf(10, 11, 12, 13, 14),
            requestedOutputTokens = 2,
            currentSessionMaxContextTokens = 16,
            safeContextTokens = 16
        )

        assertEquals(
            Amne2ContextPressureAction.REUSE_HOT_SESSION,
            decision.action
        )
        assertArrayEquals(intArrayOf(13, 14), decision.promptSuffix)
    }

    @Test
    fun prefixMismatchWithinBudgetRebuildsFromFullPrompt() {
        val decision = Amne2ContextPressurePolicy.decide(
            cachedIdentity = identity,
            requestedIdentity = identity,
            committedTokenIds = intArrayOf(1, 2, 3),
            fullPromptTokenIds = intArrayOf(1, 9, 3, 4),
            requestedOutputTokens = 2,
            currentSessionMaxContextTokens = 16,
            safeContextTokens = 16
        )

        assertEquals(
            Amne2ContextPressureAction.REBUILD_FULL_PROMPT,
            decision.action
        )
        assertTrue(decision.promptSuffix.isEmpty())
    }

    @Test
    fun sessionTooSmallButHardwareBudgetFitsRebuildsWithLargerSafeCap() {
        val decision = Amne2ContextPressurePolicy.decide(
            cachedIdentity = identity,
            requestedIdentity = identity,
            committedTokenIds = intArrayOf(1, 2, 3),
            fullPromptTokenIds = intArrayOf(1, 2, 3, 4, 5, 6),
            requestedOutputTokens = 2,
            currentSessionMaxContextTokens = 6,
            safeContextTokens = 12
        )

        assertEquals(
            Amne2ContextPressureAction.REBUILD_FULL_PROMPT,
            decision.action
        )
        assertEquals(8, decision.requiredContextTokens)
    }

    @Test
    fun identityChangeWithinBudgetRebuildsInsteadOfReusingKv() {
        val changed = identity.copy(conversationSessionId = "conversation-2")
        val decision = Amne2ContextPressurePolicy.decide(
            cachedIdentity = identity,
            requestedIdentity = changed,
            committedTokenIds = intArrayOf(1, 2),
            fullPromptTokenIds = intArrayOf(1, 2, 3),
            requestedOutputTokens = 1,
            currentSessionMaxContextTokens = 8,
            safeContextTokens = 8
        )

        assertEquals(
            Amne2ContextPressureAction.REBUILD_FULL_PROMPT,
            decision.action
        )
    }

    @Test
    fun fullPromptBeyondSafeBudgetRequiresExplicitCompaction() {
        val decision = Amne2ContextPressurePolicy.decide(
            cachedIdentity = identity,
            requestedIdentity = identity,
            committedTokenIds = intArrayOf(1, 2, 3),
            fullPromptTokenIds = IntArray(9) { it },
            requestedOutputTokens = 4,
            currentSessionMaxContextTokens = 16,
            safeContextTokens = 12
        )

        assertEquals(
            Amne2ContextPressureAction.REQUIRE_COMPACTION,
            decision.action
        )
        assertEquals(13, decision.requiredContextTokens)
        assertEquals(
            "amper-core-context-compaction-required:required=13,safe=12",
            Amne2ContextPressurePolicy.rejectionReason(
                decision.requiredContextTokens,
                decision.safeContextTokens
            )
        )
    }

    @Test
    fun noHotSessionWithinBudgetBuildsFullPrompt() {
        val decision = Amne2ContextPressurePolicy.decide(
            cachedIdentity = null,
            requestedIdentity = identity,
            committedTokenIds = intArrayOf(),
            fullPromptTokenIds = intArrayOf(4, 5, 6),
            requestedOutputTokens = 2,
            currentSessionMaxContextTokens = null,
            safeContextTokens = 8
        )

        assertEquals(
            Amne2ContextPressureAction.REBUILD_FULL_PROMPT,
            decision.action
        )
    }
}
