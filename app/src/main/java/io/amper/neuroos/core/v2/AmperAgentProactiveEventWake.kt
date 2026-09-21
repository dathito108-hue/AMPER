package io.amper.neuroos.core.v2

import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignPlanCodec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

enum class AmperAgentEventWakeDisposition {
    READY_FOR_EXPLICIT_ADVANCE,
    WAITING_GOVERNED_APPROVAL,
    TERMINAL_NOOP
}

/**
 * Phase655 checkpoint envelope for proactive EVENT_WAKE tasks.
 *
 * It is deliberately separate from the USER_REQUEST continuation envelope: trigger provenance is
 * part of the identity and EVENT_WAKE is not an Android execution host yet.
 */
data class AmperAgentEventWakeEnvelope(
    val version: Int = 1,
    val taskId: String,
    val planId: PlanId,
    val trigger: AmperAgentTrigger,
    val taskState: AmperAgentTaskState,
    val completedSteps: Int,
    val totalSteps: Int,
    val planStateSha256: String,
    val waitingApprovalStepIndex: Int? = null,
    val checkpointedAtEpochMs: Long
) {
    init {
        require(version == 1) { "unsupported Agent Core EVENT_WAKE envelope version" }
        require(taskId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(
            taskState == AmperAgentTaskState.CHECKPOINTED ||
                taskState == AmperAgentTaskState.WAITING_APPROVAL ||
                taskState == AmperAgentTaskState.COMPLETED ||
                taskState == AmperAgentTaskState.FAILED
        ) {
            "EVENT_WAKE envelope must represent a checkpointed, approval-blocked, or terminal plan"
        }
        require(completedSteps in 0..totalSteps)
        require(totalSteps in 1..4)
        require(planStateSha256.matches(Regex("[0-9a-f]{64}"))) {
            "EVENT_WAKE plan state must use lowercase SHA-256"
        }
        require(checkpointedAtEpochMs >= 0L)
        require(
            (taskState == AmperAgentTaskState.WAITING_APPROVAL) ==
                (waitingApprovalStepIndex != null)
        ) {
            "WAITING_APPROVAL EVENT_WAKE requires exact pending step index"
        }
        waitingApprovalStepIndex?.let { require(it in 1..totalSteps) }
    }

    val disposition: AmperAgentEventWakeDisposition
        get() = when (taskState) {
            AmperAgentTaskState.CHECKPOINTED ->
                AmperAgentEventWakeDisposition.READY_FOR_EXPLICIT_ADVANCE
            AmperAgentTaskState.WAITING_APPROVAL ->
                AmperAgentEventWakeDisposition.WAITING_GOVERNED_APPROVAL
            AmperAgentTaskState.COMPLETED,
            AmperAgentTaskState.FAILED ->
                AmperAgentEventWakeDisposition.TERMINAL_NOOP
            else -> error("EVENT_WAKE envelope contains unsupported task state")
        }
}

object AmperAgentEventWakeEnvelopeCodec {
    private const val MAGIC = "AMPER_AGENT_EVENT_WAKE_V1"

    fun encode(value: AmperAgentEventWakeEnvelope): String = buildString {
        appendLine(MAGIC)
        appendLine("task_id=" + value.taskId)
        appendLine("plan_id=" + value.planId.value)
        appendLine("trigger_id=" + value.trigger.triggerId)
        appendLine("trigger_source_b64=" + encodeText(value.trigger.source))
        appendLine("trigger_observed_at=" + value.trigger.observedAtEpochMs)
        appendLine("trigger_payload_sha256=" + value.trigger.payloadDigest)
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

    fun decode(text: String): Result<AmperAgentEventWakeEnvelope> = runCatching {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == MAGIC) {
            "unsupported Agent Core EVENT_WAKE envelope"
        }
        val fields = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "malformed Agent Core EVENT_WAKE field" }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(fields.put(key, value) == null) {
                "duplicate Agent Core EVENT_WAKE field: $key"
            }
        }
        val expected = setOf(
            "task_id",
            "plan_id",
            "trigger_id",
            "trigger_source_b64",
            "trigger_observed_at",
            "trigger_payload_sha256",
            "task_state",
            "completed_steps",
            "total_steps",
            "plan_state_sha256",
            "waiting_approval_step",
            "checkpointed_at"
        )
        require(fields.keys == expected) {
            "Agent Core EVENT_WAKE envelope fields are incomplete or unknown"
        }

        AmperAgentEventWakeEnvelope(
            taskId = fields.getValue("task_id"),
            planId = PlanId(fields.getValue("plan_id")),
            trigger = AmperAgentTrigger(
                triggerId = fields.getValue("trigger_id"),
                source = decodeText(fields.getValue("trigger_source_b64")),
                observedAtEpochMs = fields.getValue("trigger_observed_at").toLong(),
                payloadDigest = fields.getValue("trigger_payload_sha256")
            ),
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

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String =
        String(
            Base64.getUrlDecoder().decode(value),
            StandardCharsets.UTF_8
        )
}

data class AmperAgentEventWakeHandoff(
    val taskId: String,
    val planId: String,
    val triggerId: String,
    val triggerPayloadSha256: String,
    val disposition: AmperAgentEventWakeDisposition,
    val dedupeKey: String,
    val encodedEnvelope: String,
    val envelopeSha256: String
) {
    init {
        require(taskId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(planId.isNotBlank() && planId.length <= 160)
        require(triggerId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(triggerPayloadSha256.matches(Regex("[0-9a-f]{64}")))
        require(dedupeKey.matches(Regex("[a-z0-9][a-z0-9._:-]{0,511}")))
        require(encodedEnvelope.isNotBlank())
        require(encodedEnvelope.toByteArray(StandardCharsets.UTF_8).size <= MAX_ENVELOPE_BYTES)
        require(envelopeSha256.matches(Regex("[0-9a-f]{64}")))
        require(envelopeSha256 == eventWakeSha256(encodedEnvelope)) {
            "Agent Core EVENT_WAKE handoff envelope digest mismatch"
        }
    }

    val runnable: Boolean
        get() = disposition == AmperAgentEventWakeDisposition.READY_FOR_EXPLICIT_ADVANCE

    companion object {
        const val MAX_ENVELOPE_BYTES: Int = 64 * 1024
    }
}

object AmperAgentEventWakeHandoffPolicy {
    fun create(envelope: AmperAgentEventWakeEnvelope): AmperAgentEventWakeHandoff {
        val encoded = AmperAgentEventWakeEnvelopeCodec.encode(envelope)
        return AmperAgentEventWakeHandoff(
            taskId = envelope.taskId,
            planId = envelope.planId.value,
            triggerId = envelope.trigger.triggerId,
            triggerPayloadSha256 = envelope.trigger.payloadDigest,
            disposition = envelope.disposition,
            dedupeKey = buildDedupeKey(envelope),
            encodedEnvelope = encoded,
            envelopeSha256 = eventWakeSha256(encoded)
        )
    }

    fun verifyAndDecode(
        handoff: AmperAgentEventWakeHandoff
    ): Result<AmperAgentEventWakeEnvelope> = runCatching {
        require(eventWakeSha256(handoff.encodedEnvelope) == handoff.envelopeSha256) {
            "Agent Core EVENT_WAKE handoff changed before restore"
        }
        val envelope = AmperAgentEventWakeEnvelopeCodec
            .decode(handoff.encodedEnvelope)
            .getOrThrow()
        val canonical = create(envelope)
        require(canonical.taskId == handoff.taskId)
        require(canonical.planId == handoff.planId)
        require(canonical.triggerId == handoff.triggerId)
        require(canonical.triggerPayloadSha256 == handoff.triggerPayloadSha256)
        require(canonical.disposition == handoff.disposition)
        require(canonical.dedupeKey == handoff.dedupeKey)
        envelope
    }

    private fun buildDedupeKey(envelope: AmperAgentEventWakeEnvelope): String =
        "agent-event-wake:" +
            envelope.taskId + ":" +
            eventWakeSha256(envelope.planId.value) + ":" +
            envelope.trigger.triggerId + ":" +
            envelope.trigger.payloadDigest
}

data class AmperAgentEventWakeRestore(
    val checkpoint: AmperAgentPlanTaskCheckpoint,
    val trigger: AmperAgentTrigger,
    val planStateSha256: String,
    val disposition: AmperAgentEventWakeDisposition
) {
    val runnable: Boolean
        get() = disposition == AmperAgentEventWakeDisposition.READY_FOR_EXPLICIT_ADVANCE
}

/**
 * Phase655 pure proactive EVENT_WAKE checkpoint/restore boundary.
 *
 * It owns no Android scheduler, monitor, planner, ToolFabric, AuthorityGate, approval path, or
 * execution loop. It only binds trigger provenance to the exact durable sovereign-plan state.
 */
class AmperAgentProactiveEventWakeCoordinator(
    private val plans: AmperAgentPersistentPlanPort,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun checkpoint(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPlanTaskCheckpoint
    ): Result<AmperAgentEventWakeEnvelope> = runCatching {
        val trigger = requireProactiveAdmission(admission)
        require(checkpoint.taskId == admission.request.taskId)
        require(checkpoint.backgroundMode == OmegaBackgroundMode.EVENT_WAKE)

        val plan = requireNotNull(plans.load(checkpoint.planId)) {
            "durable sovereign plan is unavailable for EVENT_WAKE checkpoint"
        }
        verifyTaskPlanBinding(admission, plan)

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
            "EVENT_WAKE checkpoint state drifted from durable sovereign plan"
        }

        AmperAgentEventWakeEnvelope(
            taskId = admission.request.taskId,
            planId = plan.id,
            trigger = trigger,
            taskState = derived.state,
            completedSteps = derived.completedSteps,
            totalSteps = plan.steps.size,
            planStateSha256 = planDigest(plan),
            waitingApprovalStepIndex = derived.waitingApprovalStepIndex,
            checkpointedAtEpochMs = clock().coerceAtLeast(plan.createdAtEpochMs)
        )
    }

    fun handoff(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPlanTaskCheckpoint
    ): Result<AmperAgentEventWakeHandoff> =
        checkpoint(admission, checkpoint).map(AmperAgentEventWakeHandoffPolicy::create)

    fun restore(
        admission: AmperAgentTaskAdmission,
        envelope: AmperAgentEventWakeEnvelope
    ): Result<AmperAgentEventWakeRestore> = runCatching {
        val trigger = requireProactiveAdmission(admission)
        require(envelope.taskId == admission.request.taskId) {
            "EVENT_WAKE envelope belongs to a different Agent Core task"
        }
        require(envelope.trigger == trigger) {
            "EVENT_WAKE trigger provenance drifted"
        }

        val plan = requireNotNull(plans.load(envelope.planId)) {
            "durable sovereign plan is unavailable during EVENT_WAKE restore"
        }
        verifyTaskPlanBinding(admission, plan)

        val digest = planDigest(plan)
        require(digest == envelope.planStateSha256) {
            "durable sovereign plan changed after EVENT_WAKE checkpoint"
        }

        val derived = deriveState(plan)
        require(derived.state == envelope.taskState) {
            "EVENT_WAKE task state no longer matches durable sovereign plan"
        }
        require(derived.completedSteps == envelope.completedSteps) {
            "EVENT_WAKE completed-step count drifted"
        }
        require(plan.steps.size == envelope.totalSteps) {
            "EVENT_WAKE total-step count drifted"
        }
        require(derived.waitingApprovalStepIndex == envelope.waitingApprovalStepIndex) {
            "EVENT_WAKE approval checkpoint drifted"
        }

        AmperAgentEventWakeRestore(
            checkpoint = AmperAgentPlanTaskCheckpoint(
                taskId = envelope.taskId,
                planId = envelope.planId,
                backgroundMode = OmegaBackgroundMode.EVENT_WAKE,
                taskState = envelope.taskState,
                completedSteps = envelope.completedSteps,
                totalSteps = envelope.totalSteps,
                updatedAtEpochMs = clock().coerceAtLeast(envelope.checkpointedAtEpochMs)
            ),
            trigger = trigger,
            planStateSha256 = digest,
            disposition = envelope.disposition
        )
    }

    private fun requireProactiveAdmission(
        admission: AmperAgentTaskAdmission
    ): AmperAgentTrigger {
        require(admission.request.origin == AmperAgentTaskOrigin.PROACTIVE_TRIGGER) {
            "EVENT_WAKE checkpoint only accepts PROACTIVE_TRIGGER tasks"
        }
        require(admission.backgroundMode == OmegaBackgroundMode.EVENT_WAKE)
        require(admission.checkpointRequired)
        require(admission.toolAuthorityRemainsExternal && admission.auditRequired)
        return requireNotNull(admission.request.trigger) {
            "EVENT_WAKE admission lost trigger provenance"
        }
    }

    private fun verifyTaskPlanBinding(
        admission: AmperAgentTaskAdmission,
        plan: SovereignPlan
    ) {
        require(plan.goal == admission.request.objective) {
            "durable sovereign plan goal drifted from proactive Agent Core objective"
        }
        require(
            plan.steps.all { it.capability in admission.request.allowedCapabilities }
        ) {
            "durable sovereign plan escaped proactive Agent Core capability envelope"
        }
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
        } ?: error("non-terminal proactive sovereign plan has no active step")

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

    private fun planDigest(plan: SovereignPlan): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                SovereignPlanCodec.encode(plan)
                    .toByteArray(StandardCharsets.UTF_8)
            )
            .joinToString("") { "%02x".format(it) }
}

private fun eventWakeSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
