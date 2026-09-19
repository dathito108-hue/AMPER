package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalOutcomeStrategyTransferTest {
    private val capability = CapabilityId("phase336.read")

    @Test
    fun goalFingerprintPersistsOnlyBoundedHashedTerms() {
        val fingerprint = GoalOutcomeFingerprint.of(
            "Deploy Alpha service and verify deployment health"
        )

        assertTrue(fingerprint.isNotEmpty())
        assertTrue(fingerprint.size <= GoalOutcomeFingerprint.MAX_TERMS)
        assertTrue(fingerprint.all { it.matches(Regex("[0-9a-f]{16}")) })
        assertFalse(fingerprint.any { it.contains("deploy", ignoreCase = true) })
        assertFalse(fingerprint.any { it.contains("alpha", ignoreCase = true) })
    }

    @Test
    fun verifiedObservationIsIdempotentAndTransfersToAnalogousGoal() {
        val model = MemoryBackedGoalOutcomeLearningModel(InMemoryMemoryOs())
        val plan = terminalPlan(
            id = "phase336-success-plan",
            goal = "Deploy alpha service and verify health"
        )
        val checkpoint = verifiedCheckpoint(
            goalId = "phase336-success-goal",
            objective = "Deploy alpha service and verify health",
            plan = plan
        )

        val first = model.observe(
            checkpoint = checkpoint,
            terminalPlan = plan,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            hierarchy = null,
            hierarchyDepth = 0,
            verificationConfidence = 0.93,
            observedAtEpochMs = 10L
        )
        val second = model.observe(
            checkpoint = checkpoint,
            terminalPlan = plan,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            hierarchy = null,
            hierarchyDepth = 0,
            verificationConfidence = 0.93,
            observedAtEpochMs = 20L
        )

        assertEquals(first, second)
        assertEquals(1, model.recent(10).size)
        val transfer = model.transfer(
            goal = "Deploy alpha module and verify service",
            allowedCapabilities = setOf(capability)
        )
        assertEquals(1, transfer.size)
        assertEquals(1, transfer.single().analogousSuccesses)
        assertEquals(0, transfer.single().executionExhaustions)
        assertTrue(transfer.single().transferSupport > 0.0)
        assertFalse(transfer.single().authorityBearing)
    }

    @Test
    fun executionFailurePenalizesTransferButAuthorityBlockDoesNotCountAsAttempt() {
        val model = MemoryBackedGoalOutcomeLearningModel(InMemoryMemoryOs())
        observeOutcome(
            model = model,
            goalId = "phase337-success",
            planId = "phase337-success-plan",
            objective = "Inspect alpha package integrity",
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            verified = true,
            observedAt = 10L
        )
        observeOutcome(
            model = model,
            goalId = "phase337-failure",
            planId = "phase337-failure-plan",
            objective = "Inspect alpha package state",
            outcome = GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
            verified = false,
            observedAt = 20L
        )
        observeOutcome(
            model = model,
            goalId = "phase337-authority",
            planId = "phase337-authority-plan",
            objective = "Inspect alpha package metadata",
            outcome = GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED,
            verified = false,
            observedAt = 30L
        )

        val candidate = model.transfer(
            goal = "Inspect alpha package",
            allowedCapabilities = setOf(capability)
        ).single()

        assertEquals(1, candidate.analogousSuccesses)
        assertEquals(1, candidate.executionExhaustions)
        assertEquals(1, candidate.authorityBlocks)
        assertEquals(2, candidate.comparableAttempts)
        assertEquals(0.5, candidate.analogousSuccessRate, 0.0)
    }

    @Test
    fun persistentVerifiedCompletionFeedsHierarchyOutcomeLearning() {
        val runtime = AmperRuntime.reference()
        runtime.goalPortfolio.observe(
            listOf(
                DurableGoalCandidate(
                    sourceGoalId = "phase338-root",
                    objective = "Validate beta artifact integrity",
                    priority = 0.9
                )
            ),
            observedAtEpochMs = 1L
        )
        runtime.goalPortfolio.markAtomic("phase338-root", updatedAtEpochMs = 2L)

        val plan = terminalPlan(
            id = "phase338-plan",
            goal = "Validate beta artifact integrity",
            conversationId = ConversationId("phase338-conversation")
        )
        runtime.plans.save(plan)
        runtime.persistentGoalExecutiveStore.save(
            PersistentGoalExecutiveCheckpoint(
                sourceGoalId = "phase338-root",
                objective = "Validate beta artifact integrity",
                conversationId = plan.conversationId,
                priority = 0.9,
                stage = PersistentGoalExecutiveStage.PLANNED,
                plannedPlanId = plan.id,
                updatedAtEpochMs = 3L
            )
        )

        val coordinator = PersistentGoalExecutiveCoordinator(
            context = runtime.context,
            executive = unusedExecutive(runtime),
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans,
            portfolio = runtime.goalPortfolio,
            outcomeLearning = runtime.goalOutcomeLearning,
            clock = { 10L }
        )
        val assessment = GoalSatisfactionAssessment(
            planId = plan.id,
            verdict = GoalSatisfactionVerdict.SATISFIED,
            confidence = 0.92,
            reason = "terminal evidence confirms the requested artifact validation",
            cognitiveStateDigest = "a".repeat(64),
            executionContextDigest = "b".repeat(64)
        )

        val completed = coordinator.resolveVerifiedSuccess(plan.id, assessment).getOrThrow()
        val learned = runtime.goalOutcomeLearning.recent(4).single()

        assertEquals(PersistentGoalExecutiveStage.COMPLETED, completed.stage)
        assertEquals(DurableGoalStatus.COMPLETED, runtime.goalPortfolio.get("phase338-root")?.status)
        assertEquals(GoalOutcomeEvidenceKind.VERIFIED_SUCCESS, learned.outcome)
        assertEquals(1, learned.hierarchyTrackedGoals)
        assertEquals(1, learned.hierarchyCompletedGoals)
        assertEquals(1.0, learned.hierarchyCompletionRatio, 0.0)
        assertEquals(0.92, learned.verificationConfidence ?: 0.0, 0.0)
    }

    @Test
    fun plannerReceivesOutcomeTransferWithoutExecutingOrLeakingHistoricPayloads() {
        val runtime = AmperRuntime.reference()
        val historic = terminalPlan(
            id = "phase339-history-plan",
            goal = "Inspect delta artifact integrity"
        )
        runtime.goalOutcomeLearning.observe(
            checkpoint = verifiedCheckpoint(
                goalId = "phase339-history-goal",
                objective = "Inspect delta artifact integrity",
                plan = historic
            ),
            terminalPlan = historic,
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            hierarchy = null,
            hierarchyDepth = 0,
            verificationConfidence = 0.94,
            observedAtEpochMs = 1L
        )
        repeat(6) { index ->
            runtime.competence.observe(
                ActionOutcome(
                    status = ActionStatus.EXECUTED,
                    proposal = ActionProposal(
                        requestId = ActionRequestId("phase339-history-execution-" + index),
                        capability = capability,
                        reason = "historic governed execution evidence",
                        input = "read"
                    ),
                    toolId = ToolId("phase336-provider"),
                    sideEffect = ToolSideEffect.READ_ONLY,
                    output = "ok"
                )
            )
        }

        var executions = 0
        val registry = InMemoryToolRegistry().also {
            it.register(provider { executions += 1 })
        }
        val audit = InMemoryToolAuditLog()
        val loop = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = audit
            )
        )
        val requests = mutableListOf<InferenceRequest>()
        val inference = CognitiveInferencePort { request ->
            requests += request
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase339-planner"),
                    backendId = "phase339-test-backend",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase336.read
                        step.1.reason=Inspect current artifact under live contract
                        step.1.input=read
                        </AMPER_PLAN_V1>
                    """.trimIndent(),
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = loop,
            advertisedCapabilities = setOf(capability)
        )

        val plan = planner.create(
            conversationId = ConversationId("phase339-live"),
            userGoal = "Inspect delta artifact metadata and integrity"
        ).getOrThrow()

        val prompt = requests.single().prompt
        assertTrue(prompt.contains("<GOAL_OUTCOME_COUNTERFACTUAL_TRANSFER>"))
        assertTrue(prompt.contains("candidate.1.capabilities=phase336.read"))
        assertTrue(prompt.contains("projected_support="))
        assertTrue(prompt.contains("cognitive_state_digest="))
        assertTrue(prompt.contains("authority=false"))
        assertFalse(prompt.contains("phase339-history-goal"))
        assertFalse(prompt.contains("phase339-history-plan"))
        assertFalse(prompt.contains("secret-input"))
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
        assertEquals(PlanStepStatus.PLANNED, plan.steps.single().status)
    }

    @Test
    fun renderedTransferCannotCarryOldExecutionOrApprovalPayloads() {
        val model = MemoryBackedGoalOutcomeLearningModel(InMemoryMemoryOs())
        observeOutcome(
            model = model,
            goalId = "phase340-goal",
            planId = "phase340-plan",
            objective = "Review gamma artifact",
            outcome = GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            verified = true,
            observedAt = 1L
        )

        val rendered = GoalOutcomeStrategyTransfer.render(
            model.transfer(
                goal = "Review gamma artifact integrity",
                allowedCapabilities = setOf(capability)
            )
        )

        assertTrue(rendered.contains("authority=false"))
        assertTrue(rendered.contains("Never reuse old inputs"))
        assertFalse(rendered.contains("phase340-goal"))
        assertFalse(rendered.contains("phase340-plan"))
        assertFalse(rendered.contains("secret-input"))
    }

    private fun observeOutcome(
        model: GoalOutcomeLearningModel,
        goalId: String,
        planId: String,
        objective: String,
        outcome: GoalOutcomeEvidenceKind,
        verified: Boolean,
        observedAt: Long
    ) {
        val status = when (outcome) {
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS,
            GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED -> PlanStepStatus.EXECUTED
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED -> PlanStepStatus.FAILED
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED -> PlanStepStatus.DENIED
            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED -> PlanStepStatus.FAILED
        }
        val plan = terminalPlan(planId, objective, status = status)
        val checkpoint = when (outcome) {
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS ->
                verifiedCheckpoint(goalId, objective, plan)
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED ->
                PersistentGoalExecutiveCheckpoint(
                    sourceGoalId = goalId,
                    objective = objective,
                    conversationId = plan.conversationId,
                    priority = 0.8,
                    stage = PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED,
                    plannedPlanId = plan.id,
                    recoveryCount = PersistentGoalExecutiveCheckpoint.MAX_RECOVERY_GENERATIONS,
                    lastFailureCode = "RECOVERY_LIMIT_REACHED",
                    updatedAtEpochMs = observedAt
                )
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED ->
                PersistentGoalExecutiveCheckpoint(
                    sourceGoalId = goalId,
                    objective = objective,
                    conversationId = plan.conversationId,
                    priority = 0.8,
                    stage = PersistentGoalExecutiveStage.RECOVERY_BLOCKED,
                    plannedPlanId = plan.id,
                    lastFailureCode = "AUTHORITY_OR_USER_BLOCK",
                    updatedAtEpochMs = observedAt
                )
            GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED ->
                PersistentGoalExecutiveCheckpoint(
                    sourceGoalId = goalId,
                    objective = objective,
                    conversationId = plan.conversationId,
                    priority = 0.8,
                    stage = PersistentGoalExecutiveStage.FOLLOW_UP_EXHAUSTED,
                    plannedPlanId = plan.id,
                    followUpCount = PersistentGoalExecutiveCheckpoint.MAX_FOLLOW_UP_GENERATIONS,
                    lastVerificationVerdict = GoalSatisfactionVerdict.FOLLOW_UP_REQUIRED,
                    lastVerificationConfidence = 0.70,
                    lastVerificationReason = "more evidence required",
                    lastFailureCode = "GOAL_FOLLOW_UP_LIMIT",
                    updatedAtEpochMs = observedAt
                )
            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED ->
                error("partial execution fixture requires a multi-step plan")
        }
        model.observe(
            checkpoint = checkpoint,
            terminalPlan = plan,
            outcome = outcome,
            hierarchy = null,
            hierarchyDepth = 0,
            verificationConfidence = if (verified) 0.95 else null,
            observedAtEpochMs = observedAt
        )
    }

    private fun verifiedCheckpoint(
        goalId: String,
        objective: String,
        plan: SovereignPlan
    ): PersistentGoalExecutiveCheckpoint = PersistentGoalExecutiveCheckpoint(
        sourceGoalId = goalId,
        objective = objective,
        conversationId = plan.conversationId,
        priority = 0.8,
        stage = PersistentGoalExecutiveStage.COMPLETED,
        plannedPlanId = plan.id,
        lastVerificationVerdict = GoalSatisfactionVerdict.SATISFIED,
        lastVerificationConfidence = 0.95,
        lastVerificationReason = "verified outcome",
        updatedAtEpochMs = 5L
    )

    private fun terminalPlan(
        id: String,
        goal: String,
        conversationId: ConversationId = ConversationId("phase336-conversation"),
        status: PlanStepStatus = PlanStepStatus.EXECUTED
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = conversationId,
        goal = goal,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId(id + "-request"),
                capability = capability,
                reason = "Use current live contract only",
                input = "secret-input",
                status = status,
                boundToolId = ToolId("phase336-provider"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase336-planner"
    )

    private fun provider(executions: () -> Unit): ToolProvider = object : ToolProvider {
        override val descriptor: ToolDescriptor = descriptor()

        override fun execute(input: String): Result<String> = runCatching {
            executions()
            "ok:" + input
        }
    }

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase336-provider"),
        name = "Phase336 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "goal outcome transfer test contract",
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
                Result.failure(IllegalStateException("outcome-learning test does not create plans"))
            },
            practiceOne = {
                Result.failure(IllegalStateException("outcome-learning test does not practice"))
            }
        )
}
