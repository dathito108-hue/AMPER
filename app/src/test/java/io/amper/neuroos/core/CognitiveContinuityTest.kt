package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitiveContinuityTest {
    private val capability = CapabilityId("phase266.test")

    private fun descriptor(
        sideEffect: ToolSideEffect = ToolSideEffect.READ_ONLY
    ): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase266-provider"),
        name = "Phase266 provider",
        capability = capability,
        sideEffect = sideEffect,
        inputContract = ToolInputContract(
            description = "bounded cognitive-continuity test contract",
            acceptedValues = setOf("read", "apply"),
            maxLength = 32
        )
    )

    @Test
    fun executionContextIgnoresLearningOnlyReadinessDriftButRetainsFullAuditDigest() {
        val runtime = AmperRuntime.reference()
        val state = runtime.integratedCognition.capture(
            query = "Read the current grounded state",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor())
        )
        val changedReadiness = state.readiness.copy(
            overallReadiness = if (state.readiness.overallReadiness < 0.5) 0.9 else 0.1,
            learningPressure = if (state.readiness.learningPressure < 0.5) 0.9 else 0.1
        )
        val changed = state.copy(readiness = changedReadiness)

        val before = CognitiveContinuityPolicy.bind(state)
        val after = CognitiveContinuityPolicy.bind(changed)

        assertNotEquals(before.cognitiveStateDigest, after.cognitiveStateDigest)
        assertEquals(before.executionContextDigest, after.executionContextDigest)
        assertFalse(before.authorityBearing)
        assertFalse(after.authorityBearing)
    }

    @Test
    fun freshPerceptionChangeBlocksStalePlanBeforeAnyToolExecution() {
        val runtime = AmperRuntime.reference()
        var executions = 0
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
        val audit = InMemoryToolAuditLog()
        val actions = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = audit
            )
        )
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = planInference("read"),
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )

        val plan = planner.create(
            runtime.conversations.primary(),
            "Read the current sensor-grounded state"
        ).getOrThrow()

        assertNotNull(plan.planningCognitiveStateDigest)
        assertNotNull(plan.planningExecutionContextDigest)

        runtime.perception.ingest(
            Percept(
                id = "phase268-sensor",
                modality = PerceptionModality.SENSOR,
                payload = "light=77.0;proximity=0.0",
                salience = 0.9,
                provenance = Provenance(
                    source = "android-sensor-fusion",
                    producer = "phase268-test",
                    observedAtEpochMs = System.currentTimeMillis(),
                    confidence = 0.99
                )
            )
        )

        val result = planner.advance(plan).getOrThrow()
        assertTrue(result is PlanAdvanceResult.ContextChanged)
        val changed = result as PlanAdvanceResult.ContextChanged
        assertEquals(CognitiveContinuityStatus.CONTEXT_CHANGED, changed.assessment.status)
        assertTrue(changed.assessment.blocksExecution)
        assertEquals(0, executions)
        assertTrue(audit.snapshot().isEmpty())
    }

    @Test
    fun legacyUnboundPlanRetainsExistingLiveRevalidationCompatibility() {
        val runtime = AmperRuntime.reference()
        var executions = 0
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
                audit = InMemoryToolAuditLog()
            )
        )
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = planInference("read"),
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )
        val bound = planner.create(
            runtime.conversations.primary(),
            "Read the legacy-compatible state"
        ).getOrThrow()
        val legacy = bound.copy(
            planningCognitiveStateDigest = null,
            planningExecutionContextDigest = null
        )

        val result = planner.advance(legacy).getOrThrow()
        assertTrue(result is PlanAdvanceResult.StepProcessed)
        assertEquals(1, executions)
    }

    @Test
    fun persistedPlanV6RoundTripsExactContinuityBinding() {
        val plan = SovereignPlan(
            conversationId = ConversationId("phase270-conversation"),
            goal = "Preserve cognitive continuity across restart",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId("phase270-request"),
                    capability = capability,
                    reason = "Read the currently grounded value",
                    input = "read",
                    boundToolId = ToolId("phase266-provider"),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "phase270-backend",
            planningCognitiveStateDigest = "a".repeat(64),
            planningExecutionContextDigest = "b".repeat(64)
        )

        val encoded = SovereignPlanCodec.encode(plan)
        val restored = SovereignPlanCodec.decode(encoded).getOrThrow()

        assertTrue(encoded.startsWith("AMPER_PLAN_STATE_V6"))
        assertEquals(plan.planningCognitiveStateDigest, restored.planningCognitiveStateDigest)
        assertEquals(plan.planningExecutionContextDigest, restored.planningExecutionContextDigest)
        assertEquals(plan.steps.single().boundToolId, restored.steps.single().boundToolId)
    }

    @Test
    fun approvalBindingIsInvalidatedWhenGroundedContextChanges() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(
                object : ToolProvider {
                    override val descriptor: ToolDescriptor = descriptor(ToolSideEffect.LOCAL_STATE)
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
                audit = InMemoryToolAuditLog()
            )
        )
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = planInference("apply"),
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )

        val plan = planner.create(
            runtime.conversations.primary(),
            "Apply the local change using the current screen state"
        ).getOrThrow()
        val pending = planner.advance(plan).getOrThrow()
        assertTrue(pending is PlanAdvanceResult.PendingApproval)
        val pendingPlan = (pending as PlanAdvanceResult.PendingApproval).plan

        runtime.perception.ingest(
            Percept(
                id = "phase269-screen",
                modality = PerceptionModality.SCREEN,
                payload = "orientation=landscape;meanLuma=0.25",
                salience = 0.9,
                provenance = Provenance(
                    source = "android-app-screen",
                    producer = "phase269-test",
                    observedAtEpochMs = System.currentTimeMillis(),
                    confidence = 0.98
                )
            )
        )

        assertTrue(planner.approvalBinding(pendingPlan, 1).isFailure)
        assertEquals(0, executions)
    }

    private fun planInference(input: String): CognitiveInferencePort =
        CognitiveInferencePort {
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase266-planner"),
                    backendId = "phase266-planner",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase266.test
                        step.1.reason=Use the currently grounded context
                        step.1.input=$input
                        </AMPER_PLAN_V1>
                    """.trimIndent(),
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
}
