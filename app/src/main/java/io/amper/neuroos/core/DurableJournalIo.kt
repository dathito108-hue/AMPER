package io.amper.neuroos.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Durable local-file primitives for sovereign journals and sidecars.
 *
 * Successful mutation paths flush file bytes and, when directory metadata changes, force
 * the parent directory before returning. Missing directory trees are created top-down and
 * every newly materialized boundary is forced together with its parent so nested creation
 * cannot leave an unsynced ancestor. Multi-process serialization is layered with the
 * higher-level MemoryJournalProcessLock when canonical journal state spans multiple files.
 *
 * Phase 53 binds rewrite/recovery locks, marker reads, staged/backup copies, digests and
 * fallback publication to NOFOLLOW descriptor I/O. Phase 54 extends that boundary through
 * commit-marker deletion, rollback target deletion, orphan cleanup and directory fsync.
 * Phase 55 closes the remaining direct append path and binds ATOMIC_MOVE publication to the
 * exact prepared source identity across rename and durable publication.
 *
 * Phase 57 promotes the historical rewrite lock into one stable mutation lease shared by
 * append, rewrite/compaction and replacement recovery. The ordering is fixed: an optional
 * higher-level journal process lock is acquired first, then this stable mutation sidecar,
 * then the exact target descriptor. Append can therefore no longer keep writing an inode
 * while another cooperating process rotates or replaces the pathname out from under it.
 */
