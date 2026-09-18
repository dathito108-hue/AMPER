package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedApprovalBindingPersistenceTest {
    private val capability = CapabilityId("typed.binding.state")

    private fun provider(sideEffect: ToolSideEffect = ToolSideEffect.EXTERNAL): ToolProvider = object : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId("typed-binding-tool"),
            name = "Typed binding tool",
            capability = capability,
            sideEffect = sideEffect,
            inputContract = ToolInputContract("Apply typed binding", setOf("apply"), 32)
        )

        override fun execute(input: String): Result<String> = Result.success("done:$input")
    }

    private fun pendingPlan(
        sideEffect: ToolSideEffect?,
        detail: String,
        id: String = "typed-binding-plan"
    ): SovereignPlan {
        val proposal = ActionProposal(
            requestId = ActionRequestId("$id-request"),
            capability = capability,
            reason = "Apply a governed typed side effect",
            input = "apply"
        )
        return SovereignPlan(
            id = PlanId(id),
            conversationId = ConversationId("$id-thread"),
            goal = "persist typed approval binding",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = proposal.requestId,
                    capability = capability,
                    reason = proposal.reason,
                    input = proposal.input,
                    status = PlanStepStatus.REQUIRES_CONFIRMATION,
                    outcome = ActionOutcome(
                        status = ActionStatus.REQUIRES_CONFIRMATION,
                        proposal = proposal,
                        toolId = ToolId("typed-binding-tool"),
                        sideEffect = sideEffect,
                        detail = detail
                    ),
                    boundToolId = sideEffect?.let { ToolId("typed-binding-tool") },
                    boundSideEffect = sideEffect
                )
            ),
            planningBackendId = "typed-binding-test",
            createdAtEpochMs = 123L,
            planningModelId = ModelId("typed-planner"),
            planningSelectedCapabilities = setOf(TitanCapabilities.REASONING)
        )
    }

    private fun actions(): SovereignActionLoop {
        val registry = InMemoryToolRegistry().also { it.register(provider()) }
        return SovereignActionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = InMemoryToolAuditLog()
            ),
            memory = InMemoryMemoryOs(),
            workspace = InMemoryWorkspace()
        )
    }

    @Test
    fun v4RoundTripUsesTypedSideEffectAndPlanTimeBinding() {
        val plan = pendingPlan(
            sideEffect = ToolSideEffect.EXTERNAL,
            detail = "User-visible approval note with no legacy side-effect prefix"
        )
        val encoded = SovereignPlanCodec.encode(plan)
        assertTrue(encoded.startsWith("AMPER_PLAN_STATE_V4\n"))

        val restored = SovereignPlanCodec.decode(encoded).getOrThrow()
        assertEquals(ToolId("typed-binding-tool"), restored.steps.single().boundToolId)
        assertEquals(ToolSideEffect.EXTERNAL, restored.steps.single().boundSideEffect)
        assertTypedExternalBinding(restored, plan)
    }

    @Test
    fun v3SnapshotStillUsesTypedSideEffectAndRouteProvenanceWithoutInventingPlanBinding() {
        val plan = pendingPlan(
            sideEffect = ToolSideEffect.EXTERNAL,
            detail = "User-visible approval note with no legacy side-effect prefix",
            id = "legacy-v3-typed-plan"
        )
        val restored = SovereignPlanCodec.decode(encodeV3(plan)).getOrThrow()

        assertEquals(ModelId("typed-planner"), restored.planningModelId)
        assertEquals(setOf(TitanCapabilities.REASONING), restored.planningSelectedCapabilities)
        assertNull(restored.steps.single().boundToolId)
        assertNull(restored.steps.single().boundSideEffect)
        assertTypedExternalBinding(restored, plan)
    }

    @Test
    fun v2SnapshotStillUsesTypedSideEffectWithoutParsingDetail() {
        val plan = pendingPlan(
            sideEffect = ToolSideEffect.EXTERNAL,
            detail = "User-visible approval note with no legacy side-effect prefix",
            id = "legacy-v2-typed-plan"
        )
        val restored = SovereignPlanCodec.decode(encodeV2(plan)).getOrThrow()

        assertNull(restored.planningModelId)
        assertTrue(restored.planningSelectedCapabilities.isEmpty())
        assertNull(restored.steps.single().boundToolId)
        assertNull(restored.steps.single().boundSideEffect)
        assertTypedExternalBinding(restored, plan)
    }

    @Test
    fun v1SnapshotStillFallsBackToCanonicalLegacyDetail() {
        val plan = pendingPlan(
            sideEffect = null,
            detail = "EXTERNAL action requires explicit approval",
            id = "legacy-v1-plan"
        )
        val encodedV1 = encodeV1(plan)
        val restored = SovereignPlanCodec.decode(encodedV1).getOrThrow()
        assertNull(restored.steps.single().outcome?.sideEffect)
        assertNull(restored.steps.single().boundToolId)
        assertNull(restored.steps.single().boundSideEffect)

        val guard = SovereignPlanRecoveryGuard(actions(), setOf(capability))
        val binding = guard.approvalBinding(restored, 1).getOrThrow()
        assertEquals(ToolSideEffect.EXTERNAL, binding.sideEffect)

        val ledger = MemoryBackedSovereignPlanReceiptLedger(InMemoryMemoryOs())
        val claim = ledger.claimSideEffect(restored, restored.steps.single()).getOrThrow()
        assertEquals(ToolSideEffect.EXTERNAL, claim.sideEffect)
    }

    @Test
    fun typedAndLegacyBindingConflictFailsClosed() {
        val plan = pendingPlan(
            sideEffect = ToolSideEffect.EXTERNAL,
            detail = "LOCAL_STATE action requires explicit approval",
            id = "conflicting-binding-plan"
        )

        val guardFailure = SovereignPlanRecoveryGuard(actions(), setOf(capability))
            .approvalBinding(plan, 1)
        assertTrue(guardFailure.isFailure)
        assertTrue(guardFailure.exceptionOrNull()?.message.orEmpty().contains("conflicts"))

        val claimFailure = MemoryBackedSovereignPlanReceiptLedger(InMemoryMemoryOs())
            .claimSideEffect(plan, plan.steps.single())
        assertTrue(claimFailure.isFailure)
        assertTrue(claimFailure.exceptionOrNull()?.message.orEmpty().contains("conflicts"))
    }

    private fun assertTypedExternalBinding(restored: SovereignPlan, original: SovereignPlan) {
        val outcome = requireNotNull(restored.steps.single().outcome)
        assertEquals(ToolSideEffect.EXTERNAL, outcome.sideEffect)
        assertEquals(original.steps.single().outcome?.detail, outcome.detail)

        val guard = SovereignPlanRecoveryGuard(actions(), setOf(capability))
        val binding = guard.approvalBinding(restored, 1).getOrThrow()
        assertEquals(ToolId("typed-binding-tool"), binding.toolId)
        assertEquals(ToolSideEffect.EXTERNAL, binding.sideEffect)

        val ledger = MemoryBackedSovereignPlanReceiptLedger(InMemoryMemoryOs())
        val claim = ledger.claimSideEffect(restored, restored.steps.single()).getOrThrow()
        assertEquals(ToolSideEffect.EXTERNAL, claim.sideEffect)
    }

    private fun encodeV3(plan: SovereignPlan): String = buildString {
        appendLine("AMPER_PLAN_STATE_V3")
        appendLine("ID\t${enc(plan.id.value)}")
        appendLine("CONVERSATION\t${enc(plan.conversationId.value)}")
        appendLine("GOAL\t${enc(plan.goal)}")
        appendLine("BACKEND\t${enc(plan.planningBackendId)}")
        appendLine("CREATED\t${plan.createdAtEpochMs}")
        appendLine("MODEL\t${plan.planningModelId?.value?.let(::enc) ?: "~"}")
        appendLine(
            "CAPABILITIES\t${plan.planningSelectedCapabilities.sortedBy { it.value }.joinToString(",") { enc(it.value) }.ifBlank { "~" }}"
        )
        appendTypedSteps(plan)
    }.trimEnd()

    private fun encodeV2(plan: SovereignPlan): String = buildString {
        appendLine("AMPER_PLAN_STATE_V2")
        appendLine("ID\t${enc(plan.id.value)}")
        appendLine("CONVERSATION\t${enc(plan.conversationId.value)}")
        appendLine("GOAL\t${enc(plan.goal)}")
        appendLine("BACKEND\t${enc(plan.planningBackendId)}")
        appendLine("CREATED\t${plan.createdAtEpochMs}")
        appendTypedSteps(plan)
    }.trimEnd()

    private fun StringBuilder.appendTypedSteps(plan: SovereignPlan) {
        plan.steps.forEach { step ->
            val outcome = step.outcome
            appendLine(
                listOf(
                    "STEP", step.index.toString(), enc(step.requestId.value), enc(step.capability.value),
                    enc(step.reason), enc(step.input), step.status.name,
                    outcome?.status?.name ?: "~", outcome?.toolId?.value?.let(::enc) ?: "~",
                    outcome?.output?.let(::enc) ?: "~", outcome?.detail?.let(::enc) ?: "~",
                    outcome?.sideEffect?.name ?: "~"
                ).joinToString("\t")
            )
        }
    }

    private fun encodeV1(plan: SovereignPlan): String = buildString {
        appendLine("AMPER_PLAN_STATE_V1")
        appendLine("ID\t${enc(plan.id.value)}")
        appendLine("CONVERSATION\t${enc(plan.conversationId.value)}")
        appendLine("GOAL\t${enc(plan.goal)}")
        appendLine("BACKEND\t${enc(plan.planningBackendId)}")
        appendLine("CREATED\t${plan.createdAtEpochMs}")
        plan.steps.forEach { step ->
            val outcome = step.outcome
            appendLine(
                listOf(
                    "STEP", step.index.toString(), enc(step.requestId.value), enc(step.capability.value),
                    enc(step.reason), enc(step.input), step.status.name,
                    outcome?.status?.name ?: "~", outcome?.toolId?.value?.let(::enc) ?: "~",
                    outcome?.output?.let(::enc) ?: "~", outcome?.detail?.let(::enc) ?: "~"
                ).joinToString("\t")
            )
        }
    }.trimEnd()

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
