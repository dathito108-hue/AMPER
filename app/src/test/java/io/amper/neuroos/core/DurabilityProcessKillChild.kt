package io.amper.neuroos.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Child JVM used by Phase 48. The parent forcibly kills this process only after a durable boundary. */
object DurabilityProcessKillChild {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 4) { "expected mode, boundary, target/directory and signal path" }
        when (args[0]) {
            "replace-existing" -> runExistingReplacement(
                boundary = ReplaceBoundary.valueOf(args[1]),
                target = File(args[2]),
                signal = File(args[3])
            )
            "replace-new" -> runNewReplacement(
                boundary = ReplaceBoundary.valueOf(args[1]),
                target = File(args[2]),
                signal = File(args[3])
            )
            "compact" -> {
                require(args.size == 6)
                runCompactionPrefix(
                    boundary = CompactionBoundary.valueOf(args[1]),
                    target = File(args[2]),
                    completedReferenceDirectory = File(args[4]),
                    signal = File(args[3]),
                    expectedEpoch = args[5].toLong()
                )
            }
            else -> error("unsupported Phase 48 child mode: ${args[0]}")
        }
    }

    enum class ReplaceBoundary {
        EPOCH_RESERVED,
        STAGED_DURABLE,
        BACKUP_DURABLE,
        MARKER_DURABLE,
        TARGET_PUBLISHED,
        MARKER_COMMITTED
    }

    enum class CompactionBoundary {
        G1_HEAD_DURABLE,
        G3_DURABLE,
        ROTATING_G2_DURABLE,
        G1_TRUNCATED,
        RETAINED_G2_DURABLE
    }

    private fun runExistingReplacement(boundary: ReplaceBoundary, target: File, signal: File) {
        require(target.isFile) { "existing-target crash case requires an existing target" }
        val parent = requireNotNull(target.parentFile)
        val epoch = DurableJournalIo.advanceReplacementEpoch(target)
        if (boundary == ReplaceBoundary.EPOCH_RESERVED) parkAtBoundary(signal)

        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val staged = DurableJournalIo.replacementStagedFileFor(target, transactionId)
        val backup = DurableJournalIo.replacementBackupFileFor(target, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(target)

        DurableJournalIo.writeUtf8AndSync(staged, "new\n")
        DurableJournalIo.syncDirectory(parent)
        val stagedDigest = DurableJournalIo.digestFile(staged)
        if (boundary == ReplaceBoundary.STAGED_DURABLE) parkAtBoundary(signal)

        Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        DurableJournalIo.syncFile(backup)
        DurableJournalIo.syncDirectory(parent)
        val backupDigest = DurableJournalIo.digestFile(backup)
        if (boundary == ReplaceBoundary.BACKUP_DURABLE) parkAtBoundary(signal)

        DurableJournalIo.writeUtf8AndSync(
            marker,
            DurableJournalIo.encodeExistingReplacementMarker(
                epoch,
                transactionId,
                stagedDigest,
                backupDigest
            ) + "\n"
        )
        DurableJournalIo.syncDirectory(parent)
        if (boundary == ReplaceBoundary.MARKER_DURABLE) parkAtBoundary(signal)

        Files.copy(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        DurableJournalIo.syncFile(target)
        require(DurableJournalIo.digestFile(target) == stagedDigest)
        DurableJournalIo.syncDirectory(parent)
        if (boundary == ReplaceBoundary.TARGET_PUBLISHED) parkAtBoundary(signal)

        require(marker.delete())
        DurableJournalIo.syncDirectory(parent)
        if (boundary == ReplaceBoundary.MARKER_COMMITTED) parkAtBoundary(signal)
        error("Phase 48 child should have been killed at $boundary")
    }

    private fun runNewReplacement(boundary: ReplaceBoundary, target: File, signal: File) {
        require(!target.exists()) { "new-target crash case requires an absent target" }
        val parent = requireNotNull(target.parentFile)
        DurableJournalIo.ensureDirectoryExistsDurably(parent)
        val epoch = DurableJournalIo.advanceReplacementEpoch(target)
        if (boundary == ReplaceBoundary.EPOCH_RESERVED) parkAtBoundary(signal)

        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val staged = DurableJournalIo.replacementStagedFileFor(target, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(target)

        DurableJournalIo.writeUtf8AndSync(staged, "new\n")
        DurableJournalIo.syncDirectory(parent)
        val stagedDigest = DurableJournalIo.digestFile(staged)
        if (boundary == ReplaceBoundary.STAGED_DURABLE) parkAtBoundary(signal)

        require(boundary != ReplaceBoundary.BACKUP_DURABLE) {
            "new-target protocol has no rollback-backup boundary"
        }

        DurableJournalIo.writeUtf8AndSync(
            marker,
            DurableJournalIo.encodeNewReplacementMarker(epoch, transactionId, stagedDigest) + "\n"
        )
        DurableJournalIo.syncDirectory(parent)
        if (boundary == ReplaceBoundary.MARKER_DURABLE) parkAtBoundary(signal)

        Files.copy(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        DurableJournalIo.syncFile(target)
        require(DurableJournalIo.digestFile(target) == stagedDigest)
        DurableJournalIo.syncDirectory(parent)
        if (boundary == ReplaceBoundary.TARGET_PUBLISHED) parkAtBoundary(signal)

        require(marker.delete())
        DurableJournalIo.syncDirectory(parent)
        if (boundary == ReplaceBoundary.MARKER_COMMITTED) parkAtBoundary(signal)
        error("Phase 48 child should have been killed at $boundary")
    }

    private fun runCompactionPrefix(
        boundary: CompactionBoundary,
        target: File,
        completedReferenceDirectory: File,
        signal: File,
        expectedEpoch: Long
    ) {
        val journal = RecoveryEpochJournal(target)
        val parent = requireNotNull(target.parentFile)
        val epochFile = journal.fileForTest()
        val checkpoints = journal.checkpointFilesForTest()
        val authority = journal.authorityFileForTest()
        val bound = journal.compactEveryForTest()
        require(expectedEpoch % bound.toLong() == 0L)

        val nextLine = encodeNextEpochLine(epochFile, expectedEpoch)
        DurableJournalIo.appendUtf8Line(epochFile, nextLine)
        if (boundary == CompactionBoundary.G1_HEAD_DURABLE) parkAtBoundary(signal)

        copyReferenceDurably(File(completedReferenceDirectory, authority.name), authority, parent)
        if (boundary == CompactionBoundary.G3_DURABLE) parkAtBoundary(signal)

        val generation = expectedEpoch / bound.toLong()
        val advanceIndex = if ((generation and 1L) == 0L) 0 else 1
        val retainedIndex = 1 - advanceIndex
        copyReferenceDurably(
            File(completedReferenceDirectory, checkpoints[advanceIndex].name),
            checkpoints[advanceIndex],
            parent
        )
        if (boundary == CompactionBoundary.ROTATING_G2_DURABLE) parkAtBoundary(signal)

        RandomAccessFile(epochFile, "rw").use { raf ->
            raf.setLength(0L)
            raf.fd.sync()
        }
        if (boundary == CompactionBoundary.G1_TRUNCATED) parkAtBoundary(signal)

        copyReferenceDurably(
            File(completedReferenceDirectory, checkpoints[retainedIndex].name),
            checkpoints[retainedIndex],
            parent
        )
        if (boundary == CompactionBoundary.RETAINED_G2_DURABLE) parkAtBoundary(signal)
        error("Phase 48 child should have been killed at $boundary")
    }

    private fun copyReferenceDurably(source: File, destination: File, parent: File) {
        require(source.isFile) { "missing completed-state reference ${source.name}" }
        DurableJournalIo.writeUtf8AndSync(destination, source.readText(StandardCharsets.UTF_8))
        DurableJournalIo.syncDirectory(parent)
    }

    private fun encodeNextEpochLine(epochFile: File, epoch: Long): String {
        val lines = epochFile.readLines()
        require(lines.isNotEmpty()) { "G1 tail must contain the previous epoch" }
        val previousDigest = sha256(lines.last().toByteArray(StandardCharsets.UTF_8))
        val canonical = "G1|$epoch|$previousDigest"
        return "$canonical|${sha256(canonical.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun parkAtBoundary(signal: File): Nothing {
        val parent = requireNotNull(signal.parentFile)
        DurableJournalIo.ensureDirectoryExistsDurably(parent)
        DurableJournalIo.writeUtf8AndSync(signal, "READY\n")
        DurableJournalIo.syncDirectory(parent)
        while (true) Thread.sleep(60_000L)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
