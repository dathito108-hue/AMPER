package io.amper.neuroos.core.v2

import java.security.MessageDigest

data class AmcfRecurrentState(
    val foundation: AmcfFoundationBinding,
    val completedCycleIndex: Int,
    val completedCycleKind: AmcfComputeCycleKind,
    val candidateDigest: String,
    val evidenceDigest: String,
    val confidence: Double,
    val uncertainty: Double,
    val evidenceSufficiency: Double,
    val verifiedPasses: Int,
    val revisionCount: Int
) {
    init {
        require(completedCycleIndex > 0)
        require(candidateDigest.matches(Regex("[0-9a-f]{64}"))) {
            "AMCF candidate state must be represented by a SHA-256 digest"
        }
        require(evidenceDigest.matches(Regex("[0-9a-f]{64}"))) {
            "AMCF evidence state must be represented by a SHA-256 digest"
        }
        require(confidence in 0.0..1.0)
        require(uncertainty in 0.0..1.0)
        require(evidenceSufficiency in 0.0..1.0)
        require(verifiedPasses >= 0)
        require(revisionCount >= 0)
        require(revisionCount <= verifiedPasses) {
            "AMCF cannot revise more times than it has verified"
        }
    }

    /**
     * Stable control-state digest only. AMCF recurrent control never requires storing free-form
     * hidden reasoning or chain-of-thought.
     */
    val stateDigest: String
        get() = sha256(
            listOf(
                "AMCF-RECURRENT-STATE-V1",
                foundation.foundationId,
                foundation.semanticSha256,
                completedCycleIndex.toString(),
                completedCycleKind.name,
                candidateDigest,
                evidenceDigest,
                six(confidence),
                six(uncertainty),
                six(evidenceSufficiency),
                verifiedPasses.toString(),
                revisionCount.toString()
            ).joinToString("|")
        )
}

object AmcfRecurrentStateTransition {
    fun record(
        cycle: AmcfComputeCycle,
        previous: AmcfRecurrentState?,
        candidateDigest: String,
        evidenceDigest: String,
        confidence: Double,
        uncertainty: Double,
        evidenceSufficiency: Double
    ): AmcfRecurrentState {
        if (previous == null) {
            require(cycle.index == 1) {
                "AMCF recurrent state must begin at cycle 1"
            }
        } else {
            require(previous.foundation == cycle.foundation) {
                "AMCF recurrent state cannot change foundation identity"
            }
            require(cycle.index > previous.completedCycleIndex) {
                "AMCF recurrent cycle index must advance"
            }
        }

        val previousVerified = previous?.verifiedPasses ?: 0
        val previousRevisions = previous?.revisionCount ?: 0
        val verified = previousVerified +
            if (cycle.kind == AmcfComputeCycleKind.VERIFY) 1 else 0
        val revisions = previousRevisions +
            if (cycle.kind == AmcfComputeCycleKind.REVISE) 1 else 0

        if (cycle.kind == AmcfComputeCycleKind.REVISE) {
            require(previousVerified > previousRevisions) {
                "AMCF revise cycle requires an unmatched prior verification"
            }
        }

        return AmcfRecurrentState(
            foundation = cycle.foundation,
            completedCycleIndex = cycle.index,
            completedCycleKind = cycle.kind,
            candidateDigest = candidateDigest,
            evidenceDigest = evidenceDigest,
            confidence = confidence,
            uncertainty = uncertainty,
            evidenceSufficiency = evidenceSufficiency,
            verifiedPasses = verified,
            revisionCount = revisions
        )
    }
}

enum class AmcfEarlyExitAction {
    NOT_ELIGIBLE,
    CONTINUE_DELIBERATION,
    ADVANCE_TO_VERIFY,
    ADVANCE_TO_FINALIZE
}

data class AmcfEarlyExitDecision(
    val action: AmcfEarlyExitAction,
    val cycleIndex: Int,
    val reasonCode: String
) {
    init {
        require(cycleIndex > 0)
        require(reasonCode.matches(Regex("[a-z0-9][a-z0-9._-]{0,95}")))
    }
}

