package io.amper.neuroos.core

/**
 * Persistent user-selected soft model preference scoped to one sovereign conversation.
 *
 * This is routing metadata only. It never loads a model, bypasses Titan admission, grants a
 * capability, changes model capability metadata, or executes inference. Records use a separate
 * namespace from conversation turns so repeated preference changes cannot crowd bounded turn recall.
 */
interface ConversationModelRoutingPreferences {
    fun preferred(conversationId: ConversationId): ModelId?
    fun prefer(conversationId: ConversationId, modelId: ModelId?)
    fun clearModel(modelId: ModelId): Int
}

class MemoryBackedConversationModelRoutingPreferences(
    private val memory: MemoryOs
) : ConversationModelRoutingPreferences {
    private val writeLock = Any()

    override fun preferred(conversationId: ConversationId): ModelId? =
        latest(conversationId)?.modelId

    override fun prefer(conversationId: ConversationId, modelId: ModelId?) = synchronized(writeLock) {
        val existing = latest(conversationId)
        if (existing?.modelId == modelId || (existing == null && modelId == null)) {
            return@synchronized
        }
        val createdAtEpochMs = existing?.createdAtEpochMs?.let { previous ->
            require(previous < Long.MAX_VALUE) { "conversation model preference timestamp exhausted" }
            maxOf(System.currentTimeMillis(), previous + 1L)
        } ?: System.currentTimeMillis()
        memory.remember(
            MemoryRecord(
                kind = KIND,
                content = buildString {
                    append(tag(conversationId))
                    append('|')
                    if (modelId == null) {
                        append(CLEAR)
                    } else {
                        append(SET)
                        append('|')
                        append(escape(modelId.value))
                    }
                },
                importance = 0.58,
                provenance = Provenance(
                    source = "conversation-user-model-preference",
                    producer = "sovereign-conversation",
                    confidence = 1.0,
                    parents = existing?.recordId?.let(::setOf).orEmpty()
                ),
                createdAtEpochMs = createdAtEpochMs
            )
        )
    }

    override fun clearModel(modelId: ModelId): Int = synchronized(writeLock) {
        val affected = currentPreferences()
            .filterValues { it == modelId }
            .keys
            .toList()
        affected.forEach { conversationId ->
            val existing = latest(conversationId)
            if (existing?.modelId == modelId) {
                val createdAtEpochMs = existing.createdAtEpochMs.let { previous ->
                    require(previous < Long.MAX_VALUE) { "conversation model preference timestamp exhausted" }
                    maxOf(System.currentTimeMillis(), previous + 1L)
                }
                memory.remember(
                    MemoryRecord(
                        kind = KIND,
                        content = "${tag(conversationId)}|$CLEAR",
                        importance = 0.58,
                        provenance = Provenance(
                            source = "conversation-user-model-preference",
                            producer = "sovereign-conversation",
                            confidence = 1.0,
                            parents = setOf(existing.recordId)
                        ),
                        createdAtEpochMs = createdAtEpochMs
                    )
                )
            }
        }
        affected.size
    }

    private data class PersistedPreference(
        val conversationId: ConversationId,
        val modelId: ModelId?,
        val createdAtEpochMs: Long,
        val recordId: MemoryId
    )

    private fun currentPreferences(): Map<ConversationId, ModelId?> {
        val latest = linkedMapOf<ConversationId, PersistedPreference>()
        memory.recall("", memory.size())
            .asSequence()
            .filter { it.kind == KIND }
            .mapNotNull(::decode)
            .sortedWith(
                compareBy<PersistedPreference> { it.createdAtEpochMs }
                    .thenBy { it.recordId.value }
            )
            .forEach { preference -> latest[preference.conversationId] = preference }
        return latest.mapValues { it.value.modelId }
    }

    private fun latest(conversationId: ConversationId): PersistedPreference? =
        memory.recall(tag(conversationId), memory.size())
            .asSequence()
            .filter { it.kind == KIND && it.content.startsWith("${tag(conversationId)}|") }
            .mapNotNull(::decode)
            .maxWithOrNull(
                compareBy<PersistedPreference> { it.createdAtEpochMs }
                    .thenBy { it.recordId.value }
            )

    private fun decode(record: MemoryRecord): PersistedPreference? = runCatching {
        val parts = record.content.split('|', limit = 3)
        require(parts.size in 2..3)
        require(parts[0].startsWith(TAG_PREFIX))
        val conversationId = ConversationId(unescape(parts[0].removePrefix(TAG_PREFIX)))
        val modelId = when (parts[1]) {
            CLEAR -> {
                require(parts.size == 2)
                null
            }
            SET -> {
                require(parts.size == 3)
                ModelId(unescape(parts[2])).also {
                    require(it.value.isNotBlank()) { "conversation preferred model id must not be blank" }
                }
            }
            else -> error("unknown conversation model preference state")
        }
        PersistedPreference(conversationId, modelId, record.createdAtEpochMs, record.id)
    }.getOrNull()

    private fun tag(conversationId: ConversationId): String =
        TAG_PREFIX + escape(conversationId.value)

    private fun escape(value: String): String = value
        .replace("%", "%25")
        .replace("|", "%7C")
        .replace("\n", "%0A")
        .replace("\r", "%0D")

    private fun unescape(value: String): String = value
        .replace("%0D", "\r")
        .replace("%0A", "\n")
        .replace("%7C", "|")
        .replace("%25", "%")

    companion object {
        private const val KIND = "conversation-model-preference"
        private const val TAG_PREFIX = "conversation-model-preference:"
        private const val SET = "SET"
        private const val CLEAR = "CLEAR"
    }
}
