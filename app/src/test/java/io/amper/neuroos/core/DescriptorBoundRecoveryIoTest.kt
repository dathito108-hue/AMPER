package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptorBoundRecoveryIoTest {
    @Test
    fun rewriteRecoveryLockSymlinkIsRejectedBeforeVictimMutation() {
        val dir = Files.createTempDirectory("amper-phase53-rewrite-lock").toFile()
        val target = File(dir, "memory.journal")
        val victim = File(dir, "victim-lock")
        val lock = DurableJournalIo.rewriteLockFileFor(target)
        try {
            target.writeText("old\n")
            victim.writeText("victim\n")
            Files.createSymbolicLink(lock.toPath(), victim.toPath())

            val failure = runCatching {
                DurableJournalIo.rewriteUtf8LinesWithoutAtomicMove(
                    target,
                    ".updating",
                    listOf("new")
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("old\n", target.readText())
            assertEquals("victim\n", victim.readText())
        } finally {
            Files.deleteIfExists(lock.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun replacementMarkerSymlinkIsRejectedBeforeRecoveryMutation() {
        val dir = Files.createTempDirectory("amper-phase53-marker-link").toFile()
        val target = File(dir, "memory.journal")
        val victim = File(dir, "victim-marker")
        val marker = DurableJournalIo.replacementMarkerFileFor(target)
        try {
            target.writeText("old\n")
            victim.writeText("R1|new\n")
            Files.createSymbolicLink(marker.toPath(), victim.toPath())

            val failure = runCatching {
                DurableJournalIo.recoverInterruptedReplace(target)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("old\n", target.readText())
            assertEquals("R1|new\n", victim.readText())
        } finally {
            Files.deleteIfExists(marker.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun descriptorBoundCopyRejectsSymlinkDestinationWithoutTouchingVictim() {
        val dir = Files.createTempDirectory("amper-phase53-copy-link").toFile()
        val source = File(dir, "source")
        val victim = File(dir, "victim")
        val destination = File(dir, "destination")
        try {
            source.writeText("replacement\n")
            victim.writeText("do-not-touch\n")
            Files.createSymbolicLink(destination.toPath(), victim.toPath())

            val failure = runCatching {
                DescriptorBoundFileIo.copyReplacing(source, destination)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("do-not-touch\n", victim.readText())
        } finally {
            Files.deleteIfExists(destination.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun fallbackRewriteUsesRegularBoundLockAndCleansTransactionSidecars() {
        val dir = Files.createTempDirectory("amper-phase53-fallback").toFile()
        val target = File(dir, "memory.journal")
        try {
            target.writeText("old\n")

            DurableJournalIo.rewriteUtf8LinesWithoutAtomicMove(
                target,
                ".updating",
                listOf("new")
            )

            assertEquals("new\n", target.readText())
            val lock = DurableJournalIo.rewriteLockFileFor(target)
            assertTrue(lock.isFile)
            assertFalse(Files.isSymbolicLink(lock.toPath()))
            assertFalse(DurableJournalIo.replacementMarkerFileFor(target).exists())
            assertTrue(
                dir.listFiles().orEmpty().none {
                    it.name.startsWith(target.name + ".replace-") &&
                        (it.name.endsWith(".backup") || it.name.endsWith(".staged"))
                }
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun descriptorBoundJournalReaderStillRepairsOnlyTornTail() {
        val dir = Files.createTempDirectory("amper-phase53-torn-tail").toFile()
        val target = File(dir, "memory.journal")
        try {
            target.writeText("one\ntwo\npartial")

            val lines = DurableJournalIo.readCompleteUtf8LinesRecoveringTornTail(target)

            assertEquals(listOf("one", "two"), lines)
            assertEquals("one\ntwo\n", target.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
