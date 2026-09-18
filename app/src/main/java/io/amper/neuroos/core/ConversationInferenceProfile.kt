package io.amper.neuroos.core

/**
 * User-facing session-routing policy. This only changes ranking among routes that already passed
 * capability, artifact, backend-health, feedback and resource admission.
 */
enum class TitanSessionRoutingPreference {
    /** Preserve AMPER's existing warm-session affinity bonus. */
    STANDARD,

    /** Prefer a compatible warm session more strongly when the route remains otherwise eligible. */
    PREFER_REUSE,

    /** Ignore warm-session affinity during ranking; this does not force a backend to discard state. */
    IGNORE_REUSE
}

/**
 * Optional final-answer verification mode. VERIFY never grants tool authority and never creates an
 * autonomous loop: it permits at most one extra inference pass after a tool-free first response.
 */
enum class ConversationReflectionMode {
    STANDARD,
    VERIFY
}

/**
 * Optional per-conversation inference overrides. Null numeric fields inherit the coordinator's
 * production defaults, allowing old conversations and cleared profiles to preserve legacy behavior.
 */
data class ConversationInferenceProfile(
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val sessionRoutingPreference: TitanSessionRoutingPreference = TitanSessionRoutingPreference.STANDARD,
    val maxPromptChars: Int? = null,
    val reflectionMode: ConversationReflectionMode = ConversationReflectionMode.STANDARD
) {
    init {
        maxOutputTokens?.let { require(it > 0) { "conversation max output tokens must be positive" } }
        temperature?.let { require(it in 0.0..2.0) { "conversation temperature must be within 0.0..2.0" } }
        maxPromptChars?.let {
            require(it in MIN_PROMPT_CHARS..MAX_PROMPT_CHARS) {
                "conversation prompt context budget must be within $MIN_PROMPT_CHARS..$MAX_PROMPT_CHARS characters"
            }
        }
    }

    val isDefault: Boolean
        get() = maxOutputTokens == null &&
            temperature == null &&
            sessionRoutingPreference == TitanSessionRoutingPreference.STANDARD &&
            maxPromptChars == null &&
            reflectionMode == ConversationReflectionMode.STANDARD

    fun bind(
        fallbackMaxOutputTokens: Int,
        fallbackTemperature: Double = 0.7,
        fallbackMaxPromptChars: Int = DEFAULT_PROMPT_CHARS
    ): BoundConversationInferenceProfile {
        require(fallbackMaxOutputTokens > 0)
        require(fallbackTemperature in 0.0..2.0)
        require(fallbackMaxPromptChars in MIN_PROMPT_CHARS..MAX_PROMPT_CHARS)
        return BoundConversationInferenceProfile(
            maxOutputTokens = maxOutputTokens ?: fallbackMaxOutputTokens,
            temperature = temperature ?: fallbackTemperature,
            sessionRoutingPreference = sessionRoutingPreference,
            maxPromptChars = maxPromptChars ?: fallbackMaxPromptChars,
            reflectionMode = reflectionMode
        )
    }

    companion object {
        const val MIN_PROMPT_CHARS = 4096
        const val MAX_PROMPT_CHARS = 32768
        const val DEFAULT_PROMPT_CHARS = 9000
    }
}

/** Frozen turn-local profile used across all inference passes and durable approval resume. */
data class BoundConversationInferenceProfile(
    val maxOutputTokens: Int,
    val temperature: Double,
    val sessionRoutingPreference: TitanSessionRoutingPreference,
    val maxPromptChars: Int = ConversationInferenceProfile.DEFAULT_PROMPT_CHARS,
    val reflectionMode: ConversationReflectionMode = ConversationReflectionMode.STANDARD
) {
    init {
        require(maxOutputTokens > 0)
        require(temperature in 0.0..2.0)
        require(maxPromptChars in ConversationInferenceProfile.MIN_PROMPT_CHARS..ConversationInferenceProfile.MAX_PROMPT_CHARS)
    }
}

interface ConversationInferenceProfileStore {
    fun profile(conversationId: ConversationId): ConversationInferenceProfile?
    fun put(conversationId: ConversationId, profile: ConversationInferenceProfile)
    fun clear(conversationId: ConversationId): Boolean

    fun bind(
        conversationId: ConversationId,
        fallbackMaxOutputTokens: Int,
        fallbackTemperature: Double = 0.7,
        fallbackMaxPromptChars: Int = ConversationInferenceProfile.DEFAULT_PROMPT_CHARS
    ): BoundConversationInferenceProfile =
        (profile(conversationId) ?: ConversationInferenceProfile())
            .bind(fallbackMaxOutputTokens, fallbackTemperature, fallbackMaxPromptChars)
}

/**
 * Append-only sovereign Memory OS storage for conversation inference policy.
 *
 * The metadata has its own namespace, never rewrites conversation turns, never publishes workspace
 * events and never starts model execution. Equivalent writes are idempotent. A caller-provided
 * existence predicate prevents user metadata from manufacturing ghost conversations in production.
 */
