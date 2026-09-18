package io.amper.neuroos.core

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Phase 59 proves generic replacing writes and force operations join the target lease. */
class ReplacingWriteLeaseTest {
    @Test
    fun replacingWriteWaitsForForeignTargetDescriptorLease() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase59-write").toFile()
        val target = File(dir, "memory.head")
        val signal = File(dir, ".phase59-ready")
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            DurableJournalIo.writeUtf8AndSync(target, "before\n")
            child = startLeaseHolder(target, signal)
            waitForReady(child, signal)

            val write = executor.submit {
                DurableJournalIo.writeUtf8AndSync(target, "after\n")
            }
            Thread.sleep(250L)
            assertFalse("replacing write escaped a foreign target lease", write.isDone)

            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            write.get(10, TimeUnit.SECONDS)
            assertEquals("after\n", target.readText())
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
    fun forceWaitsForForeignTargetDescriptorLease() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase59-force").toFile()
        val target = File(dir, "memory.keys")
        val signal = File(dir, ".phase59-ready")
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            DurableJournalIo.writeUtf8AndSync(target, "stable\n")
            child = startLeaseHolder(target, signal)
            waitForReady(child, signal)

            val force = executor.submit { DurableJournalIo.syncFile(target) }
            Thread.sleep(250L)
            assertFalse("force escaped a foreign target lease", force.isDone)

            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            force.get(10, TimeUnit.SECONDS)
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

    private fun startLeaseHolder(target: File, signal: File): Process {
        val command = listOf(
            javaExecutable().absolutePath,
            "-cp",
            childClasspath(),
            SnapshotReadLeaseChild::class.java.name,
            target.absolutePath,
            signal.absolutePath
        )
        return ProcessBuilder(command).redirectErrorStream(true).start()
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
        fail("lease-holder child did not publish READY; signal=$observed alive=${process.isAlive} output=$output")
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
