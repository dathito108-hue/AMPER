package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalPortfolioCoordinationTest {
    private val capability = CapabilityId("phase321.test")

    @Test
    fun dependencyBlockedGoalBecomesRunnableOnlyAfterPrerequisiteCompletes() {
        val portfolio = MemoryBackedDurableGoalPortfolio(InMemoryMemoryOs())
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

        val selectedBefore = requireNotNull(
            portfolio.selectNext(selectedAtEpochMs = 120L)
        )
        assertEquals("prerequisite", selectedBefore.sourceGoalId)
        assertEquals(1, selectedBefore.selectionCount)

        portfolio.markCompleted("prerequisite", completedAtEpochMs = 130L)

        val dependentAfter = DurableGoalArbitrationPolicy.evaluate(
            portfolio.snapshot(),
            nowEpochMs = 140L
        ).single { it.goal.sourceGoalId == "dependent" }
        assertTrue(dependentAfter.runnable)
        assertTrue(dependentAfter.blockedByGoalIds.isEmpty())

        val selectedAfter = requireNotNull(
            portfolio.selectNext(selectedAtEpochMs = 140L)
        )
        assertEquals("dependent", selectedAfter.sourceGoalId)
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
    fun nearDeadlinePromotesRunnableGoalWhenNoGoalIsStarved() {
        val now = 1_000_000L
        val highPriority = record(
            id = "high-priority",
            priority = 0.82,
            firstSeenAtEpochMs = now - 1_000L
        )
        val urgent = record(
            id = "urgent",
            priority = 0.65,
            firstSeenAtEpochMs = now - 1_000L,
            deadlineEpochMs = now + 60_000L
        )

        val selected = requireNotNull(
            DurableGoalArbitrationPolicy.select(
                records = listOf(highPriority, urgent),
                nowEpochMs = now
            )
        )

        assertEquals("urgent", selected.goal.sourceGoalId)
        assertTrue(selected.deadlineUrgency > 0.99)
        assertTrue(selected.arbitrationScore > 0.70)
        assertTrue(!selected.starved)

        assertEquals(
            1.0,
            DurableGoalArbitrationPolicy.deadlineUrgency(
                deadlineEpochMs = now - 1L,
                nowEpochMs = now
            ),
            0.0
        )
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
    fun hardStarvationStillBeatsFreshUrgentDeadline() {
        val threshold = MemoryBackedDurableGoalPortfolio.STARVATION_THRESHOLD_MS
        val now = threshold + 10_000L
        val starved = record(
            id = "starved-low-priority",
            priority = 0.05,
            firstSeenAtEpochMs = 0L
        )
        val urgentFresh = record(
            id = "fresh-urgent",
            priority = 1.0,
            firstSeenAtEpochMs = now - 1_000L,
            deadlineEpochMs = now
        )

        val selected = requireNotNull(
            DurableGoalArbitrationPolicy.select(
                records = listOf(starved, urgentFresh),
                nowEpochMs = now
            )
        )

        assertEquals("starved-low-priority", selected.goal.sourceGoalId)
        assertTrue(selected.starved)
    }

    @Test
    fun dependencyGateCannotBeBypassedByStarvationOrOverdueDeadline() {
        val threshold = MemoryBackedDurableGoalPortfolio.STARVATION_THRESHOLD_MS
        val now = threshold + 100L
        val prerequisite = record(
            id = "pre",
            priority = 0.2,
            firstSeenAtEpochMs = now - 1_000L
        )
        val blocked = record(
            id = "blocked",
            priority = 1.0,
            firstSeenAtEpochMs = 0L,
            deadlineEpochMs = now - 1L,
            dependsOnGoalIds = setOf("pre")
        )

        val evaluation = DurableGoalArbitrationPolicy.evaluate(
            records = listOf(prerequisite, blocked),
            nowEpochMs = now
        )
        val blockedAssessment = evaluation.single { it.goal.sourceGoalId == "blocked" }
        assertTrue(blockedAssessment.starved)
        assertEquals(1.0, blockedAssessment.deadlineUrgency, 0.0)
        assertTrue(!blockedAssessment.runnable)

        val selected = requireNotNull(
            DurableGoalArbitrationPolicy.select(
                records = listOf(prerequisite, blocked),
                nowEpochMs = now
            )
        )
        assertEquals("pre", selected.goal.sourceGoalId)
    }

    @Test
    fun codecV3ReadsCanonicalFairnessV2AndRoundTripsCoordinationMetadata() {
        fun enc(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        val v2 = buildString {
            appendLine("AMPER_DURABLE_GOAL_PORTFOLIO_V2")
            append("GOAL\t")
            append(enc("phase324-v2-goal")).append('\t')
            append(enc("Canonical fairness goal")).append('\t')
            append("0.7\tPENDING\t10\t20\t~\t2\t15")
        }
        val restoredV2 = DurableGoalPortfolioCodec.decode(v2).getOrThrow().single()
        assertEquals(2, restoredV2.selectionCount)
        assertEquals(15L, restoredV2.lastSelectedAtEpochMs)
        assertTrue(restoredV2.dependsOnGoalIds.isEmpty())
        assertNull(restoredV2.deadlineEpochMs)

        val prerequisite = record(
            id = "phase324-prerequisite",
            priority = 0.9,
            status = DurableGoalStatus.COMPLETED,
            firstSeenAtEpochMs = 1L,
            completedAtEpochMs = 30L
        )
        val coordinated = restoredV2.copy(
            dependsOnGoalIds = setOf(prerequisite.sourceGoalId),
            deadlineEpochMs = 999_999L
        )
        val encoded = DurableGoalPortfolioCodec.encode(listOf(coordinated, prerequisite))
        val decoded = DurableGoalPortfolioCodec.decode(encoded).getOrThrow()
        val restored = decoded.single { it.sourceGoalId == coordinated.sourceGoalId }

        assertTrue(encoded.startsWith("AMPER_DURABLE_GOAL_PORTFOLIO_V4"))
        assertEquals(2, restored.selectionCount)
        assertEquals(15L, restored.lastSelectedAtEpochMs)
        assertEquals(setOf(prerequisite.sourceGoalId), restored.dependsOnGoalIds)
        assertEquals(999_999L, restored.deadlineEpochMs)
    }

    @Test
    fun persistentSelectorRunsPrerequisiteBeforeBlockedHigherPriorityGoal() {
        val runtime = AmperRuntime.reference()
        runtime.goalPortfolio.observe(
            listOf(
                DurableGoalCandidate("phase325-pre", "Prepare first", 0.50),
                DurableGoalCandidate("phase325-dependent", "Do dependent work", 0.99)
            ),
            observedAtEpochMs = 10L
        )
        runtime.goalPortfolio.setDependencies(
            sourceGoalId = "phase325-dependent",
            dependsOnGoalIds = setOf("phase325-pre"),
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
        val planId = PlanId("phase325-plan")
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
                                requestId = ActionRequestId("phase325-request"),
                                capability = capability,
                                reason = "Run the unblocked prerequisite",
                                input = "read",
                                boundToolId = descriptor().id,
                                boundSideEffect = ToolSideEffect.READ_ONLY
                            )
                        ),
                        planningBackendId = "phase325-planner"
                    )
                )
            },
            practiceOne = {
                Result.failure(IllegalStateException("ready coordinated goal should plan"))
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

        val result = goals.runNext(ConversationId("phase325-conversation")).getOrThrow()
        assertTrue(result is PersistentGoalExecutiveResult.Ran)
        val checkpoint = (result as PersistentGoalExecutiveResult.Ran).checkpoint
        assertEquals("phase325-pre", checkpoint.sourceGoalId)
        assertEquals("Prepare first", createdGoal)
        assertEquals(planId, checkpoint.plannedPlanId)
        assertEquals(1, runtime.goalPortfolio.get("phase325-pre")?.selectionCount)
    }

    private fun record(
        id: String,
        priority: Double,
        status: DurableGoalStatus = DurableGoalStatus.PENDING,
        firstSeenAtEpochMs: Long = 1L,
        deadlineEpochMs: Long? = null,
        completedAtEpochMs: Long? = null,
        dependsOnGoalIds: Set<String> = emptySet()
    ): DurableGoalRecord = DurableGoalRecord(
        sourceGoalId = id,
        objective = "Objective for $id",
        priority = priority,
        status = status,
        firstSeenAtEpochMs = firstSeenAtEpochMs,
        updatedAtEpochMs = completedAtEpochMs ?: firstSeenAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        dependsOnGoalIds = dependsOnGoalIds,
        deadlineEpochMs = deadlineEpochMs
    )

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase321-provider"),
        name = "Phase321 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "goal coordination test contract",
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
