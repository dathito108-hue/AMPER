package io.amper.neuroos.core

import java.util.UUID

/**
 * A user-approved local note stored in AMPER's sovereign Memory OS.
 *
 * Notes intentionally use the same durable/encrypted memory journal as the rest of the sovereign
 * runtime. They therefore participate in normal recall/grounding without exposing raw MemoryOs to
 * tool providers or the cognitive model.
 */
data class SovereignNote(
    val id: MemoryId,
    val text: String,
    val createdAtEpochMs: Long
)

interface SovereignNoteStore {
    fun remember(text: String): SovereignNote
    fun find(query: String, limit: Int = 8): List<SovereignNote>
    fun recent(limit: Int = SovereignNoteSearchToolContract.MAX_RESULTS): List<SovereignNote>
    fun get(id: MemoryId): SovereignNote?
    fun forget(id: MemoryId): Boolean
}

class MemoryBackedSovereignNoteStore(
    private val memory: MemoryOs,
    private val workspace: GlobalWorkspace
) : SovereignNoteStore {
    override fun remember(text: String): SovereignNote {
        val normalized = normalize(text)
        val record = MemoryRecord(
            kind = KIND,
            content = normalized,
            importance = 0.90,
            provenance = Provenance(
                source = "user-approved-sovereign-note",
                producer = SovereignNoteToolContract.toolId.value,
                confidence = 1.0
            )
        )
        memory.remember(record)
        workspace.publish(
            CognitiveEvent(
                topic = "sovereign.note.saved",
                payload = "id=${record.id.value};chars=${normalized.length}",
                salience = 0.88
            )
        )
        return record.toNote()
    }

    override fun find(query: String, limit: Int): List<SovereignNote> {
        val normalized = normalizeSearchQuery(query)
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall(normalized, (limit * 3).coerceAtLeast(limit))
            .asSequence()
            .filter { it.kind == KIND }
            .take(limit)
            .map { it.toNote() }
            .toList()
    }

    /**
     * Returns the newest sovereign notes without requiring the user to remember a search term.
     * Memory OS has a bounded live index, so an empty lexical query safely snapshots all live
     * records and this layer filters strictly to sovereign-note before sorting by creation time.
     */
    override fun recent(limit: Int): List<SovereignNote> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall("", memory.size())
            .asSequence()
            .filter { it.kind == KIND }
            .sortedWith(
                compareByDescending<MemoryRecord> { it.createdAtEpochMs }
                    .thenBy { it.id.value }
            )
            .take(limit)
            .map { it.toNote() }
            .toList()
    }

    override fun get(id: MemoryId): SovereignNote? = memory.get(id)
        ?.takeIf { it.kind == KIND }
        ?.toNote()

    override fun forget(id: MemoryId): Boolean = memory.transaction {
        val record = this.get(id) ?: return@transaction false
        if (record.kind != KIND) return@transaction false
        val removed = this.forget(id)
        if (removed) {
            workspace.publish(
                CognitiveEvent(
                    topic = "sovereign.note.deleted",
                    payload = "id=${id.value}",
                    salience = 0.90
                )
            )
        }
        removed
    }

    private fun normalize(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotBlank()) { "sovereign note cannot be blank" }
        require(normalized.length <= SovereignNoteToolContract.MAX_NOTE_CHARS) {
            "sovereign note exceeds ${SovereignNoteToolContract.MAX_NOTE_CHARS} characters"
        }
        require('\n' !in normalized && '\r' !in normalized) {
            "sovereign note must be a single line"
        }
        require('\u0000' !in normalized) { "sovereign note contains an invalid control character" }
        return normalized
    }

    private fun normalizeSearchQuery(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotBlank()) { "sovereign note search query cannot be blank" }
        require(normalized.length <= SovereignNoteSearchToolContract.MAX_QUERY_CHARS) {
            "sovereign note search query exceeds ${SovereignNoteSearchToolContract.MAX_QUERY_CHARS} characters"
        }
        require('\n' !in normalized && '\r' !in normalized && '\u0000' !in normalized) {
            "sovereign note search query must be a valid single line"
        }
        return normalized
    }

    private fun MemoryRecord.toNote() = SovereignNote(id, content, createdAtEpochMs)

    companion object {
        const val KIND = "sovereign-note"
    }
}

object SovereignNoteToolContract {
    val capability = CapabilityId("sovereign.note.write")
    val toolId = ToolId("sovereign-note-writer")
    const val MAX_NOTE_CHARS = 1024
}

object SovereignNoteSearchToolContract {
    val capability = CapabilityId("sovereign.note.search")
    val toolId = ToolId("sovereign-note-search")
    const val MAX_QUERY_CHARS = 256
    const val MAX_RESULTS = 5
    const val MAX_RENDERED_NOTE_CHARS = 512
    const val RECENT_COMMAND = "recent"
    const val RECENT_PREFIX = "recent:"
    const val ID_PREFIX = "id:"
}

object SovereignNoteDeleteToolContract {
    val capability = CapabilityId("sovereign.note.delete")
    val toolId = ToolId("sovereign-note-deleter")
    const val UUID_CHARS = 36
}

/**
 * Explicit local-state capability. The provider itself contains no auto-approval path: because the
 * descriptor is LOCAL_STATE, SovereignActionLoop returns REQUIRES_CONFIRMATION and Phase 118's
 * durable approval/claim transaction must complete before [execute] can be reached.
 */
