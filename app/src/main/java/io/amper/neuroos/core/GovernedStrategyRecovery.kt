package io.amper.neuroos.core

enum class StrategyRecoveryBlockReason {
    PLAN_INCOMPLETE,
    DEPTH_EXHAUSTED,
    AUTHORITY_DENIED,
    USER_REJECTED,
    NON_EXECUTION_CAUSE,
    NO_RECOVERABLE_FAILURE,
    INSUFFICIENT_EVIDENCE,
    STRATEGY_NOT_RECOVERY_REQUIRED
}

data class StrategyRecoveryDecision(
    val eligible: Boolean,
    val failedSignature: StrategySignature,
    val blockReason: StrategyRecoveryBlockReason? = null,
    val evidence: StrategyGuidanceCandidate? = null
) {
    init {
        require(eligible == (blockReason == null))
        if (eligible) requireNotNull(evidence)
    }
}

/**
 * Bounded recovery admission for a failed governed plan.
 *
 * Recovery is not an execution path. It only admits one additional planning inference. Authority
 * denials and user rejection are explicitly non-recoverable so replanning cannot route around a
 * permission boundary or an explicit stop. Phase181 also keeps environment/protocol failures out
 * of strategy recovery because they are not evidence that the capability sequence itself is bad.
 * The replacement must use a structurally different
 * capability sequence and is still parsed/bound through TitanPlanProtocol.
 */
object GovernedStrategyRecovery {
    const val MAX_RECOVERY_DEPTH = 1

    fun assess(
        plan: SovereignPlan,
        evidence: StrategyEvidenceSnapshot?,
        allowedCapabilities: Set<CapabilityId>
    ): StrategyRecoveryDecision {
        val signature = StrategySignature.from(plan)

        fun blocked(reason: StrategyRecoveryBlockReason) = StrategyRecoveryDecision(
            eligible = false,
            failedSignature = signature,
            blockReason = reason
        )

        if (!plan.complete) return blocked(StrategyRecoveryBlockReason.PLAN_INCOMPLETE)
        if (plan.recoveryDepth >= MAX_RECOVERY_DEPTH) {
            return blocked(StrategyRecoveryBlockReason.DEPTH_EXHAUSTED)
        }

        val statuses = plan.steps.map { it.status }.toSet()
        if (PlanStepStatus.DENIED in statuses) {
            return blocked(StrategyRecoveryBlockReason.AUTHORITY_DENIED)
        }
        if (PlanStepStatus.REJECTED in statuses) {
            return blocked(StrategyRecoveryBlockReason.USER_REJECTED)
        }

        val firstNonExecuted = plan.steps
            .sortedBy { it.index }
            .firstOrNull { it.status != PlanStepStatus.EXECUTED }
        if (
            firstNonExecuted?.status == PlanStepStatus.MALFORMED ||
            firstNonExecuted?.status == PlanStepStatus.UNAVAILABLE
        ) {
            return blocked(StrategyRecoveryBlockReason.NON_EXECUTION_CAUSE)
        }
        if (firstNonExecuted?.status != PlanStepStatus.FAILED) {
            return blocked(StrategyRecoveryBlockReason.NO_RECOVERABLE_FAILURE)
        }

        val snapshot = evidence
            ?.takeIf { it.signature == signature }
            ?: return blocked(StrategyRecoveryBlockReason.INSUFFICIENT_EVIDENCE)
        val candidate = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(snapshot),
            allowedCapabilities = allowedCapabilities,
            limit = 1
        ).singleOrNull()
            ?: return blocked(StrategyRecoveryBlockReason.INSUFFICIENT_EVIDENCE)

        if (candidate.adaptationMode != StrategyAdaptationMode.RECOVERY_REQUIRED) {
            return blocked(StrategyRecoveryBlockReason.STRATEGY_NOT_RECOVERY_REQUIRED)
        }

        return StrategyRecoveryDecision(
            eligible = true,
            failedSignature = signature,
            evidence = candidate
        )
    }

    fun renderConstraint(decision: StrategyRecoveryDecision): String {
        require(decision.eligible)
        val evidence = requireNotNull(decision.evidence)
        return buildString {
            appendLine("<RECOVERY_CONSTRAINT>")
            appendLine("A prior governed plan for this same current goal reached a recoverable terminal failure.")
            appendLine(
                "Do not repeat the exact failed capability sequence: " +
                    decision.failedSignature.capabilities.joinToString(">") { it.value }
            )
            appendLine(
                "Historical adaptation=${evidence.adaptationMode.name} " +
                    "consecutive_failures=${evidence.consecutiveFailures}."
            )
            appendLine(
                "Choose a structurally different capability sequence only if current tool contracts support it."
            )
            appendLine(
                "This is planning guidance only. Never bypass authority, approval, exact tool binding, " +
                    "or current capability admission."
            )
            append("</RECOVERY_CONSTRAINT>")
        }
    }

    fun validateReplacement(
        failedPlan: SovereignPlan,
        replacementSteps: List<SovereignPlanStep>
    ) {
        require(replacementSteps.isNotEmpty())
        val replacementSignature = StrategySignature(
            replacementSteps.sortedBy { it.index }.map { it.capability }
        )
        require(replacementSignature != StrategySignature.from(failedPlan)) {
            "recovery plan repeated the failed capability sequence"
        }
    }
}
