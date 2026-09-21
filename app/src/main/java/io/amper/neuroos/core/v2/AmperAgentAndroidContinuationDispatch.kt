package io.amper.neuroos.core.v2

enum class AmperAgentAndroidHostExecutionState {
    ADVANCED,
    CHECKPOINTED,
    WAITING_APPROVAL,
    TERMINAL_NOOP,
    RETRY_LATER
}

data class AmperAgentAndroidHostExecutionResult(
    val state: AmperAgentAndroidHostExecutionState,
    val detail: String,
    val nextHandoff: AmperAgentAndroidContinuationHandoff? = null
) {
    init {
        require(detail.isNotBlank() && detail.length <= 512)
        require(
            (state == AmperAgentAndroidHostExecutionState.CHECKPOINTED) ==
                (nextHandoff != null)
        ) {
            "CHECKPOINTED Android host result requires exactly one verified next handoff"
        }
    }

    val shouldReschedule: Boolean
        get() = state == AmperAgentAndroidHostExecutionState.RETRY_LATER
}

/**
 * Non-authoritative execution port implemented by the canonical Agent Core wiring.
 *
 * A host may call this only after Phase650 transport verification and only for a handoff whose wake
 * disposition is READY_FOR_EXPLICIT_ADVANCE. The implementation is expected to restore the exact
 * Phase649 checkpoint and request at most one passive-plan advance.
 */
fun interface AmperAgentAndroidContinuationExecutionPort {
    fun advanceOnceVerified(
        handoff: AmperAgentAndroidContinuationHandoff,
        envelope: AmperAgentContinuationEnvelope
    ): Result<AmperAgentAndroidHostExecutionResult>
}

/**
 * Pure dispatcher shared by Android Service/JobService hosts.
 *
 * WAITING_APPROVAL and terminal wake dispositions never reach the execution port. Therefore an OS
 * wake cannot implicitly approve a side effect or replay terminal work.
 */
object AmperAgentAndroidContinuationHostDispatcher {
    fun dispatch(
        handoff: AmperAgentAndroidContinuationHandoff,
        execution: AmperAgentAndroidContinuationExecutionPort?
    ): Result<AmperAgentAndroidHostExecutionResult> = runCatching {
        val envelope = AmperAgentAndroidContinuationHandoffPolicy
            .verifyAndDecode(handoff)
            .getOrThrow()

        when (handoff.wakeDisposition) {
            AmperAgentContinuationWakeDisposition.WAITING_GOVERNED_APPROVAL ->
                AmperAgentAndroidHostExecutionResult(
                    state = AmperAgentAndroidHostExecutionState.WAITING_APPROVAL,
                    detail = "continuation remains blocked on governed approval"
                )

            AmperAgentContinuationWakeDisposition.TERMINAL_NOOP ->
                AmperAgentAndroidHostExecutionResult(
                    state = AmperAgentAndroidHostExecutionState.TERMINAL_NOOP,
                    detail = "continuation is terminal; no execution requested"
                )

            AmperAgentContinuationWakeDisposition.READY_FOR_EXPLICIT_ADVANCE -> {
                val port = execution
                    ?: return@runCatching AmperAgentAndroidHostExecutionResult(
                        state = AmperAgentAndroidHostExecutionState.RETRY_LATER,
                        detail = "canonical Agent Core execution port is unavailable"
                    )
                port.advanceOnceVerified(
                    handoff = handoff,
                    envelope = envelope
                ).getOrThrow()
            }
        }
    }
}
