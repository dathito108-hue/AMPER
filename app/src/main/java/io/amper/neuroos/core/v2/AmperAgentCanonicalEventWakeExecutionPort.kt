package io.amper.neuroos.core.v2

enum class AmperAgentEventWakeHostExecutionState {
    CHECKPOINTED,
    WAITING_APPROVAL,
    TERMINAL_NOOP,
    RETRY_LATER
}

data class AmperAgentEventWakeHostExecutionResult(
    val state: AmperAgentEventWakeHostExecutionState,
    val detail: String,
    val nextHandoff: AmperAgentEventWakeHandoff? = null
) {
    init {
        require(detail.isNotBlank() && detail.length <= 512)
        require(
            (state == AmperAgentEventWakeHostExecutionState.CHECKPOINTED) ==
                (nextHandoff != null)
        ) {
            "CHECKPOINTED EVENT_WAKE host result requires exactly one fresh next handoff"
        }
    }

    val shouldReschedule: Boolean
        get() = state == AmperAgentEventWakeHostExecutionState.RETRY_LATER
}

fun interface AmperAgentEventWakeExecutionPort {
    fun advanceOnceVerified(
        handoff: AmperAgentEventWakeHandoff,
        envelope: AmperAgentEventWakeEnvelope
    ): Result<AmperAgentEventWakeHostExecutionResult>
}

/**
 * Pure Phase656 host dispatcher.
 *
 * Verification always runs before any execution port or cold fallback is acquired. Approval-blocked
 * and terminal proactive wakes therefore cannot construct execution infrastructure or call tools.
 */
object AmperAgentEventWakeHostDispatcher {
    fun dispatch(
        handoff: AmperAgentEventWakeHandoff,
        execution: AmperAgentEventWakeExecutionPort?,
        executionFallback: (() -> Result<AmperAgentEventWakeExecutionPort>)? = null
    ): Result<AmperAgentEventWakeHostExecutionResult> = runCatching {
        val envelope = AmperAgentEventWakeHandoffPolicy
            .verifyAndDecode(handoff)
            .getOrThrow()

        when (handoff.disposition) {
            AmperAgentEventWakeDisposition.WAITING_GOVERNED_APPROVAL ->
                AmperAgentEventWakeHostExecutionResult(
                    state = AmperAgentEventWakeHostExecutionState.WAITING_APPROVAL,
                    detail = "proactive EVENT_WAKE remains blocked on governed approval"
                )

            AmperAgentEventWakeDisposition.TERMINAL_NOOP ->
                AmperAgentEventWakeHostExecutionResult(
                    state = AmperAgentEventWakeHostExecutionState.TERMINAL_NOOP,
                    detail = "proactive EVENT_WAKE is terminal; no execution requested"
                )

            AmperAgentEventWakeDisposition.READY_FOR_EXPLICIT_ADVANCE -> {
                val port = execution
                    ?: executionFallback?.invoke()?.getOrThrow()
                    ?: return@runCatching AmperAgentEventWakeHostExecutionResult(
                        state = AmperAgentEventWakeHostExecutionState.RETRY_LATER,
                        detail = "canonical proactive EVENT_WAKE execution port is unavailable"
                    )
                port.advanceOnceVerified(handoff, envelope).getOrThrow()
            }
        }
    }
}

/**
 * Canonical one-step proactive wake execution port.
 *
 * The same admission registry, PersistentSovereignAgentPlanPort, proactive coordinator, verified
 * EVENT_WAKE coordinator, and process-wide wake gate are reused. No monitor-specific executor or
 * authority path exists here.
 */
