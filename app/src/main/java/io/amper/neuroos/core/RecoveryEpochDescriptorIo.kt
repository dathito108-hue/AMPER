package io.amper.neuroos.core

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import kotlin.concurrent.withLock

/**
 * Descriptor-bound filesystem access for G1/G2/G3/A1 recovery authority state.
 *
 * Phase 62 removes the remaining File/RandomAccessFile convenience I/O from
 * RecoveryEpochJournal. Every existing recovery-authority leaf is validated with
 * NOFOLLOW semantics, reads inherit the shared exact-target snapshot lease, and G1
 * compaction truncates the exact opened inode under an exclusive descriptor lock.
 */
internal object RecoveryEpochDescriptorIo {
    fun existsManaged(file: File): Boolean {
        val normalized = file.absoluteFile.normalize()
        return TargetDescriptorLeaseRegistry.lockFor(normalized).withLock {
            val path = normalized.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return@withLock false
            }
            SovereignPathIdentity.requireManagedFile(normalized, allowMissingLeaf = false)
            true
        }
    }

    fun readTextIfExists(file: File): String? {
        val normalized = file.absoluteFile.normalize()
        return TargetDescriptorLeaseRegistry.lockFor(normalized).withLock {
            if (!Files.exists(normalized.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@withLock null
            }
            SovereignPathIdentity.requireManagedFile(normalized, allowMissingLeaf = false)
            DescriptorBoundFileIo.readUtf8Text(normalized)
        }
    }

    fun readCompleteLinesRecoveringTornTailIfExists(file: File): List<String> {
        val normalized = file.absoluteFile.normalize()
        return TargetDescriptorLeaseRegistry.lockFor(normalized).withLock {
            if (!Files.exists(normalized.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@withLock emptyList()
            }
            SovereignPathIdentity.requireManagedFile(normalized, allowMissingLeaf = false)
            DescriptorBoundFileIo.readCompleteUtf8LinesRecoveringTornTail(normalized)
        }
    }

    fun truncateExistingAndForce(file: File, length: Long = 0L) {
        require(length >= 0L) { "negative recovery epoch truncate length" }
        val normalized = file.absoluteFile.normalize()
        TargetDescriptorLeaseRegistry.lockFor(normalized).withLock {
            val parent = normalized.parentFile
                ?: error("recovery epoch file has no parent directory")
            SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
            val parentIdentity = SovereignPathIdentity.snapshotDirectory(parent)
            val fileIdentity = SovereignPathIdentity.snapshot(normalized)

            FileChannel.open(
                normalized.toPath(),
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            ).use { channel ->
                channel.lock().use {
                    SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                    SovereignPathIdentity.requireSameIdentity(fileIdentity, normalized)
                    channel.truncate(length)
                    channel.force(true)
                    require(channel.size() == length) {
                        "recovery epoch truncate did not reach requested length"
                    }
                    SovereignPathIdentity.requireSameIdentity(fileIdentity, normalized)
                    SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                }
            }
        }
    }
}
