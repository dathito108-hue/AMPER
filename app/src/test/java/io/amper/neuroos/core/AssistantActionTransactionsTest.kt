package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantActionTransactionsTest {
    private class RecordingProvider(
        override val descriptor: ToolDescriptor
    ) : ToolProvider {
        val inputs = mutableListOf<String>()

        override fun execute(input: String): Result<String> {
            inputs += input
            return Result.success("done:$input")
        }
    }

    private fun provider(
        id: String,
        capability: CapabilityId,
        sideEffect: ToolSideEffect
    ) = RecordingProvider(
        ToolDescriptor(
            id = ToolId(id),
            name = id,
            capability = capability,
            sideEffect = sideEffect,
            inputContract = ToolInputContract("transaction input", setOf("apply"), 32)
        )
    )

    private fun pending(
        conversationId: ConversationId,
        proposal: ActionProposal,
        descriptor: ToolDescriptor
    ) = SovereignAssistantTurnResult.PendingApproval(
        conversationId = conversationId,
        userPrompt = "apply state",
        proposal = proposal,
        toolId = descriptor.id,
        sideEffect = descriptor.sideEffect,
        firstResponse = InferenceResponse(
            modelId = ModelId("fake-model"),
            backendId = "fake-backend",
            text = "pending"
        ),
        firstPrompt = "first prompt"
    )

    private data class Fixture(
        val runtime: AmperRuntime,
        val registry: InMemoryToolRegistry,
        val audit: InMemoryToolAuditLog,
        val actions: SovereignActionLoop,
        val executor: DurableAssistantActionExecutor
    )

    private fun fixture(capability: CapabilityId): Fixture {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry()
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
            actions = actions,
            executor = DurableAssistantActionExecutor(runtime.plans, actions)
        )
    }

    @Test
    fun approvedSideEffectPersistsClaimAndTerminalReceiptWithoutRecoveryDebt() {
        val capability = CapabilityId("assistant.state.apply")
        val f = fixture(capability)
        val provider = provider("assistant-state-tool", capability, ToolSideEffect.LOCAL_STATE)
        f.registry.register(provider)
        val proposal = ActionProposal(
            requestId = ActionRequestId("assistant-tx-success"),
            capability = capability,
            reason = "apply explicit local state",
            input = "apply"
        )
        val pending = pending(ConversationId("assistant-thread"), proposal, provider.descriptor)

        val outcome = f.executor.approve(pending).getOrThrow()
        val planId = AssistantActionTransaction.planId(proposal.requestId)
        val ledger = requireNotNull(f.runtime.plans.receipts)
        val stored = requireNotNull(f.runtime.plans.load(planId))

        assertEquals(ActionStatus.EXECUTED, outcome.status)
        assertEquals(listOf("apply"), provider.inputs)
        assertEquals(1, f.audit.snapshot().size)
        assertNotNull(ledger.claim(planId, proposal.requestId))
        assertNotNull(ledger.receipt(planId, proposal.requestId))
        assertEquals(PlanStepStatus.EXECUTED, stored.steps.single().status)
        assertTrue(AssistantActionTransaction.isTransaction(stored))
        assertFalse(SovereignRecoveryState(ledger).hasUnresolvedClaims())
    }

    @Test
    fun claimedButUnfinishedAssistantTransactionIsRecoveryDebtAndCannotReplay() {
        val capability = CapabilityId("assistant.external.send")
        val f = fixture(capability)
        val provider = provider("assistant-send-tool", capability, ToolSideEffect.EXTERNAL)
        f.registry.register(provider)
        val proposal = ActionProposal(
            requestId = ActionRequestId("assistant-tx-interrupted"),
            capability = capability,
            reason = "send explicit payload",
            input = "apply"
        )
        val pending = pending(ConversationId("assistant-crash-thread"), proposal, provider.descriptor)
        val planId = AssistantActionTransaction.planId(proposal.requestId)
        val step = SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.REQUIRES_CONFIRMATION,
            outcome = ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = provider.descriptor.id,
                detail = "EXTERNAL action requires explicit approval"
            )
        )
        val transaction = SovereignPlan(
            id = planId,
            conversationId = pending.conversationId,
            goal = pending.userPrompt,
            steps = listOf(step),
            planningBackendId = "${AssistantActionTransaction.BACKEND_PREFIX}fake-backend"
        )
        f.runtime.plans.save(transaction)
        requireNotNull(f.runtime.plans.receipts).claimSideEffect(transaction, step).getOrThrow()

        assertTrue(SovereignRecoveryState(f.runtime.plans.receipts).hasUnresolvedClaims())
        val replay = f.executor.approve(pending)

        assertTrue(replay.isFailure)
        assertTrue(replay.exceptionOrNull()?.message.orEmpty().contains("durable transaction state"))
        assertTrue(provider.inputs.isEmpty())
        assertEquals(0, f.audit.snapshot().size)
        assertNull(f.runtime.plans.receipts?.receipt(planId, proposal.requestId))
    }

    @Test
    fun providerBindingSwapFailsBeforeClaimOrInvocation() {
        val capability = CapabilityId("assistant.binding.apply")
        val f = fixture(capability)
        val original = provider("original-tool", capability, ToolSideEffect.LOCAL_STATE)
        val replacement = provider("replacement-tool", capability, ToolSideEffect.LOCAL_STATE)
        f.registry.register(original)
        val proposal = ActionProposal(
            requestId = ActionRequestId("assistant-binding-swap"),
            capability = capability,
            reason = "apply state using approved binding",
            input = "apply"
        )
        val pending = pending(ConversationId("binding-thread"), proposal, original.descriptor)

        f.registry.unregister(original.descriptor.id)
        f.registry.register(replacement)
        val result = f.executor.approve(pending)
        val planId = AssistantActionTransaction.planId(proposal.requestId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("tool binding changed"))
        assertTrue(original.inputs.isEmpty())
        assertTrue(replacement.inputs.isEmpty())
        assertEquals(0, f.audit.snapshot().size)
        assertNull(f.runtime.plans.load(planId))
        assertNull(f.runtime.plans.receipts?.claim(planId, proposal.requestId))
    }

    @Test
    fun providerSwapBetweenPrecheckAndFabricCannotExecuteReplacement() {
        val capability = CapabilityId("assistant.binding.race")
        val runtime = AmperRuntime.reference()
        val original = provider("race-original-tool", capability, ToolSideEffect.EXTERNAL)
        val replacement = provider("race-replacement-tool", capability, ToolSideEffect.EXTERNAL)
        val registry = object : ToolRegistry {
            private var routeCalls = 0

            override fun register(provider: ToolProvider) = Unit
            override fun unregister(id: ToolId): Boolean = false
            override fun descriptors(): List<ToolDescriptor> = listOf(original.descriptor)
            override fun route(capability: CapabilityId): ToolProvider? {
                routeCalls += 1
                return if (routeCalls <= 2) original else replacement
            }
        }
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(setOf(capability)),
            registry = registry,
            audit = audit
        )
        val actions = runtime.actionLoop(registry, fabric)
        val executor = DurableAssistantActionExecutor(runtime.plans, actions)
        val proposal = ActionProposal(
            requestId = ActionRequestId("assistant-binding-race"),
            capability = capability,
            reason = "execute only the reviewed provider",
            input = "apply"
        )
        val pending = pending(ConversationId("race-thread"), proposal, original.descriptor)

        val outcome = executor.approve(pending).getOrThrow()
        val planId = AssistantActionTransaction.planId(proposal.requestId)

        assertEquals(ActionStatus.DENIED, outcome.status)
        assertTrue(outcome.detail.orEmpty().contains("approved tool binding changed"))
        assertTrue(original.inputs.isEmpty())
        assertTrue(replacement.inputs.isEmpty())
        assertEquals(1, audit.snapshot().size)
        assertFalse(audit.snapshot().single().authorized)
        assertNotNull(runtime.plans.receipts?.claim(planId, proposal.requestId))
        assertNotNull(runtime.plans.receipts?.receipt(planId, proposal.requestId))
        assertFalse(SovereignRecoveryState(runtime.plans.receipts).hasUnresolvedClaims())
    }

    @Test
    fun assistantTransactionsDoNotReplaceLatestUserPlan() {
        val capability = CapabilityId("assistant.latest.apply")
        val f = fixture(capability)
        val provider = provider("latest-tool", capability, ToolSideEffect.LOCAL_STATE)
        f.registry.register(provider)
        val userPlan = SovereignPlan(
            id = PlanId("real-user-plan"),
            conversationId = ConversationId("real-user-thread"),
            goal = "real persistent goal",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId("real-user-step"),
                    capability = capability,
                    reason = "real user plan step",
                    input = "apply"
                )
            ),
            planningBackendId = "planner-backend",
            createdAtEpochMs = 1L
        )
        f.runtime.plans.save(userPlan)
        val proposal = ActionProposal(
            requestId = ActionRequestId("assistant-latest-tx"),
            capability = capability,
            reason = "assistant state change",
            input = "apply"
        )
        f.executor.approve(
            pending(ConversationId("assistant-thread"), proposal, provider.descriptor)
        ).getOrThrow()

        val planner = PersistentSovereignPlanCoordinator(
            delegate = SovereignPlanCoordinator(
                runtime = f.runtime,
                inference = CognitiveInferencePort { Result.failure(IllegalStateException("unused")) },
                actions = f.actions,
                advertisedCapabilities = setOf(capability)
            ),
            store = f.runtime.plans
        )

        assertEquals(userPlan.id, planner.latest()?.id)
    }
}
