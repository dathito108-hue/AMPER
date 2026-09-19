package io.amper.neuroos.core

import kotlin.math.min

enum class ReflexLearningResourceMode {
    READY,
    LIMITED,
    DEFERRED
}

data class ReflexLearningDemand(
    val freshCandidates: Int,
    val replayCandidates: Int,
    val learningValue: Double,
    val hardExamples: Int,
    val disagreementExamples: Int,
    val novelCapabilities: Int,
    val predictedDurationMs: Long? = null,
    val historicalCostSamples: Int = 0,
    val historicalLearningValuePerSecond: Double? = null
) {
    init {
        require(freshCandidates >= 0)
        require(replayCandidates >= 0)
        require(learningValue in 0.0..1.0)
        require(hardExamples in 0..freshCandidates)
        require(disagreementExamples in 0..freshCandidates)
        require(novelCapabilities >= 0)
        require(predictedDurationMs == null || predictedDurationMs >= 0L)
        require(historicalCostSamples >= 0)
        require(
            historicalLearningValuePerSecond == null ||
                (historicalLearningValuePerSecond.isFinite() &&
                    historicalLearningValuePerSecond >= 0.0)
        )
    }

    val highValue: Boolean
        get() =
            novelCapabilities > 0 ||
                learningValue >= HIGH_VALUE_THRESHOLD ||
                disagreementExamples >= MIN_HIGH_VALUE_DISAGREEMENTS

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val HIGH_VALUE_THRESHOLD = 0.62
        const val MIN_HIGH_VALUE_DISAGREEMENTS = 8
    }
}

data class ReflexLearningResourceDecision(
    val mode: ReflexLearningResourceMode,
    val allowTraining: Boolean,
    val maxFreshExamples: Int,
    val maxReplayExamples: Int,
    val memoryBudgetMb: Int,
    val thermalClass: Int,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val reason: String
) {
    init {
        require(maxFreshExamples >= 0)
        require(maxReplayExamples >= 0)
        require(memoryBudgetMb >= 0)
        require(batteryPercent == null || batteryPercent in 0..100)
        require(reason.isNotBlank())
        if (!allowTraining) {
            require(mode == ReflexLearningResourceMode.DEFERRED)
            require(maxFreshExamples == 0)
            require(maxReplayExamples == 0)
        } else {
            require(mode != ReflexLearningResourceMode.DEFERRED)
            require(maxFreshExamples >= MIN_TRAINING_FRESH_BUDGET)
            require(maxReplayExamples >= MIN_REPLAY_BUDGET)
        }
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MIN_TRAINING_FRESH_BUDGET = 48
        const val MIN_REPLAY_BUDGET = 16
    }
}

fun interface ReflexLearningResourcePolicy {
    fun evaluate(demand: ReflexLearningDemand): ReflexLearningResourceDecision
}

object UnconstrainedReflexLearningResourcePolicy : ReflexLearningResourcePolicy {
    override fun evaluate(demand: ReflexLearningDemand): ReflexLearningResourceDecision =
        ReflexLearningResourceDecision(
            mode = ReflexLearningResourceMode.READY,
            allowTraining = true,
            maxFreshExamples = 96,
            maxReplayExamples = 96,
            memoryBudgetMb = Int.MAX_VALUE,
            thermalClass = 0,
            reason = "no mobile learning resource policy attached"
        )
}

/**
 * Phase511-515 mobile scheduler for continual Reflex learning.
 *
 * Training is deliberately more conservative than inference. It may be deferred without touching
 * the active champion; a later lifecycle maintenance call re-evaluates live resources and resumes
 * from the same immutable evidence when conditions improve.
 *
 * The policy never changes labels, promotion criteria, runtime authority or tool execution.
 */
