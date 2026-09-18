package io.amper.neuroos.core

import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEncryptionTest {
    private fun cipher(seed: Int, id: String = "test-key"): MemoryLineCipher {
        val key = ByteArray(32) { index -> (seed + index).toByte() }
        return AesGcmMemoryLineCipher(SecretKeySpec(key, "AES"), id)
    }

    @Test
    fun legacyPlaintextJournalMigratesAndReplays() {
        val dir = Files.createTempDirectory("amper-memory-migrate").toFile()
        val file = dir.resolve("memory.journal")
        val record = MemoryRecord(
            id = MemoryId("legacy-1"),
            kind = "episodic",
            content = "legacy sovereign memory",
            importance = 0.9,
            provenance = Provenance(source = "test", producer = "legacy")
        )
        file.writeText(MemoryJournalCodec.encodeRecord(record) + "\n")
        assertTrue(file.readLines().first().startsWith("R|"))

        val encrypted = EncryptedFileMemoryJournal(file, cipher(7))
        assertEquals(listOf(record), encrypted.replay())
        assertTrue(file.readLines().filter { it.isNotBlank() }.all { it.startsWith("E1|") })
        assertFalse(file.readText().lineSequence().any { it.startsWith("R|") || it.startsWith("D|") })
    }

    @Test
    fun encryptedJournalPreservesAppendAndTombstoneSemantics() {
        val dir = Files.createTempDirectory("amper-memory-roundtrip").toFile()
        val file = dir.resolve("memory.journal")
        val journal = EncryptedFileMemoryJournal(file, cipher(11))
        val alpha = MemoryRecord(
            id = MemoryId("alpha"), kind = "episodic", content = "alpha secret", importance = 0.7,
            provenance = Provenance(source = "test", producer = "phase10")
        )
        val beta = MemoryRecord(
            id = MemoryId("beta"), kind = "semantic", content = "beta secret", importance = 0.8,
            provenance = Provenance(source = "test", producer = "phase10")
        )

        journal.append(alpha)
        journal.append(beta)
        journal.tombstone(alpha.id)

        assertEquals(listOf(beta), journal.replay())
        val stored = file.readLines().filter { it.isNotBlank() }
        assertEquals(3, stored.size)
        assertTrue(stored.all { it.startsWith("E1|") })
        assertFalse(file.readText().contains("alpha secret"))
        assertFalse(file.readText().contains("beta secret"))
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        val dir = Files.createTempDirectory("amper-memory-tamper").toFile()
        val file = dir.resolve("memory.journal")
        val journal = EncryptedFileMemoryJournal(file, cipher(21))
        journal.append(
            MemoryRecord(
                id = MemoryId("tamper"), kind = "episodic", content = "authenticated memory", importance = 1.0,
                provenance = Provenance(source = "test", producer = "phase10")
            )
        )

        val parts = file.readLines().single().split('|').toMutableList()
        assertEquals(4, parts.size)
        assertTrue(parts[3].isNotEmpty())
        val replacement = if (parts[3].first() == 'A') 'B' else 'A'
        parts[3] = replacement + parts[3].drop(1)
        file.writeText(parts.joinToString("|") + "\n")

        assertTrue(runCatching { journal.replay() }.isFailure)
    }

    @Test
    fun wrongKeyFailsClosedOnOpenOrReplay() {
        val dir = Files.createTempDirectory("amper-memory-key").toFile()
        val file = dir.resolve("memory.journal")
        EncryptedFileMemoryJournal(file, cipher(31)).append(
            MemoryRecord(
                id = MemoryId("keyed"), kind = "semantic", content = "bound to encryption key", importance = 0.8,
                provenance = Provenance(source = "test", producer = "phase10")
            )
        )

        val result = runCatching {
            EncryptedFileMemoryJournal(file, cipher(32)).replay()
        }
        assertTrue(result.isFailure)
    }
}
