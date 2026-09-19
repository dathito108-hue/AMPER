package io.amper.neuroos.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousGoalSchedulerTest {
    private val conversationId = ConversationId("phase296-conversation")

    @Test
    fun resourceGatePreventsCognitiveWorkAndAppliesBackoff() {
        var now = 1_000L
        var allowed = false
        var calls = 0
        val scheduler = AutonomousGoalScheduler(
            runGoal = {
                calls += 1
                Result.success(PersistentGoalExecutiveResult.NoGoal())
            },
            resourceAllowed = { allowed },
            clock = { now }
        )

        val gated = scheduler.tick(conversationId)
        assertEquals(AutonomousGoalSchedulerStage.RESOURCE_GATED, gated.stage)
        assertEquals(AutonomousGoalScheduler.RESOURCE_RETRY_MS, gated.nextDelayMs)
        assertEquals(0, calls)

        val throttled = scheduler.tick(conversationId)
        assertEquals(AutonomousGoalSchedulerStage.THROTTLED, throttled.stage)
        assertEquals(0, calls)

        now += AutonomousGoalScheduler.RESOURCE_RETRY_MS
        allowed = true
        val resumed = scheduler.tick(conversationId)
        assertEquals(AutonomousGoalSchedulerStage.NO_GOAL, resumed.stage)
        assertEquals(1, calls)
        assertFalse(resumed.authorityBearing)
    }

    @Test
    fun waitingObservationUsesFastBoundedRetryAndPreservesCheckpointState() {
        var now = 10_000L
        var calls = 0
        val checkpoint = checkpoint(
            stage = PersistentGoalExecutiveStage.WAITING_OBSERVATION,
            action = CognitiveExecutiveAction.OBSERVE,
            planId = null
        )
        val observationCycle = CognitiveExecutiveCycleResult.ObservationRequired(
            directive = directive(CognitiveExecutiveAction.OBSERVE),
            staleRelevantModalities = setOf(PerceptionModality.SENSOR),
            unresolvedBeliefCount = 0,
            acquisitionFailureCode = "OBSERVATION_UNAVAILABLE"
        )
        val ran = PersistentGoalExecutiveResult.Ran(
            checkpoint = checkpoint,
            run = CognitiveExecutiveRunResult(listOf(observationCycle))
        )
        val scheduler = AutonomousGoalScheduler(
            runGoal = {
                calls += 1
                Result.success(ran)
            },
            resourceAllowed = { true },
            clock = { now }
        )

        val first = scheduler.tick(conversationId)
        assertEquals(AutonomousGoalSchedulerStage.RAN, first.stage)
        assertEquals(
            PersistentGoalExecutiveStage.WAITING_OBSERVATION,
            first.checkpointStage
        )
        assertEquals(CognitiveExecutiveAction.OBSERVE, first.action)
        assertEquals(AutonomousGoalScheduler.OBSERVATION_RETRY_MS, first.nextDelayMs)
        assertEquals(1, calls)

        val early = scheduler.tick(conversationId)
        assertEquals(AutonomousGoalSchedulerStage.THROTTLED, early.stage)
        assertEquals(1, calls)

        now += AutonomousGoalScheduler.OBSERVATION_RETRY_MS
        val second = scheduler.tick(conversationId)
        assertEquals(AutonomousGoalSchedulerStage.RAN, second.stage)
        assertEquals(2, calls)
    }

    @Test
    fun plannedCheckpointIsReportedWithoutDuplicateGoalRunDuringBackoff() {
        var now = 20_000L
        var calls = 0
        val planId = PlanId("phase298-plan")
        val checkpoint = checkpoint(
            stage = PersistentGoalExecutiveStage.PLANNED,
            action = CognitiveExecutiveAction.PLAN,
            planId = planId
        )
        val scheduler = AutonomousGoalScheduler(
            runGoal = {
                calls += 1
                Result.success(
                    PersistentGoalExecutiveResult.Deferred(
                        checkpoint = checkpoint,
                        reason = "planned handoff is unresolved"
                    )
                )
            },
            resourceAllowed = { true },
            clock = { now }
        )

        val first = scheduler.tick(conversationId)
        assertEquals(AutonomousGoalSchedulerStage.DEFERRED, first.stage)
        assertEquals(PersistentGoalExecutiveStage.PLANNED, first.checkpointStage)
        assertEquals(planId, first.planId)
        assertEquals(AutonomousGoalScheduler.PLANNED_RETRY_MS, first.nextDelayMs)
        assertEquals(1, calls)

        now += 1_000L
        val early = scheduler.tick(conversationId)
        assertEquals(AutonomousGoalSchedulerStage.THROTTLED, early.stage)
        assertEquals(1, calls)
    }

    @Test
    fun overlappingTickReturnsBusyInsteadOfStartingCompetingCognition() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        var firstResult: AutonomousGoalSchedulerTick? = null
        val scheduler = AutonomousGoalScheduler(
            runGoal = {
                calls += 1
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS)) {
                    "test did not release first scheduler tick"
                }
                Result.success(PersistentGoalExecutiveResult.NoGoal())
            },
            resourceAllowed = { true },
            clock = { 30_000L }
        )

        val worker = Thread {
            firstResult = scheduler.tick(conversationId)
        }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val competing = scheduler.tick(conversationId)
        assertEquals(AutonomousGoalSchedulerStage.BUSY, competing.stage)
        assertEquals(AutonomousGoalScheduler.BUSY_RETRY_MS, competing.nextDelayMs)

        release.countDown()
        worker.join(2_000L)
        assertFalse(worker.isAlive)
        assertEquals(1, calls)
        assertEquals(AutonomousGoalSchedulerStage.NO_GOAL, requireNotNull(firstResult).stage)
    }

    @Test
    fun processLoopHasExplicitStartStopLifecycle() {
        val scheduler = AutonomousGoalScheduler(
            runGoal = { Result.success(PersistentGoalExecutiveResult.NoGoal()) },
            resourceAllowed = { true },
            clock = { System.currentTimeMillis() }
        )
        val loop = ProcessResidentAutonomyLoop(
            scheduler = scheduler,
            conversationId = { conversationId }
        )

        assertTrue(loop.start())
        assertFalse(loop.start())
        assertTrue(loop.isActive())
        assertTrue(loop.stop())
        assertFalse(loop.stop())
        assertFalse(loop.isActive())
        loop.close()
    }

    private fun checkpoint(
        stage: PersistentGoalExecutiveStage,
        action: CognitiveExecutiveAction?,
        planId: PlanId?
    ): PersistentGoalExecutiveCheckpoint = PersistentGoalExecutiveCheckpoint(
        sourceGoalId = "phase296-goal",
        objective = "Run the bounded autonomous goal scheduler",
        conversationId = conversationId,
        priority = 0.8,
        stage = stage,
        attemptCount = 1,
        lastAction = action,
        lastCognitiveStateDigest = "a".repeat(64),
        lastExecutionContextDigest = "b".repeat(64),
        plannedPlanId = planId,
        updatedAtEpochMs = 1L
    )

    private fun directive(
        action: CognitiveExecutiveAction
    ): CognitiveExecutiveDirective = CognitiveExecutiveDirective(
        cognitiveStateDigest = "c".repeat(64),
        executionContextDigest = "d".repeat(64),
        action = action,
        overallReadiness = 0.3,
        uncertainty = 0.8,
        learningPressure = 0.2,
        rationale = "phase296 bounded scheduler test"
    )
}
