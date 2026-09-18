package io.amper.neuroos.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes one complete G1/G2/G3/A1 authority-set operation across threads and processes.
 *
 * Individual recovery-authority leaves already use descriptor-bound leases. Phase 63 adds
 * this outer set lease so a reader cannot combine G2/G3/G1 snapshots from different moments
 * of one compaction or self-heal. The established ordering is:
 *
 * optional journal mutation lease -> recovery authority-set lease -> per-leaf descriptor lease.
 *
 * The persistent lock filename is intentionally target-derived and stable across restarts.
 */
internal object RecoveryEpochAuthorityLease {
    private val JVM_LOCKS = ConcurrentHashMap<String, ReentrantLock>()

    fun lockFileFor(target: File): File {
        val parent = target.parentFile ?: error("replacement target has no parent directory")
        return File(parent, target.name + ".recovery-epochs.lock")
    }

    fun <T> withExclusive(target: File, block: () -> T): T {
        val parent = target.parentFile ?: error("replacement target has no parent directory")
        DurableJournalIo.ensureDirectoryExistsDurably(parent)
        val lockFile = lockFileFor(target)
        val pathKey = lockFile.absoluteFile.normalize().path
        val jvmLock = JVM_LOCKS.computeIfAbsent(pathKey) { ReentrantLock(true) }
        return jvmLock.withLock {
            DescriptorBoundFileIo.withExclusiveLock(lockFile, block)
        }
    }
}
