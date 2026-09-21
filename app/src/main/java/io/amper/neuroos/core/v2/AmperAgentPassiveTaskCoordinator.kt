package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ConversationId
import io.amper.neuroos.core.PersistentSovereignPlanCoordinator
import io.amper.neuroos.core.PlanAdvanceResult
import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignPlanStore

data class AmperAgentPassiveTaskCheckpoint(
    val taskId: String,
    val planId: PlanId,
    val backgroundMode: OmegaBackgroundMode,
    val taskState: AmperAgentTaskState,
    val completedSteps: Int,
    val totalSteps: Int,
    val updatedAtEpochMs: Long
) {
    init {
        require(taskId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(completedSteps in 0..totalSteps)
        require(totalSteps in 1..4)
        require(updatedAtEpochMs >= 0L)
        require(taskState != AmperAgentTaskState.ADMITTED) {
            "persisted passive task checkpoint must bind to an already-created plan"
        }
    }
}

data class AmperAgentPassiveTaskDiagnostics(
    val taskId: String,
    val planId: String,
    val backgroundMode: OmegaBackgroundMode,
    val taskState: AmperAgentTaskState,
    val completedSteps: Int,
    val totalSteps: Int,
    val checkpointRequired: Boolean
) {
    init {
        require(taskId.isNotBlank())
        require(planId.isNotBlank())
        require(completedSteps in 0..totalSteps)
        require(totalSteps in 1..4)
        require(checkpointRequired == (backgroundMode != OmegaBackgroundMode.UI_BOUND))
    }
}

data class AmperAgentPassiveTaskResult(
    val checkpoint: AmperAgentPassiveTaskCheckpoint,
    val diagnostics: AmperAgentPassiveTaskDiagnostics
)

/**
 * Neutral Phase654 names for the one persistent-plan task checkpoint/result contract.
 *
 * The Phase648 passive names remain source-compatible aliases so no second persistence model is
 * introduced while proactive tasks reuse the same checkpoint representation.
 */
typealias AmperAgentPlanTaskCheckpoint = AmperAgentPassiveTaskCheckpoint
typealias AmperAgentPlanTaskDiagnostics = AmperAgentPassiveTaskDiagnostics
typealias AmperAgentPlanTaskResult = AmperAgentPassiveTaskResult

interface AmperAgentPersistentPlanPort {
    fun create(
        conversationId: ConversationId,
        userGoal: String
    ): Result<SovereignPlan>

    fun createBound(
        conversationId: ConversationId,
        userGoal: String,
        planId: PlanId
    ): Result<SovereignPlan> =
        Result.failure(
            UnsupportedOperationException("deterministic persistent plan creation is unavailable")
        )

    fun load(planId: PlanId): SovereignPlan?

    fun advance(plan: SovereignPlan): Result<PlanAdvanceResult>
}

/**
 * Thin adapter only. All planning, ToolFabric execution, AuthorityGate checks, approvals,
 * receipts, reconciliation and durable plan persistence remain owned by the existing canonical
 * persistent sovereign-plan path.
 */
class PersistentSovereignAgentPlanPort(
    private val coordinator: PersistentSovereignPlanCoordinator,
    private val store: SovereignPlanStore
) : AmperAgentPersistentPlanPort {
    override fun create(
        conversationId: ConversationId,
        userGoal: String
    ): Result<SovereignPlan> =
        coordinator.create(conversationId, userGoal)

    override fun createBound(
        conversationId: ConversationId,
        userGoal: String,
        planId: PlanId
    ): Result<SovereignPlan> =
        coordinator.createBound(conversationId, userGoal, planId)

    override fun load(planId: PlanId): SovereignPlan? =
        store.load(planId)

    override fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> =
        coordinator.advance(plan)
}

/**
 * One canonical persistent-plan task engine shared by passive USER_REQUEST and proactive-trigger
 * coordinators.
 *
 * It contains no planner, ToolFabric, AuthorityGate, approval shortcut, Android scheduler, or
 * independent persistence. Every start/advance delegates to the same AmperAgentPersistentPlanPort
 * and every call advances at most one sovereign-plan step.
 */
private class AmperAgentPersistentPlanTaskEngine(
    private val plans: AmperAgentPersistentPlanPort,
    private val clock: () -> Long
) {
    fun start(
        admission: AmperAgentTaskAdmission,
        conversationId: ConversationId,
        expectedOrigin: AmperAgentTaskOrigin
    ): Result<AmperAgentPlanTaskResult> = runCatching {
        requireAdmission(admission, expectedOrigin)

        val plan = plans.create(
            conversationId = conversationId,
            userGoal = admission.request.objective
        ).getOrThrow()

        requireTaskPlanEnvelope(admission, plan)

        result(
            admission = admission,
            plan = plan,
            state = AmperAgentTaskState.READY
        )
    }

    fun startBound(
        admission: AmperAgentTaskAdmission,
        conversationId: ConversationId,
        planId: PlanId,
        expectedOrigin: AmperAgentTaskOrigin
    ): Result<AmperAgentPlanTaskResult> = runCatching {
        requireAdmission(admission, expectedOrigin)

        val plan = plans.createBound(
            conversationId = conversationId,
            userGoal = admission.request.objective,
            planId = planId
        ).getOrThrow()

        require(plan.id == planId) {
            "deterministic Agent Core plan identity drifted during creation"
        }
        requireTaskPlanEnvelope(admission, plan)

        result(
            admission = admission,
            plan = plan,
            state = resumableState(plan)
        )
    }

    fun advance(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPlanTaskCheckpoint,
        expectedOrigin: AmperAgentTaskOrigin
    ): Result<AmperAgentPlanTaskResult> = runCatching {
        requireAdmission(admission, expectedOrigin)
        require(checkpoint.taskId == admission.request.taskId) {
            "Agent Core checkpoint belongs to a different task"
        }
        require(checkpoint.backgroundMode == admission.backgroundMode) {
            "Agent Core checkpoint background mode drifted"
        }
        require(
            checkpoint.taskState in setOf(
                AmperAgentTaskState.READY,
                AmperAgentTaskState.CHECKPOINTED,
                AmperAgentTaskState.RUNNING
            )
        ) {
            "Agent Core task is not eligible for canonical plan advancement"
        }

        val plan = requireNotNull(plans.load(checkpoint.planId)) {
            "durable sovereign plan checkpoint is unavailable"
        }
        requireTaskPlanEnvelope(admission, plan)

        val advance = plans.advance(plan).getOrThrow()
        val (updatedPlan, state) = when (advance) {
            is PlanAdvanceResult.StepProcessed -> {
                val nextState = if (advance.plan.complete) {
                    terminalState(advance.plan)
                } else {
                    AmperAgentTaskState.READY
                }
                advance.plan to nextState
            }

            is PlanAdvanceResult.PendingApproval ->
                advance.plan to AmperAgentTaskState.WAITING_APPROVAL

            is PlanAdvanceResult.ContextChanged ->
                advance.plan to AmperAgentTaskState.CHECKPOINTED

            is PlanAdvanceResult.Complete ->
                advance.plan to terminalState(advance.plan)
        }

        result(
            admission = admission,
            plan = updatedPlan,
            state = state
        )
    }

    fun resumeAfterGovernedApproval(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPlanTaskCheckpoint,
        expectedOrigin: AmperAgentTaskOrigin
    ): Result<AmperAgentPlanTaskResult> = runCatching {
        requireAdmission(admission, expectedOrigin)
        require(checkpoint.taskId == admission.request.taskId)
        require(checkpoint.taskState == AmperAgentTaskState.WAITING_APPROVAL) {
            "task is not waiting for governed approval"
        }

        val plan = requireNotNull(plans.load(checkpoint.planId)) {
            "approved task plan checkpoint is unavailable"
        }
        requireTaskPlanEnvelope(admission, plan)
        val firstActive = plan.steps.firstOrNull {
            it.status == PlanStepStatus.PLANNED ||
                it.status == PlanStepStatus.REQUIRES_CONFIRMATION
        }

        require(firstActive?.status != PlanStepStatus.REQUIRES_CONFIRMATION) {
            "side-effect approval has not been resolved by the governed plan surface"
        }

        result(
            admission = admission,
            plan = plan,
            state = if (plan.complete) terminalState(plan) else AmperAgentTaskState.READY
        )
    }

    private fun requireAdmission(
        admission: AmperAgentTaskAdmission,
        expectedOrigin: AmperAgentTaskOrigin
    ) {
        val request = admission.request
        require(request.origin == expectedOrigin) {
            "Agent Core task origin does not match this coordinator"
        }
        require(admission.toolAuthorityRemainsExternal && admission.auditRequired)
        if (expectedOrigin == AmperAgentTaskOrigin.PROACTIVE_TRIGGER) {
            require(admission.backgroundMode == OmegaBackgroundMode.EVENT_WAKE) {
                "proactive task must retain canonical EVENT_WAKE admission"
            }
            require(request.trigger != null) {
                "proactive task lost explicit trigger provenance"
            }
        }
    }

    private fun requireTaskPlanEnvelope(
        admission: AmperAgentTaskAdmission,
        plan: SovereignPlan
    ) {
        require(plan.goal == admission.request.objective) {
            "persistent sovereign plan goal drifted from Agent Core task objective"
        }
        require(
            plan.steps.all { it.capability in admission.request.allowedCapabilities }
        ) {
            "persistent sovereign plan escaped Agent Core capability envelope"
        }
    }

    private fun terminalState(plan: SovereignPlan): AmperAgentTaskState {
        require(plan.complete)
        return if (plan.steps.all { it.status == PlanStepStatus.EXECUTED }) {
            AmperAgentTaskState.COMPLETED
        } else {
            AmperAgentTaskState.FAILED
        }
    }

    private fun resumableState(plan: SovereignPlan): AmperAgentTaskState {
        if (plan.complete) return terminalState(plan)
        val active = plan.steps.firstOrNull {
            it.status == PlanStepStatus.PLANNED ||
                it.status == PlanStepStatus.REQUIRES_CONFIRMATION
        } ?: error("non-terminal Agent Core plan has no active step")
        return if (active.status == PlanStepStatus.REQUIRES_CONFIRMATION) {
            AmperAgentTaskState.WAITING_APPROVAL
        } else {
            AmperAgentTaskState.READY
        }
    }

    private fun result(
        admission: AmperAgentTaskAdmission,
        plan: SovereignPlan,
        state: AmperAgentTaskState
    ): AmperAgentPlanTaskResult {
        val completed = plan.steps.count {
            it.status !in setOf(
                PlanStepStatus.PLANNED,
                PlanStepStatus.REQUIRES_CONFIRMATION
            )
        }
        val now = clock().coerceAtLeast(plan.createdAtEpochMs)
        val checkpoint = AmperAgentPlanTaskCheckpoint(
            taskId = admission.request.taskId,
            planId = plan.id,
            backgroundMode = admission.backgroundMode,
            taskState = state,
            completedSteps = completed,
            totalSteps = plan.steps.size,
            updatedAtEpochMs = now
        )
        return AmperAgentPlanTaskResult(
            checkpoint = checkpoint,
            diagnostics = AmperAgentPlanTaskDiagnostics(
                taskId = checkpoint.taskId,
                planId = checkpoint.planId.value,
                backgroundMode = checkpoint.backgroundMode,
                taskState = checkpoint.taskState,
                completedSteps = checkpoint.completedSteps,
                totalSteps = checkpoint.totalSteps,
                checkpointRequired = admission.checkpointRequired
            )
        )
    }
}

/**
 * M5 passive task binding for explicit USER_REQUEST work.
 *
 * This Phase648 surface remains intact but now delegates to the shared persistent-plan task engine.
 */
class AmperAgentPassiveTaskCoordinator(
    plans: AmperAgentPersistentPlanPort,
    clock: () -> Long = System::currentTimeMillis
) {
    private val engine = AmperAgentPersistentPlanTaskEngine(plans, clock)

    fun start(
        admission: AmperAgentTaskAdmission,
        conversationId: ConversationId
    ): Result<AmperAgentPassiveTaskResult> =
        engine.start(admission, conversationId, AmperAgentTaskOrigin.USER_REQUEST)

    fun advance(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPassiveTaskCheckpoint
    ): Result<AmperAgentPassiveTaskResult> =
        engine.advance(admission, checkpoint, AmperAgentTaskOrigin.USER_REQUEST)

    fun resumeAfterGovernedApproval(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPassiveTaskCheckpoint
    ): Result<AmperAgentPassiveTaskResult> =
        engine.resumeAfterGovernedApproval(
            admission,
            checkpoint,
            AmperAgentTaskOrigin.USER_REQUEST
        )
}

/**
 * Phase654 proactive-trigger binding.
 *
 * PROACTIVE_TRIGGER admissions reuse the exact same persistent sovereign-plan path and one-step
 * engine as passive tasks. Trigger provenance and EVENT_WAKE mode are mandatory. The coordinator
 * owns no monitor, Android wake source, planner, tool authority, or approval path; those remain
 * separate canonical boundaries.
 */
class AmperAgentProactiveTaskCoordinator(
    plans: AmperAgentPersistentPlanPort,
    clock: () -> Long = System::currentTimeMillis
) {
    private val engine = AmperAgentPersistentPlanTaskEngine(plans, clock)

    fun start(
        admission: AmperAgentTaskAdmission,
        conversationId: ConversationId
    ): Result<AmperAgentPlanTaskResult> =
        engine.start(admission, conversationId, AmperAgentTaskOrigin.PROACTIVE_TRIGGER)

    fun startBound(
        admission: AmperAgentTaskAdmission,
        conversationId: ConversationId,
        planId: PlanId
    ): Result<AmperAgentPlanTaskResult> =
        engine.startBound(
            admission = admission,
            conversationId = conversationId,
            planId = planId,
            expectedOrigin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER
        )

    fun advance(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPlanTaskCheckpoint
    ): Result<AmperAgentPlanTaskResult> =
        engine.advance(admission, checkpoint, AmperAgentTaskOrigin.PROACTIVE_TRIGGER)

    fun resumeAfterGovernedApproval(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPlanTaskCheckpoint
    ): Result<AmperAgentPlanTaskResult> =
        engine.resumeAfterGovernedApproval(
            admission,
            checkpoint,
            AmperAgentTaskOrigin.PROACTIVE_TRIGGER
        )
}
