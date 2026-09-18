package io.amper.neuroos.core

import java.util.Locale

data class DeliberationCandidate(
    val index: Int,
    val steps: List<SovereignPlanStep>
) {
    init {
        require(index > 0)
        require(steps.isNotEmpty())
        require(steps.size <= TitanPlanProtocol.MAX_STEPS)
    }

    val signature: StrategySignature
        get() = StrategySignature(steps.sortedBy { it.index }.map { it.capability })
}

data class DeliberationEvaluation(
    val candidate: DeliberationCandidate,
    val historicalEvidenceSupport: Double,
    val evidenceObserved: Boolean,
    val stepEfficiency: Double,
    val readOnlySafety: Double,
    val sideEffectSteps: Int,
    val counterfactualViability: Double = 0.50,
    val counterfactualConfidence: Double = 0.0,
    val authorityBlockRisk: Double = 0.0,
    val environmentUnavailableRisk: Double = 0.0,
    val protocolFailureRisk: Double = 0.0,
    val legacyUncertainty: Double = 0.0,
    val totalScore: Double
) {
    init {
        require(historicalEvidenceSupport in 0.0..1.0)
        require(stepEfficiency in 0.0..1.0)
        require(readOnlySafety in 0.0..1.0)
        require(sideEffectSteps in 0..candidate.steps.size)
        require(counterfactualViability in 0.0..1.0)
        require(counterfactualConfidence in 0.0..1.0)
        require(authorityBlockRisk in 0.0..1.0)
        require(environmentUnavailableRisk in 0.0..1.0)
        require(protocolFailureRisk in 0.0..1.0)
        require(legacyUncertainty in 0.0..1.0)
        require(totalScore in 0.0..1.0)
    }
}

data class DeliberationSelection(
    val selected: DeliberationEvaluation,
    val evaluated: List<DeliberationEvaluation>
) {
    init {
        require(evaluated.isNotEmpty())
        require(selected in evaluated)
        require(evaluated.size <= TitanDeliberationProtocol.MAX_CANDIDATES)
    }
}

/**
 * One-inference, multi-candidate planning protocol.
 *
 * Candidate parsing is deliberately delegated back through [TitanPlanProtocol], preserving the
 * canonical capability whitelist, input contracts and exact plan-time tool binding for every
 * alternative before any candidate can be evaluated.
 */
object TitanDeliberationProtocol {
    const val MAX_CANDIDATES = 3
    private const val OPEN = "<AMPER_DELIBERATION_V1>"
    private const val CLOSE = "</AMPER_DELIBERATION_V1>"
    private val FIELD = Regex("candidate\\.(\\d+)\\.step\\.(\\d+)\\.(capability|reason|input)")

