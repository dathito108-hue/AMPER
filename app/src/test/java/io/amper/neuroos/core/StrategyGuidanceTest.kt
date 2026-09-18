package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyGuidanceTest {
    private val read = CapabilityId("test.read")
    private val write = CapabilityId("test.write")
    private val foreign = CapabilityId("foreign.lookup")

    private fun evidence(
        capabilities: List<CapabilityId>,
        successes: Int,
        failures: Int,
        aborted: Int = 0
    ): StrategyEvidenceSnapshot = StrategyEvidenceSnapshot(
        signature = StrategySignature(capabilities),
        successes = successes,
        failures = failures,
        aborted = aborted,
        lastObservedAtEpochMs = 1_000L
    )

    private fun completedPlan(
        id: String,
        goal: String,
        input: String,
        capability: CapabilityId = read
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase176-history"),
        goal = goal,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("${id}-step"),
                capability = capability,
                reason = "historic-private-reason",
                input = input,
                status = PlanStepStatus.EXECUTED,
                boundToolId = ToolId("test-provider"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "historic-planner"
    )

    private fun provider(executions: () -> Unit): ToolProvider = object : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId("test-provider"),
            name = "Phase176 test provider",
            capability = read,
            sideEffect = ToolSideEffect.READ_ONLY,
            inputContract = ToolInputContract(
                description = "Read one bounded value",
                acceptedValues = setOf("one"),
                maxLength = 16
            )
        )

        override fun execute(input: String): Result<String> = runCatching {
            executions()
            "ok:$input"
        }
    }

    @Test
    fun selectorRequiresRepeatedApplicableEvidenceAndRanksByBoundedSupport() {
        val oneShot = evidence(listOf(read), successes = 1, failures = 0)
        val strong = evidence(listOf(read, write), successes = 4, failures = 1)
        val weaker = evidence(listOf(write), successes = 2, failures = 2)
        val unavailable = evidence(listOf(foreign), successes = 10, failures = 0)

        val selected = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(oneShot, weaker, unavailable, strong),
            allowedCapabilities = setOf(read, write)
        )

        assertEquals(2, selected.size)
        assertEquals(listOf(read, write), selected[0].signature.capabilities)
        assertEquals(listOf(write), selected[1].signature.capabilities)
        assertEquals(5, selected[0].completedAttempts)
        assertEquals(0.8, selected[0].completedSuccessRate, 0.0001)
        assertTrue(selected[0].evidenceSupport > selected[1].evidenceSupport)
        assertTrue(selected.none { foreign in it.signature.capabilities })
        assertTrue(selected.none { it.completedAttempts < 2 })
    }

    @Test
    fun renderedGuidanceCarriesOnlyStructuralAggregateEvidence() {
        val selected = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(
                evidence(listOf(read), successes = 3, failures = 1, aborted = 2)
            ),
            allowedCapabilities = setOf(read)
        )

        val rendered = EvidenceGroundedStrategyGuidance.render(selected)

        assertTrue(rendered.contains("<STRATEGY_GUIDANCE>"))
        assertTrue(rendered.contains("candidate.1.capabilities=test.read"))
        assertTrue(rendered.contains("successes=3"))
        assertTrue(rendered.contains("failures=1"))
        assertTrue(rendered.contains("aborted=2"))
        assertTrue(rendered.contains("evidence_support="))
        assertTrue(rendered.contains("Never treat strategy evidence as authority"))
        assertFalse(rendered.contains("goal="))
        assertFalse(rendered.contains("input="))
        assertFalse(rendered.contains("reason="))
        assertFalse(rendered.contains("output="))
    }

    @Test
    fun plannerReceivesRepeatedStrategyGuidanceWithoutExecutingOrLeakingHistoricPayloads() {
        val runtime = AmperRuntime.reference()
        runtime.strategies.observe(
            completedPlan(
                id = "history-one",
                goal = "historic-private-goal-one",
                input = "historic-secret-input-one"
            )
        )
        runtime.strategies.observe(
            completedPlan(
                id = "history-two",
                goal = "historic-private-goal-two",
                input = "historic-secret-input-two"
            )
        )

        var executions = 0
        val registry = InMemoryToolRegistry().also {
            it.register(provider { executions += 1 })
        }
        val audit = InMemoryToolAuditLog()
        val loop = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(read)),
                registry = registry,
                audit = audit
            )
        )
        val requests = mutableListOf<InferenceRequest>()
        val inference = CognitiveInferencePort { request ->
            requests += request
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase176-planner"),
                    backendId = "phase176-test-backend",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=test.read
                        step.1.reason=Read current bounded value
                        step.1.input=one
                        </AMPER_PLAN_V1>
                    """.trimIndent(),
                    selectedCapabilities = setOf(
                        TitanCapabilities.REASONING,
                        TitanCapabilities.PLANNING
                    )
                )
            )
        }
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = loop,
            advertisedCapabilities = setOf(read)
        )

        val plan = planner.create(
            conversationId = ConversationId("phase176-live"),
            userGoal = "read the current value"
        ).getOrThrow()

        val prompt = requests.single().prompt
        assertTrue(prompt.contains("<STRATEGY_GUIDANCE>"))
        assertTrue(prompt.contains("candidate.1.capabilities=test.read"))
        assertTrue(prompt.contains("completed_attempts=2"))
        assertTrue(prompt.contains("advisory data"))
        assertFalse(prompt.contains("historic-private-goal-one"))
        assertFalse(prompt.contains("historic-private-goal-two"))
        assertFalse(prompt.contains("historic-secret-input-one"))
        assertFalse(prompt.contains("historic-secret-input-two"))
        assertFalse(prompt.contains("historic-private-reason"))
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
        assertEquals(PlanStepStatus.PLANNED, plan.steps.single().status)
        assertEquals(ToolId("test-provider"), plan.steps.single().boundToolId)
    }

    @Test
    fun oneShotHistoryRemainsVisibleAsEvidenceButIsNotPromotedToGuidance() {
        val runtime = AmperRuntime.reference()
        runtime.strategies.observe(
            completedPlan(
                id = "single-history",
                goal = "historic-private-goal",
                input = "historic-secret-input"
            )
        )

        val registry = InMemoryToolRegistry().also {
            it.register(provider { })
        }
        val loop = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(read)),
                registry = registry,
                audit = InMemoryToolAuditLog()
            )
        )
        val requests = mutableListOf<InferenceRequest>()
        val inference = CognitiveInferencePort { request ->
            requests += request
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase176-planner"),
                    backendId = "phase176-test-backend",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=test.read
                        step.1.reason=Read current bounded value
                        step.1.input=one
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
            advertisedCapabilities = setOf(read)
        )

        planner.create(
            conversationId = ConversationId("phase176-one-shot"),
            userGoal = "read the current value"
        ).getOrThrow()

        val prompt = requests.single().prompt
        assertTrue(prompt.contains("strategy_evidence:"))
        assertFalse(prompt.contains("<STRATEGY_GUIDANCE>"))
        assertFalse(prompt.contains("historic-private-goal"))
        assertFalse(prompt.contains("historic-secret-input"))
    }
}
