package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalContextualStrategyPortfolioTest {
    private val read = CapabilityId("phase351.read")
    private val inspect = CapabilityId("phase351.inspect")
    private val cognitiveDigest = "a".repeat(64)

    @Test
    fun boundedExplorationCanPromoteUnderSampledNearPeerButNotWeakCandidate() {
        val fingerprint = GoalOutcomeFingerprint.of("Inspect alpha package integrity")
        val contextDigest = GoalContextualStrategyPortfolioPolicy.contextDigest(fingerprint)
        val established = assessment(read, calibratedSupport = 0.70)
        val nearPeer = assessment(inspect, calibratedSupport = 0.65)
        val historical = listOf(
            GoalStrategyPortfolioStats(
                contextDigest = contextDigest,
                contextFingerprint = fingerprint,
                strategy = StrategySignature(listOf(read)),
                selections = 16
            )
        )

        val ranked = GoalContextualStrategyPortfolioPolicy.rank(
            candidates = listOf(established, nearPeer),
            contextDigest = contextDigest,
            contextFingerprint = fingerprint,
            historical = historical,
            nowEpochMs = 100L
        )

        assertEquals(inspect, ranked.first().assessment.candidate.strategy.capabilities.single())
        assertEquals(GoalStrategyPortfolioMode.EXPLORE, ranked.first().mode)
        assertTrue(ranked.first().explorationBonus > ranked.last().explorationBonus)

        val weak = assessment(inspect, calibratedSupport = 0.40)
        val weakRanked = GoalContextualStrategyPortfolioPolicy.rank(
            candidates = listOf(established, weak),
            contextDigest = contextDigest,
            contextFingerprint = fingerprint,
            historical = historical,
            nowEpochMs = 100L
        )
        val weakEntry = weakRanked.single {
            it.assessment.candidate.strategy.capabilities.single() == inspect
        }
        assertEquals(0.0, weakEntry.explorationBonus, 0.0)
        assertEquals(read, weakRanked.first().assessment.candidate.strategy.capabilities.single())
    }

    @Test
    fun bindAndVerifiedOutcomeAreRestartSafeAndIdempotent() {
        val memory = InMemoryMemoryOs()
        val model = MemoryBackedGoalContextualStrategyPortfolio(memory)
        val goal = "Inspect alpha package integrity"
        val assessment = assessment(read, calibratedSupport = 0.70)
        val ranked = model.rank(goal, listOf(assessment), nowEpochMs = 10L)
        val plan = boundPlan(
            id = "phase352-success",
            goal = goal,
            capability = read,
            assessment = assessment,
            status = PlanStepStatus.EXECUTED
        )

        val firstDecision = model.bind(plan, ranked, boundAtEpochMs = 11L)
        val secondDecision = model.bind(plan, ranked, boundAtEpochMs = 12L)
        assertEquals(firstDecision, secondDecision)

        val initialStats = requireNotNull(firstDecision).let {
            requireNotNull(model.snapshot(it.contextDigest, it.strategy))
        }
        assertEquals(1, initialStats.selections)

        val firstOutcome = model.observe(
            plan = plan,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            observedAtEpochMs = 20L
        )
        val secondOutcome = model.observe(
            plan = plan,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            observedAtEpochMs = 21L
        )

        assertEquals(firstOutcome, secondOutcome)
        assertEquals(1, firstOutcome?.verifiedSuccesses)
        assertEquals(1, firstOutcome?.comparableOutcomes)
        assertEquals(1.0, firstOutcome?.realizedRewardRate ?: 0.0, 0.0)
        assertEquals(0.0, firstOutcome?.cumulativeRegretProxy ?: -1.0, 0.0)

        val restarted = MemoryBackedGoalContextualStrategyPortfolio(memory)
        assertEquals(firstDecision, restarted.decision(plan.id))
        assertEquals(
            firstOutcome,
            restarted.snapshot(
                requireNotNull(firstDecision).contextDigest,
                firstDecision.strategy
            )
        )
    }

    @Test
    fun failedExplorationAccumulatesBoundedRealizedRegretProxy() {
        val memory = InMemoryMemoryOs()
        val model = MemoryBackedGoalContextualStrategyPortfolio(memory)
        val goal = "Inspect beta package integrity"
        val best = assessment(read, calibratedSupport = 0.78)
        val explored = assessment(inspect, calibratedSupport = 0.70)
        val ranked = model.rank(goal, listOf(best, explored), nowEpochMs = 100L)
        val exploredEntry = ranked.single {
            it.assessment.candidate.strategy.capabilities.single() == inspect
        }
        val plan = boundPlan(
            id = "phase353-failed-explore",
            goal = goal,
            capability = inspect,
            assessment = explored,
            status = PlanStepStatus.FAILED
        )

        val decision = requireNotNull(
            model.bind(plan, ranked, boundAtEpochMs = 101L)
        )
        val stats = requireNotNull(
            model.observeTerminalPlan(plan, observedAtEpochMs = 110L)
        )

        assertEquals(exploredEntry.mode, decision.mode)
        assertEquals(1, stats.executionFailures)
        assertEquals(1, stats.comparableOutcomes)
        assertTrue(stats.cumulativeRegretProxy > 0.0)
        assertTrue(stats.cumulativeRegretProxy <= 1.0)
        assertEquals(decision.bestExploitationScore, stats.cumulativeRegretProxy, 1e-12)
    }

    @Test
    fun authorityBlockedSelectionRemainsNeutralForRewardAndRegret() {
        val model = MemoryBackedGoalContextualStrategyPortfolio(InMemoryMemoryOs())
        val goal = "Inspect governed package"
        val assessment = assessment(read, calibratedSupport = 0.72)
        val ranked = model.rank(goal, listOf(assessment), nowEpochMs = 200L)
        val plan = boundPlan(
            id = "phase354-authority",
            goal = goal,
            capability = read,
            assessment = assessment,
            status = PlanStepStatus.DENIED
        )
        val decision = requireNotNull(model.bind(plan, ranked, boundAtEpochMs = 201L))
        val stats = requireNotNull(model.observeTerminalPlan(plan, observedAtEpochMs = 202L))

        assertEquals(1, stats.authorityNeutral)
        assertEquals(0, stats.comparableOutcomes)
        assertEquals(0.0, stats.cumulativeRegretProxy, 0.0)
        assertEquals(null, stats.realizedRewardRate)
        assertFalse(decision.authorityBearing)
    }

    @Test
    fun similarGoalContextReusesRewardWithoutPersistingRawGoal() {
        val model = MemoryBackedGoalContextualStrategyPortfolio(InMemoryMemoryOs())
        val firstGoal = "Inspect alpha package integrity"
        val firstAssessment = assessment(read, calibratedSupport = 0.66)
        val firstRanked = model.rank(firstGoal, listOf(firstAssessment), nowEpochMs = 300L)
        val firstPlan = boundPlan(
            id = "phase355-context-source",
            goal = firstGoal,
            capability = read,
            assessment = firstAssessment,
            status = PlanStepStatus.EXECUTED
        )
        model.bind(firstPlan, firstRanked, boundAtEpochMs = 301L)
        model.observe(
            firstPlan,
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            observedAtEpochMs = 302L
        )

        val secondRanked = model.rank(
            goal = "Inspect alpha package metadata",
            candidates = listOf(assessment(read, calibratedSupport = 0.62)),
            nowEpochMs = 400L
        )

        assertEquals(1, secondRanked.size)
        assertTrue(secondRanked.single().similarSelections >= 1)
        assertEquals(1.0, secondRanked.single().contextualRewardRate ?: 0.0, 0.0)
        assertTrue(secondRanked.single().contextFingerprint.all {
            it.matches(Regex("[0-9a-f]{16}"))
        })
        assertFalse(secondRanked.single().contextDigest.contains("alpha"))
        assertFalse(secondRanked.single().contextDigest.contains("inspect"))
    }

    @Test
    fun rendererCarriesPortfolioSignalsButNoAuthorityOrExecutionPayloads() {
        val model = MemoryBackedGoalContextualStrategyPortfolio(InMemoryMemoryOs())
        val assessment = assessment(read, calibratedSupport = 0.70)
        val ranked = model.rank(
            goal = "Inspect gamma package integrity",
            candidates = listOf(assessment),
            nowEpochMs = 500L
        )

        val rendered = GoalContextualStrategyPortfolioPolicy.render(ranked)

        assertTrue(rendered.contains("<GOAL_CONTEXTUAL_STRATEGY_PORTFOLIO>"))
        assertTrue(rendered.contains("cognitive_state_digest=" + cognitiveDigest))
        assertTrue(rendered.contains("portfolio_score="))
        assertTrue(rendered.contains("regret_proxy="))
        assertTrue(rendered.contains("authority=false"))
        assertFalse(rendered.contains("approval="))
        assertFalse(rendered.contains("request_id="))
        assertFalse(rendered.contains("tool_id="))
        assertFalse(rendered.contains("input="))
        assertFalse(rendered.contains("output="))
    }

    private fun assessment(
        capability: CapabilityId,
        calibratedSupport: Double
    ): GoalTransferCounterfactualAssessment {
        val candidate = GoalStrategyTransferCandidate(
            strategy = StrategySignature(listOf(capability)),
            analogousSuccesses = 3,
            executionExhaustions = 1,
            evidenceExhaustions = 0,
            authorityBlocks = 0,
            partialExecutionBlocks = 0,
            meanSimilarity = 0.75,
            analogousSuccessRate = 0.75,
            evidenceConfidence = 0.60,
            transferSupport = calibratedSupport,
            meanHierarchyCompletionRatio = 0.85,
            latestAnalogousObservedAtEpochMs = 1L,
            latestSuccessfulObservedAtEpochMs = 1L
        )
        return GoalTransferCounterfactualAssessment(
            candidate = candidate,
            cognitiveStateDigest = cognitiveDigest,
            liveCapabilityCoverage = 1.0,
            competenceFit = 0.80,
            worldFit = 0.80,
            epistemicFit = 0.80,
            perceptionFit = 0.70,
            learningRisk = 0.10,
            contextFit = 0.80,
            mismatchRisk = 0.20,
            projectedSupport = calibratedSupport,
            calibrationMultiplier = 1.0,
            calibratedSupport = calibratedSupport,
            staleHistoricalEvidence = false,
            decision = GoalTransferValidationDecision.ACCEPT
        )
    }

    private fun boundPlan(
        id: String,
        goal: String,
        capability: CapabilityId,
        assessment: GoalTransferCounterfactualAssessment,
        status: PlanStepStatus
    ): SovereignPlan {
        val binding = GoalTransferPlanBinding(
            strategy = assessment.candidate.strategy,
            cognitiveStateDigest = assessment.cognitiveStateDigest,
            historicalSupport = assessment.candidate.transferSupport,
            contextFit = assessment.contextFit,
            projectedSupport = assessment.projectedSupport,
            calibrationMultiplier = assessment.calibrationMultiplier,
            calibratedSupport = assessment.calibratedSupport,
            staleHistoricalEvidence = assessment.staleHistoricalEvidence,
            boundAtEpochMs = 1L
        )
        return SovereignPlan(
            id = PlanId(id),
            conversationId = ConversationId("phase351-conversation"),
            goal = goal,
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId(id + "-request"),
                    capability = capability,
                    reason = "Use current governed strategy",
                    input = "read",
                    status = status,
                    boundToolId = ToolId("phase351-provider-" + capability.value),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "phase351-planner",
            planningCognitiveStateDigest = cognitiveDigest,
            planningExecutionContextDigest = "b".repeat(64),
            goalTransferBinding = binding
        )
    }
}
