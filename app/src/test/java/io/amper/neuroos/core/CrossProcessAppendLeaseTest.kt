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

/** Phase 56 proves append serialization at both JVM and operating-system process boundaries. */
class CrossProcessAppendLeaseTest {
    @Test
    fun appendWaitsForForeignDescriptorLeaseThenContinues() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase56-cross-process-append").toFile()
        val target = File(dir, "memory.journal")
        val signal = File(dir, ".phase56-ready")
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            DurableJournalIo.ensureFileExistsDurably(target)
            child = startLeaseHolder(target, signal)
            waitForReady(child, signal)

            val append = executor.submit {
                DurableJournalIo.appendUtf8Line(target, "after-lease")
            }

            Thread.sleep(250L)
            assertFalse("append escaped while another process held the descriptor lease", append.isDone)

            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            append.get(10, TimeUnit.SECONDS)

            assertEquals(listOf("after-lease"), target.readLines())
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
    fun concurrentSameJvmAppendsAreSerializedWithoutOverlappingFileLocks() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase56-jvm-append").toFile()
        val target = File(dir, "memory.journal")
        val executor = Executors.newFixedThreadPool(8)
        try {
            DurableJournalIo.ensureFileExistsDurably(target)
            val count = 64
            val futures = (0 until count).map { index ->
                executor.submit {
                    DurableJournalIo.appendUtf8Line(target, "line-$index")
                }
            }
            futures.forEach { it.get(15, TimeUnit.SECONDS) }

            val lines = target.readLines()
            assertEquals(count, lines.size)
            assertEquals((0 until count).map { "line-$it" }.toSet(), lines.toSet())
        } finally {
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    private fun startLeaseHolder(target: File, signal: File): Process {
        val command = listOf(
            javaExecutable().absolutePath,
            "-cp",
            childClasspath(),
            CrossProcessAppendLeaseChild::class.java.name,
            target.absolutePath,
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
            "lease-holder child did not publish READY; " +
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
            CrossProcessAppendLeaseChild::class.java,
            DurableJournalIo::class.java,
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

/** Holds the exact target descriptor lock until the parent forcibly terminates this JVM. */
object CrossProcessAppendLeaseChild {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val target = File(args[0])
        val signal = File(args[1])
        require(java.nio.file.Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))

        FileChannel.open(
            target.toPath(),
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
