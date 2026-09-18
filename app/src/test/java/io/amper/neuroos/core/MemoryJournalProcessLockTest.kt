package io.amper.neuroos.core

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryJournalProcessLockTest {
    private fun record(id: String) = MemoryRecord(
        id = MemoryId(id),
        kind = "process-lock-test",
        content = "payload-$id",
        importance = 1.0,
        provenance = Provenance(source = "test", producer = "phase35", confidence = 1.0)
    )

    @Test
    fun twoPlaintextJournalInstancesSerializeConcurrentAppends() {
        val file = File.createTempFile("amper-process-lock", ".journal")
        val head = FileMemoryJournal.headFileFor(file)
        val lock = FileMemoryJournal.lockFileFor(file)
        try {
            val first = FileMemoryJournal(file)
            val second = FileMemoryJournal(file)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val a = pool.submit<Unit> {
                    start.await()
                    first.append(record("a"))
                }
                val b = pool.submit<Unit> {
                    start.await()
                    second.append(record("b"))
                }
                start.countDown()
                a.get(10, TimeUnit.SECONDS)
                b.get(10, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }

            val replayed = FileMemoryJournal(file).replay()
            assertEquals(setOf("a", "b"), replayed.map { it.id.value }.toSet())
            val entries = MemoryJournalChainCodec.decodeSequence(file.readLines()).getOrThrow()
            assertEquals(listOf(1L, 2L), entries.map { it.sequence })
            assertTrue(lock.exists())
        } finally {
            file.delete()
            head.delete()
            lock.delete()
        }
    }

    @Test
    fun stalePlaintextInstanceRefreshesHeadBeforeNextAppend() {
        val file = File.createTempFile("amper-stale-instance", ".journal")
        val head = FileMemoryJournal.headFileFor(file)
        val lock = FileMemoryJournal.lockFileFor(file)
        try {
            val stale = FileMemoryJournal(file)
            val other = FileMemoryJournal(file)
            other.append(record("first"))
            stale.append(record("second"))

            val entries = MemoryJournalChainCodec.decodeSequence(file.readLines()).getOrThrow()
            assertEquals(listOf(1L, 2L), entries.map { it.sequence })
            assertEquals(setOf("first", "second"), stale.replay().map { it.id.value }.toSet())
        } finally {
            file.delete()
            head.delete()
            lock.delete()
        }
    }

    @Test
    fun twoEncryptedJournalInstancesSerializeConcurrentAppends() {
        val file = File.createTempFile("amper-process-lock-encrypted", ".journal")
        val head = EncryptedFileMemoryJournal.headFileFor(file)
        val lock = EncryptedFileMemoryJournal.lockFileFor(file)
        val key = SecretKeySpec(ByteArray(32) { index -> (index + 111).toByte() }, "AES")
        val firstCipher = AesGcmMemoryLineCipher(key, "phase35-process-key")
        val secondCipher = AesGcmMemoryLineCipher(key, "phase35-process-key")
        try {
            val first = EncryptedFileMemoryJournal(file, firstCipher)
            val second = EncryptedFileMemoryJournal(file, secondCipher)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val a = pool.submit<Unit> {
                    start.await()
                    first.append(record("a"))
                }
                val b = pool.submit<Unit> {
                    start.await()
                    second.append(record("b"))
                }
                start.countDown()
                a.get(10, TimeUnit.SECONDS)
                b.get(10, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }

            val replayed = EncryptedFileMemoryJournal(file, firstCipher).replay()
            assertEquals(setOf("a", "b"), replayed.map { it.id.value }.toSet())
            val chainLines = file.readLines().map(firstCipher::decrypt)
            val entries = MemoryJournalChainCodec.decodeSequence(chainLines).getOrThrow()
            assertEquals(listOf(1L, 2L), entries.map { it.sequence })
            assertTrue(lock.exists())
        } finally {
            file.delete()
            head.delete()
            lock.delete()
        }
    }
}
