package io.amper.neuroos.core

enum class TitanPromptWorkloadBand { COMPACT, STANDARD, LONG }
enum class TitanOutputWorkloadBand { SHORT, STANDARD, LONG }
enum class TitanInputModalityClass { TEXT_ONLY, IMAGE, AUDIO, IMAGE_AND_AUDIO }

/** Shared overflow-safe increment for long-lived process-local routing counters. */
internal fun saturatingFeedbackIncrement(value: Int): Int {
    require(value >= 0) { "feedback counter must not be negative" }
    return if (value == Int.MAX_VALUE) Int.MAX_VALUE else value + 1
}

/** Finite mobile resource condition used for process-local routing feedback. */
enum class TitanResourceConditionClass {
    UNGOVERNED,
    COOL_CONSTRAINED,
    COOL_STANDARD,
    COOL_EXPANDED,
    WARM_CONSTRAINED,
    WARM_STANDARD,
    WARM_EXPANDED,
    CRITICAL_CONSTRAINED,
    CRITICAL_STANDARD,
    CRITICAL_EXPANDED;

    companion object {
        fun from(budget: ResourceBudget?): TitanResourceConditionClass {
            if (budget == null) return UNGOVERNED
            return resourceCondition(
                thermalBand = rawThermalBand(budget.thermalClass),
                memoryBand = rawMemoryBand(budget.memoryMb)
            )
        }
    }
}

private enum class TitanThermalFeedbackBand { COOL, WARM, CRITICAL }
private enum class TitanMemoryFeedbackBand { CONSTRAINED, STANDARD, EXPANDED }

private fun rawThermalBand(thermalClass: Int): TitanThermalFeedbackBand = when {
    thermalClass >= 4 -> TitanThermalFeedbackBand.CRITICAL
    thermalClass >= 2 -> TitanThermalFeedbackBand.WARM
    else -> TitanThermalFeedbackBand.COOL
}

private fun rawMemoryBand(memoryMb: Int): TitanMemoryFeedbackBand = when {
    memoryMb <= 1_024 -> TitanMemoryFeedbackBand.CONSTRAINED
    memoryMb > 4_096 -> TitanMemoryFeedbackBand.EXPANDED
    else -> TitanMemoryFeedbackBand.STANDARD
}

private fun TitanResourceConditionClass.thermalBand(): TitanThermalFeedbackBand? = when (this) {
    TitanResourceConditionClass.UNGOVERNED -> null
    TitanResourceConditionClass.COOL_CONSTRAINED,
    TitanResourceConditionClass.COOL_STANDARD,
    TitanResourceConditionClass.COOL_EXPANDED -> TitanThermalFeedbackBand.COOL
    TitanResourceConditionClass.WARM_CONSTRAINED,
    TitanResourceConditionClass.WARM_STANDARD,
    TitanResourceConditionClass.WARM_EXPANDED -> TitanThermalFeedbackBand.WARM
    TitanResourceConditionClass.CRITICAL_CONSTRAINED,
    TitanResourceConditionClass.CRITICAL_STANDARD,
    TitanResourceConditionClass.CRITICAL_EXPANDED -> TitanThermalFeedbackBand.CRITICAL
}

private fun TitanResourceConditionClass.memoryBand(): TitanMemoryFeedbackBand? = when (this) {
    TitanResourceConditionClass.UNGOVERNED -> null
    TitanResourceConditionClass.COOL_CONSTRAINED,
    TitanResourceConditionClass.WARM_CONSTRAINED,
    TitanResourceConditionClass.CRITICAL_CONSTRAINED -> TitanMemoryFeedbackBand.CONSTRAINED
    TitanResourceConditionClass.COOL_STANDARD,
    TitanResourceConditionClass.WARM_STANDARD,
    TitanResourceConditionClass.CRITICAL_STANDARD -> TitanMemoryFeedbackBand.STANDARD
    TitanResourceConditionClass.COOL_EXPANDED,
    TitanResourceConditionClass.WARM_EXPANDED,
    TitanResourceConditionClass.CRITICAL_EXPANDED -> TitanMemoryFeedbackBand.EXPANDED
}

