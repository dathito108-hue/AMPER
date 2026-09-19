package io.amper.neuroos.core

enum class ReflexLearningDeferCause {
    THERMAL_OR_MEMORY,
    STORAGE,
    BATTERY,
    COST,
    LOW_YIELD,
    RESOURCE_PRESSURE,
    HYSTERESIS,
    OTHER
}

data class ReflexLearningSchedulerTelemetrySnapshot(
    val decisions: Int = 0,
    val readyDecisions: Int = 0,
    val limitedDecisions: Int = 0,
    val deferredDecisions: Int = 0,
    val highValueDecisions: Int = 0,
    val highValueDeferredDecisions: Int = 0,
    val maintenanceAttempts: Int = 0,
    val maintenanceCompleted: Int = 0,
    val maintenanceFailed: Int = 0,
    val queueWaitEwmaMs: Double? = null,
    val thermalClassEwma: Double? = null,
    val batteryPercentEwma: Double? = null,
    val thermalMemoryDeferrals: Int = 0,
    val batteryDeferrals: Int = 0,
    val storageDeferrals: Int = 0,
    val costDeferrals: Int = 0,
    val lowYieldDeferrals: Int = 0,
    val pressureDeferrals: Int = 0,
    val hysteresisDeferrals: Int = 0,
    val updatedAtEpochMs: Long? = null
) {
    init {
        listOf(
            decisions,
            readyDecisions,
            limitedDecisions,
            deferredDecisions,
            highValueDecisions,
            highValueDeferredDecisions,
            maintenanceAttempts,
            maintenanceCompleted,
            maintenanceFailed,
            thermalMemoryDeferrals,
            batteryDeferrals,
            storageDeferrals,
            costDeferrals,
            lowYieldDeferrals,
            pressureDeferrals,
            hysteresisDeferrals
        ).forEach { require(it >= 0) }
        require(readyDecisions + limitedDecisions + deferredDecisions == decisions)
        require(highValueDeferredDecisions <= highValueDecisions)
        require(maintenanceCompleted + maintenanceFailed <= maintenanceAttempts)
        require(queueWaitEwmaMs == null || (queueWaitEwmaMs.isFinite() && queueWaitEwmaMs >= 0.0))
        require(
            thermalClassEwma == null ||
                (thermalClassEwma.isFinite() && thermalClassEwma >= 0.0)
        )
        require(
            batteryPercentEwma == null ||
                (batteryPercentEwma.isFinite() && batteryPercentEwma in 0.0..100.0)
        )
        require(updatedAtEpochMs == null || updatedAtEpochMs >= 0L)
    }

    val deferredRate: Double
        get() = if (decisions == 0) 0.0 else deferredDecisions.toDouble() / decisions.toDouble()

    val maintenanceFailureRate: Double
        get() = if (maintenanceAttempts == 0) {
            0.0
        } else {
            maintenanceFailed.toDouble() / maintenanceAttempts.toDouble()
        }

    val maintenanceCompletionRate: Double
        get() = if (maintenanceAttempts == 0) {
            0.0
        } else {
            maintenanceCompleted.toDouble() / maintenanceAttempts.toDouble()
        }

    val authorityBearing: Boolean
        get() = false
}

data class ReflexLearningSchedulerTuningProfile(
    val budgetScale: Double = 1.0,
    val predictedDurationScale: Double = 1.0,
    val minimumLearningValueBoost: Double = 0.0,
    val retryDelayMultiplier: Int = 1,
    val evidenceSamples: Int = 0,
    val reason: String = "baseline scheduler profile"
) {
    init {
        require(budgetScale in MIN_BUDGET_SCALE..1.0)
        require(predictedDurationScale in MIN_DURATION_SCALE..1.0)
        require(minimumLearningValueBoost in 0.0..MAX_LEARNING_VALUE_BOOST)
        require(retryDelayMultiplier in 1..MAX_RETRY_DELAY_MULTIPLIER)
        require(evidenceSamples >= 0)
        require(reason.isNotBlank())
    }

    fun scaleBudget(base: Int, floor: Int): Int {
        require(base >= floor)
        return (base.toDouble() * budgetScale)
            .toInt()
            .coerceIn(floor, base)
    }

    fun scaleDuration(baseMs: Long): Long {
        require(baseMs > 0L)
        return (baseMs.toDouble() * predictedDurationScale)
            .toLong()
            .coerceIn(1L, baseMs)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MIN_BUDGET_SCALE = 0.65
        const val MIN_DURATION_SCALE = 0.65
        const val MAX_LEARNING_VALUE_BOOST = 0.15
        const val MAX_RETRY_DELAY_MULTIPLIER = 4
    }
}

