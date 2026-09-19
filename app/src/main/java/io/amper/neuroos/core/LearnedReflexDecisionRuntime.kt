package io.amper.neuroos.core

data class NativeReflexDecisionInput(
    val featureHashes: Set<String>,
    val availableCapabilities: Set<CapabilityId>
) {
    init {
        require(featureHashes.isNotEmpty())
        require(featureHashes.size <= ReflexDecisionFeatureEncoder.MAX_FEATURES)
        require(availableCapabilities.size <= ReflexExperienceTrainingExample.MAX_AVAILABLE_CAPABILITIES)
    }
}

data class NativeReflexDecisionPrediction(
    val disposition: ReflexDecisionDisposition,
    val capability: CapabilityId? = null,
    val confidence: Double,
    val uncertainty: Double
) {
    init {
        require(confidence in 0.0..1.0)
        require(uncertainty in 0.0..1.0)
        when (disposition) {
            ReflexDecisionDisposition.ESCALATE_SYSTEM2 -> require(capability == null)
            ReflexDecisionDisposition.PROPOSE_ACTION -> require(capability != null)
        }
    }
}

/**
 * Runtime boundary implemented by the actual admitted System-1 checkpoint backend.
 * It receives only privacy-preserving lexical features and the current live capability set.
 */
interface NativeReflexDecisionPort {
    val checkpointId: NativeCheckpointId
    val weightArtifactSha256: String

    fun predict(input: NativeReflexDecisionInput): Result<NativeReflexDecisionPrediction>
}

/**
 * Optional mobile residency hook. Failure to release transient resources is telemetry only and never
 * changes a prediction result or tool authority.
 */
interface NativeReflexResidencyAwarePort : NativeReflexDecisionPort {
    fun releaseTransientResources(): Result<Unit>
}

fun interface ReflexDecisionRuntimeActivationGate {
    fun validate(port: NativeReflexDecisionPort): Result<Unit>
}

fun interface ReflexDecisionRuntimeReplacementGate {
    fun validate(
        candidate: NativeReflexDecisionPort,
        baselineCheckpointId: NativeCheckpointId
    ): Result<Unit>
}

object RejectingReflexDecisionRuntimeReplacementGate : ReflexDecisionRuntimeReplacementGate {
    override fun validate(
        candidate: NativeReflexDecisionPort,
        baselineCheckpointId: NativeCheckpointId
    ): Result<Unit> = Result.failure(
        IllegalStateException("Reflex runtime replacement gate is unavailable")
    )
}

class CanonicalReflexDecisionRuntimeReplacementGate(
    private val activationGate: ReflexDecisionRuntimeActivationGate,
    private val training: NativeTrainingPipeline
) : ReflexDecisionRuntimeReplacementGate {
    override fun validate(
        candidate: NativeReflexDecisionPort,
        baselineCheckpointId: NativeCheckpointId
    ): Result<Unit> = runCatching {
        activationGate.validate(candidate).getOrThrow()
        val promotion = training.promotionCandidate(
            candidateCheckpointId = candidate.checkpointId,
            baselineCheckpointId = baselineCheckpointId
        )
        require(promotion.promotable) {
            "Reflex challenger is not promotable over the active champion"
        }
    }
}

data class ReflexRuntimeHealthPolicy(
    val maxConsecutivePredictionFailures: Int = 3,
    val maxPredictionLatencyMs: Double = 350.0,
    val maxConsecutiveSlowPredictions: Int = 3
) {
    init {
        require(maxConsecutivePredictionFailures > 0)
        require(maxPredictionLatencyMs > 0.0 && maxPredictionLatencyMs.isFinite())
        require(maxConsecutiveSlowPredictions > 0)
    }
}

