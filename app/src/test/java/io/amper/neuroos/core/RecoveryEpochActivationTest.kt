package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryEpochActivationTest {
    @Test
    fun emptyEpochFileBeforeFirstCommittedGenerationRemainsGenesis() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-preactivation").toFile()
        val target = File(dir, "memory.head")
        val epochFile = File(dir, target.name + ".recovery-epochs")
        try {
            assertTrue(epochFile.createNewFile())
            assertEquals(0L, epochFile.length())

            val journal = RecoveryEpochJournal(target)

            assertEquals(0L, journal.current())
            assertFalse(journal.activationFileForTest().exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun firstCommittedGenerationPublishesActivationWitness() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-activation").toFile()
        val target = File(dir, "memory.head")
        try {
            val journal = RecoveryEpochJournal(target)
            assertEquals(1L, journal.advance())

            assertTrue(journal.activationFileForTest().isFile)
            assertTrue(journal.activationFileForTest().readLines().single().startsWith("A1|active|"))
            assertEquals(1L, RecoveryEpochJournal(target).current())
        } finally {
            dir.deleteRecursively()
        }
    }
}
