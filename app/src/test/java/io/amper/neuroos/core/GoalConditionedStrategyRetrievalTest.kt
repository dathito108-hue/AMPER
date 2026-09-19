package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalConditionedStrategyRetrievalTest {
    private val read = CapabilityId("test.read")
    private val write = CapabilityId("test.write")

    private fun descriptor(
        capability: CapabilityId,
        name: String,
        description: String
    ): ToolDescriptor = ToolDescriptor(
        id = ToolId("provider-${capability.value}"),
        name = name,
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = description,
            acceptedValues = emptySet(),
            maxLength = 64
        )
    )

    private fun evidence(
        capability: CapabilityId,
        successes: Int,
        failures: Int,
        failureStreak: Int = 0,
        lastOutcome: StrategyOutcomeKind? = null
    ): StrategyEvidenceSnapshot = StrategyEvidenceSnapshot(
        signature = StrategySignature(listOf(capability)),
        successes = successes,
        failures = failures,
        consecutiveFailures = failureStreak,
        lastOutcome = lastOutcome,
        lastObservedAtEpochMs = 1_000L
    )

    @Test
    fun goalConditionCanRerankNearbyEvidenceWithoutChangingEvidenceSupport() {
        val candidates = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(
                evidence(read, successes = 3, failures = 1),
                evidence(write, successes = 4, failures = 1)
            ),
            allowedCapabilities = setOf(read, write)
        )
        assertEquals(listOf(write), candidates.first().signature.capabilities)

        val ranked = GoalConditionedStrategyRetrieval.rank(
            candidates = candidates,
            goal = "inspect sensor record",
            descriptors = listOf(
                descriptor(read, "Sensor reader", "inspect sensor record"),
                descriptor(write, "Configuration writer", "replace configuration setting")
            )
        )

        assertEquals(listOf(read), ranked[0].signature.capabilities)
        assertEquals(listOf(write), ranked[1].signature.capabilities)
        val readSupportBefore = candidates.single { it.signature.capabilities == listOf(read) }.evidenceSupport
        val readSupportAfter = ranked.single { it.signature.capabilities == listOf(read) }.evidenceSupport
        assertEquals(readSupportBefore, readSupportAfter, 0.0)
    }

    @Test
    fun strongGovernedEvidenceStillBeatsWeakGoalMatch() {
        val candidates = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(
                evidence(read, successes = 3, failures = 1),
                evidence(write, successes = 8, failures = 1)
            ),
            allowedCapabilities = setOf(read, write)
        )

        val ranked = GoalConditionedStrategyRetrieval.rank(
            candidates = candidates,
            goal = "inspect sensor record",
            descriptors = listOf(
                descriptor(read, "Sensor reader", "inspect sensor record"),
                descriptor(write, "Configuration writer", "replace configuration setting")
            )
        )

        assertEquals(listOf(write), ranked[0].signature.capabilities)
        assertEquals(listOf(read), ranked[1].signature.capabilities)
    }

    @Test
    fun noGoalSignalPreservesEvidenceOrderingExactly() {
        val candidates = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(
                evidence(read, successes = 3, failures = 1),
                evidence(write, successes = 4, failures = 1)
            ),
            allowedCapabilities = setOf(read, write)
        )

        val ranked = GoalConditionedStrategyRetrieval.rank(
            candidates = candidates,
            goal = "unrelated horizon poetry",
            descriptors = listOf(
                descriptor(read, "Sensor reader", "inspect sensor record"),
                descriptor(write, "Configuration writer", "replace configuration setting")
            )
        )

        assertEquals(candidates.map { it.signature }, ranked.map { it.signature })
    }

    @Test
    fun goalMatchCannotResurrectZeroSupportFailureStrategy() {
        val candidates = EvidenceGroundedStrategyGuidance.select(
            evidence = listOf(
                evidence(
                    read,
                    successes = 0,
                    failures = 3,
                    failureStreak = 3,
                    lastOutcome = StrategyOutcomeKind.FAILED
                ),
                evidence(write, successes = 2, failures = 1)
            ),
            allowedCapabilities = setOf(read, write)
        )

        val ranked = GoalConditionedStrategyRetrieval.rank(
            candidates = candidates,
            goal = "inspect sensor record",
            descriptors = listOf(
                descriptor(read, "Sensor reader", "inspect sensor record"),
                descriptor(write, "Configuration writer", "replace configuration setting")
            )
        )

        assertEquals(listOf(write), ranked.first().signature.capabilities)
        assertEquals(0.0, ranked.last().evidenceSupport, 0.0)
    }

    @Test
    fun canonicalPlannerDoesNotInjectLegacyStrategyHistoryTwice() {
        val runtime = AmperRuntime.reference()
        fun completed(id: String, capability: CapabilityId, status: PlanStepStatus) =
            SovereignPlan(
                id = PlanId(id),
                conversationId = ConversationId("phase178-history"),
                goal = "historic private goal",
                steps = listOf(
                    SovereignPlanStep(
                        index = 1,
                        requestId = ActionRequestId("$id-step"),
                        capability = capability,
                        reason = "historic private reason",
                        input = "historic-private-input",
                        status = status,
                        boundToolId = ToolId("provider-${capability.value}"),
                        boundSideEffect = ToolSideEffect.READ_ONLY
                    )
                ),
                planningBackendId = "historic-planner"
            )

        repeat(3) { index ->
            runtime.strategies.observe(completed("read-success-$index", read, PlanStepStatus.EXECUTED))
        }
        runtime.strategies.observe(completed("read-failure", read, PlanStepStatus.FAILED))
        repeat(4) { index ->
            runtime.strategies.observe(completed("write-success-$index", write, PlanStepStatus.EXECUTED))
        }
        runtime.strategies.observe(completed("write-failure", write, PlanStepStatus.FAILED))

        var executions = 0
        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(object : ToolProvider {
                override val descriptor = descriptor(read, "Sensor reader", "inspect sensor record")
                override fun execute(input: String): Result<String> = runCatching {
                    executions += 1
                    "read:$input"
                }
            })
            registry.register(object : ToolProvider {
                override val descriptor = descriptor(write, "Configuration writer", "replace configuration setting")
                override fun execute(input: String): Result<String> = runCatching {
                    executions += 1
                    "write:$input"
                }
            })
        }
        val audit = InMemoryToolAuditLog()
        val loop = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(read, write)),
                registry = registry,
                audit = audit
            )
        )
        val requests = mutableListOf<InferenceRequest>()
        val inference = CognitiveInferencePort { request ->
            requests += request
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase178-planner"),
                    backendId = "phase178-test-backend",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=test.read
                        step.1.reason=Inspect current sensor record
                        step.1.input=current
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
            advertisedCapabilities = setOf(read, write)
        )

        val plan = planner.create(
            conversationId = ConversationId("phase178-live"),
            userGoal = "inspect sensor record"
        ).getOrThrow()

        val prompt = requests.single().prompt
        assertFalse(prompt.contains("<STRATEGY_GUIDANCE>"))
        assertFalse(prompt.contains("historic private goal"))
        assertFalse(prompt.contains("historic private reason"))
        assertFalse(prompt.contains("historic-private-input"))
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
        assertEquals(PlanStepStatus.PLANNED, plan.steps.single().status)
    }
}
