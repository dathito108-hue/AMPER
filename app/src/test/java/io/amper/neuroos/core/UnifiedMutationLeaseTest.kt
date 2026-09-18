package io.amper.neuroos.core

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Phase 57 proves append, rewrite and recovery share one stable mutation sidecar lease. */
class UnifiedMutationLeaseTest {
    @Test
    fun appendAndRewriteBlockBehindSameForeignMutationLease() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase57-cross-process-mutation").toFile()
        val target = File(dir, "memory.journal")
        val signal = File(dir, ".phase57-ready")
        val executor = Executors.newFixedThreadPool(2)
        var child: Process? = null
        try {
            DurableJournalIo.ensureFileExistsDurably(target)
            DurableJournalIo.writeUtf8AndSync(target, "seed\n")

            child = startMutationLeaseHolder(target, signal)
            waitForReady(child, signal)

            val appendStarted = CountDownLatch(1)
            val append = executor.submit {
                appendStarted.countDown()
                DurableJournalIo.appendUtf8Line(target, "append-before-rotation")
            }
            assertTrue(appendStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(150L)
            assertFalse("append escaped the foreign mutation barrier", append.isDone)

            val rewrite = executor.submit {
                DurableJournalIo.rewriteUtf8LinesAtomically(
                    target,
                    ".phase57-rotation",
                    listOf("rewritten")
                )
            }
            Thread.sleep(250L)
            assertFalse("rewrite escaped the foreign mutation barrier", rewrite.isDone)
            assertFalse("append unexpectedly completed while mutation lease was foreign", append.isDone)

            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))

            append.get(10, TimeUnit.SECONDS)
            rewrite.get(10, TimeUnit.SECONDS)
            assertEquals("rewritten\n", target.readText())
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
    fun sameJvmAppendAndRotationSerializeWithoutDetachedWriter() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase57-jvm-mutation").toFile()
        val target = File(dir, "memory.journal")
        val executor = Executors.newFixedThreadPool(2)
        try {
            DurableJournalIo.ensureFileExistsDurably(target)
            repeat(24) { round ->
                DurableJournalIo.rewriteUtf8LinesAtomically(
                    target,
                    ".phase57-reset",
                    listOf("seed-$round")
                )

                val start = CountDownLatch(1)
                val append = executor.submit {
                    start.await()
                    DurableJournalIo.appendUtf8Line(target, "append-$round")
                }
                val rewrite = executor.submit {
                    start.await()
                    DurableJournalIo.rewriteUtf8LinesAtomically(
                        target,
                        ".phase57-rotate",
                        listOf("rewrite-$round")
                    )
                }
                start.countDown()

                append.get(10, TimeUnit.SECONDS)
                rewrite.get(10, TimeUnit.SECONDS)

                val lines = target.readLines()
                val rewriteOnly = listOf("rewrite-$round")
                val rewriteThenAppend = listOf("rewrite-$round", "append-$round")
                assertTrue(
                    "round=$round final journal escaped serialized mutation outcomes: $lines",
                    lines == rewriteOnly || lines == rewriteThenAppend
                )
            }
        } finally {
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    @Test
    fun unifiedLeaseRetainsHistoricalRewriteLockPath() {
        val dir = java.nio.file.Files.createTempDirectory("amper-phase57-lock-name").toFile()
        try {
            val target = File(dir, "memory.journal")
            assertEquals(
                DurableJournalIo.rewriteLockFileFor(target),
                DurableJournalIo.mutationLockFileFor(target)
            )
            assertEquals("memory.journal.rewrite-lock", DurableJournalIo.mutationLockFileFor(target).name)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun startMutationLeaseHolder(target: File, signal: File): Process {
        val command = listOf(
            javaExecutable().absolutePath,
            "-cp",
            childClasspath(),
            UnifiedMutationLeaseChild::class.java.name,
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
            "mutation-lease child did not publish READY; " +
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
            UnifiedMutationLeaseChild::class.java,
            DurableJournalIo::class.java,
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

/** Holds the exact stable mutation sidecar until the parent forcibly terminates this JVM. */
object UnifiedMutationLeaseChild {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val target = File(args[0])
        val signal = File(args[1])
        val mutationLock = DurableJournalIo.mutationLockFileFor(target)

        DescriptorBoundFileIo.withExclusiveLock(mutationLock) {
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
