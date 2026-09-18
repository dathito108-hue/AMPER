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

/** Phase 58 exercises the shared exact-target lease from fresh JVMs. */
class SnapshotReadLeaseTest {
    @Test
    fun tornTailSnapshotWaitsForForeignTargetLeaseThenRepairsSameObject() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase58-snapshot").toFile()
        val target = File(dir, "memory.journal")
        val signal = File(dir, ".phase58-ready")
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            DurableJournalIo.writeUtf8AndSync(target, "stable\ntorn")
            DurableJournalIo.syncDirectory(dir)
            child = startTargetLeaseHolder(target, signal)
            waitForReady(child, signal)

            val read = executor.submit<List<String>> {
                DurableJournalIo.readCompleteUtf8LinesRecoveringTornTail(target)
            }
            Thread.sleep(250L)
            assertFalse("snapshot escaped while a foreign process held the target descriptor", read.isDone)

            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(listOf("stable"), read.get(10, TimeUnit.SECONDS))
            assertEquals("stable\n", target.readText())
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
    fun atomicRotationWaitsForForeignTargetLeaseBeforePublication() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase58-rotation").toFile()
        val target = File(dir, "memory.journal")
        val signal = File(dir, ".phase58-ready")
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            DurableJournalIo.writeUtf8AndSync(target, "old\n")
            DurableJournalIo.syncDirectory(dir)
            child = startTargetLeaseHolder(target, signal)
            waitForReady(child, signal)

            val rewrite = executor.submit {
                DurableJournalIo.rewriteUtf8LinesAtomically(target, ".phase58", listOf("new"))
            }
            Thread.sleep(250L)
            assertFalse("rotation crossed a foreign target descriptor lease", rewrite.isDone)

            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            rewrite.get(10, TimeUnit.SECONDS)
            assertEquals("new\n", target.readText())
        } finally {
            if (child?.isAlive == true) {
                child.destroyForcibly()
                child.waitFor(5, TimeUnit.SECONDS)
            }
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    private fun startTargetLeaseHolder(target: File, signal: File): Process {
        val command = listOf(
            javaExecutable().absolutePath,
            "-cp",
            childClasspath(),
            SnapshotReadLeaseChild::class.java.name,
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
        fail("target-lease child did not publish READY; signal=$observed alive=${process.isAlive} output=$output")
    }

    private fun childClasspath(): String {
        val entries = linkedSetOf<String>()
        System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.filter(String::isNotBlank)
            ?.forEach(entries::add)
        listOf(
            SnapshotReadLeaseChild::class.java,
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

object SnapshotReadLeaseChild {
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
