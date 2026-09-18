package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryEpochDescriptorIoTest {
    @Test
    fun authoritySymlinkFailsClosedWithoutTouchingVictim() {
        val dir = Files.createTempDirectory("amper-epoch-g3-link").toFile()
        val target = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(target)
        val victim = File(dir, "victim-g3")
        try {
            repeat(journal.compactEveryForTest()) { journal.advance() }
            val authority = journal.authorityFileForTest()
            victim.writeText("do-not-touch\n")
            assertTrue(authority.delete())
            Files.createSymbolicLink(authority.toPath(), victim.toPath())

            val failure = runCatching { RecoveryEpochJournal(target).current() }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertEquals("do-not-touch\n", victim.readText())
        } finally {
            Files.deleteIfExists(journal.authorityFileForTest().toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun activationSymlinkFailsClosedWithoutOverwritingVictim() {
        val dir = Files.createTempDirectory("amper-epoch-a1-link").toFile()
        val target = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(target)
        val victim = File(dir, "victim-a1")
        try {
            journal.advance()
            val activation = journal.activationFileForTest()
            victim.writeText("activation-victim\n")
            assertTrue(activation.delete())
            Files.createSymbolicLink(activation.toPath(), victim.toPath())

            val failure = runCatching { RecoveryEpochJournal(target).current() }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertEquals("activation-victim\n", victim.readText())
        } finally {
            Files.deleteIfExists(journal.activationFileForTest().toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun tornG1TailIsRepairedThroughDescriptorBoundSnapshot() {
        val dir = Files.createTempDirectory("amper-epoch-g1-torn").toFile()
        val target = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(target)
        try {
            journal.advance()
            journal.advance()
            val g1 = journal.fileForTest()
            g1.appendText("G1|torn")
            assertFalse(g1.readText().endsWith('\n'))

            assertEquals(2L, RecoveryEpochJournal(target).current())

            val repaired = g1.readText()
            assertTrue(repaired.endsWith('\n'))
            assertEquals(2, repaired.split('\n').dropLast(1).size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