interface ReflexLearningSchedulerTelemetry {
    fun snapshot(): ReflexLearningSchedulerTelemetrySnapshot

    fun tuningProfile(): ReflexLearningSchedulerTuningProfile

    fun observeDecision(
        demand: ReflexLearningDemand,
        decision: ReflexLearningResourceDecision,
        observedAtEpochMs: Long
    ): ReflexLearningSchedulerTelemetrySnapshot

    fun observeMaintenanceAttempt(
        queueWaitMs: Long,
        completed: Boolean,
        failed: Boolean,
        observedAtEpochMs: Long
    ): ReflexLearningSchedulerTelemetrySnapshot
}

object NoopReflexLearningSchedulerTelemetry : ReflexLearningSchedulerTelemetry {
    override fun snapshot() = ReflexLearningSchedulerTelemetrySnapshot()

    override fun tuningProfile() = ReflexLearningSchedulerTuningProfile()

    override fun observeDecision(
        demand: ReflexLearningDemand,
        decision: ReflexLearningResourceDecision,
        observedAtEpochMs: Long
    ) = snapshot()

    override fun observeMaintenanceAttempt(
        queueWaitMs: Long,
        completed: Boolean,
        failed: Boolean,
        observedAtEpochMs: Long
    ) = snapshot()
}

/**
 * Phase531-535 device-local scheduler telemetry and conservative self-tuning.
 *
 * Tuning can only make training more conservative than canonical Phase511-520 defaults:
 * budgets and duration limits may shrink, and the minimum learning-value threshold may rise.
 * Critical battery/thermal/memory/storage safety floors are never relaxed here.
 */
