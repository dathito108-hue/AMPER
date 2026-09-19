package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalSatisfactionVerificationTest {
    private val capability = CapabilityId("phase306.test")

    @Test
    fun lowConfidenceSatisfiedOutputIsConservativelyDowngraded() {
        val parsed = GoalSatisfactionProtocol.parse(
            planId = PlanId("phase306-plan"),
            output = """
                <AMPER_GOAL_VERIFY_V1>
                verdict=SATISFIED
                confidence=0.61
                reason=the tool ran but postcondition evidence is weak
                </AMPER_GOAL_VERIFY_V1>
            """.trimIndent(),
            cognitiveStateDigest = "a".repeat(64),
            executionContextDigest = "b".repeat(64)
        ).getOrThrow()

        assertEquals(GoalSatisfactionVerdict.FOLLOW_UP_REQUIRED, parsed.verdict)
        assertEquals(0.61, parsed.confidence, 0.0001)
        assertTrue(parsed.reason.contains("downgraded"))
        assertFalse(parsed.authorityBearing)
    }

    @Test
    fun verifiedSatisfiedClosesExactAllExecutedGoal() {
        val runtime = AmperRuntime.reference()
        val plan = executedPlan("phase307-satisfied-plan")
        runtime.plans.save(plan)
        runtime.persistentGoalExecutiveStore.save(checkpoint(plan.id))
        val goals = goalCoordinator(runtime)

        val resolved = goals.resolveVerifiedSuccess(
            planId = plan.id,
            assessment = assessment(
                plan.id,
                GoalSatisfactionVerdict.SATISFIED,
                confidence = 0.93,
                reason = "current evidence confirms the requested state"
            )
        ).getOrThrow()

        assertEquals(PersistentGoalExecutiveStage.COMPLETED, resolved.stage)
        assertEquals(plan.id, resolved.plannedPlanId)
        assertEquals(GoalSatisfactionVerdict.SATISFIED, resolved.lastVerificationVerdict)
        assertEquals(0.93, resolved.lastVerificationConfidence ?: 0.0, 0.0001)
        assertEquals(0, resolved.followUpCount)
        assertNull(resolved.lastFailureCode)
    }

    @Test
    fun followUpVerdictRequeuesGoalWithoutUsingExecutionRecoveryLineage() {
        val runtime = AmperRuntime.reference()
        val plan = executedPlan("phase308-followup-plan")
        runtime.plans.save(plan)
        runtime.persistentGoalExecutiveStore.save(checkpoint(plan.id, recoveryCount = 2))
        val goals = goalCoordinator(runtime)

        val resolved = goals.resolveVerifiedSuccess(
            planId = plan.id,
            assessment = assessment(
                plan.id,
                GoalSatisfactionVerdict.FOLLOW_UP_REQUIRED,
                confidence = 0.74,
                reason = "the action completed but goal-level outcome is not yet evidenced"
            )
        ).getOrThrow()

        assertEquals(PersistentGoalExecutiveStage.FOLLOW_UP_QUEUED, resolved.stage)
        assertNull(resolved.plannedPlanId)
        assertEquals(1, resolved.followUpCount)
        assertEquals(2, resolved.recoveryCount)
        assertEquals(
            GoalSatisfactionVerdict.FOLLOW_UP_REQUIRED,
            resolved.lastVerificationVerdict
        )
        assertNull(resolved.lastFailureCode)
    }

    @Test
    fun boundedFollowUpCapBlocksEndlessGoalLoop() {
        val runtime = AmperRuntime.reference()
        val plan = executedPlan("phase309-followup-cap")
        runtime.plans.save(plan)
        runtime.persistentGoalExecutiveStore.save(
            checkpoint(
                planId = plan.id,
                followUpCount =
                    PersistentGoalExecutiveCheckpoint.MAX_FOLLOW_UP_GENERATIONS
            )
        )
        val goals = goalCoordinator(runtime)

        val resolved = goals.resolveVerifiedSuccess(
            planId = plan.id,
            assessment = assessment(
                plan.id,
                GoalSatisfactionVerdict.FOLLOW_UP_REQUIRED,
                confidence = 0.79,
                reason = "more evidence would still be required"
            )
        ).getOrThrow()

        assertEquals(PersistentGoalExecutiveStage.FOLLOW_UP_EXHAUSTED, resolved.stage)
        assertEquals(plan.id, resolved.plannedPlanId)
        assertEquals("GOAL_FOLLOW_UP_LIMIT", resolved.lastFailureCode)
        val rerun = goals.runNext(plan.conversationId).getOrThrow()
        assertTrue(rerun is PersistentGoalExecutiveResult.Deferred)
    }

    @Test
    fun autonomousRunnerUsesVerifierBeforeReportingCompleted() {
        val runtime = AmperRuntime.reference()
        val plan = executedPlan("phase309-runner-followup")
        runtime.plans.save(plan)
        runtime.persistentGoalExecutiveStore.save(checkpoint(plan.id))

        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(
                object : ToolProvider {
                    override val descriptor: ToolDescriptor = descriptor()
                    override fun execute(input: String): Result<String> =
                        Result.success("unused")
                }
            )
        }
        val actions = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = InMemoryToolAuditLog()
            )
        )
        val inference = CognitiveInferencePort {
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase309-planner"),
                    backendId = "phase309-planner",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase306.test
                        step.1.reason=unused
                        step.1.input=read
                        </AMPER_PLAN_V1>
                    """.trimIndent(),
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
        val coordinator = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )
        val planner = PersistentSovereignPlanCoordinator(
            delegate = coordinator,
            store = runtime.plans
        )
        val goals = coordinator.persistentGoalExecutive()
        val runner = AutonomousGovernedPlanRunner(
            planner = planner,
            plans = runtime.plans,
            goals = goals,
            completionVerifier = GoalSatisfactionVerifier { _, terminal ->
                Result.success(
                    assessment(
                        terminal.id,
                        GoalSatisfactionVerdict.FOLLOW_UP_REQUIRED,
                        confidence = 0.70,
                        reason = "executed steps do not yet prove goal satisfaction"
                    )
                )
            }
        )

        val result = runner.runBounded(plan.id).getOrThrow()

        assertEquals(AutonomousGovernedPlanRunStage.FOLLOW_UP_QUEUED, result.stage)
        assertEquals(0, result.processedSteps)
        assertEquals(
            PersistentGoalExecutiveStage.FOLLOW_UP_QUEUED,
            result.goalCheckpointStage
        )
        assertEquals(1, goals.current()?.followUpCount)
    }

    @Test
    fun codecV3ReadsV2WithZeroVerificationLineageAndRoundTripsNewEvidence() {
        fun enc(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        val v2 = buildString {
            appendLine("AMPER_PERSISTENT_GOAL_EXECUTIVE_V2")
            appendLine("GOAL_ID\t" + enc("phase310-v2-goal"))
            appendLine("OBJECTIVE\t" + enc("Resume old goal state"))
            appendLine("CONVERSATION\t" + enc("phase310-v2-conversation"))
            appendLine("PRIORITY\t0.8")
            appendLine("STAGE\tPLANNED")
            appendLine("ATTEMPTS\t1")
            appendLine("LAST_ACTION\tPLAN")
            appendLine("COGNITIVE_DIGEST\t" + "a".repeat(64))
            appendLine("EXECUTION_DIGEST\t" + "b".repeat(64))
            appendLine("PLAN_ID\t" + enc("phase310-v2-plan"))
            appendLine("RECOVERY_COUNT\t2")
            appendLine("FAILURE_CODE\t~")
            append("UPDATED\t123")
        }
        val restoredV2 = PersistentGoalExecutiveCodec.decode(v2).getOrThrow()

        assertEquals(2, restoredV2.recoveryCount)
        assertEquals(0, restoredV2.followUpCount)
        assertNull(restoredV2.lastVerificationVerdict)

        val v3 = restoredV2.copy(
            followUpCount = 1,
            lastVerificationVerdict = GoalSatisfactionVerdict.FOLLOW_UP_REQUIRED,
            lastVerificationConfidence = 0.72,
            lastVerificationReason = "needs another bounded evidence pass"
        )
        val encoded = PersistentGoalExecutiveCodec.encode(v3)
        val roundTrip = PersistentGoalExecutiveCodec.decode(encoded).getOrThrow()

        assertTrue(encoded.startsWith("AMPER_PERSISTENT_GOAL_EXECUTIVE_V3"))
        assertEquals(v3.followUpCount, roundTrip.followUpCount)
        assertEquals(v3.lastVerificationVerdict, roundTrip.lastVerificationVerdict)
        assertEquals(
            v3.lastVerificationConfidence ?: 0.0,
            roundTrip.lastVerificationConfidence ?: 0.0,
            0.0001
        )
        assertEquals(v3.lastVerificationReason, roundTrip.lastVerificationReason)
    }

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase306-provider"),
        name = "Phase306 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded goal verification test contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    private fun executedPlan(id: String): SovereignPlan {
        val planId = PlanId(id)
        val proposal = ActionProposal(
            requestId = ActionRequestId("$id-request"),
            capability = capability,
            reason = "Execute the bounded goal evidence step",
            input = "read"
        )
        val outcome = ActionOutcome(
            status = ActionStatus.EXECUTED,
            proposal = proposal,
            toolId = descriptor().id,
            output = "bounded result confirmed by provider",
            sideEffect = ToolSideEffect.READ_ONLY
        )
        return SovereignPlan(
            id = planId,
            conversationId = ConversationId("phase306-conversation"),
            goal = "Confirm the bounded result",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = proposal.requestId,
                    capability = capability,
                    reason = proposal.reason,
                    input = proposal.input,
                    status = PlanStepStatus.EXECUTED,
                    outcome = outcome,
                    boundToolId = descriptor().id,
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "phase306-plan"
        )
    }

    private fun checkpoint(
        planId: PlanId,
        recoveryCount: Int = 0,
        followUpCount: Int = 0
    ): PersistentGoalExecutiveCheckpoint = PersistentGoalExecutiveCheckpoint(
        sourceGoalId = "phase306-goal",
        objective = "Confirm the bounded result",
        conversationId = ConversationId("phase306-conversation"),
        priority = 0.9,
        stage = PersistentGoalExecutiveStage.PLANNED,
        attemptCount = 1,
        lastAction = CognitiveExecutiveAction.PLAN,
        lastCognitiveStateDigest = "c".repeat(64),
        lastExecutionContextDigest = "d".repeat(64),
        plannedPlanId = planId,
        recoveryCount = recoveryCount,
        followUpCount = followUpCount,
        updatedAtEpochMs = 10L
    )

    private fun assessment(
        planId: PlanId,
        verdict: GoalSatisfactionVerdict,
        confidence: Double,
        reason: String
    ): GoalSatisfactionAssessment = GoalSatisfactionAssessment(
        planId = planId,
        verdict = verdict,
        confidence = confidence,
        reason = reason,
        cognitiveStateDigest = "e".repeat(64),
        executionContextDigest = "f".repeat(64),
        backendId = "phase306-verifier",
        modelId = ModelId("phase306-verifier")
    )

    private fun goalCoordinator(
        runtime: AmperRuntime
    ): PersistentGoalExecutiveCoordinator {
        val state = runtime.integratedCognition.capture(
            query = "Confirm the bounded result",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor())
        )
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
            createPlan = { _, _ ->
                Result.failure(
                    IllegalStateException("goal verification test must not create a plan")
                )
            },
            practiceOne = {
                Result.failure(
                    IllegalStateException("goal verification test must not practice")
                )
            }
        )
        return PersistentGoalExecutiveCoordinator(
            context = runtime.context,
            executive = executive,
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans,
            clock = { 20L }
        )
    }
}
