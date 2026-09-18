package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryEpochJournalTest {
    @Test
    fun fallbackReplacementAdvancesDurableEpochMonotonically() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-fallback").toFile()
        val file = File(dir, "memory.head")
        try {
            file.writeText("v0\n")

            DurableJournalIo.rewriteUtf8LinesWithoutAtomicMove(file, ".updating", listOf("v1"))
            assertEquals(1L, DurableJournalIo.currentReplacementEpoch(file))
            assertTrue(DurableJournalIo.replacementEpochFileFor(file).isFile)

            DurableJournalIo.rewriteUtf8LinesWithoutAtomicMove(file, ".updating", listOf("v2"))
            assertEquals(2L, DurableJournalIo.currentReplacementEpoch(file))
            assertEquals(listOf("v2"), file.readLines())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun currentEpochR3MarkerRecoversBoundRollback() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-current").toFile()
        val file = File(dir, "memory.head")
        val epoch = DurableJournalIo.advanceReplacementEpoch(file)
        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val staged = DurableJournalIo.replacementStagedFileFor(file, transactionId)
        val backup = DurableJournalIo.replacementBackupFileFor(file, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        try {
            file.writeText("published-but-uncommitted\n")
            staged.writeText("new-authority\n")
            backup.writeText("old-authority\n")
            marker.writeText(
                DurableJournalIo.encodeExistingReplacementMarker(
                    epoch,
                    transactionId,
                    DurableJournalIo.digestFile(staged),
                    DurableJournalIo.digestFile(backup)
                ) + "\n"
            )

            DurableJournalIo.recoverInterruptedReplace(file)

            assertEquals("old-authority\n", file.readText())
            assertFalse(marker.exists())
            assertFalse(staged.exists())
            assertFalse(backup.exists())
            assertEquals(epoch, DurableJournalIo.currentReplacementEpoch(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun staleR3MarkerIsRejectedAfterNewerEpoch() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-replay").toFile()
        val file = File(dir, "memory.head")
        val staleEpoch = DurableJournalIo.advanceReplacementEpoch(file)
        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val staged = DurableJournalIo.replacementStagedFileFor(file, transactionId)
        val backup = DurableJournalIo.replacementBackupFileFor(file, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        try {
            file.writeText("current-authority\n")
            staged.writeText("stale-staged\n")
            backup.writeText("stale-backup\n")
            marker.writeText(
                DurableJournalIo.encodeExistingReplacementMarker(
                    staleEpoch,
                    transactionId,
                    DurableJournalIo.digestFile(staged),
                    DurableJournalIo.digestFile(backup)
                ) + "\n"
            )

            val newerEpoch = DurableJournalIo.advanceReplacementEpoch(file)
            assertEquals(staleEpoch + 1L, newerEpoch)

            assertTrue(runCatching { DurableJournalIo.recoverInterruptedReplace(file) }.isFailure)
            assertEquals("current-authority\n", file.readText())
            assertTrue(marker.exists())
            assertTrue(staged.exists())
            assertTrue(backup.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun phase42R2MarkerIsRejectedAfterEpochProtocolActivation() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-r2-replay").toFile()
        val file = File(dir, "memory.head")
        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val staged = DurableJournalIo.replacementStagedFileFor(file, transactionId)
        val backup = DurableJournalIo.replacementBackupFileFor(file, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        try {
            file.writeText("current-authority\n")
            staged.writeText("legacy-staged\n")
            backup.writeText("legacy-backup\n")
            marker.writeText(
                DurableJournalIo.encodeExistingReplacementMarker(
                    transactionId,
                    DurableJournalIo.digestFile(staged),
                    DurableJournalIo.digestFile(backup)
                ) + "\n"
            )
            DurableJournalIo.advanceReplacementEpoch(file)

            assertTrue(runCatching { DurableJournalIo.recoverInterruptedReplace(file) }.isFailure)
            assertEquals("current-authority\n", file.readText())
            assertTrue(marker.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun epochJournalRejectsTamperedCompleteHistory() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-tamper").toFile()
        val file = File(dir, "memory.head")
        try {
            DurableJournalIo.advanceReplacementEpoch(file)
            DurableJournalIo.advanceReplacementEpoch(file)
            val epochFile = DurableJournalIo.replacementEpochFileFor(file)
            val lines = epochFile.readLines().toMutableList()
            lines[0] = lines[0].replace("G1|1|", "G1|9|")
            epochFile.writeText(lines.joinToString("\n", postfix = "\n"))

            assertTrue(runCatching { DurableJournalIo.currentReplacementEpoch(file) }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun epochTailCompactsIntoMirroredCheckpointsAtBound() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-compact").toFile()
        val file = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(file)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }

            assertEquals(bound.toLong(), journal.current())
            assertTrue(journal.fileForTest().isFile)
            assertEquals(0L, journal.fileForTest().length())
            val checkpoints = journal.checkpointFilesForTest()
            assertEquals(2, checkpoints.size)
            assertTrue(checkpoints.all { it.isFile })
            assertTrue(checkpoints.all { it.readLines().single().startsWith("G2|$bound|") })
            assertTrue(journal.authorityFileForTest().readLines().single().startsWith("G3|$bound|"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun epochGrowthStaysBoundedAcrossRepeatedCompactions() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-bounded").toFile()
        val file = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(file)
        val bound = journal.compactEveryForTest()
        val tail = 7
        val total = bound * 3 + tail
        try {
            repeat(total) { journal.advance() }

            assertEquals(total.toLong(), journal.current())
            assertEquals(tail, journal.fileForTest().readLines().size)
            assertTrue(journal.fileForTest().readLines().all { it.startsWith("G1|") })
            assertTrue(journal.checkpointFilesForTest().all { it.readLines().size == 1 })
            assertTrue(journal.authorityFileForTest().readLines().size == 1)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oneTornCheckpointCannotRollEpochBack() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-mirror").toFile()
        val file = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(file)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }
            val checkpoints = journal.checkpointFilesForTest()
            checkpoints.first().writeText("G2|torn")

            assertEquals(bound.toLong(), RecoveryEpochJournal(file).current())
            assertTrue(checkpoints.last().readLines().single().startsWith("G2|$bound|"))
            assertTrue(journal.authorityFileForTest().readLines().single().startsWith("G3|$bound|"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun bothCheckpointLossFailsClosedInsteadOfTrustingSingleAuthority() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-quorum-loss").toFile()
        val file = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(file)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }
            journal.checkpointFilesForTest().forEach { assertTrue(it.delete()) }

            assertTrue(journal.authorityFileForTest().isFile)
            assertTrue(runCatching { RecoveryEpochJournal(file).current() }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun simultaneousCheckpointRollbackConflictsWithNewerAuthority() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-quorum-replay").toFile()
        val file = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(file)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }
            val oldCheckpoint = journal.checkpointFilesForTest().first().readText()

            repeat(bound) { journal.advance() }
            assertEquals((bound * 2).toLong(), journal.current())
            assertTrue(journal.authorityFileForTest().readLines().single().startsWith("G3|${bound * 2}|"))

            journal.checkpointFilesForTest().forEach { it.writeText(oldCheckpoint) }

            assertTrue(runCatching { RecoveryEpochJournal(file).current() }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptAuthorityIsRepairedFromMatchingCheckpointQuorum() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-authority-repair").toFile()
        val file = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(file)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }
            journal.authorityFileForTest().writeText("G3|torn")

            assertEquals(bound.toLong(), RecoveryEpochJournal(file).current())
            assertTrue(journal.authorityFileForTest().readLines().single().startsWith("G3|$bound|"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun phase44SingleCheckpointUpgradesIntoG3Quorum() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-phase44-upgrade").toFile()
        val file = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(file)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }
            assertTrue(journal.authorityFileForTest().delete())
            journal.checkpointFilesForTest().first().writeText("G2|torn")

            assertEquals(bound.toLong(), RecoveryEpochJournal(file).current())
            assertTrue(journal.authorityFileForTest().readLines().single().startsWith("G3|$bound|"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun totalBoundedAuthorityLossCannotMasqueradeAsEpochZero() {
        val dir = Files.createTempDirectory("amper-recovery-epoch-authority-total-loss").toFile()
        val file = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(file)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }
            journal.checkpointFilesForTest().forEach { assertTrue(it.delete()) }
            assertTrue(journal.authorityFileForTest().delete())
            assertTrue(journal.fileForTest().exists())
            assertEquals(0L, journal.fileForTest().length())

            assertTrue(runCatching { RecoveryEpochJournal(file).current() }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }
}
