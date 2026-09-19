package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetacognitiveControlTest {
    private val capability = CapabilityId("phase256.test")

    private fun descriptor(
        sideEffect: ToolSideEffect = ToolSideEffect.READ_ONLY
    ): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase256-provider"),
        name = "Phase256 provider",
        capability = capability,
        sideEffect = sideEffect,
        inputContract = ToolInputContract(
            description = "bounded metacognitive-control test contract",
            acceptedValues = setOf("read", "apply"),
            maxLength = 32
        )
    )

    private fun profile(
        maxOutputTokens: Int = 400,
        temperature: Double = 1.0
    ): BoundConversationInferenceProfile = BoundConversationInferenceProfile(
        maxOutputTokens = maxOutputTokens,
        temperature = temperature,
        sessionRoutingPreference = TitanSessionRoutingPreference.STANDARD,
        maxPromptChars = 9000
    )

    @Test
    fun policyAdaptsReasoningWithoutExceedingFrozenProfile() {
        val runtime = AmperRuntime.reference()
        val state = runtime.integratedCognition.capture(
            query = "Inspect the governed cognitive state",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor())
        )
        val frozen = profile(maxOutputTokens = 400, temperature = 1.1)

        val direct = MetacognitiveInferenceControlPolicy.derive(
            state = state.copy(
                readiness = state.readiness.copy(
                    epistemicConfidence = 0.90,
                    worldConfidence = 0.88,
                    skillConfidence = 0.82,
                    competenceConfidence = 0.80,
                    uncertainty = 0.10,
                    learningPressure = 0.20,
                    overallReadiness = 0.82
                )
            ),
            profile = frozen
        )
        val deliberate = MetacognitiveInferenceControlPolicy.derive(
            state = state.copy(
                readiness = state.readiness.copy(
                    uncertainty = 0.30,
                    learningPressure = 0.55,
                    overallReadiness = 0.62
                )
            ),
            profile = frozen
        )
        val cautious = MetacognitiveInferenceControlPolicy.derive(
            state = state.copy(
                readiness = state.readiness.copy(
                    uncertainty = 0.72,
                    learningPressure = 0.85,
                    overallReadiness = 0.32
                )
            ),
            profile = frozen
        )

        assertEquals(MetacognitiveControlMode.DIRECT, direct.mode)
        assertEquals(1, direct.requestedCandidateCount)
        assertEquals(300, direct.planningMaxOutputTokens)
        assertTrue(direct.planningTemperature <= 0.65)

        assertEquals(MetacognitiveControlMode.DELIBERATE, deliberate.mode)
        assertEquals(2, deliberate.requestedCandidateCount)
        assertEquals(360, deliberate.planningMaxOutputTokens)
        assertTrue(deliberate.planningTemperature <= 0.40)

        assertEquals(MetacognitiveControlMode.CAUTIOUS, cautious.mode)
        assertEquals(TitanDeliberationProtocol.MAX_CANDIDATES, cautious.requestedCandidateCount)
        assertEquals(400, cautious.planningMaxOutputTokens)
        assertTrue(cautious.planningTemperature <= 0.20)
        assertTrue(cautious.criticTemperature <= 0.10)

        listOf(direct, deliberate, cautious).forEach { directive ->
            assertTrue(directive.planningMaxOutputTokens <= frozen.maxOutputTokens)
            assertTrue(directive.criticMaxOutputTokens <= frozen.maxOutputTokens)
            assertTrue(directive.planningTemperature <= frozen.temperature)
            assertTrue(directive.criticTemperature <= frozen.temperature)
            assertFalse(directive.authorityBearing)
        }
    }

    @Test
    fun plannerAndCriticShareOneFrozenMetacognitiveDirective() {
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
        val requests = mutableListOf<InferenceRequest>()
        val inference = CognitiveInferencePort { request ->
            requests += request
            val critic = request.prompt.contains("<AMPER_PLAN_CRITIC_V1>")
            Result.success(
                InferenceResponse(
                    modelId = ModelId(if (critic) "phase260-critic" else "phase258-planner"),
                    backendId = if (critic) "phase260-critic" else "phase258-planner",
                    text = if (critic) {
                        """
                        <AMPER_PLAN_CRITIC_V1>
                        verdict=ACCEPT
                        critique=Frozen metacognitive control is consistent with the bounded plan
                        </AMPER_PLAN_CRITIC_V1>
                        """.trimIndent()
                    } else {
                        """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase256.test
                        step.1.reason=Read the bounded governed value
                        step.1.input=read
                        </AMPER_PLAN_V1>
                        """.trimIndent()
                    },
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(capability),
            maxOutputTokens = 400,
            temperature = 1.0,
            criticInference = inference
        )

        val plan = planner.create(
            runtime.conversations.primary(),
            "Read the governed value"
        ).getOrThrow()

        assertEquals(2, requests.size)
        val plannerPrompt = requests[0].prompt
        val criticPrompt = requests[1].prompt
        assertTrue(plannerPrompt.contains("<METACOGNITIVE_CONTROL>"))
        assertTrue(criticPrompt.contains("<METACOGNITIVE_CONTROL_BINDING>"))

        val plannerDigest = requireNotNull(CONTROL_DIGEST.find(plannerPrompt)?.groupValues?.get(1))
        val criticDigest = requireNotNull(CONTROL_DIGEST.find(criticPrompt)?.groupValues?.get(1))
        val plannerMode = requireNotNull(MODE.find(plannerPrompt)?.groupValues?.get(1))
        val criticMode = requireNotNull(MODE.find(criticPrompt)?.groupValues?.get(1))

        assertEquals(plannerDigest, criticDigest)
        assertEquals(plannerMode, criticMode)
        assertTrue(requests[0].maxOutputTokens <= 400)
        assertTrue(requests[0].temperature <= 1.0)
        assertTrue(requests[1].maxOutputTokens <= 400)
        assertTrue(requests[1].temperature <= requests[0].temperature)
        assertEquals(ToolId("phase256-provider"), plan.steps.single().boundToolId)
        assertEquals(0, executions)
        assertTrue(audit.snapshot().isEmpty())
    }

    @Test
    fun cautiousControlCannotBypassExplicitSideEffectApproval() {
        val runtime = AmperRuntime.reference()
        var executions = 0
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
        val audit = InMemoryToolAuditLog()
        val actions = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = audit
            )
        )
        val inference = CognitiveInferencePort { request ->
            val critic = request.prompt.contains("<AMPER_PLAN_CRITIC_V1>")
            Result.success(
                InferenceResponse(
                    modelId = ModelId(if (critic) "phase260-critic" else "phase258-planner"),
                    backendId = if (critic) "phase260-critic" else "phase258-planner",
                    text = if (critic) {
                        """
                        <AMPER_PLAN_CRITIC_V1>
                        verdict=ACCEPT
                        critique=Side effect remains governed by explicit approval
                        </AMPER_PLAN_CRITIC_V1>
                        """.trimIndent()
                    } else {
                        """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase256.test
                        step.1.reason=Apply the explicitly requested local change
                        step.1.input=apply
                        </AMPER_PLAN_V1>
                        """.trimIndent()
                    },
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(capability),
            criticInference = inference
        )

        val plan = planner.create(
            runtime.conversations.primary(),
            "Apply the local governed change"
        ).getOrThrow()
        val pending = planner.advance(plan).getOrThrow()

        assertTrue(pending is PlanAdvanceResult.PendingApproval)
        assertEquals(0, executions)
        assertTrue(audit.snapshot().isEmpty())
    }

    companion object {
        private val CONTROL_DIGEST = Regex("cognitive_state_digest=([0-9a-f]{64})")
        private val MODE = Regex("mode=(DIRECT|DELIBERATE|CAUTIOUS)")
    }
}
