package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ConversationId
import io.amper.neuroos.core.PlanId

data class AmperAgentPendingTriggerDispatchBinding(
    val sourceId: String,
    val configurationSha256: String,
    val observationIdentitySha256: String,
    val planId: PlanId,
    val admission: AmperAgentTaskAdmission,
    val checkpoint: AmperAgentPlanTaskCheckpoint,
    val handoff: AmperAgentEventWakeHandoff
) {
    init {
        require(sourceId == admission.request.trigger?.triggerId)
        require(configurationSha256.matches(Regex("[0-9a-f]{64}")))
        require(planId == checkpoint.planId)
        require(checkpoint.taskId == admission.request.taskId)
        require(handoff.taskId == admission.request.taskId)
        require(handoff.planId == planId.value)
        require(handoff.triggerId == sourceId)
    }

    val requiresEventWakeSchedule: Boolean
        get() = handoff.runnable
}

/**
 * Phase661 durable bridge from the oldest Phase660 accepted observation to the existing canonical
 * proactive task / persistent plan / verified EVENT_WAKE path.
 *
 * It creates no plan implementation, scheduler, tool path, or authority. Plan identity is derived
 * only from the persisted observation identity so a crash/retry can rediscover the same durable plan.
 */
class AmperAgentPendingTriggerDispatchCoordinator(
    private val registry: AmperAgentProactiveTriggerSourceRegistry,
    private val admissions: AmperAgentTaskAdmissionRegistry,
    private val proactive: AmperAgentProactiveTaskCoordinator,
    private val eventWake: AmperAgentProactiveEventWakeCoordinator
) {
    fun bindOldest(
        sourceId: String,
        conversationId: ConversationId
    ): Result<AmperAgentPendingTriggerDispatchBinding?> = runCatching {
        val state = registry.get(sourceId)
            ?: return@runCatching null
        require(state.source.enabled) {
            "disabled proactive trigger source cannot dispatch pending observations"
        }

        val pending = state.pendingObservations.firstOrNull()
            ?: return@runCatching null

        // Phase660 prevents revision/kind changes while pending observations exist. Requalify from
        // the exact persisted source + observation instead of trusting process-local admission RAM.
        val qualified = AmperAgentProactiveTriggerSourcePolicy
            .qualify(
                source = state.source,
                observation = pending.observation,
                lastAcceptedObservationAtEpochMs = null
            )
            .getOrThrow()
        require(
            qualified.observationIdentitySha256 ==
                pending.observationIdentitySha256
        ) {
            "pending proactive observation identity drifted from source configuration"
        }

        val admission = qualified.admission
        admissions.register(admission)

        val planId = deterministicPlanId(pending.observationIdentitySha256)
        val started = proactive
            .startBound(
                admission = admission,
                conversationId = conversationId,
                planId = planId
            )
            .getOrThrow()

        val handoff = eventWake
            .handoff(admission, started.checkpoint)
            .getOrThrow()

        // Prove the produced transport contract before returning it to an Android scheduler.
        val decoded = AmperAgentEventWakeHandoffPolicy
            .verifyAndDecode(handoff)
            .getOrThrow()
        require(decoded.planId == planId)
        require(decoded.trigger == admission.request.trigger)
        require(decoded.planStateSha256.length == 64)

        AmperAgentPendingTriggerDispatchBinding(
            sourceId = sourceId,
            configurationSha256 = state.source.configurationSha256,
            observationIdentitySha256 = pending.observationIdentitySha256,
            planId = planId,
            admission = admission,
            checkpoint = started.checkpoint,
            handoff = handoff
        )
    }

    companion object {
        fun deterministicPlanId(observationIdentitySha256: String): PlanId {
            require(observationIdentitySha256.matches(Regex("[0-9a-f]{64}")))
            return PlanId("agent-trigger-plan:$observationIdentitySha256")
        }
    }
}
