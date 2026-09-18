package io.amper.neuroos.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes journal + head-anchor mutations across independent AMPER instances that
 * share the same filesystem path. A JVM-local lock prevents OverlappingFileLockException
 * between threads in one process; FileChannel.lock() provides the cross-process boundary.
 *
 * Phase 50 additionally refuses symbolic-link/path-substituted journal namespaces and opens
 * the process lock with NOFOLLOW_LINKS. Provider file identity is checked while the lock is
 * held so replacing the lock inode or its parent directory fails closed.
 *
 * Phase 51 reserves the entire derived sidecar namespace before bootstrap, so a journal root
 * can never masquerade as another journal's H1/K1/lock/recovery/rewrite artifact.
 *
 * Phase 52 moves lock creation/opening behind DescriptorBoundFileIo: missing lock leaves use
 * CREATE_NEW, existing leaves are snapshotted before open, and parent/leaf identities are
 * revalidated before and after the descriptor-held lock body.
 */
internal class MemoryJournalProcessLock(
    journalFile: File
) {
    private val journalFile = journalFile.absoluteFile.normalize()
    private val lockFile = File(this.journalFile.parentFile, this.journalFile.name + ".lock")
    private val pathKey = lockFile.absoluteFile.normalize().path
    private val jvmLock = JVM_LOCKS.computeIfAbsent(pathKey) { ReentrantLock(true) }

    init {
        // Parent may not exist yet; the durable create path will materialize it later.
        SovereignPathIdentity.requireNamespaceRootName(this.journalFile)
        SovereignPathIdentity.requireManagedFile(this.journalFile)
        this.journalFile.parentFile?.let { SovereignPathIdentity.requireDirectory(it) }
    }

    fun <T> exclusive(block: () -> T): T = jvmLock.withLock {
        val parent = lockFile.parentFile ?: error("memory journal lock has no parent directory")
        DurableJournalIo.ensureDirectoryExistsDurably(parent)
        SovereignPathIdentity.requireManagedNamespace(journalFile)
        SovereignPathIdentity.requireManagedFile(lockFile)

        DescriptorBoundFileIo.withExclusiveLock(lockFile) {
            SovereignPathIdentity.requireManagedNamespace(journalFile)
            block()
        }
    }

    companion object {
        private val JVM_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
    }
}
