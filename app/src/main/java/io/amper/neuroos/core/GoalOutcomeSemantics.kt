package io.amper.neuroos.core

/**
 * Canonical semantics for mapping terminal plan statuses to governed goal outcomes.
 *
 * Higher layers may impose additional stage, verification, or binding invariants, but they must not
 * redefine the structural meaning of a governed goal outcome.
 */
object GoalOutcomeSemantics {
    private val EXECUTION_FAILURE_STATUSES = setOf(
        PlanStepStatus.FAILED,
        PlanStepStatus.MALFORMED,
        PlanStepStatus.UNAVAILABLE
    )
    private val AUTHORITY_STATUSES = setOf(
        PlanStepStatus.DENIED,
        PlanStepStatus.REJECTED
    )

    fun validate(plan: SovereignPlan, outcome: GoalOutcomeEvidenceKind) {
        require(plan.complete) { "goal outcome requires a terminal plan" }
        require(plan.steps.isNotEmpty()) { "goal outcome requires at least one plan step" }
        val statuses = plan.steps.map { it.status }

        when (outcome) {
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED ->
                require(statuses.all { it == PlanStepStatus.EXECUTED }) {
                    "$outcome requires an all-executed terminal plan"
                }

            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED ->
                require(statuses.all { it in EXECUTION_FAILURE_STATUSES }) {
                    "execution exhaustion requires only failed/malformed/unavailable terminal steps"
                }

            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED -> {
                require(statuses.none { it == PlanStepStatus.EXECUTED }) {
                    "authority-blocked outcome cannot include executed steps"
                }
                require(statuses.any { it in AUTHORITY_STATUSES }) {
                    "authority-blocked outcome requires denied or rejected evidence"
                }
            }

            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED -> {
                require(statuses.any { it == PlanStepStatus.EXECUTED }) {
                    "partial execution requires at least one executed step"
                }
                require(statuses.any { it != PlanStepStatus.EXECUTED }) {
                    "partial execution requires at least one non-executed step"
                }
            }
        }
    }

    fun classifyPreVerification(plan: SovereignPlan): GoalOutcomeEvidenceKind? {
        require(plan.complete) { "pre-verification classification requires a terminal plan" }
        require(plan.steps.isNotEmpty())
        val statuses = plan.steps.map { it.status }

        return when {
            statuses.all { it == PlanStepStatus.EXECUTED } -> null
            statuses.any { it == PlanStepStatus.EXECUTED } ->
                GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED
            statuses.any { it in AUTHORITY_STATUSES } ->
                GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED
            statuses.all { it in EXECUTION_FAILURE_STATUSES } ->
                GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED
            else -> null
        }
    }
}
