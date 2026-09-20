package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId

enum class AmperAgentTaskOrigin {
    USER_REQUEST,
    PROACTIVE_TRIGGER
}

data class AmperAgentTrigger(
    val triggerId: String,
    val source: String,
    val observedAtEpochMs: Long,
    val payloadDigest: String
) {
    init {
        require(triggerId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}"))) {
            "agent trigger id must be stable lowercase text"
        }
        require(source.isNotBlank() && source.length <= 128)
        require(observedAtEpochMs >= 0L)
        require(payloadDigest.matches(Regex("[0-9a-f]{64}"))) {
            "agent trigger payload must be represented by lowercase SHA-256"
        }
    }
}

data class AmperAgentTaskRequest(
    val taskId: String,
    val origin: AmperAgentTaskOrigin,
    val objective: String,
    val allowedCapabilities: Set<CapabilityId>,
    val expectedRuntimeMs: Long,
    val mustSurviveUiExit: Boolean,
    val canBeDeferred: Boolean,
    val createdAtEpochMs: Long,
    val trigger: AmperAgentTrigger? = null
) {
    init {
        require(taskId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}"))) {
            "agent task id must be stable lowercase text"
        }
        require(objective.isNotBlank())
        require(objective.length <= MAX_OBJECTIVE_CHARS) {
            "agent task objective exceeds bounded control limit"
        }
        require(allowedCapabilities.size <= MAX_CAPABILITIES)
        require(expectedRuntimeMs >= 0L)
        require(createdAtEpochMs >= 0L)

        when (origin) {
            AmperAgentTaskOrigin.USER_REQUEST ->
                require(trigger == null) {
                    "user-request task cannot claim proactive trigger provenance"
                }

            AmperAgentTaskOrigin.PROACTIVE_TRIGGER ->
                require(trigger != null) {
                    "proactive agent task requires explicit trigger provenance"
                }
        }
    }

    val userInitiated: Boolean
        get() = origin == AmperAgentTaskOrigin.USER_REQUEST

    companion object {
        const val MAX_OBJECTIVE_CHARS: Int = 32 * 1024
        const val MAX_CAPABILITIES: Int = 32
    }
}

data class AmperAgentTaskAdmission(
    val request: AmperAgentTaskRequest,
    val backgroundMode: OmegaBackgroundMode,
    val checkpointRequired: Boolean,
    val toolAuthorityRemainsExternal: Boolean = true,
    val auditRequired: Boolean = true
) {
    init {
        require(toolAuthorityRemainsExternal) {
            "Agent Core cannot absorb ToolFabric/AuthorityGate authority"
        }
        require(auditRequired) {
            "Agent Core tasks must preserve audited tool execution"
        }
        require(
            checkpointRequired ==
                (backgroundMode != OmegaBackgroundMode.UI_BOUND)
        ) {
            "all non-UI-bound Agent Core work must be checkpointed"
        }

        if (request.origin == AmperAgentTaskOrigin.PROACTIVE_TRIGGER) {
            require(backgroundMode == OmegaBackgroundMode.EVENT_WAKE) {
                "proactive trigger tasks must enter through canonical EVENT_WAKE policy"
            }
        }
    }
}

/**
 * Canonical M5 admission contract above the existing planner, ToolFabric, approval/audit and
 * persistent-goal machinery.
 *
 * It decides only task provenance and Android-safe execution mode. It never authorizes a tool,
 * executes a plan, changes a model route, or bypasses existing approval/receipt boundaries.
 */
object AmperAgentTaskAdmissionPolicy {
    fun admit(request: AmperAgentTaskRequest): AmperAgentTaskAdmission {
        val work = OmegaBackgroundWork(
            expectedRuntimeMs = request.expectedRuntimeMs,
            userInitiated = request.userInitiated,
            mustSurviveUiExit = request.mustSurviveUiExit,
            canBeDeferred = request.canBeDeferred,
            hasFutureTrigger = request.origin == AmperAgentTaskOrigin.PROACTIVE_TRIGGER
        )
        val mode = OmegaBackgroundExecutionPolicy.choose(work)
        return AmperAgentTaskAdmission(
            request = request,
            backgroundMode = mode,
            checkpointRequired = mode != OmegaBackgroundMode.UI_BOUND
        )
    }
}

enum class AmperAgentTaskState {
    ADMITTED,
    READY,
    RUNNING,
    WAITING_APPROVAL,
    CHECKPOINTED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Fail-closed task lifecycle. Side-effect approval remains external: WAITING_APPROVAL can only
 * return to READY after the existing governed approval path resolves it.
 */
object AmperAgentTaskLifecycle {
    private val allowedTransitions: Map<AmperAgentTaskState, Set<AmperAgentTaskState>> = mapOf(
        AmperAgentTaskState.ADMITTED to setOf(
            AmperAgentTaskState.READY,
            AmperAgentTaskState.CHECKPOINTED,
            AmperAgentTaskState.CANCELLED
        ),
        AmperAgentTaskState.READY to setOf(
            AmperAgentTaskState.RUNNING,
            AmperAgentTaskState.CHECKPOINTED,
            AmperAgentTaskState.CANCELLED
        ),
        AmperAgentTaskState.RUNNING to setOf(
            AmperAgentTaskState.WAITING_APPROVAL,
            AmperAgentTaskState.CHECKPOINTED,
            AmperAgentTaskState.COMPLETED,
            AmperAgentTaskState.FAILED,
            AmperAgentTaskState.CANCELLED
        ),
        AmperAgentTaskState.WAITING_APPROVAL to setOf(
            AmperAgentTaskState.READY,
            AmperAgentTaskState.CHECKPOINTED,
            AmperAgentTaskState.FAILED,
            AmperAgentTaskState.CANCELLED
        ),
        AmperAgentTaskState.CHECKPOINTED to setOf(
            AmperAgentTaskState.READY,
            AmperAgentTaskState.FAILED,
            AmperAgentTaskState.CANCELLED
        ),
        AmperAgentTaskState.COMPLETED to emptySet(),
        AmperAgentTaskState.FAILED to emptySet(),
        AmperAgentTaskState.CANCELLED to emptySet()
    )

    fun canTransition(
        from: AmperAgentTaskState,
        to: AmperAgentTaskState
    ): Boolean = to in requireNotNull(allowedTransitions[from])

    fun requireTransition(
        from: AmperAgentTaskState,
        to: AmperAgentTaskState
    ) {
        require(canTransition(from, to)) {
            "invalid Agent Core task transition: $from -> $to"
        }
    }
}
