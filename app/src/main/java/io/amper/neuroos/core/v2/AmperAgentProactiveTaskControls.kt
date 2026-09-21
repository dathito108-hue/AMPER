package io.amper.neuroos.core.v2

enum class AmperAgentProactiveTaskControl {
    REFRESH_STATUS,
    OPEN_GOVERNED_PLAN,
    OPEN_RECOVERY
}

data class AmperAgentProactiveTaskControlContext(
    val planAvailable: Boolean,
    val exactRecoveryTargetAvailable: Boolean
) {
    init {
        require(!exactRecoveryTargetAvailable || planAvailable) {
            "recovery navigation requires a canonical plan"
        }
    }
}

/**
 * Phase667 user-facing proactive control policy.
 *
 * The policy deliberately contains navigation/refresh controls only. Approval, rejection,
 * cancellation, plan advance, recovery reconciliation, scheduling, and execution remain owned by
 * their existing canonical surfaces.
 */
object AmperAgentProactiveTaskControlPolicy {
    fun controls(
        context: AmperAgentProactiveTaskControlContext
    ): Set<AmperAgentProactiveTaskControl> = buildSet {
        add(AmperAgentProactiveTaskControl.REFRESH_STATUS)
        if (context.planAvailable) {
            add(AmperAgentProactiveTaskControl.OPEN_GOVERNED_PLAN)
        }
        if (context.exactRecoveryTargetAvailable) {
            add(AmperAgentProactiveTaskControl.OPEN_RECOVERY)
        }
    }
}

/**
 * UI invalidation sequence only. This value is never persisted and never participates in plan,
 * lifecycle, scheduler, receipt, acknowledgement, or execution identity.
 */
object AmperAgentProactiveSurfaceRefreshRevision {
    fun next(current: Long): Long =
        if (current == Long.MAX_VALUE) 0L else current + 1L
}
