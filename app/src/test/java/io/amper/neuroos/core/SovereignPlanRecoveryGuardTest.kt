package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignPlanRecoveryGuardTest {
    private val capability = CapabilityId("test.state")

    private fun provider(id: String, sideEffect: ToolSideEffect): ToolProvider = object : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId(id),
            name = "Recovery guard provider",
            capability = capability,
            sideEffect = sideEffect,
            inputContract = ToolInputContract(
                description = "Apply guarded state",
                acceptedValues = setOf("apply"),
                maxLength = 16
            )
        )

        override fun execute(input: String): Result<String> = Result.success("executed:$input")
    }

    private fun coordinator(
        runtime: AmperRuntime,
        registry: InMemoryToolRegistry,
        audit: InMemoryToolAuditLog
    ): SovereignPlanCoordinator {
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(setOf(capability)),
            registry = registry,
            audit = audit
        )
        return SovereignPlanCoordinator(
            runtime = runtime,
            inference = CognitiveInferencePort {
                Result.failure(IllegalStateException("planning inference is not used in recovery tests"))
            },
            actions = runtime.actionLoop(registry, fabric),
            advertisedCapabilities = setOf(capability)
        )
    }

    private fun pendingPlan(
        toolId: String,
        sideEffect: ToolSideEffect = ToolSideEffect.LOCAL_STATE
    ): SovereignPlan {
        val proposal = ActionProposal(
            requestId = ActionRequestId("stable-recovery-request"),
            capability = capability,
            reason = "Apply state after explicit approval",
            input = "apply"
        )
        return SovereignPlan(
            id = PlanId("recovered-plan"),
            conversationId = ConversationId("recovery-thread"),
            goal = "recover safely",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = proposal.requestId,
                    capability = proposal.capability,
                    reason = proposal.reason,
                    input = proposal.input,
                    status = PlanStepStatus.REQUIRES_CONFIRMATION,
                    outcome = ActionOutcome(
                        status = ActionStatus.REQUIRES_CONFIRMATION,
                        proposal = proposal,
                        toolId = ToolId(toolId),
                        detail = "$sideEffect action requires explicit approval"
                    )
                )
            ),
            planningBackendId = "recovered-planner"
        )
    }

    private fun plannedPlan(
        boundToolId: ToolId?,
        boundSideEffect: ToolSideEffect?
    ): SovereignPlan = SovereignPlan(
        id = PlanId("planned-recovery"),
        conversationId = ConversationId("recovery-thread"),
        goal = "recover one planned step",
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("planned-recovery-request"),
                capability = capability,
                reason = "Apply one guarded value",
                input = "apply",
                boundToolId = boundToolId,
                boundSideEffect = boundSideEffect
            )
        ),
        planningBackendId = "recovered-planner"
    )

    @Test
    fun recoveredApprovalFailsClosedWhenProviderIdentityChanged() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also {
            it.register(provider("new-provider", ToolSideEffect.LOCAL_STATE))
        }
        val audit = InMemoryToolAuditLog()
        val planner = coordinator(runtime, registry, audit)

        val result = planner.approve(pendingPlan("old-provider"), 1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("tool provider changed"))
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun recoveredApprovalFailsClosedWhenSideEffectClassChanged() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also {
            it.register(provider("stable-provider", ToolSideEffect.EXTERNAL))
        }
        val audit = InMemoryToolAuditLog()
        val planner = coordinator(runtime, registry, audit)

        val result = planner.approve(
            pendingPlan("stable-provider", ToolSideEffect.LOCAL_STATE),
            1
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("side-effect changed"))
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun legacyRecoveredPlannedStepWithoutPlanTimeBindingFailsClosed() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also {
            it.register(provider("live-provider", ToolSideEffect.READ_ONLY))
        }
        val audit = InMemoryToolAuditLog()
        val planner = coordinator(runtime, registry, audit)

        val result = planner.advance(plannedPlan(boundToolId = null, boundSideEffect = null))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no plan-time tool binding"))
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun recoveredPlannedStepFailsClosedWhenBoundProviderChanged() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also {
            it.register(provider("replacement-provider", ToolSideEffect.READ_ONLY))
        }
        val audit = InMemoryToolAuditLog()
        val planner = coordinator(runtime, registry, audit)

        val result = planner.advance(
            plannedPlan(
                boundToolId = ToolId("original-provider"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("planned tool provider changed"))
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun recoveredPlanRejectsTerminalStepAfterAnEarlierActiveStep() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also {
            it.register(provider("read-provider", ToolSideEffect.READ_ONLY))
        }
        val audit = InMemoryToolAuditLog()
        val planner = coordinator(runtime, registry, audit)
        val first = SovereignPlanStep(
            index = 1,
            requestId = ActionRequestId("first-request"),
            capability = capability,
            reason = "First active step",
            input = "apply",
            status = PlanStepStatus.PLANNED
        )
        val secondProposal = ActionProposal(
            requestId = ActionRequestId("second-request"),
            capability = capability,
            reason = "Impossible later executed step",
            input = "apply"
        )
        val second = SovereignPlanStep(
            index = 2,
            requestId = secondProposal.requestId,
            capability = capability,
            reason = secondProposal.reason,
            input = secondProposal.input,
            status = PlanStepStatus.EXECUTED,
            outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = secondProposal,
                toolId = ToolId("read-provider"),
                output = "already ran"
            )
        )
        val recovered = SovereignPlan(
            id = PlanId("out-of-order"),
            conversationId = ConversationId("recovery-thread"),
            goal = "reject replay ordering",
            steps = listOf(first, second),
            planningBackendId = "recovered-planner"
        )

        val result = planner.advance(recovered)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("terminal step follows an active step"))
        assertEquals(0, audit.snapshot().size)
    }
}
