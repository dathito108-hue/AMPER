package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundPlannerApprovalTest {
    private class RecordingProvider(
        override val descriptor: ToolDescriptor
    ) : ToolProvider {
        val inputs = mutableListOf<String>()

        override fun execute(input: String): Result<String> {
            inputs += input
            return Result.success("done:$input")
        }
    }

    @Test
    fun providerSwapAfterPlannerGuardIsDeniedBeforeExecutionAndReceipted() {
        val capability = CapabilityId("planner.binding.race")
        val original = RecordingProvider(
            ToolDescriptor(
                id = ToolId("planner-original-tool"),
                name = "Planner original tool",
                capability = capability,
                sideEffect = ToolSideEffect.EXTERNAL,
                inputContract = ToolInputContract("Apply planner side effect", setOf("apply"), 32)
            )
        )
        val replacement = RecordingProvider(
            original.descriptor.copy(
                id = ToolId("planner-replacement-tool"),
                name = "Planner replacement tool"
            )
        )
        val registry = object : ToolRegistry {
            private var routeCalls = 0

            override fun register(provider: ToolProvider) = Unit
            override fun unregister(id: ToolId): Boolean = false
            override fun descriptors(): List<ToolDescriptor> = listOf(original.descriptor)
            override fun route(capability: CapabilityId): ToolProvider? {
                routeCalls += 1
                return if (routeCalls == 1) original else replacement
            }
        }
        val runtime = AmperRuntime.reference()
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(setOf(capability)),
            registry = registry,
            audit = audit
        )
        val actions = runtime.actionLoop(registry, fabric)
        val delegate = SovereignPlanCoordinator(
            runtime = runtime,
            inference = CognitiveInferencePort {
                Result.failure(IllegalStateException("planning inference is not used"))
            },
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )
        val planner = PersistentSovereignPlanCoordinator(
            delegate = delegate,
            store = runtime.plans
        )
        val proposal = ActionProposal(
            requestId = ActionRequestId("planner-bound-race"),
            capability = capability,
            reason = "execute only the provider reviewed at approval checkpoint",
            input = "apply"
        )
        val pendingStep = SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.REQUIRES_CONFIRMATION,
            outcome = ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = original.descriptor.id,
                detail = "EXTERNAL action requires explicit approval"
            )
        )
        val plan = SovereignPlan(
            id = PlanId("planner-bound-plan"),
            conversationId = ConversationId("planner-bound-thread"),
            goal = "test bound planner approval",
            steps = listOf(pendingStep),
            planningBackendId = "planner-test"
        )

        val processed = planner.approve(plan, 1).getOrThrow()
        val ledger = requireNotNull(runtime.plans.receipts)

        assertEquals(ActionStatus.DENIED, processed.outcome.status)
        assertEquals(PlanStepStatus.DENIED, processed.step.status)
        assertTrue(processed.outcome.detail.orEmpty().contains("approved tool binding changed"))
        assertTrue(original.inputs.isEmpty())
        assertTrue(replacement.inputs.isEmpty())
        assertEquals(1, audit.snapshot().size)
        assertFalse(audit.snapshot().single().authorized)
        assertNotNull(ledger.claim(plan.id, proposal.requestId))
        assertNotNull(ledger.receipt(plan.id, proposal.requestId))
        assertFalse(SovereignRecoveryState(ledger).hasUnresolvedClaims())
    }
}
