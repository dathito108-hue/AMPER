package io.amper.neuroos.core

import android.content.Context
import io.amper.neuroos.core.v2.AmperAgentProactiveTaskLifecycleCoordinator

data class AndroidAgentProactiveTaskLifecycleReconcileReport(
    val tracked: Int,
    val runnableScheduled: Int,
    val nonRunnableCancelled: Int,
    val missingPlans: Int
) {
    init {
        require(tracked >= 0)
        require(runnableScheduled >= 0)
        require(nonRunnableCancelled >= 0)
        require(missingPlans >= 0)
        require(runnableScheduled + nonRunnableCancelled + missingPlans <= tracked)
    }
}

/**
 * Phase662 Android adapter around the existing Phase657 EVENT_WAKE scheduler.
 *
 * No new scheduler, worker, planner, approval path, or executor is introduced. The lifecycle core
 * reconstructs a verified Phase655 handoff from canonical persistent plan state; this adapter only
 * installs/cancels that handoff through AndroidAgentEventWakeScheduler.
 */
class AndroidAgentProactiveTaskLifecycleController(
    context: Context,
    private val lifecycle: AmperAgentProactiveTaskLifecycleCoordinator,
    governor: ResourceGovernor = AndroidResourceGovernor(context)
) {
    private val scheduler = AndroidAgentEventWakeScheduler(
        context.applicationContext,
        governor
    )

    fun reconcilePlan(planId: PlanId): Result<Boolean?> = runCatching {
        val handoff = lifecycle.currentHandoff(planId).getOrThrow()
            ?: return@runCatching null
        val scheduled = scheduler.handoff(handoff).getOrThrow()
        require(scheduled == handoff.runnable) {
            "Phase657 scheduler result drifted from proactive lifecycle disposition"
        }
        scheduled
    }

    /**
     * Call only after planner.approve/planner.reject has durably completed on the existing governed
     * plan surface. The lifecycle coordinator performs no approval and emits only the next handoff.
     */
    fun resumeAfterGovernedDecision(planId: PlanId): Result<Boolean?> = runCatching {
        val handoff = lifecycle
            .resumeAfterGovernedDecision(planId)
            .getOrThrow()
            ?: return@runCatching null
        val scheduled = scheduler.handoff(handoff).getOrThrow()
        require(scheduled == handoff.runnable) {
            "Phase657 scheduler result drifted after governed proactive decision"
        }
        scheduled
    }

    fun reconcileTracked(limit: Int = 32): Result<AndroidAgentProactiveTaskLifecycleReconcileReport> =
        runCatching {
            require(limit in 1..64)
            val views = lifecycle.inspect(limit)
            var scheduled = 0
            var cancelled = 0
            var missing = 0
            views.forEach { view ->
                if (!view.planAvailable) {
                    missing += 1
                } else {
                    when (reconcilePlan(view.binding.planId).getOrThrow()) {
                        true -> scheduled += 1
                        false -> cancelled += 1
                        null -> error("tracked proactive plan lost lifecycle binding")
                    }
                }
            }
            AndroidAgentProactiveTaskLifecycleReconcileReport(
                tracked = views.size,
                runnableScheduled = scheduled,
                nonRunnableCancelled = cancelled,
                missingPlans = missing
            )
        }
}
