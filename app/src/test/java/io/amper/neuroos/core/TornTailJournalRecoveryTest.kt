package io.amper.neuroos.core

import java.io.File
import java.io.FileOutputStream
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TornTailJournalRecoveryTest {
    private fun record(id: String, content: String = "stable") = MemoryRecord(
        id = MemoryId(id),
        kind = "torn-tail-test",
        content = content,
        importance = 1.0,
        provenance = Provenance(source = "test", producer = "test", confidence = 1.0)
    )

    private fun appendRaw(file: File, value: String) {
        FileOutputStream(file, true).use { it.write(value.toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun plaintextReplayTruncatesOnlyUnterminatedFinalFragment() {
        val file = File.createTempFile("amper-torn-plain", ".journal")
        try {
            val journal = FileMemoryJournal(file)
            val stable = record("plain-stable")
            journal.append(stable)
            appendRaw(file, "C1|definitely-partial")
            assertFalse(file.readText().endsWith("\n"))

            val replayed = FileMemoryJournal(file).replay()

            assertEquals(listOf(stable.id), replayed.map { it.id })
            assertTrue(file.readText().endsWith("\n"))
            assertFalse(file.readText().contains("definitely-partial"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun plaintextCompleteCorruptionFailsClosed() {
        val file = File.createTempFile("amper-corrupt-plain", ".journal")
        try {
            FileMemoryJournal(file).append(record("plain-before-corruption"))
            val line = file.readLines().single()
            val last = line.last()
            val replacement = if (last == 'a') 'b' else 'a'
            file.writeText(line.dropLast(1) + replacement + "\n")

            val result = runCatching { FileMemoryJournal(file).replay() }

            assertTrue(result.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun encryptedReplayTruncatesUnterminatedCiphertextFragment() {
        val file = File.createTempFile("amper-torn-encrypted", ".journal")
        val cipher = testCipher()
        try {
            val stable = record("encrypted-stable", "encrypted-stable-payload")
            EncryptedFileMemoryJournal(file, cipher).append(stable)
            appendRaw(file, "E1|phase31-key|partial-envelope")
            assertFalse(file.readText().endsWith("\n"))

            val reopened = EncryptedFileMemoryJournal(file, cipher)
            val replayed = reopened.replay()

            assertEquals("encrypted-stable-payload", replayed.single().content)
            assertTrue(file.readText().endsWith("\n"))
            assertFalse(file.readText().contains("partial-envelope"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun encryptedCompleteAuthenticationCorruptionFailsClosedOnOpenOrReplay() {
        val file = File.createTempFile("amper-corrupt-encrypted", ".journal")
        val cipher = testCipher()
        try {
            EncryptedFileMemoryJournal(file, cipher).append(record("encrypted-corrupt"))
            val parts = file.readText().trimEnd('\n').split('|').toMutableList()
            val ciphertext = parts[3]
            val first = ciphertext.first()
            val replacement = if (first == 'A') 'B' else 'A'
            parts[3] = replacement + ciphertext.drop(1)
            file.writeText(parts.joinToString("|") + "\n")

            val result = runCatching {
                EncryptedFileMemoryJournal(file, cipher).replay()
            }

            assertTrue(result.isFailure)
        } finally {
            file.delete()
        }
    }

    private fun testCipher(): AesGcmMemoryLineCipher = AesGcmMemoryLineCipher(
        key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES"),
        keyId = "phase31-key"
    )
}
