package io.amper.neuroos.core

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Phase 61 proves unlink joins the same exact-target descriptor lease as reads and writes. */
class IdentityBoundDeleteLeaseTest {
    @Test
    fun deleteWaitsForForeignTargetDescriptorLeaseThenUnlinksExpectedObject() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase61-delete").toFile()
        val target = File(dir, "memory.replace-pending")
        val signal = File(dir, ".phase61-ready")
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            DurableJournalIo.writeUtf8AndSync(target, "marker\n")
            val identity = SovereignPathIdentity.snapshot(target)
            child = startLeaseHolder(target, signal)
            waitForReady(child, signal)

            val deletion = executor.submit<Boolean> {
                DescriptorBoundFileIo.deleteIfExistsBound(target, identity)
            }
            Thread.sleep(250L)
            assertFalse("identity-bound delete crossed a foreign target lease", deletion.isDone)
            assertTrue(target.exists())

            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertTrue(deletion.get(10, TimeUnit.SECONDS))
            assertFalse(target.exists())
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
            DescriptorBoundFileIo::class.java,
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
