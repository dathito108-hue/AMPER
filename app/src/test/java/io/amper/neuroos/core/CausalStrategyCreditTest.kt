package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalStrategyCreditTest {
    private val read = CapabilityId("test.read")

    private fun plan(
        id: String,
        status: PlanStepStatus
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase181"),
        goal = "private causal goal",
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("$id-step"),
                capability = read,
                reason = "private causal reason",
                input = "private causal input",
                status = status,
                boundToolId = ToolId("phase181-tool"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase181-test"
    )

    @Test
    fun governanceEnvironmentAndProtocolOutcomesDoNotPoisonExecutionSkill() {
        val memory = InMemoryMemoryOs()
        val model = MemoryBackedStrategyLearningModel(memory)

        model.observe(plan("denied", PlanStepStatus.DENIED))
        model.observe(plan("unavailable", PlanStepStatus.UNAVAILABLE))
        val snapshot = requireNotNull(model.observe(plan("malformed", PlanStepStatus.MALFORMED)))

        assertEquals(3, snapshot.failures)
        assertEquals(0, snapshot.executionFailures)
        assertEquals(1, snapshot.authorityBlocked)
        assertEquals(1, snapshot.environmentUnavailable)
        assertEquals(1, snapshot.protocolFailures)
        assertEquals(0, snapshot.legacyUnattributedFailures)
        assertEquals(0, snapshot.consecutiveFailures)
        assertEquals(0, snapshot.completedAttempts)
        assertEquals(3, snapshot.nonExecutionBlocks)
        assertEquals(StrategyOutcomeCause.PROTOCOL_FAILURE, snapshot.lastCause)

        val guidance = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(snapshot),
            allowedCapabilities = setOf(read)
        )
        assertTrue(guidance.isEmpty())
    }

    @Test
    fun onlyExecutionFailureBuildsAdaptiveFailureStreak() {
        val model = MemoryBackedStrategyLearningModel(InMemoryMemoryOs())

        val first = requireNotNull(model.observe(plan("failed-1", PlanStepStatus.FAILED)))
        assertEquals(1, first.executionFailures)
        assertEquals(1, first.consecutiveFailures)
        assertEquals(StrategyOutcomeCause.EXECUTION_FAILURE, first.lastCause)

        val authorityNeutral = requireNotNull(model.observe(plan("denied-mid", PlanStepStatus.DENIED)))
        assertEquals(1, authorityNeutral.executionFailures)
        assertEquals(1, authorityNeutral.consecutiveFailures)
        assertEquals(1, authorityNeutral.authorityBlocked)

        val second = requireNotNull(model.observe(plan("failed-2", PlanStepStatus.FAILED)))
        assertEquals(2, second.executionFailures)
        assertEquals(2, second.consecutiveFailures)
        assertEquals(2, second.completedAttempts)

        val guidance = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(second),
            allowedCapabilities = setOf(read)
        ).single()
        assertEquals(StrategyAdaptationMode.RECOVERY_REQUIRED, guidance.adaptationMode)
        assertEquals(2, guidance.executionFailures)
        assertEquals(1, guidance.authorityBlocked)
        assertEquals(StrategyOutcomeCause.EXECUTION_FAILURE, guidance.lastCause)
    }

    @Test
    fun governedSuccessResetsExecutionFailureStreak() {
        val model = MemoryBackedStrategyLearningModel(InMemoryMemoryOs())
        model.observe(plan("failed-1", PlanStepStatus.FAILED))
        model.observe(plan("failed-2", PlanStepStatus.FAILED))

        val success = requireNotNull(model.observe(plan("success", PlanStepStatus.EXECUTED)))

        assertEquals(0, success.consecutiveFailures)
        assertEquals(StrategyOutcomeKind.SUCCEEDED, success.lastOutcome)
        assertEquals(StrategyOutcomeCause.SUCCESS, success.lastCause)
        assertEquals(3, success.completedAttempts)
        assertEquals(1.0 / 3.0, success.completedSuccessRate!!, 0.0001)
    }

    @Test
    fun samePlanIdentityCannotChangeCausalFailureClass() {
        val model = MemoryBackedStrategyLearningModel(InMemoryMemoryOs())
        model.observe(plan("stable-cause", PlanStepStatus.DENIED))

        val changed = runCatching {
            model.observe(plan("stable-cause", PlanStepStatus.UNAVAILABLE))
        }

        assertTrue(changed.isFailure)
        assertTrue(
            changed.exceptionOrNull()?.message.orEmpty()
                .contains("changed terminal strategy cause")
        )
    }

    @Test
    fun v2AggregateFailuresMigrateAsUnattributedInsteadOfInventedExecutionFailures() {
        val memory = InMemoryMemoryOs()
        val signature = StrategySignature(listOf(read))
        memory.remember(
            MemoryRecord(
                id = MemoryId("strategy-evidence:${signature.digest}"),
                kind = "strategy-evidence",
                content =
                    "v=2;capabilities=test.read;successes=3;failures=2;aborted=1;" +
                        "failure_streak=2;last_outcome=FAILED;last=1234",
                importance = 0.76,
                provenance = Provenance(
                    source = "governed-plan-outcome",
                    producer = "strategy-learning-model",
                    confidence = 1.0
                ),
                createdAtEpochMs = 1_234L
            )
        )

        val loaded = requireNotNull(MemoryBackedStrategyLearningModel(memory).snapshot(signature))

        assertEquals(3, loaded.successes)
        assertEquals(2, loaded.failures)
        assertEquals(0, loaded.executionFailures)
        assertEquals(2, loaded.legacyUnattributedFailures)
        assertEquals(0, loaded.consecutiveFailures)
        assertEquals(3, loaded.completedAttempts)
        assertNull(loaded.lastCause)
    }

    @Test
    fun nonExecutionFailureCannotTriggerStrategyRecovery() {
        val runtime = AmperRuntime.reference()
        val first = plan("unavailable-1", PlanStepStatus.UNAVAILABLE)
        val second = plan("unavailable-2", PlanStepStatus.UNAVAILABLE)
        runtime.strategies.observe(first)
        runtime.strategies.observe(second)

        val decision = GovernedStrategyRecovery.assess(
            plan = second,
            evidence = runtime.strategies.snapshot(StrategySignature.from(second)),
            allowedCapabilities = setOf(read)
        )

        assertTrue(!decision.eligible)
        assertEquals(StrategyRecoveryBlockReason.NON_EXECUTION_CAUSE, decision.blockReason)
    }

    @Test
    fun causalGuidanceRenderingContainsOnlyAggregateStructuralEvidence() {
        val snapshot = StrategyEvidenceSnapshot(
            signature = StrategySignature(listOf(read)),
            successes = 4,
            failures = 4,
            aborted = 1,
            consecutiveFailures = 1,
            lastOutcome = StrategyOutcomeKind.FAILED,
            executionFailures = 2,
            authorityBlocked = 1,
            environmentUnavailable = 1,
            protocolFailures = 0,
            legacyUnattributedFailures = 0,
            lastCause = StrategyOutcomeCause.EXECUTION_FAILURE,
            lastObservedAtEpochMs = 2_000L
        )

        val rendered = EvidenceGroundedStrategyGuidance.render(
            EvidenceGroundedStrategyGuidance.select(
                evidence = listOf(snapshot),
                allowedCapabilities = setOf(read)
            )
        )

        assertTrue(rendered.contains("execution_failures=2"))
        assertTrue(rendered.contains("authority_blocked=1"))
        assertTrue(rendered.contains("environment_unavailable=1"))
        assertTrue(rendered.contains("last_cause=EXECUTION_FAILURE"))
        assertTrue(!rendered.contains("private causal goal"))
        assertTrue(!rendered.contains("private causal input"))
        assertTrue(!rendered.contains("private causal reason"))
    }
}