data class ReflexRuntimeHealthSnapshot(
    val checkpointId: NativeCheckpointId?,
    val totalPredictions: Long = 0L,
    val predictionFailures: Long = 0L,
    val consecutivePredictionFailures: Int = 0,
    val slowPredictions: Long = 0L,
    val consecutiveSlowPredictions: Int = 0,
    val lastPredictionLatencyMs: Double = 0.0,
    val maxPredictionLatencyMs: Double = 0.0,
    val automaticRollbacks: Long = 0L,
    val resourceSkips: Long = 0L,
    val lastResourceMode: ReflexRuntimeResourceMode = ReflexRuntimeResourceMode.NORMAL,
    val lastResourceMemoryMb: Int = 0,
    val lastResourceThermalClass: Int = 0,
    val lastResourceBatteryPercent: Int? = null,
    val lastResourceCharging: Boolean? = null,
    val throttleSkips: Long = 0L,
    val residencyReleases: Long = 0L,
    val residencyReleaseFailures: Long = 0L,
    val lastWorkCostScore: Double = 0.0,
    val workCostEwma: Double? = null
) {
    init {
        require(totalPredictions >= 0L)
        require(predictionFailures in 0L..totalPredictions)
        require(consecutivePredictionFailures >= 0)
        require(slowPredictions in 0L..totalPredictions)
        require(consecutiveSlowPredictions >= 0)
        require(lastPredictionLatencyMs >= 0.0 && lastPredictionLatencyMs.isFinite())
        require(maxPredictionLatencyMs >= 0.0 && maxPredictionLatencyMs.isFinite())
        require(automaticRollbacks >= 0L)
        require(resourceSkips >= 0L)
        require(lastResourceMemoryMb >= 0)
        require(lastResourceBatteryPercent == null || lastResourceBatteryPercent in 0..100)
        require(throttleSkips >= 0L)
        require(residencyReleases >= 0L)
        require(residencyReleaseFailures >= 0L)
        require(lastWorkCostScore >= 0.0 && lastWorkCostScore.isFinite())
        require(workCostEwma == null || (workCostEwma >= 0.0 && workCostEwma.isFinite()))
    }

    val authorityBearing: Boolean
        get() = false
}

/**
 * Recomputes runtime eligibility from the canonical checkpoint/evaluation/promotion path.
 * A caller cannot activate a System-1 port by supplying a self-declared "admitted" flag.
 */
class CanonicalReflexDecisionRuntimeActivationGate(
    private val foundation: NativeModelFoundation,
    private val training: NativeTrainingPipeline
) : ReflexDecisionRuntimeActivationGate {
    override fun validate(port: NativeReflexDecisionPort): Result<Unit> = runCatching {
        require(port.weightArtifactSha256.matches(Regex("[0-9a-f]{64}"))) {
            "native Reflex runtime weight digest is malformed"
        }
        val checkpoint = requireNotNull(foundation.getCheckpoint(port.checkpointId)) {
            "native Reflex checkpoint lineage is unavailable"
        }
        require(checkpoint.weightArtifactSha256 == port.weightArtifactSha256) {
            "native Reflex runtime weights do not match checkpoint lineage"
        }
        val contract = requireNotNull(foundation.getContract(checkpoint.contractId)) {
            "native Reflex model contract is unavailable"
        }
        require(contract.ownedByAmper) {
            "native Reflex runtime requires an AMPER-owned model contract"
        }
        require(contract.capabilities == setOf(TitanCapabilities.REFLEX_DECISION)) {
            "native Reflex runtime requires a reflex-decision-only checkpoint"
        }
        val evaluation = requireNotNull(training.getEvaluation(port.checkpointId)) {
            "native Reflex checkpoint has no held-out evaluation"
        }
        require(evaluation.admission.admitted) {
            "native Reflex checkpoint has not passed admission"
        }
        require(training.promotionCandidate(port.checkpointId).promotable) {
            "native Reflex checkpoint is not objectively promotable"
        }
    }
}

fun interface ReflexActionArgumentBinder {
    fun bind(
        request: ReflexDecisionRequest,
        capability: CapabilityId
    ): ActionProposal?
}

/**
 * Canonical safe binder for Phase446-450.
 *
 * Learned System-1 predicts only routing/capability. Tool input is never accepted from model output.
 * The existing deterministic Reflex parser must independently recognize the same capability and
 * produce an input that already satisfies the live ToolDescriptor contract.
 */
object CanonicalReflexActionArgumentBinder : ReflexActionArgumentBinder {
    override fun bind(
        request: ReflexDecisionRequest,
        capability: CapabilityId
    ): ActionProposal? {
        val deterministic = DeterministicReflexDecisionCortex.decide(request)
        if (
            deterministic.disposition != ReflexDecisionDisposition.PROPOSE_ACTION ||
            deterministic.capability != capability
        ) {
            return null
        }
        return deterministic.toActionProposal()
    }
}

