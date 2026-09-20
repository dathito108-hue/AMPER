package io.amper.neuroos.core.v2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Amne2ConversationHotStateTest {
    private val identity = Amne2ConversationHotIdentity(
        conversationSessionId = "conversation-1",
        modelId = "model-1",
        foundationId = "amper-foundation",
        semanticSha256 = "1".repeat(64),
        artifactSha256 = "2".repeat(64)
    )

    @Test
    fun exactStrictPrefixReturnsOnlyUnevaluatedSuffix() {
        val suffix = Amne2ConversationHotReusePolicy.reusablePromptSuffix(
            cachedIdentity = identity,
            requestedIdentity = identity,
            committedTokenIds = intArrayOf(10, 11, 12),
            fullPromptTokenIds = intArrayOf(10, 11, 12, 13, 14),
            requestedOutputTokens = 2,
            maxContextTokens = 16
        )

        assertArrayEquals(intArrayOf(13, 14), suffix)
    }

    @Test
    fun lifecycleOrArtifactIdentityChangeRejectsReuse() {
        val changedConversation = identity.copy(conversationSessionId = "conversation-2")
        val changedArtifact = identity.copy(artifactSha256 = "3".repeat(64))

        assertNull(
            Amne2ConversationHotReusePolicy.reusablePromptSuffix(
                cachedIdentity = identity,
                requestedIdentity = changedConversation,
                committedTokenIds = intArrayOf(1, 2),
                fullPromptTokenIds = intArrayOf(1, 2, 3),
                requestedOutputTokens = 1,
                maxContextTokens = 8
            )
        )
        assertNull(
            Amne2ConversationHotReusePolicy.reusablePromptSuffix(
                cachedIdentity = identity,
                requestedIdentity = changedArtifact,
                committedTokenIds = intArrayOf(1, 2),
                fullPromptTokenIds = intArrayOf(1, 2, 3),
                requestedOutputTokens = 1,
                maxContextTokens = 8
            )
        )
    }

    @Test
    fun tokenPrefixMismatchOrNonExtensionRejectsReuse() {
        assertNull(
            Amne2ConversationHotReusePolicy.reusablePromptSuffix(
                cachedIdentity = identity,
                requestedIdentity = identity,
                committedTokenIds = intArrayOf(1, 2, 3),
                fullPromptTokenIds = intArrayOf(1, 9, 3, 4),
                requestedOutputTokens = 1,
                maxContextTokens = 16
            )
        )
        assertNull(
            Amne2ConversationHotReusePolicy.reusablePromptSuffix(
                cachedIdentity = identity,
                requestedIdentity = identity,
                committedTokenIds = intArrayOf(1, 2, 3),
                fullPromptTokenIds = intArrayOf(1, 2, 3),
                requestedOutputTokens = 1,
                maxContextTokens = 16
            )
        )
    }

    @Test
    fun contextOverflowRejectsReuse() {
        assertNull(
            Amne2ConversationHotReusePolicy.reusablePromptSuffix(
                cachedIdentity = identity,
                requestedIdentity = identity,
                committedTokenIds = intArrayOf(1, 2),
                fullPromptTokenIds = intArrayOf(1, 2, 3, 4),
                requestedOutputTokens = 5,
                maxContextTokens = 8
            )
        )
    }
}
