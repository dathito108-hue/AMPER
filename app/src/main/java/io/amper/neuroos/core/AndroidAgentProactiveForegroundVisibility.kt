package io.amper.neuroos.core

data class AndroidAgentProactiveForegroundVisibilityReport(
    val lifecycle: AndroidAgentProactiveTaskLifecycleReconcileReport,
    val attention: AndroidAgentProactiveAttentionReconcileReport?,
    val attentionReconciled: Boolean
) {
    init {
        require(attentionReconciled == (attention != null))
    }
}

/**
 * Phase669 foreground-entry coordinator over existing Phase662/663 reconciliation ports.
 *
 * It owns no Lifecycle observer, timer, polling loop, scheduler, planner, notification policy,
 * task store, or execution authority. MainActivity invokes it only from the activity ON_RESUME
 * event. Lifecycle reconciliation gates the read-model refresh; notification reconciliation is
 * discoverability-only and cannot gate the canonical lifecycle result.
 */
class AndroidAgentProactiveForegroundVisibilityCoordinator(
    private val reconcileLifecycle:
        () -> Result<AndroidAgentProactiveTaskLifecycleReconcileReport>,
    private val reconcileAttention:
        () -> Result<AndroidAgentProactiveAttentionReconcileReport>
) {
    fun reconcileOnResume(): Result<AndroidAgentProactiveForegroundVisibilityReport> {
        val lifecycle = reconcileLifecycle()
            .getOrElse { return Result.failure(it) }
        val attention = reconcileAttention()
        return Result.success(
            AndroidAgentProactiveForegroundVisibilityReport(
                lifecycle = lifecycle,
                attention = attention.getOrNull(),
                attentionReconciled = attention.isSuccess
            )
        )
    }
}
