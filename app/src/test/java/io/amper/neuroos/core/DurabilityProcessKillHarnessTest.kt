package io.amper.neuroos.core

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 48 complements the deterministic Phase 47 state matrix with real JVM termination.
 * A child process executes each durable prefix, persists a READY witness, blocks at the exact
 * boundary, and is then forcibly terminated by the parent. Recovery always happens in a fresh
 * process context rather than by unwinding the writer's stack or running shutdown hooks.
 */
class DurabilityProcessKillHarnessTest {
    @Test
    fun forcedProcessKillAcrossExistingReplacementBoundariesRecoversAtCommitPoint() {
        DurabilityProcessKillChild.ReplaceBoundary.entries.forEach { boundary ->
            val dir = createDurableTempDirectory("amper-phase48-existing-${boundary.name.lowercase()}")
            val target = File(dir, "memory.head")
            val signal = File(dir, ".phase48-ready")
            try {
                DurableJournalIo.writeUtf8AndSync(target, "old\n")
                DurableJournalIo.syncDirectory(dir)

                runChildUntilBoundaryAndKill(
                    listOf("replace-existing", boundary.name, target.absolutePath, signal.absolutePath),
                    signal
                )

                DurableJournalIo.recoverInterruptedReplace(target)

                val expected = if (boundary == DurabilityProcessKillChild.ReplaceBoundary.MARKER_COMMITTED) {
                    "new\n"
                } else {
                    "old\n"
                }
                assertEquals("boundary=$boundary", expected, target.readText())
                assertEquals("boundary=$boundary", 1L, DurableJournalIo.currentReplacementEpoch(target))
                assertTrue("boundary=$boundary", replacementSidecars(target).isEmpty())

                DurableJournalIo.rewriteUtf8LinesWithoutAtomicMove(target, ".phase48-retry", listOf("next"))
                assertEquals("next\n", target.readText())
                assertEquals(2L, DurableJournalIo.currentReplacementEpoch(target))
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun forcedProcessKillAcrossNewTargetReplacementBoundariesRecoversAtCommitPoint() {
        val boundaries = DurabilityProcessKillChild.ReplaceBoundary.entries.filter {
            it != DurabilityProcessKillChild.ReplaceBoundary.BACKUP_DURABLE
        }
        boundaries.forEach { boundary ->
            val dir = createDurableTempDirectory("amper-phase48-new-${boundary.name.lowercase()}")
            val target = File(dir, "memory.head")
            val signal = File(dir, ".phase48-ready")
            try {
                runChildUntilBoundaryAndKill(
                    listOf("replace-new", boundary.name, target.absolutePath, signal.absolutePath),
                    signal
                )

                DurableJournalIo.recoverInterruptedReplace(target)

                if (boundary == DurabilityProcessKillChild.ReplaceBoundary.MARKER_COMMITTED) {
                    assertTrue("boundary=$boundary", target.isFile)
                    assertEquals("new\n", target.readText())
                } else {
                    assertFalse("boundary=$boundary", target.exists())
                }
                assertEquals("boundary=$boundary", 1L, DurableJournalIo.currentReplacementEpoch(target))
                assertTrue("boundary=$boundary", replacementSidecars(target).isEmpty())

                DurableJournalIo.rewriteUtf8LinesWithoutAtomicMove(target, ".phase48-retry", listOf("next"))
                assertEquals("next\n", target.readText())
                assertEquals(2L, DurableJournalIo.currentReplacementEpoch(target))
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun forcedProcessKillAcrossSecondCompactionRotationBoundariesRecoversAndAdvances() {
        val preDir = createDurableTempDirectory("amper-phase48-compaction-pre")
        val completedDir = createDurableTempDirectory("amper-phase48-compaction-complete")
        try {
            val preTarget = File(preDir, "memory.head")
            val preJournal = RecoveryEpochJournal(preTarget)
            val bound = preJournal.compactEveryForTest()
            val expectedEpoch = (bound * 2).toLong()
            repeat(bound * 2 - 1) { preJournal.advance() }
            assertEquals(expectedEpoch - 1L, preJournal.current())

            val completedTarget = File(completedDir, "memory.head")
            val completedJournal = RecoveryEpochJournal(completedTarget)
            repeat(bound * 2) { completedJournal.advance() }
            assertEquals(expectedEpoch, completedJournal.current())

            val preSnapshot = snapshotFiles(preDir)
            DurabilityProcessKillChild.CompactionBoundary.entries.forEach { boundary ->
                val scenarioDir = createDurableTempDirectory(
                    "amper-phase48-compaction-${boundary.name.lowercase()}"
                )
                val scenarioTarget = File(scenarioDir, "memory.head")
                val signal = File(scenarioDir, ".phase48-ready")
                try {
                    restoreFilesDurably(preSnapshot, scenarioDir)

                    runChildUntilBoundaryAndKill(
                        listOf(
                            "compact",
                            boundary.name,
                            scenarioTarget.absolutePath,
                            signal.absolutePath,
                            completedDir.absolutePath,
                            expectedEpoch.toString()
                        ),
                        signal
                    )

                    val restarted = RecoveryEpochJournal(scenarioTarget)
                    assertEquals("boundary=$boundary", expectedEpoch, restarted.current())
                    assertEquals("boundary=$boundary", expectedEpoch + 1L, restarted.advance())
                    assertEquals(expectedEpoch + 1L, RecoveryEpochJournal(scenarioTarget).current())
                } finally {
                    scenarioDir.deleteRecursively()
                }
            }
        } finally {
            preDir.deleteRecursively()
            completedDir.deleteRecursively()
        }
    }

    private fun runChildUntilBoundaryAndKill(arguments: List<String>, signal: File) {
        if (signal.exists()) assertTrue(signal.delete())
        val javaExecutable = javaExecutable()
        val command = buildList {
            add(javaExecutable.absolutePath)
            add("-cp")
            add(childClasspath())
            add(DurabilityProcessKillChild::class.java.name)
            addAll(arguments)
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var observedSignal: String? = null
        while (process.isAlive && System.nanoTime() < deadline) {
            if (signal.isFile) {
                observedSignal = runCatching { signal.readText() }.getOrNull()
                if (observedSignal == "READY\n") break
            }
            Thread.sleep(10L)
        }

        if (observedSignal != "READY\n") {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            val output = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
            fail(
                "child did not publish complete durable boundary witness; " +
                    "signal=${observedSignal?.replace("\n", "\\n")} " +
                    "exit=${runCatching { process.exitValue() }.getOrNull()} output=$output"
            )
        }
        assertEquals("READY\n", observedSignal)
        assertTrue("child must still be blocked at boundary", process.isAlive)

        process.destroyForcibly()
        assertTrue("child did not terminate after destroyForcibly", process.waitFor(10, TimeUnit.SECONDS))
        assertFalse("child unexpectedly survived forcible termination", process.isAlive)
        assertTrue("forcibly killed child must not report successful exit", process.exitValue() != 0)
    }

    private fun childClasspath(): String {
        val entries = linkedSetOf<String>()
        System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.filter(String::isNotBlank)
            ?.forEach(entries::add)

        listOf(
            DurabilityProcessKillChild::class.java,
            DurableJournalIo::class.java,
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

    private fun createDurableTempDirectory(prefix: String): File {
        val directory = java.nio.file.Files.createTempDirectory(prefix).toFile()
        DurableJournalIo.syncDirectory(requireNotNull(directory.parentFile))
        return directory
    }

    private fun replacementSidecars(target: File): List<File> =
        target.parentFile.listFiles().orEmpty().filter { it.name.startsWith(target.name + ".replace-") }

    private fun snapshotFiles(directory: File): Map<String, ByteArray> =
        directory.listFiles().orEmpty()
            .filter { it.isFile }
            .associate { it.name to it.readBytes() }

    private fun restoreFilesDurably(snapshot: Map<String, ByteArray>, directory: File) {
        snapshot.forEach { (name, bytes) ->
            val destination = File(directory, name)
            FileOutputStream(destination, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
        }
        DurableJournalIo.syncDirectory(directory)
    }
}
