package io.amper.neuroos.core

import java.util.Locale

data class ReflexAdaptiveRuntimePolicy(
    val checkpointId: NativeCheckpointId,
    val minFastPathConfidence: Double,
    val maxFastPathUncertainty: Double,
    val maxPredictionLatencyMs: Double,
    val heldoutActionPrecision: Double?,
    val heldoutCalibrationError: Double?,
    val onlineResolvedActionSamples: Long,
    val onlineActionSuccessRate: Double?,
    val predictionSamples: Long,
    val latencyEwmaMs: Double?
) {
    init {
        require(minFastPathConfidence in HARD_MIN_CONFIDENCE..HARD_MAX_CONFIDENCE)
        require(maxFastPathUncertainty in HARD_MIN_UNCERTAINTY..HARD_MAX_UNCERTAINTY)
        require(maxPredictionLatencyMs in HARD_MIN_LATENCY_MS..HARD_MAX_LATENCY_MS)
        require(heldoutActionPrecision == null || heldoutActionPrecision in 0.0..1.0)
        require(heldoutCalibrationError == null || heldoutCalibrationError in 0.0..1.0)
        require(onlineResolvedActionSamples >= 0L)
        require(onlineActionSuccessRate == null || onlineActionSuccessRate in 0.0..1.0)
        require(predictionSamples >= 0L)
        require(latencyEwmaMs == null || latencyEwmaMs >= 0.0)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val HARD_MIN_CONFIDENCE = 0.985
        const val HARD_MAX_CONFIDENCE = 0.997
        const val HARD_MIN_UNCERTAINTY = 0.003
        const val HARD_MAX_UNCERTAINTY = 0.015
        const val HARD_MIN_LATENCY_MS = 100.0
        const val HARD_MAX_LATENCY_MS = 350.0
    }
}

data class ReflexRuntimeCalibrationSnapshot(
    val checkpointId: NativeCheckpointId,
    val predictionSamples: Long = 0L,
    val latencyEwmaMs: Double? = null,
    val resolvedActionSamples: Long = 0L,
    val executedActions: Long = 0L,
    val rejectedActions: Long = 0L,
    val positiveConfidenceEwma: Double? = null,
    val negativeConfidenceEwma: Double? = null,
    val updatedAtEpochMs: Long = 0L
) {
    init {
        require(predictionSamples >= 0L)
        require(latencyEwmaMs == null || latencyEwmaMs >= 0.0)
        require(resolvedActionSamples >= 0L)
        require(executedActions >= 0L)
        require(rejectedActions >= 0L)
        require(executedActions + rejectedActions == resolvedActionSamples)
        require(positiveConfidenceEwma == null || positiveConfidenceEwma in 0.0..1.0)
        require(negativeConfidenceEwma == null || negativeConfidenceEwma in 0.0..1.0)
        require(updatedAtEpochMs >= 0L)
    }
}

data class ReflexCalibrationActionBinding(
    val requestId: ActionRequestId,
    val checkpointId: NativeCheckpointId,
    val confidence: Double,
    val boundAtEpochMs: Long
) {
    init {
        require(confidence in 0.0..1.0)
        require(boundAtEpochMs >= 0L)
    }
}

fun interface ReflexHeldoutCalibrationSource {
    fun metrics(checkpointId: NativeCheckpointId): ReflexDecisionHeldoutMetrics?
}

interface ReflexRuntimeCalibration {
    fun policy(checkpointId: NativeCheckpointId): ReflexAdaptiveRuntimePolicy
    fun observePrediction(checkpointId: NativeCheckpointId, latencyMs: Double)
    fun bindAction(
        requestId: ActionRequestId,
        checkpointId: NativeCheckpointId,
        confidence: Double
    )
    fun observeActionOutcome(requestId: ActionRequestId, status: ActionStatus)
    fun discardAction(requestId: ActionRequestId)
    fun snapshot(checkpointId: NativeCheckpointId): ReflexRuntimeCalibrationSnapshot
}

