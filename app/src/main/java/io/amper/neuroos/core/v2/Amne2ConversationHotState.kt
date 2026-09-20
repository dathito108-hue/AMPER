package io.amper.neuroos.core.v2

data class Amne2ConversationHotIdentity(
    val conversationSessionId: String,
    val modelId: String,
    val foundationId: String,
    val semanticSha256: String,
    val artifactSha256: String
) {
    init {
        require(conversationSessionId.isNotBlank())
        require(modelId.isNotBlank())
        require(foundationId.isNotBlank())
        require(semanticSha256.matches(Regex("[0-9a-f]{64}")))
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/**
 * Pure admission policy for reusing one AMNE2 conversation KV state.
 *
 * Reuse is allowed only when the lifecycle and verified artifact identity are identical and the
 * next fully-tokenized prompt strictly extends the exact token sequence already committed to KV.
 */
object Amne2ConversationHotReusePolicy {
    fun reusablePromptSuffix(
        cachedIdentity: Amne2ConversationHotIdentity,
        requestedIdentity: Amne2ConversationHotIdentity,
        committedTokenIds: IntArray,
        fullPromptTokenIds: IntArray,
        requestedOutputTokens: Int,
        maxContextTokens: Int
    ): IntArray? {
        require(requestedOutputTokens > 0)
        require(maxContextTokens > 0)
        if (cachedIdentity != requestedIdentity) return null
        if (committedTokenIds.isEmpty()) return null
        if (fullPromptTokenIds.size <= committedTokenIds.size) return null
        if (
            fullPromptTokenIds.size.toLong() + requestedOutputTokens.toLong() >
                maxContextTokens.toLong()
        ) {
            return null
        }

        for (index in committedTokenIds.indices) {
            if (fullPromptTokenIds[index] != committedTokenIds[index]) return null
        }

        return fullPromptTokenIds.copyOfRange(
            committedTokenIds.size,
            fullPromptTokenIds.size
        )
    }
}