private fun resourceCondition(
    thermalBand: TitanThermalFeedbackBand,
    memoryBand: TitanMemoryFeedbackBand
): TitanResourceConditionClass = when (thermalBand) {
    TitanThermalFeedbackBand.COOL -> when (memoryBand) {
        TitanMemoryFeedbackBand.CONSTRAINED -> TitanResourceConditionClass.COOL_CONSTRAINED
        TitanMemoryFeedbackBand.STANDARD -> TitanResourceConditionClass.COOL_STANDARD
        TitanMemoryFeedbackBand.EXPANDED -> TitanResourceConditionClass.COOL_EXPANDED
    }
    TitanThermalFeedbackBand.WARM -> when (memoryBand) {
        TitanMemoryFeedbackBand.CONSTRAINED -> TitanResourceConditionClass.WARM_CONSTRAINED
        TitanMemoryFeedbackBand.STANDARD -> TitanResourceConditionClass.WARM_STANDARD
        TitanMemoryFeedbackBand.EXPANDED -> TitanResourceConditionClass.WARM_EXPANDED
    }
    TitanThermalFeedbackBand.CRITICAL -> when (memoryBand) {
        TitanMemoryFeedbackBand.CONSTRAINED -> TitanResourceConditionClass.CRITICAL_CONSTRAINED
        TitanMemoryFeedbackBand.STANDARD -> TitanResourceConditionClass.CRITICAL_STANDARD
        TitanMemoryFeedbackBand.EXPANDED -> TitanResourceConditionClass.CRITICAL_EXPANDED
    }
}

/**
 * Process-local asymmetric hysteresis for mobile feedback identity.
 *
 * Resource eligibility still receives the raw [ResourceBudget]. This class stabilizes only the
 * feedback/exploration bucket: worsening thermal or memory conditions take effect immediately,
 * while recovery requires either a memory margin or repeated lower-thermal observations. That
 * prevents near-threshold telemetry noise from fragmenting route history without delaying safety
 * gates or masking a sudden resource deterioration.
 */
class TitanResourceConditionStabilizer(
    private val memoryRecoveryMarginMb: Int = 256,
    private val thermalRecoverySamples: Int = 2
) {
    init {
        require(memoryRecoveryMarginMb >= 0)
        require(thermalRecoverySamples > 0)
    }

    private var stable: TitanResourceConditionClass? = null
    private var pendingThermalRecovery: TitanThermalFeedbackBand? = null
    private var pendingThermalRecoverySamples: Int = 0

    @Synchronized
    fun observe(budget: ResourceBudget?): TitanResourceConditionClass {
        val raw = TitanResourceConditionClass.from(budget)
        val previous = stable
        if (budget == null || previous == null || previous == TitanResourceConditionClass.UNGOVERNED) {
            stable = raw
            resetThermalRecovery()
            return raw
        }

        val previousThermal = requireNotNull(previous.thermalBand())
        val previousMemory = requireNotNull(previous.memoryBand())
        val nextThermal = stabilizeThermal(previousThermal, rawThermalBand(budget.thermalClass))
        val nextMemory = stabilizeMemory(previousMemory, budget.memoryMb)
        val next = resourceCondition(nextThermal, nextMemory)
        stable = next
        return next
    }

    @Synchronized
    fun reset() {
        stable = null
        resetThermalRecovery()
    }

    /** Fresh state with the exact same hysteresis configuration. */
    fun newSibling(): TitanResourceConditionStabilizer = TitanResourceConditionStabilizer(
        memoryRecoveryMarginMb = memoryRecoveryMarginMb,
        thermalRecoverySamples = thermalRecoverySamples
    )

    private fun stabilizeThermal(
        previous: TitanThermalFeedbackBand,
        observed: TitanThermalFeedbackBand
    ): TitanThermalFeedbackBand {
        if (observed.ordinal > previous.ordinal) {
            resetThermalRecovery()
            return observed
        }
        if (observed == previous) {
            resetThermalRecovery()
            return previous
        }

        if (pendingThermalRecovery == observed) {
            pendingThermalRecoverySamples = saturatingFeedbackIncrement(pendingThermalRecoverySamples)
        } else {
            pendingThermalRecovery = observed
            pendingThermalRecoverySamples = 1
        }
        return if (pendingThermalRecoverySamples >= thermalRecoverySamples) {
            resetThermalRecovery()
            observed
        } else {
            previous
        }
    }

    private fun stabilizeMemory(
        previous: TitanMemoryFeedbackBand,
        memoryMb: Int
    ): TitanMemoryFeedbackBand {
        val observedMb = memoryMb.toLong()
        val recoveryMarginMb = memoryRecoveryMarginMb.toLong()
        return when (previous) {
            TitanMemoryFeedbackBand.CONSTRAINED -> {
                if (observedMb > 1_024L + recoveryMarginMb) rawMemoryBand(memoryMb)
                else TitanMemoryFeedbackBand.CONSTRAINED
            }
            TitanMemoryFeedbackBand.STANDARD -> when {
                memoryMb <= 1_024 -> TitanMemoryFeedbackBand.CONSTRAINED
                observedMb > 4_096L + recoveryMarginMb -> TitanMemoryFeedbackBand.EXPANDED
                else -> TitanMemoryFeedbackBand.STANDARD
            }
            TitanMemoryFeedbackBand.EXPANDED -> when {
                memoryMb <= 1_024 -> TitanMemoryFeedbackBand.CONSTRAINED
                memoryMb <= 4_096 -> TitanMemoryFeedbackBand.STANDARD
                else -> TitanMemoryFeedbackBand.EXPANDED
            }
        }
    }

    private fun resetThermalRecovery() {
        pendingThermalRecovery = null
        pendingThermalRecoverySamples = 0
    }
}

