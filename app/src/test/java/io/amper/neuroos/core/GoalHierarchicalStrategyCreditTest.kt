package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalHierarchicalStrategyCreditTest {
    private val read = CapabilityId("phase356.read")
    private val inspect = CapabilityId("phase356.inspect")
    private val write = CapabilityId("phase356.write")

    @Test
    fun verifiedSuccessCreditsStepsPrefixAndSequenceAtChildDepth() {
        val memory = InMemoryMemoryOs()
        val model = MemoryBackedGoalHierarchicalStrategyCreditModel(memory)
        val records = hierarchyRecords()
        val plan = plan(
            id = "phase356-success",
            goal = "Inspect alpha package",
            capabilities = listOf(read, inspect, write),
            statuses = listOf(
                PlanStepStatus.EXECUTED,
                PlanStepStatus.EXECUTED,
                PlanStepStatus.EXECUTED
            )
        )
        val checkpoint = checkpoint(
            goalId = "child-alpha",
            objective = "Inspect alpha package",
            plan = plan
        )

        val first = model.observe(
            checkpoint = checkpoint,
            plan = plan,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            portfolioRecords = records,
            observedAtEpochMs = 100L
        )
        val second = model.observe(
            checkpoint = checkpoint,
            plan = plan,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            portfolioRecords = records,
            observedAtEpochMs = 200L
        )

        assertEquals(first, second)
        assertEquals(5, first.size)
        assertTrue(first.all { it.hierarchyDepth == 1 })
        assertTrue(first.all { it.goalFingerprint.all { term -> term.matches(Regex("[0-9a-f]{16}")) } })
        assertTrue(first.all { it.rootFingerprint.all { term -> term.matches(Regex("[0-9a-f]{16}")) } })

        val step = first.single {
            it.componentKind == GoalStrategyCreditComponentKind.STEP &&
                it.componentIndex == 1
        }
        val prefix = first.single {
            it.componentKind == GoalStrategyCreditComponentKind.PREFIX
        }
        val sequence = first.single {
            it.componentKind == GoalStrategyCreditComponentKind.SEQUENCE
        }

        assertEquals(1, step.executionSuccesses)
        assertEquals(1, step.goalSuccesses)
        assertEquals(0.60, step.cumulativeCredit, 0.0)
        assertEquals(0.80, prefix.cumulativeCredit, 0.0)
        assertEquals(1.00, sequence.cumulativeCredit, 0.0)

        val restarted = MemoryBackedGoalHierarchicalStrategyCreditModel(memory)
        val assessment = restarted.assess(
            goal = "Inspect alpha package metadata",
            strategy = StrategySignature(listOf(read, inspect, write))
        )
        assertTrue(assessment.creditScore > 0.0)
        assertTrue(assessment.evidenceConfidence > 0.0)
        assertTrue(assessment.matchedComponents >= 1)
        assertTrue(1 in assessment.matchedHierarchyDepths)
        assertFalse(assessment.authorityBearing)
    }

    @Test
    fun evidenceExhaustionPreservesExecutionCreditButPenalizesOnlyFullSequenceGoalContribution() {
        val model = MemoryBackedGoalHierarchicalStrategyCreditModel(InMemoryMemoryOs())
        val plan = plan(
            id = "phase357-evidence",
            goal = "Inspect alpha package",
            capabilities = listOf(read, inspect, write),
            statuses = List(3) { PlanStepStatus.EXECUTED }
        )
        val result = model.observe(
            checkpoint = checkpoint("child-alpha", "Inspect alpha package", plan),
            plan = plan,
            outcome = GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED,
            portfolioRecords = hierarchyRecords(),
            observedAtEpochMs = 300L
        )

        val steps = result.filter {
            it.componentKind == GoalStrategyCreditComponentKind.STEP
        }
        val prefix = result.single {
            it.componentKind == GoalStrategyCreditComponentKind.PREFIX
        }
        val sequence = result.single {
            it.componentKind == GoalStrategyCreditComponentKind.SEQUENCE
        }

        assertTrue(steps.all { it.executionSuccesses == 1 })
        assertTrue(steps.all { it.goalEvidenceFailures == 0 })
        assertTrue(steps.all { it.cumulativeCredit == 0.20 })
        assertEquals(1, prefix.executionSuccesses)
        assertEquals(0, prefix.goalEvidenceFailures)
        assertEquals(0.20, prefix.cumulativeCredit, 0.0)
        assertEquals(1, sequence.executionSuccesses)
        assertEquals(1, sequence.goalEvidenceFailures)
        assertEquals(-0.60, sequence.cumulativeCredit, 0.0)
    }

    @Test
    fun authorityBlockIsNeutralAcrossAllHierarchicalComponents() {
        val model = MemoryBackedGoalHierarchicalStrategyCreditModel(InMemoryMemoryOs())
        val plan = plan(
            id = "phase358-authority",
            goal = "Inspect alpha package",
            capabilities = listOf(read, inspect),
            statuses = listOf(PlanStepStatus.DENIED, PlanStepStatus.REJECTED)
        )

        val result = model.observeTerminalPlan(
            checkpoint = checkpoint("child-alpha", "Inspect alpha package", plan),
            plan = plan,
            portfolioRecords = hierarchyRecords(),
            observedAtEpochMs = 400L
        )

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.authorityNeutral == 1 })
        assertTrue(result.all { it.cumulativeCredit == 0.0 })
        assertTrue(result.all { it.executionFailures == 0 })
        assertTrue(result.all { it.goalEvidenceFailures == 0 })
        assertTrue(result.all { it.comparableObservations == 0 })
        assertTrue(result.all { it.evidenceConfidence == 0.0 })

        val assessment = model.assess(
            goal = "Inspect alpha package metadata",
            strategy = StrategySignature(listOf(read, inspect))
        )
        assertEquals(0.0, assessment.creditScore, 0.0)
        assertEquals(0.0, assessment.evidenceConfidence, 0.0)
    }

    @Test
    fun failedSiblingDoesNotContaminateDissimilarSiblingCredit() {
        val model = MemoryBackedGoalHierarchicalStrategyCreditModel(InMemoryMemoryOs())
        val failed = plan(
            id = "phase358-failed-alpha",
            goal = "Inspect alpha package",
            capabilities = listOf(read, inspect),
            statuses = listOf(PlanStepStatus.FAILED, PlanStepStatus.UNAVAILABLE)
        )
        model.observeTerminalPlan(
            checkpoint = checkpoint("child-alpha", "Inspect alpha package", failed),
            plan = failed,
            portfolioRecords = hierarchyRecords(),
            observedAtEpochMs = 500L
        )

        val alpha = model.assess(
            goal = "Inspect alpha package integrity",
            strategy = StrategySignature(listOf(read, inspect))
        )
        val beta = model.assess(
            goal = "Compile quantum shader benchmark",
            strategy = StrategySignature(listOf(read, inspect))
        )

        assertTrue(alpha.creditScore < 0.0)
        assertTrue(alpha.matchedComponents > 0)
        assertEquals(0.0, beta.creditScore, 0.0)
        assertEquals(0, beta.matchedComponents)
    }

    @Test
    fun hierarchicalCreditAdjustsPortfolioWithinStrictBound() {
        val memory = InMemoryMemoryOs()
        val credit = MemoryBackedGoalHierarchicalStrategyCreditModel(memory)
        val goal = "Inspect alpha package"
        repeat(3) { index ->
            val successful = plan(
                id = "phase359-credit-" + index,
                goal = goal,
                capabilities = listOf(read, inspect),
                statuses = listOf(PlanStepStatus.EXECUTED, PlanStepStatus.EXECUTED)
            )
            credit.observe(
                checkpoint = checkpoint("child-alpha", goal, successful),
                plan = successful,
                outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
                portfolioRecords = hierarchyRecords(),
                observedAtEpochMs = 600L + index
            )
        }

        val base = GoalStrategyPortfolioCandidate(
            assessment = counterfactualAssessment(
                strategy = StrategySignature(listOf(read, inspect)),
                support = 0.60
            ),
            contextDigest = GoalContextualStrategyPortfolioPolicy.contextDigest(
                GoalOutcomeFingerprint.of(goal)
            ),
            contextFingerprint = GoalOutcomeFingerprint.of(goal),
            similarSelections = 2,
            contextualRewardRate = 0.5,
            contextualEvidenceConfidence = 0.5,
            contextualRegretProxy = 0.0,
            exploitationScore = 0.60,
            explorationBonus = 0.0,
            portfolioScore = 0.60,
            mode = GoalStrategyPortfolioMode.EXPLOIT
        )

        val adjusted = GoalHierarchicalStrategyCreditPolicy.apply(
            goal = goal,
            candidates = listOf(base),
            credit = credit
        ).single()

        assertTrue(adjusted.hierarchicalCreditScore > 0.0)
        assertTrue(adjusted.hierarchicalCreditConfidence > 0.0)
        assertTrue(adjusted.hierarchicalMatchedComponents > 0)
        assertTrue(adjusted.hierarchicalCreditAdjustment > 0.0)
        assertTrue(
            adjusted.hierarchicalCreditAdjustment <=
                GoalHierarchicalStrategyCreditPolicy.MAX_PORTFOLIO_ADJUSTMENT
        )
        assertEquals(
            base.portfolioScore + adjusted.hierarchicalCreditAdjustment,
            adjusted.portfolioScore,
            1e-12
        )
    }

    @Test
    fun rendererExposesCreditSignalsWithoutRawHierarchyOrAuthorityPayloads() {
        val memory = InMemoryMemoryOs()
        val credit = MemoryBackedGoalHierarchicalStrategyCreditModel(memory)
        val goal = "Inspect alpha package"
        val success = plan(
            id = "phase360-render",
            goal = goal,
            capabilities = listOf(read),
            statuses = listOf(PlanStepStatus.EXECUTED)
        )
        credit.observe(
            checkpoint = checkpoint("child-alpha", goal, success),
            plan = success,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            portfolioRecords = hierarchyRecords(),
            observedAtEpochMs = 700L
        )
        val portfolio = MemoryBackedGoalContextualStrategyPortfolio(
            memory = memory,
            hierarchicalCredit = credit
        )
        val ranked = portfolio.rank(
            goal = goal,
            candidates = listOf(
                counterfactualAssessment(
                    strategy = StrategySignature(listOf(read)),
                    support = 0.60
                )
            ),
            nowEpochMs = 701L
        )

        val rendered = GoalContextualStrategyPortfolioPolicy.render(ranked)

        assertTrue(rendered.contains("hierarchical_credit="))
        assertTrue(rendered.contains("hierarchical_confidence="))
        assertTrue(rendered.contains("hierarchical_adjustment="))
        assertTrue(rendered.contains("authority=false"))
        assertFalse(rendered.contains("child-alpha"))
        assertFalse(rendered.contains("root-goal"))
        assertFalse(rendered.contains("approval="))
        assertFalse(rendered.contains("tool_id="))
        assertFalse(rendered.contains("input="))
    }

    private fun hierarchyRecords(): List<DurableGoalRecord> = listOf(
        DurableGoalRecord(
            sourceGoalId = "root-goal",
            objective = "Prepare release package",
            priority = 0.9,
            status = DurableGoalStatus.PENDING,
            firstSeenAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
            decompositionState = DurableGoalDecompositionState.DECOMPOSED,
            decompositionDepth = 0,
            dependsOnGoalIds = setOf("child-alpha", "child-beta")
        ),
        DurableGoalRecord(
            sourceGoalId = "child-alpha",
            objective = "Inspect alpha package",
            priority = 0.8,
            status = DurableGoalStatus.PENDING,
            firstSeenAtEpochMs = 2L,
            updatedAtEpochMs = 2L,
            decompositionState = DurableGoalDecompositionState.ATOMIC,
            decompositionDepth = 1,
            parentGoalId = "root-goal"
        ),
        DurableGoalRecord(
            sourceGoalId = "child-beta",
            objective = "Compile quantum shader benchmark",
            priority = 0.8,
            status = DurableGoalStatus.PENDING,
            firstSeenAtEpochMs = 2L,
            updatedAtEpochMs = 2L,
            decompositionState = DurableGoalDecompositionState.ATOMIC,
            decompositionDepth = 1,
            parentGoalId = "root-goal"
        )
    )

    private fun checkpoint(
        goalId: String,
        objective: String,
        plan: SovereignPlan
    ): PersistentGoalExecutiveCheckpoint = PersistentGoalExecutiveCheckpoint(
        sourceGoalId = goalId,
        objective = objective,
        conversationId = plan.conversationId,
        priority = 0.8,
        stage = PersistentGoalExecutiveStage.PLANNED,
        plannedPlanId = plan.id,
        updatedAtEpochMs = 10L
    )

    private fun plan(
        id: String,
        goal: String,
        capabilities: List<CapabilityId>,
        statuses: List<PlanStepStatus>
    ): SovereignPlan {
        require(capabilities.size == statuses.size)
        return SovereignPlan(
            id = PlanId(id),
            conversationId = ConversationId("phase356-conversation"),
            goal = goal,
            steps = capabilities.mapIndexed { index, capability ->
                SovereignPlanStep(
                    index = index + 1,
                    requestId = ActionRequestId(id + "-request-" + index),
                    capability = capability,
                    reason = "Use governed component",
                    input = "read",
                    status = statuses[index],
                    boundToolId = ToolId("phase356-provider-" + index),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            },
            planningBackendId = "phase356-planner"
        )
    }

    private fun counterfactualAssessment(
        strategy: StrategySignature,
        support: Double
    ): GoalTransferCounterfactualAssessment {
        val candidate = GoalStrategyTransferCandidate(
            strategy = strategy,
            analogousSuccesses = 3,
            executionExhaustions = 1,
            evidenceExhaustions = 0,
            authorityBlocks = 0,
            partialExecutionBlocks = 0,
            meanSimilarity = 0.8,
            analogousSuccessRate = 0.75,
            evidenceConfidence = 0.6,
            transferSupport = support,
            meanHierarchyCompletionRatio = 0.8,
            latestAnalogousObservedAtEpochMs = 1L,
            latestSuccessfulObservedAtEpochMs = 1L
        )
        return GoalTransferCounterfactualAssessment(
            candidate = candidate,
            cognitiveStateDigest = "c".repeat(64),
            liveCapabilityCoverage = 1.0,
            competenceFit = 0.8,
            worldFit = 0.8,
            epistemicFit = 0.8,
            perceptionFit = 0.7,
            learningRisk = 0.1,
            contextFit = 0.8,
            mismatchRisk = 0.2,
            projectedSupport = support,
            calibrationMultiplier = 1.0,
            calibratedSupport = support,
            staleHistoricalEvidence = false,
            decision = GoalTransferValidationDecision.ACCEPT
        )
    }
}
