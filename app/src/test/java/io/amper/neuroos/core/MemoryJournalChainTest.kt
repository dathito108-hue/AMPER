package io.amper.neuroos.core

import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryJournalChainTest {
    private fun record(id: String) = MemoryRecord(
        id = MemoryId(id),
        kind = "chain-test",
        content = "payload-$id",
        importance = 1.0,
        provenance = Provenance(source = "test", producer = "phase33", confidence = 1.0)
    )

    @Test
    fun codecBindsSequenceAndPredecessor() {
        val first = MemoryJournalChainCodec.encode("R|first", MemoryJournalChainState.GENESIS)
        val firstState = MemoryJournalChainState(first.sequence, first.digest)
        val second = MemoryJournalChainCodec.encode("D|second", firstState)

        val decoded = MemoryJournalChainCodec.decodeSequence(listOf(first.encoded, second.encoded)).getOrThrow()

        assertEquals(listOf(1L, 2L), decoded.map { it.sequence })
        assertEquals(first.digest, decoded[1].previousDigest)
    }

    @Test
    fun phase32PlaintextF1MigratesToC1WithoutDataLoss() {
        val file = File.createTempFile("amper-chain-f1-migrate", ".journal")
        try {
            val expected = record("phase32-plain")
            val payload = MemoryJournalCodec.encodeRecord(expected)
            file.writeText(MemoryJournalFrameCodec.encode(payload) + "\n")

            val journal = FileMemoryJournal(file)

            assertEquals(listOf(expected), journal.replay())
            assertTrue(file.readLines().single().startsWith("C1|"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun phase32EncryptedE1F1MigratesToEncryptedC1WithoutDataLoss() {
        val file = File.createTempFile("amper-chain-e1f1-migrate", ".journal")
        val cipher = AesGcmMemoryLineCipher(
            SecretKeySpec(ByteArray(32) { index -> (index + 31).toByte() }, "AES"),
            "phase32-upgrade-key"
        )
        try {
            val expected = record("phase32-encrypted")
            val f1 = MemoryJournalFrameCodec.encode(MemoryJournalCodec.encodeRecord(expected))
            file.writeText(cipher.encrypt(f1) + "\n")

            val journal = EncryptedFileMemoryJournal(file, cipher)

            assertEquals(listOf(expected), journal.replay())
            val plaintext = cipher.decrypt(file.readLines().single())
            assertTrue(plaintext.startsWith("C1|"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun plaintextInternalDeletionFailsClosed() {
        val file = File.createTempFile("amper-chain-delete", ".journal")
        try {
            val journal = FileMemoryJournal(file)
            journal.append(record("a"))
            journal.append(record("b"))
            journal.append(record("c"))
            val lines = file.readLines()
            file.writeText(listOf(lines[0], lines[2]).joinToString("\n", postfix = "\n"))

            val result = runCatching { FileMemoryJournal(file).replay() }

            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("sequence") || message.contains("predecessor"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun plaintextReorderFailsClosed() {
        val file = File.createTempFile("amper-chain-reorder", ".journal")
        try {
            val journal = FileMemoryJournal(file)
            journal.append(record("a"))
            journal.append(record("b"))
            journal.append(record("c"))
            val lines = file.readLines()
            file.writeText(listOf(lines[1], lines[0], lines[2]).joinToString("\n", postfix = "\n"))

            assertTrue(runCatching { FileMemoryJournal(file).replay() }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun plaintextDuplicateEntryFailsClosed() {
        val file = File.createTempFile("amper-chain-duplicate", ".journal")
        try {
            val journal = FileMemoryJournal(file)
            journal.append(record("a"))
            journal.append(record("b"))
            val lines = file.readLines()
            file.writeText(listOf(lines[0], lines[0], lines[1]).joinToString("\n", postfix = "\n"))

            assertTrue(runCatching { FileMemoryJournal(file).replay() }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun encryptedInternalDeletionFailsClosed() {
        val file = File.createTempFile("amper-chain-encrypted-delete", ".journal")
        val cipher = AesGcmMemoryLineCipher(
            SecretKeySpec(ByteArray(32) { index -> (index + 41).toByte() }, "AES"),
            "phase33-chain-key"
        )
        try {
            val journal = EncryptedFileMemoryJournal(file, cipher)
            journal.append(record("a"))
            journal.append(record("b"))
            journal.append(record("c"))
            val lines = file.readLines()
            file.writeText(listOf(lines[0], lines[2]).joinToString("\n", postfix = "\n"))

            val result = runCatching { EncryptedFileMemoryJournal(file, cipher).replay() }

            assertTrue(result.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun encryptedWriterAuthenticatesC1InsideE1() {
        val file = File.createTempFile("amper-chain-encrypted-format", ".journal")
        val cipher = AesGcmMemoryLineCipher(
            SecretKeySpec(ByteArray(32) { index -> (index + 61).toByte() }, "AES"),
            "phase33-format-key"
        )
        try {
            val expected = record("one")
            EncryptedFileMemoryJournal(file, cipher).append(expected)

            val envelope = file.readLines().single()
            assertTrue(envelope.startsWith("E1|"))
            val plaintext = cipher.decrypt(envelope)
            assertTrue(plaintext.startsWith("C1|"))
            val entry = MemoryJournalChainCodec.decode(plaintext).getOrThrow()
            assertEquals(1L, entry.sequence)
            assertEquals(MemoryJournalCodec.encodeRecord(expected), entry.payload)
        } finally {
            file.delete()
        }
    }
}
