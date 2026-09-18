package io.amper.neuroos.core

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Phase 60 proves descriptor-bound metadata reads and digests wait behind exclusive writers. */
class MetadataReadLeaseTest {
    @Test
    fun utf8MetadataReadWaitsForForeignTargetLease() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase60-read").toFile()
        val target = File(dir, "memory.head")
        val signal = File(dir, ".phase60-ready")
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            DurableJournalIo.writeUtf8AndSync(target, "stable\n")
            child = startLeaseHolder(target, signal)
            waitForReady(child, signal)

            val read = executor.submit<String> { DescriptorBoundFileIo.readUtf8Text(target) }
            Thread.sleep(250L)
            assertFalse("metadata read escaped a foreign exclusive target lease", read.isDone)

            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals("stable\n", read.get(10, TimeUnit.SECONDS))
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
    fun digestWaitsForForeignTargetLeaseAndSeesStableBytes() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase60-digest").toFile()
        val target = File(dir, "memory.replace-staged")
        val signal = File(dir, ".phase60-ready")
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            val content = "digest-me\n"
            DurableJournalIo.writeUtf8AndSync(target, content)
            val expected = MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            child = startLeaseHolder(target, signal)
            waitForReady(child, signal)

            val digest = executor.submit<String> { DurableJournalIo.digestFile(target) }
            Thread.sleep(250L)
            assertFalse("digest escaped a foreign exclusive target lease", digest.isDone)

            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(expected, digest.get(10, TimeUnit.SECONDS))
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