    fun parse(
        modelOutput: String,
        allowedCapabilities: Collection<CapabilityId>,
        descriptors: Collection<ToolDescriptor>
    ): Result<List<DeliberationCandidate>> = runCatching {
        val text = modelOutput.trim()

        // Backward-compatible path for existing models/tests that still emit one canonical plan.
        if (text.startsWith("<AMPER_PLAN_V1>")) {
            val steps = TitanPlanProtocol.parse(
                modelOutput = text,
                allowedCapabilities = allowedCapabilities,
                descriptors = descriptors
            ).getOrThrow()
            return@runCatching listOf(DeliberationCandidate(index = 1, steps = steps))
        }

        require(text.startsWith(OPEN) && text.endsWith(CLOSE)) {
            "deliberation envelope must be the entire model output"
        }
        val body = text.removePrefix(OPEN).removeSuffix(CLOSE).trim()
        require(body.isNotBlank()) { "deliberation must contain at least one candidate" }

        val fields = linkedMapOf<String, String>()
        body.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "deliberation field missing '='" }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(FIELD.matches(key)) { "unknown deliberation field: $key" }
                require(fields.put(key, value) == null) {
                    "duplicate deliberation field: $key"
                }
            }

        val candidateIndices = fields.keys
            .mapNotNull { FIELD.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()
        require(candidateIndices.isNotEmpty()) { "deliberation has no candidate" }
        require(candidateIndices.size <= MAX_CANDIDATES) {
            "deliberation exceeds $MAX_CANDIDATES candidates"
        }
        require(candidateIndices == (1..candidateIndices.size).toList()) {
            "candidate numbers must be contiguous from 1"
        }

        candidateIndices.map { candidateIndex ->
            val candidateFields = fields
                .filterKeys { it.startsWith("candidate.$candidateIndex.step.") }
            val stepIndices = candidateFields.keys
                .mapNotNull { FIELD.matchEntire(it)?.groupValues?.get(2)?.toIntOrNull() }
                .distinct()
                .sorted()
            require(stepIndices.isNotEmpty()) {
                "candidate $candidateIndex must contain at least one step"
            }
            require(stepIndices.size <= TitanPlanProtocol.MAX_STEPS) {
                "candidate $candidateIndex exceeds bounded plan size"
            }
            require(stepIndices == (1..stepIndices.size).toList()) {
                "candidate $candidateIndex step numbers must be contiguous from 1"
            }

            val canonicalPlan = buildString {
                appendLine("<AMPER_PLAN_V1>")
                stepIndices.forEach { stepIndex ->
                    listOf("capability", "reason", "input").forEach { field ->
                        val key = "candidate.$candidateIndex.step.$stepIndex.$field"
                        val value = requireNotNull(fields[key]) {
                            "candidate $candidateIndex step $stepIndex requires capability, reason and input"
                        }
                        appendLine("step.$stepIndex.$field=$value")
                    }
                }
                append("</AMPER_PLAN_V1>")
            }

            val steps = TitanPlanProtocol.parse(
                modelOutput = canonicalPlan,
                allowedCapabilities = allowedCapabilities,
                descriptors = descriptors
            ).getOrThrow()
            DeliberationCandidate(index = candidateIndex, steps = steps)
        }.distinctBy { it.signature }
    }

    fun instructions(
        capabilities: Collection<CapabilityId>,
        descriptors: Collection<ToolDescriptor>
    ): String {
        val canonicalContext = TitanPlanProtocol.instructions(capabilities, descriptors)
            .lineSequence()
            .filter { line ->
                line.startsWith("Allowed capability names:") || line.startsWith("TOOL ")
            }
            .joinToString("\n")

        return buildString {
            appendLine("Deliberate over bounded execution strategies only. Do not execute or claim any tool has executed.")
            appendLine("Return 1 to $MAX_CANDIDATES distinct candidate plans in one envelope.")
            appendLine("Prefer materially different capability sequences when the live tool surface supports alternatives.")
            appendLine("Every candidate must independently satisfy the current tool contracts.")
            appendLine("Legacy single-plan <AMPER_PLAN_V1>...</AMPER_PLAN_V1> output remains accepted for compatibility.")
            appendLine("Output only one $OPEN envelope and nothing else when using deliberation format.")
            appendLine(OPEN)
            appendLine("candidate.1.step.1.capability=<allowed capability>")
            appendLine("candidate.1.step.1.reason=<short user-centered reason>")
            appendLine("candidate.1.step.1.input=<single-line input matching the tool contract>")
            appendLine("candidate.2.step.1.capability=<optional alternative capability>")
            appendLine("candidate.2.step.1.reason=<optional alternative reason>")
            appendLine("candidate.2.step.1.input=<optional alternative input>")
            appendLine(CLOSE)
            if (canonicalContext.isNotBlank()) append(canonicalContext)
        }.trim()
    }
}

/**
 * Deterministic counterfactual evaluator for already-valid candidate plans.
 *
 * No model call and no tool execution occurs here. Phase182 additionally projects causal
 * counterfactual viability from execution/environment/protocol evidence before materialization.
 * Authority-block history is diagnostic only and is explicitly excluded from causal viability,
 * so deliberation cannot learn to route around permission boundaries. Unknown strategies retain
 * bounded neutral priors. Read-only/shorter strategies receive a modest structural preference.
 */
object EvidenceGroundedDeliberationEvaluator {
    private const val EVIDENCE_WEIGHT = 0.35
    private const val COUNTERFACTUAL_WEIGHT = 0.25
    private const val EFFICIENCY_WEIGHT = 0.15
    private const val READ_ONLY_SAFETY_WEIGHT = 0.25
    private const val UNOBSERVED_EVIDENCE_PRIOR = 0.50

