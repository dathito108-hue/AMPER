package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase65RecoveryConcurrencySoakTest {
    @Test
    fun concurrentReadersAndAdvancersCrossCompactionBoundaryWithoutMixedAuthoritySnapshots() {
        val dir = Files.createTempDirectory("amper-phase65-recovery-soak").toFile()
        val target = File(dir, "memory.head")
        val writerCount = 72
        val executor = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        val writersDone = AtomicBoolean(false)
        val observed = Collections.synchronizedList(mutableListOf<Long>())
        try {
            val writers = (0 until writerCount).map {
                executor.submit<Long> {
                    start.await()
                    RecoveryEpochJournal(target).advance()
                }
            }
            val readers = (0 until 4).map {
                executor.submit {
                    start.await()
                    while (!writersDone.get()) {
                        observed += RecoveryEpochJournal(target).current()
                        Thread.yield()
                    }
                    observed += RecoveryEpochJournal(target).current()
                }
            }

            start.countDown()
            val epochs = writers.map { it.get(45, TimeUnit.SECONDS) }
            writersDone.set(true)
            readers.forEach { it.get(20, TimeUnit.SECONDS) }

            assertEquals(writerCount, epochs.toSet().size)
            assertEquals((1L..writerCount.toLong()).toSet(), epochs.toSet())
            assertTrue(observed.all { it in 0L..writerCount.toLong() })

            val restarted = RecoveryEpochJournal(target)
            assertEquals(writerCount.toLong(), restarted.current())
            assertTrue(restarted.authorityLeaseFileForTest().isFile)
            assertTrue(restarted.activationFileForTest().isFile)
            assertTrue(restarted.authorityFileForTest().isFile)
            restarted.checkpointFilesForTest().forEach { assertTrue(it.isFile) }

            val tailLines = DurableJournalIo.readCompleteUtf8LinesRecoveringTornTail(restarted.fileForTest())
            assertEquals(writerCount - restarted.compactEveryForTest(), tailLines.size)
        } finally {
            writersDone.set(true)
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }
}