data class AmcfEarlyExitThreshold(
    val minimumConfidence: Double,
    val maximumUncertainty: Double,
    val minimumEvidenceSufficiency: Double
) {
    init {
        require(minimumConfidence in 0.0..1.0)
        require(maximumUncertainty in 0.0..1.0)
        require(minimumEvidenceSufficiency in 0.0..1.0)
    }
}

/**
 * Deterministic early-exit gate over structured recurrent state.
 *
 * It never inspects or stores hidden reasoning text. It can only shorten remaining DELIBERATE
 * cycles. Scheduled VERIFY/REVISE/FINALIZE work remains mandatory.
 */
object AmcfEarlyExitGate {
    fun decide(
        plan: AmcfComputeCyclePlan,
        completedCycle: AmcfComputeCycle,
        state: AmcfRecurrentState
    ): AmcfEarlyExitDecision {
        require(completedCycle in plan.cycles) {
            "AMCF early-exit cycle is not part of the supplied compute plan"
        }
        require(completedCycle.foundation == plan.foundation)
        require(state.foundation == plan.foundation)
        require(state.completedCycleIndex == completedCycle.index)
        require(state.completedCycleKind == completedCycle.kind)
        require(state.verifiedPasses <= plan.verifyPasses)

        if (
            completedCycle.kind != AmcfComputeCycleKind.DELIBERATE ||
            !completedCycle.earlyExitEligibleAfter
        ) {
            return AmcfEarlyExitDecision(
                action = AmcfEarlyExitAction.NOT_ELIGIBLE,
                cycleIndex = completedCycle.index,
                reasonCode = "cycle-not-early-exit-eligible"
            )
        }

        val threshold = threshold(plan.mode)
        val sufficient =
            state.confidence >= threshold.minimumConfidence &&
                state.uncertainty <= threshold.maximumUncertainty &&
                state.evidenceSufficiency >= threshold.minimumEvidenceSufficiency

        if (!sufficient) {
            return AmcfEarlyExitDecision(
                action = AmcfEarlyExitAction.CONTINUE_DELIBERATION,
                cycleIndex = completedCycle.index,
                reasonCode = "recurrent-evidence-insufficient"
            )
        }

        return AmcfEarlyExitDecision(
            action = if (plan.verifyPasses > state.verifiedPasses) {
                AmcfEarlyExitAction.ADVANCE_TO_VERIFY
            } else {
                AmcfEarlyExitAction.ADVANCE_TO_FINALIZE
            },
            cycleIndex = completedCycle.index,
            reasonCode = "recurrent-evidence-sufficient"
        )
    }

    fun threshold(mode: OmegaComputeMode): AmcfEarlyExitThreshold =
        when (mode) {
            OmegaComputeMode.REFLEX,
            OmegaComputeMode.FAST -> AmcfEarlyExitThreshold(
                minimumConfidence = 1.0,
                maximumUncertainty = 0.0,
                minimumEvidenceSufficiency = 1.0
            )
            OmegaComputeMode.STANDARD -> AmcfEarlyExitThreshold(
                minimumConfidence = 0.80,
                maximumUncertainty = 0.20,
                minimumEvidenceSufficiency = 0.75
            )
            OmegaComputeMode.REASON -> AmcfEarlyExitThreshold(
                minimumConfidence = 0.84,
                maximumUncertainty = 0.18,
                minimumEvidenceSufficiency = 0.80
            )
            OmegaComputeMode.DEEP -> AmcfEarlyExitThreshold(
                minimumConfidence = 0.88,
                maximumUncertainty = 0.15,
                minimumEvidenceSufficiency = 0.84
            )
            OmegaComputeMode.VERIFY -> AmcfEarlyExitThreshold(
                minimumConfidence = 1.0,
                maximumUncertainty = 0.0,
                minimumEvidenceSufficiency = 1.0
            )
        }
}

private fun six(value: Double): String =
    java.lang.String.format(java.util.Locale.US, "%.6f", value)

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
