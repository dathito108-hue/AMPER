package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentGoalRecoveryTest {
    private val capability = CapabilityId("phase286.test")

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase286-provider"),
        name = "Phase286 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded persistent goal recovery test contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    @Test
    fun zeroExecutionFailureQueuesRecoveryAndCreatesFreshPlan() {
        val runtime = AmperRuntime.reference()
        val oldPlan = terminalPlan(
            id = PlanId("phase286-old-plan"),
            statuses = listOf(PlanStepStatus.FAILED)
        )
        runtime.plans.save(oldPlan)
        runtime.persistentGoalExecutiveStore.save(
            checkpoint(
                planId = oldPlan.id,
                recoveryCount = 0
            )
        )

        val ready = readyState(runtime)
        var planCalls = 0
        val newPlanId = PlanId("phase286-new-plan")
        val coordinator = coordinator(
            runtime = runtime,
            state = { ready },
            createPlan = { conversationId, goal ->
                planCalls += 1
                Result.success(
                    SovereignPlan(
                        id = newPlanId,
                        conversationId = conversationId,
                        goal = goal,
                        steps = listOf(
                            SovereignPlanStep(
                                index = 1,
                                requestId = ActionRequestId("phase286-new-request"),
                                capability = capability,
                                reason = "Fresh recovery plan after zero-execution failure",
                                input = "read",
                                boundToolId = descriptor().id,
                                boundSideEffect = ToolSideEffect.READ_ONLY
                            )
                        ),
                        planningBackendId = "phase286-recovery"
                    )
                )
            }
        )

        val resolved = coordinator.resolveTerminalPlan(oldPlan.id).getOrThrow()
        assertEquals(PersistentGoalExecutiveStage.RECOVERY_QUEUED, resolved.stage)
        assertEquals(1, resolved.recoveryCount)
        assertEquals("TERMINAL_PLAN_FAILED", resolved.lastFailureCode)
        assertNull(resolved.plannedPlanId)

        val rerun = coordinator.runNext(resolved.conversationId).getOrThrow()
        assertTrue(rerun is PersistentGoalExecutiveResult.Ran)
        val next = (rerun as PersistentGoalExecutiveResult.Ran).checkpoint
        assertEquals(PersistentGoalExecutiveStage.PLANNED, next.stage)
        assertEquals(newPlanId, next.plannedPlanId)
        assertEquals(1, next.recoveryCount)
        assertEquals(1, planCalls)
        assertTrue(runtime.plans.load(newPlanId) != null)
    }

    @Test
    fun deniedPlanCannotBeRetriedAsAutonomousRecovery() {
        val runtime = AmperRuntime.reference()
        val denied = terminalPlan(
            id = PlanId("phase287-denied-plan"),
            statuses = listOf(PlanStepStatus.DENIED)
        )
        runtime.plans.save(denied)
        runtime.persistentGoalExecutiveStore.save(checkpoint(denied.id))

        var planCalls = 0
        val coordinator = coordinator(
            runtime = runtime,
            state = { readyState(runtime) },
            createPlan = { _, _ ->
                planCalls += 1
                Result.failure(IllegalStateException("denied plan must not auto-replan"))
            }
        )

        val resolved = coordinator.resolveTerminalPlan(denied.id).getOrThrow()
        assertEquals(PersistentGoalExecutiveStage.RECOVERY_BLOCKED, resolved.stage)
        assertEquals("AUTHORITY_OR_USER_BLOCK", resolved.lastFailureCode)
        assertEquals(denied.id, resolved.plannedPlanId)

        val rerun = coordinator.runNext(resolved.conversationId).getOrThrow()
        assertTrue(rerun is PersistentGoalExecutiveResult.Deferred)
        assertEquals(0, planCalls)
    }

    @Test
    fun partialExecutionBlocksFreshGoalReplay() {
        val runtime = AmperRuntime.reference()
        val partial = terminalPlan(
            id = PlanId("phase288-partial-plan"),
            statuses = listOf(
                PlanStepStatus.EXECUTED,
                PlanStepStatus.FAILED
            )
        )
        runtime.plans.save(partial)
        runtime.persistentGoalExecutiveStore.save(checkpoint(partial.id))

        val coordinator = coordinator(
            runtime = runtime,
            state = { readyState(runtime) },
            createPlan = { _, _ ->
                Result.failure(IllegalStateException("partial execution must not replay"))
            }
        )

        val resolved = coordinator.resolveTerminalPlan(partial.id).getOrThrow()
        assertEquals(
            PersistentGoalExecutiveStage.PARTIAL_EXECUTION_BLOCKED,
            resolved.stage
        )
        assertEquals("PARTIAL_EXECUTION", resolved.lastFailureCode)
        assertEquals(partial.id, resolved.plannedPlanId)
        assertEquals(0, resolved.recoveryCount)
    }

    @Test
    fun recoveryGenerationCapStopsRepeatedFailedPlanning() {
        val runtime = AmperRuntime.reference()
        val failed = terminalPlan(
            id = PlanId("phase289-capped-plan"),
            statuses = listOf(PlanStepStatus.UNAVAILABLE)
        )
        runtime.plans.save(failed)
        runtime.persistentGoalExecutiveStore.save(
            checkpoint(
                planId = failed.id,
                recoveryCount = PersistentGoalExecutiveCheckpoint.MAX_RECOVERY_GENERATIONS
            )
        )

        val coordinator = coordinator(
            runtime = runtime,
            state = { readyState(runtime) },
            createPlan = { _, _ ->
                Result.failure(IllegalStateException("recovery cap must stop replanning"))
            }
        )

        val resolved = coordinator.resolveTerminalPlan(failed.id).getOrThrow()
        assertEquals(PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED, resolved.stage)
        assertEquals("RECOVERY_LIMIT_REACHED", resolved.lastFailureCode)
        assertEquals(
            PersistentGoalExecutiveCheckpoint.MAX_RECOVERY_GENERATIONS,
            resolved.recoveryCount
        )
        assertEquals(failed.id, resolved.plannedPlanId)
    }

    @Test
    fun codecV2ReadsExistingV1CheckpointWithZeroRecoveryLineage() {
        fun enc(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        val v1 = buildString {
            appendLine("AMPER_PERSISTENT_GOAL_EXECUTIVE_V1")
            appendLine("GOAL_ID\t" + enc("phase290-v1-goal"))
            appendLine("OBJECTIVE\t" + enc("Resume the old persisted goal"))
            appendLine("CONVERSATION\t" + enc("phase290-v1-conversation"))
            appendLine("PRIORITY\t0.8")
            appendLine("STAGE\tPLANNED")
            appendLine("ATTEMPTS\t1")
            appendLine("LAST_ACTION\tPLAN")
            appendLine("COGNITIVE_DIGEST\t" + "a".repeat(64))
            appendLine("EXECUTION_DIGEST\t" + "b".repeat(64))
            appendLine("PLAN_ID\t" + enc("phase290-v1-plan"))
            append("UPDATED\t123")
        }

        val restored = PersistentGoalExecutiveCodec.decode(v1).getOrThrow()

        assertEquals(PersistentGoalExecutiveStage.PLANNED, restored.stage)
        assertEquals(0, restored.recoveryCount)
        assertNull(restored.lastFailureCode)
        assertEquals(PlanId("phase290-v1-plan"), restored.plannedPlanId)
    }

    @Test
    fun contextRefreshChildRebindsExactPersistentGoalHandoff() {
        val runtime = AmperRuntime.reference()
        val oldPlan = SovereignPlan(
            id = PlanId("phase295-parent-plan"),
            conversationId = ConversationId("phase286-conversation"),
            goal = "Recover the bounded persistent goal",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId("phase295-parent-request"),
                    capability = capability,
                    reason = "Parent plan before grounded context refresh",
                    input = "read",
                    boundToolId = descriptor().id,
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "phase295-parent"
        )
        val childPlan = SovereignPlan(
            id = PlanId("phase295-child-plan"),
            conversationId = oldPlan.conversationId,
            goal = oldPlan.goal,
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId("phase295-child-request"),
                    capability = capability,
                    reason = "Fresh child plan after grounded context refresh",
                    input = "read",
                    boundToolId = descriptor().id,
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "phase295-child",
            parentPlanId = oldPlan.id,
            recoveryDepth = 1
        )
        runtime.plans.save(oldPlan)
        runtime.plans.save(childPlan)
        runtime.persistentGoalExecutiveStore.save(checkpoint(oldPlan.id))

        val coordinator = coordinator(
            runtime = runtime,
            state = { readyState(runtime) },
            createPlan = { _, _ ->
                Result.failure(IllegalStateException("handoff rebind must not create a plan"))
            }
        )

        val rebound = coordinator.rebindPlannedHandoff(
            previousPlanId = oldPlan.id,
            replacementPlanId = childPlan.id
        ).getOrThrow()

        assertEquals(PersistentGoalExecutiveStage.PLANNED, rebound.stage)
        assertEquals(childPlan.id, rebound.plannedPlanId)
        assertEquals(0, rebound.recoveryCount)
        assertEquals(childPlan.id, coordinator.current()?.plannedPlanId)
    }

    private fun checkpoint(
        planId: PlanId,
        recoveryCount: Int = 0
    ): PersistentGoalExecutiveCheckpoint = PersistentGoalExecutiveCheckpoint(
        sourceGoalId = "phase286-goal",
        objective = "Recover the bounded persistent goal",
        conversationId = ConversationId("phase286-conversation"),
        priority = 0.85,
        stage = PersistentGoalExecutiveStage.PLANNED,
        attemptCount = 1,
        lastAction = CognitiveExecutiveAction.PLAN,
        lastCognitiveStateDigest = "a".repeat(64),
        lastExecutionContextDigest = "b".repeat(64),
        plannedPlanId = planId,
        recoveryCount = recoveryCount,
        updatedAtEpochMs = 10L
    )

    private fun terminalPlan(
        id: PlanId,
        statuses: List<PlanStepStatus>
    ): SovereignPlan = SovereignPlan(
        id = id,
        conversationId = ConversationId("phase286-conversation"),
        goal = "Recover the bounded persistent goal",
        steps = statuses.mapIndexed { index, status ->
            SovereignPlanStep(
                index = index + 1,
                requestId = ActionRequestId("phase286-request-" + (index + 1)),
                capability = capability,
                reason = "Terminal recovery evidence step " + (index + 1),
                input = "read",
                status = status,
                boundToolId = descriptor().id,
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        },
        planningBackendId = "phase286-old-planner"
    )

    private fun readyState(
        runtime: AmperRuntime
    ): IntegratedCognitiveStatePacket {
        val base = runtime.integratedCognition.capture(
            query = "Recover the bounded persistent goal",
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

    private fun coordinator(
        runtime: AmperRuntime,
        state: () -> IntegratedCognitiveStatePacket,
        createPlan: (ConversationId, String) -> Result<SovereignPlan>
    ): PersistentGoalExecutiveCoordinator {
        val executive = AutonomousCognitiveExecutive(
            stateSource = object : IntegratedCognitiveStateSource {
                override fun capture(
                    query: String,
                    allowedCapabilities: Set<CapabilityId>,
                    descriptors: Collection<ToolDescriptor>
                ): IntegratedCognitiveStatePacket = state()
            },
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = createPlan,
            practiceOne = {
                Result.failure(IllegalStateException("practice is not part of goal recovery tests"))
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