object StaticReflexRuntimeCalibration : ReflexRuntimeCalibration {
    override fun policy(checkpointId: NativeCheckpointId): ReflexAdaptiveRuntimePolicy =
        ReflexAdaptiveRuntimePolicy(
            checkpointId = checkpointId,
            minFastPathConfidence = ReflexDecision.MIN_FAST_PATH_CONFIDENCE,
            maxFastPathUncertainty = ReflexDecision.MAX_FAST_PATH_UNCERTAINTY,
            maxPredictionLatencyMs = 250.0,
            heldoutActionPrecision = null,
            heldoutCalibrationError = null,
            onlineResolvedActionSamples = 0L,
            onlineActionSuccessRate = null,
            predictionSamples = 0L,
            latencyEwmaMs = null
        )

    override fun observePrediction(checkpointId: NativeCheckpointId, latencyMs: Double) = Unit
    override fun bindAction(
        requestId: ActionRequestId,
        checkpointId: NativeCheckpointId,
        confidence: Double
    ) = Unit
    override fun observeActionOutcome(requestId: ActionRequestId, status: ActionStatus) = Unit
    override fun discardAction(requestId: ActionRequestId) = Unit
    override fun snapshot(checkpointId: NativeCheckpointId): ReflexRuntimeCalibrationSnapshot =
        ReflexRuntimeCalibrationSnapshot(checkpointId)
}

/**
 * Phase461-465 online calibration. Held-out evidence is the prior; runtime evidence may only make
 * bounded adjustments inside hard safety floors/ceilings. Raw prompts, tool inputs/outputs and
 * approvals are never retained here.
 */
