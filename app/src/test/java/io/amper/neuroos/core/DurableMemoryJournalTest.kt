package io.amper.neuroos.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableMemoryJournalTest {
    private fun record(id: String, content: String = "durable-content") = MemoryRecord(
        id = MemoryId(id),
        kind = "durability-test",
        content = content,
        importance = 1.0,
        provenance = Provenance(
            source = "durability-test",
            producer = "durability-test",
            confidence = 1.0
        )
    )

    @Test
    fun plaintextJournalReplaysDurableAppendAndTombstoneAfterReopen() {
        val file = File.createTempFile("amper-memory", ".journal")
        try {
            val first = FileMemoryJournal(file)
            val kept = record("durable-kept", "kept")
            val removed = record("durable-removed", "removed")
            first.append(kept)
            first.append(removed)
            first.tombstone(removed.id)

            val reopened = FileMemoryJournal(file)
            val replayed = reopened.replay()

            assertEquals(listOf(kept.id), replayed.map { it.id })
            assertEquals("kept", replayed.single().content)
        } finally {
            file.delete()
        }
    }

    @Test
    fun encryptedJournalDurableAppendRemainsEncryptedAndReplaysAfterReopen() {
        val file = File.createTempFile("amper-encrypted-memory", ".journal")
        val cipher = TestLineCipher()
        try {
            val journal = EncryptedFileMemoryJournal(file, cipher)
            val secret = record("encrypted-durable", "secret-durable-payload")
            journal.append(secret)

            val stored = file.readText(Charsets.UTF_8)
            assertTrue(stored.lineSequence().filter { it.isNotBlank() }.all { it.startsWith("E1|") })
            assertFalse(stored.contains("secret-durable-payload"))

            val reopened = EncryptedFileMemoryJournal(file, cipher)
            assertEquals("secret-durable-payload", reopened.replay().single().content)
        } finally {
            file.delete()
        }
    }

    @Test
    fun failedJournalAppendDoesNotPublishRecordToMemoryIndex() {
        val journal = object : MemoryJournal {
            override fun append(record: MemoryRecord) {
                error("simulated durable append failure")
            }

            override fun tombstone(id: MemoryId) = Unit
            override fun replay(): List<MemoryRecord> = emptyList()
        }
        val memory = PersistentMemoryOs(journal)
        val candidate = record("failed-durable-claim")

        val result = runCatching { memory.rememberIfAbsent(candidate) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("durable append failure"))
        assertNull(memory.get(candidate.id))
        assertEquals(0, memory.size())
    }

    private class TestLineCipher : MemoryLineCipher {
        override val keyId: String = "test-durable-key"

        override fun encrypt(plaintext: String): String =
            "E1|test|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plaintext.toByteArray(StandardCharsets.UTF_8))

        override fun decrypt(envelope: String): String {
            val parts = envelope.split('|')
            require(parts.size == 3 && parts[0] == "E1" && parts[1] == "test")
            return String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8)
        }
    }
}
