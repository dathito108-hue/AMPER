package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ActionRequestId
import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.ConversationId
import io.amper.neuroos.core.PlanAdvanceResult
import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignPlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentProactiveTriggerSourceTest {
    @Test
    fun scheduledWindowDefinitionIsFiniteOneShot() {
        val result = runCatching {
            definition(
                kind = AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW,
                maxFirings = 2
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun highFrequencyDefinitionIsRejectedBeforeObservation() {
        val result = runCatching {
            definition(
                minimumIntervalMs =
                    AmperAgentProactiveTriggerSourceDefinition.MINIMUM_TRIGGER_INTERVAL_MS - 1L
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun qualifiedObservationBecomesCanonicalEventWakeAdmission() {
        val definition = definition()
        val observation = observation(observedAtEpochMs = 20_000L)

        val qualified = AmperAgentProactiveTriggerSourcePolicy
            .qualify(
                definition = definition,
                observation = observation,
                state = AmperAgentProactiveTriggerSourceState()
            )
            .getOrThrow()

        assertEquals(AmperAgentTaskOrigin.PROACTIVE_TRIGGER, qualified.request.origin)
        assertEquals(OmegaBackgroundMode.EVENT_WAKE, qualified.admission.backgroundMode)
        assertTrue(qualified.admission.checkpointRequired)
        assertEquals(observation.canonicalTrigger(), qualified.request.trigger)
        assertEquals(1, qualified.nextSourceState.fireCount)
        assertTrue(
            observation.payloadDigest in qualified.nextSourceState.consumedPayloadDigests
        )
        assertTrue(qualified.request.taskId.startsWith("proactive:"))
    }

    @Test
    fun observationOutsideConfiguredWindowIsRejected() {
        val definition = definition(
            activeFromEpochMs = 10_000L,
            expiresAtEpochMs = 30_000L
        )

        val result = AmperAgentProactiveTriggerSourcePolicy.qualify(
            definition = definition,
            observation = observation(observedAtEpochMs = 30_001L),
            state = AmperAgentProactiveTriggerSourceState()
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun duplicatePayloadCannotFireAgain() {
        val definition = definition(maxFirings = 2)
        val firstObservation = observation(
            observedAtEpochMs = 20_000L,
            payloadDigest = "a".repeat(64)
        )
        val first = AmperAgentProactiveTriggerSourcePolicy
            .qualify(
                definition,
                firstObservation,
                AmperAgentProactiveTriggerSourceState()
            )
            .getOrThrow()

        val duplicate = AmperAgentProactiveTriggerSourcePolicy.qualify(
            definition = definition,
            observation = observation(
                observedAtEpochMs = 40_000L,
                payloadDigest = "a".repeat(64)
            ),
            state = first.nextSourceState
        )

        assertTrue(duplicate.isFailure)
    }

    @Test
    fun minimumCadenceIsEnforcedBetweenDistinctObservations() {
        val definition = definition(
            maxFirings = 2,
            minimumIntervalMs = 30_000L
        )
        val first = AmperAgentProactiveTriggerSourcePolicy
            .qualify(
                definition,
                observation(
                    observedAtEpochMs = 20_000L,
                    payloadDigest = "a".repeat(64)
                ),
                AmperAgentProactiveTriggerSourceState()
            )
            .getOrThrow()

        val tooSoon = AmperAgentProactiveTriggerSourcePolicy.qualify(
            definition,
            observation(
                observedAtEpochMs = 40_000L,
                payloadDigest = "b".repeat(64)
            ),
            first.nextSourceState
        )

        assertTrue(tooSoon.isFailure)
    }

    @Test
    fun startCreatesOnePersistentPlanAndVerifiedHandoffWithoutAdvance() {
        val definition = definition()
        val qualified = AmperAgentProactiveTriggerSourcePolicy
            .qualify(
                definition,
                observation(observedAtEpochMs = 20_000L),
                AmperAgentProactiveTriggerSourceState()
            )
            .getOrThrow()
        val port = FakePlanPort(
            plan(
                goal = definition.objective,
                capability = CapabilityId("reasoning")
            )
        )
        val proactive = AmperAgentProactiveTaskCoordinator(port) { 100L }
        val eventWake = AmperAgentProactiveEventWakeCoordinator(port) { 100L }
        val coordinator = AmperAgentProactiveTriggerStartCoordinator(
            proactive = proactive,
            eventWake = eventWake
        )

        val started = coordinator
            .start(qualified, ConversationId("conversation-1"))
            .getOrThrow()
        val decoded = AmperAgentEventWakeHandoffPolicy
            .verifyAndDecode(started.handoff)
            .getOrThrow()

        assertEquals(1, port.createCalls)
        assertEquals(0, port.advanceCalls)
        assertEquals(AmperAgentTaskState.CHECKPOINTED, decoded.taskState)
        assertEquals(qualified.trigger, decoded.trigger)
        assertEquals(qualified.request.taskId, decoded.taskId)
        assertTrue(started.handoff.runnable)
    }

    @Test
    fun sourceCannotWidenConfiguredCapabilityEnvelopeDuringPlanStart() {
        val definition = definition()
        val qualified = AmperAgentProactiveTriggerSourcePolicy
            .qualify(
                definition,
                observation(observedAtEpochMs = 20_000L),
                AmperAgentProactiveTriggerSourceState()
            )
            .getOrThrow()
        val port = FakePlanPort(
            plan(
                goal = definition.objective,
                capability = CapabilityId("device.admin")
            )
        )
        val coordinator = AmperAgentProactiveTriggerStartCoordinator(
            proactive = AmperAgentProactiveTaskCoordinator(port),
            eventWake = AmperAgentProactiveEventWakeCoordinator(port)
        )

        val result = coordinator.start(
            qualified,
            ConversationId("conversation-1")
        )

        assertTrue(result.isFailure)
        assertEquals(1, port.createCalls)
        assertEquals(0, port.advanceCalls)
    }

    private fun definition(
        kind: AmperAgentProactiveTriggerSourceKind =
            AmperAgentProactiveTriggerSourceKind.APP_LOCAL_CONDITION,
        minimumIntervalMs: Long =
            AmperAgentProactiveTriggerSourceDefinition.MINIMUM_TRIGGER_INTERVAL_MS,
        maxFirings: Int = 1,
        activeFromEpochMs: Long = 10_000L,
        expiresAtEpochMs: Long = 100_000L
    ) = AmperAgentProactiveTriggerSourceDefinition(
        triggerId = "monitor.example",
        source = "user-configured-app-condition",
        kind = kind,
        objective = "Observe one bounded condition and prepare governed work.",
        allowedCapabilities = setOf(CapabilityId("reasoning")),
        expectedRuntimeMs = 30_000L,
        activeFromEpochMs = activeFromEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        minimumIntervalMs = minimumIntervalMs,
        maxFirings = maxFirings,
        configuredAtEpochMs = 1_000L
    )

    private fun observation(
        observedAtEpochMs: Long,
        payloadDigest: String = "a".repeat(64)
    ) = AmperAgentProactiveTriggerObservation(
        triggerId = "monitor.example",
        source = "user-configured-app-condition",
        observedAtEpochMs = observedAtEpochMs,
        payloadDigest = payloadDigest
    )

    private fun plan(
        goal: String,
        capability: CapabilityId
    ) = SovereignPlan(
        id = PlanId("proactive-plan"),
        conversationId = ConversationId("conversation-1"),
        goal = goal,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("request-1"),
                capability = capability,
                reason = "bounded proactive step",
                input = "event"
            )
        ),
        planningBackendId = "amper-core",
        createdAtEpochMs = 1L
    )

    private class FakePlanPort(
        var current: SovereignPlan
    ) : AmperAgentPersistentPlanPort {
        var createCalls: Int = 0
        var advanceCalls: Int = 0

        override fun create(
            conversationId: ConversationId,
            userGoal: String
        ): Result<SovereignPlan> {
            createCalls += 1
            current = current.copy(
                conversationId = conversationId,
                goal = userGoal
            )
            return Result.success(current)
        }

        override fun load(planId: PlanId): SovereignPlan? =
            current.takeIf { it.id == planId }

        override fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> {
            advanceCalls += 1
            return Result.success(PlanAdvanceResult.Complete(plan))
        }
    }
}
