package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.ConversationId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class AmperAgentProactiveTriggerSourceKind {
    SCHEDULED_WINDOW,
    APP_LOCAL_CONDITION
}

/**
 * Finite user-configured proactive trigger definition.
 *
 * A definition is control data only. It cannot observe Android state, schedule itself, plan, invoke
 * tools, or grant authority. Phase658 deliberately requires a bounded active window, bounded firing
 * count, and minimum cadence so a trigger source cannot become an unbounded polling loop.
 */
data class AmperAgentProactiveTriggerSourceDefinition(
    val triggerId: String,
    val source: String,
    val kind: AmperAgentProactiveTriggerSourceKind,
    val objective: String,
    val allowedCapabilities: Set<CapabilityId>,
    val expectedRuntimeMs: Long,
    val activeFromEpochMs: Long,
    val expiresAtEpochMs: Long,
    val minimumIntervalMs: Long,
    val maxFirings: Int,
    val configuredAtEpochMs: Long
) {
    init {
        require(triggerId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}"))) {
            "proactive trigger definition requires stable lowercase id"
        }
        require(source.isNotBlank() && source.length <= 128)
        require(objective.isNotBlank() && objective.length <= AmperAgentTaskRequest.MAX_OBJECTIVE_CHARS)
        require(allowedCapabilities.isNotEmpty())
        require(allowedCapabilities.size <= AmperAgentTaskRequest.MAX_CAPABILITIES)
        require(expectedRuntimeMs in 0L..MAX_EXPECTED_RUNTIME_MS)
        require(configuredAtEpochMs >= 0L)
        require(activeFromEpochMs >= configuredAtEpochMs)
        require(expiresAtEpochMs >= activeFromEpochMs)
        require(expiresAtEpochMs - activeFromEpochMs <= MAX_ACTIVE_WINDOW_MS) {
            "proactive trigger active window exceeds bounded lifetime"
        }
        require(minimumIntervalMs >= MINIMUM_TRIGGER_INTERVAL_MS) {
            "proactive trigger cadence is too frequent"
        }
        require(maxFirings in 1..MAX_FIRINGS)
        if (kind == AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW) {
            require(maxFirings == 1) {
                "scheduled-window trigger is a finite one-shot observation"
            }
        }
    }

    companion object {
        const val MINIMUM_TRIGGER_INTERVAL_MS: Long = 15_000L
        const val MAX_ACTIVE_WINDOW_MS: Long = 30L * 24L * 60L * 60L * 1000L
        const val MAX_EXPECTED_RUNTIME_MS: Long = 6L * 60L * 60L * 1000L
        const val MAX_FIRINGS: Int = 32
    }
}

/**
 * One externally observed event sample.
 *
 * The payload itself is intentionally absent. Only its SHA-256 identity crosses the Agent trigger
 * boundary. Observation is push/one-shot input to Phase658; this contract owns no polling loop.
 */
