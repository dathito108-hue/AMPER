package io.amper.neuroos.core.v2

import io.amper.neuroos.core.PlanId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class AmperAgentProactiveAttentionKind {
    GOVERNED_APPROVAL_REQUIRED,
    COMPLETED,
    FAILED
}

data class AmperAgentProactiveAttentionSignal(
    val planId: PlanId,
    val kind: AmperAgentProactiveAttentionKind,
    val fingerprintSha256: String,
    val waitingApprovalStepIndex: Int? = null
) {
    init {
        require(fingerprintSha256.matches(Regex("[0-9a-f]{64}")))
        require(
            (kind == AmperAgentProactiveAttentionKind.GOVERNED_APPROVAL_REQUIRED) ==
                (waitingApprovalStepIndex != null)
        )
        waitingApprovalStepIndex?.let { require(it > 0) }
    }

    /**
     * Privacy-preserving notification copy. No goal, prompt, input, capability, trigger payload,
     * source configuration, or model/tool detail is exposed outside the governed plan surface.
     */
    val title: String
        get() = when (kind) {
            AmperAgentProactiveAttentionKind.GOVERNED_APPROVAL_REQUIRED ->
                "AMPER needs your review"
            AmperAgentProactiveAttentionKind.COMPLETED ->
                "AMPER proactive task completed"
            AmperAgentProactiveAttentionKind.FAILED ->
                "AMPER proactive task ended"
        }

    val message: String
        get() = when (kind) {
            AmperAgentProactiveAttentionKind.GOVERNED_APPROVAL_REQUIRED ->
                "A proactive task is waiting for governed approval. Open AMPER to inspect the exact plan."
            AmperAgentProactiveAttentionKind.COMPLETED ->
                "A proactive task reached a completed durable state. Open AMPER to inspect its plan."
            AmperAgentProactiveAttentionKind.FAILED ->
                "A proactive task reached a terminal state without full success. Open AMPER to inspect its plan."
        }
}

/**
 * Phase663 pure attention policy.
 *
 * Attention is read-only. It cannot authorize, approve, reject, schedule, advance, execute, or
 * mutate a proactive task. The canonical Phase662 lifecycle view remains its only input.
 */
object AmperAgentProactiveAttentionPolicy {
    fun signal(
        view: AmperAgentProactiveTaskLifecycleView
    ): AmperAgentProactiveAttentionSignal? {
        if (!view.planAvailable) return null

        val state = view.taskState ?: return null
        val kind = when (state) {
            AmperAgentTaskState.WAITING_APPROVAL ->
                AmperAgentProactiveAttentionKind.GOVERNED_APPROVAL_REQUIRED
            AmperAgentTaskState.COMPLETED ->
                AmperAgentProactiveAttentionKind.COMPLETED
            AmperAgentTaskState.FAILED,
            AmperAgentTaskState.CANCELLED ->
                AmperAgentProactiveAttentionKind.FAILED
            AmperAgentTaskState.ADMITTED,
            AmperAgentTaskState.READY,
            AmperAgentTaskState.RUNNING,
            AmperAgentTaskState.CHECKPOINTED ->
                return null
        }

        val step = if (kind == AmperAgentProactiveAttentionKind.GOVERNED_APPROVAL_REQUIRED) {
            requireNotNull(view.waitingApprovalStepIndex)
        } else {
            null
        }
        val fingerprint = sha256(
            buildString {
                append("AMPER_PROACTIVE_ATTENTION_V1|")
                append(view.binding.planId.value)
                append('|')
                append(kind.name)
                append('|')
                append(step ?: "~")
                append('|')
                append(state.name)
            }
        )

        return AmperAgentProactiveAttentionSignal(
            planId = view.binding.planId,
            kind = kind,
            fingerprintSha256 = fingerprint,
            waitingApprovalStepIndex = step
        )
    }

    fun signals(
        views: List<AmperAgentProactiveTaskLifecycleView>
    ): List<AmperAgentProactiveAttentionSignal> =
        views.mapNotNull(::signal)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
