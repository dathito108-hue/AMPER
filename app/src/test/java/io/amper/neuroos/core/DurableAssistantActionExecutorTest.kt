package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableAssistantActionExecutorTest {
    private class RecordingProvider(
        private val toolId: String,
        private val capability: CapabilityId,
        private val sideEffect: ToolSideEffect
    ) : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId(toolId),
            name = "Provider $toolId",
            capability = capability,
            sideEffect = sideEffect,
            inputContract = ToolInputContract("single test input", setOf("apply"), 32)
        )
        val inputs = mutableListOf<String>()

        override fun execute(input: String): Result<String> {
            inputs += input
            return Result.success("applied:$input")
        }
    }

    private data class Fixture(
        val runtime: AmperRuntime,
        val registry: InMemoryToolRegistry,
        val audit: InMemoryToolAuditLog,
        val provider: RecordingProvider,
        val actions: SovereignActionLoop,
        val executor: DurableAssistantActionExecutor
    )

    private fun fixture(
        capability: CapabilityId = CapabilityId("test.assistant.state"),
        toolId: String = "assistant-state-tool",
        sideEffect: ToolSideEffect = ToolSideEffect.LOCAL_STATE
    ): Fixture {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry()
        val provider = RecordingProvider(toolId, capability, sideEffect)
        registry.register(provider)
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(setOf(capability)),
            registry = registry,
            audit = audit
        )
        val actions = runtime.actionLoop(registry, fabric)
        return Fixture(
            runtime = runtime,
            registry = registry,
            audit = audit,
            provider = provider,
            actions = actions,
            executor = DurableAssistantActionExecutor(runtime.plans, actions)
        )
    }

    private fun pending(
        capability: CapabilityId,
        toolId: ToolId,
        sideEffect: ToolSideEffect,
        requestId: String = "assistant-r1"
    ): SovereignAssistantTurnResult.PendingApproval {
        val proposal = ActionProposal(
            requestId = ActionRequestId(requestId),
            capability = capability,
            reason = "Apply the explicitly approved assistant state change",
            input = "apply"
        )
        return SovereignAssistantTurnResult.PendingApproval(
            conversationId = ConversationId("assistant-thread"),
            userPrompt = "Apply the state change",
            proposal = proposal,
            toolId = toolId,
            sideEffect = sideEffect,
            firstResponse = InferenceResponse(
                modelId = ModelId("assistant-test-model"),
                backendId = "assistant-test-backend",
                text = "pending"
            ),
            firstPrompt = "grounded prompt"
        )
    }

    @Test
    fun successfulApprovalPersistsClaimAndTerminalReceiptWithoutRecoveryDebt() {
        val capability = CapabilityId("test.assistant.state")
        val f = fixture(capability)
        val pending = pending(capability, f.provider.descriptor.id, ToolSideEffect.LOCAL_STATE)
        val planId = AssistantActionTransaction.planId(pending.proposal.requestId)

        val outcome = f.executor.approve(pending).getOrThrow()

        assertEquals(ActionStatus.EXECUTED, outcome.status)
        assertEquals(listOf("apply"), f.provider.inputs)
        assertEquals(1, f.audit.snapshot().size)
        assertNotNull(f.runtime.plans.receipts?.claim(planId, pending.proposal.requestId))
        assertNotNull(f.runtime.plans.receipts?.receipt(planId, pending.proposal.requestId))
        val stored = f.runtime.plans.load(planId)
        assertNotNull(stored)
        assertEquals(PlanStepStatus.EXECUTED, stored!!.steps.single().status)
        assertTrue(AssistantActionTransaction.isTransaction(stored))
        assertFalse(SovereignRecoveryState(f.runtime.plans.receipts).hasUnresolvedClaims())
    }

    @Test
    fun finalizedAssistantRequestCannotReplayProvider() {
        val capability = CapabilityId("test.assistant.replay")
        val f = fixture(capability, toolId = "assistant-replay-tool")
        val pending = pending(
            capability = capability,
            toolId = f.provider.descriptor.id,
            sideEffect = ToolSideEffect.LOCAL_STATE,
            requestId = "assistant-replay-r1"
        )

        f.executor.approve(pending).getOrThrow()
        val replay = f.executor.approve(pending)

        assertTrue(replay.isFailure)
        assertTrue(replay.exceptionOrNull()?.message.orEmpty().contains("already finalized"))
        assertEquals(listOf("apply"), f.provider.inputs)
        assertEquals(1, f.audit.snapshot().size)
    }

    @Test
    fun providerSwapBeforeApprovalFailsBeforeClaimOrExecution() {
        val capability = CapabilityId("test.assistant.binding")
        val f = fixture(capability, toolId = "assistant-original-tool")
        val pending = pending(
            capability = capability,
            toolId = f.provider.descriptor.id,
            sideEffect = ToolSideEffect.EXTERNAL,
            requestId = "assistant-binding-r1"
        )
        f.registry.unregister(f.provider.descriptor.id)
        val replacement = RecordingProvider(
            toolId = "assistant-replacement-tool",
            capability = capability,
            sideEffect = ToolSideEffect.EXTERNAL
        )
        f.registry.register(replacement)

        val result = f.executor.approve(pending)
        val planId = AssistantActionTransaction.planId(pending.proposal.requestId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("tool binding changed"))
        assertTrue(f.provider.inputs.isEmpty())
        assertTrue(replacement.inputs.isEmpty())
        assertEquals(0, f.audit.snapshot().size)
        assertNull(f.runtime.plans.load(planId))
        assertNull(f.runtime.plans.receipts?.claim(planId, pending.proposal.requestId))
        assertNull(f.runtime.plans.receipts?.receipt(planId, pending.proposal.requestId))
    }

    @Test
    fun assistantTransactionDoesNotBecomeLatestUserPlan() {
        val capability = CapabilityId("test.assistant.latest")
        val f = fixture(capability, toolId = "assistant-latest-tool")
        val pending = pending(
            capability = capability,
            toolId = f.provider.descriptor.id,
            sideEffect = ToolSideEffect.LOCAL_STATE,
            requestId = "assistant-latest-r1"
        )
        f.executor.approve(pending).getOrThrow()

        val planner = PersistentSovereignPlanCoordinator(
            delegate = SovereignPlanCoordinator(
                runtime = f.runtime,
                inference = CognitiveInferencePort { Result.failure(IllegalStateException("unused")) },
                actions = f.actions,
                advertisedCapabilities = setOf(capability)
            ),
            store = f.runtime.plans
        )

        assertNull(planner.latest())
    }
}