data class AmperAgentProactiveTriggerObservation(
    val triggerId: String,
    val source: String,
    val observedAtEpochMs: Long,
    val payloadDigest: String
) {
    init {
        require(triggerId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(source.isNotBlank() && source.length <= 128)
        require(observedAtEpochMs >= 0L)
        require(payloadDigest.matches(Regex("[0-9a-f]{64}")))
    }

    fun canonicalTrigger(): AmperAgentTrigger =
        AmperAgentTrigger(
            triggerId = triggerId,
            source = source,
            observedAtEpochMs = observedAtEpochMs,
            payloadDigest = payloadDigest
        )
}

/**
 * Bounded source replay/cadence state.
 *
 * Phase658 keeps this representation pure. A later durable registry may persist it, but execution
 * authority must never be embedded into source state.
 */
data class AmperAgentProactiveTriggerSourceState(
    val fireCount: Int = 0,
    val lastFiredAtEpochMs: Long? = null,
    val consumedPayloadDigests: Set<String> = emptySet()
) {
    init {
        require(fireCount >= 0)
        require(lastFiredAtEpochMs == null || lastFiredAtEpochMs >= 0L)
        require(consumedPayloadDigests.size <= AmperAgentProactiveTriggerSourceDefinition.MAX_FIRINGS)
        require(consumedPayloadDigests.all { it.matches(Regex("[0-9a-f]{64}")) })
        require(consumedPayloadDigests.size <= fireCount) {
            "consumed trigger observations cannot exceed firing count"
        }
    }
}

data class AmperAgentQualifiedProactiveTrigger(
    val definition: AmperAgentProactiveTriggerSourceDefinition,
    val observation: AmperAgentProactiveTriggerObservation,
    val trigger: AmperAgentTrigger,
    val request: AmperAgentTaskRequest,
    val admission: AmperAgentTaskAdmission,
    val nextSourceState: AmperAgentProactiveTriggerSourceState
) {
    init {
        require(request.origin == AmperAgentTaskOrigin.PROACTIVE_TRIGGER)
        require(request.trigger == trigger)
        require(admission.request == request)
        require(admission.backgroundMode == OmegaBackgroundMode.EVENT_WAKE)
        require(admission.checkpointRequired)
    }
}

/**
 * Pure one-observation qualification policy.
 *
 * Qualification can only narrow an already user-configured definition into the canonical Phase647
 * PROACTIVE_TRIGGER admission. It executes nothing and has no Android/runtime/tool dependency.
 */
object AmperAgentProactiveTriggerSourcePolicy {
    fun qualify(
        definition: AmperAgentProactiveTriggerSourceDefinition,
        observation: AmperAgentProactiveTriggerObservation,
        state: AmperAgentProactiveTriggerSourceState
    ): Result<AmperAgentQualifiedProactiveTrigger> = runCatching {
        require(observation.triggerId == definition.triggerId) {
            "trigger observation id does not match configured source"
        }
        require(observation.source == definition.source) {
            "trigger observation source does not match configured source"
        }
        require(observation.observedAtEpochMs in
            definition.activeFromEpochMs..definition.expiresAtEpochMs
        ) {
            "trigger observation is outside configured active window"
        }
        require(state.fireCount < definition.maxFirings) {
            "proactive trigger exhausted its bounded firing count"
        }
        require(observation.payloadDigest !in state.consumedPayloadDigests) {
            "proactive trigger observation was already consumed"
        }
        state.lastFiredAtEpochMs?.let { last ->
            require(observation.observedAtEpochMs >= last) {
                "proactive trigger observation moved backwards in time"
            }
            require(observation.observedAtEpochMs - last >= definition.minimumIntervalMs) {
                "proactive trigger observation violates minimum cadence"
            }
        }

        val trigger = observation.canonicalTrigger()
        val request = AmperAgentTaskRequest(
            taskId = taskIdFor(definition, observation),
            origin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER,
            objective = definition.objective,
            allowedCapabilities = definition.allowedCapabilities,
            expectedRuntimeMs = definition.expectedRuntimeMs,
            mustSurviveUiExit = true,
            canBeDeferred = true,
            createdAtEpochMs = observation.observedAtEpochMs,
            trigger = trigger
        )
        val admission = AmperAgentTaskAdmissionPolicy.admit(request)
        val nextState = AmperAgentProactiveTriggerSourceState(
            fireCount = state.fireCount + 1,
            lastFiredAtEpochMs = observation.observedAtEpochMs,
            consumedPayloadDigests = state.consumedPayloadDigests + observation.payloadDigest
        )

        AmperAgentQualifiedProactiveTrigger(
            definition = definition,
            observation = observation,
            trigger = trigger,
            request = request,
            admission = admission,
            nextSourceState = nextState
        )
    }

    private fun taskIdFor(
        definition: AmperAgentProactiveTriggerSourceDefinition,
        observation: AmperAgentProactiveTriggerObservation
    ): String {
        val identity = listOf(
            definition.triggerId,
            definition.source,
            observation.observedAtEpochMs.toString(),
            observation.payloadDigest
        ).joinToString("|")
        return "proactive:" + sha256(identity).take(40)
    }
}

data class AmperAgentProactiveTriggerStart(
    val qualification: AmperAgentQualifiedProactiveTrigger,
    val task: AmperAgentPlanTaskResult,
    val handoff: AmperAgentEventWakeHandoff
)

/**
 * Canonical first-step bridge from one qualified trigger observation to the Phase657 scheduler.
 *
 * It creates one persistent sovereign plan through the existing Phase654 proactive coordinator and
 * emits one Phase655 verified handoff. It performs no plan advance and no tool call.
 */
class AmperAgentProactiveTriggerStartCoordinator(
    private val proactive: AmperAgentProactiveTaskCoordinator,
    private val eventWake: AmperAgentProactiveEventWakeCoordinator
) {
    fun start(
        qualification: AmperAgentQualifiedProactiveTrigger,
        conversationId: ConversationId
    ): Result<AmperAgentProactiveTriggerStart> = runCatching {
        require(qualification.admission.request.origin == AmperAgentTaskOrigin.PROACTIVE_TRIGGER)
        require(qualification.admission.backgroundMode == OmegaBackgroundMode.EVENT_WAKE)

        val started = proactive
            .start(qualification.admission, conversationId)
            .getOrThrow()
        val handoff = eventWake
            .handoff(qualification.admission, started.checkpoint)
            .getOrThrow()

        AmperAgentProactiveTriggerStart(
            qualification = qualification,
            task = started,
            handoff = handoff
        )
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
