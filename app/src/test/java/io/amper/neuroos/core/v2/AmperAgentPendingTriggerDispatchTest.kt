package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ActionRequestId
import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.ConversationId
import io.amper.neuroos.core.InMemoryMemoryOs
import io.amper.neuroos.core.PlanAdvanceResult
import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignPlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentPendingTriggerDispatchTest {
    @Test
    fun repeatedBindingReusesDeterministicPlanAndSameVerifiedHandoff() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val source = source()
        registry.upsert(source, updatedAtEpochMs = 1L)
        val accepted = registry.accept(observation()).getOrThrow()

        val plans = FakePlanPort()
        val proactive = AmperAgentProactiveTaskCoordinator(plans) { 100L }
        val eventWake = AmperAgentProactiveEventWakeCoordinator(plans) { 200L }
        val binder = AmperAgentPendingTriggerDispatchCoordinator(
            registry = registry,
            admissions = AmperAgentTaskAdmissionRegistry(),
            proactive = proactive,
            eventWake = eventWake
        )

        val first = binder
            .bindOldest(source.sourceId, ConversationId("primary"))
            .getOrThrow()
        val second = binder
            .bindOldest(source.sourceId, ConversationId("primary"))
            .getOrThrow()

        val expectedPlanId =
            AmperAgentPendingTriggerDispatchCoordinator.deterministicPlanId(
                accepted.qualified.observationIdentitySha256
            )
        assertEquals(expectedPlanId, first?.planId)
        assertEquals(expectedPlanId, second?.planId)
        assertEquals(1, plans.createdDurablePlans)
        assertEquals(first?.handoff, second?.handoff)
        assertTrue(first?.requiresEventWakeSchedule == true)
        assertEquals(1, registry.pending(source.sourceId).size)
    }

    @Test
    fun retryAfterPlanReachedApprovalProducesNonRunnableApprovalHandoff() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val source = source()
        registry.upsert(source, updatedAtEpochMs = 1L)
        registry.accept(observation()).getOrThrow()

        val plans = FakePlanPort()
        val proactive = AmperAgentProactiveTaskCoordinator(plans) { 100L }
        val eventWake = AmperAgentProactiveEventWakeCoordinator(plans) { 200L }
        val binder = AmperAgentPendingTriggerDispatchCoordinator(
            registry = registry,
            admissions = AmperAgentTaskAdmissionRegistry(),
            proactive = proactive,
            eventWake = eventWake
        )

        val first = requireNotNull(
            binder.bindOldest(source.sourceId, ConversationId("primary")).getOrThrow()
        )
        plans.current = requireNotNull(plans.current).copy(
            steps = requireNotNull(plans.current).steps.map {
                it.copy(status = PlanStepStatus.REQUIRES_CONFIRMATION)
            }
        )

        val retried = requireNotNull(
            binder.bindOldest(source.sourceId, ConversationId("primary")).getOrThrow()
        )

        assertEquals(first.planId, retried.planId)
        assertEquals(1, plans.createdDurablePlans)
        assertFalse(retried.requiresEventWakeSchedule)
        assertEquals(
            AmperAgentEventWakeDisposition.WAITING_GOVERNED_APPROVAL,
            retried.handoff.disposition
        )
        assertEquals(AmperAgentTaskState.WAITING_APPROVAL, retried.checkpoint.taskState)
        assertEquals(1, registry.pending(source.sourceId).size)
    }

    @Test
    fun bindingFailsClosedWhenPendingIdentityNoLongerMatchesSourceRevision() {
        val registry = FakeRegistry(
            AmperAgentPersistedProactiveTriggerSource(
                source = source(configurationSha256 = "b".repeat(64)),
                lastAcceptedObservationAtEpochMs = 1_000_000L,
                lastAcceptedObservationIdentitySha256 = "f".repeat(64),
                pendingObservations = listOf(
                    AmperAgentPendingProactiveTriggerObservation(
                        observation = observation(),
                        observationIdentitySha256 = "f".repeat(64)
                    )
                ),
                updatedAtEpochMs = 1_000_000L
            )
        )
        val plans = FakePlanPort()
        val binder = AmperAgentPendingTriggerDispatchCoordinator(
            registry = registry,
            admissions = AmperAgentTaskAdmissionRegistry(),
            proactive = AmperAgentProactiveTaskCoordinator(plans),
            eventWake = AmperAgentProactiveEventWakeCoordinator(plans)
        )

        val result = binder.bindOldest("monitor.example", ConversationId("primary"))

        assertTrue(result.isFailure)
        assertEquals(0, plans.createdDurablePlans)
    }

    @Test
    fun disabledSourceDoesNotBindPendingWork() {
        val registry = MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
            InMemoryMemoryOs()
        )
        val source = source()
        registry.upsert(source, updatedAtEpochMs = 1L)
        registry.accept(observation()).getOrThrow()
        registry.setEnabled(source.sourceId, false).getOrThrow()

        val plans = FakePlanPort()
        val binder = AmperAgentPendingTriggerDispatchCoordinator(
            registry = registry,
            admissions = AmperAgentTaskAdmissionRegistry(),
            proactive = AmperAgentProactiveTaskCoordinator(plans),
            eventWake = AmperAgentProactiveEventWakeCoordinator(plans)
        )

        val result = binder.bindOldest(source.sourceId, ConversationId("primary"))

        assertTrue(result.isFailure)
        assertEquals(0, plans.createdDurablePlans)
    }

    private fun source(
        configurationSha256: String = "a".repeat(64)
    ) = AmperAgentProactiveTriggerSource(
        sourceId = "monitor.example",
        configurationId = "config.example",
        kind = AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT,
        objective = "Handle one bounded proactive observation.",
        allowedCapabilities = setOf(CapabilityId("reasoning")),
        expectedRuntimeMs = 30_000L,
        minimumIntervalMs =
            AmperAgentProactiveTriggerSource.APP_LOCAL_EVENT_MIN_INTERVAL_MS,
        configurationSha256 = configurationSha256,
        userConfigured = true,
        enabled = true
    )

    private fun observation() = AmperAgentProactiveTriggerObservation(
        sourceId = "monitor.example",
        observedAtEpochMs = 1_000_000L,
        payloadDigest = "d".repeat(64)
    )

    private class FakePlanPort : AmperAgentPersistentPlanPort {
        var current: SovereignPlan? = null
        var createdDurablePlans: Int = 0

        override fun create(
            conversationId: ConversationId,
            userGoal: String
        ): Result<SovereignPlan> =
            Result.failure(UnsupportedOperationException("unbound create not expected"))

        override fun createBound(
            conversationId: ConversationId,
            userGoal: String,
            planId: PlanId
        ): Result<SovereignPlan> = runCatching {
            current?.let {
                require(it.id == planId)
                require(it.conversationId == conversationId)
                require(it.goal == userGoal)
                return@runCatching it
            }
            createdDurablePlans += 1
            plan(
                id = planId,
                conversationId = conversationId,
                goal = userGoal
            ).also { current = it }
        }

        override fun load(planId: PlanId): SovereignPlan? =
            current?.takeIf { it.id == planId }

        override fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> =
            Result.success(PlanAdvanceResult.Complete(plan))

        private fun plan(
            id: PlanId,
            conversationId: ConversationId,
            goal: String
        ) = SovereignPlan(
            id = id,
            conversationId = conversationId,
            goal = goal,
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId("request-1"),
                    capability = CapabilityId("reasoning"),
                    reason = "test",
                    input = "test"
                )
            ),
            planningBackendId = "amper-core",
            createdAtEpochMs = 1L
        )
    }

    private class FakeRegistry(
        private val state: AmperAgentPersistedProactiveTriggerSource
    ) : AmperAgentProactiveTriggerSourceRegistry {
        override fun upsert(
            source: AmperAgentProactiveTriggerSource,
            updatedAtEpochMs: Long
        ): AmperAgentPersistedProactiveTriggerSource = error("unused")

        override fun get(sourceId: String): AmperAgentPersistedProactiveTriggerSource? =
            state.takeIf { it.source.sourceId == sourceId }

        override fun list(limit: Int): List<AmperAgentPersistedProactiveTriggerSource> =
            listOf(state).take(limit)

        override fun setEnabled(
            sourceId: String,
            enabled: Boolean,
            updatedAtEpochMs: Long
        ): Result<AmperAgentPersistedProactiveTriggerSource> = error("unused")

        override fun remove(sourceId: String): Boolean = false

        override fun pending(
            sourceId: String
        ): List<AmperAgentPendingProactiveTriggerObservation> =
            state.pendingObservations

        override fun acknowledgePending(
            sourceId: String,
            observationIdentitySha256: String,
            updatedAtEpochMs: Long
        ): Result<AmperAgentPersistedProactiveTriggerSource> = error("unused")

        override fun accept(
            observation: AmperAgentProactiveTriggerObservation,
            acceptedAtEpochMs: Long
        ): Result<AmperAgentAcceptedProactiveTriggerObservation> = error("unused")
    }
}