/**
 * Finite request-shape identity used only for process-local route feedback.
 * Raw prompt text is deliberately excluded.
 */
data class TitanInferenceWorkloadClass(
    val promptBand: TitanPromptWorkloadBand,
    val outputBand: TitanOutputWorkloadBand,
    val inputModality: TitanInputModalityClass = TitanInputModalityClass.TEXT_ONLY
) {
    companion object {
        val DEFAULT = TitanInferenceWorkloadClass(
            promptBand = TitanPromptWorkloadBand.STANDARD,
            outputBand = TitanOutputWorkloadBand.STANDARD
        )

        fun from(request: InferenceRequest): TitanInferenceWorkloadClass = TitanInferenceWorkloadClass(
            promptBand = when {
                request.prompt.length <= 1_024 -> TitanPromptWorkloadBand.COMPACT
                request.prompt.length <= 8_192 -> TitanPromptWorkloadBand.STANDARD
                else -> TitanPromptWorkloadBand.LONG
            },
            outputBand = when {
                request.maxOutputTokens <= 256 -> TitanOutputWorkloadBand.SHORT
                request.maxOutputTokens <= 1_024 -> TitanOutputWorkloadBand.STANDARD
                else -> TitanOutputWorkloadBand.LONG
            },
            inputModality = when {
                request.attachments.isEmpty() -> TitanInputModalityClass.TEXT_ONLY
                request.attachments.any { it.kind == InferenceAttachmentKind.IMAGE } &&
                    request.attachments.any { it.kind == InferenceAttachmentKind.AUDIO } ->
                    TitanInputModalityClass.IMAGE_AND_AUDIO
                request.attachments.any { it.kind == InferenceAttachmentKind.IMAGE } ->
                    TitanInputModalityClass.IMAGE
                else -> TitanInputModalityClass.AUDIO
            }
        )
    }
}

/** Runtime-only identity for one concrete admitted execution route. */
data class TitanRouteFeedbackKey(
    val runtimeIdentity: ModelRuntimeIdentity,
    val backendId: String,
    val selectedCapabilities: Set<CapabilityId>,
    val workloadClass: TitanInferenceWorkloadClass = TitanInferenceWorkloadClass.DEFAULT,
    val resourceCondition: TitanResourceConditionClass = TitanResourceConditionClass.UNGOVERNED
) {
    init {
        require(backendId.isNotBlank())
        require(selectedCapabilities.isNotEmpty()) { "feedback capability profile must not be empty" }
    }

    val modelId: ModelId
        get() = runtimeIdentity.modelId

    companion object {
        fun from(route: TitanInferenceRoute): TitanRouteFeedbackKey = TitanRouteFeedbackKey(
            runtimeIdentity = route.runtimeIdentity,
            backendId = route.backend.id,
            selectedCapabilities = route.selectedCapabilities.toSet(),
            workloadClass = route.workloadClass,
            resourceCondition = route.resourceCondition
        )
    }
}

