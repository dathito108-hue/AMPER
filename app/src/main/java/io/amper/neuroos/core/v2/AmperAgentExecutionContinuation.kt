package io.amper.neuroos.core.v2

import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignPlanCodec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class AmperAgentContinuationEnvelope(
    val version: Int = 1,
    val taskId: String,
    val planId: PlanId,
    val backgroundMode: OmegaBackgroundMode,
    val taskState: AmperAgentTaskState,
    val completedSteps: Int,
    val totalSteps: Int,
    val planStateSha256: String,
    val waitingApprovalStepIndex: Int? = null,
    val checkpointedAtEpochMs: Long
) {
    init {
        require(version == 1) { "unsupported Agent Core continuation envelope version" }
        require(taskId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(
            backgroundMode == OmegaBackgroundMode.FOREGROUND_CONTINUATION ||
                backgroundMode == OmegaBackgroundMode.PERSISTED_JOB
        ) {
            "Phase649 continuation envelope only supports foreground or persisted user work"
        }
        require(taskState != AmperAgentTaskState.ADMITTED) {
            "continuation envelope requires a bound persistent plan"
        }
        require(completedSteps in 0..totalSteps)
        require(totalSteps in 1..4)
        require(planStateSha256.matches(Regex("[0-9a-f]{64}"))) {
            "continuation plan state must use lowercase SHA-256"
        }
        require(checkpointedAtEpochMs >= 0L)
        require(
            (taskState == AmperAgentTaskState.WAITING_APPROVAL) ==
                (waitingApprovalStepIndex != null)
        ) {
            "WAITING_APPROVAL continuation requires exact pending step index"
        }
        waitingApprovalStepIndex?.let {
            require(it in 1..totalSteps)
        }
    }
}

object AmperAgentContinuationEnvelopeCodec {
    private const val MAGIC = "AMPER_AGENT_CONTINUATION_V1"

    fun encode(value: AmperAgentContinuationEnvelope): String = buildString {
        appendLine(MAGIC)
        appendLine("task_id=" + value.taskId)
        appendLine("plan_id=" + value.planId.value)
        appendLine("background_mode=" + value.backgroundMode.name)
        appendLine("task_state=" + value.taskState.name)
        appendLine("completed_steps=" + value.completedSteps)
        appendLine("total_steps=" + value.totalSteps)
        appendLine("plan_state_sha256=" + value.planStateSha256)
        appendLine(
            "waiting_approval_step=" +
                (value.waitingApprovalStepIndex?.toString() ?: "~")
        )
        appendLine("checkpointed_at=" + value.checkpointedAtEpochMs)
    }.trimEnd()

    fun decode(text: String): Result<AmperAgentContinuationEnvelope> = runCatching {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == MAGIC) {
            "unsupported Agent Core continuation envelope"
        }
        val fields = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "malformed Agent Core continuation field" }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(fields.put(key, value) == null) {
                "duplicate Agent Core continuation field: $key"
            }
        }
        val expected = setOf(
            "task_id",
            "plan_id",
            "background_mode",
            "task_state",
            "completed_steps",
            "total_steps",
            "plan_state_sha256",
            "waiting_approval_step",
            "checkpointed_at"
        )
        require(fields.keys == expected) {
            "Agent Core continuation envelope fields are incomplete or unknown"
        }

        AmperAgentContinuationEnvelope(
            taskId = fields.getValue("task_id"),
            planId = PlanId(fields.getValue("plan_id")),
            backgroundMode = OmegaBackgroundMode.valueOf(fields.getValue("background_mode")),
            taskState = AmperAgentTaskState.valueOf(fields.getValue("task_state")),
            completedSteps = fields.getValue("completed_steps").toInt(),
            totalSteps = fields.getValue("total_steps").toInt(),
            planStateSha256 = fields.getValue("plan_state_sha256"),
            waitingApprovalStepIndex = fields.getValue("waiting_approval_step")
                .takeUnless { it == "~" }
                ?.toInt(),
            checkpointedAtEpochMs = fields.getValue("checkpointed_at").toLong()
        )
    }
}

data class AmperAgentContinuationRestore(
    val checkpoint: AmperAgentPassiveTaskCheckpoint,
    val planStateSha256: String,
    val replayRequired: Boolean = false
) {
    init {
        require(!replayRequired) {
            "Agent Core continuation restore must never require implicit replay"
        }
    }
}

/**
 * Phase649 continuation binding over the durable sovereign-plan state from Phase648.
 *
 * It never advances a plan, invokes ToolFabric, approves a side effect, or mutates persistence.
 * Checkpoint creation hashes the exact persisted plan representation. Restore succeeds only if the
 * durable plan still matches that exact snapshot, preventing stale process state from replaying work.
 */
