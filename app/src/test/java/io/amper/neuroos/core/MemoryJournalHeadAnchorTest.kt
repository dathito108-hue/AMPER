package io.amper.neuroos.core

import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryJournalHeadAnchorTest {
    private fun record(id: String) = MemoryRecord(
        id = MemoryId(id),
        kind = "head-anchor-test",
        content = "payload-$id",
        importance = 1.0,
        provenance = Provenance(source = "test", producer = "phase34", confidence = 1.0)
    )

    @Test
    fun plaintextSuffixRollbackFailsClosedWhenAnchorSurvives() {
        val file = File.createTempFile("amper-head-rollback", ".journal")
        val head = FileMemoryJournal.headFileFor(file)
        try {
            val journal = FileMemoryJournal(file)
            journal.append(record("a"))
            journal.append(record("b"))
            journal.append(record("c"))
            val lines = file.readLines()
            assertEquals(3, lines.size)
            file.writeText(lines.take(2).joinToString("\n", postfix = "\n"))

            val result = runCatching { FileMemoryJournal(file).replay() }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("rollback detected"))
        } finally {
            file.delete()
            head.delete()
        }
    }

    @Test
    fun verifiedJournalExtensionAdvancesLaggingAnchorAfterCrashWindow() {
        val file = File.createTempFile("amper-head-extension", ".journal")
        val head = FileMemoryJournal.headFileFor(file)
        try {
            val journal = FileMemoryJournal(file)
            journal.append(record("a"))
            val oldAnchor = head.readText()
            journal.append(record("b"))

            // Simulate a process crash after the second journal line became durable but
            // before its head-anchor replacement: restore the previously durable anchor.
            head.writeText(oldAnchor)

            val reopened = FileMemoryJournal(file)
            assertEquals(listOf("a", "b"), reopened.replay().map { it.id.value })
            val advanced = MemoryJournalHeadCodec.decode(head.readText().trimEnd('\n')).getOrThrow()
            assertEquals(2L, advanced.sequence)
        } finally {
            file.delete()
            head.delete()
        }
    }

    @Test
    fun phase33JournalWithoutAnchorBootstrapsCurrentVerifiedHead() {
        val file = File.createTempFile("amper-head-bootstrap", ".journal")
        val head = FileMemoryJournal.headFileFor(file)
        try {
            val a = record("a")
            val b = record("b")
            val first = MemoryJournalChainCodec.encode(
                MemoryJournalCodec.encodeRecord(a),
                MemoryJournalChainState.GENESIS
            )
            val second = MemoryJournalChainCodec.encode(
                MemoryJournalCodec.encodeRecord(b),
                MemoryJournalChainState(first.sequence, first.digest)
            )
            file.writeText(listOf(first.encoded, second.encoded).joinToString("\n", postfix = "\n"))
            assertFalse(head.exists())

            val reopened = FileMemoryJournal(file)

            assertEquals(listOf(a, b), reopened.replay())
            val anchored = MemoryJournalHeadCodec.decode(head.readText().trimEnd('\n')).getOrThrow()
            assertEquals(2L, anchored.sequence)
            assertEquals(second.digest, anchored.digest)
        } finally {
            file.delete()
            head.delete()
        }
    }

    @Test
    fun encryptedHeadAnchorIsAuthenticatedAndSuffixRollbackFailsClosed() {
        val file = File.createTempFile("amper-head-encrypted", ".journal")
        val head = EncryptedFileMemoryJournal.headFileFor(file)
        val cipher = AesGcmMemoryLineCipher(
            SecretKeySpec(ByteArray(32) { index -> (index + 71).toByte() }, "AES"),
            "phase34-head-key"
        )
        try {
            val journal = EncryptedFileMemoryJournal(file, cipher)
            journal.append(record("a"))
            journal.append(record("b"))

            val envelope = head.readLines().single()
            assertTrue(envelope.startsWith("E1|"))
            val state = MemoryJournalHeadCodec.decode(cipher.decrypt(envelope)).getOrThrow()
            assertEquals(2L, state.sequence)

            val journalLines = file.readLines()
            file.writeText(journalLines.take(1).joinToString("\n", postfix = "\n"))

            val result = runCatching { EncryptedFileMemoryJournal(file, cipher).replay() }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("rollback detected"))
        } finally {
            file.delete()
            head.delete()
        }
    }

    @Test
    fun tamperedEncryptedHeadAnchorFailsClosed() {
        val file = File.createTempFile("amper-head-anchor-tamper", ".journal")
        val head = EncryptedFileMemoryJournal.headFileFor(file)
        val cipher = AesGcmMemoryLineCipher(
            SecretKeySpec(ByteArray(32) { index -> (index + 91).toByte() }, "AES"),
            "phase34-anchor-tamper-key"
        )
        try {
            EncryptedFileMemoryJournal(file, cipher).append(record("a"))
            val line = head.readLines().single()
            val replacement = if (line.last() == 'A') 'B' else 'A'
            head.writeText(line.dropLast(1) + replacement + "\n")

            assertTrue(runCatching { EncryptedFileMemoryJournal(file, cipher).replay() }.isFailure)
        } finally {
            file.delete()
            head.delete()
        }
    }
}