class AmperAgentCanonicalEventWakeExecutionPort(
    private val admissions: AmperAgentTaskAdmissionRegistry,
    private val plans: AmperAgentPersistentPlanPort,
    private val proactive: AmperAgentProactiveTaskCoordinator,
    private val eventWake: AmperAgentProactiveEventWakeCoordinator
) : AmperAgentEventWakeExecutionPort {
    private fun restoreNarrowAdmission(
        envelope: AmperAgentEventWakeEnvelope
    ): AmperAgentTaskAdmission {
        val plan = requireNotNull(plans.load(envelope.planId)) {
            "durable proactive sovereign plan is unavailable for admission reconstruction"
        }
        val request = AmperAgentTaskRequest(
            taskId = envelope.taskId,
            origin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER,
            objective = plan.goal,
            allowedCapabilities = plan.steps.mapTo(linkedSetOf()) { it.capability },
            expectedRuntimeMs = 0L,
            mustSurviveUiExit = true,
            canBeDeferred = true,
            createdAtEpochMs = plan.createdAtEpochMs,
            trigger = envelope.trigger
        )
        val admission = AmperAgentTaskAdmissionPolicy.admit(request)
        require(admission.backgroundMode == OmegaBackgroundMode.EVENT_WAKE)
        return admission
    }

    override fun advanceOnceVerified(
        handoff: AmperAgentEventWakeHandoff,
        envelope: AmperAgentEventWakeEnvelope
    ): Result<AmperAgentEventWakeHostExecutionResult> =
        AmperAgentCanonicalWakeExecutionGate.exclusive {
            runCatching {
                require(handoff.taskId == envelope.taskId)
                require(handoff.planId == envelope.planId.value)
                require(handoff.triggerId == envelope.trigger.triggerId)
                require(handoff.triggerPayloadSha256 == envelope.trigger.payloadDigest)
                require(
                    envelope.disposition ==
                        AmperAgentEventWakeDisposition.READY_FOR_EXPLICIT_ADVANCE
                ) {
                    "non-runnable proactive EVENT_WAKE reached execution port"
                }

                val admission = admissions.get(envelope.taskId)
                    ?: restoreNarrowAdmission(envelope).also(admissions::register)

                require(admission.request.origin == AmperAgentTaskOrigin.PROACTIVE_TRIGGER)
                require(admission.backgroundMode == OmegaBackgroundMode.EVENT_WAKE)
                require(admission.request.trigger == envelope.trigger) {
                    "proactive admission trigger drifted before EVENT_WAKE execution"
                }

                val restored = eventWake.restore(admission, envelope).getOrThrow()
                require(restored.runnable)
                require(restored.checkpoint.taskState == AmperAgentTaskState.CHECKPOINTED) {
                    "verified proactive EVENT_WAKE did not restore to CHECKPOINTED"
                }

                val advanced = proactive
                    .advance(admission, restored.checkpoint)
                    .getOrThrow()

                when (advanced.checkpoint.taskState) {
                    AmperAgentTaskState.READY,
                    AmperAgentTaskState.CHECKPOINTED -> {
                        val next = eventWake
                            .handoff(admission, advanced.checkpoint)
                            .getOrThrow()
                        AmperAgentEventWakeHostExecutionResult(
                            state = AmperAgentEventWakeHostExecutionState.CHECKPOINTED,
                            detail = "one proactive persistent plan step advanced; next EVENT_WAKE checkpoint is ready",
                            nextHandoff = next
                        )
                    }

                    AmperAgentTaskState.WAITING_APPROVAL ->
                        AmperAgentEventWakeHostExecutionResult(
                            state = AmperAgentEventWakeHostExecutionState.WAITING_APPROVAL,
                            detail = "one proactive plan step reached governed approval"
                        )

                    AmperAgentTaskState.COMPLETED,
                    AmperAgentTaskState.FAILED,
                    AmperAgentTaskState.CANCELLED -> {
                        admissions.remove(envelope.taskId)
                        AmperAgentEventWakeHostExecutionResult(
                            state = AmperAgentEventWakeHostExecutionState.TERMINAL_NOOP,
                            detail = "proactive Agent Core task reached terminal durable state"
                        )
                    }

                    AmperAgentTaskState.ADMITTED,
                    AmperAgentTaskState.RUNNING ->
                        error("proactive coordinator returned non-checkpointable task state")
                }
            }
        }
}
