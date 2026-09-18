package io.amper.neuroos.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.concurrent.withLock

/**
 * Descriptor-bound mutation primitives for append and ATOMIC_MOVE publication.
 *
 * Phase 55 moved append/publication behind NOFOLLOW descriptors and pinned parent/leaf
 * identities. Phase 56 turns each append descriptor into an explicit mutation lease: writers
 * are serialized inside one JVM, acquire an exclusive FileChannel lock across processes, pin
 * the target identity while that descriptor lease is held, and verify the exact post-append
 * size before releasing it. A pathname substitution during the lease therefore fails closed
 * instead of silently continuing on a different journal object.
 *
 * Phase 58 shares the exact-target JVM lease with snapshot read/repair and atomic publication.
 * Existing targets are descriptor-locked before rename, so a reader holding the old pathname
 * object cannot be crossed by a cooperating rotation. After rename the prepared source
 * descriptor is still forced and rebound to the target identity before publication completes.
 *
 * Atomic publication keeps the prepared source descriptor open across rename, forces that
 * exact file object after rename, and proves that the destination path resolves to the same
 * source identity before the parent directory is durably synced.
 */
internal object DescriptorBoundMutationIo {
    fun appendUtf8Line(file: File, line: String) {
        require('\n' !in line && '\r' !in line) { "journal entry must be one line" }
        val normalized = file.absoluteFile.normalize()
        val jvmLock = TargetDescriptorLeaseRegistry.lockFor(normalized)

        jvmLock.withLock {
            appendUtf8LineWithDescriptorLease(normalized, line)
        }
    }

    private fun appendUtf8LineWithDescriptorLease(file: File, line: String) {
        val parent = file.parentFile ?: error("descriptor-bound append target has no parent directory")
        SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
        val parentIdentity = SovereignPathIdentity.snapshotDirectory(parent)
        val fileIdentity = SovereignPathIdentity.snapshot(file)
        val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)

        FileChannel.open(
            file.toPath(),
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        ).use { channel ->
            channel.lock().use {
                SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                SovereignPathIdentity.requireSameIdentity(fileIdentity, file)

                val startSize = channel.size()
                val expectedSize = Math.addExact(startSize, bytes.size.toLong())
                channel.position(startSize)
                writeFully(channel, ByteBuffer.wrap(bytes))
                channel.force(true)

                require(channel.size() == expectedSize) {
                    "descriptor-bound append size changed outside the held lease"
                }
                SovereignPathIdentity.requireSameIdentity(fileIdentity, file)
                SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            }
        }
    }

    /**
     * Atomically publishes [source] over [target] only when both paths remain inside the same
     * pinned parent directory. Existing source/target leaves must be regular files, never
     * symlinks. [expectedSourceIdentity] lets a caller bind publication to the exact temp file
     * it created before entering this operation.
     *
     * The source channel stays open across Files.move(). After rename, force(true) flushes the
     * exact source inode/file object now expected at [target]; the target pathname must then
     * resolve to the same fileKey. If the filesystem provider exposes no fileKey, this fast
     * path fails closed and DurableJournalIo falls back to the explicit R3 replacement protocol.
     * AtomicMoveNotSupportedException is intentionally allowed to propagate for the same reason.
     */
    fun publishAtomicReplacing(
        source: File,
        target: File,
        expectedSourceIdentity: SovereignPathIdentity.Snapshot? = null
    ) {
        val sourceParent = source.parentFile ?: error("atomic publication source has no parent directory")
        val targetParent = target.parentFile ?: error("atomic publication target has no parent directory")
        require(sourceParent.absoluteFile.normalize() == targetParent.absoluteFile.normalize()) {
            "atomic publication source and target must share one parent directory"
        }
        require(source.absoluteFile.normalize() != target.absoluteFile.normalize()) {
            "atomic publication source and target must differ"
        }

        SovereignPathIdentity.requireDirectory(sourceParent, allowMissingLeaf = false)
        val parentIdentity = SovereignPathIdentity.snapshotDirectory(sourceParent)
        val sourceIdentity = expectedSourceIdentity ?: SovereignPathIdentity.snapshot(source)
        SovereignPathIdentity.requireSameIdentity(sourceIdentity, source)
        val normalizedTarget = target.absoluteFile.normalize()

        TargetDescriptorLeaseRegistry.lockFor(normalizedTarget).withLock {
            val targetPath = target.toPath()
            val targetExisted = Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
            if (targetExisted) {
                val targetIdentity = SovereignPathIdentity.snapshot(target)
                FileChannel.open(
                    targetPath,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                ).use { targetChannel ->
                    targetChannel.lock().use {
                        SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, sourceParent)
                        SovereignPathIdentity.requireSameIdentity(targetIdentity, target)
                        publishAtomicReplacingUnderTargetLease(
                            source,
                            target,
                            sourceParent,
                            parentIdentity,
                            sourceIdentity,
                            targetExpectedAbsent = false
                        )
                    }
                }
            } else {
                publishAtomicReplacingUnderTargetLease(
                    source,
                    target,
                    sourceParent,
                    parentIdentity,
                    sourceIdentity,
                    targetExpectedAbsent = true
                )
            }
        }
    }

    private fun publishAtomicReplacingUnderTargetLease(
        source: File,
        target: File,
        parent: File,
        parentIdentity: SovereignPathIdentity.Snapshot,
        sourceIdentity: SovereignPathIdentity.Snapshot,
        targetExpectedAbsent: Boolean
    ) {
        val targetPath = target.toPath()
        FileChannel.open(
            source.toPath(),
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        ).use { sourceChannel ->
            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            SovereignPathIdentity.requireSameIdentity(sourceIdentity, source)
            if (targetExpectedAbsent) {
                require(!Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                    "atomic publication target appeared before rename: ${target.path}"
                }
            }

            Files.move(
                source.toPath(),
                targetPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )

            sourceChannel.force(true)
            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            requirePublishedSourceIdentity(sourceIdentity, target)
        }

        DescriptorBoundFileIo.syncDirectory(parent)
        SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
        requirePublishedSourceIdentity(sourceIdentity, target)
    }

    private fun requirePublishedSourceIdentity(
        sourceIdentity: SovereignPathIdentity.Snapshot,
        target: File
    ) {
        val published = SovereignPathIdentity.snapshot(target)
        val sourceKey = sourceIdentity.fileKey
        val publishedKey = published.fileKey
        require(sourceKey != null && publishedKey != null) {
            "atomic publication cannot prove renamed file identity without fileKey"
        }
        require(sourceKey == publishedKey) {
            "atomic publication target is not the prepared source file object"
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }
}
