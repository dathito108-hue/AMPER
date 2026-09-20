package io.amper.neuroos.core.v2

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class AmperAgentAndroidExecutionHost {
    VISIBLE_FOREGROUND_SERVICE,
    PERSISTED_JOB_SCHEDULER
}

enum class AmperAgentContinuationWakeDisposition {
    READY_FOR_EXPLICIT_ADVANCE,
    WAITING_GOVERNED_APPROVAL,
    TERMINAL_NOOP
}

data class AmperAgentAndroidContinuationHandoff(
    val taskId: String,
    val planId: String,
    val executionHost: AmperAgentAndroidExecutionHost,
    val wakeDisposition: AmperAgentContinuationWakeDisposition,
    val dedupeKey: String,
    val encodedEnvelope: String,
    val envelopeSha256: String,
    val requiresVisibleNotification: Boolean,
    val persistedAcrossReboot: Boolean
) {
    init {
        require(taskId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(planId.isNotBlank() && planId.length <= 160)
        require(dedupeKey.matches(Regex("[a-z0-9][a-z0-9._:-]{0,319}")))
        require(encodedEnvelope.isNotBlank())
        require(encodedEnvelope.toByteArray(StandardCharsets.UTF_8).size <= MAX_ENVELOPE_BYTES) {
            "Agent Core Android continuation envelope exceeds bounded transport limit"
        }
        require(envelopeSha256.matches(Regex("[0-9a-f]{64}")))
        require(
            envelopeSha256 == sha256(encodedEnvelope)
        ) { "Agent Core Android handoff envelope digest mismatch" }

        when (executionHost) {
            AmperAgentAndroidExecutionHost.VISIBLE_FOREGROUND_SERVICE -> {
                require(requiresVisibleNotification) {
                    "foreground Agent Core continuation must remain user-visible"
                }
                require(!persistedAcrossReboot) {
                    "foreground service handoff is not the persisted reboot mechanism"
                }
            }
            AmperAgentAndroidExecutionHost.PERSISTED_JOB_SCHEDULER -> {
                require(!requiresVisibleNotification) {
                    "persisted Agent Core job handoff does not claim foreground-service visibility"
                }
                require(persistedAcrossReboot) {
                    "persisted Agent Core jobs must survive reboot scheduling"
                }
            }
        }
    }

    companion object {
        const val MAX_ENVELOPE_BYTES: Int = 64 * 1024
    }
}

/**
 * Pure M5 routing contract between verified Agent Core continuation state and Android execution
 * components. It schedules nothing and owns no Context, Service, JobScheduler, planner, or tool.
 *
 * Phase651 Android hosts consume this handoff after verifying [envelopeSha256], decode the exact
 * Phase649 envelope, restore it, and only then may request one explicit passive-plan advance.
 */
object AmperAgentAndroidContinuationHandoffPolicy {
    fun create(
        envelope: AmperAgentContinuationEnvelope
    ): AmperAgentAndroidContinuationHandoff {
        val encoded = AmperAgentContinuationEnvelopeCodec.encode(envelope)
        val host = when (envelope.backgroundMode) {
            OmegaBackgroundMode.FOREGROUND_CONTINUATION ->
                AmperAgentAndroidExecutionHost.VISIBLE_FOREGROUND_SERVICE
            OmegaBackgroundMode.PERSISTED_JOB ->
                AmperAgentAndroidExecutionHost.PERSISTED_JOB_SCHEDULER
            OmegaBackgroundMode.UI_BOUND,
            OmegaBackgroundMode.EVENT_WAKE ->
                error("unsupported Phase650 Android continuation mode")
        }
        val wakeDisposition = when (envelope.taskState) {
            AmperAgentTaskState.WAITING_APPROVAL ->
                AmperAgentContinuationWakeDisposition.WAITING_GOVERNED_APPROVAL
            AmperAgentTaskState.COMPLETED,
            AmperAgentTaskState.FAILED,
            AmperAgentTaskState.CANCELLED ->
                AmperAgentContinuationWakeDisposition.TERMINAL_NOOP
            AmperAgentTaskState.CHECKPOINTED,
            AmperAgentTaskState.READY,
            AmperAgentTaskState.RUNNING ->
                AmperAgentContinuationWakeDisposition.READY_FOR_EXPLICIT_ADVANCE
            AmperAgentTaskState.ADMITTED ->
                error("unbound Agent Core task cannot create Android continuation handoff")
        }

        return AmperAgentAndroidContinuationHandoff(
            taskId = envelope.taskId,
            planId = envelope.planId.value,
            executionHost = host,
            wakeDisposition = wakeDisposition,
            dedupeKey = buildDedupeKey(envelope.taskId, envelope.planId.value),
            encodedEnvelope = encoded,
            envelopeSha256 = sha256(encoded),
            requiresVisibleNotification =
                host == AmperAgentAndroidExecutionHost.VISIBLE_FOREGROUND_SERVICE,
            persistedAcrossReboot =
                host == AmperAgentAndroidExecutionHost.PERSISTED_JOB_SCHEDULER
        )
    }

    fun verifyAndDecode(
        handoff: AmperAgentAndroidContinuationHandoff
    ): Result<AmperAgentContinuationEnvelope> = runCatching {
        require(sha256(handoff.encodedEnvelope) == handoff.envelopeSha256) {
            "Agent Core Android handoff changed before restore"
        }
        val envelope = AmperAgentContinuationEnvelopeCodec
            .decode(handoff.encodedEnvelope)
            .getOrThrow()
        require(envelope.taskId == handoff.taskId)
        require(envelope.planId.value == handoff.planId)
        require(buildDedupeKey(envelope.taskId, envelope.planId.value) == handoff.dedupeKey)

        val canonical = create(envelope)
        require(canonical.executionHost == handoff.executionHost)
        require(canonical.wakeDisposition == handoff.wakeDisposition)
        require(canonical.requiresVisibleNotification == handoff.requiresVisibleNotification)
        require(canonical.persistedAcrossReboot == handoff.persistedAcrossReboot)
        envelope
    }

    private fun buildDedupeKey(taskId: String, planId: String): String =
        "agent-continuation:$taskId:$planId"
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
