package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignPlanningTest {
    private val capability = CapabilityId("test.read")

    private fun provider(
        id: String? = null,
        sideEffect: ToolSideEffect = ToolSideEffect.READ_ONLY,
        accepted: Set<String> = setOf("one", "two"),
        execute: (String) -> String
    ): ToolProvider = object : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId(id ?: "test-provider-${sideEffect.name.lowercase()}"),
            name = "Test provider",
            capability = capability,
            sideEffect = sideEffect,
            inputContract = ToolInputContract(
                description = "Test bounded planner tool",
                acceptedValues = accepted,
                maxLength = 16
            )
        )

        override fun execute(input: String): Result<String> = runCatching { execute(input) }
    }

    private fun coordinator(
        runtime: AmperRuntime,
        registry: InMemoryToolRegistry,
        audit: InMemoryToolAuditLog,
        planOutput: String,
        granted: Set<CapabilityId> = setOf(capability),
        capturedRequests: MutableList<InferenceRequest>? = null,
        selectedCapabilities: Set<CapabilityId> = linkedSetOf(
            TitanCapabilities.REASONING,
            TitanCapabilities.PLANNING
        )
    ): SovereignPlanCoordinator {
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(granted),
            registry = registry,
            audit = audit
        )
        val loop = runtime.actionLoop(registry, fabric)
        val inference = CognitiveInferencePort { request ->
            capturedRequests?.add(request)
            Result.success(
                InferenceResponse(
                    modelId = ModelId("planner-model"),
                    backendId = "planner-test-backend",
                    text = planOutput,
                    selectedCapabilities = selectedCapabilities
                )
            )
        }
        return SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = loop,
            advertisedCapabilities = setOf(capability)
        )
    }

    @Test
    fun creatingPlanNeverExecutesToolsAndBindsExactRoutedProvider() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val registry = InMemoryToolRegistry().also {
            it.register(provider { input -> executions += 1; "ok:$input" })
        }
        val audit = InMemoryToolAuditLog()
        val output = """
            <AMPER_PLAN_V1>
            step.1.capability=test.read
            step.1.reason=Read first bounded value
            step.1.input=one
            step.2.capability=test.read
            step.2.reason=Read second bounded value
            step.2.input=two
            </AMPER_PLAN_V1>
        """.trimIndent()

        val planner = coordinator(runtime, registry, audit, output)
        val conversation = runtime.conversations.primary()
        val plan = planner.create(conversation, "inspect two values").getOrThrow()

        assertEquals(2, plan.steps.size)
        assertTrue(plan.steps.all { it.status == PlanStepStatus.PLANNED })
        assertTrue(plan.steps.all { it.boundToolId == ToolId("test-provider-read_only") })
        assertTrue(plan.steps.all { it.boundSideEffect == ToolSideEffect.READ_ONLY })
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
        assertTrue(runtime.conversations.recent(conversation, 8).any { it.text.contains("Sovereign plan") })
    }

    @Test
    fun planningRouteProvenanceIsPreservedInPlanAndCompletedAssistantTurn() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also {
            it.register(provider { input -> "ok:$input" })
        }
        val audit = InMemoryToolAuditLog()
        val selected = linkedSetOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING)
        val output = """
            <AMPER_PLAN_V1>
            step.1.capability=test.read
            step.1.reason=Read one bounded value
            step.1.input=one
            </AMPER_PLAN_V1>
        """.trimIndent()
        val planner = coordinator(
            runtime = runtime,
            registry = registry,
            audit = audit,
            planOutput = output,
            selectedCapabilities = selected
        )
        val conversation = ConversationId("planning-provenance")

        val plan = planner.create(conversation, "build an auditable plan").getOrThrow()

        assertEquals("planner-test-backend", plan.planningBackendId)
        assertEquals(ModelId("planner-model"), plan.planningModelId)
        assertEquals(selected, plan.planningSelectedCapabilities)
        assertEquals(ModelId("planner-model"), runtime.conversations.latestAssistantModelId(conversation))
        assertEquals(selected, runtime.conversations.latestAssistantSelectedCapabilities(conversation))
    }

    @Test
    fun planningInferencePrefersPlanningCapabilityWithReasoningFallback() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also {
            it.register(provider { input -> "ok:$input" })
        }
        val audit = InMemoryToolAuditLog()
        val requests = mutableListOf<InferenceRequest>()
        val output = """
            <AMPER_PLAN_V1>
            step.1.capability=test.read
            step.1.reason=Read one bounded value
            step.1.input=one
            </AMPER_PLAN_V1>
        """.trimIndent()
        val planner = coordinator(
            runtime = runtime,
            registry = registry,
            audit = audit,
            planOutput = output,
            capturedRequests = requests
        )

        planner.create(runtime.conversations.primary(), "build a safe plan").getOrThrow()

        val request = requests.single()
        assertEquals(setOf(TitanCapabilities.REASONING), request.requiredCapabilities)
        assertEquals(
            listOf(setOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING)),
            request.preferredCapabilityProfiles
        )
        assertEquals(
            listOf(
                setOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING),
                setOf(TitanCapabilities.REASONING)
            ),
            request.capabilityProfiles()
        )
    }

    @Test
    fun actualFallbackCapabilityProfileIsStoredTruthfully() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also {
            it.register(provider { input -> "ok:$input" })
        }
        val audit = InMemoryToolAuditLog()
        val output = """
            <AMPER_PLAN_V1>
            step.1.capability=test.read
            step.1.reason=Read one bounded value
            step.1.input=one
            </AMPER_PLAN_V1>
        """.trimIndent()
        val planner = coordinator(
            runtime = runtime,
            registry = registry,
            audit = audit,
            planOutput = output,
            selectedCapabilities = setOf(TitanCapabilities.REASONING)
        )
        val conversation = ConversationId("planning-fallback-provenance")

        val plan = planner.create(conversation, "build with safe fallback").getOrThrow()

        assertEquals(setOf(TitanCapabilities.REASONING), plan.planningSelectedCapabilities)
        assertEquals(
            setOf(TitanCapabilities.REASONING),
            runtime.conversations.latestAssistantSelectedCapabilities(conversation)
        )
        assertFalse(plan.planningSelectedCapabilities.contains(TitanCapabilities.PLANNING))
    }

    @Test
    fun eachAdvanceExecutesAtMostOneStep() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val registry = InMemoryToolRegistry().also {
            it.register(provider { input -> executions += 1; "value:$input" })
        }
        val audit = InMemoryToolAuditLog()
        val output = """
            <AMPER_PLAN_V1>
            step.1.capability=test.read
            step.1.reason=Read first value
            step.1.input=one
            step.2.capability=test.read
            step.2.reason=Read second value
            step.2.input=two
            </AMPER_PLAN_V1>
        """.trimIndent()
        val planner = coordinator(runtime, registry, audit, output)
        val initial = planner.create(runtime.conversations.primary(), "read both values").getOrThrow()

        val first = planner.advance(initial).getOrThrow() as PlanAdvanceResult.StepProcessed
        assertEquals(1, executions)
        assertEquals(1, audit.snapshot().size)
        assertEquals(PlanStepStatus.EXECUTED, first.plan.steps[0].status)
        assertEquals(PlanStepStatus.PLANNED, first.plan.steps[1].status)

        val second = planner.advance(first.plan).getOrThrow() as PlanAdvanceResult.StepProcessed
        assertEquals(2, executions)
        assertEquals(2, audit.snapshot().size)
        assertTrue(second.plan.complete)

        val learned = runtime.strategies.recent(4).single()
        assertEquals(listOf(capability, capability), learned.signature.capabilities)
        assertEquals(1, learned.successes)
        assertEquals(0, learned.failures)

        val complete = planner.advance(second.plan).getOrThrow()
        assertTrue(complete is PlanAdvanceResult.Complete)
        assertEquals(2, executions)
        assertEquals(2, audit.snapshot().size)
        assertEquals(1, runtime.strategies.recent(4).single().successes)
    }

    @Test
    fun providerReplacementAfterPlanCreationFailsClosedBeforeExecution() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val registry = InMemoryToolRegistry().also {
            it.register(provider(id = "planned-provider") { input -> executions += 1; "old:$input" })
        }
        val audit = InMemoryToolAuditLog()
        val output = """
            <AMPER_PLAN_V1>
            step.1.capability=test.read
            step.1.reason=Read one bounded value
            step.1.input=one
            </AMPER_PLAN_V1>
        """.trimIndent()
        val planner = coordinator(runtime, registry, audit, output)
        val plan = planner.create(runtime.conversations.primary(), "read with stable provider").getOrThrow()
        assertEquals(ToolId("planned-provider"), plan.steps.single().boundToolId)

        assertTrue(registry.unregister(ToolId("planned-provider")))
        registry.register(provider(id = "replacement-provider") { input -> executions += 1; "new:$input" })

        val result = planner.advance(plan)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("planned tool provider changed"))
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun boundEvaluatorRejectsProviderSubstitutionWithoutInvokingReplacement() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val registry = InMemoryToolRegistry().also {
            it.register(provider(id = "bound-original") { input -> executions += 1; "old:$input" })
        }
        val audit = InMemoryToolAuditLog()
        val loop = runtime.actionLoop(
            registry,
            AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = audit
            )
        )
        val proposal = ActionProposal(
            requestId = ActionRequestId("bound-substitution"),
            capability = capability,
            reason = "Read one stable value",
            input = "one"
        )

        assertTrue(registry.unregister(ToolId("bound-original")))
        registry.register(provider(id = "bound-replacement") { input -> executions += 1; "new:$input" })

        val outcome = loop.evaluateBound(
            proposal = proposal,
            expectedToolId = ToolId("bound-original"),
            expectedSideEffect = ToolSideEffect.READ_ONLY
        )

        assertEquals(ActionStatus.DENIED, outcome.status)
        assertEquals(ToolId("bound-original"), outcome.toolId)
        assertEquals(ToolSideEffect.READ_ONLY, outcome.sideEffect)
        assertTrue(outcome.detail.orEmpty().contains("planned tool binding changed"))
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun invalidTypedInputRejectsWholePlanBeforeExecution() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val registry = InMemoryToolRegistry().also {
            it.register(provider(accepted = setOf("one")) { input -> executions += 1; input })
        }
        val audit = InMemoryToolAuditLog()
        val output = """
            <AMPER_PLAN_V1>
            step.1.capability=test.read
            step.1.reason=Attempt undeclared input
            step.1.input=forbidden
            </AMPER_PLAN_V1>
        """.trimIndent()

        val planner = coordinator(runtime, registry, audit, output)
        val result = planner.create(runtime.conversations.primary(), "use invalid value")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("outside declared contract"))
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun sideEffectStepStopsUntilExplicitApproval() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val registry = InMemoryToolRegistry().also {
            it.register(
                provider(
                    sideEffect = ToolSideEffect.LOCAL_STATE,
                    accepted = setOf("apply")
                ) { input -> executions += 1; "applied:$input" }
            )
        }
        val audit = InMemoryToolAuditLog()
        val output = """
            <AMPER_PLAN_V1>
            step.1.capability=test.read
            step.1.reason=Apply a local state change requested by the user
            step.1.input=apply
            </AMPER_PLAN_V1>
        """.trimIndent()
        val planner = coordinator(runtime, registry, audit, output)
        val plan = planner.create(runtime.conversations.primary(), "apply the local change").getOrThrow()
        assertEquals(ToolSideEffect.LOCAL_STATE, plan.steps.single().boundSideEffect)

        val pending = planner.advance(plan).getOrThrow() as PlanAdvanceResult.PendingApproval
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
        assertEquals(PlanStepStatus.REQUIRES_CONFIRMATION, pending.step.status)
        assertEquals(pending.step.requestId, pending.proposal.requestId)

        val approved = planner.approve(pending.plan, pending.step.index).getOrThrow()
        assertEquals(1, executions)
        assertEquals(1, audit.snapshot().size)
        assertEquals(PlanStepStatus.EXECUTED, approved.step.status)
        assertTrue(approved.plan.complete)
    }

    @Test
    fun rejectingPendingStepPerformsNoToolInvocation() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val registry = InMemoryToolRegistry().also {
            it.register(
                provider(
                    sideEffect = ToolSideEffect.EXTERNAL,
                    accepted = setOf("apply")
                ) { input -> executions += 1; input }
            )
        }
        val audit = InMemoryToolAuditLog()
        val output = """
            <AMPER_PLAN_V1>
            step.1.capability=test.read
            step.1.reason=External action awaiting user choice
            step.1.input=apply
            </AMPER_PLAN_V1>
        """.trimIndent()
        val planner = coordinator(runtime, registry, audit, output)
        val plan = planner.create(runtime.conversations.primary(), "consider external action").getOrThrow()
        val pending = planner.advance(plan).getOrThrow() as PlanAdvanceResult.PendingApproval

        val rejected = planner.reject(pending.plan, pending.step.index)
        assertEquals(PlanStepStatus.REJECTED, rejected.steps.single().status)
        assertTrue(rejected.complete)
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
        assertFalse(rejected.steps.single().outcome?.status == ActionStatus.EXECUTED)

        val learned = runtime.strategies.recent(4).single()
        assertEquals(0, learned.completedAttempts)
        assertEquals(1, learned.aborted)
    }
}