internal object DurableJournalIo {
    private const val REPLACE_MARKER_VERSION = "R3"
    private const val LEGACY_R2_MARKER_VERSION = "R2"
    private const val LEGACY_REPLACE_MARKER_EXISTING = "R1|existing"
    private const val LEGACY_REPLACE_MARKER_NEW = "R1|new"
    private const val REPLACE_MODE_EXISTING = "existing"
    private const val REPLACE_MODE_NEW = "new"
    private const val NO_BACKUP_DIGEST = "~"
    private val TRANSACTION_ID = Regex("[0-9a-f]{32}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val REWRITE_PURPOSE = Regex("[A-Za-z0-9_-]+")
    private val MUTATION_JVM_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
    private val LEGACY_REWRITE_SUFFIXES = setOf(
        ".updating",
        ".compacting",
        ".chaining",
        ".compacting-encrypted",
        ".rekeying",
        ".encrypting-compacted-segment",
        ".encrypting-chain"
    )

    private data class ReplacementMarker(
        val mode: String,
        val epoch: Long,
        val transactionId: String,
        val stagedDigest: String,
        val backupDigest: String?
    )

    private data class LegacyR2Marker(
        val mode: String,
        val transactionId: String,
        val stagedDigest: String,
        val backupDigest: String?
    )

    fun ensureFileExistsDurably(file: File) {
        val parent = file.parentFile ?: error("journal file has no parent directory")
        ensureDirectoryExistsDurably(parent)
        withMutationLease(file) {
            recoverInterruptedReplaceLocked(file)
            cleanupOrphanRewriteTemps(file)
            DescriptorBoundFileIo.ensureFileExists(file)
        }
    }

    /**
     * Materializes a missing directory chain one level at a time. Existing components are
     * inspected with NOFOLLOW_LINKS and must be real directories. Every created level is
     * forced, and so is the parent that names it.
     */
    fun ensureDirectoryExistsDurably(directory: File) {
        val target = directory.absoluteFile.normalize()
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            SovereignPathIdentity.requireDirectory(target, allowMissingLeaf = false)
            return
        }

        val missing = ArrayDeque<File>()
        var cursor: File? = target
        while (cursor != null && !Files.exists(cursor.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(cursor)
            cursor = cursor.parentFile
        }
        val existingAncestor = requireNotNull(cursor) { "directory tree has no existing ancestor" }
        SovereignPathIdentity.requireDirectory(existingAncestor, allowMissingLeaf = false)

        var parent = existingAncestor
        missing.forEach { next ->
            SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
            if (!Files.exists(next.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(next.toPath())
            }
            SovereignPathIdentity.requireDirectory(next, allowMissingLeaf = false)
            syncDirectory(next)
            syncDirectory(parent)
            parent = next
        }
    }

    fun appendUtf8Line(file: File, line: String) {
        withMutationLease(file) {
            DescriptorBoundMutationIo.appendUtf8Line(file, line)
        }
    }

    fun writeUtf8AndSync(file: File, content: String) {
        DescriptorBoundFileIo.writeUtf8Replacing(file, content)
    }

    fun syncFile(file: File) {
        DescriptorBoundFileIo.force(file)
    }

    /** Forces directory metadata without following or accepting a substituted directory. */
    fun syncDirectory(directory: File) {
        DescriptorBoundFileIo.syncDirectory(directory)
    }

    fun rewriteUtf8LinesAtomically(file: File, tempSuffix: String, lines: List<String>) {
        rewriteUtf8Lines(file, tempSuffix, lines, attemptAtomicMove = true)
    }

    /** Exercises the production recoverable fallback directly on filesystems that support ATOMIC_MOVE. */
    internal fun rewriteUtf8LinesWithoutAtomicMove(
        file: File,
        tempSuffix: String,
        lines: List<String>
    ) {
        rewriteUtf8Lines(file, tempSuffix, lines, attemptAtomicMove = false)
    }

    private fun rewriteUtf8Lines(
        file: File,
        tempSuffix: String,
        lines: List<String>,
        attemptAtomicMove: Boolean
    ) {
        rewritePurpose(tempSuffix)
        lines.forEach { require('\n' !in it && '\r' !in it) { "journal entry must be one line" } }
        val parent = file.parentFile ?: error("journal file has no parent directory")
        ensureDirectoryExistsDurably(parent)

        withMutationLease(file) {
            recoverInterruptedReplaceLocked(file)
            cleanupOrphanRewriteTemps(file)

            val transactionId = newReplacementTransactionId()
            val temp = rewriteTempFileFor(file, tempSuffix, transactionId)
            var tempIdentity: SovereignPathIdentity.Snapshot? = null
            try {
                writeUtf8AndSync(
                    temp,
                    if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")
                )
                tempIdentity = SovereignPathIdentity.snapshot(temp)

                if (attemptAtomicMove) {
                    try {
                        DescriptorBoundMutationIo.publishAtomicReplacing(temp, file, tempIdentity)
                        return@withMutationLease
                    } catch (_: AtomicMoveNotSupportedException) {
                        // Fall through to the explicit rollback protocol below.
                    }
                }

                replaceWithoutAtomicMoveRecoverably(file, temp)
            } finally {
                val deleted = if (tempIdentity != null) {
                    DescriptorBoundFileIo.deleteIfExistsBound(temp, tempIdentity)
                } else {
                    DescriptorBoundFileIo.deleteIfExistsBound(temp)
                }
                if (deleted) syncDirectory(parent)
            }
        }
    }

    /**
     * Crash-safe fallback for filesystems that reject ATOMIC_MOVE.
     *
     * R3 reserves a durable monotonic epoch before publishing transaction metadata. The
     * marker binds that epoch, a unique transaction id and SHA-256 digests for staged and
     * rollback bytes. A marker remains recoverable only while its epoch is still the latest
     * durable generation, preventing a previously valid R2/R3 marker from being replayed
     * after a newer fallback transaction has begun or committed.
     *
     * Deleting + syncing the exact marker identity remains the commit point:
     * - valid current-epoch marker present => validate exact sidecars, then roll back
     * - marker absent => replacement committed; orphan transaction sidecars are disposable
     */
    private fun replaceWithoutAtomicMoveRecoverably(file: File, preparedTemp: File) {
        val parent = file.parentFile ?: error("journal file has no parent directory")
        recoverInterruptedReplaceLocked(file)

        val epoch = RecoveryEpochJournal(file).advance()
        val transactionId = newReplacementTransactionId()
        val staged = replacementStagedFileFor(file, transactionId)
        val backup = replacementBackupFileFor(file, transactionId)
        val marker = replacementMarkerFileFor(file)

        DescriptorBoundFileIo.copyReplacing(preparedTemp, staged)
        syncDirectory(parent)
        val stagedDigest = digestFile(staged)

        val targetExisted = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        val backupDigest = if (targetExisted) {
            DescriptorBoundFileIo.copyReplacing(file, backup)
            syncDirectory(parent)
            digestFile(backup)
        } else {
            null
        }

        val markerContent = encodeReplacementMarker(
            ReplacementMarker(
                mode = if (targetExisted) REPLACE_MODE_EXISTING else REPLACE_MODE_NEW,
                epoch = epoch,
                transactionId = transactionId,
                stagedDigest = stagedDigest,
                backupDigest = backupDigest
            )
        ) + "\n"
        writeUtf8AndSync(marker, markerContent)
        syncDirectory(parent)
        val boundMarker = DescriptorBoundFileIo.readUtf8TextBound(marker)
        require(boundMarker.text == markerContent) { "published replacement marker changed before target publication" }

        DescriptorBoundFileIo.copyReplacing(staged, file)
        require(digestFile(file) == stagedDigest) { "published replacement digest mismatch" }
        syncDirectory(parent)

        require(DescriptorBoundFileIo.deleteIfExistsBound(marker, boundMarker.identity)) {
            "failed to commit recoverable replacement"
        }
        syncDirectory(parent)
        cleanupOrphanReplaceSidecars(file)
    }

    /**
     * Rolls back an interrupted non-atomic replacement before callers inspect [file].
     * R3 additionally requires marker epoch == latest durable epoch. R2 and R1 are accepted
     * only while no R3 epoch has ever been reserved, which preserves upgrade recovery while
     * preventing legacy-marker replay after the Phase 43 protocol becomes active.
     *
     * Phase 49 serializes recovery against active rewrites with a dedicated target-local file
     * lock. Phase 53 binds that lock and recovery data I/O to NOFOLLOW descriptors. Phase 54
     * binds recovery deletion to the exact marker identity that was decoded. Phase 57 shares
     * the same stable lease with append, so recovery cannot race a descriptor-held writer.
     */
    fun recoverInterruptedReplace(file: File) {
        val parent = file.parentFile ?: error("journal file has no parent directory")
        if (!Files.exists(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)

        withMutationLease(file) {
            recoverInterruptedReplaceLocked(file)
            cleanupOrphanRewriteTemps(file)
        }
    }

    private fun recoverInterruptedReplaceLocked(file: File) {
        val parent = file.parentFile ?: error("journal file has no parent directory")
        if (!Files.exists(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)

        val markerFile = replacementMarkerFileFor(file)
        if (!Files.exists(markerFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            cleanupOrphanReplaceSidecars(file)
            return
        }

        val boundMarker = DescriptorBoundFileIo.readUtf8TextBound(markerFile)
        val encoded = boundMarker.text.trim()
        when {
            encoded == LEGACY_REPLACE_MARKER_EXISTING || encoded == LEGACY_REPLACE_MARKER_NEW -> {
                require(RecoveryEpochJournal(file).current() == 0L) {
                    "legacy R1 replacement marker replay rejected after recovery epochs activated"
                }
                recoverLegacyInterruptedReplace(file, encoded, boundMarker.identity)
            }
            encoded.startsWith("$LEGACY_R2_MARKER_VERSION|") -> {
                require(RecoveryEpochJournal(file).current() == 0L) {
                    "legacy R2 replacement marker replay rejected after recovery epochs activated"
                }
                recoverR2InterruptedReplace(
                    file,
                    decodeLegacyR2ReplacementMarker(encoded),
                    boundMarker.identity
                )
            }
            encoded.startsWith("$REPLACE_MARKER_VERSION|") -> {
                recoverR3InterruptedReplace(file, decodeReplacementMarker(encoded), boundMarker.identity)
            }
            else -> error("unsupported replacement recovery marker")
        }
    }

    private fun <T> withMutationLease(file: File, block: () -> T): T {
        val parent = file.parentFile ?: error("journal file has no parent directory")
        ensureDirectoryExistsDurably(parent)
        val lockFile = mutationLockFileFor(file)
        val pathKey = lockFile.absoluteFile.normalize().path
        val jvmLock = MUTATION_JVM_LOCKS.computeIfAbsent(pathKey) { ReentrantLock(true) }
        return jvmLock.withLock {
            DescriptorBoundFileIo.withExclusiveLock(lockFile, block)
        }
    }

    private fun recoverR3InterruptedReplace(
        file: File,
        marker: ReplacementMarker,
        markerIdentity: SovereignPathIdentity.Snapshot
    ) {
        val latestEpoch = RecoveryEpochJournal(file).current()
        require(marker.epoch == latestEpoch) {
            "replacement recovery epoch mismatch: marker=${marker.epoch}, latest=$latestEpoch"
        }
        recoverTransactionBoundReplacement(
            file = file,
            mode = marker.mode,
            transactionId = marker.transactionId,
            stagedDigest = marker.stagedDigest,
            backupDigest = marker.backupDigest,
            markerIdentity = markerIdentity
        )
    }

    private fun recoverR2InterruptedReplace(
        file: File,
        marker: LegacyR2Marker,
        markerIdentity: SovereignPathIdentity.Snapshot
    ) {
        recoverTransactionBoundReplacement(
            file = file,
            mode = marker.mode,
            transactionId = marker.transactionId,
            stagedDigest = marker.stagedDigest,
            backupDigest = marker.backupDigest,
            markerIdentity = markerIdentity
        )
    }

    private fun recoverTransactionBoundReplacement(
        file: File,
        mode: String,
        transactionId: String,
        stagedDigest: String,
        backupDigest: String?,
        markerIdentity: SovereignPathIdentity.Snapshot
    ) {
        val parent = file.parentFile ?: error("journal file has no parent directory")
        val markerFile = replacementMarkerFileFor(file)
        val staged = replacementStagedFileFor(file, transactionId)
        val backup = replacementBackupFileFor(file, transactionId)

        SovereignPathIdentity.requireManagedFile(staged, allowMissingLeaf = false)
        require(digestFile(staged) == stagedDigest) {
            "pending replacement staged digest mismatch"
        }

        when (mode) {
            REPLACE_MODE_EXISTING -> {
                val expectedBackupDigest = requireNotNull(backupDigest) {
                    "existing replacement is missing rollback digest"
                }
                SovereignPathIdentity.requireManagedFile(backup, allowMissingLeaf = false)
                require(digestFile(backup) == expectedBackupDigest) {
                    "pending replacement rollback digest mismatch"
                }
                DescriptorBoundFileIo.copyReplacing(backup, file)
                require(digestFile(file) == expectedBackupDigest) {
                    "restored replacement rollback digest mismatch"
                }
                syncDirectory(parent)
            }
            REPLACE_MODE_NEW -> {
                require(backupDigest == null) { "new-file replacement unexpectedly declares a rollback digest" }
                require(!Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "new-file replacement unexpectedly has a transaction rollback backup"
                }
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    require(DescriptorBoundFileIo.deleteIfExistsBound(file)) {
                        "failed to roll back interrupted new-file replacement"
                    }
                    syncDirectory(parent)
                }
            }
            else -> error("unsupported replacement recovery mode")
        }

        require(DescriptorBoundFileIo.deleteIfExistsBound(markerFile, markerIdentity)) {
            "failed to clear recovered replacement marker"
        }
        syncDirectory(parent)
        cleanupOrphanReplaceSidecars(file)
    }

    private fun recoverLegacyInterruptedReplace(
        file: File,
        encodedMarker: String,
        markerIdentity: SovereignPathIdentity.Snapshot
    ) {
        val parent = file.parentFile ?: error("journal file has no parent directory")
        val marker = replacementMarkerFileFor(file)
        val backup = legacyReplacementBackupFileFor(file)
        val staged = legacyReplacementStagedFileFor(file)

        when (encodedMarker) {
            LEGACY_REPLACE_MARKER_EXISTING -> {
                SovereignPathIdentity.requireManagedFile(backup, allowMissingLeaf = false)
                DescriptorBoundFileIo.copyReplacing(backup, file)
                syncDirectory(parent)
            }
            LEGACY_REPLACE_MARKER_NEW -> {
                require(!Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "legacy new-file replacement unexpectedly has a rollback backup"
                }
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    require(DescriptorBoundFileIo.deleteIfExistsBound(file)) {
                        "failed to roll back interrupted legacy new-file replacement"
                    }
                    syncDirectory(parent)
                }
            }
            else -> error("unsupported legacy replacement recovery marker")
        }

        require(DescriptorBoundFileIo.deleteIfExistsBound(marker, markerIdentity)) {
            "failed to clear legacy replacement marker"
        }
        DescriptorBoundFileIo.deleteIfExistsBound(backup)
        DescriptorBoundFileIo.deleteIfExistsBound(staged)
        syncDirectory(parent)
        cleanupOrphanReplaceSidecars(file)
    }

    private fun cleanupOrphanReplaceSidecars(file: File) {
        val parent = file.parentFile ?: error("journal file has no parent directory")
        if (!Files.exists(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
        val parentIdentity = SovereignPathIdentity.snapshotDirectory(parent)

        val transactionSidecar = Regex(
            "^${Regex.escape(file.name)}\\.replace-[0-9a-f]{32}\\.(backup|staged)$"
        )
        val legacyNames = setOf(
            legacyReplacementBackupFileFor(file).name,
            legacyReplacementStagedFileFor(file).name
        )
        var changed = false
        parent.listFiles().orEmpty().forEach { candidate ->
            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            if (candidate.name in legacyNames || transactionSidecar.matches(candidate.name)) {
                val candidateIdentity = SovereignPathIdentity.snapshot(candidate)
                require(DescriptorBoundFileIo.deleteIfExistsBound(candidate, candidateIdentity)) {
                    "failed to clear orphan replacement sidecar ${candidate.name}"
                }
                changed = true
            }
        }
        SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
        if (changed) syncDirectory(parent)
    }

    private fun cleanupOrphanRewriteTemps(file: File) {
        val parent = file.parentFile ?: error("journal file has no parent directory")
        if (!Files.exists(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
        val parentIdentity = SovereignPathIdentity.snapshotDirectory(parent)

        val scopedTemp = Regex(
            "^${Regex.escape(file.name)}\\.rewrite-[0-9a-f]{32}\\.[A-Za-z0-9_-]+$"
        )
        val legacyNames = LEGACY_REWRITE_SUFFIXES.mapTo(mutableSetOf()) { file.name + it }
        var changed = false
        parent.listFiles().orEmpty().forEach { candidate ->
            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            if (candidate.name in legacyNames || scopedTemp.matches(candidate.name)) {
                val candidateIdentity = SovereignPathIdentity.snapshot(candidate)
                require(DescriptorBoundFileIo.deleteIfExistsBound(candidate, candidateIdentity)) {
                    "failed to clear orphan rewrite temp ${candidate.name}"
                }
                changed = true
            }
        }
        SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
        if (changed) syncDirectory(parent)
    }

    private fun encodeReplacementMarker(marker: ReplacementMarker): String {
        validateReplacementFields(marker.mode, marker.transactionId, marker.stagedDigest, marker.backupDigest)
        require(marker.epoch > 0) { "invalid replacement recovery epoch" }
        val canonical = listOf(
            REPLACE_MARKER_VERSION,
            marker.mode,
            marker.epoch.toString(),
            marker.transactionId,
            marker.stagedDigest,
            marker.backupDigest ?: NO_BACKUP_DIGEST
        ).joinToString("|")
        return "$canonical|${sha256(canonical.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun decodeReplacementMarker(value: String): ReplacementMarker {
        val parts = value.split('|')
        require(parts.size == 7 && parts[0] == REPLACE_MARKER_VERSION) {
            "unsupported replacement recovery marker"
        }
        val mode = parts[1]
        val epoch = parts[2].toLong()
        require(epoch > 0) { "invalid replacement recovery epoch" }
        val transactionId = parts[3]
        val stagedDigest = parts[4]
        val backupDigest = decodeBackupDigest(parts[5])
        validateReplacementFields(mode, transactionId, stagedDigest, backupDigest)
        val checksum = parts[6]
        require(checksum.matches(SHA256)) { "invalid replacement marker checksum" }
        val canonical = parts.take(6).joinToString("|")
        require(sha256(canonical.toByteArray(StandardCharsets.UTF_8)) == checksum) {
            "replacement marker checksum mismatch"
        }
        return ReplacementMarker(mode, epoch, transactionId, stagedDigest, backupDigest)
    }

    private fun encodeLegacyR2ReplacementMarker(marker: LegacyR2Marker): String {
        validateReplacementFields(marker.mode, marker.transactionId, marker.stagedDigest, marker.backupDigest)
        val canonical = listOf(
            LEGACY_R2_MARKER_VERSION,
            marker.mode,
            marker.transactionId,
            marker.stagedDigest,
            marker.backupDigest ?: NO_BACKUP_DIGEST
        ).joinToString("|")
        return "$canonical|${sha256(canonical.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun decodeLegacyR2ReplacementMarker(value: String): LegacyR2Marker {
        val parts = value.split('|')
        require(parts.size == 6 && parts[0] == LEGACY_R2_MARKER_VERSION) {
            "unsupported legacy R2 replacement recovery marker"
        }
        val mode = parts[1]
        val transactionId = parts[2]
        val stagedDigest = parts[3]
        val backupDigest = decodeBackupDigest(parts[4])
        validateReplacementFields(mode, transactionId, stagedDigest, backupDigest)
        val checksum = parts[5]
        require(checksum.matches(SHA256)) { "invalid legacy R2 replacement marker checksum" }
        val canonical = parts.take(5).joinToString("|")
        require(sha256(canonical.toByteArray(StandardCharsets.UTF_8)) == checksum) {
            "legacy R2 replacement marker checksum mismatch"
        }
        return LegacyR2Marker(mode, transactionId, stagedDigest, backupDigest)
    }

    private fun decodeBackupDigest(encoded: String): String? = when (encoded) {
        NO_BACKUP_DIGEST -> null
        else -> encoded.also { require(it.matches(SHA256)) { "invalid replacement backup digest" } }
    }

    private fun validateReplacementFields(
        mode: String,
        transactionId: String,
        stagedDigest: String,
        backupDigest: String?
    ) {
        require(mode == REPLACE_MODE_EXISTING || mode == REPLACE_MODE_NEW) {
            "unsupported replacement recovery mode"
        }
        require(transactionId.matches(TRANSACTION_ID)) { "invalid replacement transaction id" }
        require(stagedDigest.matches(SHA256)) { "invalid replacement staged digest" }
        if (mode == REPLACE_MODE_EXISTING) {
            require(backupDigest?.matches(SHA256) == true) { "invalid replacement backup digest" }
        } else {
            require(backupDigest == null) { "new replacement must not declare a backup digest" }
        }
    }

    internal fun encodeExistingReplacementMarker(
        epoch: Long,
        transactionId: String,
        stagedDigest: String,
        backupDigest: String
    ): String = encodeReplacementMarker(
        ReplacementMarker(REPLACE_MODE_EXISTING, epoch, transactionId, stagedDigest, backupDigest)
    )

    internal fun encodeNewReplacementMarker(
        epoch: Long,
        transactionId: String,
        stagedDigest: String
    ): String = encodeReplacementMarker(
        ReplacementMarker(REPLACE_MODE_NEW, epoch, transactionId, stagedDigest, null)
    )

    /** Phase 42 compatibility helper used only by upgrade/replay regression tests. */
    internal fun encodeExistingReplacementMarker(
        transactionId: String,
        stagedDigest: String,
        backupDigest: String
    ): String = encodeLegacyR2ReplacementMarker(
        LegacyR2Marker(REPLACE_MODE_EXISTING, transactionId, stagedDigest, backupDigest)
    )

    /** Phase 42 compatibility helper used only by upgrade/replay regression tests. */
    internal fun encodeNewReplacementMarker(
        transactionId: String,
        stagedDigest: String
    ): String = encodeLegacyR2ReplacementMarker(
        LegacyR2Marker(REPLACE_MODE_NEW, transactionId, stagedDigest, null)
    )

    internal fun currentReplacementEpoch(file: File): Long = RecoveryEpochJournal(file).current()

    internal fun advanceReplacementEpoch(file: File): Long = RecoveryEpochJournal(file).advance()

    internal fun replacementEpochFileFor(file: File): File = RecoveryEpochJournal(file).fileForTest()

    internal fun digestFile(file: File): String = DescriptorBoundFileIo.sha256(file)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    internal fun newReplacementTransactionId(): String =
        UUID.randomUUID().toString().replace("-", "")

    private fun rewritePurpose(tempSuffix: String): String {
        require(tempSuffix.startsWith('.') && tempSuffix.length > 1) {
            "rewrite temp suffix must start with a purpose separator"
        }
        val purpose = tempSuffix.substring(1)
        require(purpose.matches(REWRITE_PURPOSE)) { "invalid rewrite temp purpose" }
        return purpose
    }

    internal fun rewriteTempFileFor(file: File, tempSuffix: String, transactionId: String): File {
        require(transactionId.matches(TRANSACTION_ID)) { "invalid rewrite transaction id" }
        val purpose = rewritePurpose(tempSuffix)
        return File(file.parentFile, "${file.name}.rewrite-$transactionId.$purpose")
    }

    /**
     * Stable mutation sidecar. The legacy .rewrite-lock name is intentionally retained so
     * upgrades do not create a second lock domain beside already-running Phase 49-56 peers.
     */
    internal fun mutationLockFileFor(file: File): File =
        File(file.parentFile, file.name + ".rewrite-lock")

    internal fun rewriteLockFileFor(file: File): File = mutationLockFileFor(file)

    internal fun replacementMarkerFileFor(file: File): File =
        File(file.parentFile, file.name + ".replace-pending")

    internal fun replacementBackupFileFor(file: File, transactionId: String): File {
        require(transactionId.matches(TRANSACTION_ID)) { "invalid replacement transaction id" }
        return File(file.parentFile, file.name + ".replace-$transactionId.backup")
    }

    internal fun replacementStagedFileFor(file: File, transactionId: String): File {
        require(transactionId.matches(TRANSACTION_ID)) { "invalid replacement transaction id" }
        return File(file.parentFile, file.name + ".replace-$transactionId.staged")
    }

    private fun legacyReplacementBackupFileFor(file: File): File =
        File(file.parentFile, file.name + ".replace-backup")

    private fun legacyReplacementStagedFileFor(file: File): File =
        File(file.parentFile, file.name + ".replace-staged")

    /**
     * Returns only newline-terminated journal entries. DescriptorBoundFileIo preserves the
     * previous torn-tail semantics while binding both the read and repair to NOFOLLOW
     * descriptors and the originally observed file identity.
     */
    fun readCompleteUtf8LinesRecoveringTornTail(file: File): List<String> =
        DescriptorBoundFileIo.readCompleteUtf8LinesRecoveringTornTail(file)
}