class ResourceGovernorReflexLearningResourcePolicy(
    private val governor: ResourceGovernor,
    private val deviceStatusSource: DeviceStatusSource? = null,
    private val telemetry: ReflexLearningSchedulerTelemetry =
        NoopReflexLearningSchedulerTelemetry,
    private val clock: () -> Long = System::currentTimeMillis
) : ReflexLearningResourcePolicy {
    private var deferredState = false
    private var recoveryHealthySamples = 0

    @Synchronized
    override fun evaluate(demand: ReflexLearningDemand): ReflexLearningResourceDecision {
        val budget = governor.currentBudget()
        val device = runCatching { deviceStatusSource?.snapshot() }.getOrNull()
        val memoryMb = min(
            budget.memoryMb.coerceAtLeast(0),
            device?.availableMemoryMb
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: Int.MAX_VALUE
        )
        val thermal = maxOf(
            budget.thermalClass,
            device?.thermalStatus ?: budget.thermalClass
        )
        val battery = device?.batteryPercent
        val charging = device?.charging
        val storageFreeMb = device?.appStorageFreeMb
        val tuning = telemetry.tuningProfile()
        val pressuredMinLearningValue = (
            PRESSURED_MIN_LEARNING_VALUE + tuning.minimumLearningValueBoost
            ).coerceAtMost(MAX_TUNED_PRESSURED_MIN_LEARNING_VALUE)
        val conserveMaxPredictedMs =
            tuning.scaleDuration(CONSERVE_MAX_PREDICTED_MS)
        val pressuredMaxPredictedMs =
            tuning.scaleDuration(PRESSURED_MAX_PREDICTED_MS)
        val normalTargetPredictedMs =
            tuning.scaleDuration(NORMAL_TARGET_PREDICTED_MS)
        val maxUnpluggedPredictedMs =
            tuning.scaleDuration(MAX_UNPLUGGED_PREDICTED_MS)

        fun deferred(reason: String) = ReflexLearningResourceDecision(
            mode = ReflexLearningResourceMode.DEFERRED,
            allowTraining = false,
            maxFreshExamples = 0,
            maxReplayExamples = 0,
            memoryBudgetMb = memoryMb,
            thermalClass = thermal,
            batteryPercent = battery,
            charging = charging,
            reason = reason
        )

        fun allowed(
            mode: ReflexLearningResourceMode,
            freshBudget: Int,
            replayBudget: Int,
            reason: String
        ) = ReflexLearningResourceDecision(
            mode = mode,
            allowTraining = true,
            maxFreshExamples = freshBudget,
            maxReplayExamples = replayBudget,
            memoryBudgetMb = memoryMb,
            thermalClass = thermal,
            batteryPercent = battery,
            charging = charging,
            reason = reason
        )

        if (
            !governor.allows(1) ||
            thermal >= SEVERE_THERMAL_CLASS ||
            memoryMb < CRITICAL_MEMORY_MB ||
            device?.lowMemory == true
        ) {
            return stabilize(deferred("training deferred by thermal or memory pressure"), demand)
        }

        if (storageFreeMb != null && storageFreeMb < MIN_STORAGE_FREE_MB) {
            return stabilize(deferred("training deferred to preserve app storage reserve"), demand)
        }

        if (charging != true && battery != null && battery <= MIN_BATTERY_PERCENT) {
            return stabilize(deferred("training deferred for battery conservation"), demand)
        }

        val predictedDurationMs = demand.predictedDurationMs
        if (
            charging != true &&
            predictedDurationMs != null &&
            predictedDurationMs > maxUnpluggedPredictedMs &&
            demand.novelCapabilities == 0
        ) {
            return stabilize(
                deferred("historical training cost exceeds unplugged learning budget"),
                demand
            )
        }
        if (
            charging != true &&
            demand.historicalCostSamples >= MIN_COST_SAMPLES &&
            demand.historicalLearningValuePerSecond != null &&
            demand.historicalLearningValuePerSecond < MIN_HISTORICAL_VALUE_PER_SECOND &&
            !demand.highValue
        ) {
            return stabilize(
                deferred("historical learning yield is too low for current mobile budget"),
                demand
            )
        }

        if (
            charging != true &&
            battery != null &&
            battery <= CONSERVE_BATTERY_PERCENT
        ) {
            if (
                !demand.highValue ||
                thermal > LIGHT_THERMAL_CLASS ||
                memoryMb < CONSERVE_MEMORY_MB ||
                (
                    predictedDurationMs != null &&
                        predictedDurationMs > conserveMaxPredictedMs &&
                        demand.novelCapabilities == 0
                    )
            ) {
                return stabilize(
                    deferred("training deferred until charging or higher-value evidence"),
                    demand
                )
            }
            return stabilize(
                allowed(
                    mode = ReflexLearningResourceMode.LIMITED,
                    freshBudget = tuning.scaleBudget(
                        CONSERVE_FRESH_BUDGET,
                        ReflexLearningResourceDecision.MIN_TRAINING_FRESH_BUDGET
                    ),
                    replayBudget = tuning.scaleBudget(
                        CONSERVE_REPLAY_BUDGET,
                        ReflexLearningResourceDecision.MIN_REPLAY_BUDGET
                    ),
                    reason = "high-value learning allowed under battery-conservation budget"
                ),
                demand
            )
        }

        if (
            thermal >= MODERATE_THERMAL_CLASS ||
            memoryMb < NORMAL_MEMORY_MB ||
            budget.maxConcurrentAgents <= 1
        ) {
            if (
                (
                    demand.learningValue < pressuredMinLearningValue ||
                        (
                            predictedDurationMs != null &&
                                predictedDurationMs > pressuredMaxPredictedMs
                            )
                    ) &&
                demand.novelCapabilities == 0
            ) {
                return stabilize(
                    deferred("training deferred under mobile pressure for low-value update"),
                    demand
                )
            }
            return stabilize(
                allowed(
                    mode = ReflexLearningResourceMode.LIMITED,
                    freshBudget = tuning.scaleBudget(
                        PRESSURED_FRESH_BUDGET,
                        ReflexLearningResourceDecision.MIN_TRAINING_FRESH_BUDGET
                    ),
                    replayBudget = tuning.scaleBudget(
                        PRESSURED_REPLAY_BUDGET,
                        ReflexLearningResourceDecision.MIN_REPLAY_BUDGET
                    ),
                    reason = "resource pressure permits only a bounded high-value learning update"
                ),
                demand
            )
        }

        if (
            charging != true &&
            predictedDurationMs != null &&
            predictedDurationMs > normalTargetPredictedMs
        ) {
            return stabilize(
                allowed(
                    mode = ReflexLearningResourceMode.LIMITED,
                    freshBudget = tuning.scaleBudget(
                        PRESSURED_FRESH_BUDGET,
                        ReflexLearningResourceDecision.MIN_TRAINING_FRESH_BUDGET
                    ),
                    replayBudget = tuning.scaleBudget(
                        PRESSURED_REPLAY_BUDGET,
                        ReflexLearningResourceDecision.MIN_REPLAY_BUDGET
                    ),
                    reason = "historical cost model limits this unplugged learning update"
                ),
                demand
            )
        }

        return stabilize(
            allowed(
                mode = ReflexLearningResourceMode.READY,
                freshBudget = tuning.scaleBudget(
                    NORMAL_FRESH_BUDGET,
                    ReflexLearningResourceDecision.MIN_TRAINING_FRESH_BUDGET
                ),
                replayBudget = tuning.scaleBudget(
                    NORMAL_REPLAY_BUDGET,
                    ReflexLearningResourceDecision.MIN_REPLAY_BUDGET
                ),
                reason = if (charging == true) {
                    "charging and mobile resources permit full Reflex learning budget"
                } else {
                    "mobile resources permit full Reflex learning budget"
                }
            ),
            demand
        )
    }

    private fun stabilize(
        decision: ReflexLearningResourceDecision,
        demand: ReflexLearningDemand
    ): ReflexLearningResourceDecision {
        val stabilized = when {
            !decision.allowTraining -> {
                deferredState = true
                recoveryHealthySamples = 0
                decision
            }

            !deferredState -> decision

            decision.charging == true || demand.highValue -> {
                deferredState = false
                recoveryHealthySamples = 0
                decision
            }

            else -> {
                recoveryHealthySamples += 1
                if (recoveryHealthySamples < RECOVERY_HEALTHY_CONFIRMATIONS) {
                    decision.copy(
                        mode = ReflexLearningResourceMode.DEFERRED,
                        allowTraining = false,
                        maxFreshExamples = 0,
                        maxReplayExamples = 0,
                        reason =
                            "resource recovery hysteresis waiting for another healthy sample; " +
                                "pending_value=" + demand.learningValue
                    )
                } else {
                    deferredState = false
                    recoveryHealthySamples = 0
                    decision
                }
            }
        }
        telemetry.observeDecision(
            demand = demand,
            decision = stabilized,
            observedAtEpochMs = clock().coerceAtLeast(0L)
        )
        return stabilized
    }

    companion object {
        const val LIGHT_THERMAL_CLASS = 1
        const val MODERATE_THERMAL_CLASS = 2
        const val SEVERE_THERMAL_CLASS = 3

        const val CRITICAL_MEMORY_MB = 256
        const val CONSERVE_MEMORY_MB = 512
        const val NORMAL_MEMORY_MB = 512
        const val MIN_STORAGE_FREE_MB = 256L

        const val MIN_BATTERY_PERCENT = 20
        const val CONSERVE_BATTERY_PERCENT = 35

        const val PRESSURED_MIN_LEARNING_VALUE = 0.50
        const val MAX_TUNED_PRESSURED_MIN_LEARNING_VALUE = 0.65
        const val MIN_COST_SAMPLES = 3
        const val MIN_HISTORICAL_VALUE_PER_SECOND = 0.015

        const val CONSERVE_MAX_PREDICTED_MS = 6_000L
        const val PRESSURED_MAX_PREDICTED_MS = 10_000L
        const val NORMAL_TARGET_PREDICTED_MS = 8_000L
        const val MAX_UNPLUGGED_PREDICTED_MS = 20_000L
        const val RECOVERY_HEALTHY_CONFIRMATIONS = 2

        const val NORMAL_FRESH_BUDGET = 96
        const val NORMAL_REPLAY_BUDGET = 96
        const val PRESSURED_FRESH_BUDGET = 64
        const val PRESSURED_REPLAY_BUDGET = 64
        const val CONSERVE_FRESH_BUDGET = 64
        const val CONSERVE_REPLAY_BUDGET = 48
    }
}

internal fun ReflexActiveLearningBatch.learningValue(): Double {
    if (eligibleExamples <= 0) return 0.0
    val denominator = eligibleExamples.toDouble()
    val disagreementRate = disagreementExamples.toDouble() / denominator
    val hardRate = hardExamples.toDouble() / denominator
    val novelty = if (novelCapabilities.isNotEmpty()) 1.0 else 0.0
    return maxOf(
        meanPriority,
        0.55 * disagreementRate + 0.25 * hardRate + 0.20 * novelty
    ).coerceIn(0.0, 1.0)
}
