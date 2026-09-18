package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernedStrategyRecoveryTest {
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
            description = if (capability == read) "inspect current sensor record" else "use alternate local cache",
            acceptedValues = setOf("one"),
            maxLength = 16
        )
    )

    private fun terminalPlan(
        id: String,
        capability: CapabilityId = read,
        status: PlanStepStatus = PlanStepStatus.FAILED,
        recoveryDepth: Int = 0,
        parentPlanId: PlanId? = null
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = ConversationId("phase179"),
        goal = "inspect sensor record",
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("$id-step"),
                capability = capability,
                reason = "historic-private-reason-$id",
                input = "historic-private-input-$id",
                status = status,
                boundToolId = ToolId("provider-${capability.value}"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "historic-planner",
        parentPlanId = parentPlanId,
        recoveryDepth = recoveryDepth
    )

    private fun loop(executions: () -> Unit): SovereignActionLoop {
        val registry = InMemoryToolRegistry().also { registry ->
            listOf(read, write).forEach { capability ->
                registry.register(object : ToolProvider {
                    override val descriptor = descriptor(
                        capability = capability,
                        name = if (capability == read) "Sensor reader" else "Cache reader"
                    )
                    override fun execute(input: String): Result<String> = runCatching {
                        executions()
                        "ok:$input"
                    }
                })
            }
        }
        return AmperRuntime.reference().actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(read, write)),
                registry = registry,
                audit = InMemoryToolAuditLog()
            )
        )
    }

    @Test
    fun repeatedRecoverableFailureAdmitsOneDifferentPlanWithoutExecutingTools() {
        val runtime = AmperRuntime.reference()
        val first = terminalPlan("failed-one")
        val second = terminalPlan("failed-two")
        runtime.strategies.observe(first)
        runtime.strategies.observe(second)

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
                    modelId = ModelId("phase179-planner"),
                    backendId = "phase179-test-backend",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=test.write
                        step.1.reason=Use a different admitted route
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
        val coordinator = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(read, write)
        )

        val replacement = coordinator.recover(second).getOrThrow()

        assertEquals(second.id, replacement.parentPlanId)
        assertEquals(1, replacement.recoveryDepth)
        assertEquals(listOf(write), replacement.steps.map { it.capability })
        assertEquals(PlanStepStatus.PLANNED, replacement.steps.single().status)
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)

        val prompt = requests.single().prompt
        assertTrue(prompt.contains("<RECOVERY_CONSTRAINT>"))
        assertTrue(prompt.contains("Do not repeat the exact failed capability sequence: test.read"))
        assertTrue(prompt.contains("adaptation=RECOVERY_REQUIRED"))
        assertTrue(prompt.contains("consecutive_failures=2"))
        assertFalse(prompt.contains("historic-private-reason-failed-two"))
        assertFalse(prompt.contains("historic-private-input-failed-two"))
    }

    @Test
    fun authorityDeniedPlanCannotBecomeRecoveryRoute() {
        val runtime = AmperRuntime.reference()
        val first = terminalPlan("denied-one", status = PlanStepStatus.DENIED)
        val second = terminalPlan("denied-two", status = PlanStepStatus.DENIED)
        runtime.strategies.observe(first)
        runtime.strategies.observe(second)

        var inferenceCalls = 0
        val inference = CognitiveInferencePort {
            inferenceCalls += 1
            error("inference must not run for authority denial")
        }
        var executions = 0
        val coordinator = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = loop { executions += 1 },
            advertisedCapabilities = setOf(read, write)
        )

        val result = coordinator.recover(second)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("AUTHORITY_DENIED"))
        assertEquals(0, inferenceCalls)
        assertEquals(0, executions)
    }

    @Test
    fun userRejectedPlanCannotBecomeRecoveryRoute() {
        val runtime = AmperRuntime.reference()
        val rejected = terminalPlan("rejected", status = PlanStepStatus.REJECTED)
        runtime.strategies.observe(rejected)

        val decision = GovernedStrategyRecovery.assess(
            plan = rejected,
            evidence = runtime.strategies.snapshot(StrategySignature.from(rejected)),
            allowedCapabilities = setOf(read, write)
        )

        assertFalse(decision.eligible)
        assertEquals(StrategyRecoveryBlockReason.USER_REJECTED, decision.blockReason)
    }

    @Test
    fun recoveryDepthIsHardBoundedToOne() {
        val runtime = AmperRuntime.reference()
        val parent = PlanId("original")
        val recoveredFailure = terminalPlan(
            id = "recovered-failure",
            status = PlanStepStatus.FAILED,
            recoveryDepth = 1,
            parentPlanId = parent
        )
        repeat(2) { index ->
            runtime.strategies.observe(terminalPlan("seed-$index"))
        }
        runtime.strategies.observe(recoveredFailure)

        val decision = GovernedStrategyRecovery.assess(
            plan = recoveredFailure,
            evidence = runtime.strategies.snapshot(StrategySignature.from(recoveredFailure)),
            allowedCapabilities = setOf(read, write)
        )

        assertFalse(decision.eligible)
        assertEquals(StrategyRecoveryBlockReason.DEPTH_EXHAUSTED, decision.blockReason)
    }

    @Test
    fun modelCannotRepeatFailedCapabilitySequenceDuringRecovery() {
        val runtime = AmperRuntime.reference()
        val first = terminalPlan("repeat-one")
        val second = terminalPlan("repeat-two")
        runtime.strategies.observe(first)
        runtime.strategies.observe(second)

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
                    modelId = ModelId("phase179-repeat"),
                    backendId = "phase179-test-backend",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=test.read
                        step.1.reason=Repeat old route
                        step.1.input=one
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
            advertisedCapabilities = setOf(read, write)
        )

        val result = coordinator.recover(second)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty()
                .contains("repeated the failed capability sequence")
        )
        assertEquals(0, executions)
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun oneFailureIsInsufficientToTriggerRecovery() {
        val runtime = AmperRuntime.reference()
        val single = terminalPlan("single-failure")
        runtime.strategies.observe(single)

        val decision = GovernedStrategyRecovery.assess(
            plan = single,
            evidence = runtime.strategies.snapshot(StrategySignature.from(single)),
            allowedCapabilities = setOf(read, write)
        )

        assertFalse(decision.eligible)
        assertEquals(StrategyRecoveryBlockReason.INSUFFICIENT_EVIDENCE, decision.blockReason)
    }
}
