package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalPortfolioArbitrationTest {
    private val capability = CapabilityId("phase316.test")

    @Test
    fun dependencyBlockedGoalBecomesRunnableOnlyAfterPrerequisiteCompletes() {
        val memory = InMemoryMemoryOs()
        val portfolio = MemoryBackedDurableGoalPortfolio(memory)
        portfolio.observe(
            listOf(
                DurableGoalCandidate("prerequisite", "Prepare required state", 0.40),
                DurableGoalCandidate("dependent", "Use prepared state", 0.95)
            ),
            observedAtEpochMs = 100L
        )
        portfolio.setDependencies(
            sourceGoalId = "dependent",
            dependsOnGoalIds = setOf("prerequisite"),
            updatedAtEpochMs = 110L
        )

        val before = DurableGoalArbitrationPolicy.evaluate(
            portfolio.snapshot(),
            nowEpochMs = 120L
        )
        val dependentBefore = before.single { it.goal.sourceGoalId == "dependent" }
        assertTrue(!dependentBefore.runnable)
        assertEquals(setOf("prerequisite"), dependentBefore.blockedByGoalIds)
        assertEquals(
            "prerequisite",
            DurableGoalArbitrationPolicy.select(
                portfolio.snapshot(),
                nowEpochMs = 120L
            )?.goal?.sourceGoalId
        )

        portfolio.markCompleted("prerequisite", completedAtEpochMs = 130L)

        val dependentAfter = DurableGoalArbitrationPolicy.evaluate(
            portfolio.snapshot(),
            nowEpochMs = 140L
        ).single { it.goal.sourceGoalId == "dependent" }
        assertTrue(dependentAfter.runnable)
        assertTrue(dependentAfter.blockedByGoalIds.isEmpty())
        assertEquals(
            "dependent",
            DurableGoalArbitrationPolicy.select(
                portfolio.snapshot(),
                nowEpochMs = 140L
            )?.goal?.sourceGoalId
        )
        assertTrue(!dependentAfter.authorityBearing)
    }

    @Test
    fun dependencyMutationRejectsUnknownPrerequisiteAndCyclesWithoutPersistingThem() {
        val portfolio = MemoryBackedDurableGoalPortfolio(InMemoryMemoryOs())
        portfolio.observe(
            listOf(
                DurableGoalCandidate("goal-a", "Goal A", 0.8),
                DurableGoalCandidate("goal-b", "Goal B", 0.7)
            ),
            observedAtEpochMs = 1L
        )

        val unknown = runCatching {
            portfolio.setDependencies(
                sourceGoalId = "goal-a",
                dependsOnGoalIds = setOf("missing-goal"),
                updatedAtEpochMs = 2L
            )
        }
        assertTrue(unknown.isFailure)
        assertTrue(requireNotNull(portfolio.get("goal-a")).dependsOnGoalIds.isEmpty())

        portfolio.setDependencies(
            sourceGoalId = "goal-a",
            dependsOnGoalIds = setOf("goal-b"),
            updatedAtEpochMs = 3L
        )
        val cycle = runCatching {
            portfolio.setDependencies(
                sourceGoalId = "goal-b",
                dependsOnGoalIds = setOf("goal-a"),
                updatedAtEpochMs = 4L
            )
        }
        assertTrue(cycle.isFailure)
        assertEquals(setOf("goal-b"), requireNotNull(portfolio.get("goal-a")).dependsOnGoalIds)
        assertTrue(requireNotNull(portfolio.get("goal-b")).dependsOnGoalIds.isEmpty())
    }

    @Test
    fun nearDeadlineCanPromoteRunnableGoalWithoutOverridingDependencies() {
        val now = 1_000_000L
        val noDeadline = record(
            id = "high-priority",
            priority = 0.82
        )
        val urgent = record(
            id = "urgent",
            priority = 0.65,
            deadlineEpochMs = now + 60_000L
        )
        val selected = DurableGoalArbitrationPolicy.select(
            records = listOf(noDeadline, urgent),
            nowEpochMs = now
        )

        assertEquals("urgent", selected?.goal?.sourceGoalId)
        assertTrue(requireNotNull(selected).deadlineUrgency > 0.99)
        assertTrue(selected.arbitrationScore > 0.70)

        val overdueUrgency = DurableGoalArbitrationPolicy.deadlineUrgency(
            deadlineEpochMs = now - 1L,
            nowEpochMs = now
        )
        assertEquals(1.0, overdueUrgency, 0.0)
        assertEquals(
            0.0,
            DurableGoalArbitrationPolicy.deadlineUrgency(
                deadlineEpochMs = now + DurableGoalArbitrationPolicy.DEADLINE_HORIZON_MS,
                nowEpochMs = now
            ),
            0.0
        )
    }

    @Test
    fun codecV2ReadsV1AndRoundTripsDependenciesAndDeadline() {
        fun enc(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        val v1 = buildString {
            appendLine("AMPER_DURABLE_GOAL_PORTFOLIO_V1")
            append("GOAL\t")
            append(enc("legacy-goal")).append('\t')
            append(enc("Legacy objective")).append('\t')
            append("0.7\tPENDING\t10\t20\t~")
        }
        val restoredV1 = DurableGoalPortfolioCodec.decode(v1).getOrThrow().single()
        assertTrue(restoredV1.dependsOnGoalIds.isEmpty())
        assertNull(restoredV1.deadlineEpochMs)

        val v2Record = restoredV1.copy(
            dependsOnGoalIds = setOf("prerequisite"),
            deadlineEpochMs = 999_999L
        )
        val prerequisite = record(
            id = "prerequisite",
            priority = 0.9,
            status = DurableGoalStatus.COMPLETED,
            completedAtEpochMs = 50L
        )
        val encoded = DurableGoalPortfolioCodec.encode(listOf(v2Record, prerequisite))
        val decoded = DurableGoalPortfolioCodec.decode(encoded).getOrThrow()
        val restored = decoded.single { it.sourceGoalId == "legacy-goal" }

        assertTrue(encoded.startsWith("AMPER_DURABLE_GOAL_PORTFOLIO_V2"))
        assertEquals(setOf("prerequisite"), restored.dependsOnGoalIds)
        assertEquals(999_999L, restored.deadlineEpochMs)
    }

    @Test
    fun persistentSelectorChoosesRunnablePrerequisiteBeforeBlockedHigherPriorityGoal() {
        val runtime = AmperRuntime.reference()
        runtime.goalPortfolio.observe(
            listOf(
                DurableGoalCandidate("phase319-pre", "Prepare first", 0.50),
                DurableGoalCandidate("phase319-dependent", "Do dependent work", 0.99)
            ),
            observedAtEpochMs = 10L
        )
        runtime.goalPortfolio.setDependencies(
            sourceGoalId = "phase319-dependent",
            dependsOnGoalIds = setOf("phase319-pre"),
            updatedAtEpochMs = 11L
        )

        val liveContext = runtime.context
        val emptyLiveGoals = object : SovereignContextSource {
            override fun capture(
                query: String,
                memoryLimit: Int,
                worldLimit: Int,
                workspaceLimit: Int
            ): SovereignContextSnapshot =
                liveContext.capture(query, memoryLimit, worldLimit, workspaceLimit)
                    .copy(goals = emptyList())

            override fun groundedPrompt(query: String, charBudget: Int): String =
                liveContext.groundedPrompt(query, charBudget)

            override fun rememberAssistantResponse(
                userPrompt: String,
                response: String,
                backendId: String,
                confidence: Double
            ) {
                liveContext.rememberAssistantResponse(
                    userPrompt,
                    response,
                    backendId,
                    confidence
                )
            }
        }

        val state = readyState(runtime, "Prepare first")
        var createdGoal: String? = null
        val planId = PlanId("phase319-plan")
        val executive = AutonomousCognitiveExecutive(
            stateSource = object : IntegratedCognitiveStateSource {
                override fun capture(
                    query: String,
                    allowedCapabilities: Set<CapabilityId>,
                    descriptors: Collection<ToolDescriptor>
                ): IntegratedCognitiveStatePacket = state
            },
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { conversationId, goal ->
                createdGoal = goal
                Result.success(
                    SovereignPlan(
                        id = planId,
                        conversationId = conversationId,
                        goal = goal,
                        steps = listOf(
                            SovereignPlanStep(
                                index = 1,
                                requestId = ActionRequestId("phase319-request"),
                                capability = capability,
                                reason = "Run the unblocked prerequisite",
                                input = "read",
                                boundToolId = descriptor().id,
                                boundSideEffect = ToolSideEffect.READ_ONLY
                            )
                        ),
                        planningBackendId = "phase319-planner"
                    )
                )
            },
            practiceOne = {
                Result.failure(IllegalStateException("ready goal should plan"))
            }
        )
        val goals = PersistentGoalExecutiveCoordinator(
            context = emptyLiveGoals,
            executive = executive,
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans,
            portfolio = runtime.goalPortfolio,
            clock = { 20L }
        )

        val result = goals.runNext(ConversationId("phase319-conversation")).getOrThrow()
        assertTrue(result is PersistentGoalExecutiveResult.Ran)
        val checkpoint = (result as PersistentGoalExecutiveResult.Ran).checkpoint
        assertEquals("phase319-pre", checkpoint.sourceGoalId)
        assertEquals("Prepare first", createdGoal)
        assertEquals(planId, checkpoint.plannedPlanId)
    }

    private fun record(
        id: String,
        priority: Double,
        status: DurableGoalStatus = DurableGoalStatus.PENDING,
        deadlineEpochMs: Long? = null,
        completedAtEpochMs: Long? = null
    ): DurableGoalRecord = DurableGoalRecord(
        sourceGoalId = id,
        objective = "Objective for $id",
        priority = priority,
        status = status,
        firstSeenAtEpochMs = 1L,
        updatedAtEpochMs = completedAtEpochMs ?: 1L,
        completedAtEpochMs = completedAtEpochMs,
        deadlineEpochMs = deadlineEpochMs
    )

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase316-provider"),
        name = "Phase316 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "goal arbitration test contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    private fun readyState(
        runtime: AmperRuntime,
        query: String
    ): IntegratedCognitiveStatePacket {
        val base = runtime.integratedCognition.capture(
            query = query,
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor())
        )
        return base.copy(
            perceptualEvidence = emptyList(),
            learningNeeds = emptyList(),
            readiness = base.readiness.copy(
                epistemicConfidence = 0.90,
                worldConfidence = 0.90,
                skillConfidence = 0.85,
                competenceConfidence = 0.80,
                uncertainty = 0.10,
                learningPressure = 0.10,
                overallReadiness = 0.88
            )
        )
    }
}
