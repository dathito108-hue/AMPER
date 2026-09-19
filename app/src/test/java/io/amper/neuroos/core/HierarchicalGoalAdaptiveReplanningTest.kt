package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HierarchicalGoalAdaptiveReplanningTest {
    private val capability = CapabilityId("phase331.test")

    @Test
    fun progressProjectionTracksActiveCompletedAndSupersededLineage() {
        val portfolio = MemoryBackedDurableGoalPortfolio(InMemoryMemoryOs())
        portfolio.observe(
            listOf(DurableGoalCandidate("progress-root", "Complete the hierarchy", 0.9)),
            observedAtEpochMs = 1L
        )
        val applied = requireNotNull(
            portfolio.applyDecomposition(
                "progress-root",
                listOf(
                    DurableGoalDecompositionSpec(1, "Prepare source", 0.8),
                    DurableGoalDecompositionSpec(2, "Use source", 0.7, setOf(1)),
                    DurableGoalDecompositionSpec(3, "Independent evidence", 0.6)
                ),
                updatedAtEpochMs = 2L
            )
        )
        val failedLeaf = applied.children[0]
        val completedSibling = applied.children[2]
        portfolio.markCompleted(completedSibling.sourceGoalId, completedAtEpochMs = 3L)
        portfolio.markAdaptiveReplanAttempt(failedLeaf.sourceGoalId, attemptedAtEpochMs = 4L)
        val replacement = requireNotNull(
            portfolio.replacePendingLeaf(
                failedLeaf.sourceGoalId,
                listOf(
                    DurableGoalDecompositionSpec(1, "Alternative source", 0.75)
                ),
                updatedAtEpochMs = 5L
            )
        )

        val progress = DurableGoalHierarchyProgressPolicy.snapshot(
            records = portfolio.snapshot(),
            rootGoalId = "progress-root"
        )

        assertTrue(completedSibling.sourceGoalId in progress.completedGoalIds)
        assertTrue(failedLeaf.sourceGoalId in progress.supersededGoalIds)
        assertTrue(replacement.replacements.single().sourceGoalId in progress.pendingGoalIds)
        assertTrue(progress.completionRatio > 0.0)
        assertFalse(progress.authorityBearing)
    }

    @Test
    fun v4CodecMigrationInfersCanonicalParentLineageAndReencodesV5() {
        fun enc(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        val rootId = "phase331-v4-root"
        val childId = rootId + "::subgoal:1:0123456789abcdef01234567"
        val v4 = buildString {
            appendLine("AMPER_DURABLE_GOAL_PORTFOLIO_V4")
            append("GOAL\t")
            append(enc(rootId)).append('\t')
            append(enc("Migrated root")).append('\t')
            append("0.9\tPENDING\t1\t2\t~\t1\t2\t")
            append(enc(childId)).append('\t')
            append("~\tDECOMPOSED\t0")
            appendLine()
            append("GOAL\t")
            append(enc(childId)).append('\t')
            append(enc("Migrated child")).append('\t')
            append("0.8\tPENDING\t2\t2\t~\t0\t~\t~\t~\tNONE\t1")
        }

        val decoded = DurableGoalPortfolioCodec.decode(v4).getOrThrow()
        val child = decoded.single { it.sourceGoalId == childId }

        assertEquals(rootId, child.parentGoalId)
        assertEquals(0, child.replanGeneration)
        assertNull(child.supersededAtEpochMs)
        assertNull(child.adaptiveReplanAttemptedAtEpochMs)

        val encoded = DurableGoalPortfolioCodec.encode(decoded)
        assertTrue(encoded.startsWith("AMPER_DURABLE_GOAL_PORTFOLIO_V5"))
    }

    @Test
    fun adaptiveReplacementRewiresOnlyPendingDependantsAndPreservesCompletedSibling() {
        val portfolio = MemoryBackedDurableGoalPortfolio(InMemoryMemoryOs())
        portfolio.observe(
            listOf(DurableGoalCandidate("replace-root", "Complete replaceable branch", 0.9)),
            observedAtEpochMs = 10L
        )
        val applied = requireNotNull(
            portfolio.applyDecomposition(
                "replace-root",
                listOf(
                    DurableGoalDecompositionSpec(1, "Failing leaf", 0.8),
                    DurableGoalDecompositionSpec(2, "Dependent leaf", 0.7, setOf(1)),
                    DurableGoalDecompositionSpec(3, "Completed independent leaf", 0.6)
                ),
                updatedAtEpochMs = 11L
            )
        )
        val source = applied.children[0]
        val dependent = applied.children[1]
        val completed = applied.children[2]
        portfolio.markCompleted(completed.sourceGoalId, completedAtEpochMs = 12L)
        portfolio.markAdaptiveReplanAttempt(source.sourceGoalId, attemptedAtEpochMs = 13L)

        val replacement = requireNotNull(
            portfolio.replacePendingLeaf(
                source.sourceGoalId,
                listOf(
                    DurableGoalDecompositionSpec(1, "Collect alternative evidence", 1.0),
                    DurableGoalDecompositionSpec(
                        2,
                        "Validate alternative evidence",
                        0.75,
                        setOf(1)
                    )
                ),
                updatedAtEpochMs = 14L
            )
        )
        val replacementIds = replacement.replacements.map { it.sourceGoalId }.toSet()
        val restoredDependent = requireNotNull(portfolio.get(dependent.sourceGoalId))
        val restoredParent = requireNotNull(portfolio.get("replace-root"))
        val restoredCompleted = requireNotNull(portfolio.get(completed.sourceGoalId))

        assertEquals(DurableGoalStatus.SUPERSEDED, portfolio.get(source.sourceGoalId)?.status)
        assertEquals(14L, portfolio.get(source.sourceGoalId)?.supersededAtEpochMs)
        assertEquals(DurableGoalStatus.COMPLETED, restoredCompleted.status)
        assertEquals(completed.completedAtEpochMs ?: 12L, restoredCompleted.completedAtEpochMs)
        assertFalse(source.sourceGoalId in restoredDependent.dependsOnGoalIds)
        assertTrue(restoredDependent.dependsOnGoalIds.containsAll(replacementIds))
        assertFalse(source.sourceGoalId in restoredParent.dependsOnGoalIds)
        assertTrue(restoredParent.dependsOnGoalIds.containsAll(replacementIds))
        assertTrue(replacement.replacements.all { it.priority <= source.priority })
        assertTrue(replacement.replacements.all { it.parentGoalId == "replace-root" })
        assertTrue(replacement.replacements.all { it.replanGeneration == 1 })
        assertFalse(replacement.authorityBearing)
    }

    @Test
    fun adaptiveReplacementRefusesToRewriteCompletedDependencyEvidence() {
        val portfolio = MemoryBackedDurableGoalPortfolio(InMemoryMemoryOs())
        portfolio.observe(
            listOf(DurableGoalCandidate("evidence-root", "Preserve completed evidence", 0.9)),
            observedAtEpochMs = 1L
        )
        val applied = requireNotNull(
            portfolio.applyDecomposition(
                "evidence-root",
                listOf(
                    DurableGoalDecompositionSpec(1, "Original prerequisite", 0.8),
                    DurableGoalDecompositionSpec(2, "Already completed dependent", 0.7, setOf(1))
                ),
                updatedAtEpochMs = 2L
            )
        )
        val source = applied.children[0]
        val dependent = applied.children[1]
        portfolio.markCompleted(dependent.sourceGoalId, completedAtEpochMs = 3L)
        portfolio.markAdaptiveReplanAttempt(source.sourceGoalId, attemptedAtEpochMs = 4L)

        val attempt = runCatching {
            portfolio.replacePendingLeaf(
                source.sourceGoalId,
                listOf(DurableGoalDecompositionSpec(1, "Alternative prerequisite", 0.7)),
                updatedAtEpochMs = 5L
            )
        }

        assertTrue(attempt.isFailure)
        assertEquals(DurableGoalStatus.PENDING, portfolio.get(source.sourceGoalId)?.status)
        assertEquals(DurableGoalStatus.COMPLETED, portfolio.get(dependent.sourceGoalId)?.status)
        assertTrue(source.sourceGoalId in requireNotNull(portfolio.get(dependent.sourceGoalId)).dependsOnGoalIds)
    }

    @Test
    fun adaptiveProtocolIsStrictAndRejectsForwardReplacementDependency() {
        val valid = GoalAdaptiveReplanProtocol.parse(
            """
                <AMPER_GOAL_REPLAN_V1>
                verdict=REPLACE
                replacement.1.objective=Collect alternative evidence
                replacement.1.priority=0.8
                replacement.1.depends=~
                replacement.2.objective=Validate alternative evidence
                replacement.2.priority=0.7
                replacement.2.depends=1
                </AMPER_GOAL_REPLAN_V1>
            """.trimIndent()
        ).getOrThrow()

        assertEquals(GoalAdaptiveReplanVerdict.REPLACE, valid.verdict)
        assertEquals(2, valid.replacements.size)
        assertEquals(setOf(1), valid.replacements[1].dependsOnIndices)
        assertFalse(valid.authorityBearing)

        val invalid = GoalAdaptiveReplanProtocol.parse(
            """
                <AMPER_GOAL_REPLAN_V1>
                verdict=REPLACE
                replacement.1.objective=Invalid forward edge
                replacement.1.priority=0.8
                replacement.1.depends=2
                replacement.2.objective=Later replacement
                replacement.2.priority=0.7
                replacement.2.depends=~
                </AMPER_GOAL_REPLAN_V1>
            """.trimIndent()
        )
        assertTrue(invalid.isFailure)
    }

    @Test
    fun inferenceAdaptiveReplannerUsesExactlyOneReasoningInference() {
        val runtime = AmperRuntime.reference()
        var inferenceCalls = 0
        val inference = CognitiveInferencePort {
            inferenceCalls += 1
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase332-replanner"),
                    backendId = "phase332-replanner",
                    text = """
                        <AMPER_GOAL_REPLAN_V1>
                        verdict=REPLACE
                        replacement.1.objective=Use bounded alternate path
                        replacement.1.priority=0.7
                        replacement.1.depends=~
                        </AMPER_GOAL_REPLAN_V1>
                    """.trimIndent(),
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
        val goal = leafRecord(
            id = "phase332-leaf",
            parentId = "phase332-root",
            objective = "Failed bounded leaf"
        )
        val plan = failedPlan(
            id = "phase332-plan",
            conversationId = ConversationId("phase332-conversation"),
            goal = goal.objective
        )
        val checkpoint = exhaustedCheckpoint(goal, plan)
        val progress = DurableGoalHierarchyProgress(
            rootGoalId = "phase332-root",
            trackedGoalIds = setOf("phase332-root", goal.sourceGoalId),
            pendingGoalIds = setOf("phase332-root", goal.sourceGoalId),
            completedGoalIds = emptySet(),
            supersededGoalIds = emptySet(),
            runnablePendingGoalIds = setOf(goal.sourceGoalId),
            blockedPendingGoalIds = setOf("phase332-root"),
            completionRatio = 0.0
        )
        val replanner = InferenceGoalAdaptiveReplanner(
            inference = inference,
            stateSource = runtime.integratedCognition,
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) }
        )

        val assessment = replanner.replan(
            checkpoint = checkpoint,
            failedPlan = plan,
            goal = goal,
            progress = progress
        ).getOrThrow()

        assertEquals(1, inferenceCalls)
        assertEquals(GoalAdaptiveReplanVerdict.REPLACE, assessment.verdict)
        assertEquals("phase332-replanner", assessment.backendId)
    }

    @Test
    fun exhaustedUnexecutedLeafIsReplannedAndCheckpointReleased() {
        val runtime = AmperRuntime.reference()
        runtime.goalPortfolio.observe(
            listOf(DurableGoalCandidate("phase333-root", "Finish robust hierarchy", 0.95)),
            observedAtEpochMs = 1L
        )
        val applied = requireNotNull(
            runtime.goalPortfolio.applyDecomposition(
                "phase333-root",
                listOf(
                    DurableGoalDecompositionSpec(1, "Original failing leaf", 0.85),
                    DurableGoalDecompositionSpec(2, "Dependent leaf", 0.75, setOf(1)),
                    DurableGoalDecompositionSpec(3, "Independent completed leaf", 0.65)
                ),
                updatedAtEpochMs = 2L
            )
        )
        val failedLeaf = applied.children[0]
        val dependent = applied.children[1]
        val completedSibling = applied.children[2]
        runtime.goalPortfolio.markCompleted(completedSibling.sourceGoalId, completedAtEpochMs = 3L)

        val plan = failedPlan(
            id = "phase333-failed-plan",
            conversationId = ConversationId("phase333-conversation"),
            goal = failedLeaf.objective
        )
        runtime.plans.save(plan)
        runtime.persistentGoalExecutiveStore.save(exhaustedCheckpoint(failedLeaf, plan))

        var replanningCalls = 0
        val coordinator = PersistentGoalExecutiveCoordinator(
            context = runtime.context,
            executive = unusedExecutive(runtime),
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans,
            portfolio = runtime.goalPortfolio,
            adaptiveReplanner = GoalAdaptiveReplanner { _, _, _, progress ->
                replanningCalls += 1
                assertTrue(completedSibling.sourceGoalId in progress.completedGoalIds)
                Result.success(
                    GoalAdaptiveReplanAssessment(
                        verdict = GoalAdaptiveReplanVerdict.REPLACE,
                        replacements = listOf(
                            DurableGoalDecompositionSpec(
                                1,
                                "Alternative bounded leaf",
                                0.80
                            )
                        )
                    )
                )
            },
            clock = { 100L }
        )

        val result = coordinator.runNext(plan.conversationId).getOrThrow()

        assertTrue(result is PersistentGoalExecutiveResult.Replanned)
        val replanned = result as PersistentGoalExecutiveResult.Replanned
        assertEquals(1, replanningCalls)
        assertEquals("phase333-root", replanned.parentGoalId)
        assertEquals(failedLeaf.sourceGoalId, replanned.supersededGoalId)
        assertEquals(1, replanned.replacementGoalIds.size)
        assertNull(coordinator.current())
        assertEquals(DurableGoalStatus.SUPERSEDED, runtime.goalPortfolio.get(failedLeaf.sourceGoalId)?.status)
        assertEquals(DurableGoalStatus.COMPLETED, runtime.goalPortfolio.get(completedSibling.sourceGoalId)?.status)
        val dependentAfter = requireNotNull(runtime.goalPortfolio.get(dependent.sourceGoalId))
        assertFalse(failedLeaf.sourceGoalId in dependentAfter.dependsOnGoalIds)
        assertTrue(dependentAfter.dependsOnGoalIds.containsAll(replanned.replacementGoalIds))
    }

    @Test
    fun keepBlockedDecisionIsPersistedAtMostOnceAndDoesNotHotLoopInference() {
        val runtime = AmperRuntime.reference()
        runtime.goalPortfolio.observe(
            listOf(DurableGoalCandidate("phase334-root", "Keep blocked hierarchy", 0.9)),
            observedAtEpochMs = 1L
        )
        val applied = requireNotNull(
            runtime.goalPortfolio.applyDecomposition(
                "phase334-root",
                listOf(
                    DurableGoalDecompositionSpec(1, "Blocked leaf", 0.8),
                    DurableGoalDecompositionSpec(2, "Dependent leaf", 0.7, setOf(1))
                ),
                updatedAtEpochMs = 2L
            )
        )
        val leaf = applied.children[0]
        val plan = failedPlan(
            id = "phase334-plan",
            conversationId = ConversationId("phase334-conversation"),
            goal = leaf.objective
        )
        runtime.plans.save(plan)
        runtime.persistentGoalExecutiveStore.save(exhaustedCheckpoint(leaf, plan))

        var replanningCalls = 0
        val coordinator = PersistentGoalExecutiveCoordinator(
            context = runtime.context,
            executive = unusedExecutive(runtime),
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans,
            portfolio = runtime.goalPortfolio,
            adaptiveReplanner = GoalAdaptiveReplanner { _, _, _, _ ->
                replanningCalls += 1
                Result.success(
                    GoalAdaptiveReplanAssessment(GoalAdaptiveReplanVerdict.KEEP_BLOCKED)
                )
            },
            clock = { 50L }
        )

        val first = coordinator.runNext(plan.conversationId).getOrThrow()
        val second = coordinator.runNext(plan.conversationId).getOrThrow()

        assertTrue(first is PersistentGoalExecutiveResult.Deferred)
        assertTrue(second is PersistentGoalExecutiveResult.Deferred)
        assertEquals(1, replanningCalls)
        assertNotNull(runtime.goalPortfolio.get(leaf.sourceGoalId)?.adaptiveReplanAttemptedAtEpochMs)
        assertEquals(DurableGoalStatus.PENDING, runtime.goalPortfolio.get(leaf.sourceGoalId)?.status)
    }

    @Test
    fun executedOrAuthorityBlockedEvidenceIsNeverEligibleForAdaptiveRewrite() {
        val goal = leafRecord(
            id = "phase335-leaf",
            parentId = "phase335-root",
            objective = "Do not rewrite executed evidence"
        )
        val conversation = ConversationId("phase335-conversation")
        val executedPlan = failedPlan(
            id = "phase335-executed-plan",
            conversationId = conversation,
            goal = goal.objective
        ).copy(
            steps = listOf(
                failedPlan(
                    id = "phase335-template",
                    conversationId = conversation,
                    goal = goal.objective
                ).steps.single().copy(status = PlanStepStatus.EXECUTED)
            )
        )
        val deniedPlan = failedPlan(
            id = "phase335-denied-plan",
            conversationId = conversation,
            goal = goal.objective
        ).copy(
            steps = listOf(
                failedPlan(
                    id = "phase335-template-2",
                    conversationId = conversation,
                    goal = goal.objective
                ).steps.single().copy(status = PlanStepStatus.DENIED)
            )
        )

        assertFalse(
            GoalAdaptiveReplanningEligibility.isEligible(
                exhaustedCheckpoint(goal, executedPlan),
                executedPlan,
                goal,
                listOf(goal)
            )
        )
        assertFalse(
            GoalAdaptiveReplanningEligibility.isEligible(
                exhaustedCheckpoint(goal, deniedPlan),
                deniedPlan,
                goal,
                listOf(goal)
            )
        )
    }

    private fun exhaustedCheckpoint(
        goal: DurableGoalRecord,
        plan: SovereignPlan
    ): PersistentGoalExecutiveCheckpoint = PersistentGoalExecutiveCheckpoint(
        sourceGoalId = goal.sourceGoalId,
        objective = goal.objective,
        conversationId = plan.conversationId,
        priority = goal.priority,
        stage = PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED,
        plannedPlanId = plan.id,
        recoveryCount = PersistentGoalExecutiveCheckpoint.MAX_RECOVERY_GENERATIONS,
        lastFailureCode = "RECOVERY_LIMIT_REACHED",
        updatedAtEpochMs = 20L
    )

    private fun failedPlan(
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
                reason = "Attempt bounded failed operation",
                input = "read",
                status = PlanStepStatus.FAILED,
                boundToolId = descriptor().id,
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase331-planner"
    )

    private fun leafRecord(
        id: String,
        parentId: String,
        objective: String
    ): DurableGoalRecord = DurableGoalRecord(
        sourceGoalId = id,
        objective = objective,
        priority = 0.8,
        status = DurableGoalStatus.PENDING,
        firstSeenAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        decompositionState = DurableGoalDecompositionState.ATOMIC,
        decompositionDepth = 1,
        parentGoalId = parentId
    )

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase331-provider"),
        name = "Phase331 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "hierarchical adaptive replanning test contract",
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
                Result.failure(IllegalStateException("adaptive replan path must not create a plan"))
            },
            practiceOne = {
                Result.failure(IllegalStateException("adaptive replan path must not practice"))
            }
        )
}