    fun select(
        candidates: Collection<DeliberationCandidate>,
        strategies: StrategyLearningModel,
        allowedCapabilities: Set<CapabilityId>
    ): DeliberationSelection {
        require(candidates.isNotEmpty())
        require(candidates.size <= TitanDeliberationProtocol.MAX_CANDIDATES)

        val evaluated = candidates
            .distinctBy { it.signature }
            .map { candidate -> evaluate(candidate, strategies, allowedCapabilities) }
            .sortedWith(
                compareByDescending<DeliberationEvaluation> { it.totalScore }
                    .thenByDescending { it.historicalEvidenceSupport }
                    .thenBy { it.sideEffectSteps }
                    .thenBy { it.candidate.steps.size }
                    .thenBy { it.candidate.signature.canonical }
            )

        return DeliberationSelection(
            selected = evaluated.first(),
            evaluated = evaluated
        )
    }

    private fun evaluate(
        candidate: DeliberationCandidate,
        strategies: StrategyLearningModel,
        allowedCapabilities: Set<CapabilityId>
    ): DeliberationEvaluation {
        val snapshot = strategies.snapshot(candidate.signature)
        val guidance = snapshot?.let {
            EvidenceGroundedStrategyGuidance.select(
                evidence = listOf(it),
                allowedCapabilities = allowedCapabilities,
                limit = 1
            ).singleOrNull()
        }
        val evidenceSupport = guidance?.evidenceSupport ?: UNOBSERVED_EVIDENCE_PRIOR
        val stepEfficiency = when (candidate.steps.size) {
            1 -> 1.00
            2 -> 0.90
            3 -> 0.80
            else -> 0.70
        }
        val sideEffectSteps = candidate.steps.count { step ->
            step.boundSideEffect?.toString() != "READ_ONLY"
        }
        val readOnlySafety = 1.0 -
            (sideEffectSteps.toDouble() / candidate.steps.size.toDouble())

        val projection = CausalCounterfactualWorldModel.project(
            candidate = candidate,
            strategies = strategies
        )
        val score = (
            evidenceSupport * EVIDENCE_WEIGHT +
                projection.causalViability * COUNTERFACTUAL_WEIGHT +
                stepEfficiency * EFFICIENCY_WEIGHT +
                readOnlySafety * READ_ONLY_SAFETY_WEIGHT
            ).coerceIn(0.0, 1.0)

        return DeliberationEvaluation(
            candidate = candidate,
            historicalEvidenceSupport = evidenceSupport,
            evidenceObserved = guidance != null,
            stepEfficiency = stepEfficiency,
            readOnlySafety = readOnlySafety,
            sideEffectSteps = sideEffectSteps,
            counterfactualViability = projection.causalViability,
            counterfactualConfidence = projection.epistemicConfidence,
            authorityBlockRisk = projection.authorityBlockRisk,
            environmentUnavailableRisk = projection.environmentUnavailableRisk,
            protocolFailureRisk = projection.protocolFailureRisk,
            legacyUncertainty = projection.legacyUncertainty,
            totalScore = score
        )
    }

    fun renderSelection(selection: DeliberationSelection): String = buildString {
        append("candidates=")
        append(selection.evaluated.size)
        append(" selected=")
        append(selection.selected.candidate.index)
        append(" score=")
        append(fmt(selection.selected.totalScore))
        append(" evidence=")
        append(fmt(selection.selected.historicalEvidenceSupport))
        append(" observed=")
        append(selection.selected.evidenceObserved)
        append(" side_effect_steps=")
        append(selection.selected.sideEffectSteps)
        append(" counterfactual_viability=")
        append(fmt(selection.selected.counterfactualViability))
        append(" counterfactual_confidence=")
        append(fmt(selection.selected.counterfactualConfidence))
        append(" environment_risk=")
        append(fmt(selection.selected.environmentUnavailableRisk))
        append(" protocol_risk=")
        append(fmt(selection.selected.protocolFailureRisk))
        append(" legacy_uncertainty=")
        append(fmt(selection.selected.legacyUncertainty))
    }

    private fun fmt(value: Double): String = "%.3f".format(Locale.US, value)
}
