package io.amper.neuroos.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-local execution lanes for Android UI work.
 *
 * Interactive work must never queue behind optional/background learning maintenance. Both lanes
 * remain single-threaded so each class of work preserves deterministic ordering, while Titan and
 * the existing execution-admission gates remain authoritative for model/backend concurrency.
 */
class AmperExecutionLanes(
    private val interactiveExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedThreadFactory("amper-interactive")),
    private val maintenanceExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedThreadFactory("amper-maintenance"))
) : AutoCloseable {
    fun executeInteractive(block: () -> Unit) {
        interactiveExecutor.execute(block)
    }

    fun executeMaintenance(block: () -> Unit) {
        maintenanceExecutor.execute(block)
    }

    override fun close() {
        interactiveExecutor.shutdown()
        maintenanceExecutor.shutdown()
    }

    companion object {
        private fun namedThreadFactory(prefix: String): ThreadFactory {
            val counter = AtomicInteger(0)
            return ThreadFactory { runnable ->
                Thread(
                    runnable,
                    "$prefix-${counter.incrementAndGet()}"
                ).apply {
                    isDaemon = true
                }
            }
        }
    }
}
