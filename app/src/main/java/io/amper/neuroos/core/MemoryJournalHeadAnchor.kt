package io.amper.neuroos.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal interface MemoryJournalHeadAnchor {
    fun load(): MemoryJournalChainState?
    fun store(state: MemoryJournalChainState)
}

internal object MemoryJournalHeadCodec {
    private const val VERSION = "H1"
    private val HASH = Regex("[0-9a-f]{64}")

    fun encode(state: MemoryJournalChainState): String {
        val canonical = listOf(VERSION, state.sequence.toString(), state.digest).joinToString("|")
        return "$canonical|${sha256(canonical)}"
    }

    fun decode(value: String): Result<MemoryJournalChainState> = runCatching {
        val parts = value.split('|')
        require(parts.size == 4 && parts[0] == VERSION) { "unsupported memory journal head anchor" }
        val sequence = parts[1].toLong()
        require(sequence >= 0) { "invalid memory journal head sequence" }
        val digest = parts[2]
        require(digest.matches(HASH)) { "invalid memory journal head digest" }
        val checksum = parts[3]
        require(checksum.matches(HASH)) { "invalid memory journal head checksum" }
        val canonical = parts.take(3).joinToString("|")
        require(sha256(canonical) == checksum) { "memory journal head checksum mismatch" }
        MemoryJournalChainState(sequence, digest)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal class PlaintextMemoryJournalHeadAnchor(private val file: File) : MemoryJournalHeadAnchor {
    override fun load(): MemoryJournalChainState? {
        SovereignPathIdentity.requireManagedNamespace(file)
        DurableJournalIo.recoverInterruptedReplace(file)
        SovereignPathIdentity.requireManagedNamespace(file)
        if (!file.exists()) return null
        return MemoryJournalHeadCodec.decode(readSingleCompleteLine(file)).getOrThrow()
    }

    override fun store(state: MemoryJournalChainState) {
        SovereignPathIdentity.requireManagedNamespace(file)
        DurableJournalIo.rewriteUtf8LinesAtomically(file, ".updating", listOf(MemoryJournalHeadCodec.encode(state)))
        SovereignPathIdentity.requireManagedNamespace(file)
    }
}

internal class EncryptedMemoryJournalHeadAnchor(
    private val file: File,
    private val cipher: MemoryLineCipher
) : MemoryJournalHeadAnchor {
    override fun load(): MemoryJournalChainState? {
        SovereignPathIdentity.requireManagedNamespace(file)
        DurableJournalIo.recoverInterruptedReplace(file)
        SovereignPathIdentity.requireManagedNamespace(file)
        if (!file.exists()) return null
        val envelope = readSingleCompleteLine(file)
        require(envelope.startsWith("E1|")) { "unencrypted encrypted-memory head anchor" }
        return MemoryJournalHeadCodec.decode(cipher.decrypt(envelope)).getOrThrow()
    }

    override fun store(state: MemoryJournalChainState) {
        SovereignPathIdentity.requireManagedNamespace(file)
        DurableJournalIo.rewriteUtf8LinesAtomically(
            file,
            ".updating",
            listOf(cipher.encrypt(MemoryJournalHeadCodec.encode(state)))
        )
        SovereignPathIdentity.requireManagedNamespace(file)
    }
}

internal object MemoryJournalHeadVerifier {
    fun verifyAndReconcile(
        entries: List<MemoryJournalChainEntry>,
        anchor: MemoryJournalHeadAnchor,
        base: MemoryJournalChainState = MemoryJournalChainState.GENESIS
    ): MemoryJournalChainState {
        val head = MemoryJournalChainCodec.stateOf(entries, base)
        val anchored = anchor.load()
        if (anchored == null) {
            anchor.store(head)
            return head
        }

        require(anchored.sequence >= base.sequence) {
            "memory journal anchor predates compacted base"
        }
        require(anchored.sequence <= head.sequence) {
            "memory journal rollback detected: anchor sequence ${anchored.sequence} exceeds journal head ${head.sequence}"
        }

        if (anchored.sequence == base.sequence) {
            require(anchored.digest == base.digest) {
                "memory journal compacted base diverges from durable anchor"
            }
            if (head != anchored) anchor.store(head)
            return head
        }

        val anchoredDigest = entries.firstOrNull { it.sequence == anchored.sequence }?.digest
            ?: error("memory journal anchor sequence is outside verified segment")
        require(anchoredDigest == anchored.digest) {
            "memory journal extension does not descend from durable anchor"
        }

        if (anchored.sequence == head.sequence) return head

        // A verified extension with an older anchor is the recoverable crash window where
        // journal fsync succeeded before the head sidecar replacement.
        anchor.store(head)
        return head
    }
}

private fun readSingleCompleteLine(file: File): String =
    DescriptorBoundFileIo.readSingleCompleteUtf8Line(file, "memory journal head anchor")
