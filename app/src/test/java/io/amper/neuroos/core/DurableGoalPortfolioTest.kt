package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableGoalPortfolioTest {
    private val capability = CapabilityId("phase311.test")

    @Test
    fun portfolioPersistsPendingAcrossFreshStoreInstanceAndKeepsPriorityOrder() {
        val memory = InMemoryMemoryOs()
        val first = MemoryBackedDurableGoalPortfolio(memory)
        first.observe(
            candidates = listOf(
                DurableGoalCandidate(
                    sourceGoalId = "goal-low",
                    objective = "Low priority objective",
                    priority = 0.20
                ),
                DurableGoalCandidate(
                    sourceGoalId = "goal-high",
                    objective = "Mục tiêu ưu tiên cao",
                    priority = 0.95
                ),
                DurableGoalCandidate(
                    sourceGoalId = "goal-mid",
                    objective = "Medium priority objective",
                    priority = 0.60
                )
            ),
            observedAtEpochMs = 100L
        )

        val afterRestart = MemoryBackedDurableGoalPortfolio(memory)
        val pending = afterRestart.pending()

        assertEquals(listOf("goal-high", "goal-mid", "goal-low"), pending.map { it.sourceGoalId })
        assertEquals("Mục tiêu ưu tiên cao", pending.first().objective)
        assertTrue(pending.all { it.status == DurableGoalStatus.PENDING })
    }

    @Test
    fun completedTombstoneIsNotResurrectedByLiveGoalObservation() {
        val memory = InMemoryMemoryOs()
        val portfolio = MemoryBackedDurableGoalPortfolio(memory)
        portfolio.observe(
            listOf(
                DurableGoalCandidate(
                    sourceGoalId = "goal-complete",
                    objective = "Original objective",
                    priority = 0.80
                )
            ),
            observedAtEpochMs = 100L
        )
        val completed = portfolio.markCompleted(
            sourceGoalId = "goal-complete",
            completedAtEpochMs = 200L
        )
        assertEquals(DurableGoalStatus.COMPLETED, completed?.status)

        portfolio.observe(
            listOf(
                DurableGoalCandidate(
                    sourceGoalId = "goal-complete",
                    objective = "Same in-memory goal observed again",
                    priority = 1.0
                )
            ),
            observedAtEpochMs = 300L
        )

        val restored = requireNotNull(portfolio.get("goal-complete"))
        assertEquals(DurableGoalStatus.COMPLETED, restored.status)
        assertEquals("Original objective", restored.objective)
        assertTrue(portfolio.pending().none { it.sourceGoalId == "goal-complete" })
    }

    @Test
    fun boundedPortfolioRetainsHighestPriorityPendingGoals() {
        val portfolio = MemoryBackedDurableGoalPortfolio(InMemoryMemoryOs())
        val candidates = (0 until 40).map { index ->
            DurableGoalCandidate(
                sourceGoalId = "goal-$index",
                objective = "Objective $index",
                priority = index / 40.0
            )
        }

        portfolio.observe(candidates, observedAtEpochMs = 1L)
        val pending = portfolio.pending(limit = 100)

        assertEquals(MemoryBackedDurableGoalPortfolio.MAX_PENDING, pending.size)
        assertEquals("goal-39", pending.first().sourceGoalId)
        assertFalse(pending.any { it.sourceGoalId == "goal-0" })
        assertFalse(pending.any { it.sourceGoalId == "goal-7" })
        assertTrue(pending.any { it.sourceGoalId == "goal-8" })
    }

    @Test
    fun verifiedCompletionWritesDurablePortfolioTombstone() {
        val runtime = AmperRuntime.reference()
        val plan = executedPlan("phase314-plan")
        runtime.plans.save(plan)
        runtime.goalPortfolio.observe(
            listOf(
                DurableGoalCandidate(
                    sourceGoalId = "phase314-goal",
                    objective = plan.goal,
                    priority = 0.91
                )
            ),
            observedAtEpochMs = 5L
        )
        runtime.persistentGoalExecutiveStore.save(
            PersistentGoalExecutiveCheckpoint(
                sourceGoalId = "phase314-goal",
                objective = plan.goal,
                conversationId = plan.conversationId,
                priority = 0.91,
                stage = PersistentGoalExecutiveStage.PLANNED,
                attemptCount = 1,
                lastAction = CognitiveExecutiveAction.PLAN,
                plannedPlanId = plan.id,
                updatedAtEpochMs = 5L
            )
        )
        val goals = PersistentGoalExecutiveCoordinator(
            context = runtime.context,
            executive = dummyExecutive(runtime),
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans,
            portfolio = runtime.goalPortfolio,
            clock = { 10L }
        )

        val resolved = goals.resolveVerifiedSuccess(
            planId = plan.id,
            assessment = GoalSatisfactionAssessment(
                planId = plan.id,
                verdict = GoalSatisfactionVerdict.SATISFIED,
                confidence = 0.95,
                reason = "exact goal outcome is evidenced",
                cognitiveStateDigest = "a".repeat(64),
                executionContextDigest = "b".repeat(64)
            )
        ).getOrThrow()

        assertEquals(PersistentGoalExecutiveStage.COMPLETED, resolved.stage)
        val portfolioRecord = requireNotNull(runtime.goalPortfolio.get("phase314-goal"))
        assertEquals(DurableGoalStatus.COMPLETED, portfolioRecord.status)
        assertEquals(10L, portfolioRecord.completedAtEpochMs)
        assertTrue(runtime.goalPortfolio.pending().none { it.sourceGoalId == "phase314-goal" })
    }

    @Test
    fun missingGoalCompletionDoesNotInventPortfolioRecord() {
        val portfolio = MemoryBackedDurableGoalPortfolio(InMemoryMemoryOs())
        assertNull(portfolio.markCompleted("unknown-goal", completedAtEpochMs = 100L))
        assertTrue(portfolio.snapshot().isEmpty())
    }

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase311-provider"),
        name = "Phase311 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "durable goal portfolio test contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    private fun executedPlan(id: String): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase314-conversation"),
        goal = "Persist this goal completion across restart",
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("$id-request"),
                capability = capability,
                reason = "Already executed bounded evidence step",
                input = "read",
                status = PlanStepStatus.EXECUTED,
                boundToolId = descriptor().id,
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase314-test"
    )

    private fun dummyExecutive(
        runtime: AmperRuntime
    ): AutonomousCognitiveExecutive {
        val state = runtime.integratedCognition.capture(
            query = "Persist this goal completion across restart",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor())
        )
        return AutonomousCognitiveExecutive(
            stateSource = object : IntegratedCognitiveStateSource {
                override fun capture(
                    query: String,
                    allowedCapabilities: Set<CapabilityId>,
                    descriptors: Collection<ToolDescriptor>
                ): IntegratedCognitiveStatePacket = state
            },
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { _, _ ->
                Result.failure(IllegalStateException("portfolio completion test must not plan"))
            },
            practiceOne = {
                Result.failure(IllegalStateException("portfolio completion test must not practice"))
            }
        )
    }
}
