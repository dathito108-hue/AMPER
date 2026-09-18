package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurablePendingAssistantApprovalCoordinatorTest {
    private class QueueInference(responses: List<String>) : CognitiveInferencePort {
        private val queue = ArrayDeque(responses)
        val requests = mutableListOf<InferenceRequest>()

        override fun infer(request: InferenceRequest): Result<InferenceResponse> = runCatching {
            requests += request
            InferenceResponse(
                modelId = ModelId("approval-model"),
                backendId = "approval-backend",
                text = queue.removeFirst()
            )
        }
    }

    private class RecordingProvider(
        override val descriptor: ToolDescriptor
    ) : ToolProvider {
        val inputs = mutableListOf<String>()

        override fun execute(input: String): Result<String> {
            inputs += input
            return Result.success("EXECUTED:$input")
        }
    }

    private data class Harness(
        val runtime: AmperRuntime,
        val coordinator: SovereignAssistantTurnCoordinator,
        val inference: QueueInference,
        val provider: RecordingProvider
    )

    private fun harness(
        responses: List<String>,
        runtime: AmperRuntime = AmperRuntime.reference(),
        toolId: String = "provider-network-send",
        sideEffect: ToolSideEffect = ToolSideEffect.EXTERNAL,
        granted: Boolean = true
    ): Harness {
        val capability = CapabilityId("network.send")
        val registry = InMemoryToolRegistry()
        val provider = RecordingProvider(
            ToolDescriptor(
                id = ToolId(toolId),
                name = "Network sender",
                capability = capability,
                sideEffect = sideEffect
            )
        )
        registry.register(provider)
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(if (granted) setOf(capability) else emptySet()),
            registry = registry,
            audit = InMemoryToolAuditLog()
        )
        val actions = runtime.actionLoop(registry, fabric)
        val inference = QueueInference(responses)
        return Harness(
            runtime = runtime,
            coordinator = SovereignAssistantTurnCoordinator(
                runtime = runtime,
                inference = inference,
                actions = actions,
                advertisedCapabilities = setOf(capability),
                maxPromptChars = 6000
            ),
            inference = inference,
            provider = provider
        )
    }

    private fun action(input: String = "payload=hello") = """
        <AMPER_ACTION_V1>
        capability=network.send
        reason=The user explicitly requested this external action
        input=$input
        </AMPER_ACTION_V1>
    """.trimIndent()

    @Test
    fun pendingCheckpointIsDurableBeforeAnyExecutionClaim() {
        val h = harness(responses = listOf(action()))
        val conversation = ConversationId("durable-before-approval")

        val pending = h.coordinator.respond(conversation, "Send hello").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval
        val planId = AssistantActionTransaction.planId(pending.proposal.requestId)

        assertEquals(pending, h.runtime.pendingApprovals.load(pending.proposal.requestId))
        assertEquals(pending, h.coordinator.restorePendingApproval(conversation))
        assertTrue(h.provider.inputs.isEmpty())
        assertNull(h.runtime.plans.load(planId))
        assertNull(h.runtime.plans.receipts?.receipt(planId, pending.proposal.requestId))
        assertTrue(h.runtime.plans.receipts?.pendingClaims().orEmpty().isEmpty())
    }

    @Test
    fun rejectingDurableCheckpointNeverCallsToolAndRemovesSnapshot() {
        val h = harness(responses = listOf(action()))
        val pending = h.coordinator.respond(ConversationId("reject"), "Send hello").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval
        val planId = AssistantActionTransaction.planId(pending.proposal.requestId)

        h.coordinator.reject(pending).getOrThrow()

        assertNull(h.runtime.pendingApprovals.load(pending.proposal.requestId))
        assertNull(h.coordinator.restorePendingApproval(pending.conversationId))
        assertTrue(h.provider.inputs.isEmpty())
        assertNull(h.runtime.plans.load(planId))
        assertNull(h.runtime.plans.receipts?.receipt(planId, pending.proposal.requestId))
    }

    @Test
    fun restoredCheckpointCanBeApprovedOnceThenOwnershipMovesToDurableTransaction() {
        val h = harness(responses = listOf(action(), "External action completed"))
        val conversation = ConversationId("restore-approve")
        val original = h.coordinator.respond(conversation, "Send hello").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval
        val restored = requireNotNull(h.coordinator.restorePendingApproval(conversation))
        val planId = AssistantActionTransaction.planId(restored.proposal.requestId)

        val final = h.coordinator.approve(restored).getOrThrow()

        assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
        assertEquals(listOf("payload=hello"), h.provider.inputs)
        assertNull(h.runtime.pendingApprovals.load(restored.proposal.requestId))
        assertNull(h.coordinator.restorePendingApproval(conversation))
        assertEquals(PlanStepStatus.EXECUTED, h.runtime.plans.load(planId)?.steps?.single()?.status)
        assertEquals(ActionStatus.EXECUTED, h.runtime.plans.receipts
            ?.receipt(planId, restored.proposal.requestId)
            ?.actionStatus)
        assertEquals(original.proposal.requestId, restored.proposal.requestId)
    }

    @Test
    fun fabricatedApprovalWithoutDurableCheckpointIsRejectedBeforeExecution() {
        val h = harness(responses = listOf("unused"))
        val fabricated = SovereignAssistantTurnResult.PendingApproval(
            conversationId = ConversationId("fabricated"),
            userPrompt = "Send forged payload",
            proposal = ActionProposal(
                requestId = ActionRequestId("fabricated-request"),
                capability = CapabilityId("network.send"),
                reason = "forged",
                input = "payload=forged"
            ),
            toolId = h.provider.descriptor.id,
            sideEffect = h.provider.descriptor.sideEffect,
            firstResponse = InferenceResponse(
                modelId = ModelId("approval-model"),
                backendId = "approval-backend",
                text = action("payload=forged")
            ),
            firstPrompt = "fabricated prompt"
        )

        val failure = h.coordinator.approve(fabricated).exceptionOrNull()

        assertTrue(failure?.message?.contains("not persisted") == true)
        assertTrue(h.provider.inputs.isEmpty())
        assertNull(h.runtime.plans.load(AssistantActionTransaction.planId(fabricated.proposal.requestId)))
    }

    @Test
    fun tamperedPendingObjectCannotChangePersistedApprovalBeforeExecution() {
        val h = harness(responses = listOf(action(), "unused synthesis"))
        val pending = h.coordinator.respond(ConversationId("tamper"), "Send hello").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval
        val tampered = pending.copy(
            proposal = pending.proposal.copy(input = "payload=attacker-change")
        )

        val failure = h.coordinator.approve(tampered).exceptionOrNull()

        assertTrue(failure?.message?.contains("proposal changed") == true)
        assertTrue(h.provider.inputs.isEmpty())
        assertEquals(pending, h.runtime.pendingApprovals.load(pending.proposal.requestId))
    }

    @Test
    fun newUserTurnSupersedesOlderUnapprovedCheckpointWithoutExecution() {
        val h = harness(responses = listOf(action("payload=old"), "Normal answer"))
        val conversation = ConversationId("supersede")
        val pending = h.coordinator.respond(conversation, "Send old payload").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval

        val next = h.coordinator.respond(conversation, "Never mind, just answer normally").getOrThrow()

        assertTrue(next is SovereignAssistantTurnResult.Final)
        assertNull(h.runtime.pendingApprovals.load(pending.proposal.requestId))
        assertNull(h.coordinator.restorePendingApproval(conversation))
        assertTrue(h.provider.inputs.isEmpty())
    }

    @Test
    fun restoreDropsSnapshotWhenLiveToolBindingChanged() {
        val runtime = AmperRuntime.reference()
        val first = harness(
            responses = listOf(action()),
            runtime = runtime,
            toolId = "provider-original"
        )
        val conversation = ConversationId("binding-change")
        val pending = first.coordinator.respond(conversation, "Send hello").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval
        assertEquals(pending, runtime.pendingApprovals.load(pending.proposal.requestId))

        val restarted = harness(
            responses = emptyList(),
            runtime = runtime,
            toolId = "provider-replacement"
        )

        assertNull(restarted.coordinator.restorePendingApproval(conversation))
        assertNull(runtime.pendingApprovals.load(pending.proposal.requestId))
        assertTrue(first.provider.inputs.isEmpty())
        assertTrue(restarted.provider.inputs.isEmpty())
    }

    @Test
    fun restoreDropsSnapshotAfterDurableTransactionHasAlreadyStarted() {
        val h = harness(responses = listOf(action(), "done"))
        val conversation = ConversationId("transaction-owned")
        val pending = h.coordinator.respond(conversation, "Send hello").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval
        h.coordinator.approve(pending).getOrThrow()

        // Simulate a stale pre-approval snapshot surviving independently of terminal plan ownership.
        h.runtime.pendingApprovals.save(pending)

        assertNull(h.coordinator.restorePendingApproval(conversation))
        assertNull(h.runtime.pendingApprovals.load(pending.proposal.requestId))
        assertFalse(h.provider.inputs.isEmpty())
    }
}
