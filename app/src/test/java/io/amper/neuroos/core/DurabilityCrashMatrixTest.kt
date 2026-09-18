package io.amper.neuroos.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 47 turns the Phase 41-46 durability protocol into an explicit restart matrix.
 *
 * These tests materialize the exact durable filesystem image expected at each crash boundary,
 * then reopen through the production recovery path. This is intentionally deterministic: no
 * timing, sleeps, process scheduling, or filesystem-specific failure injection is required.
 */
class DurabilityCrashMatrixTest {
    private enum class ReplaceBoundary {
        EPOCH_RESERVED,
        STAGED_DURABLE,
        BACKUP_DURABLE,
        MARKER_DURABLE,
        TARGET_PUBLISHED,
        MARKER_COMMITTED
    }

    private enum class CompactionBoundary {
        G1_HEAD_DURABLE,
        G3_DURABLE,
        ROTATING_G2_DURABLE,
        G1_TRUNCATED,
        RETAINED_G2_DURABLE
    }

    @Test
    fun existingTargetReplacementRestartMatrixPreservesOldValueUntilCommitPoint() {
        ReplaceBoundary.entries.forEach { boundary ->
            val dir = Files.createTempDirectory("amper-replace-crash-${boundary.name.lowercase()}").toFile()
            val target = File(dir, "memory.head")
            try {
                materializeExistingReplacementBoundary(target, boundary)

                DurableJournalIo.recoverInterruptedReplace(target)

                val expected = if (boundary == ReplaceBoundary.MARKER_COMMITTED) "new\n" else "old\n"
                assertEquals("boundary=$boundary", expected, target.readText())
                assertEquals("boundary=$boundary", 1L, DurableJournalIo.currentReplacementEpoch(target))
                assertTrue("boundary=$boundary", replacementSidecars(target).isEmpty())
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun newTargetReplacementRestartMatrixKeepsFileOnlyAfterCommitPoint() {
        val boundaries = listOf(
            ReplaceBoundary.EPOCH_RESERVED,
            ReplaceBoundary.STAGED_DURABLE,
            ReplaceBoundary.MARKER_DURABLE,
            ReplaceBoundary.TARGET_PUBLISHED,
            ReplaceBoundary.MARKER_COMMITTED
        )

        boundaries.forEach { boundary ->
            val dir = Files.createTempDirectory("amper-new-replace-crash-${boundary.name.lowercase()}").toFile()
            val target = File(dir, "memory.head")
            try {
                materializeNewReplacementBoundary(target, boundary)

                DurableJournalIo.recoverInterruptedReplace(target)

                if (boundary == ReplaceBoundary.MARKER_COMMITTED) {
                    assertTrue("boundary=$boundary", target.isFile)
                    assertEquals("new\n", target.readText())
                } else {
                    assertFalse("boundary=$boundary", target.exists())
                }
                assertEquals("boundary=$boundary", 1L, DurableJournalIo.currentReplacementEpoch(target))
                assertTrue("boundary=$boundary", replacementSidecars(target).isEmpty())
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun recoveryItselfIsRestartIdempotentAtRollbackAndMarkerClearBoundaries() {
        val rollbackDir = Files.createTempDirectory("amper-recovery-crash-rollback").toFile()
        try {
            val target = File(rollbackDir, "memory.head")
            val epoch = DurableJournalIo.advanceReplacementEpoch(target)
            val transactionId = DurableJournalIo.newReplacementTransactionId()
            val staged = DurableJournalIo.replacementStagedFileFor(target, transactionId)
            val backup = DurableJournalIo.replacementBackupFileFor(target, transactionId)
            val marker = DurableJournalIo.replacementMarkerFileFor(target)

            staged.writeText("new\n")
            backup.writeText("old\n")
            target.writeText("old\n") // rollback bytes already durable, marker not yet cleared
            marker.writeText(
                DurableJournalIo.encodeExistingReplacementMarker(
                    epoch,
                    transactionId,
                    DurableJournalIo.digestFile(staged),
                    DurableJournalIo.digestFile(backup)
                ) + "\n"
            )

            DurableJournalIo.recoverInterruptedReplace(target)

            assertEquals("old\n", target.readText())
            assertTrue(replacementSidecars(target).isEmpty())
        } finally {
            rollbackDir.deleteRecursively()
        }

        val clearDir = Files.createTempDirectory("amper-recovery-crash-marker-clear").toFile()
        try {
            val target = File(clearDir, "memory.head")
            DurableJournalIo.advanceReplacementEpoch(target)
            val transactionId = DurableJournalIo.newReplacementTransactionId()
            DurableJournalIo.replacementStagedFileFor(target, transactionId).writeText("new\n")
            DurableJournalIo.replacementBackupFileFor(target, transactionId).writeText("old\n")
            target.writeText("old\n") // rollback complete, marker already durably cleared

            DurableJournalIo.recoverInterruptedReplace(target)

            assertEquals("old\n", target.readText())
            assertTrue(replacementSidecars(target).isEmpty())
        } finally {
            clearDir.deleteRecursively()
        }
    }

    @Test
    fun secondCompactionRestartMatrixRecoversEveryRotationBoundaryAndMakesProgress() {
        val preDir = Files.createTempDirectory("amper-compaction-pre-boundary").toFile()
        val completeDir = Files.createTempDirectory("amper-compaction-complete-boundary").toFile()
        try {
            val preTarget = File(preDir, "memory.head")
            val preJournal = RecoveryEpochJournal(preTarget)
            val bound = preJournal.compactEveryForTest()
            val nextCompactionEpoch = (bound * 2).toLong()
            repeat(bound * 2 - 1) { preJournal.advance() }
            assertEquals(nextCompactionEpoch - 1L, preJournal.current())

            val completeTarget = File(completeDir, "memory.head")
            val completeJournal = RecoveryEpochJournal(completeTarget)
            repeat(bound * 2) { completeJournal.advance() }
            assertEquals(nextCompactionEpoch, completeJournal.current())

            val preSnapshot = snapshotFiles(preDir)
            val completedSnapshot = snapshotFiles(completeDir)
            val epochFileName = preJournal.fileForTest().name
            val authorityName = preJournal.authorityFileForTest().name
            val checkpointNames = preJournal.checkpointFilesForTest().map { it.name }
            val generation = nextCompactionEpoch / bound.toLong()
            val advanceIndex = if ((generation and 1L) == 0L) 0 else 1
            val retainedIndex = 1 - advanceIndex
            val nextG1Line = encodeNextEpochLine(preJournal.fileForTest(), nextCompactionEpoch)

            CompactionBoundary.entries.forEach { boundary ->
                val scenarioDir = Files.createTempDirectory(
                    "amper-compaction-crash-${boundary.name.lowercase()}"
                ).toFile()
                try {
                    restoreFiles(preSnapshot, scenarioDir)
                    val scenarioTarget = File(scenarioDir, preTarget.name)
                    val scenarioEpochFile = File(scenarioDir, epochFileName)
                    scenarioEpochFile.appendText(nextG1Line + "\n", StandardCharsets.UTF_8)

                    if (boundary.ordinal >= CompactionBoundary.G3_DURABLE.ordinal) {
                        File(scenarioDir, authorityName).writeBytes(
                            requireNotNull(completedSnapshot[authorityName])
                        )
                    }
                    if (boundary.ordinal >= CompactionBoundary.ROTATING_G2_DURABLE.ordinal) {
                        val advanceName = checkpointNames[advanceIndex]
                        File(scenarioDir, advanceName).writeBytes(
                            requireNotNull(completedSnapshot[advanceName])
                        )
                    }
                    if (boundary.ordinal >= CompactionBoundary.G1_TRUNCATED.ordinal) {
                        scenarioEpochFile.writeBytes(byteArrayOf())
                    }
                    if (boundary.ordinal >= CompactionBoundary.RETAINED_G2_DURABLE.ordinal) {
                        val retainedName = checkpointNames[retainedIndex]
                        File(scenarioDir, retainedName).writeBytes(
                            requireNotNull(completedSnapshot[retainedName])
                        )
                    }

                    val restarted = RecoveryEpochJournal(scenarioTarget)
                    assertEquals("boundary=$boundary", nextCompactionEpoch, restarted.current())
                    assertEquals(
                        "boundary=$boundary",
                        nextCompactionEpoch + 1L,
                        restarted.advance()
                    )
                    assertEquals(nextCompactionEpoch + 1L, RecoveryEpochJournal(scenarioTarget).current())
                } finally {
                    scenarioDir.deleteRecursively()
                }
            }
        } finally {
            preDir.deleteRecursively()
            completeDir.deleteRecursively()
        }
    }

    private fun materializeExistingReplacementBoundary(target: File, boundary: ReplaceBoundary) {
        target.writeText("old\n")
        val epoch = DurableJournalIo.advanceReplacementEpoch(target)
        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val staged = DurableJournalIo.replacementStagedFileFor(target, transactionId)
        val backup = DurableJournalIo.replacementBackupFileFor(target, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(target)

        if (boundary.ordinal >= ReplaceBoundary.STAGED_DURABLE.ordinal) {
            staged.writeText("new\n")
        }
        if (boundary.ordinal >= ReplaceBoundary.BACKUP_DURABLE.ordinal) {
            backup.writeText("old\n")
        }
        if (boundary.ordinal >= ReplaceBoundary.MARKER_DURABLE.ordinal) {
            marker.writeText(
                DurableJournalIo.encodeExistingReplacementMarker(
                    epoch,
                    transactionId,
                    DurableJournalIo.digestFile(staged),
                    DurableJournalIo.digestFile(backup)
                ) + "\n"
            )
        }
        if (boundary.ordinal >= ReplaceBoundary.TARGET_PUBLISHED.ordinal) {
            target.writeText("new\n")
        }
        if (boundary == ReplaceBoundary.MARKER_COMMITTED) {
            assertTrue(marker.delete())
        }
    }

    private fun materializeNewReplacementBoundary(target: File, boundary: ReplaceBoundary) {
        val epoch = DurableJournalIo.advanceReplacementEpoch(target)
        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val staged = DurableJournalIo.replacementStagedFileFor(target, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(target)

        if (boundary.ordinal >= ReplaceBoundary.STAGED_DURABLE.ordinal) {
            staged.writeText("new\n")
        }
        if (boundary.ordinal >= ReplaceBoundary.MARKER_DURABLE.ordinal) {
            marker.writeText(
                DurableJournalIo.encodeNewReplacementMarker(
                    epoch,
                    transactionId,
                    DurableJournalIo.digestFile(staged)
                ) + "\n"
            )
        }
        if (boundary.ordinal >= ReplaceBoundary.TARGET_PUBLISHED.ordinal) {
            target.writeText("new\n")
        }
        if (boundary == ReplaceBoundary.MARKER_COMMITTED) {
            assertTrue(marker.delete())
        }
    }

    private fun replacementSidecars(target: File): List<File> =
        target.parentFile.listFiles().orEmpty().filter { it.name.startsWith(target.name + ".replace-") }

    private fun snapshotFiles(directory: File): Map<String, ByteArray> =
        directory.listFiles().orEmpty()
            .filter { it.isFile }
            .associate { it.name to it.readBytes() }

    private fun restoreFiles(snapshot: Map<String, ByteArray>, directory: File) {
        snapshot.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
    }

    private fun encodeNextEpochLine(epochFile: File, epoch: Long): String {
        val lines = epochFile.readLines()
        require(lines.isNotEmpty())
        val previousDigest = sha256(lines.last().toByteArray(StandardCharsets.UTF_8))
        val canonical = "G1|$epoch|$previousDigest"
        return "$canonical|${sha256(canonical.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
