package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignRecoveryInterlockTest {
    private class RecordingProvider(
        capability: CapabilityId,
        override val descriptor: ToolDescriptor = ToolDescriptor(
            id = ToolId("provider-${capability.value}"),
            name = "Provider ${capability.value}",
            capability = capability,
            sideEffect = ToolSideEffect.EXTERNAL
        )
    ) : ToolProvider {
        val inputs = mutableListOf<String>()
        override fun execute(input: String): Result<String> {
            inputs += input
            return Result.success("ok:$input")
        }
    }

    private class QueueInference(responses: List<String>) : CognitiveInferencePort {
        private val queue = ArrayDeque(responses)
        val prompts = mutableListOf<String>()
        override fun infer(request: InferenceRequest): Result<InferenceResponse> = runCatching {
            prompts += request.prompt
            InferenceResponse(
                modelId = ModelId("fake-model"),
                backendId = "fake-backend",
                text = queue.removeFirst()
            )
        }
    }

    private fun unresolvedClaim(runtime: AmperRuntime, id: String = "old-r1"): Pair<SovereignPlan, SovereignPlanStep> {
        val capability = CapabilityId("old.external")
        val proposal = ActionProposal(
            requestId = ActionRequestId(id),
            capability = capability,
            reason = "Old interrupted side effect",
            input = "old"
        )
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
                toolId = ToolId("old-tool"),
                detail = "EXTERNAL action requires explicit approval"
            )
        )
        val plan = SovereignPlan(
            id = PlanId("old-plan-$id"),
            conversationId = ConversationId("old-thread"),
            goal = "recover old action",
            steps = listOf(step),
            planningBackendId = "planner"
        )
        runtime.plans.receipts!!.claimSideEffect(plan, step).getOrThrow()
        return plan to step
    }

    @Test
    fun unresolvedRecoveryDebtBlocksAssistantApprovalButNotProposalGeneration() {
        val runtime = AmperRuntime.reference()
        unresolvedClaim(runtime)
        val capability = CapabilityId("network.send")
        val provider = RecordingProvider(capability)
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val audit = InMemoryToolAuditLog()
        val actions = runtime.actionLoop(
            registry,
            AuditedToolFabric(DenyByDefaultAuthorityGate(setOf(capability)), registry, audit)
        )
        val inference = QueueInference(
            listOf(
                """<AMPER_ACTION_V1>
capability=network.send
reason=Send the requested message
input=hello
</AMPER_ACTION_V1>""",
                "sent"
            )
        )
        val assistant = SovereignAssistantTurnCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(capability),
            maxPromptChars = 6000
        )

        val pending = assistant.respond(ConversationId("new-thread"), "send hello").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval
        val approval = assistant.approve(pending)

        assertTrue(approval.isFailure)
        assertTrue(approval.exceptionOrNull()?.message.orEmpty().contains("recovery-locked"))
        assertTrue(provider.inputs.isEmpty())
        assertEquals(0, audit.snapshot().size)
        assertEquals(1, inference.prompts.size)
    }

    @Test
    fun unresolvedRecoveryDebtDoesNotBlockReadOnlyAutomaticExecution() {
        val runtime = AmperRuntime.reference()
        unresolvedClaim(runtime, "old-r2")
        val capability = CapabilityId("device.read")
        val provider = object : ToolProvider {
            override val descriptor = ToolDescriptor(
                id = ToolId("device-read"),
                name = "Device read",
                capability = capability,
                sideEffect = ToolSideEffect.READ_ONLY
            )
            val inputs = mutableListOf<String>()
            override fun execute(input: String): Result<String> {
                inputs += input
                return Result.success("battery=80")
            }
        }
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val audit = InMemoryToolAuditLog()
        val actions = runtime.actionLoop(
            registry,
            AuditedToolFabric(DenyByDefaultAuthorityGate(setOf(capability)), registry, audit)
        )
        val inference = QueueInference(
            listOf(
                """<AMPER_ACTION_V1>
capability=device.read
reason=Read device status
input=battery
</AMPER_ACTION_V1>""",
                "Battery is 80 percent"
            )
        )
        val assistant = SovereignAssistantTurnCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(capability),
            maxPromptChars = 6000
        )

        val final = assistant.respond(ConversationId("read-thread"), "battery?").getOrThrow()
            as SovereignAssistantTurnResult.Final

        assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
        assertEquals(listOf("battery"), provider.inputs)
        assertEquals(1, audit.snapshot().size)
        assertEquals(2, inference.prompts.size)
    }

    @Test
    fun planApprovalIsBlockedBeforeNewClaimAndUnlocksAfterOldReceipt() {
        val runtime = AmperRuntime.reference()
        val (oldPlan, oldPending) = unresolvedClaim(runtime, "old-r3")
        val capability = CapabilityId("plan.external")
        val provider = RecordingProvider(capability)
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val audit = InMemoryToolAuditLog()
        val actions = runtime.actionLoop(
            registry,
            AuditedToolFabric(DenyByDefaultAuthorityGate(setOf(capability)), registry, audit)
        )
        val delegate = SovereignPlanCoordinator(
            runtime = runtime,
            inference = CognitiveInferencePort { Result.failure(IllegalStateException("unused")) },
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )
        val planner = PersistentSovereignPlanCoordinator(delegate, runtime.plans)
        val proposal = ActionProposal(
            requestId = ActionRequestId("new-r1"),
            capability = capability,
            reason = "New side effect",
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
                toolId = provider.descriptor.id,
                detail = "EXTERNAL action requires explicit approval"
            )
        )
        val newPlan = SovereignPlan(
            id = PlanId("new-plan"),
            conversationId = ConversationId("new-plan-thread"),
            goal = "new side effect",
            steps = listOf(pendingStep),
            planningBackendId = "planner"
        )

        val blocked = planner.approve(newPlan, 1)
        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull()?.message.orEmpty().contains("recovery-locked"))
        assertTrue(provider.inputs.isEmpty())
        assertEquals(listOf("old-r3"), SovereignRecoveryState(runtime.plans.receipts).unresolvedClaims().map { it.requestId.value })

        val oldExecutedStep = oldPending.copy(
            status = PlanStepStatus.EXECUTED,
            outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = oldPending.proposal(),
                toolId = ToolId("old-tool"),
                output = "verified"
            )
        )
        runtime.plans.receipts!!.recordTerminal(
            oldPlan.copy(steps = listOf(oldExecutedStep)),
            oldExecutedStep
        ).getOrThrow()

        val allowed = planner.approve(newPlan, 1).getOrThrow()
        assertEquals(ActionStatus.EXECUTED, allowed.outcome.status)
        assertEquals(listOf("apply"), provider.inputs)
        assertEquals(1, audit.snapshot().size)
    }
}