class AmperAgentExecutionContinuationCoordinator(
    private val plans: AmperAgentPersistentPlanPort,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun checkpoint(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPassiveTaskCheckpoint
    ): Result<AmperAgentContinuationEnvelope> = runCatching {
        require(admission.request.origin == AmperAgentTaskOrigin.USER_REQUEST)
        require(admission.checkpointRequired) {
            "UI-bound task does not require execution continuation checkpoint"
        }
        require(
            admission.backgroundMode == OmegaBackgroundMode.FOREGROUND_CONTINUATION ||
                admission.backgroundMode == OmegaBackgroundMode.PERSISTED_JOB
        ) {
            "Phase649 only checkpoints user continuation modes"
        }
        require(checkpoint.taskId == admission.request.taskId)
        require(checkpoint.backgroundMode == admission.backgroundMode)

        val plan = requireNotNull(plans.load(checkpoint.planId)) {
            "durable sovereign plan is unavailable for continuation checkpoint"
        }
        verifyTaskPlanBinding(admission, checkpoint, plan)

        val derived = deriveState(plan)
        require(
            checkpoint.taskState == derived.state ||
                (
                    checkpoint.taskState == AmperAgentTaskState.READY &&
                        derived.state == AmperAgentTaskState.CHECKPOINTED
                    ) ||
                (
                    checkpoint.taskState == AmperAgentTaskState.RUNNING &&
                        derived.state == AmperAgentTaskState.CHECKPOINTED
                    )
        ) {
            "Agent Core checkpoint state drifted from durable sovereign plan"
        }

        AmperAgentContinuationEnvelope(
            taskId = admission.request.taskId,
            planId = plan.id,
            backgroundMode = admission.backgroundMode,
            taskState = derived.state,
            completedSteps = derived.completedSteps,
            totalSteps = plan.steps.size,
            planStateSha256 = planDigest(plan),
            waitingApprovalStepIndex = derived.waitingApprovalStepIndex,
            checkpointedAtEpochMs = clock().coerceAtLeast(plan.createdAtEpochMs)
        )
    }

    fun restore(
        admission: AmperAgentTaskAdmission,
        envelope: AmperAgentContinuationEnvelope
    ): Result<AmperAgentContinuationRestore> = runCatching {
        require(admission.request.origin == AmperAgentTaskOrigin.USER_REQUEST)
        require(admission.checkpointRequired)
        require(envelope.taskId == admission.request.taskId) {
            "continuation envelope belongs to a different Agent Core task"
        }
        require(envelope.backgroundMode == admission.backgroundMode) {
            "continuation background mode drifted"
        }

        val plan = requireNotNull(plans.load(envelope.planId)) {
            "durable sovereign plan is unavailable during continuation restore"
        }
        val digest = planDigest(plan)
        require(digest == envelope.planStateSha256) {
            "durable sovereign plan changed after continuation checkpoint"
        }

        val derived = deriveState(plan)
        require(derived.state == envelope.taskState) {
            "continuation task state no longer matches durable sovereign plan"
        }
        require(derived.completedSteps == envelope.completedSteps) {
            "continuation completed-step count drifted"
        }
        require(plan.steps.size == envelope.totalSteps) {
            "continuation total-step count drifted"
        }
        require(derived.waitingApprovalStepIndex == envelope.waitingApprovalStepIndex) {
            "continuation approval checkpoint drifted"
        }

        AmperAgentContinuationRestore(
            checkpoint = AmperAgentPassiveTaskCheckpoint(
                taskId = envelope.taskId,
                planId = envelope.planId,
                backgroundMode = envelope.backgroundMode,
                taskState = envelope.taskState,
                completedSteps = envelope.completedSteps,
                totalSteps = envelope.totalSteps,
                updatedAtEpochMs = clock().coerceAtLeast(envelope.checkpointedAtEpochMs)
            ),
            planStateSha256 = digest
        )
    }

    private data class DerivedPlanState(
        val state: AmperAgentTaskState,
        val completedSteps: Int,
        val waitingApprovalStepIndex: Int?
    )

    private fun deriveState(plan: SovereignPlan): DerivedPlanState {
        val completed = plan.steps.count {
            it.status != PlanStepStatus.PLANNED &&
                it.status != PlanStepStatus.REQUIRES_CONFIRMATION
        }
        if (plan.complete) {
            return DerivedPlanState(
                state = if (plan.steps.all { it.status == PlanStepStatus.EXECUTED }) {
                    AmperAgentTaskState.COMPLETED
                } else {
                    AmperAgentTaskState.FAILED
                },
                completedSteps = completed,
                waitingApprovalStepIndex = null
            )
        }

        val active = plan.steps.firstOrNull {
            it.status == PlanStepStatus.PLANNED ||
                it.status == PlanStepStatus.REQUIRES_CONFIRMATION
        } ?: error("non-terminal sovereign plan has no active step")

        return if (active.status == PlanStepStatus.REQUIRES_CONFIRMATION) {
            DerivedPlanState(
                state = AmperAgentTaskState.WAITING_APPROVAL,
                completedSteps = completed,
                waitingApprovalStepIndex = active.index
            )
        } else {
            DerivedPlanState(
                state = AmperAgentTaskState.CHECKPOINTED,
                completedSteps = completed,
                waitingApprovalStepIndex = null
            )
        }
    }

    private fun verifyTaskPlanBinding(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPassiveTaskCheckpoint,
        plan: SovereignPlan
    ) {
        require(plan.id == checkpoint.planId)
        require(plan.goal == admission.request.objective) {
            "durable sovereign plan goal drifted from Agent Core objective"
        }
        require(plan.steps.size == checkpoint.totalSteps) {
            "Agent Core checkpoint total-step count drifted"
        }
        require(
            plan.steps.all { it.capability in admission.request.allowedCapabilities }
        ) {
            "durable sovereign plan escaped Agent Core capability envelope"
        }
    }

    private fun planDigest(plan: SovereignPlan): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                SovereignPlanCodec.encode(plan)
                    .toByteArray(StandardCharsets.UTF_8)
            )
            .joinToString("") { "%02x".format(it) }
}
