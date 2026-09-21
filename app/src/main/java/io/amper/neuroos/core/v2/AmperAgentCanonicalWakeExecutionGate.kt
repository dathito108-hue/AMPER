package io.amper.neuroos.core.v2

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-local serialization boundary for canonical Agent wake execution.
 *
 * Durable plan digests remain the replay authority. This gate prevents two concurrent verified
 * wake consumers from racing between exact restore and the single-step persistent-plan advance.
 * Future EVENT_WAKE execution must reuse this same gate rather than introducing another executor
 * lock.
 */
object AmperAgentCanonicalWakeExecutionGate {
    private val lock = ReentrantLock()

    fun <T> exclusive(block: () -> T): T = lock.withLock(block)
}
