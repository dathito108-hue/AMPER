package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase64RecoveryAuthorityNamespaceTest {
    @Test
    fun recoveryAuthoritySetLockNameCannotBecomeAnotherJournalRoot() {
        val dir = Files.createTempDirectory("amper-phase64-lock-namespace").toFile()
        try {
            val owner = File(dir, "memory")
            owner.writeText("owner\n")
            val collidingRoot = File(dir, "memory.recovery-epochs.lock")

            val failure = runCatching {
                SovereignPathIdentity.requireNamespaceRootName(collidingRoot)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("collides"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun symlinkedAuthoritySetLockFailsClosedWithoutTouchingVictim() {
        val dir = Files.createTempDirectory("amper-phase64-lock-symlink").toFile()
        val target = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(target)
        val victim = File(dir, "victim")
        try {
            assertEquals(1L, journal.advance())
            val lockFile = journal.authorityLeaseFileForTest()
            victim.writeText("do-not-touch\n")
            assertTrue(lockFile.delete())
            Files.createSymbolicLink(lockFile.toPath(), victim.toPath())

            val failure = runCatching { RecoveryEpochJournal(target).current() }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertEquals("do-not-touch\n", victim.readText())
        } finally {
            Files.deleteIfExists(journal.authorityLeaseFileForTest().toPath())
            dir.deleteRecursively()
        }
    }
}
