package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveGoalReplanningTest {
    private val capability = CapabilityId("phase271.test")

    private fun descriptor(
        sideEffect: ToolSideEffect = ToolSideEffect.READ_ONLY
    ): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase271-provider"),
        name = "Phase271 provider",
        capability = capability,
        sideEffect = sideEffect,
        inputContract = ToolInputContract(
            description = "bounded adaptive replanning contract",
            acceptedValues = setOf("read", "apply"),
            maxLength = 32
        )
    )

    @Test
    fun changedGroundedContextProducesFreshChildPlanWithZeroToolExecution() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val audit = InMemoryToolAuditLog()
        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(
                object : ToolProvider {
                    override val descriptor: ToolDescriptor = descriptor()
                    override fun execute(input: String): Result<String> = runCatching {
                        executions += 1
                        "executed:" + input
                    }
                }
            )
        }
        val actions = runtime.actionLoop(
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
                    modelId = ModelId("phase271-planner"),
                    backendId = "phase271-planner",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase271.test
                        step.1.reason=Use the current grounded sensor state
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
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )

        val original = planner.create(
            runtime.conversations.primary(),
            "Read the current sensor state"
        ).getOrThrow()
        assertEquals(1, requests.size)

        runtime.perception.ingest(
            Percept(
                id = "phase271-sensor",
                modality = PerceptionModality.SENSOR,
                payload = "light=88.0;proximity=0.2",
                salience = 0.95,
                provenance = Provenance(
                    source = "android-sensor-fusion",
                    producer = "phase271-test",
                    observedAtEpochMs = System.currentTimeMillis(),
                    confidence = 0.99
                )
            )
        )

        val blocked = planner.advance(original).getOrThrow()
        assertTrue(blocked is PlanAdvanceResult.ContextChanged)

        val replacement = planner.refreshContext(original).getOrThrow()
        assertEquals(2, requests.size)
        assertEquals(original.id, replacement.parentPlanId)
        assertEquals(original.recoveryDepth + 1, replacement.recoveryDepth)
        assertNotEquals(original.id, replacement.id)
        assertNotEquals(
            original.planningExecutionContextDigest,
            replacement.planningExecutionContextDigest
        )
        assertNotEquals(original.steps.single().requestId, replacement.steps.single().requestId)
        assertNotNull(replacement.planningCognitiveStateDigest)
        assertNotNull(replacement.planningExecutionContextDigest)
        assertTrue(requests.last().prompt.contains("<CONTEXT_REFRESH_CONSTRAINT>"))
        assertTrue(requests.last().prompt.contains("old_approval_transfer=false"))
        assertTrue(requests.last().prompt.contains("old_request_id_reuse=false"))
        assertEquals(0, executions)
        assertTrue(audit.snapshot().isEmpty())
    }

    @Test
    fun stableContextCannotConsumeAnotherPlanningInference() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(
                object : ToolProvider {
                    override val descriptor: ToolDescriptor = descriptor()
                    override fun execute(input: String): Result<String> =
                        Result.success("executed:" + input)
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
        var inferenceCalls = 0
        val inference = CognitiveInferencePort {
            inferenceCalls += 1
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase272-planner"),
                    backendId = "phase272-planner",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase271.test
                        step.1.reason=Use stable grounded state
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
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )
        val plan = planner.create(
            runtime.conversations.primary(),
            "Read the stable sensor state"
        ).getOrThrow()

        assertTrue(planner.refreshContext(plan).isFailure)
        assertEquals(1, inferenceCalls)
    }

    @Test
    fun pendingApprovalIsNotTransferredIntoContextRefreshedPlan() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val audit = InMemoryToolAuditLog()
        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(
                object : ToolProvider {
                    override val descriptor: ToolDescriptor =
                        descriptor(ToolSideEffect.LOCAL_STATE)

                    override fun execute(input: String): Result<String> = runCatching {
                        executions += 1
                        "executed:" + input
                    }
                }
            )
        }
        val actions = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = audit
            )
        )
        var inferenceCalls = 0
        val inference = CognitiveInferencePort {
            inferenceCalls += 1
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase274-planner"),
                    backendId = "phase274-planner",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase271.test
                        step.1.reason=Apply using the currently grounded screen state
                        step.1.input=apply
                        </AMPER_PLAN_V1>
                    """.trimIndent(),
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )
        val original = planner.create(
            runtime.conversations.primary(),
            "Apply the local change using the current screen state"
        ).getOrThrow()
        val pending = planner.advance(original).getOrThrow() as PlanAdvanceResult.PendingApproval

        runtime.perception.ingest(
            Percept(
                id = "phase274-screen",
                modality = PerceptionModality.SCREEN,
                payload = "orientation=landscape;meanLuma=0.31",
                salience = 0.9,
                provenance = Provenance(
                    source = "android-app-screen",
                    producer = "phase274-test",
                    observedAtEpochMs = System.currentTimeMillis(),
                    confidence = 0.98
                )
            )
        )

        val replacement = planner.refreshContext(pending.plan).getOrThrow()
        assertEquals(2, inferenceCalls)
        assertEquals(PlanStepStatus.PLANNED, replacement.steps.single().status)
        assertNotEquals(pending.step.requestId, replacement.steps.single().requestId)
        assertFalse(replacement.steps.single().status == PlanStepStatus.REQUIRES_CONFIRMATION)
        assertEquals(0, executions)
        assertTrue(audit.snapshot().isEmpty())
    }

    @Test
    fun contextRefreshDepthCapStopsUnboundedReplanningBeforeInference() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(
                object : ToolProvider {
                    override val descriptor: ToolDescriptor = descriptor()
                    override fun execute(input: String): Result<String> =
                        Result.success("executed:" + input)
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
        var inferenceCalls = 0
        val inference = CognitiveInferencePort {
            inferenceCalls += 1
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase275-planner"),
                    backendId = "phase275-planner",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase271.test
                        step.1.reason=Read bounded state
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
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )
        val base = planner.create(
            runtime.conversations.primary(),
            "Read the bounded sensor state"
        ).getOrThrow()
        val capped = base.copy(
            parentPlanId = PlanId("phase275-parent"),
            recoveryDepth = SovereignPlanCoordinator.MAX_CONTEXT_REPLANS
        )

        assertTrue(planner.refreshContext(capped).isFailure)
        assertEquals(1, inferenceCalls)
    }
}
