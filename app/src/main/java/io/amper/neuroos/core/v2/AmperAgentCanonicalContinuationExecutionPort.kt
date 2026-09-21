package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ConversationId
import java.util.concurrent.ConcurrentHashMap

class AmperAgentTaskAdmissionRegistry {
    private val entries = ConcurrentHashMap<String, AmperAgentTaskAdmission>()

    fun register(admission: AmperAgentTaskAdmission): AmperAgentTaskAdmission {
        require(admission.request.origin == AmperAgentTaskOrigin.USER_REQUEST) {
            "Phase652 warm admission registry accepts USER_REQUEST tasks only"
        }
        val existing = entries.putIfAbsent(admission.request.taskId, admission)
        require(existing == null || existing == admission) {
            "Agent Core task id is already bound to a different admission"
        }
        return existing ?: admission
    }

    fun get(taskId: String): AmperAgentTaskAdmission? = entries[taskId]

    fun remove(taskId: String): AmperAgentTaskAdmission? = entries.remove(taskId)

    fun size(): Int = entries.size
}

data class AmperAgentUserTaskStart(
    val admission: AmperAgentTaskAdmission,
    val result: AmperAgentPassiveTaskResult,
    val continuationHandoff: AmperAgentAndroidContinuationHandoff?
)

/**
 * Warm-process USER_REQUEST entrypoint.
 *
 * It creates the canonical Phase647 admission, starts the Phase648 persistent plan, registers only
 * process-local admission context, and emits a Phase650 handoff when Android continuation is needed.
 * No second planner, database, or tool authority is introduced.
 */
class AmperAgentUserTaskRuntime(
    private val admissions: AmperAgentTaskAdmissionRegistry,
    private val passive: AmperAgentPassiveTaskCoordinator,
    private val continuation: AmperAgentExecutionContinuationCoordinator
) {
    fun start(
        request: AmperAgentTaskRequest,
        conversationId: ConversationId
    ): Result<AmperAgentUserTaskStart> = runCatching {
        val admission = AmperAgentTaskAdmissionPolicy.admit(request)
        require(request.origin == AmperAgentTaskOrigin.USER_REQUEST) {
            "warm user-task runtime accepts USER_REQUEST tasks only"
        }

        admissions.register(admission)
        try {
            val started = passive.start(admission, conversationId).getOrThrow()
            val handoff = if (admission.checkpointRequired) {
                continuation
                    .checkpoint(admission, started.checkpoint)
                    .map(AmperAgentAndroidContinuationHandoffPolicy::create)
                    .getOrThrow()
            } else {
                null
            }
            AmperAgentUserTaskStart(
                admission = admission,
                result = started,
                continuationHandoff = handoff
            )
        } catch (error: Throwable) {
            admissions.remove(request.taskId)
            throw error
        }
    }
}

/**
 * Canonical warm-process implementation of the Phase651 Android execution port.
 *
 * Each verified host call restores the exact Phase649 checkpoint and calls Phase648 advance exactly
 * once. If work remains, a fresh verified continuation handoff is returned to the Android host.
 */
class AmperAgentCanonicalContinuationExecutionPort(
    private val admissions: AmperAgentTaskAdmissionRegistry,
    private val plans: AmperAgentPersistentPlanPort,
    private val passive: AmperAgentPassiveTaskCoordinator,
    private val continuation: AmperAgentExecutionContinuationCoordinator
) : AmperAgentAndroidContinuationExecutionPort {
    private fun restoreNarrowAdmission(
        envelope: AmperAgentContinuationEnvelope
    ): AmperAgentTaskAdmission {
        val plan = requireNotNull(plans.load(envelope.planId)) {
            "durable sovereign plan is unavailable for admission reconstruction"
        }
        val request = AmperAgentTaskRequest(
            taskId = envelope.taskId,
            origin = AmperAgentTaskOrigin.USER_REQUEST,
            objective = plan.goal,
            allowedCapabilities = plan.steps.mapTo(linkedSetOf()) { it.capability },
            expectedRuntimeMs = 0L,
            mustSurviveUiExit = true,
            canBeDeferred = envelope.backgroundMode == OmegaBackgroundMode.PERSISTED_JOB,
            createdAtEpochMs = plan.createdAtEpochMs
        )
        val admission = AmperAgentTaskAdmissionPolicy.admit(request)
        require(admission.backgroundMode == envelope.backgroundMode) {
            "reconstructed Agent Core admission cannot reproduce continuation mode"
        }
        return admission
    }

    override fun advanceOnceVerified(
        handoff: AmperAgentAndroidContinuationHandoff,
        envelope: AmperAgentContinuationEnvelope
    ): Result<AmperAgentAndroidHostExecutionResult> = runCatching {
        require(handoff.taskId == envelope.taskId)
        require(handoff.planId == envelope.planId.value)

        val admission = admissions.get(envelope.taskId)
            ?: restoreNarrowAdmission(envelope).also {
                admissions.register(it)
            }

        require(admission.backgroundMode == envelope.backgroundMode) {
            "Agent Core background mode drifted before Android execution"
        }

        val restored = continuation
            .restore(admission, envelope)
            .getOrThrow()
        require(restored.checkpoint.taskState == AmperAgentTaskState.CHECKPOINTED) {
            "verified Android ready handoff did not restore to CHECKPOINTED"
        }

        val advanced = passive
            .advance(admission, restored.checkpoint)
            .getOrThrow()

        when (advanced.checkpoint.taskState) {
            AmperAgentTaskState.READY,
            AmperAgentTaskState.CHECKPOINTED -> {
                val nextEnvelope = continuation
                    .checkpoint(admission, advanced.checkpoint)
                    .getOrThrow()
                val nextHandoff = AmperAgentAndroidContinuationHandoffPolicy.create(nextEnvelope)
                AmperAgentAndroidHostExecutionResult(
                    state = AmperAgentAndroidHostExecutionState.CHECKPOINTED,
                    detail = "one canonical plan step advanced; next checkpoint is ready",
                    nextHandoff = nextHandoff
                )
            }

            AmperAgentTaskState.WAITING_APPROVAL ->
                AmperAgentAndroidHostExecutionResult(
                    state = AmperAgentAndroidHostExecutionState.WAITING_APPROVAL,
                    detail = "one canonical plan step reached governed approval"
                )

            AmperAgentTaskState.COMPLETED,
            AmperAgentTaskState.FAILED,
            AmperAgentTaskState.CANCELLED -> {
                admissions.remove(envelope.taskId)
                AmperAgentAndroidHostExecutionResult(
                    state = AmperAgentAndroidHostExecutionState.TERMINAL_NOOP,
                    detail = "canonical Agent Core task reached terminal durable state"
                )
            }

            AmperAgentTaskState.ADMITTED,
            AmperAgentTaskState.RUNNING ->
                error("passive coordinator returned non-checkpointable task state")
        }
    }
}
