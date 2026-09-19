package io.amper.neuroos.core

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalTransferCalibrationTest {
    private val capability = CapabilityId("phase346.read")
    private val digest = "a".repeat(64)

    @Test
    fun verifiedSuccessRaisesCalibratedSupportAndPersistsIdempotently() {
        val memory = InMemoryMemoryOs()
        val model = MemoryBackedGoalTransferCalibrationModel(memory)
        val plan = terminalBoundPlan(
            id = "phase346-success",
            status = PlanStepStatus.EXECUTED
        )

        val first = model.observe(
            plan = plan,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            observedAtEpochMs = 1_000L
        )
        val second = model.observe(
            plan = plan,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            observedAtEpochMs = 2_000L
        )

        assertNotNull(first)
        assertEquals(first, second)
        assertEquals(1, first!!.verifiedSuccesses)
        assertEquals(0, first.executionFailures)
        assertEquals(0, first.consecutiveFailures)

        val candidate = transferCandidate(latestObservedAt = 1_000L)
        val adjustment = GoalTransferCalibrationPolicy.adjust(
            candidate = candidate,
            snapshot = first,
            nowEpochMs = 2_000L
        )
        assertTrue(adjustment.multiplier > 1.0)
        assertTrue(adjustment.calibratedSupport > candidate.transferSupport)
        assertFalse(adjustment.suppressedByFailureStreak)
        val restarted = MemoryBackedGoalTransferCalibrationModel(memory)
        assertEquals(first, restarted.snapshot(StrategySignature.from(plan)))
    }

    @Test
    fun legacyV6PlanRemainsReadableWithoutInventingTransferAttribution() {
        fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        val content = listOf(
            "AMPER_PLAN_STATE_V6",
            "ID\t" + enc("phase348-v6-plan"),
            "CONVERSATION\t" + enc("phase348-v6-conversation"),
            "GOAL\t" + enc("legacy continuity goal"),
            "BACKEND\t" + enc("phase348-v6-backend"),
            "CREATED\t55",
            "MODEL\t~",
            "CAPABILITIES\t~",
            "PARENT_PLAN\t~",
            "RECOVERY_DEPTH\t0",
            "DELIBERATION_COUNT\t1",
            "DELIBERATION_SCORE\t~",
            "COUNTERFACTUAL_VIABILITY\t~",
            "COUNTERFACTUAL_CONFIDENCE\t~",
            "COGNITIVE_STATE_DIGEST\t" + "c".repeat(64),
            "EXECUTION_CONTEXT_DIGEST\t" + "d".repeat(64),
            listOf(
                "STEP",
                "1",
                enc("phase348-v6-request"),
                enc(capability.value),
                enc("Read current value"),
                enc("read"),
                PlanStepStatus.PLANNED.name,
                "~",
                "~",
                "~",
                "~",
                "~",
                enc("phase346-provider"),
                ToolSideEffect.READ_ONLY.name
            ).joinToString("\t")
        ).joinToString("\n")

        val restored = SovereignPlanCodec.decode(content).getOrThrow()

        assertEquals(PlanId("phase348-v6-plan"), restored.id)
        assertEquals("c".repeat(64), restored.planningCognitiveStateDigest)
        assertEquals("d".repeat(64), restored.planningExecutionContextDigest)
        assertEquals(null, restored.goalTransferBinding)
    }

    @Test
    fun threeExecutionFailuresSuppressTransferCandidate() {
        val model = MemoryBackedGoalTransferCalibrationModel(InMemoryMemoryOs())
        repeat(3) { index ->
            model.observeTerminalPlan(
                plan = terminalBoundPlan(
                    id = "phase347-failure-" + index,
                    status = PlanStepStatus.FAILED
                ),
                observedAtEpochMs = 1_000L + index
            )
        }

        val snapshot = requireNotNull(model.snapshot(StrategySignature(listOf(capability))))
        assertEquals(3, snapshot.executionFailures)
        assertEquals(3, snapshot.consecutiveFailures)

        val candidate = transferCandidate(latestObservedAt = 1_003L)
        val adjustment = GoalTransferCalibrationPolicy.adjust(
            candidate = candidate,
            snapshot = snapshot,
            nowEpochMs = 1_004L
        )
        assertTrue(adjustment.suppressedByFailureStreak)
        assertEquals(0.0, adjustment.multiplier, 0.0)
        assertEquals(0.0, adjustment.calibratedSupport, 0.0)
    }

    @Test
    fun authorityNeutralDoesNotPenalizeTransferQuality() {
        val model = MemoryBackedGoalTransferCalibrationModel(InMemoryMemoryOs())
        repeat(4) { index ->
            model.observeTerminalPlan(
                plan = terminalBoundPlan(
                    id = "phase347-authority-" + index,
                    status = PlanStepStatus.DENIED
                ),
                observedAtEpochMs = 2_000L + index
            )
        }

        val snapshot = requireNotNull(model.snapshot(StrategySignature(listOf(capability))))
        assertEquals(4, snapshot.authorityNeutral)
        assertEquals(0, snapshot.comparableAttempts)
        assertEquals(0, snapshot.consecutiveFailures)

        val candidate = transferCandidate(latestObservedAt = 2_000L)
        val adjustment = GoalTransferCalibrationPolicy.adjust(
            candidate = candidate,
            snapshot = snapshot,
            nowEpochMs = 2_010L
        )
        assertEquals(1.0, adjustment.multiplier, 0.0)
        assertEquals(candidate.transferSupport, adjustment.calibratedSupport, 0.0)
    }

    @Test
    fun staleHistoricalEvidenceDecaysWithoutInventingFailure() {
        val now = GoalTransferCalibrationPolicy.HARD_STALE_AFTER_MS + 10_000L
        val candidate = transferCandidate(latestObservedAt = 1L)

        val adjustment = GoalTransferCalibrationPolicy.adjust(
            candidate = candidate,
            snapshot = null,
            nowEpochMs = now
        )

        assertTrue(adjustment.staleHistoricalEvidence)
        assertEquals(0.52, adjustment.multiplier, 0.0)
        assertTrue(adjustment.calibratedSupport < candidate.transferSupport)
        assertFalse(adjustment.suppressedByFailureStreak)
    }

    @Test
    fun planOsV7PersistsExactTransferBindingAcrossRestart() {
        val memory = InMemoryMemoryOs()
        val store = MemoryBackedSovereignPlanStore(memory)
        val plan = terminalBoundPlan(
            id = "phase348-persisted",
            status = PlanStepStatus.EXECUTED
        )

        store.save(plan)
        val loaded = requireNotNull(store.load(plan.id))

        assertEquals(plan.goalTransferBinding, loaded.goalTransferBinding)
        assertEquals(plan.planningCognitiveStateDigest, loaded.planningCognitiveStateDigest)
        assertEquals(plan.planningExecutionContextDigest, loaded.planningExecutionContextDigest)
        assertEquals(StrategySignature.from(plan), loaded.goalTransferBinding?.strategy)
    }

    @Test
    fun persistentGoalExecutiveLearnsFailedTransferBeforeRecoveryExhaustion() {
        val runtime = AmperRuntime.reference()
        val plan = terminalBoundPlan(
            id = "phase349-early-failure",
            status = PlanStepStatus.FAILED,
            conversationId = ConversationId("phase349-conversation")
        )
        runtime.plans.save(plan)
        runtime.persistentGoalExecutiveStore.save(
            PersistentGoalExecutiveCheckpoint(
                sourceGoalId = "phase349-goal",
                objective = plan.goal,
                conversationId = plan.conversationId,
                priority = 0.8,
                stage = PersistentGoalExecutiveStage.PLANNED,
                plannedPlanId = plan.id,
                updatedAtEpochMs = 10L
            )
        )
        val coordinator = PersistentGoalExecutiveCoordinator(
            context = runtime.context,
            executive = unusedExecutive(runtime),
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans,
            portfolio = runtime.goalPortfolio,
            outcomeLearning = runtime.goalOutcomeLearning,
            transferCalibration = runtime.goalTransferCalibration,
            clock = { 20L }
        )

        val next = coordinator.resolveTerminalPlan(plan.id).getOrThrow()
        val calibration = requireNotNull(
            runtime.goalTransferCalibration.snapshot(StrategySignature.from(plan))
        )

        assertEquals(PersistentGoalExecutiveStage.RECOVERY_QUEUED, next.stage)
        assertEquals(1, calibration.executionFailures)
        assertEquals(1, calibration.consecutiveFailures)
    }

    @Test
    fun counterfactualValidatorUsesCalibrationToSuppressRepeatedlyBadTransfer() {
        val model = MemoryBackedGoalTransferCalibrationModel(InMemoryMemoryOs())
        repeat(3) { index ->
            model.observeTerminalPlan(
                plan = terminalBoundPlan(
                    id = "phase350-bad-" + index,
                    status = PlanStepStatus.FAILED
                ),
                observedAtEpochMs = 100L + index
            )
        }
        val state = readyState()
        val candidate = transferCandidate(
            latestObservedAt = state.capturedAtEpochMs
        )

        val validated = GoalTransferCounterfactualValidator.validate(
            candidates = listOf(candidate),
            state = state,
            descriptors = listOf(descriptor()),
            calibration = model
        )

        assertTrue(validated.isEmpty())
    }

    private fun transferCandidate(
        latestObservedAt: Long
    ): GoalStrategyTransferCandidate = GoalStrategyTransferCandidate(
        strategy = StrategySignature(listOf(capability)),
        analogousSuccesses = 4,
        executionExhaustions = 1,
        evidenceExhaustions = 0,
        authorityBlocks = 0,
        partialExecutionBlocks = 0,
        meanSimilarity = 0.80,
        analogousSuccessRate = 0.80,
        evidenceConfidence = 0.70,
        transferSupport = 0.60,
        meanHierarchyCompletionRatio = 0.90,
        latestAnalogousObservedAtEpochMs = latestObservedAt
    )

    private fun terminalBoundPlan(
        id: String,
        status: PlanStepStatus,
        conversationId: ConversationId = ConversationId("phase346-conversation")
    ): SovereignPlan {
        val binding = GoalTransferPlanBinding(
            strategy = StrategySignature(listOf(capability)),
            cognitiveStateDigest = digest,
            historicalSupport = 0.60,
            contextFit = 0.80,
            projectedSupport = 0.54,
            calibrationMultiplier = 1.0,
            calibratedSupport = 0.54,
            staleHistoricalEvidence = false,
            boundAtEpochMs = 1L
        )
        return SovereignPlan(
            id = PlanId(id),
            conversationId = conversationId,
            goal = "Inspect calibrated artifact integrity",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId(id + "-request"),
                    capability = capability,
                    reason = "Use current live contract",
                    input = "read",
                    status = status,
                    boundToolId = ToolId("phase346-provider"),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "phase346-planner",
            planningCognitiveStateDigest = digest,
            planningExecutionContextDigest = "b".repeat(64),
            goalTransferBinding = binding
        )
    }

    private fun readyState(): IntegratedCognitiveStatePacket {
        val runtime = AmperRuntime.reference()
        val base = runtime.integratedCognition.capture(
            query = "inspect calibrated artifact integrity",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor())
        )
        return base.copy(
            learningNeeds = emptyList(),
            readiness = base.readiness.copy(
                epistemicConfidence = 0.90,
                worldConfidence = 0.90,
                skillConfidence = 0.80,
                competenceConfidence = 0.80,
                uncertainty = 0.10,
                learningPressure = 0.10,
                overallReadiness = 0.86
            )
        )
    }

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase346-provider"),
        name = "Phase346 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded calibration test contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    private fun unusedExecutive(runtime: AmperRuntime): AutonomousCognitiveExecutive =
        AutonomousCognitiveExecutive(
            stateSource = runtime.integratedCognition,
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { _, _ ->
                Result.failure(IllegalStateException("calibration test does not create plans"))
            },
            practiceOne = {
                Result.failure(IllegalStateException("calibration test does not practice"))
            }
        )
}
