package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HierarchicalDeliberationTest {
    private val read = CapabilityId("test.read")
    private val write = CapabilityId("test.write")

    private fun descriptor(
        capability: CapabilityId,
        name: String
    ): ToolDescriptor = ToolDescriptor(
        id = ToolId("provider-${capability.value}"),
        name = name,
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = if (capability == read) {
                "inspect current sensor record"
            } else {
                "inspect alternate local cache"
            },
            acceptedValues = setOf("one"),
            maxLength = 16
        )
    )

    private fun completed(
        id: String,
        capability: CapabilityId,
        status: PlanStepStatus
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase180-history"),
        goal = "private historic goal",
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("$id-step"),
                capability = capability,
                reason = "private historic reason",
                input = "one",
                status = status,
                boundToolId = ToolId("provider-${capability.value}"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "historic"
    )

    private fun deliberation(
        first: CapabilityId = read,
        second: CapabilityId = write
    ): String = """
        <AMPER_DELIBERATION_V1>
        candidate.1.step.1.capability=${first.value}
        candidate.1.step.1.reason=Use first route
        candidate.1.step.1.input=one
        candidate.2.step.1.capability=${second.value}
        candidate.2.step.1.reason=Use second route
        candidate.2.step.1.input=one
        </AMPER_DELIBERATION_V1>
    """.trimIndent()

    @Test
    fun protocolParsesMultipleCandidatesThroughCanonicalPlanBinding() {
        val parsed = TitanDeliberationProtocol.parse(
            modelOutput = deliberation(),
            allowedCapabilities = setOf(read, write),
            descriptors = listOf(
                descriptor(read, "Sensor reader"),
                descriptor(write, "Cache reader")
            )
        ).getOrThrow()

        assertEquals(2, parsed.size)
        assertEquals(listOf(read), parsed[0].steps.map { it.capability })
        assertEquals(listOf(write), parsed[1].steps.map { it.capability })
        assertEquals(ToolId("provider-test.read"), parsed[0].steps.single().boundToolId)
        assertEquals(ToolId("provider-test.write"), parsed[1].steps.single().boundToolId)
    }

    @Test
    fun legacySinglePlanRemainsCompatible() {
        val parsed = TitanDeliberationProtocol.parse(
            modelOutput = """
                <AMPER_PLAN_V1>
                step.1.capability=test.read
                step.1.reason=Use canonical legacy route
                step.1.input=one
                </AMPER_PLAN_V1>
            """.trimIndent(),
            allowedCapabilities = setOf(read),
            descriptors = listOf(descriptor(read, "Sensor reader"))
        ).getOrThrow()

        assertEquals(1, parsed.size)
        assertEquals(listOf(read), parsed.single().steps.map { it.capability })
    }

    @Test
    fun everyCandidateMustPassCanonicalToolContractValidation() {
        val result = TitanDeliberationProtocol.parse(
            modelOutput = """
                <AMPER_DELIBERATION_V1>
                candidate.1.step.1.capability=test.read
                candidate.1.step.1.reason=Valid route
                candidate.1.step.1.input=one
                candidate.2.step.1.capability=test.write
                candidate.2.step.1.reason=Invalid contract route
                candidate.2.step.1.input=outside-contract
                </AMPER_DELIBERATION_V1>
            """.trimIndent(),
            allowedCapabilities = setOf(read, write),
            descriptors = listOf(
                descriptor(read, "Sensor reader"),
                descriptor(write, "Cache reader")
            )
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun durableEvidenceCanSelectBetterCounterfactualWithoutExecutingAnything() {
        val runtime = AmperRuntime.reference()
        repeat(12) { index ->
            runtime.strategies.observe(
                completed("read-success-$index", read, PlanStepStatus.EXECUTED)
            )
        }
        val candidates = TitanDeliberationProtocol.parse(
            modelOutput = deliberation(),
            allowedCapabilities = setOf(read, write),
            descriptors = listOf(
                descriptor(read, "Sensor reader"),
                descriptor(write, "Cache reader")
            )
        ).getOrThrow()

        val selection = EvidenceGroundedDeliberationEvaluator.select(
            candidates = candidates,
            strategies = runtime.strategies,
            allowedCapabilities = setOf(read, write)
        )

        assertEquals(listOf(read), selection.selected.candidate.steps.map { it.capability })
        assertEquals(2, selection.evaluated.size)
        assertTrue(selection.selected.evidenceObserved)
        assertTrue(selection.selected.historicalEvidenceSupport > 0.5)
    }

    @Test
    fun coordinatorMaterializesOnlySelectedCandidateAndExecutesZeroTools() {
        val runtime = AmperRuntime.reference()
        repeat(12) { index ->
            runtime.strategies.observe(
                completed("read-history-$index", read, PlanStepStatus.EXECUTED)
            )
        }

        var executions = 0
        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(object : ToolProvider {
                override val descriptor = descriptor(read, "Sensor reader")
                override fun execute(input: String): Result<String> = runCatching {
                    executions += 1
                    "read:$input"
                }
            })
            registry.register(object : ToolProvider {
                override val descriptor = descriptor(write, "Cache reader")
                override fun execute(input: String): Result<String> = runCatching {
                    executions += 1
                    "write:$input"
                }
            })
        }
        val audit = InMemoryToolAuditLog()
        val actions = runtime.actionLoop(
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
                    modelId = ModelId("phase180-planner"),
                    backendId = "phase180-test-backend",
                    text = deliberation(first = write, second = read),
                    selectedCapabilities = setOf(
                        TitanCapabilities.REASONING,
                        TitanCapabilities.PLANNING
                    )
                )
            )
        }
        val coordinator = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(read, write)
        )

        val plan = coordinator.create(
            conversationId = ConversationId("phase180-live"),
            userGoal = "inspect current sensor record"
        ).getOrThrow()

        assertEquals(1, requests.size)
        assertTrue(requests.single().prompt.contains("<AMPER_DELIBERATION_V1>"))
        assertEquals(2, plan.deliberationCandidateCount)
        assertTrue(plan.deliberationScore != null)
        assertEquals(listOf(read), plan.steps.map { it.capability })
        assertEquals(PlanStepStatus.PLANNED, plan.steps.single().status)
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun recoveryDeliberationExcludesFailedSignatureBeforeSelection() {
        val runtime = AmperRuntime.reference()
        val failedOne = completed("failed-one", read, PlanStepStatus.FAILED)
        val failedTwo = completed("failed-two", read, PlanStepStatus.FAILED)
        runtime.strategies.observe(failedOne)
        runtime.strategies.observe(failedTwo)

        var executions = 0
        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(object : ToolProvider {
                override val descriptor = descriptor(read, "Sensor reader")
                override fun execute(input: String): Result<String> = runCatching {
                    executions += 1
                    "read:$input"
                }
            })
            registry.register(object : ToolProvider {
                override val descriptor = descriptor(write, "Cache reader")
                override fun execute(input: String): Result<String> = runCatching {
                    executions += 1
                    "write:$input"
                }
            })
        }
        val audit = InMemoryToolAuditLog()
        val actions = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(read, write)),
                registry = registry,
                audit = audit
            )
        )
        val inference = CognitiveInferencePort {
            Result.success(
                InferenceResponse(
                    modelId = ModelId("phase180-recovery"),
                    backendId = "phase180-test-backend",
                    text = deliberation(first = read, second = write),
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
        val coordinator = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(read, write)
        )

        val replacement = coordinator.recover(failedTwo).getOrThrow()

        assertEquals(listOf(write), replacement.steps.map { it.capability })
        assertEquals(failedTwo.id, replacement.parentPlanId)
        assertEquals(1, replacement.recoveryDepth)
        assertEquals(1, replacement.deliberationCandidateCount)
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun deliberationSelectionContainsNoExecutionClaimOrAuthorityMutation() {
        val runtime = AmperRuntime.reference()
        val candidates = TitanDeliberationProtocol.parse(
            modelOutput = deliberation(),
            allowedCapabilities = setOf(read, write),
            descriptors = listOf(
                descriptor(read, "Sensor reader"),
                descriptor(write, "Cache reader")
            )
        ).getOrThrow()
        val selection = EvidenceGroundedDeliberationEvaluator.select(
            candidates = candidates,
            strategies = runtime.strategies,
            allowedCapabilities = setOf(read, write)
        )

        val rendered = EvidenceGroundedDeliberationEvaluator.renderSelection(selection)

        assertTrue(rendered.contains("candidates=2"))
        assertTrue(rendered.contains("selected="))
        assertFalse(rendered.contains("executed"))
        assertFalse(rendered.contains("permission"))
        assertFalse(rendered.contains("authority"))
    }
}
