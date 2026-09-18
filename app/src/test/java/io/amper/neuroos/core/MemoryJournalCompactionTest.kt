package io.amper.neuroos.core

import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryJournalCompactionTest {
    private fun record(id: String, content: String = "payload-$id") = MemoryRecord(
        id = MemoryId(id),
        kind = "compaction-test",
        content = content,
        importance = 1.0,
        provenance = Provenance(source = "test", producer = "phase36", confidence = 1.0)
    )

    private fun cipher(seed: Int = 73): AesGcmMemoryLineCipher = AesGcmMemoryLineCipher(
        SecretKeySpec(ByteArray(32) { index -> (seed + index).toByte() }, "AES"),
        "phase36-compaction-key"
    )

    @Test
    fun plaintextCompactionPreservesLiveStateAndContinuesLineage() {
        val file = File.createTempFile("amper-compact-plain", ".journal")
        try {
            val journal = FileMemoryJournal(file)
            journal.append(record("a", "a-v1"))
            journal.append(record("b"))
            journal.append(record("c"))
            journal.tombstone(MemoryId("b"))
            journal.append(record("a", "a-v2"))

            val oldLines = file.readLines()
            val oldLast = MemoryJournalChainCodec.decode(oldLines.last()).getOrThrow()
            val oldHead = MemoryJournalChainState(oldLast.sequence, oldLast.digest)

            val removed = journal.compact()

            assertTrue(removed > 0)
            val compacted = file.readLines()
            assertTrue(MemoryJournalBaseCodec.isBase(compacted.first()))
            val segment = MemoryJournalSegmentCodec.decodePlaintext(compacted).getOrThrow()
            assertEquals(oldHead, segment.base)
            assertEquals(oldHead.sequence + 1, segment.entries.first().sequence)
            assertEquals(listOf("a", "c"), journal.replay().map { it.id.value }.sorted())
            assertEquals("a-v2", journal.replay().first { it.id.value == "a" }.content)

            journal.append(record("d"))
            val reopened = FileMemoryJournal(file)
            assertEquals(listOf("a", "c", "d"), reopened.replay().map { it.id.value }.sorted())
        } finally {
            file.delete()
            FileMemoryJournal.headFileFor(file).delete()
            FileMemoryJournal.lockFileFor(file).delete()
        }
    }

    @Test
    fun encryptedCompactionKeepsBaseAndSnapshotInsideE1() {
        val file = File.createTempFile("amper-compact-encrypted", ".journal")
        val cipher = cipher()
        try {
            val journal = EncryptedFileMemoryJournal(file, cipher)
            journal.append(record("a", "encrypted-a"))
            journal.append(record("b", "encrypted-b"))
            journal.append(record("c", "encrypted-c"))
            journal.tombstone(MemoryId("b"))
            journal.append(record("a", "encrypted-a-v2"))

            assertTrue(journal.compact() > 0)

            val stored = file.readLines()
            assertTrue(stored.all { it.startsWith("E1|") })
            assertFalse(file.readText().contains("encrypted-a-v2"))
            val plaintext = stored.map(cipher::decrypt)
            assertTrue(MemoryJournalBaseCodec.isBase(plaintext.first()))
            assertTrue(plaintext.drop(1).all(MemoryJournalChainCodec::isChained))
            val segment = MemoryJournalSegmentCodec.decodePlaintext(plaintext).getOrThrow()
            assertTrue(segment.base.sequence > 0)

            val reopened = EncryptedFileMemoryJournal(file, cipher)
            assertEquals(listOf("a", "c"), reopened.replay().map { it.id.value }.sorted())
            assertEquals("encrypted-a-v2", reopened.replay().first { it.id.value == "a" }.content)
            assertTrue(EncryptedFileMemoryJournal.headFileFor(file).readText().startsWith("E1|"))
        } finally {
            file.delete()
            EncryptedFileMemoryJournal.headFileFor(file).delete()
            EncryptedFileMemoryJournal.lockFileFor(file).delete()
        }
    }

    @Test
    fun compactedRewriteWithLaggingAnchorRecoversCrashWindow() {
        val file = File.createTempFile("amper-compact-crash-window", ".journal")
        try {
            val journal = FileMemoryJournal(file)
            val a = record("a")
            val b = record("b")
            val c = record("c")
            journal.append(a)
            journal.append(b)
            journal.append(c)
            journal.tombstone(b.id)

            val originalLines = file.readLines()
            val oldLast = MemoryJournalChainCodec.decode(originalLines.last()).getOrThrow()
            val oldHead = MemoryJournalChainState(oldLast.sequence, oldLast.digest)
            val anchorBefore = MemoryJournalHeadCodec.decode(
                FileMemoryJournal.headFileFor(file).readLines().single()
            ).getOrThrow()
            assertEquals(oldHead, anchorBefore)

            var state = oldHead
            val snapshot = listOf(a, c).map { live ->
                MemoryJournalChainCodec.encode(MemoryJournalCodec.encodeRecord(live), state).also {
                    state = MemoryJournalChainState(it.sequence, it.digest)
                }
            }
            DurableJournalIo.rewriteUtf8LinesAtomically(
                file,
                ".test-crash-compaction",
                listOf(MemoryJournalBaseCodec.encode(oldHead)) + snapshot.map { it.encoded }
            )
            // Intentionally do not update H1: this simulates death after journal replacement.

            val reopened = FileMemoryJournal(file)

            assertEquals(listOf("a", "c"), reopened.replay().map { it.id.value }.sorted())
            val anchorAfter = MemoryJournalHeadCodec.decode(
                FileMemoryJournal.headFileFor(file).readLines().single()
            ).getOrThrow()
            assertEquals(state, anchorAfter)
        } finally {
            file.delete()
            FileMemoryJournal.headFileFor(file).delete()
            FileMemoryJournal.lockFileFor(file).delete()
        }
    }

    @Test
    fun staleInstanceRefreshesAfterOtherInstanceCompacts() {
        val file = File.createTempFile("amper-compact-stale-instance", ".journal")
        try {
            val first = FileMemoryJournal(file)
            val stale = FileMemoryJournal(file)
            first.append(record("a"))
            first.append(record("b"))
            first.append(record("c"))
            first.tombstone(MemoryId("b"))
            first.append(record("a", "a-v2"))
            assertTrue(first.compact() > 0)

            stale.append(record("d"))

            assertEquals(
                listOf("a", "c", "d"),
                FileMemoryJournal(file).replay().map { it.id.value }.sorted()
            )
        } finally {
            file.delete()
            FileMemoryJournal.headFileFor(file).delete()
            FileMemoryJournal.lockFileFor(file).delete()
        }
    }

    @Test
    fun suffixRollbackAfterCompactionFailsClosedAgainstHeadAnchor() {
        val file = File.createTempFile("amper-compact-rollback", ".journal")
        try {
            val journal = FileMemoryJournal(file)
            journal.append(record("a"))
            journal.append(record("b"))
            journal.append(record("c"))
            journal.tombstone(MemoryId("b"))
            journal.append(record("a", "a-v2"))
            assertTrue(journal.compact() > 0)

            val compacted = file.readLines()
            assertTrue(compacted.size >= 3)
            file.writeText(compacted.dropLast(1).joinToString("\n", postfix = "\n"))

            val result = runCatching { FileMemoryJournal(file).replay() }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("rollback"))
        } finally {
            file.delete()
            FileMemoryJournal.headFileFor(file).delete()
            FileMemoryJournal.lockFileFor(file).delete()
        }
    }
}
