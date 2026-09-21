package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class AmperAgentProactiveTriggerSourceKind {
    SCHEDULED_WINDOW,
    APP_LOCAL_EVENT
}

/**
 * User-configured bounded source definition for proactive Agent work.
 *
 * Phase658 intentionally exposes no generic polling source. Scheduled sources must use an
 * Android-safe minimum cadence, while app-local events are event-driven and still use a cooldown to
 * prevent burst amplification.
 */
data class AmperAgentProactiveTriggerSource(
    val sourceId: String,
    val configurationId: String,
    val kind: AmperAgentProactiveTriggerSourceKind,
    val objective: String,
    val allowedCapabilities: Set<CapabilityId>,
    val expectedRuntimeMs: Long,
    val minimumIntervalMs: Long,
    val configurationSha256: String,
    val userConfigured: Boolean = true,
    val enabled: Boolean = true
) {
    init {
        require(sourceId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}"))) {
            "proactive trigger source id must be stable lowercase text"
        }
        require(configurationId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}"))) {
            "proactive trigger configuration id must be stable lowercase text"
        }
        require(objective.isNotBlank())
        require(objective.length <= AmperAgentTaskRequest.MAX_OBJECTIVE_CHARS)
        require(allowedCapabilities.isNotEmpty())
        require(allowedCapabilities.size <= AmperAgentTaskRequest.MAX_CAPABILITIES)
        require(expectedRuntimeMs in 1L..MAX_EXPECTED_RUNTIME_MS) {
            "proactive trigger expected runtime exceeds bounded mobile task budget"
        }
        require(configurationSha256.matches(Regex("[0-9a-f]{64}"))) {
            "proactive trigger configuration identity must use lowercase SHA-256"
        }
        require(userConfigured) {
            "Phase658 proactive trigger sources must be explicitly user-configured"
        }
        require(minimumIntervalMs >= minimumIntervalFor(kind)) {
            "proactive trigger source cadence is too frequent for its source kind"
        }
    }

    companion object {
        const val APP_LOCAL_EVENT_MIN_INTERVAL_MS: Long = 60_000L
        const val SCHEDULED_WINDOW_MIN_INTERVAL_MS: Long = 15L * 60L * 1_000L
        const val MAX_EXPECTED_RUNTIME_MS: Long = 10L * 60L * 1_000L

        fun minimumIntervalFor(kind: AmperAgentProactiveTriggerSourceKind): Long =
            when (kind) {
                AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW ->
                    SCHEDULED_WINDOW_MIN_INTERVAL_MS
                AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT ->
                    APP_LOCAL_EVENT_MIN_INTERVAL_MS
            }
    }
}

data class AmperAgentProactiveTriggerObservation(
    val sourceId: String,
    val observedAtEpochMs: Long,
    val payloadDigest: String
) {
    init {
        require(sourceId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(observedAtEpochMs >= 0L)
        require(payloadDigest.matches(Regex("[0-9a-f]{64}"))) {
            "proactive trigger observation payload must use lowercase SHA-256"
        }
    }
}

data class AmperAgentQualifiedProactiveTrigger(
    val sourceId: String,
    val sourceKind: AmperAgentProactiveTriggerSourceKind,
    val configurationSha256: String,
    val observationIdentitySha256: String,
    val admission: AmperAgentTaskAdmission
) {
    init {
        require(admission.request.origin == AmperAgentTaskOrigin.PROACTIVE_TRIGGER)
        require(admission.backgroundMode == OmegaBackgroundMode.EVENT_WAKE)
        require(admission.checkpointRequired)
        require(admission.toolAuthorityRemainsExternal)
        require(admission.auditRequired)
        require(observationIdentitySha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/**
 * Pure qualification boundary from a finite user-configured observation to canonical Agent
 * admission.
 *
 * It creates no Android job, plan, tool execution, model call, monitor loop, or approval. The
 * resulting admission may enter the existing Phase654 proactive persistent-plan coordinator and
 * Phase657 scheduler only through their canonical boundaries.
 */
object AmperAgentProactiveTriggerSourcePolicy {
    fun qualify(
        source: AmperAgentProactiveTriggerSource,
        observation: AmperAgentProactiveTriggerObservation,
        lastAcceptedObservationAtEpochMs: Long? = null
    ): Result<AmperAgentQualifiedProactiveTrigger> = runCatching {
        require(source.enabled) {
            "disabled proactive trigger source cannot emit Agent work"
        }
        require(source.userConfigured)
        require(observation.sourceId == source.sourceId) {
            "proactive trigger observation belongs to a different source"
        }
        lastAcceptedObservationAtEpochMs?.let { lastAccepted ->
            require(lastAccepted >= 0L)
            require(observation.observedAtEpochMs >= lastAccepted)
            require(
                observation.observedAtEpochMs - lastAccepted >= source.minimumIntervalMs
            ) {
                "proactive trigger observation is inside the configured cooldown"
            }
        }

        val observationIdentity = sha256(
            listOf(
                source.sourceId,
                source.configurationId,
                source.configurationSha256,
                source.kind.name,
                observation.observedAtEpochMs.toString(),
                observation.payloadDigest
            ).joinToString("|")
        )
        val triggerSource = buildString {
            append("user-configured:")
            append(source.kind.name.lowercase())
            append(":")
            append(sha256(source.configurationId).take(16))
            append(":")
            append(source.configurationSha256.take(16))
        }
        val request = AmperAgentTaskRequest(
            taskId = "proactive:" + observationIdentity.take(48),
            origin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER,
            objective = source.objective,
            allowedCapabilities = source.allowedCapabilities,
            expectedRuntimeMs = source.expectedRuntimeMs,
            mustSurviveUiExit = true,
            canBeDeferred = true,
            createdAtEpochMs = observation.observedAtEpochMs,
            trigger = AmperAgentTrigger(
                triggerId = source.sourceId,
                source = triggerSource,
                observedAtEpochMs = observation.observedAtEpochMs,
                payloadDigest = observation.payloadDigest
            )
        )
        val admission = AmperAgentTaskAdmissionPolicy.admit(request)
        require(admission.backgroundMode == OmegaBackgroundMode.EVENT_WAKE) {
            "qualified proactive trigger did not retain canonical EVENT_WAKE admission"
        }

        AmperAgentQualifiedProactiveTrigger(
            sourceId = source.sourceId,
            sourceKind = source.kind,
            configurationSha256 = source.configurationSha256,
            observationIdentitySha256 = observationIdentity,
            admission = admission
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
