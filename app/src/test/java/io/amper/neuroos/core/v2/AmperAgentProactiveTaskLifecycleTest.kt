package io.amper.neuroos.core.v2

import io.amper.neuroos.core.ActionRequestId
import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.ConversationId
import io.amper.neuroos.core.FileMemoryJournal
import io.amper.neuroos.core.InMemoryMemoryOs
import io.amper.neuroos.core.PersistentMemoryOs
import io.amper.neuroos.core.PlanAdvanceResult
import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignPlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AmperAgentProactiveTaskLifecycleTest {
    @Test
    fun lifecycleBindingSurvivesJournalReopenAndExactReplayIsIdempotent() {
        val root = Files.createTempDirectory("amper-proactive-lifecycle").toFile()
        val journal = root.resolve("memory.journal")
        val first = MemoryBackedAmperAgentProactiveTaskLifecycleLedger(
            PersistentMemoryOs(FileMemoryJournal(journal))
        )
        val binding = binding()

        assertEquals(binding, first.record(binding).getOrThrow())
        assertEquals(binding, first.record(binding).getOrThrow())
        assertEquals(1, first.recent(8).size)

        val reopened = MemoryBackedAmperAgentProactiveTaskLifecycleLedger(
            PersistentMemoryOs(FileMemoryJournal(journal))
        )
        assertEquals(binding, reopened.findByPlanId(binding.planId))
        assertEquals(listOf(binding), reopened.recent(8))
    }

    @Test
    fun lifecycleIdentityCollisionFailsClosedWithoutReplacingOriginalBinding() {
        val ledger = MemoryBackedAmperAgentProactiveTaskLifecycleLedger(
            InMemoryMemoryOs()
        )
        val binding = binding()
        ledger.record(binding).getOrThrow()

        val drifted = binding.copy(configurationSha256 = "d".repeat(64))
        assertTrue(ledger.record(drifted).isFailure)
        assertEquals(binding, ledger.findByPlanId(binding.planId))
    }

    @Test
    fun lifecycleCoordinatorDerivesStateOnlyFromCanonicalPlan() {
        val ledger = MemoryBackedAmperAgentProactiveTaskLifecycleLedger(
            InMemoryMemoryOs()
        )
        val planPort = FakePlanPort(plan())
        val admissions = AmperAgentTaskAdmissionRegistry()
        val proactive = AmperAgentProactiveTaskCoordinator(planPort) { 100L }
        val wake = AmperAgentProactiveEventWakeCoordinator(planPort) { 100L }
        val lifecycle = AmperAgentProactiveTaskLifecycleCoordinator(
            ledger = ledger,
            plans = planPort,
            admissions = admissions,
            proactive = proactive,
            eventWake = wake,
            clock = { 100L }
        )
        val dispatch = dispatch(planPort, wake)
        lifecycle.recordDispatch(dispatch).getOrThrow()

        val ready = requireNotNull(lifecycle.findByPlanId(dispatch.planId))
        assertEquals(AmperAgentTaskState.CHECKPOINTED, ready.taskState)
        assertEquals(0, ready.completedSteps)
        assertEquals(null, ready.waitingApprovalStepIndex)

        planPort.current = planPort.current.copy(
            steps = planPort.current.steps.map {
                if (it.index == 1) {
                    it.copy(status = PlanStepStatus.REQUIRES_CONFIRMATION)
                } else {
                    it
                }
            }
        )
        val waiting = requireNotNull(lifecycle.findByPlanId(dispatch.planId))
        assertEquals(AmperAgentTaskState.WAITING_APPROVAL, waiting.taskState)
        assertEquals(1, waiting.waitingApprovalStepIndex)
        assertEquals(0, waiting.completedSteps)

        planPort.current = planPort.current.copy(
            steps = planPort.current.steps.map {
                if (it.index == 1) {
                    it.copy(status = PlanStepStatus.EXECUTED)
                } else {
                    it
                }
            }
        )
        val resumed = requireNotNull(
            lifecycle.resumeAfterGovernedDecision(dispatch.planId).getOrThrow()
        )
        val resumedEnvelope = AmperAgentEventWakeHandoffPolicy
            .verifyAndDecode(resumed)
            .getOrThrow()

        assertTrue(resumed.runnable)
        assertEquals(AmperAgentTaskState.CHECKPOINTED, resumedEnvelope.taskState)
        assertEquals(1, resumedEnvelope.completedSteps)
        assertEquals(dispatch.admission.request.trigger, resumedEnvelope.trigger)
    }

    @Test
    fun rejectedFinalStepProducesTerminalNoopAndTerminalCompactionIsBounded() {
        val ledger = MemoryBackedAmperAgentProactiveTaskLifecycleLedger(
            InMemoryMemoryOs()
        )
        val planPort = FakePlanPort(plan(stepCount = 1))
        val admissions = AmperAgentTaskAdmissionRegistry()
        val proactive = AmperAgentProactiveTaskCoordinator(planPort) { 100L }
        val wake = AmperAgentProactiveEventWakeCoordinator(planPort) { 100L }
        val lifecycle = AmperAgentProactiveTaskLifecycleCoordinator(
            ledger = ledger,
            plans = planPort,
            admissions = admissions,
            proactive = proactive,
            eventWake = wake,
            clock = { 100L }
        )
        val dispatch = dispatch(planPort, wake)
        lifecycle.recordDispatch(dispatch).getOrThrow()

        planPort.current = planPort.current.copy(
            steps = planPort.current.steps.map {
                it.copy(status = PlanStepStatus.REJECTED)
            }
        )
        val terminal = requireNotNull(
            lifecycle.resumeAfterGovernedDecision(dispatch.planId).getOrThrow()
        )
        assertFalse(terminal.runnable)
        assertEquals(
            AmperAgentEventWakeDisposition.TERMINAL_NOOP,
            terminal.disposition
        )
        assertEquals(
            AmperAgentTaskState.FAILED,
            requireNotNull(lifecycle.findByPlanId(dispatch.planId)).taskState
        )

        assertEquals(1, lifecycle.compactTerminal(keepRecentTerminal = 0).getOrThrow())
        assertEquals(null, lifecycle.findByPlanId(dispatch.planId))
    }

    @Test
    fun missingCanonicalPlanRemainsVisibleAndFailsClosed() {
        val ledger = MemoryBackedAmperAgentProactiveTaskLifecycleLedger(
            InMemoryMemoryOs()
        )
        val binding = binding()
        ledger.record(binding).getOrThrow()
        val planPort = FakePlanPort(null)
        val lifecycle = AmperAgentProactiveTaskLifecycleCoordinator(
            ledger = ledger,
            plans = planPort,
            admissions = AmperAgentTaskAdmissionRegistry(),
            proactive = AmperAgentProactiveTaskCoordinator(planPort),
            eventWake = AmperAgentProactiveEventWakeCoordinator(planPort)
        )

        val view = lifecycle.inspect(8).single()
        assertFalse(view.planAvailable)
        assertEquals(null, view.taskState)
        assertTrue(lifecycle.currentHandoff(binding.planId).isFailure)
        assertEquals(binding, ledger.findByPlanId(binding.planId))
    }

    private fun dispatch(
        planPort: FakePlanPort,
        wake: AmperAgentProactiveEventWakeCoordinator
    ): AmperAgentPendingTriggerDispatchBinding {
        val binding = binding()
        val admission = admission(binding)
        val plan = requireNotNull(planPort.load(binding.planId))
        val checkpoint = AmperAgentPlanTaskCheckpoint(
            taskId = binding.taskId,
            planId = binding.planId,
            backgroundMode = OmegaBackgroundMode.EVENT_WAKE,
            taskState = AmperAgentTaskState.CHECKPOINTED,
            completedSteps = 0,
            totalSteps = plan.steps.size,
            updatedAtEpochMs = 100L
        )
        val handoff = wake.handoff(admission, checkpoint).getOrThrow()
        return AmperAgentPendingTriggerDispatchBinding(
            sourceId = binding.sourceId,
            configurationSha256 = binding.configurationSha256,
            observationIdentitySha256 = binding.observationIdentitySha256,
            planId = binding.planId,
            admission = admission,
            checkpoint = checkpoint,
            handoff = handoff
        )
    }

    private fun binding(): AmperAgentProactiveTaskLifecycleBinding {
        val observationIdentity = "a".repeat(64)
        val sourceId = "monitor.example"
        return AmperAgentProactiveTaskLifecycleBinding(
            sourceId = sourceId,
            configurationSha256 = "c".repeat(64),
            observationIdentitySha256 = observationIdentity,
            taskId = "proactive:" + observationIdentity.take(48),
            planId = AmperAgentPendingTriggerDispatchCoordinator
                .deterministicPlanId(observationIdentity),
            trigger = AmperAgentTrigger(
                triggerId = sourceId,
                source = "user-configured:app_local_event:1234567890abcdef:cdef0123456789ab",
                observedAtEpochMs = 10L,
                payloadDigest = "b".repeat(64)
            ),
            boundAtEpochMs = 100L
        )
    }

    private fun admission(
        binding: AmperAgentProactiveTaskLifecycleBinding
    ): AmperAgentTaskAdmission =
        AmperAgentTaskAdmissionPolicy.admit(
            AmperAgentTaskRequest(
                taskId = binding.taskId,
                origin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER,
                objective = "Handle one bounded proactive event.",
                allowedCapabilities = setOf(CapabilityId("reasoning")),
                expectedRuntimeMs = 1_000L,
                mustSurviveUiExit = true,
                canBeDeferred = true,
                createdAtEpochMs = binding.trigger.observedAtEpochMs,
                trigger = binding.trigger
            )
        )

    private fun plan(stepCount: Int = 2): SovereignPlan {
        val id = AmperAgentPendingTriggerDispatchCoordinator
            .deterministicPlanId("a".repeat(64))
        return SovereignPlan(
            id = id,
            conversationId = ConversationId("primary"),
            goal = "Handle one bounded proactive event.",
            steps = (1..stepCount).map { index ->
                SovereignPlanStep(
                    index = index,
                    requestId = ActionRequestId("request-$index"),
                    capability = CapabilityId("reasoning"),
                    reason = "step $index",
                    input = "input-$index"
                )
            },
            planningBackendId = "amper-core",
            createdAtEpochMs = 10L
        )
    }

    private class FakePlanPort(
        var current: SovereignPlan?
    ) : AmperAgentPersistentPlanPort {
        override fun create(
            conversationId: ConversationId,
            userGoal: String
        ): Result<SovereignPlan> =
            Result.failure(UnsupportedOperationException("not used"))

        override fun load(planId: PlanId): SovereignPlan? =
            current?.takeIf { it.id == planId }

        override fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> =
            Result.failure(UnsupportedOperationException("not used"))
    }
}
