package io.amper.neuroos.core

enum class ReflexRuntimeResourceMode {
    NORMAL,
    PRESSURED,
    BLOCKED
}

enum class ReflexRuntimeResidencyHint {
    KEEP_WARM,
    RELEASE_AFTER_DECISION
}

data class ReflexRuntimeResourceDecision(
    val mode: ReflexRuntimeResourceMode,
    val allowLearnedInference: Boolean,
    val memoryBudgetMb: Int,
    val thermalClass: Int,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val minInterInferenceMs: Long = 0L,
    val residencyHint: ReflexRuntimeResidencyHint = ReflexRuntimeResidencyHint.KEEP_WARM,
    val reason: String
) {
    init {
        require(memoryBudgetMb >= 0)
        require(batteryPercent == null || batteryPercent in 0..100)
        require(minInterInferenceMs >= 0L)
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
 * Reuses ResourceGovernor for RAM/thermal and an optional shared DeviceStatusSource for battery.
 *
 * Battery data is sampled per turn and never persisted here. When the Android host supplies one
 * DeviceStatusSource, that same instance may also back device.status.read, avoiding a second monitor.
 */
class ResourceGovernorReflexRuntimeResourcePolicy(
    private val governor: ResourceGovernor,
    private val deviceStatusSource: DeviceStatusSource? = null
) : ReflexRuntimeResourcePolicy {
    override fun evaluate(
        adaptivePolicy: ReflexAdaptiveRuntimePolicy
    ): ReflexRuntimeResourceDecision {
        val budget = governor.currentBudget()
        val baseAllowed = governor.allows(1)
        val device = runCatching { deviceStatusSource?.snapshot() }.getOrNull()
        val battery = device?.batteryPercent
        val charging = device?.charging

        fun decision(
            mode: ReflexRuntimeResourceMode,
            allow: Boolean,
            reason: String,
            minInterInferenceMs: Long = 0L,
            residencyHint: ReflexRuntimeResidencyHint = ReflexRuntimeResidencyHint.KEEP_WARM
        ) = ReflexRuntimeResourceDecision(
            mode = mode,
            allowLearnedInference = allow,
            memoryBudgetMb = budget.memoryMb.coerceAtLeast(0),
            thermalClass = budget.thermalClass,
            batteryPercent = battery,
            charging = charging,
            minInterInferenceMs = minInterInferenceMs,
            residencyHint = residencyHint,
            reason = reason
        )

        if (
            !baseAllowed ||
            budget.thermalClass >= CRITICAL_THERMAL_CLASS ||
            budget.memoryMb < CRITICAL_MEMORY_MB
        ) {
            return decision(
                mode = ReflexRuntimeResourceMode.BLOCKED,
                allow = false,
                reason = "critical thermal or memory pressure",
                residencyHint = ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION
            )
        }

        if (
            budget.thermalClass >= SEVERE_THERMAL_CLASS ||
            budget.memoryMb < SEVERE_MEMORY_MB
        ) {
            return decision(
                mode = ReflexRuntimeResourceMode.BLOCKED,
                allow = false,
                reason = "severe mobile resource pressure",
                residencyHint = ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION
            )
        }

        if (charging != true && battery != null) {
            if (battery <= CRITICAL_BATTERY_PERCENT) {
                return decision(
                    mode = ReflexRuntimeResourceMode.BLOCKED,
                    allow = false,
                    reason = "critical battery conservation",
                    minInterInferenceMs = CRITICAL_BATTERY_INTERVAL_MS,
                    residencyHint = ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION
                )
            }

            if (battery <= LOW_BATTERY_PERCENT) {
                val latency = adaptivePolicy.latencyEwmaMs
                val allow = latency != null && latency <= LOW_BATTERY_LATENCY_LIMIT_MS
                return decision(
                    mode = ReflexRuntimeResourceMode.PRESSURED,
                    allow = allow,
                    reason = if (allow) {
                        "low battery; fast learned Reflex permitted with burst throttling"
                    } else {
                        "low battery; learned Reflex cost is unproven or too high"
                    },
                    minInterInferenceMs = LOW_BATTERY_INTERVAL_MS,
                    residencyHint = ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION
                )
            }

            if (battery <= CONSERVE_BATTERY_PERCENT) {
                val latency = adaptivePolicy.latencyEwmaMs
                val allow = latency == null || latency <= CONSERVE_LATENCY_LIMIT_MS
                return decision(
                    mode = ReflexRuntimeResourceMode.PRESSURED,
                    allow = allow,
                    reason = if (allow) {
                        "battery conservation; learned Reflex permitted with transient residency"
                    } else {
                        "battery conservation; learned Reflex latency exceeds energy budget"
                    },
                    minInterInferenceMs = CONSERVE_INTERVAL_MS,
                    residencyHint = ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION
                )
            }
        }

        if (
            budget.thermalClass >= MODERATE_THERMAL_CLASS ||
            budget.memoryMb < MODERATE_MEMORY_MB
        ) {
            val learnedLatency = adaptivePolicy.latencyEwmaMs
            val allow = learnedLatency == null || learnedLatency <= PRESSURED_LATENCY_LIMIT_MS
            return decision(
                mode = ReflexRuntimeResourceMode.PRESSURED,
                allow = allow,
                reason = if (allow) {
                    "moderate pressure; learned Reflex remains within mobile latency budget"
                } else {
                    "moderate pressure; learned Reflex latency exceeds mobile budget"
                },
                residencyHint = ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION
            )
        }

        return decision(
            mode = ReflexRuntimeResourceMode.NORMAL,
            allow = true,
            reason = if (charging == true) {
                "charging and mobile resources permit learned Reflex inference"
            } else {
                "mobile resources permit learned Reflex inference"
            }
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

        const val CRITICAL_BATTERY_PERCENT = 8
        const val LOW_BATTERY_PERCENT = 15
        const val CONSERVE_BATTERY_PERCENT = 30
        const val LOW_BATTERY_LATENCY_LIMIT_MS = 120.0
        const val CONSERVE_LATENCY_LIMIT_MS = 180.0
        const val CRITICAL_BATTERY_INTERVAL_MS = 5_000L
        const val LOW_BATTERY_INTERVAL_MS = 2_000L
        const val CONSERVE_INTERVAL_MS = 750L
    }
}
