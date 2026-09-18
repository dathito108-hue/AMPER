package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal data class MemoryJournalChainState(
    val sequence: Long,
    val digest: String
) {
    init {
        require(sequence >= 0)
        require(digest.matches(HASH))
    }

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
        val GENESIS = MemoryJournalChainState(0, "0".repeat(64))
    }
}

internal data class MemoryJournalChainEntry(
    val sequence: Long,
    val previousDigest: String,
    val digest: String,
    val payload: String,
    val encoded: String
)

/**
 * C1 binds each F1-framed journal mutation to its position and predecessor.
 * A compacted segment may start from a verified B1 base state instead of genesis.
 */
internal object MemoryJournalChainCodec {
    private const val VERSION = "C1"
    private val HASH = Regex("[0-9a-f]{64}")

    fun encode(payload: String, previous: MemoryJournalChainState): MemoryJournalChainEntry {
        require(payload.isNotEmpty())
        val sequence = previous.sequence + 1
        val framed = MemoryJournalFrameCodec.encode(payload)
        val framedBytes = framed.toByteArray(StandardCharsets.UTF_8)
        val framedBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(framedBytes)
        val canonical = listOf(VERSION, sequence.toString(), previous.digest, framedBase64).joinToString("|")
        val digest = sha256(canonical.toByteArray(StandardCharsets.UTF_8))
        val encoded = "$canonical|$digest"
        return MemoryJournalChainEntry(sequence, previous.digest, digest, payload, encoded)
    }

    fun decode(line: String): Result<MemoryJournalChainEntry> = runCatching {
        val parts = line.split('|')
        require(parts.size == 5 && parts[0] == VERSION) { "unsupported memory journal chain frame" }
        val sequence = parts[1].toLong()
        require(sequence > 0) { "invalid memory journal chain sequence" }
        val previousDigest = parts[2]
        require(previousDigest.matches(HASH)) { "invalid memory journal previous digest" }
        val framedBase64 = parts[3]
        val digest = parts[4]
        require(digest.matches(HASH)) { "invalid memory journal chain digest" }
        val canonical = parts.take(4).joinToString("|")
        require(sha256(canonical.toByteArray(StandardCharsets.UTF_8)) == digest) {
            "memory journal chain digest mismatch"
        }
        val framed = String(Base64.getUrlDecoder().decode(framedBase64), StandardCharsets.UTF_8)
        val payload = MemoryJournalFrameCodec.decode(framed).getOrThrow()
        MemoryJournalChainEntry(sequence, previousDigest, digest, payload, line)
    }

    fun decodeSequence(
        lines: List<String>,
        initialState: MemoryJournalChainState = MemoryJournalChainState.GENESIS
    ): Result<List<MemoryJournalChainEntry>> = runCatching {
        var state = initialState
        lines.map { line ->
            val entry = decode(line).getOrThrow()
            require(entry.sequence == state.sequence + 1) {
                "memory journal chain sequence gap or reorder"
            }
            require(entry.previousDigest == state.digest) {
                "memory journal chain predecessor mismatch"
            }
            state = MemoryJournalChainState(entry.sequence, entry.digest)
            entry
        }
    }

    fun stateOf(
        entries: List<MemoryJournalChainEntry>,
        initialState: MemoryJournalChainState = MemoryJournalChainState.GENESIS
    ): MemoryJournalChainState =
        entries.lastOrNull()?.let { MemoryJournalChainState(it.sequence, it.digest) } ?: initialState

    fun isChained(line: String): Boolean = line.startsWith("$VERSION|")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