data class TitanRouteFeedbackSnapshot(
    val consecutiveFailures: Int = 0,
    val performancePenalty: Int = 0,
    val deferredPlanningPasses: Int = 0,
    val throughputEwma: Double? = null,
    val throughputSamples: Int = 0,
    val consecutiveSlowSamples: Int = 0
) {
    init {
        require(consecutiveFailures >= 0)
        require(performancePenalty >= 0)
        require(deferredPlanningPasses >= 0)
        require(throughputSamples >= 0)
        require(consecutiveSlowSamples >= 0)
        throughputEwma?.let { require(it.isFinite() && it >= 0.0) }
    }

    val scorePenalty: Int
        get() = (
            consecutiveFailures.toLong() * FAILURE_WEIGHT.toLong() + performancePenalty.toLong()
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    companion object {
        private const val FAILURE_WEIGHT = 10
    }
}

/**
 * Bounded process-local feedback for future route decisions.
 *
 * Feedback is scoped by admitted artifact, backend, selected capabilities, workload shape and
 * stabilized coarse resource condition. Exact device telemetry is deliberately not retained.
 * Phase 95 saturates all long-lived counters so extreme process uptime cannot wrap routing penalties
 * negative or invalidate a snapshot.
 */
class TitanRuntimeFeedback(
    private val maxEntries: Int = 64,
    private val quarantineAfterFailures: Int = 2,
    private val quarantinePlanningPasses: Int = 2,
    private val slowTokensPerSecond: Double = 2.0,
    private val maxPerformancePenalty: Int = 4,
    private val minSlowSamplesForPenalty: Int = 2,
    private val throughputEwmaAlpha: Double = 0.5
) {
    init {
        require(maxEntries > 0)
        require(quarantineAfterFailures > 0)
        require(quarantinePlanningPasses > 0)
        require(slowTokensPerSecond > 0.0)
        require(maxPerformancePenalty > 0)
        require(minSlowSamplesForPenalty > 0)
        require(throughputEwmaAlpha > 0.0 && throughputEwmaAlpha <= 1.0)
    }

    private val entries = linkedMapOf<TitanRouteFeedbackKey, TitanRouteFeedbackSnapshot>()

    @Synchronized
    fun snapshot(key: TitanRouteFeedbackKey): TitanRouteFeedbackSnapshot =
        entries[key] ?: TitanRouteFeedbackSnapshot()

    @Synchronized
    fun consumeDeferral(key: TitanRouteFeedbackKey): Boolean {
        val current = entries[key] ?: return false
        if (current.deferredPlanningPasses == 0) return false
        putBounded(key, current.copy(deferredPlanningPasses = current.deferredPlanningPasses - 1))
        return true
    }

    @Synchronized
    fun recordFailure(key: TitanRouteFeedbackKey) {
        val current = entries[key] ?: TitanRouteFeedbackSnapshot()
        val failures = saturatingFeedbackIncrement(current.consecutiveFailures)
        val deferred = if (failures >= quarantineAfterFailures) {
            quarantinePlanningPasses
        } else {
            current.deferredPlanningPasses
        }
        putBounded(
            key,
            current.copy(
                consecutiveFailures = failures,
                deferredPlanningPasses = deferred
            )
        )
    }

    @Synchronized
    fun recordSuccess(key: TitanRouteFeedbackKey, response: InferenceResponse) {
        val current = entries[key] ?: TitanRouteFeedbackSnapshot()
        val observed = observedTokensPerSecond(response)
        val ewma = observed?.let { sample ->
            current.throughputEwma?.let { previous ->
                throughputEwmaAlpha * sample + (1.0 - throughputEwmaAlpha) * previous
            } ?: sample
        } ?: current.throughputEwma
        val samples = if (observed != null) {
            saturatingFeedbackIncrement(current.throughputSamples)
        } else {
            current.throughputSamples
        }
        val isSlow = observed != null && ewma != null && ewma < slowTokensPerSecond
        val slowStreak = when {
            observed == null -> current.consecutiveSlowSamples
            isSlow -> saturatingFeedbackIncrement(current.consecutiveSlowSamples)
            else -> 0
        }
        val performancePenalty = when {
            observed == null -> current.performancePenalty
            isSlow && slowStreak >= minSlowSamplesForPenalty ->
                saturatingFeedbackIncrement(current.performancePenalty).coerceAtMost(maxPerformancePenalty)
            !isSlow -> (current.performancePenalty - 1).coerceAtLeast(0)
            else -> current.performancePenalty
        }
        putBounded(
            key,
            TitanRouteFeedbackSnapshot(
                consecutiveFailures = 0,
                performancePenalty = performancePenalty,
                deferredPlanningPasses = 0,
                throughputEwma = ewma,
                throughputSamples = samples,
                consecutiveSlowSamples = slowStreak
            )
        )
    }

    @Synchronized fun size(): Int = entries.size
    @Synchronized fun clear() = entries.clear()

    private fun observedTokensPerSecond(response: InferenceResponse): Double? {
        response.tokensPerSecond?.let { explicit ->
            if (explicit.isFinite() && explicit >= 0.0) return explicit
        }
        val outputTokens = response.outputTokens ?: return null
        val generationTimeMs = response.generationTimeMs ?: return null
        if (outputTokens < 0 || generationTimeMs <= 0L) return null
        return outputTokens.toDouble() * 1000.0 / generationTimeMs.toDouble()
    }

    private fun putBounded(key: TitanRouteFeedbackKey, value: TitanRouteFeedbackSnapshot) {
        entries.remove(key)
        entries[key] = value
        while (entries.size > maxEntries) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
    }
}
