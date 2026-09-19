package io.amper.neuroos.core

data class ReflexDeferredJobPlan(
    val scheduled: Boolean,
    val minimumLatencyMs: Long,
    val requiresCharging: Boolean,
    val requiresDeviceIdle: Boolean,
    val requiresBatteryNotLow: Boolean,
    val requiresStorageNotLow: Boolean,
    val persistedAcrossReboot: Boolean
) {
    init {
        require(minimumLatencyMs >= 0L)
        if (!scheduled) {
            require(minimumLatencyMs == 0L)
        }
    }
}

/**
 * Platform-neutral plan consumed by Android JobScheduler.
 *
 * OS-native deferred learning is intentionally conservative: the out-of-process wake-up path waits
 * for charging, idle, non-low-battery and non-low-storage signals. The process-resident loop remains
 * responsible for quicker opportunistic work while the app is alive.
 */
object ReflexDeferredJobPlanner {
    fun plan(
        ticket: ReflexLearningMaintenanceTicket?,
        nowEpochMs: Long
    ): ReflexDeferredJobPlan {
        require(nowEpochMs >= 0L)
        if (ticket == null) {
            return ReflexDeferredJobPlan(
                scheduled = false,
                minimumLatencyMs = 0L,
                requiresCharging = true,
                requiresDeviceIdle = true,
                requiresBatteryNotLow = true,
                requiresStorageNotLow = true,
                persistedAcrossReboot = true
            )
        }
        return ReflexDeferredJobPlan(
            scheduled = true,
            minimumLatencyMs = (ticket.notBeforeEpochMs - nowEpochMs).coerceAtLeast(0L),
            requiresCharging = true,
            requiresDeviceIdle = true,
            requiresBatteryNotLow = true,
            requiresStorageNotLow = true,
            persistedAcrossReboot = true
        )
    }
}
