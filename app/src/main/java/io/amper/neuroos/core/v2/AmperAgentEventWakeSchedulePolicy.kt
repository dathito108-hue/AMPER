package io.amper.neuroos.core.v2

data class AmperAgentEventWakeSchedulePlan(
    val dedupeKey: String,
    val minimumLatencyMs: Long,
    val persistedAcrossReboot: Boolean,
    val requiresBatteryNotLow: Boolean,
    val requiresStorageNotLow: Boolean
) {
    init {
        require(dedupeKey.isNotBlank())
        require(minimumLatencyMs >= 0L)
        require(persistedAcrossReboot)
    }
}

/**
 * Pure Phase657 Android scheduling policy for already-qualified EVENT_WAKE handoffs.
 *
 * It owns no Context, JobScheduler, planner, ToolFabric, monitor, or execution port. Only verified
 * READY handoffs are eligible for OS scheduling. Approval-blocked and terminal states fail closed.
 */
object AmperAgentEventWakeSchedulePolicy {
    const val MINIMUM_WAKE_LATENCY_MS: Long = 15_000L
    const val MAXIMUM_WAKE_LATENCY_MS: Long = 6L * 60L * 60L * 1_000L

    fun plan(
        handoff: AmperAgentEventWakeHandoff,
        requestedMinimumLatencyMs: Long = MINIMUM_WAKE_LATENCY_MS
    ): Result<AmperAgentEventWakeSchedulePlan> = runCatching {
        val envelope = AmperAgentEventWakeHandoffPolicy
            .verifyAndDecode(handoff)
            .getOrThrow()
        require(
            envelope.disposition ==
                AmperAgentEventWakeDisposition.READY_FOR_EXPLICIT_ADVANCE
        ) {
            "only READY proactive EVENT_WAKE handoffs may be scheduled"
        }
        require(requestedMinimumLatencyMs >= 0L) {
            "EVENT_WAKE minimum latency cannot be negative"
        }
        val boundedLatency = requestedMinimumLatencyMs
            .coerceAtLeast(MINIMUM_WAKE_LATENCY_MS)
            .coerceAtMost(MAXIMUM_WAKE_LATENCY_MS)

        AmperAgentEventWakeSchedulePlan(
            dedupeKey = handoff.dedupeKey,
            minimumLatencyMs = boundedLatency,
            persistedAcrossReboot = true,
            requiresBatteryNotLow = true,
            requiresStorageNotLow = true
        )
    }
}
