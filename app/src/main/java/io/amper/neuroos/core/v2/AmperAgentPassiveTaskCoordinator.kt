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

interface AmperAgentPersistentPlanPort {
    fun create(
        conversationId: ConversationId,
        userGoal: String
    ): Result<SovereignPlan>

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

    override fun load(planId: PlanId): SovereignPlan? =
        store.load(planId)

    override fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> =
        coordinator.advance(plan)
}

/**
 * M5 passive task binding for explicit USER_REQUEST work.
 *
 * The coordinator does not execute tools itself and does not expose approve/reject shortcuts.
 * It advances at most one canonical plan step per call. Side effects that require confirmation stop
 * at WAITING_APPROVAL and must be resolved by the existing governed approval surface.
 *
 * The durable plan id is the checkpoint anchor. Non-UI-bound Agent Core tasks therefore reuse the
 * already-persistent plan store rather than creating a second task persistence system.
 */
class AmperAgentPassiveTaskCoordinator(
    private val plans: AmperAgentPersistentPlanPort,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun start(
        admission: AmperAgentTaskAdmission,
        conversationId: ConversationId
    ): Result<AmperAgentPassiveTaskResult> = runCatching {
        val request = admission.request
        require(request.origin == AmperAgentTaskOrigin.USER_REQUEST) {
            "passive task coordinator only accepts USER_REQUEST tasks"
        }
        require(admission.toolAuthorityRemainsExternal && admission.auditRequired)

        val plan = plans.create(
            conversationId = conversationId,
            userGoal = request.objective
        ).getOrThrow()

        requireTaskPlanEnvelope(admission, plan)

        result(
            admission = admission,
            plan = plan,
            state = AmperAgentTaskState.READY
        )
    }

    fun advance(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPassiveTaskCheckpoint
    ): Result<AmperAgentPassiveTaskResult> = runCatching {
        val request = admission.request
        require(request.origin == AmperAgentTaskOrigin.USER_REQUEST)
        require(checkpoint.taskId == request.taskId) {
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
            "passive task is not eligible for canonical plan advancement"
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
        checkpoint: AmperAgentPassiveTaskCheckpoint
    ): Result<AmperAgentPassiveTaskResult> = runCatching {
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

    private fun result(
        admission: AmperAgentTaskAdmission,
        plan: SovereignPlan,
        state: AmperAgentTaskState
    ): AmperAgentPassiveTaskResult {
        val completed = plan.steps.count {
            it.status !in setOf(
                PlanStepStatus.PLANNED,
                PlanStepStatus.REQUIRES_CONFIRMATION
            )
        }
        val now = clock().coerceAtLeast(plan.createdAtEpochMs)
        val checkpoint = AmperAgentPassiveTaskCheckpoint(
            taskId = admission.request.taskId,
            planId = plan.id,
            backgroundMode = admission.backgroundMode,
            taskState = state,
            completedSteps = completed,
            totalSteps = plan.steps.size,
            updatedAtEpochMs = now
        )
        return AmperAgentPassiveTaskResult(
            checkpoint = checkpoint,
            diagnostics = AmperAgentPassiveTaskDiagnostics(
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