data class ReflexDecisionRuntimeActivation(
    val checkpointId: NativeCheckpointId,
    val weightArtifactSha256: String,
    val activatedAtEpochMs: Long
) {
    init {
        require(weightArtifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(activatedAtEpochMs >= 0L)
    }

    val authorityBearing: Boolean
        get() = false
}

interface ReflexDecisionRuntimeController : ReflexDecisionCortex {
    fun activate(port: NativeReflexDecisionPort): Result<ReflexDecisionRuntimeActivation>
    fun recover(
        resolver: NativeReflexDecisionPortResolver
    ): Result<ReflexDecisionRuntimeActivation?>
    fun rollback(): ReflexDecisionRuntimeActivation?
    fun active(): ReflexDecisionRuntimeActivation?
    fun persistedIntent(): ReflexRuntimeActivationIntent?
    fun health(): ReflexRuntimeHealthSnapshot
    fun adaptivePolicy(): ReflexAdaptiveRuntimePolicy?
    fun bindActionDecision(requestId: ActionRequestId, decision: ReflexDecision)
    fun observeActionOutcome(requestId: ActionRequestId, status: ActionStatus)
    fun discardActionDecision(requestId: ActionRequestId)
}

/**
 * Runtime System-1 switchboard.
 *
 * With no admitted learned checkpoint, AMPER uses the deterministic bootstrap. After activation,
 * the learned model owns only ACTION-vs-ESCALATE and capability routing. Any malformed/unsupported
 * action, missing live descriptor, low-confidence route, or unsafe argument binding falls through to
 * System-2. A runtime backend failure falls back to the deterministic bootstrap so existing fast
 * assistant behavior remains available.
 */
class CanonicalReflexDecisionRuntimeController(
    private val activationGate: ReflexDecisionRuntimeActivationGate,
    private val replacementGate: ReflexDecisionRuntimeReplacementGate =
        RejectingReflexDecisionRuntimeReplacementGate,
    private val fallback: ReflexDecisionCortex = DeterministicReflexDecisionCortex,
    private val binder: ReflexActionArgumentBinder = CanonicalReflexActionArgumentBinder,
    private val activationStore: ReflexDecisionRuntimeActivationStore =
        VolatileReflexDecisionRuntimeActivationStore(),
    private val calibration: ReflexRuntimeCalibration = StaticReflexRuntimeCalibration,
    private val resourcePolicy: ReflexRuntimeResourcePolicy =
        UnconstrainedReflexRuntimeResourcePolicy,
    private val healthPolicy: ReflexRuntimeHealthPolicy = ReflexRuntimeHealthPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonicNanos: () -> Long = System::nanoTime
) : ReflexDecisionRuntimeController {
    @Volatile
    private var activePort: NativeReflexDecisionPort? = null

    @Volatile
    private var activation: ReflexDecisionRuntimeActivation? = null

    @Volatile
    private var standbyPort: NativeReflexDecisionPort? = null

    @Volatile
    private var standbyActivation: ReflexDecisionRuntimeActivation? = null

    @Volatile
    private var healthState = ReflexRuntimeHealthSnapshot(checkpointId = null)

    @Volatile
    private var lastLearnedInferenceAtEpochMs: Long? = null

    @Synchronized
    override fun activate(port: NativeReflexDecisionPort): Result<ReflexDecisionRuntimeActivation> =
        runCatching {
            val currentActivation = activation
            val replacing =
                currentActivation != null &&
                    currentActivation.checkpointId != port.checkpointId
            if (replacing) {
                replacementGate.validate(
                    candidate = port,
                    baselineCheckpointId = requireNotNull(currentActivation).checkpointId
                ).getOrThrow()
            } else {
                activationGate.validate(port).getOrThrow()
            }

            val activatedAt = clock()
            val next = ReflexDecisionRuntimeActivation(
                checkpointId = port.checkpointId,
                weightArtifactSha256 = port.weightArtifactSha256,
                activatedAtEpochMs = activatedAt
            )
            activationStore.persistActive(next, activatedAt)

            if (replacing) {
                standbyPort = activePort
                standbyActivation = currentActivation
            } else if (currentActivation == null) {
                standbyPort = null
                standbyActivation = null
            }
            activePort = port
            activation = next
            lastLearnedInferenceAtEpochMs = null
            healthState = ReflexRuntimeHealthSnapshot(
                checkpointId = next.checkpointId,
                automaticRollbacks = healthState.automaticRollbacks
            )
            next
        }

    @Synchronized
    override fun recover(
        resolver: NativeReflexDecisionPortResolver
    ): Result<ReflexDecisionRuntimeActivation?> = runCatching {
        val intent = activationStore.load() ?: return@runCatching null
        if (intent.status == ReflexRuntimeActivationIntentStatus.DISABLED) {
            activePort = null
            activation = null
            return@runCatching null
        }

        val checkpointId = requireNotNull(intent.checkpointId)
        val weightDigest = requireNotNull(intent.weightArtifactSha256)
        val port = resolver.resolve(checkpointId, weightDigest).getOrThrow()
        require(port.checkpointId == checkpointId) {
            "recovered Reflex runtime port checkpoint identity mismatch"
        }
        require(port.weightArtifactSha256 == weightDigest) {
            "recovered Reflex runtime port weight identity mismatch"
        }
        activationGate.validate(port).getOrThrow()

        val restored = ReflexDecisionRuntimeActivation(
            checkpointId = checkpointId,
            weightArtifactSha256 = weightDigest,
            activatedAtEpochMs = requireNotNull(intent.activatedAtEpochMs)
        )
        activePort = port
        activation = restored
        lastLearnedInferenceAtEpochMs = null
        standbyPort = null
        standbyActivation = null
        healthState = ReflexRuntimeHealthSnapshot(
            checkpointId = restored.checkpointId,
            automaticRollbacks = healthState.automaticRollbacks
        )
        restored
    }

    @Synchronized
    override fun rollback(): ReflexDecisionRuntimeActivation? {
        val previous = activation
        val now = clock()
        activationStore.persistDisabled(now)
        activePort = null
        activation = null
        lastLearnedInferenceAtEpochMs = null
        standbyPort = null
        standbyActivation = null
        healthState = ReflexRuntimeHealthSnapshot(
            checkpointId = null,
            automaticRollbacks = healthState.automaticRollbacks
        )
        return previous
    }

    override fun active(): ReflexDecisionRuntimeActivation? = activation

    override fun persistedIntent(): ReflexRuntimeActivationIntent? = activationStore.load()

    override fun health(): ReflexRuntimeHealthSnapshot = healthState

    override fun adaptivePolicy(): ReflexAdaptiveRuntimePolicy? =
        activation?.checkpointId?.let(calibration::policy)

    override fun bindActionDecision(
        requestId: ActionRequestId,
        decision: ReflexDecision
    ) {
        val checkpointId = activation?.checkpointId ?: return
        if (decision.source != ReflexDecisionSource.NATIVE_SYSTEM1) return
        calibration.bindAction(
            requestId = requestId,
            checkpointId = checkpointId,
            confidence = decision.confidence
        )
    }

    override fun observeActionOutcome(requestId: ActionRequestId, status: ActionStatus) {
        calibration.observeActionOutcome(requestId, status)
    }

    override fun discardActionDecision(requestId: ActionRequestId) {
        calibration.discardAction(requestId)
    }

    override fun decide(request: ReflexDecisionRequest): ReflexDecision {
        val port = activePort ?: return fallback.decide(request)
        val currentAdaptivePolicy = calibration.policy(port.checkpointId)
        val resourceDecision = resourcePolicy.evaluate(currentAdaptivePolicy)
        recordResourceDecision(port, resourceDecision)
        if (!resourceDecision.allowLearnedInference) {
            return fallback.decide(request)
        }
        if (resourceDecision.minInterInferenceMs > 0L) {
            val now = clock()
            val previous = lastLearnedInferenceAtEpochMs
            if (
                previous != null &&
                now >= previous &&
                now - previous < resourceDecision.minInterInferenceMs
            ) {
                recordThrottleSkip(port, resourceDecision)
                return fallback.decide(request)
            }
            lastLearnedInferenceAtEpochMs = now
        }

        val available = request.descriptors
            .map { it.capability }
            .distinct()
            .sortedBy { it.value }
            .take(ReflexExperienceTrainingExample.MAX_AVAILABLE_CAPABILITIES)
            .toCollection(linkedSetOf())
        val startedAtNanos = monotonicNanos()
        val predictionResult = port.predict(
            NativeReflexDecisionInput(
                featureHashes = ReflexDecisionFeatureEncoder.encode(request.userInput),
                availableCapabilities = available
            )
        )
        val elapsedNanos = (monotonicNanos() - startedAtNanos).coerceAtLeast(0L)
        val latencyMs = elapsedNanos.toDouble() / 1_000_000.0
        recordWorkCost(port, resourceDecision, latencyMs)
        applyResidencyHint(port, resourceDecision)

        val prediction = predictionResult.getOrElse {
            val policy = calibration.policy(port.checkpointId)
            val unhealthy = recordPredictionHealth(
                port = port,
                latencyMs = latencyMs,
                failed = true,
                latencyBudgetMs = policy.maxPredictionLatencyMs
            )
            if (unhealthy) {
                autoRollbackUnhealthy(port)
            }
            return fallback.decide(request)
        }

        calibration.observePrediction(port.checkpointId, latencyMs)
        val adaptivePolicy = calibration.policy(port.checkpointId)
        val unhealthy = recordPredictionHealth(
            port = port,
            latencyMs = latencyMs,
            failed = false,
            latencyBudgetMs = adaptivePolicy.maxPredictionLatencyMs
        )
        if (unhealthy) {
            autoRollbackUnhealthy(port)
            return fallback.decide(request)
        }

        if (prediction.disposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2) {
            return escalation(prediction.confidence, prediction.uncertainty)
        }

        val capability = requireNotNull(prediction.capability)
        if (capability !in available) {
            return escalation(0.0, 1.0)
        }
        if (
            prediction.confidence < adaptivePolicy.minFastPathConfidence ||
            prediction.uncertainty > adaptivePolicy.maxFastPathUncertainty
        ) {
            return escalation(prediction.confidence, prediction.uncertainty)
        }

        val proposal = binder.bind(request, capability)
            ?: return escalation(prediction.confidence, prediction.uncertainty)

        return ReflexDecision(
            disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
            confidence = prediction.confidence,
            uncertainty = prediction.uncertainty,
            source = ReflexDecisionSource.NATIVE_SYSTEM1,
            capability = capability,
            input = proposal.input,
            reason = proposal.reason,
            fastPathConfidenceThreshold = adaptivePolicy.minFastPathConfidence,
            fastPathUncertaintyThreshold = adaptivePolicy.maxFastPathUncertainty
        )
    }

    @Synchronized
    private fun recordResourceDecision(
        port: NativeReflexDecisionPort,
        decision: ReflexRuntimeResourceDecision
    ) {
        if (activePort !== port) return
        val previous = healthState
        healthState = previous.copy(
            checkpointId = port.checkpointId,
            resourceSkips = previous.resourceSkips +
                if (decision.allowLearnedInference) 0L else 1L,
            lastResourceMode = decision.mode,
            lastResourceMemoryMb = decision.memoryBudgetMb,
            lastResourceThermalClass = decision.thermalClass,
            lastResourceBatteryPercent = decision.batteryPercent,
            lastResourceCharging = decision.charging
        )
    }

    @Synchronized
    private fun recordThrottleSkip(
        port: NativeReflexDecisionPort,
        decision: ReflexRuntimeResourceDecision
    ) {
        if (activePort !== port) return
        val previous = healthState
        healthState = previous.copy(
            checkpointId = port.checkpointId,
            resourceSkips = previous.resourceSkips + 1L,
            throttleSkips = previous.throttleSkips + 1L,
            lastResourceMode = decision.mode,
            lastResourceMemoryMb = decision.memoryBudgetMb,
            lastResourceThermalClass = decision.thermalClass,
            lastResourceBatteryPercent = decision.batteryPercent,
            lastResourceCharging = decision.charging
        )
    }

    @Synchronized
    private fun recordWorkCost(
        port: NativeReflexDecisionPort,
        decision: ReflexRuntimeResourceDecision,
        latencyMs: Double
    ) {
        if (activePort !== port) return
        val modeMultiplier = when (decision.mode) {
            ReflexRuntimeResourceMode.NORMAL -> 1.0
            ReflexRuntimeResourceMode.PRESSURED -> 1.25
            ReflexRuntimeResourceMode.BLOCKED -> 1.5
        }
        val thermalMultiplier = 1.0 + decision.thermalClass.coerceAtLeast(0) * 0.05
        val batteryMultiplier = if (
            decision.charging != true &&
            decision.batteryPercent != null &&
            decision.batteryPercent <= ResourceGovernorReflexRuntimeResourcePolicy.CONSERVE_BATTERY_PERCENT
        ) {
            1.25
        } else {
            1.0
        }
        val score = latencyMs * modeMultiplier * thermalMultiplier * batteryMultiplier
        val previous = healthState
        val ewma = previous.workCostEwma?.let { it + 0.125 * (score - it) } ?: score
        healthState = previous.copy(
            checkpointId = port.checkpointId,
            lastWorkCostScore = score,
            workCostEwma = ewma
        )
    }

    @Synchronized
    private fun applyResidencyHint(
        port: NativeReflexDecisionPort,
        decision: ReflexRuntimeResourceDecision
    ) {
        if (decision.residencyHint != ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION) return
        val residencyPort = port as? NativeReflexResidencyAwarePort ?: return
        if (activePort !== port) return
        val previous = healthState
        val result = residencyPort.releaseTransientResources()
        healthState = previous.copy(
            checkpointId = port.checkpointId,
            residencyReleases = previous.residencyReleases + if (result.isSuccess) 1L else 0L,
            residencyReleaseFailures =
                previous.residencyReleaseFailures + if (result.isFailure) 1L else 0L
        )
    }

    @Synchronized
    private fun recordPredictionHealth(
        port: NativeReflexDecisionPort,
        latencyMs: Double,
        failed: Boolean,
        latencyBudgetMs: Double
    ): Boolean {
        if (activePort !== port) return false
        val previous = healthState
        val effectiveLatencyBudget = minOf(
            latencyBudgetMs,
            healthPolicy.maxPredictionLatencyMs
        )
        val slow = latencyMs > effectiveLatencyBudget
        val next = previous.copy(
            checkpointId = port.checkpointId,
            totalPredictions = previous.totalPredictions + 1L,
            predictionFailures = previous.predictionFailures + if (failed) 1L else 0L,
            consecutivePredictionFailures =
                if (failed) previous.consecutivePredictionFailures + 1 else 0,
            slowPredictions = previous.slowPredictions + if (slow) 1L else 0L,
            consecutiveSlowPredictions =
                if (slow) previous.consecutiveSlowPredictions + 1 else 0,
            lastPredictionLatencyMs = latencyMs,
            maxPredictionLatencyMs = maxOf(previous.maxPredictionLatencyMs, latencyMs)
        )
        healthState = next
        return next.consecutivePredictionFailures >=
            healthPolicy.maxConsecutivePredictionFailures ||
            next.consecutiveSlowPredictions >=
            healthPolicy.maxConsecutiveSlowPredictions
    }

    @Synchronized
    private fun autoRollbackUnhealthy(port: NativeReflexDecisionPort) {
        if (activePort !== port) return
        val rollbackCount = healthState.automaticRollbacks + 1L
        val previousPort = standbyPort
        val previousActivation = standbyActivation
        val now = clock()

        if (previousPort != null && previousActivation != null) {
            activationStore.persistActive(previousActivation, now)
            activePort = previousPort
            activation = previousActivation
            standbyPort = null
            standbyActivation = null
            healthState = ReflexRuntimeHealthSnapshot(
                checkpointId = previousActivation.checkpointId,
                automaticRollbacks = rollbackCount
            )
        } else {
            activationStore.persistDisabled(now)
            activePort = null
            activation = null
            standbyPort = null
            standbyActivation = null
            healthState = ReflexRuntimeHealthSnapshot(
                checkpointId = null,
                automaticRollbacks = rollbackCount
            )
        }
    }

    private fun escalation(
        confidence: Double,
        uncertainty: Double
    ): ReflexDecision = ReflexDecision(
        disposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
        confidence = confidence.coerceIn(0.0, 1.0),
        uncertainty = uncertainty.coerceIn(0.0, 1.0),
        source = ReflexDecisionSource.NATIVE_SYSTEM1
    )
}
