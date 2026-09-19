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

fun interface ReflexDecisionRuntimeActivationGate {
    fun validate(port: NativeReflexDecisionPort): Result<Unit>
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
    fun rollback(): ReflexDecisionRuntimeActivation?
    fun active(): ReflexDecisionRuntimeActivation?
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
    private val fallback: ReflexDecisionCortex = DeterministicReflexDecisionCortex,
    private val binder: ReflexActionArgumentBinder = CanonicalReflexActionArgumentBinder,
    private val clock: () -> Long = System::currentTimeMillis
) : ReflexDecisionRuntimeController {
    @Volatile
    private var activePort: NativeReflexDecisionPort? = null

    @Volatile
    private var activation: ReflexDecisionRuntimeActivation? = null

    @Synchronized
    override fun activate(port: NativeReflexDecisionPort): Result<ReflexDecisionRuntimeActivation> =
        runCatching {
            activationGate.validate(port).getOrThrow()
            val next = ReflexDecisionRuntimeActivation(
                checkpointId = port.checkpointId,
                weightArtifactSha256 = port.weightArtifactSha256,
                activatedAtEpochMs = clock()
            )
            activePort = port
            activation = next
            next
        }

    @Synchronized
    override fun rollback(): ReflexDecisionRuntimeActivation? {
        val previous = activation
        activePort = null
        activation = null
        return previous
    }

    override fun active(): ReflexDecisionRuntimeActivation? = activation

    override fun decide(request: ReflexDecisionRequest): ReflexDecision {
        val port = activePort ?: return fallback.decide(request)
        val available = request.descriptors.mapTo(linkedSetOf()) { it.capability }
        val prediction = port.predict(
            NativeReflexDecisionInput(
                featureHashes = ReflexDecisionFeatureEncoder.encode(request.userInput),
                availableCapabilities = available
            )
        ).getOrElse {
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
            prediction.confidence < ReflexDecision.MIN_FAST_PATH_CONFIDENCE ||
            prediction.uncertainty > ReflexDecision.MAX_FAST_PATH_UNCERTAINTY
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
            reason = proposal.reason
        )
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
