package io.amper.neuroos.core

/**
 * Read-only persisted conversation transcript projection for user inspection.
 *
 * Transcript browsing never performs inference, invokes tools, grants authority, appends turns,
 * rewrites metadata or publishes workspace events. Long conversations are intentionally bounded;
 * when truncation occurs the newest [limit] persisted turns are returned in chronological order.
 */
data class SovereignConversationTranscriptTurn(
    val role: ConversationRole,
    val text: String,
    val createdAtEpochMs: Long,
    val backendId: String?,
    val modelId: ModelId?,
    val selectedCapabilities: Set<CapabilityId>
)

data class SovereignConversationTranscript(
    val conversationId: ConversationId,
    val title: String?,
    val turns: List<SovereignConversationTranscriptTurn>,
    val truncated: Boolean,
    val forkInfo: ConversationForkInfo? = null
)

class SovereignConversationTranscriptBrowser(
    private val conversations: SovereignConversationCoordinator
) {
    fun open(
        conversationId: ConversationId,
        limit: Int = DEFAULT_TRANSCRIPT_TURNS
    ): SovereignConversationTranscript {
        require(limit in 1..MAX_TRANSCRIPT_TURNS) {
            "conversation transcript limit must be between 1 and $MAX_TRANSCRIPT_TURNS"
        }
        val persisted = conversations.recent(conversationId, limit + 1)
        require(persisted.isNotEmpty()) {
            "conversation has no persisted turns: ${conversationId.value}"
        }
        val truncated = persisted.size > limit
        val visible = if (truncated) persisted.takeLast(limit) else persisted
        return SovereignConversationTranscript(
            conversationId = conversationId,
            title = conversations.title(conversationId),
            turns = visible.map { turn ->
                SovereignConversationTranscriptTurn(
                    role = turn.role,
                    text = turn.text,
                    createdAtEpochMs = turn.createdAtEpochMs,
                    backendId = turn.backendId,
                    modelId = turn.modelId,
                    selectedCapabilities = turn.selectedCapabilities
                )
            },
            truncated = truncated,
            forkInfo = conversations.forkInfo(conversationId)
        )
    }

    companion object {
        const val DEFAULT_TRANSCRIPT_TURNS = 64
        const val MAX_TRANSCRIPT_TURNS = 128
    }
}