class MemoryBackedReflexRuntimeCalibration(
    private val memory: MemoryOs,
    private val heldout: ReflexHeldoutCalibrationSource,
    private val clock: () -> Long = System::currentTimeMillis
) : ReflexRuntimeCalibration {
    override fun policy(checkpointId: NativeCheckpointId): ReflexAdaptiveRuntimePolicy {
        val snapshot = snapshot(checkpointId)
        val heldoutMetrics = heldout.metrics(checkpointId)

        var confidence = when {
            heldoutMetrics == null -> ReflexDecision.MIN_FAST_PATH_CONFIDENCE
            heldoutMetrics.actionPrecision >= 0.995 &&
                heldoutMetrics.calibrationMeanAbsoluteError <= 0.015 -> 0.985
            heldoutMetrics.actionPrecision >= 0.990 &&
                heldoutMetrics.calibrationMeanAbsoluteError <= 0.030 -> 0.990
            else -> 0.995
        }
        val onlineRate = if (snapshot.resolvedActionSamples == 0L) {
            null
        } else {
            snapshot.executedActions.toDouble() / snapshot.resolvedActionSamples.toDouble()
        }
        if (snapshot.resolvedActionSamples >= MIN_ACTION_SAMPLES_FOR_ADAPTATION) {
            confidence += when {
                requireNotNull(onlineRate) >= 0.995 -> -0.002
                onlineRate < 0.980 -> 0.002
                else -> 0.0
            }
        }
        confidence = confidence.coerceIn(
            ReflexAdaptiveRuntimePolicy.HARD_MIN_CONFIDENCE,
            ReflexAdaptiveRuntimePolicy.HARD_MAX_CONFIDENCE
        )
        val uncertainty = (1.0 - confidence).coerceIn(
            ReflexAdaptiveRuntimePolicy.HARD_MIN_UNCERTAINTY,
            ReflexAdaptiveRuntimePolicy.HARD_MAX_UNCERTAINTY
        )
        val latencyBudget = if (
            snapshot.predictionSamples >= MIN_PREDICTION_SAMPLES_FOR_LATENCY_ADAPTATION &&
            snapshot.latencyEwmaMs != null
        ) {
            (snapshot.latencyEwmaMs * 2.0 + 20.0).coerceIn(
                ReflexAdaptiveRuntimePolicy.HARD_MIN_LATENCY_MS,
                ReflexAdaptiveRuntimePolicy.HARD_MAX_LATENCY_MS
            )
        } else {
            DEFAULT_LATENCY_BUDGET_MS
        }

        return ReflexAdaptiveRuntimePolicy(
            checkpointId = checkpointId,
            minFastPathConfidence = confidence,
            maxFastPathUncertainty = uncertainty,
            maxPredictionLatencyMs = latencyBudget,
            heldoutActionPrecision = heldoutMetrics?.actionPrecision,
            heldoutCalibrationError = heldoutMetrics?.calibrationMeanAbsoluteError,
            onlineResolvedActionSamples = snapshot.resolvedActionSamples,
            onlineActionSuccessRate = onlineRate,
            predictionSamples = snapshot.predictionSamples,
            latencyEwmaMs = snapshot.latencyEwmaMs
        )
    }

    @Synchronized
    override fun observePrediction(checkpointId: NativeCheckpointId, latencyMs: Double) {
        require(latencyMs >= 0.0 && latencyMs.isFinite())
        val previous = snapshot(checkpointId)
        val ewma = ewma(previous.latencyEwmaMs, latencyMs)
        persistSnapshot(
            previous.copy(
                predictionSamples = previous.predictionSamples + 1L,
                latencyEwmaMs = ewma,
                updatedAtEpochMs = clock()
            )
        )
    }

    @Synchronized
    override fun bindAction(
        requestId: ActionRequestId,
        checkpointId: NativeCheckpointId,
        confidence: Double
    ) {
        require(confidence in 0.0..1.0)
        val binding = ReflexCalibrationActionBinding(
            requestId = requestId,
            checkpointId = checkpointId,
            confidence = confidence,
            boundAtEpochMs = clock()
        )
        memory.remember(
            MemoryRecord(
                id = bindingMemoryId(requestId),
                kind = BINDING_KIND,
                content = ReflexRuntimeCalibrationCodec.encodeBinding(binding),
                importance = 0.62,
                provenance = Provenance(
                    source = "amper-reflex-runtime",
                    producer = "reflex-online-calibration-binding",
                    confidence = 1.0
                ),
                createdAtEpochMs = binding.boundAtEpochMs
            )
        )
    }

    @Synchronized
    override fun observeActionOutcome(requestId: ActionRequestId, status: ActionStatus) {
        val binding = loadBinding(requestId) ?: return
        if (status == ActionStatus.REQUIRES_CONFIRMATION) return

        val classification = when (status) {
            ActionStatus.EXECUTED -> 1
            ActionStatus.MALFORMED,
            ActionStatus.UNAVAILABLE -> -1
            ActionStatus.NO_ACTION,
            ActionStatus.REQUIRES_CONFIRMATION,
            ActionStatus.DENIED,
            ActionStatus.FAILED -> 0
        }

        if (classification != 0) {
            val previous = snapshot(binding.checkpointId)
            val positive = classification > 0
            persistSnapshot(
                previous.copy(
                    resolvedActionSamples = previous.resolvedActionSamples + 1L,
                    executedActions = previous.executedActions + if (positive) 1L else 0L,
                    rejectedActions = previous.rejectedActions + if (positive) 0L else 1L,
                    positiveConfidenceEwma = if (positive) {
                        ewma(previous.positiveConfidenceEwma, binding.confidence)
                    } else {
                        previous.positiveConfidenceEwma
                    },
                    negativeConfidenceEwma = if (!positive) {
                        ewma(previous.negativeConfidenceEwma, binding.confidence)
                    } else {
                        previous.negativeConfidenceEwma
                    },
                    updatedAtEpochMs = clock()
                )
            )
        }
        memory.forget(bindingMemoryId(requestId))
    }

    override fun discardAction(requestId: ActionRequestId) {
        memory.forget(bindingMemoryId(requestId))
    }

    override fun snapshot(checkpointId: NativeCheckpointId): ReflexRuntimeCalibrationSnapshot =
        memory.get(snapshotMemoryId(checkpointId))
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { ReflexRuntimeCalibrationCodec.decodeSnapshot(it.content) }
            ?.takeIf { it.checkpointId == checkpointId }
            ?: ReflexRuntimeCalibrationSnapshot(checkpointId)

    private fun loadBinding(requestId: ActionRequestId): ReflexCalibrationActionBinding? =
        memory.get(bindingMemoryId(requestId))
            ?.takeIf { it.kind == BINDING_KIND }
            ?.let { ReflexRuntimeCalibrationCodec.decodeBinding(it.content) }
            ?.takeIf { it.requestId == requestId }

    private fun persistSnapshot(snapshot: ReflexRuntimeCalibrationSnapshot) {
        memory.remember(
            MemoryRecord(
                id = snapshotMemoryId(snapshot.checkpointId),
                kind = SNAPSHOT_KIND,
                content = ReflexRuntimeCalibrationCodec.encodeSnapshot(snapshot),
                importance = 0.78,
                provenance = Provenance(
                    source = "amper-reflex-runtime",
                    producer = "reflex-online-calibration",
                    confidence = 1.0
                ),
                createdAtEpochMs = snapshot.updatedAtEpochMs
            )
        )
    }

    private fun snapshotMemoryId(checkpointId: NativeCheckpointId): MemoryId =
        MemoryId("reflex-calibration:" + checkpointId.value)

    private fun bindingMemoryId(requestId: ActionRequestId): MemoryId =
        MemoryId("reflex-calibration-binding:" + requestId.value)

    private fun ewma(previous: Double?, value: Double): Double =
        if (previous == null) value else previous + EWMA_ALPHA * (value - previous)

    companion object {
        const val SNAPSHOT_KIND = "reflex-runtime-calibration-v1"
        const val BINDING_KIND = "reflex-runtime-calibration-binding-v1"
        const val MIN_ACTION_SAMPLES_FOR_ADAPTATION = 32L
        const val MIN_PREDICTION_SAMPLES_FOR_LATENCY_ADAPTATION = 16L
        const val DEFAULT_LATENCY_BUDGET_MS = 250.0
        private const val EWMA_ALPHA = 0.125
    }
}

