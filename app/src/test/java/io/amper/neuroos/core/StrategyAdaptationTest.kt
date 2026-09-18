package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyAdaptationTest {
    private val read = CapabilityId("test.read")
    private val write = CapabilityId("test.write")

    private fun plan(
        id: String,
        status: PlanStepStatus,
        capability: CapabilityId = read
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase177-adaptation"),
        goal = "private-goal-$id",
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("$id-step"),
                capability = capability,
                reason = "private-reason-$id",
                input = "private-input-$id",
                status = status,
                boundToolId = ToolId("phase177-tool"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase177-test"
    )

    @Test
    fun governedOutcomesBuildFailureStreakAbortIsNeutralAndSuccessRecovers() {
        val memory = InMemoryMemoryOs()
        var now = 1_000L
        val model = MemoryBackedStrategyLearningModel(memory) { now++ }
        val signature = StrategySignature(listOf(read))

        val firstFailure = requireNotNull(model.observe(plan("failure-1", PlanStepStatus.FAILED)))
        assertEquals(1, firstFailure.consecutiveFailures)
        assertEquals(StrategyOutcomeKind.FAILED, firstFailure.lastOutcome)

        val aborted = requireNotNull(model.observe(plan("abort", PlanStepStatus.REJECTED)))
        assertEquals(1, aborted.consecutiveFailures)
        assertEquals(StrategyOutcomeKind.ABORTED, aborted.lastOutcome)

        val secondFailure = requireNotNull(model.observe(plan("failure-2", PlanStepStatus.FAILED)))
        assertEquals(2, secondFailure.consecutiveFailures)
        assertEquals(StrategyOutcomeKind.FAILED, secondFailure.lastOutcome)

        val adapted = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(secondFailure),
            allowedCapabilities = setOf(read)
        ).single()
        assertEquals(StrategyAdaptationMode.RECOVERY_REQUIRED, adapted.adaptationMode)
        assertEquals(0.45, adapted.adaptationMultiplier, 0.0001)

        val recovered = requireNotNull(model.observe(plan("success", PlanStepStatus.EXECUTED)))
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(StrategyOutcomeKind.SUCCEEDED, recovered.lastOutcome)

        val restored = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(recovered),
            allowedCapabilities = setOf(read)
        ).single()
        assertEquals(StrategyAdaptationMode.STABLE, restored.adaptationMode)
        assertEquals(1.0, restored.adaptationMultiplier, 0.0001)
        assertTrue(restored.evidenceSupport > adapted.evidenceSupport)
    }

    @Test
    fun recentFailurePressureCanDeprioritizeHistoricallyStrongStrategy() {
        val historicallyStrongButFailing = StrategyEvidenceSnapshot(
            signature = StrategySignature(listOf(read)),
            successes = 8,
            failures = 2,
            consecutiveFailures = 2,
            lastOutcome = StrategyOutcomeKind.FAILED,
            lastObservedAtEpochMs = 2_000L
        )
        val steadyAlternative = StrategyEvidenceSnapshot(
            signature = StrategySignature(listOf(write)),
            successes = 3,
            failures = 1,
            consecutiveFailures = 0,
            lastOutcome = StrategyOutcomeKind.SUCCEEDED,
            lastObservedAtEpochMs = 1_900L
        )

        val selected = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(historicallyStrongButFailing, steadyAlternative),
            allowedCapabilities = setOf(read, write)
        )

        assertEquals(listOf(write), selected[0].signature.capabilities)
        assertEquals(StrategyAdaptationMode.STABLE, selected[0].adaptationMode)
        assertEquals(listOf(read), selected[1].signature.capabilities)
        assertEquals(StrategyAdaptationMode.RECOVERY_REQUIRED, selected[1].adaptationMode)
        assertTrue(selected[0].evidenceSupport > selected[1].evidenceSupport)
        assertTrue(selected[1].baseEvidenceSupport > selected[0].baseEvidenceSupport)
    }

    @Test
    fun legacyV1EvidenceLoadsWithNeutralAdaptationState() {
        val memory = InMemoryMemoryOs()
        val signature = StrategySignature(listOf(read))
        memory.remember(
            MemoryRecord(
                id = MemoryId("strategy-evidence:${signature.digest}"),
                kind = "strategy-evidence",
                content = "v=1;capabilities=test.read;successes=3;failures=1;aborted=2;last=1234",
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
        assertEquals(1, loaded.failures)
        assertEquals(2, loaded.aborted)
        assertEquals(0, loaded.consecutiveFailures)
        assertNull(loaded.lastOutcome)

        val guidance = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(loaded),
            allowedCapabilities = setOf(read)
        ).single()
        assertEquals(StrategyAdaptationMode.STABLE, guidance.adaptationMode)
        assertEquals(1.0, guidance.adaptationMultiplier, 0.0001)
    }

    @Test
    fun renderingExposesOnlyBoundedAdaptationMetadata() {
        val snapshot = StrategyEvidenceSnapshot(
            signature = StrategySignature(listOf(read)),
            successes = 4,
            failures = 2,
            aborted = 1,
            consecutiveFailures = 1,
            lastOutcome = StrategyOutcomeKind.FAILED,
            lastObservedAtEpochMs = 2_000L
        )
        val candidate = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(snapshot),
            allowedCapabilities = setOf(read)
        ).single()

        val rendered = EvidenceGroundedStrategyGuidance.render(listOf(candidate))

        assertTrue(rendered.contains("adaptation=CAUTION"))
        assertTrue(rendered.contains("consecutive_failures=1"))
        assertTrue(rendered.contains("last_outcome=FAILED"))
        assertTrue(rendered.contains("base_evidence_support="))
        assertTrue(rendered.contains("adaptation_multiplier=0.700"))
        assertTrue(rendered.contains("evidence_support="))
        assertTrue(!rendered.contains("private-goal"))
        assertTrue(!rendered.contains("private-input"))
        assertTrue(!rendered.contains("private-reason"))
    }
}
