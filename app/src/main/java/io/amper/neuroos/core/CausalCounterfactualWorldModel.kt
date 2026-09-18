package io.amper.neuroos.core

import java.util.Locale

/**
 * Read-only causal projection of one already-valid candidate strategy.
 *
 * This model does not execute tools, mutate world facts, grant authority or rewrite strategy
 * evidence. It projects structural risk from governed historical outcomes only. Authority blocks
 * are surfaced diagnostically but deliberately excluded from ranking so the planner cannot learn
 * to route around permissions.
 */
data class CausalCounterfactualProjection(
    val signature: StrategySignature,
    val evidenceObserved: Boolean,
    val executionSuccessExpectation: Double,
    val environmentUnavailableRisk: Double,
    val protocolFailureRisk: Double,
    val authorityBlockRisk: Double,
    val legacyUncertainty: Double,
    val epistemicConfidence: Double,
    val causalViability: Double
) {
    init {
        require(executionSuccessExpectation in 0.0..1.0)
        require(environmentUnavailableRisk in 0.0..1.0)
        require(protocolFailureRisk in 0.0..1.0)
        require(authorityBlockRisk in 0.0..1.0)
        require(legacyUncertainty in 0.0..1.0)
        require(epistemicConfidence in 0.0..1.0)
        require(causalViability in 0.0..1.0)
    }
}

/**
 * Phase182 causal counterfactual world model.
 *
 * The projection separates endogenous execution quality from exogenous environment/protocol
 * fragility. The historical authority-block rate is never part of [causalViability], preventing
 * counterfactual planning from treating permission denial as a reason to choose a route around
 * governance. Unknown strategies receive bounded neutral priors instead of fabricated certainty.
 */
object CausalCounterfactualWorldModel {
    private const val PRIOR_SUCCESS = 1.0
    private const val PRIOR_FAILURE = 1.0
    private const val UNOBSERVED_SUCCESS_EXPECTATION = 0.50
    private const val UNOBSERVED_CAUSAL_VIABILITY = 0.50

    fun project(
        candidate: DeliberationCandidate,
        strategies: StrategyLearningModel
    ): CausalCounterfactualProjection {
        val snapshot = strategies.snapshot(candidate.signature)
            ?: return CausalCounterfactualProjection(
                signature = candidate.signature,
                evidenceObserved = false,
                executionSuccessExpectation = UNOBSERVED_SUCCESS_EXPECTATION,
                environmentUnavailableRisk = 0.0,
                protocolFailureRisk = 0.0,
                authorityBlockRisk = 0.0,
                legacyUncertainty = 0.0,
                epistemicConfidence = 0.0,
                causalViability = UNOBSERVED_CAUSAL_VIABILITY
            )

        val executionDenominator =
            snapshot.successes + snapshot.executionFailures + PRIOR_SUCCESS + PRIOR_FAILURE
        val executionSuccessExpectation =
            (snapshot.successes + PRIOR_SUCCESS) / executionDenominator

        val attributedWorldEvents = (
            snapshot.successes +
                snapshot.executionFailures +
                snapshot.environmentUnavailable +
                snapshot.protocolFailures
            ).coerceAtLeast(1)
        val diagnosticEvents = (
            snapshot.successes +
                snapshot.executionFailures +
                snapshot.authorityBlocked +
                snapshot.environmentUnavailable +
                snapshot.protocolFailures
            ).coerceAtLeast(1)

        val environmentRisk =
            snapshot.environmentUnavailable.toDouble() / attributedWorldEvents.toDouble()
        val protocolRisk =
            snapshot.protocolFailures.toDouble() / attributedWorldEvents.toDouble()
        val authorityRisk =
            snapshot.authorityBlocked.toDouble() / diagnosticEvents.toDouble()

        val totalHistoricEvents = (
            snapshot.successes +
                snapshot.failures +
                snapshot.aborted
            ).coerceAtLeast(1)
        val legacyUncertainty =
            snapshot.legacyUnattributedFailures.toDouble() / totalHistoricEvents.toDouble()

        // Confidence excludes authority blocks so permission history cannot improve or reduce
        // the ranking confidence of a strategy. Legacy unattributed outcomes also remain neutral.
        val causalEvidenceEvents =
            snapshot.successes +
                snapshot.executionFailures +
                snapshot.environmentUnavailable +
                snapshot.protocolFailures
        val epistemicConfidence =
            causalEvidenceEvents.toDouble() / (causalEvidenceEvents.toDouble() + 4.0)

        // Environment/protocol fragility can reduce expected applicability, but authority history
        // is excluded by construction. Legacy uncertainty lowers confidence, not execution credit.
        val exogenousFragility = (
            environmentRisk * 0.20 +
                protocolRisk * 0.20 +
                legacyUncertainty * 0.15
            ).coerceIn(0.0, 0.55)
        val confidenceFactor = 0.75 + epistemicConfidence * 0.25
        val causalViability = (
            executionSuccessExpectation *
                (1.0 - exogenousFragility) *
                confidenceFactor
            ).coerceIn(0.0, 1.0)

        return CausalCounterfactualProjection(
            signature = candidate.signature,
            evidenceObserved = true,
            executionSuccessExpectation = executionSuccessExpectation,
            environmentUnavailableRisk = environmentRisk,
            protocolFailureRisk = protocolRisk,
            authorityBlockRisk = authorityRisk,
            legacyUncertainty = legacyUncertainty,
            epistemicConfidence = epistemicConfidence,
            causalViability = causalViability
        )
    }

    fun render(projection: CausalCounterfactualProjection): String = buildString {
        append("signature=")
        append(projection.signature.canonical)
        append(" observed=")
        append(projection.evidenceObserved)
        append(" expected_execution_success=")
        append(fmt(projection.executionSuccessExpectation))
        append(" environment_risk=")
        append(fmt(projection.environmentUnavailableRisk))
        append(" protocol_risk=")
        append(fmt(projection.protocolFailureRisk))
        append(" authority_block_risk=")
        append(fmt(projection.authorityBlockRisk))
        append(" legacy_uncertainty=")
        append(fmt(projection.legacyUncertainty))
        append(" epistemic_confidence=")
        append(fmt(projection.epistemicConfidence))
        append(" causal_viability=")
        append(fmt(projection.causalViability))
    }

    private fun fmt(value: Double): String = "%.3f".format(Locale.US, value)
}
