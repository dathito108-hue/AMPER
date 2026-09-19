package io.amper.neuroos.core

enum class ReflexRuntimeResourceMode {
    NORMAL,
    PRESSURED,
    BLOCKED
}

data class ReflexRuntimeResourceDecision(
    val mode: ReflexRuntimeResourceMode,
    val allowLearnedInference: Boolean,
    val memoryBudgetMb: Int,
    val thermalClass: Int,
    val reason: String
) {
    init {
        require(memoryBudgetMb >= 0)
        require(reason.isNotBlank())
        if (mode == ReflexRuntimeResourceMode.BLOCKED) {
            require(!allowLearnedInference)
        }
    }

    val authorityBearing: Boolean
        get() = false
}

fun interface ReflexRuntimeResourcePolicy {
    fun evaluate(adaptivePolicy: ReflexAdaptiveRuntimePolicy): ReflexRuntimeResourceDecision
}

object UnconstrainedReflexRuntimeResourcePolicy : ReflexRuntimeResourcePolicy {
    override fun evaluate(
        adaptivePolicy: ReflexAdaptiveRuntimePolicy
    ): ReflexRuntimeResourceDecision = ReflexRuntimeResourceDecision(
        mode = ReflexRuntimeResourceMode.NORMAL,
        allowLearnedInference = true,
        memoryBudgetMb = Int.MAX_VALUE,
        thermalClass = 0,
        reason = "no mobile resource policy attached"
    )
}

/**
 * Phase466-470 reuses the existing ResourceGovernor instead of creating a second Android monitor.
 *
 * Thermal classes use the same monotonic severity convention already consumed by
 * AndroidResourceGovernor. Resource pressure controls whether learned System-1 is attempted for the
 * current turn only; it never changes checkpoint admission, persisted activation or model health.
 */
class ResourceGovernorReflexRuntimeResourcePolicy(
    private val governor: ResourceGovernor
) : ReflexRuntimeResourcePolicy {
    override fun evaluate(
        adaptivePolicy: ReflexAdaptiveRuntimePolicy
    ): ReflexRuntimeResourceDecision {
        val budget = governor.currentBudget()
        val baseAllowed = governor.allows(1)

        if (
            !baseAllowed ||
            budget.thermalClass >= CRITICAL_THERMAL_CLASS ||
            budget.memoryMb < CRITICAL_MEMORY_MB
        ) {
            return ReflexRuntimeResourceDecision(
                mode = ReflexRuntimeResourceMode.BLOCKED,
                allowLearnedInference = false,
                memoryBudgetMb = budget.memoryMb.coerceAtLeast(0),
                thermalClass = budget.thermalClass,
                reason = "critical thermal or memory pressure"
            )
        }

        if (
            budget.thermalClass >= SEVERE_THERMAL_CLASS ||
            budget.memoryMb < SEVERE_MEMORY_MB
        ) {
            return ReflexRuntimeResourceDecision(
                mode = ReflexRuntimeResourceMode.BLOCKED,
                allowLearnedInference = false,
                memoryBudgetMb = budget.memoryMb.coerceAtLeast(0),
                thermalClass = budget.thermalClass,
                reason = "severe mobile resource pressure"
            )
        }

        if (
            budget.thermalClass >= MODERATE_THERMAL_CLASS ||
            budget.memoryMb < MODERATE_MEMORY_MB
        ) {
            val learnedLatency = adaptivePolicy.latencyEwmaMs
            val allow = learnedLatency == null || learnedLatency <= PRESSURED_LATENCY_LIMIT_MS
            return ReflexRuntimeResourceDecision(
                mode = ReflexRuntimeResourceMode.PRESSURED,
                allowLearnedInference = allow,
                memoryBudgetMb = budget.memoryMb.coerceAtLeast(0),
                thermalClass = budget.thermalClass,
                reason = if (allow) {
                    "moderate pressure; learned Reflex remains within mobile latency budget"
                } else {
                    "moderate pressure; learned Reflex latency exceeds mobile budget"
                }
            )
        }

        return ReflexRuntimeResourceDecision(
            mode = ReflexRuntimeResourceMode.NORMAL,
            allowLearnedInference = true,
            memoryBudgetMb = budget.memoryMb.coerceAtLeast(0),
            thermalClass = budget.thermalClass,
            reason = "mobile resources permit learned Reflex inference"
        )
    }

    companion object {
        const val MODERATE_THERMAL_CLASS = 2
        const val SEVERE_THERMAL_CLASS = 3
        const val CRITICAL_THERMAL_CLASS = 4

        const val MODERATE_MEMORY_MB = 384
        const val SEVERE_MEMORY_MB = 192
        const val CRITICAL_MEMORY_MB = 96

        const val PRESSURED_LATENCY_LIMIT_MS = 180.0
    }
}
