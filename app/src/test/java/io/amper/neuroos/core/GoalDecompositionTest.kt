package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalDecompositionTest {
    private val capability = CapabilityId("phase326.test")

    @Test
    fun protocolParsesAtomicAndBoundedDagAndRejectsForwardReferences() {
        val atomic = GoalDecompositionProtocol.parse(
            """
                <AMPER_GOAL_DECOMPOSE_V1>
                verdict=ATOMIC
                </AMPER_GOAL_DECOMPOSE_V1>
            """.trimIndent()
        ).getOrThrow()
        assertEquals(GoalDecompositionVerdict.ATOMIC, atomic.verdict)
        assertTrue(atomic.subgoals.isEmpty())

        val decomposed = GoalDecompositionProtocol.parse(
            """
                <AMPER_GOAL_DECOMPOSE_V1>
                verdict=DECOMPOSED
                subgoal.1.objective=Collect bounded evidence
                subgoal.1.priority=0.9
                subgoal.1.depends=~
                subgoal.2.objective=Transform the verified evidence
                subgoal.2.priority=0.8
                subgoal.2.depends=1
                subgoal.3.objective=Verify the composed result
                subgoal.3.priority=0.7
                subgoal.3.depends=1,2
                </AMPER_GOAL_DECOMPOSE_V1>
            """.trimIndent()
        ).getOrThrow()

        assertEquals(GoalDecompositionVerdict.DECOMPOSED, decomposed.verdict)
        assertEquals(3, decomposed.subgoals.size)
        assertEquals(setOf(1, 2), decomposed.subgoals[2].dependsOnIndices)
        assertFalse(decomposed.authorityBearing)

        val forwardReference = GoalDecompositionProtocol.parse(
            """
                <AMPER_GOAL_DECOMPOSE_V1>
                verdict=DECOMPOSED
                subgoal.1.objective=Invalid first goal
                subgoal.1.priority=0.8
                subgoal.1.depends=2
                subgoal.2.objective=Second goal
                subgoal.2.priority=0.7
                subgoal.2.depends=~
                </AMPER_GOAL_DECOMPOSE_V1>
            """.trimIndent()
        )
        assertTrue(forwardReference.isFailure)

        val nonContiguous = GoalDecompositionProtocol.parse(
            """
                <AMPER_GOAL_DECOMPOSE_V1>
                verdict=DECOMPOSED
                subgoal.1.objective=First goal
                subgoal.1.priority=0.8
                subgoal.1.depends=~
                subgoal.3.objective=Skipped index
                subgoal.3.priority=0.7
                subgoal.3.depends=1
                </AMPER_GOAL_DECOMPOSE_V1>
            """.trimIndent()
        )
        assertTrue(nonContiguous.isFailure)
    }

    @Test
    fun inferenceDecomposerPerformsExactlyOneReasoningInference() {
        val runtime = AmperRuntime.reference()
        var inferenceCalls = 0
        val inference = CognitiveInferencePort {
            inferenceCalls += 1
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase327-decomposer"),
                    backendId = "phase327-decomposer",
                    text = """
                        <AMPER_GOAL_DECOMPOSE_V1>
                        verdict=DECOMPOSED
                        subgoal.1.objective=Prepare evidence
                        subgoal.1.priority=0.8
                        subgoal.1.depends=~
                        subgoal.2.objective=Verify prepared evidence
                        subgoal.2.priority=0.7
                        subgoal.2.depends=1
                        </AMPER_GOAL_DECOMPOSE_V1>
                    """.trimIndent(),
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
        val decomposer = InferenceGoalDecomposer(
            inference = inference,
            stateSource = runtime.integratedCognition,
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) }
        )
        val goal = record(
            id = "phase327-goal",
            objective = "Prepare and verify bounded evidence",
            priority = 0.8
        )

        val assessment = decomposer.decompose(goal).getOrThrow()

        assertEquals(1, inferenceCalls)
        assertEquals(GoalDecompositionVerdict.DECOMPOSED, assessment.verdict)
        assertEquals(2, assessment.subgoals.size)
        assertEquals("phase327-decomposer", assessment.backendId)
        assertFalse(assessment.authorityBearing)
    }

    @Test
    fun atomicPortfolioApplicationCreatesDeterministicBoundedChildren() {
        val memoryA = InMemoryMemoryOs()
        val first = MemoryBackedDurableGoalPortfolio(memoryA)
        first.observe(
            listOf(
                DurableGoalCandidate(
                    sourceGoalId = "phase328-parent",
                    objective = "Complete a bounded two-stage objective",
                    priority = 0.60
                )
            ),
            observedAtEpochMs = 10L
        )
        first.setDeadline(
            sourceGoalId = "phase328-parent",
            deadlineEpochMs = 50_000L,
            updatedAtEpochMs = 11L
        )
        val specs = listOf(
            DurableGoalDecompositionSpec(
                index = 1,
                objective = "Prepare the bounded input",
                priority = 0.95
            ),
            DurableGoalDecompositionSpec(
                index = 2,
                objective = "Verify the bounded output",
                priority = 0.50,
                dependsOnIndices = setOf(1)
            )
        )

        val applied = requireNotNull(
            first.applyDecomposition(
                sourceGoalId = "phase328-parent",
                specs = specs,
                updatedAtEpochMs = 20L
            )
        )

        assertEquals(DurableGoalDecompositionState.DECOMPOSED, applied.parent.decompositionState)
        assertEquals(2, applied.children.size)
        assertEquals(0.60, applied.children[0].priority, 0.0)
        assertEquals(0.50, applied.children[1].priority, 0.0)
        assertEquals(50_000L, applied.children[0].deadlineEpochMs)
        assertEquals(1, applied.children[0].decompositionDepth)
        assertEquals(
            setOf(applied.children[0].sourceGoalId),
            applied.children[1].dependsOnGoalIds
        )
        assertTrue(
            applied.parent.dependsOnGoalIds.containsAll(
                applied.children.map { it.sourceGoalId }
            )
        )

        val memoryB = InMemoryMemoryOs()
        val second = MemoryBackedDurableGoalPortfolio(memoryB)
        second.observe(
            listOf(
                DurableGoalCandidate(
                    sourceGoalId = "phase328-parent",
                    objective = "Complete a bounded two-stage objective",
                    priority = 0.60
                )
            ),
            observedAtEpochMs = 10L
        )
        second.setDeadline("phase328-parent", 50_000L, updatedAtEpochMs = 11L)
        val reapplied = requireNotNull(
            second.applyDecomposition(
                sourceGoalId = "phase328-parent",
                specs = specs,
                updatedAtEpochMs = 20L
            )
        )
        assertEquals(
            applied.children.map { it.sourceGoalId },
            reapplied.children.map { it.sourceGoalId }
        )
    }

    @Test
    fun decompositionCapacityFailureLeavesPortfolioUnchanged() {
        val portfolio = MemoryBackedDurableGoalPortfolio(InMemoryMemoryOs())
        val candidates = (0 until MemoryBackedDurableGoalPortfolio.MAX_PENDING).map { index ->
            DurableGoalCandidate(
                sourceGoalId = if (index == 0) "capacity-parent" else "capacity-goal-" + index,
                objective = "Capacity objective " + index,
                priority = if (index == 0) 1.0 else 0.5
            )
        }
        portfolio.observe(candidates, observedAtEpochMs = 1L)
        val before = portfolio.snapshot()

        val attempt = runCatching {
            portfolio.applyDecomposition(
                sourceGoalId = "capacity-parent",
                specs = listOf(
                    DurableGoalDecompositionSpec(1, "Child one", 1.0),
                    DurableGoalDecompositionSpec(2, "Child two", 1.0, setOf(1))
                ),
                updatedAtEpochMs = 2L
            )
        }

        assertTrue(attempt.isFailure)
        assertEquals(before, portfolio.snapshot())
        assertEquals(
            DurableGoalDecompositionState.NONE,
            requireNotNull(portfolio.get("capacity-parent")).decompositionState
        )
    }

    @Test
    fun codecV4MigratesV3WithoutLosingCoordinationMetadata() {
        fun enc(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        val v3 = buildString {
            appendLine("AMPER_DURABLE_GOAL_PORTFOLIO_V3")
            append("GOAL\t")
            append(enc("phase329-v3-goal")).append('\t')
            append(enc("Migrated coordinated goal")).append('\t')
            append("0.7\tPENDING\t10\t20\t~\t2\t15\t")
            append(enc("completed-prerequisite")).append('\t')
            append("999999")
        }
        val restored = DurableGoalPortfolioCodec.decode(v3).getOrThrow().single()

        assertEquals(2, restored.selectionCount)
        assertEquals(15L, restored.lastSelectedAtEpochMs)
        assertEquals(setOf("completed-prerequisite"), restored.dependsOnGoalIds)
        assertEquals(999_999L, restored.deadlineEpochMs)
        assertEquals(DurableGoalDecompositionState.NONE, restored.decompositionState)
        assertEquals(0, restored.decompositionDepth)

        val encoded = DurableGoalPortfolioCodec.encode(
            listOf(
                restored.copy(
                    decompositionState = DurableGoalDecompositionState.ATOMIC,
                    decompositionDepth = 1
                )
            )
        )
        val roundTrip = DurableGoalPortfolioCodec.decode(encoded).getOrThrow().single()

        assertTrue(encoded.startsWith("AMPER_DURABLE_GOAL_PORTFOLIO_V4"))
        assertEquals(DurableGoalDecompositionState.ATOMIC, roundTrip.decompositionState)
        assertEquals(1, roundTrip.decompositionDepth)
        assertEquals(restored.dependsOnGoalIds, roundTrip.dependsOnGoalIds)
        assertEquals(restored.deadlineEpochMs, roundTrip.deadlineEpochMs)
    }

    @Test
    fun decomposedParentRunsOnlyAfterChildrenAndStillRequiresFinalPlan() {
        val runtime = AmperRuntime.reference()
        runtime.goalPortfolio.observe(
            listOf(
                DurableGoalCandidate(
                    sourceGoalId = "phase330-parent",
                    objective = "Prepare evidence and verify the final parent outcome",
                    priority = 0.90
                )
            ),
            observedAtEpochMs = 10L
        )
        val state = readyState(runtime, "bounded decomposition")
        var planCalls = 0
        var decompositionCalls = 0
        val executive = AutonomousCognitiveExecutive(
            stateSource = fixedState(state),
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { conversationId, goal ->
                planCalls += 1
                Result.success(plan("phase330-plan-" + planCalls, conversationId, goal))
            },
            practiceOne = {
                Result.failure(IllegalStateException("ready decomposition goal must plan"))
            }
        )
        val decomposer = GoalDecomposer { goal ->
            decompositionCalls += 1
            if (goal.sourceGoalId == "phase330-parent") {
                Result.success(
                    GoalDecompositionAssessment(
                        verdict = GoalDecompositionVerdict.DECOMPOSED,
                        subgoals = listOf(
                            DurableGoalDecompositionSpec(
                                index = 1,
                                objective = "Prepare bounded evidence",
                                priority = 0.90
                            ),
                            DurableGoalDecompositionSpec(
                                index = 2,
                                objective = "Verify bounded evidence",
                                priority = 0.80,
                                dependsOnIndices = setOf(1)
                            )
                        )
                    )
                )
            } else {
                Result.success(
                    GoalDecompositionAssessment(GoalDecompositionVerdict.ATOMIC)
                )
            }
        }
        var now = 100L
        val goals = PersistentGoalExecutiveCoordinator(
            context = emptyGoalContext(runtime),
            executive = executive,
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans,
            portfolio = runtime.goalPortfolio,
            decomposer = decomposer,
            clock = { now++ }
        )
        val conversationId = ConversationId("phase330-conversation")

        val first = goals.runNext(conversationId).getOrThrow()
        assertTrue(first is PersistentGoalExecutiveResult.Decomposed)
        val decomposition = first as PersistentGoalExecutiveResult.Decomposed
        assertEquals("phase330-parent", decomposition.parentGoalId)
        assertEquals(2, decomposition.childGoalIds.size)
        assertNull(goals.current())
        assertEquals(0, planCalls)

        val firstChildRun = goals.runNext(conversationId).getOrThrow()
        assertTrue(firstChildRun is PersistentGoalExecutiveResult.Ran)
        val firstChild = (firstChildRun as PersistentGoalExecutiveResult.Ran).checkpoint
        assertEquals(decomposition.childGoalIds[0], firstChild.sourceGoalId)
        executeAndComplete(runtime, goals, requireNotNull(firstChild.plannedPlanId))

        val secondChildRun = goals.runNext(conversationId).getOrThrow()
        assertTrue(secondChildRun is PersistentGoalExecutiveResult.Ran)
        val secondChild = (secondChildRun as PersistentGoalExecutiveResult.Ran).checkpoint
        assertEquals(decomposition.childGoalIds[1], secondChild.sourceGoalId)
        executeAndComplete(runtime, goals, requireNotNull(secondChild.plannedPlanId))

        val parentRun = goals.runNext(conversationId).getOrThrow()
        assertTrue(parentRun is PersistentGoalExecutiveResult.Ran)
        val parentCheckpoint = (parentRun as PersistentGoalExecutiveResult.Ran).checkpoint
        assertEquals("phase330-parent", parentCheckpoint.sourceGoalId)
        assertEquals(PersistentGoalExecutiveStage.PLANNED, parentCheckpoint.stage)
        assertEquals(DurableGoalStatus.PENDING, runtime.goalPortfolio.get("phase330-parent")?.status)
        assertEquals(DurableGoalDecompositionState.DECOMPOSED, runtime.goalPortfolio.get("phase330-parent")?.decompositionState)
        assertEquals(3, planCalls)
        assertEquals(3, decompositionCalls)
    }

    @Test
    fun maximumDepthGoalIsMarkedAtomicWithoutAnotherDecompositionInference() {
        val runtime = AmperRuntime.reference()
        runtime.goalPortfolio.observe(
            listOf(DurableGoalCandidate("depth-root", "Depth root", 0.9)),
            observedAtEpochMs = 1L
        )
        val levelOne = requireNotNull(
            runtime.goalPortfolio.applyDecomposition(
                "depth-root",
                listOf(
                    DurableGoalDecompositionSpec(1, "Depth child one", 0.9),
                    DurableGoalDecompositionSpec(2, "Depth child two", 0.8, setOf(1))
                ),
                updatedAtEpochMs = 2L
            )
        )
        val levelTwo = requireNotNull(
            runtime.goalPortfolio.applyDecomposition(
                levelOne.children[0].sourceGoalId,
                listOf(
                    DurableGoalDecompositionSpec(1, "Depth grandchild one", 0.9),
                    DurableGoalDecompositionSpec(2, "Depth grandchild two", 0.8, setOf(1))
                ),
                updatedAtEpochMs = 3L
            )
        )
        val target = levelTwo.children[0]
        assertEquals(DurableGoalRecord.MAX_DECOMPOSITION_DEPTH, target.decompositionDepth)

        val state = readyState(runtime, target.objective)
        var decompositionCalls = 0
        val executive = AutonomousCognitiveExecutive(
            stateSource = fixedState(state),
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { conversationId, goal ->
                Result.success(plan("depth-plan", conversationId, goal))
            },
            practiceOne = {
                Result.failure(IllegalStateException("maximum-depth goal must plan"))
            }
        )
        val goals = PersistentGoalExecutiveCoordinator(
            context = emptyGoalContext(runtime),
            executive = executive,
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans,
            portfolio = runtime.goalPortfolio,
            decomposer = GoalDecomposer {
                decompositionCalls += 1
                Result.failure(IllegalStateException("depth cap must prevent inference"))
            },
            clock = { 10L }
        )

        val result = goals.runNext(ConversationId("depth-conversation")).getOrThrow()

        assertTrue(result is PersistentGoalExecutiveResult.Ran)
        val checkpoint = (result as PersistentGoalExecutiveResult.Ran).checkpoint
        assertEquals(target.sourceGoalId, checkpoint.sourceGoalId)
        assertEquals(0, decompositionCalls)
        assertEquals(
            DurableGoalDecompositionState.ATOMIC,
            runtime.goalPortfolio.get(target.sourceGoalId)?.decompositionState
        )
    }

    private fun executeAndComplete(
        runtime: AmperRuntime,
        goals: PersistentGoalExecutiveCoordinator,
        planId: PlanId
    ) {
        val stored = requireNotNull(runtime.plans.load(planId))
        runtime.plans.save(
            stored.copy(
                steps = stored.steps.map { it.copy(status = PlanStepStatus.EXECUTED) }
            )
        )
        val completed = goals.completePlanned(planId).getOrThrow()
        assertEquals(PersistentGoalExecutiveStage.COMPLETED, completed.stage)
    }

    private fun plan(
        id: String,
        conversationId: ConversationId,
        goal: String
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = conversationId,
        goal = goal,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId(id + "-request"),
                capability = capability,
                reason = "Execute one bounded verification step",
                input = "read",
                boundToolId = descriptor().id,
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase330-planner"
    )

    private fun record(
        id: String,
        objective: String,
        priority: Double
    ): DurableGoalRecord = DurableGoalRecord(
        sourceGoalId = id,
        objective = objective,
        priority = priority,
        status = DurableGoalStatus.PENDING,
        firstSeenAtEpochMs = 1L,
        updatedAtEpochMs = 1L
    )

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase326-provider"),
        name = "Phase326 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded decomposition test contract",
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

    private fun fixedState(
        state: IntegratedCognitiveStatePacket
    ): IntegratedCognitiveStateSource = object : IntegratedCognitiveStateSource {
        override fun capture(
            query: String,
            allowedCapabilities: Set<CapabilityId>,
            descriptors: Collection<ToolDescriptor>
        ): IntegratedCognitiveStatePacket = state
    }

    private fun emptyGoalContext(runtime: AmperRuntime): SovereignContextSource {
        val delegate = runtime.context
        return object : SovereignContextSource {
            override fun capture(
                query: String,
                memoryLimit: Int,
                worldLimit: Int,
                workspaceLimit: Int
            ): SovereignContextSnapshot =
                delegate.capture(query, memoryLimit, worldLimit, workspaceLimit)
                    .copy(goals = emptyList())

            override fun groundedPrompt(query: String, charBudget: Int): String =
                delegate.groundedPrompt(query, charBudget)

            override fun rememberAssistantResponse(
                userPrompt: String,
                response: String,
                backendId: String,
                confidence: Double
            ) {
                delegate.rememberAssistantResponse(
                    userPrompt,
                    response,
                    backendId,
                    confidence
                )
            }
        }
    }
}
