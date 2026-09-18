package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryJournalFrameTest {
    private fun record(id: String, content: String = "framed") = MemoryRecord(
        id = MemoryId(id),
        kind = "frame-test",
        content = content,
        importance = 1.0,
        provenance = Provenance(source = "test", producer = "phase32", confidence = 1.0)
    )

    @Test
    fun plaintextWriterUsesC1ContainingF1AndRoundTrips() {
        val file = File.createTempFile("amper-frame", ".journal")
        try {
            val expected = record("frame-roundtrip", "payload-v1")
            val journal = FileMemoryJournal(file)
            journal.append(expected)

            val stored = file.readLines().single()
            assertTrue(stored.startsWith("C1|"))
            val entry = MemoryJournalChainCodec.decode(stored).getOrThrow()
            assertEquals(1L, entry.sequence)
            assertEquals(MemoryJournalCodec.encodeRecord(expected), entry.payload)
            assertEquals(listOf(expected), journal.replay())
        } finally {
            file.delete()
        }
    }

    @Test
    fun framedPayloadCorruptionFailsClosed() {
        val payload = MemoryJournalCodec.encodeRecord(record("frame-corrupt"))
        val frame = MemoryJournalFrameCodec.encode(payload)
        val parts = frame.split('|').toMutableList()
        assertEquals(4, parts.size)
        parts[2] = "0".repeat(64)

        val result = MemoryJournalFrameCodec.decode(parts.joinToString("|"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("digest mismatch"))
    }

    @Test
    fun plaintextReplayStillAcceptsAndMigratesLegacyRawRecord() {
        val file = File.createTempFile("amper-frame-legacy", ".journal")
        try {
            val expected = record("frame-legacy")
            file.writeText(MemoryJournalCodec.encodeRecord(expected) + "\n")

            val journal = FileMemoryJournal(file)

            assertEquals(listOf(expected), journal.replay())
            assertTrue(file.readLines().single().startsWith("C1|"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyEncryptedRawPayloadMigratesToEncryptedC1F1() {
        val dir = Files.createTempDirectory("amper-frame-e1-migrate").toFile()
        val file = dir.resolve("memory.journal")
        val key = SecretKeySpec(ByteArray(32) { index -> (index + 17).toByte() }, "AES")
        val cipher = AesGcmMemoryLineCipher(key, "phase32-key")
        val expected = record("legacy-e1", "legacy encrypted payload")
        val legacyEnvelope = cipher.encrypt(MemoryJournalCodec.encodeRecord(expected))
        file.writeText(legacyEnvelope + "\n")

        val journal = EncryptedFileMemoryJournal(file, cipher)

        assertEquals(listOf(expected), journal.replay())
        val migratedEnvelope = file.readLines().single()
        assertTrue(migratedEnvelope.startsWith("E1|"))
        val migratedPlaintext = cipher.decrypt(migratedEnvelope)
        assertTrue(migratedPlaintext.startsWith("C1|"))
        val entry = MemoryJournalChainCodec.decode(migratedPlaintext).getOrThrow()
        assertEquals(1L, entry.sequence)
        assertEquals(MemoryJournalCodec.encodeRecord(expected), entry.payload)
    }
}