class SovereignNoteToolProvider(
    private val store: SovereignNoteStore
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = SovereignNoteToolContract.toolId,
        name = "AMPER sovereign note writer",
        capability = SovereignNoteToolContract.capability,
        sideEffect = ToolSideEffect.LOCAL_STATE,
        inputContract = ToolInputContract(
            description = "Persist one user-approved single-line note in AMPER sovereign local memory",
            maxLength = SovereignNoteToolContract.MAX_NOTE_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val note = store.remember(input)
        "note_saved;id=${note.id.value};chars=${note.text.length}"
    }
}

/**
 * Read-only sovereign-note browser. The canonical capability/tool identity is unchanged from the
 * original search tool so persisted V4 plan-time bindings remain valid. Free text keeps legacy
 * lexical search semantics, while exact reserved commands add user-facing browsing without any
 * new authority: `recent`, `recent:N` (1..MAX_RESULTS), and `id:<canonical UUID>`.
 * Note contents remain untrusted tool-result data.
 */
class SovereignNoteSearchToolProvider(
    private val store: SovereignNoteStore
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = SovereignNoteSearchToolContract.toolId,
        name = "AMPER sovereign note browser",
        capability = SovereignNoteSearchToolContract.capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description =
                "Browse user-approved sovereign notes: free-text search, 'recent', 'recent:N' where N is 1..${SovereignNoteSearchToolContract.MAX_RESULTS}, or 'id:<canonical UUID>'",
            maxLength = SovereignNoteSearchToolContract.MAX_QUERY_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val raw = input.trim()
        require(raw.isNotBlank()) { "sovereign note browser input cannot be blank" }
        require(raw.length <= SovereignNoteSearchToolContract.MAX_QUERY_CHARS) {
            "sovereign note browser input exceeds ${SovereignNoteSearchToolContract.MAX_QUERY_CHARS} characters"
        }
        require('\n' !in raw && '\r' !in raw && '\u0000' !in raw) {
            "sovereign note browser input must be a valid single line"
        }

        val matches = when {
            raw == SovereignNoteSearchToolContract.RECENT_COMMAND ->
                store.recent(SovereignNoteSearchToolContract.MAX_RESULTS)

            raw.startsWith(SovereignNoteSearchToolContract.RECENT_PREFIX) -> {
                val requested = raw.removePrefix(SovereignNoteSearchToolContract.RECENT_PREFIX)
                require(requested.matches(Regex("[1-9][0-9]*"))) {
                    "recent note limit must be an integer from 1 to ${SovereignNoteSearchToolContract.MAX_RESULTS}"
                }
                val limit = requested.toInt()
                require(limit in 1..SovereignNoteSearchToolContract.MAX_RESULTS) {
                    "recent note limit must be from 1 to ${SovereignNoteSearchToolContract.MAX_RESULTS}"
                }
                store.recent(limit)
            }

            raw.startsWith(SovereignNoteSearchToolContract.ID_PREFIX) -> {
                val rawId = raw.removePrefix(SovereignNoteSearchToolContract.ID_PREFIX)
                require(rawId.length == SovereignNoteDeleteToolContract.UUID_CHARS) {
                    "sovereign note id lookup requires an exact canonical UUID"
                }
                val canonical = UUID.fromString(rawId).toString()
                require(canonical == rawId) { "sovereign note id lookup requires a canonical UUID" }
                store.get(MemoryId(canonical))?.let(::listOf).orEmpty()
            }

            else -> store.find(raw, SovereignNoteSearchToolContract.MAX_RESULTS)
        }

        render(matches)
    }

    private fun render(matches: List<SovereignNote>): String {
        if (matches.isEmpty()) return "notes=none"
        return buildString {
            appendLine("notes=${matches.size}")
            matches.forEach { note ->
                append("note_id=${note.id.value};created_at_epoch_ms=${note.createdAtEpochMs};text=")
                appendLine(note.text.take(SovereignNoteSearchToolContract.MAX_RENDERED_NOTE_CHARS))
            }
        }.trimEnd()
    }
}

/**
 * Exact-id note deletion. It is LOCAL_STATE and therefore cannot execute until the durable approval
 * transaction has bound this exact provider and side-effect class. The store refuses to delete any
 * MemoryRecord whose kind is not sovereign-note.
 */
class SovereignNoteDeleteToolProvider(
    private val store: SovereignNoteStore
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = SovereignNoteDeleteToolContract.toolId,
        name = "AMPER sovereign note deleter",
        capability = SovereignNoteDeleteToolContract.capability,
        sideEffect = ToolSideEffect.LOCAL_STATE,
        inputContract = ToolInputContract(
            description = "Delete one sovereign note by its exact UUID shown by sovereign.note.search",
            maxLength = SovereignNoteDeleteToolContract.UUID_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val raw = input.trim()
        require(raw.length == SovereignNoteDeleteToolContract.UUID_CHARS) {
            "sovereign.note.delete requires an exact note UUID"
        }
        val canonical = UUID.fromString(raw).toString()
        require(canonical == raw) { "sovereign.note.delete requires a canonical UUID" }
        val id = MemoryId(canonical)
        require(store.forget(id)) { "sovereign note $canonical was not found" }
        "note_deleted;id=$canonical"
    }
}