class MemoryBackedReflexLearningSchedulerTelemetry(
    private val memory: MemoryOs
) : ReflexLearningSchedulerTelemetry {
    @Synchronized
    override fun snapshot(): ReflexLearningSchedulerTelemetrySnapshot =
        memory.get(SNAPSHOT_ID)
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { decode(it.content) }
            ?: ReflexLearningSchedulerTelemetrySnapshot()

    @Synchronized
    override fun tuningProfile(): ReflexLearningSchedulerTuningProfile {
        val state = snapshot()
        val samples = state.decisions + state.maintenanceAttempts
        if (
            state.decisions < MIN_DECISION_SAMPLES &&
            state.maintenanceAttempts < MIN_MAINTENANCE_SAMPLES
        ) {
            return ReflexLearningSchedulerTuningProfile(
                evidenceSamples = samples,
                reason = "insufficient scheduler telemetry; canonical baseline retained"
            )
        }

        val hotDeferralRate = if (state.decisions == 0) {
            0.0
        } else {
            state.thermalMemoryDeferrals.toDouble() / state.decisions.toDouble()
        }
        val severe =
            state.maintenanceFailureRate >= SEVERE_FAILURE_RATE ||
                state.deferredRate >= SEVERE_DEFER_RATE ||
                hotDeferralRate >= SEVERE_HOT_DEFER_RATE ||
                (state.thermalClassEwma ?: 0.0) >= SEVERE_THERMAL_EWMA

        if (severe) {
            return ReflexLearningSchedulerTuningProfile(
                budgetScale = 0.70,
                predictedDurationScale = 0.75,
                minimumLearningValueBoost = 0.10,
                retryDelayMultiplier = 3,
                evidenceSamples = samples,
                reason = "device telemetry requests strongly conservative learning budget"
            )
        }

        val moderate =
            state.maintenanceFailureRate >= MODERATE_FAILURE_RATE ||
                state.deferredRate >= MODERATE_DEFER_RATE ||
                hotDeferralRate >= MODERATE_HOT_DEFER_RATE ||
                (state.thermalClassEwma ?: 0.0) >= MODERATE_THERMAL_EWMA

        if (moderate) {
            return ReflexLearningSchedulerTuningProfile(
                budgetScale = 0.85,
                predictedDurationScale = 0.90,
                minimumLearningValueBoost = 0.05,
                retryDelayMultiplier = 2,
                evidenceSamples = samples,
                reason = "device telemetry requests moderately conservative learning budget"
            )
        }

        return ReflexLearningSchedulerTuningProfile(
            evidenceSamples = samples,
            reason = "device telemetry supports canonical learning budget"
        )
    }

    @Synchronized
    override fun observeDecision(
        demand: ReflexLearningDemand,
        decision: ReflexLearningResourceDecision,
        observedAtEpochMs: Long
    ): ReflexLearningSchedulerTelemetrySnapshot {
        require(observedAtEpochMs >= 0L)
        val previous = snapshot()
        val cause = if (decision.allowTraining) {
            null
        } else {
            classifyDeferredReason(decision.reason)
        }
        val next = previous.copy(
            decisions = previous.decisions + 1,
            readyDecisions = previous.readyDecisions +
                if (decision.mode == ReflexLearningResourceMode.READY) 1 else 0,
            limitedDecisions = previous.limitedDecisions +
                if (decision.mode == ReflexLearningResourceMode.LIMITED) 1 else 0,
            deferredDecisions = previous.deferredDecisions +
                if (decision.mode == ReflexLearningResourceMode.DEFERRED) 1 else 0,
            highValueDecisions = previous.highValueDecisions + if (demand.highValue) 1 else 0,
            highValueDeferredDecisions = previous.highValueDeferredDecisions +
                if (demand.highValue && !decision.allowTraining) 1 else 0,
            thermalClassEwma = ewma(
                previous.thermalClassEwma,
                decision.thermalClass.toDouble()
            ),
            batteryPercentEwma = decision.batteryPercent?.let { battery ->
                ewma(previous.batteryPercentEwma, battery.toDouble())
            } ?: previous.batteryPercentEwma,
            thermalMemoryDeferrals = previous.thermalMemoryDeferrals +
                if (cause == ReflexLearningDeferCause.THERMAL_OR_MEMORY) 1 else 0,
            batteryDeferrals = previous.batteryDeferrals +
                if (cause == ReflexLearningDeferCause.BATTERY) 1 else 0,
            storageDeferrals = previous.storageDeferrals +
                if (cause == ReflexLearningDeferCause.STORAGE) 1 else 0,
            costDeferrals = previous.costDeferrals +
                if (cause == ReflexLearningDeferCause.COST) 1 else 0,
            lowYieldDeferrals = previous.lowYieldDeferrals +
                if (cause == ReflexLearningDeferCause.LOW_YIELD) 1 else 0,
            pressureDeferrals = previous.pressureDeferrals +
                if (cause == ReflexLearningDeferCause.RESOURCE_PRESSURE) 1 else 0,
            hysteresisDeferrals = previous.hysteresisDeferrals +
                if (cause == ReflexLearningDeferCause.HYSTERESIS) 1 else 0,
            updatedAtEpochMs = observedAtEpochMs
        )
        persist(next)
        return next
    }

    @Synchronized
    override fun observeMaintenanceAttempt(
        queueWaitMs: Long,
        completed: Boolean,
        failed: Boolean,
        observedAtEpochMs: Long
    ): ReflexLearningSchedulerTelemetrySnapshot {
        require(queueWaitMs >= 0L)
        require(observedAtEpochMs >= 0L)
        require(!(completed && failed))
        val previous = snapshot()
        val next = previous.copy(
            maintenanceAttempts = previous.maintenanceAttempts + 1,
            maintenanceCompleted = previous.maintenanceCompleted + if (completed) 1 else 0,
            maintenanceFailed = previous.maintenanceFailed + if (failed) 1 else 0,
            queueWaitEwmaMs = ewma(previous.queueWaitEwmaMs, queueWaitMs.toDouble()),
            updatedAtEpochMs = observedAtEpochMs
        )
        persist(next)
        return next
    }

    private fun persist(snapshot: ReflexLearningSchedulerTelemetrySnapshot) {
        memory.remember(
            MemoryRecord(
                id = SNAPSHOT_ID,
                kind = SNAPSHOT_KIND,
                content = encode(snapshot),
                importance = 0.82,
                provenance = Provenance(
                    source = "amper-reflex-scheduler",
                    producer = "reflex-learning-scheduler-telemetry",
                    confidence = 1.0
                ),
                createdAtEpochMs = requireNotNull(snapshot.updatedAtEpochMs)
            )
        )
    }

    private fun ewma(previous: Double?, next: Double): Double =
        previous?.let { old -> EWMA_ALPHA * next + (1.0 - EWMA_ALPHA) * old } ?: next

    private fun classifyDeferredReason(reason: String): ReflexLearningDeferCause {
        val normalized = reason.lowercase()
        return when {
            "thermal" in normalized || "memory" in normalized ->
                ReflexLearningDeferCause.THERMAL_OR_MEMORY
            "storage" in normalized -> ReflexLearningDeferCause.STORAGE
            "battery" in normalized || "charging" in normalized ->
                ReflexLearningDeferCause.BATTERY
            "cost" in normalized -> ReflexLearningDeferCause.COST
            "yield" in normalized -> ReflexLearningDeferCause.LOW_YIELD
            "pressure" in normalized -> ReflexLearningDeferCause.RESOURCE_PRESSURE
            "hysteresis" in normalized -> ReflexLearningDeferCause.HYSTERESIS
            else -> ReflexLearningDeferCause.OTHER
        }
    }

    private fun encode(snapshot: ReflexLearningSchedulerTelemetrySnapshot): String =
        listOf(
            VERSION,
            snapshot.decisions,
            snapshot.readyDecisions,
            snapshot.limitedDecisions,
            snapshot.deferredDecisions,
            snapshot.highValueDecisions,
            snapshot.highValueDeferredDecisions,
            snapshot.maintenanceAttempts,
            snapshot.maintenanceCompleted,
            snapshot.maintenanceFailed,
            encodeNullable(snapshot.queueWaitEwmaMs),
            encodeNullable(snapshot.thermalClassEwma),
            encodeNullable(snapshot.batteryPercentEwma),
            snapshot.thermalMemoryDeferrals,
            snapshot.batteryDeferrals,
            snapshot.storageDeferrals,
            snapshot.costDeferrals,
            snapshot.lowYieldDeferrals,
            snapshot.pressureDeferrals,
            snapshot.hysteresisDeferrals,
            snapshot.updatedAtEpochMs ?: NULL
        ).joinToString("|")

    private fun decode(content: String): ReflexLearningSchedulerTelemetrySnapshot {
        val parts = content.split('|')
        require(parts.size == 21 && parts[0] == VERSION) {
            "unsupported Reflex scheduler telemetry snapshot"
        }
        return ReflexLearningSchedulerTelemetrySnapshot(
            decisions = parts[1].toInt(),
            readyDecisions = parts[2].toInt(),
            limitedDecisions = parts[3].toInt(),
            deferredDecisions = parts[4].toInt(),
            highValueDecisions = parts[5].toInt(),
            highValueDeferredDecisions = parts[6].toInt(),
            maintenanceAttempts = parts[7].toInt(),
            maintenanceCompleted = parts[8].toInt(),
            maintenanceFailed = parts[9].toInt(),
            queueWaitEwmaMs = decodeNullable(parts[10]),
            thermalClassEwma = decodeNullable(parts[11]),
            batteryPercentEwma = decodeNullable(parts[12]),
            thermalMemoryDeferrals = parts[13].toInt(),
            batteryDeferrals = parts[14].toInt(),
            storageDeferrals = parts[15].toInt(),
            costDeferrals = parts[16].toInt(),
            lowYieldDeferrals = parts[17].toInt(),
            pressureDeferrals = parts[18].toInt(),
            hysteresisDeferrals = parts[19].toInt(),
            updatedAtEpochMs = parts[20].takeUnless { it == NULL }?.toLong()
        )
    }

    private fun encodeNullable(value: Double?): String = value?.toString() ?: NULL

    private fun decodeNullable(value: String): Double? =
        value.takeUnless { it == NULL }?.toDouble()

    companion object {
        private val SNAPSHOT_ID = MemoryId("reflex-learning-scheduler:telemetry:v1")
        private const val SNAPSHOT_KIND = "reflex-learning-scheduler-telemetry-v1"
        private const val VERSION = "RLST1"
        private const val NULL = "~"
        private const val EWMA_ALPHA = 0.20

        const val MIN_DECISION_SAMPLES = 8
        const val MIN_MAINTENANCE_SAMPLES = 4
        const val MODERATE_FAILURE_RATE = 0.08
        const val SEVERE_FAILURE_RATE = 0.20
        const val MODERATE_DEFER_RATE = 0.45
        const val SEVERE_DEFER_RATE = 0.65
        const val MODERATE_HOT_DEFER_RATE = 0.10
        const val SEVERE_HOT_DEFER_RATE = 0.25
        const val MODERATE_THERMAL_EWMA = 1.20
        const val SEVERE_THERMAL_EWMA = 1.80
    }
}
