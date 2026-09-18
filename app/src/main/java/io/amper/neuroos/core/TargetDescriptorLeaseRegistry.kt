package io.amper.neuroos.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * JVM-local half of the exact-target descriptor lease.
 *
 * FileChannel locks serialize cooperating processes, while this registry prevents two threads
 * in the same JVM from tripping OverlappingFileLockException before the operating-system lock
 * can provide backpressure. Phase 58 shares the same per-target lock between append and
 * snapshot read/repair so both operations enter the descriptor lease through one JVM domain.
 */
internal object TargetDescriptorLeaseRegistry {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun lockFor(file: File): ReentrantLock = locks.computeIfAbsent(
        file.absoluteFile.normalize().path
    ) { ReentrantLock(true) }
}
