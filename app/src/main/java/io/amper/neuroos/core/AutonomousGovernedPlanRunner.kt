package io.amper.neuroos.core

enum class AutonomousGovernedPlanRunStage {
    COMPLETED,
    TERMINAL_RECOVERY,
    EXECUTION_PAUSED,
    WAITING_APPROVAL,
    CONTEXT_REFRESHED,
    STEP_LIMIT
}

data class AutonomousGovernedPlanRunResult(
    val planId: PlanId,
    val stage: AutonomousGovernedPlanRunStage,
    val processedSteps: Int,
    val activePlanId: PlanId,
    val goalCheckpointStage: PersistentGoalExecutiveStage? = null
) {
    init {
        require(processedSteps in 0..TitanPlanProtocol.MAX_STEPS)
    }

    val authorityBearing: Boolean
        get() = false
}

/**
 * Phase301-305 autonomous governed plan runner.
 *
 * Phase301 loads only the exact persisted plan handed off by the persistent goal executive.
 * Phase302 advances through the existing PersistentSovereignPlanCoordinator so normal live
 * ToolDescriptor binding, AuthorityGate and side-effect classification remain authoritative.
 * Phase303 stops at PendingApproval and never calls approve()/approveBound().
 * Phase304 handles cognitive context drift only by creating the existing bounded refresh child and
 * rebinding the exact persistent-goal handoff, with zero tool execution during refresh.
 * Phase305 resolves terminal plans through the existing bounded goal-recovery policy and caps each
 * invocation to TitanPlanProtocol.MAX_STEPS.
 */
class AutonomousGovernedPlanRunner(
    private val planner: PersistentSovereignPlanCoordinator,
    private val plans: SovereignPlanStore,
    private val goals: PersistentGoalExecutiveCoordinator
) {
    fun runBounded(
        planId: PlanId,
        maxSteps: Int = TitanPlanProtocol.MAX_STEPS
    ): Result<AutonomousGovernedPlanRunResult> = runCatching {
        require(maxSteps in 1..TitanPlanProtocol.MAX_STEPS)
        var plan = requireNotNull(plans.load(planId)) {
            "autonomous plan handoff is unavailable from plan store"
        }
        require(
            goals.current()?.plannedPlanId == plan.id &&
                goals.current()?.stage == PersistentGoalExecutiveStage.PLANNED
        ) {
            "autonomous plan is not the exact active persistent-goal handoff"
        }

        var processed = 0
        while (processed < maxSteps) {
            if (plan.complete) {
                return@runCatching resolveTerminal(plan, processed)
            }

            when (val advance = planner.advance(plan).getOrThrow()) {
                is PlanAdvanceResult.StepProcessed -> {
                    processed += 1
                    plan = advance.plan
                    if (plan.complete) {
                        return@runCatching resolveTerminal(plan, processed)
                    }
                    if (advance.outcome.status != ActionStatus.EXECUTED) {
                        val paused = goals.pausePlannedExecution(
                            planId = plan.id,
                            failureCode = (
                                "AUTONOMOUS_STEP_" + advance.outcome.status.name
                                ).take(128)
                        ).getOrThrow()
                        return@runCatching AutonomousGovernedPlanRunResult(
                            planId = planId,
                            stage = AutonomousGovernedPlanRunStage.EXECUTION_PAUSED,
                            processedSteps = processed,
                            activePlanId = plan.id,
                            goalCheckpointStage = paused.stage
                        )
                    }
                }

                is PlanAdvanceResult.PendingApproval -> {
                    return@runCatching AutonomousGovernedPlanRunResult(
                        planId = planId,
                        stage = AutonomousGovernedPlanRunStage.WAITING_APPROVAL,
                        processedSteps = processed,
                        activePlanId = advance.plan.id,
                        goalCheckpointStage = goals.current()?.stage
                    )
                }

                is PlanAdvanceResult.ContextChanged -> {
                    val replacement = planner.refreshContext(advance.plan).getOrThrow()
                    goals.rebindPlannedHandoff(
                        previousPlanId = advance.plan.id,
                        replacementPlanId = replacement.id
                    ).getOrThrow()
                    return@runCatching AutonomousGovernedPlanRunResult(
                        planId = planId,
                        stage = AutonomousGovernedPlanRunStage.CONTEXT_REFRESHED,
                        processedSteps = processed,
                        activePlanId = replacement.id,
                        goalCheckpointStage = goals.current()?.stage
                    )
                }

                is PlanAdvanceResult.Complete -> {
                    plan = advance.plan
                    return@runCatching resolveTerminal(plan, processed)
                }
            }
        }

        AutonomousGovernedPlanRunResult(
            planId = planId,
            stage = AutonomousGovernedPlanRunStage.STEP_LIMIT,
            processedSteps = processed,
            activePlanId = plan.id,
            goalCheckpointStage = goals.current()?.stage
        )
    }

    private fun resolveTerminal(
        plan: SovereignPlan,
        processed: Int
    ): AutonomousGovernedPlanRunResult {
        val checkpoint = goals.resolveTerminalPlan(plan.id).getOrThrow()
        val completed = checkpoint.stage == PersistentGoalExecutiveStage.COMPLETED
        return AutonomousGovernedPlanRunResult(
            planId = plan.id,
            stage = if (completed) {
                AutonomousGovernedPlanRunStage.COMPLETED
            } else {
                AutonomousGovernedPlanRunStage.TERMINAL_RECOVERY
            },
            processedSteps = processed,
            activePlanId = checkpoint.plannedPlanId ?: plan.id,
            goalCheckpointStage = checkpoint.stage
        )
    }
}
