package io.amper.neuroos.core

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecoveryEpochAuthorityLeaseTest {
    @Test
    fun currentWaitsForForeignAuthoritySetLeaseThenReadsStableEpoch() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase63-authority-lease").toFile()
        val target = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(target)
        val signal = File(dir, ".phase63-ready")
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            assertEquals(1L, journal.advance())
            val lockFile = journal.authorityLeaseFileForTest()
            assertTrue(lockFile.isFile)

            child = startLeaseHolder(lockFile, signal)
            waitForReady(child, signal)

            val read = executor.submit<Long> { RecoveryEpochJournal(target).current() }
            Thread.sleep(250L)
            assertFalse(
                "authority reader escaped while another process held the set lease",
                read.isDone
            )

            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(1L, read.get(10, TimeUnit.SECONDS))
        } finally {
            if (child?.isAlive == true) {
                child.destroyForcibly()
                child.waitFor(5, TimeUnit.SECONDS)
            }
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    @Test
    fun concurrentSameJvmAdvancesSerializeWithoutOverlappingFileLocks() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase63-jvm-authority").toFile()
        val target = File(dir, "memory.head")
        val executor = Executors.newFixedThreadPool(8)
        try {
            val count = 24
            val futures = (0 until count).map {
                executor.submit<Long> { RecoveryEpochJournal(target).advance() }
            }
            val epochs = futures.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(count, epochs.toSet().size)
            assertEquals((1L..count.toLong()).toSet(), epochs.toSet())
            assertEquals(count.toLong(), RecoveryEpochJournal(target).current())
        } finally {
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    private fun startLeaseHolder(lockFile: File, signal: File): Process {
        val command = listOf(
            javaExecutable().absolutePath,
            "-cp",
            childClasspath(),
            RecoveryEpochAuthorityLeaseChild::class.java.name,
            lockFile.absolutePath,
            signal.absolutePath
        )
        return ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
    }

    private fun waitForReady(process: Process, signal: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var observed: String? = null
        while (process.isAlive && System.nanoTime() < deadline) {
            if (signal.isFile) {
                observed = runCatching { signal.readText() }.getOrNull()
                if (observed == "READY\n") return
            }
            Thread.sleep(10L)
        }

        val output = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        fail(
            "authority-lease child did not publish READY; " +
                "signal=${observed?.replace("\n", "\\n")} " +
                "alive=${process.isAlive} output=$output"
        )
    }

    private fun childClasspath(): String {
        val entries = linkedSetOf<String>()
        System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.filter(String::isNotBlank)
            ?.forEach(entries::add)

        listOf(
            RecoveryEpochAuthorityLeaseChild::class.java,
            RecoveryEpochAuthorityLease::class.java,
            RecoveryEpochJournal::class.java,
            kotlin.Unit::class.java
        ).forEach { type ->
            val location = requireNotNull(type.protectionDomain?.codeSource?.location) {
                "missing code-source location for ${type.name}"
            }
            entries += File(location.toURI()).absolutePath
        }
        return entries.joinToString(File.pathSeparator)
    }

    private fun javaExecutable(): File {
        val bin = File(System.getProperty("java.home"), "bin")
        return listOf(File(bin, "java"), File(bin, "java.exe"))
            .firstOrNull(File::isFile)
            ?: error("unable to locate child JVM executable")
    }
}

object RecoveryEpochAuthorityLeaseChild {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val lockFile = File(args[0])
        val signal = File(args[1])
        require(java.nio.file.Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS))

        FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        ).use { channel ->
            channel.lock().use {
                FileOutputStream(signal, false).use { output ->
                    output.write("READY\n".toByteArray())
                    output.flush()
                    output.fd.sync()
                }
                DurableJournalIo.syncDirectory(requireNotNull(signal.parentFile))
                while (true) Thread.sleep(1_000L)
            }
        }
    }
}
