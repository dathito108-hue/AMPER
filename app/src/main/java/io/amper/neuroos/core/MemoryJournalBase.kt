package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class MemoryJournalSegment(
    val base: MemoryJournalChainState,
    val entries: List<MemoryJournalChainEntry>
) {
    val head: MemoryJournalChainState
        get() = MemoryJournalChainCodec.stateOf(entries, base)
}

/**
 * B1 is the physical compaction boundary. It records the verified C1 head that the
 * compacted snapshot descends from; new snapshot entries continue sequence numbers
 * from this state instead of resetting to genesis.
 */
internal object MemoryJournalBaseCodec {
    private const val VERSION = "B1"
    private val HASH = Regex("[0-9a-f]{64}")

    fun encode(state: MemoryJournalChainState): String {
        val canonical = listOf(VERSION, state.sequence.toString(), state.digest).joinToString("|")
        return "$canonical|${sha256(canonical)}"
    }

    fun decode(line: String): Result<MemoryJournalChainState> = runCatching {
        val parts = line.split('|')
        require(parts.size == 4 && parts[0] == VERSION) { "unsupported memory journal base checkpoint" }
        val sequence = parts[1].toLong()
        require(sequence >= 0) { "invalid memory journal base sequence" }
        val digest = parts[2]
        require(digest.matches(HASH)) { "invalid memory journal base digest" }
        val checksum = parts[3]
        require(checksum.matches(HASH)) { "invalid memory journal base checksum" }
        val canonical = parts.take(3).joinToString("|")
        require(sha256(canonical) == checksum) { "memory journal base checksum mismatch" }
        MemoryJournalChainState(sequence, digest)
    }

    fun isBase(line: String): Boolean = line.startsWith("$VERSION|")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal object MemoryJournalSegmentCodec {
    fun decodePlaintext(lines: List<String>): Result<MemoryJournalSegment> = runCatching {
        if (lines.isEmpty()) return@runCatching MemoryJournalSegment(MemoryJournalChainState.GENESIS, emptyList())
        lines.forEach { require(it.isNotBlank()) { "blank complete memory journal line" } }
        val base = if (MemoryJournalBaseCodec.isBase(lines.first())) {
            MemoryJournalBaseCodec.decode(lines.first()).getOrThrow()
        } else {
            MemoryJournalChainState.GENESIS
        }
        val chainLines = if (MemoryJournalBaseCodec.isBase(lines.first())) lines.drop(1) else lines
        require(chainLines.none(MemoryJournalBaseCodec::isBase)) { "memory journal base checkpoint must appear only first" }
        val entries = MemoryJournalChainCodec.decodeSequence(chainLines, base).getOrThrow()
        MemoryJournalSegment(base, entries)
    }
}