class MemoryBackedConversationInferenceProfileStore(
    private val memory: MemoryOs,
    private val conversationExists: (ConversationId) -> Boolean = { true }
) : ConversationInferenceProfileStore {
    private val writeLock = Any()

    override fun profile(conversationId: ConversationId): ConversationInferenceProfile? =
        latest(conversationId)?.profile

    override fun put(
        conversationId: ConversationId,
        profile: ConversationInferenceProfile
    ) = synchronized(writeLock) {
        require(conversationExists(conversationId)) {
            "conversation has no persisted turns: ${conversationId.value}"
        }
        if (profile.isDefault) {
            clearLocked(conversationId)
            return@synchronized
        }
        val existing = latest(conversationId)
        if (existing?.profile == profile) return@synchronized
        memory.remember(
            MemoryRecord(
                kind = KIND,
                content = listOf(
                    tag(conversationId),
                    SET,
                    profile.maxOutputTokens?.toString() ?: INHERIT,
                    profile.temperature?.toString() ?: INHERIT,
                    profile.sessionRoutingPreference.name,
                    profile.maxPromptChars?.toString() ?: INHERIT,
                    profile.reflectionMode.name
                ).joinToString("|"),
                importance = 0.57,
                provenance = Provenance(
                    source = "conversation-user-inference-profile",
                    producer = "sovereign-conversation",
                    confidence = 1.0,
                    parents = existing?.recordId?.let(::setOf).orEmpty()
                ),
                createdAtEpochMs = nextTimestamp(existing)
            )
        )
    }

    override fun clear(conversationId: ConversationId): Boolean = synchronized(writeLock) {
        require(conversationExists(conversationId)) {
            "conversation has no persisted turns: ${conversationId.value}"
        }
        clearLocked(conversationId)
    }

    private fun clearLocked(conversationId: ConversationId): Boolean {
        val existing = latest(conversationId) ?: return false
        if (existing.profile == null) return false
        memory.remember(
            MemoryRecord(
                kind = KIND,
                content = "${tag(conversationId)}|$CLEAR",
                importance = 0.57,
                provenance = Provenance(
                    source = "conversation-user-inference-profile",
                    producer = "sovereign-conversation",
                    confidence = 1.0,
                    parents = setOf(existing.recordId)
                ),
                createdAtEpochMs = nextTimestamp(existing)
            )
        )
        return true
    }

    private data class PersistedProfile(
        val conversationId: ConversationId,
        val profile: ConversationInferenceProfile?,
        val createdAtEpochMs: Long,
        val recordId: MemoryId
    )

    private fun latest(conversationId: ConversationId): PersistedProfile? =
        memory.recall(tag(conversationId), memory.size())
            .asSequence()
            .filter { it.kind == KIND && it.content.startsWith("${tag(conversationId)}|") }
            .mapNotNull(::decode)
            .maxWithOrNull(
                compareBy<PersistedProfile> { it.createdAtEpochMs }
                    .thenBy { it.recordId.value }
            )

    private fun decode(record: MemoryRecord): PersistedProfile? = runCatching {
        val parts = record.content.split('|')
        require(parts.size >= 2)
        require(parts[0].startsWith(TAG_PREFIX))
        val conversationId = ConversationId(unescape(parts[0].removePrefix(TAG_PREFIX)))
        val profile = when (parts[1]) {
            CLEAR -> {
                require(parts.size == 2)
                null
            }
            SET -> {
                require(parts.size in 5..7)
                ConversationInferenceProfile(
                    maxOutputTokens = parts[2].takeUnless { it == INHERIT }?.toInt(),
                    temperature = parts[3].takeUnless { it == INHERIT }?.toDouble(),
                    sessionRoutingPreference = TitanSessionRoutingPreference.valueOf(parts[4]),
                    maxPromptChars = parts.getOrNull(5)?.takeUnless { it == INHERIT }?.toInt(),
                    reflectionMode = parts.getOrNull(6)
                        ?.let(ConversationReflectionMode::valueOf)
                        ?: ConversationReflectionMode.STANDARD
                ).also { require(!it.isDefault) { "default profile must be represented by clear state" } }
            }
            else -> error("unknown conversation inference profile state")
        }
        PersistedProfile(conversationId, profile, record.createdAtEpochMs, record.id)
    }.getOrNull()

    private fun nextTimestamp(existing: PersistedProfile?): Long =
        existing?.createdAtEpochMs?.let { previous ->
            require(previous < Long.MAX_VALUE) { "conversation inference profile timestamp exhausted" }
            maxOf(System.currentTimeMillis(), previous + 1L)
        } ?: System.currentTimeMillis()

    private fun tag(conversationId: ConversationId): String = TAG_PREFIX + escape(conversationId.value)

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
        private const val KIND = "conversation-inference-profile"
        private const val TAG_PREFIX = "conversation-inference-profile:"
        private const val SET = "SET"
        private const val CLEAR = "CLEAR"
        private const val INHERIT = "~"
    }
}