private object ReflexRuntimeCalibrationCodec {
    fun encodeSnapshot(value: ReflexRuntimeCalibrationSnapshot): String = listOf(
        "v=1",
        "checkpoint=" + value.checkpointId.value,
        "predictions=" + value.predictionSamples,
        "latency=" + (value.latencyEwmaMs?.let { format(it) } ?: "~"),
        "resolved=" + value.resolvedActionSamples,
        "executed=" + value.executedActions,
        "rejected=" + value.rejectedActions,
        "positive=" + (value.positiveConfidenceEwma?.let { format(it) } ?: "~"),
        "negative=" + (value.negativeConfidenceEwma?.let { format(it) } ?: "~"),
        "updated=" + value.updatedAtEpochMs
    ).joinToString(";")

    fun decodeSnapshot(content: String): ReflexRuntimeCalibrationSnapshot? = runCatching {
        val p = fields(content)
        require(p["v"] == "1")
        ReflexRuntimeCalibrationSnapshot(
            checkpointId = NativeCheckpointId(requireNotNull(p["checkpoint"])),
            predictionSamples = requireNotNull(p["predictions"]).toLong(),
            latencyEwmaMs = requireNotNull(p["latency"]).takeUnless { it == "~" }?.toDouble(),
            resolvedActionSamples = requireNotNull(p["resolved"]).toLong(),
            executedActions = requireNotNull(p["executed"]).toLong(),
            rejectedActions = requireNotNull(p["rejected"]).toLong(),
            positiveConfidenceEwma =
                requireNotNull(p["positive"]).takeUnless { it == "~" }?.toDouble(),
            negativeConfidenceEwma =
                requireNotNull(p["negative"]).takeUnless { it == "~" }?.toDouble(),
            updatedAtEpochMs = requireNotNull(p["updated"]).toLong()
        )
    }.getOrNull()

    fun encodeBinding(value: ReflexCalibrationActionBinding): String = listOf(
        "v=1",
        "request=" + value.requestId.value,
        "checkpoint=" + value.checkpointId.value,
        "confidence=" + format(value.confidence),
        "bound=" + value.boundAtEpochMs
    ).joinToString(";")

    fun decodeBinding(content: String): ReflexCalibrationActionBinding? = runCatching {
        val p = fields(content)
        require(p["v"] == "1")
        ReflexCalibrationActionBinding(
            requestId = ActionRequestId(requireNotNull(p["request"])),
            checkpointId = NativeCheckpointId(requireNotNull(p["checkpoint"])),
            confidence = requireNotNull(p["confidence"]).toDouble(),
            boundAtEpochMs = requireNotNull(p["bound"]).toLong()
        )
    }.getOrNull()

    private fun fields(content: String): Map<String, String> =
        content.split(';').associate { field ->
            val split = field.indexOf('=')
            require(split > 0)
            field.substring(0, split) to field.substring(split + 1)
        }

    private fun format(value: Double): String =
        String.format(Locale.US, "%.9f", value)
}
