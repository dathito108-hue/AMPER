package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousGovernedPlanRunnerTest {
    private val capability = CapabilityId("phase301.test")

    @Test
    fun readOnlyPlanExecutesThroughGovernedPathAndCompletesGoal() {
        val executions = intArrayOf(0)
        val harness = harness(
            sideEffect = ToolSideEffect.READ_ONLY,
            execute = {
                executions[0] += 1
                Result.success("read:$it")
            }
        )
        val plan = manualPlan(
            conversationId = harness.runtime.conversations.primary(),
            id = PlanId("phase301-readonly-plan"),
            sideEffect = ToolSideEffect.READ_ONLY,
            stepCount = 2
        )
        harness.runtime.plans.save(plan)
        saveCheckpoint(harness.runtime, plan)

        val result = harness.runner.runBounded(plan.id).getOrThrow()

        assertEquals(AutonomousGovernedPlanRunStage.COMPLETED, result.stage)
        assertEquals(2, result.processedSteps)
        assertEquals(2, executions[0])
        assertEquals(
            PersistentGoalExecutiveStage.COMPLETED,
            harness.goals.current()?.stage
        )
        val stored = requireNotNull(harness.runtime.plans.load(plan.id))
        assertTrue(stored.steps.all { it.status == PlanStepStatus.EXECUTED })
        assertFalse(result.authorityBearing)
    }

    @Test
    fun sideEffectPlanStopsAtPendingApprovalWithoutExecutingProvider() {
        val executions = intArrayOf(0)
        val harness = harness(
            sideEffect = ToolSideEffect.LOCAL_STATE,
            execute = {
                executions[0] += 1
                Result.success("applied:$it")
            }
        )
        val plan = manualPlan(
            conversationId = harness.runtime.conversations.primary(),
            id = PlanId("phase302-side-effect-plan"),
            sideEffect = ToolSideEffect.LOCAL_STATE,
            stepCount = 1
        )
        harness.runtime.plans.save(plan)
        saveCheckpoint(harness.runtime, plan)

        val result = harness.runner.runBounded(plan.id).getOrThrow()

        assertEquals(AutonomousGovernedPlanRunStage.WAITING_APPROVAL, result.stage)
        assertEquals(0, result.processedSteps)
        assertEquals(0, executions[0])
        assertEquals(
            PersistentGoalExecutiveStage.PLANNED,
            harness.goals.current()?.stage
        )
        val stored = requireNotNull(harness.runtime.plans.load(plan.id))
        assertEquals(
            PlanStepStatus.REQUIRES_CONFIRMATION,
            stored.steps.single().status
        )
    }

    @Test
    fun nonterminalExecutionFailurePausesAutonomyBeforeLaterStep() {
        val executions = intArrayOf(0)
        val harness = harness(
            sideEffect = ToolSideEffect.READ_ONLY,
            execute = {
                executions[0] += 1
                Result.failure(IllegalStateException("phase303 provider failure"))
            }
        )
        val plan = manualPlan(
            conversationId = harness.runtime.conversations.primary(),
            id = PlanId("phase303-failed-chain"),
            sideEffect = ToolSideEffect.READ_ONLY,
            stepCount = 2
        )
        harness.runtime.plans.save(plan)
        saveCheckpoint(harness.runtime, plan)

        val result = harness.runner.runBounded(plan.id).getOrThrow()

        assertEquals(AutonomousGovernedPlanRunStage.EXECUTION_PAUSED, result.stage)
        assertEquals(1, result.processedSteps)
        assertEquals(1, executions[0])
        val stored = requireNotNull(harness.runtime.plans.load(plan.id))
        assertEquals(PlanStepStatus.FAILED, stored.steps[0].status)
        assertEquals(PlanStepStatus.PLANNED, stored.steps[1].status)
        val checkpoint = requireNotNull(harness.goals.current())
        assertEquals(PersistentGoalExecutiveStage.EXECUTION_PAUSED, checkpoint.stage)
        assertEquals("AUTONOMOUS_STEP_FAILED", checkpoint.lastFailureCode)

        val rerun = harness.goals.runNext(plan.conversationId).getOrThrow()
        assertTrue(rerun is PersistentGoalExecutiveResult.Deferred)
        assertEquals(1, executions[0])
    }

    @Test
    fun changedContextRefreshesAndRebindsWithoutToolExecution() {
        val executions = intArrayOf(0)
        val harness = harness(
            sideEffect = ToolSideEffect.READ_ONLY,
            execute = {
                executions[0] += 1
                Result.success("read:$it")
            }
        )
        val conversationId = harness.runtime.conversations.primary()
        val goal = "Read the current sensor-grounded value"
        val oldPlan = harness.planner.create(conversationId, goal).getOrThrow()
        saveCheckpoint(harness.runtime, oldPlan)

        harness.runtime.perception.ingest(
            Percept(
                id = "phase304-live-sensor",
                modality = PerceptionModality.SENSOR,
                payload = "light=91.0;proximity=0.0",
                salience = 0.9,
                provenance = Provenance(
                    source = "phase304-sensor",
                    producer = "phase304-test",
                    observedAtEpochMs = System.currentTimeMillis(),
                    confidence = 0.99
                )
            )
        )

        val result = harness.runner.runBounded(oldPlan.id).getOrThrow()

        assertEquals(AutonomousGovernedPlanRunStage.CONTEXT_REFRESHED, result.stage)
        assertEquals(0, result.processedSteps)
        assertEquals(0, executions[0])
        assertNotEquals(oldPlan.id, result.activePlanId)
        val replacement = requireNotNull(harness.runtime.plans.load(result.activePlanId))
        assertEquals(oldPlan.id, replacement.parentPlanId)
        assertEquals(
            result.activePlanId,
            harness.goals.current()?.plannedPlanId
        )
        assertEquals(
            PersistentGoalExecutiveStage.PLANNED,
            harness.goals.current()?.stage
        )
    }

    private data class Harness(
        val runtime: AmperRuntime,
        val planner: PersistentSovereignPlanCoordinator,
        val goals: PersistentGoalExecutiveCoordinator,
        val runner: AutonomousGovernedPlanRunner
    )

    private fun harness(
        sideEffect: ToolSideEffect,
        execute: (String) -> Result<String>
    ): Harness {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(
                object : ToolProvider {
                    override val descriptor: ToolDescriptor = descriptor(sideEffect)
                    override fun execute(input: String): Result<String> = execute(input)
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
                    modelId = ModelId("phase304-planner"),
                    backendId = "phase304-planner",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase301.test
                        step.1.reason=Use the fresh governed context
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
        return Harness(
            runtime = runtime,
            planner = planner,
            goals = goals,
            runner = AutonomousGovernedPlanRunner(
                planner = planner,
                plans = runtime.plans,
                goals = goals
            )
        )
    }

    private fun descriptor(
        sideEffect: ToolSideEffect
    ): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase301-provider"),
        name = "Phase301 provider",
        capability = capability,
        sideEffect = sideEffect,
        inputContract = ToolInputContract(
            description = "bounded autonomous governed execution contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    private fun manualPlan(
        conversationId: ConversationId,
        id: PlanId,
        sideEffect: ToolSideEffect,
        stepCount: Int
    ): SovereignPlan = SovereignPlan(
        id = id,
        conversationId = conversationId,
        goal = "Run the governed autonomous plan",
        steps = (1..stepCount).map { index ->
            SovereignPlanStep(
                index = index,
                requestId = ActionRequestId(id.value + "-request-" + index),
                capability = capability,
                reason = "Governed autonomous step $index",
                input = "read",
                boundToolId = ToolId("phase301-provider"),
                boundSideEffect = sideEffect
            )
        },
        planningBackendId = "phase301-manual"
    )

    private fun saveCheckpoint(
        runtime: AmperRuntime,
        plan: SovereignPlan
    ) {
        runtime.persistentGoalExecutiveStore.save(
            PersistentGoalExecutiveCheckpoint(
                sourceGoalId = "phase301-goal",
                objective = plan.goal,
                conversationId = plan.conversationId,
                priority = 0.9,
                stage = PersistentGoalExecutiveStage.PLANNED,
                attemptCount = 1,
                lastAction = CognitiveExecutiveAction.PLAN,
                plannedPlanId = plan.id,
                updatedAtEpochMs = 1L
            )
        )
    }
}
