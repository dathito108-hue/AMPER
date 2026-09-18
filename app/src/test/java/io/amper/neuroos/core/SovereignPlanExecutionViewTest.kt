package io.amper.neuroos.core

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignPlanExecutionViewTest {
    @Test
    fun plannedV4BindingAndRouteProvenanceAreProjectedExactly() {
        val runtime = AmperRuntime.reference()
        val capability = CapabilityId("test.plan.read")
        val plan = SovereignPlan(
            id = PlanId("phase128-bound-plan"),
            conversationId = ConversationId("phase128-thread"),
            goal = "inspect one governed read",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId("request-1234567890-abcdef"),
                    capability = capability,
                    reason = "Read exact governed state",
                    input = "status",
                    boundToolId = ToolId("phase128-read-tool"),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "planner-backend-phase128",
            planningModelId = ModelId("planner-model-phase128"),
            planningSelectedCapabilities = linkedSetOf(
                TitanCapabilities.REASONING,
                TitanCapabilities.PLANNING
            )
        )

        val view = SovereignPlanExecutionInspector(runtime.plans.receipts).inspect(plan)
        val step = view.steps.single()

        assertEquals("planner-backend-phase128", view.planningBackendId)
        assertEquals(ModelId("planner-model-phase128"), view.planningModelId)
        assertEquals(
            linkedSetOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING),
            view.planningSelectedCapabilities
        )
        assertTrue(view.receiptLedgerAvailable)
        assertFalse(view.recoveryRequired)
        assertEquals(PlanStepStatus.PLANNED, step.status)
        assertEquals(PlanToolBindingState.BOUND, step.bindingState)
        assertEquals(ToolId("phase128-read-tool"), step.boundToolId)
        assertEquals(ToolSideEffect.READ_ONLY, step.boundSideEffect)
        assertEquals("phase128-read-tool / READ_ONLY", step.bindingLabel)
        assertEquals(ActionRequestId("request-1234567890-abcdef"), step.requestId)
        assertEquals("request-…cdef", step.shortRequestId)
        assertEquals(PlanDurabilityEvidence.NONE, step.durabilityEvidence)
        assertEquals("no durable evidence", step.durabilityLabel)
    }

    @Test
    fun pendingSideEffectSeparatesNoClaimFromUnresolvedClaim() {
        val runtime = AmperRuntime.reference()
        val ledger = requireNotNull(runtime.plans.receipts)
        val capability = CapabilityId("test.plan.local")
        val proposal = ActionProposal(
            requestId = ActionRequestId("phase128-pending-request"),
            capability = capability,
            reason = "Apply exact local state",
            input = "apply"
        )
        val pending = SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = proposal.capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.REQUIRES_CONFIRMATION,
            outcome = ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = ToolId("phase128-local-tool"),
                sideEffect = ToolSideEffect.LOCAL_STATE,
                detail = "LOCAL_STATE action requires explicit approval"
            ),
            boundToolId = ToolId("phase128-local-tool"),
            boundSideEffect = ToolSideEffect.LOCAL_STATE
        )
        val plan = SovereignPlan(
            id = PlanId("phase128-pending-plan"),
            conversationId = ConversationId("phase128-pending-thread"),
            goal = "inspect pending durable state",
            steps = listOf(pending),
            planningBackendId = "planner"
        )
        val inspector = SovereignPlanExecutionInspector(ledger)

        val beforeClaim = inspector.inspect(plan).steps.single()
        assertEquals(PlanDurabilityEvidence.NONE, beforeClaim.durabilityEvidence)
        assertNull(beforeClaim.claimToolId)
        assertNull(beforeClaim.claimSideEffect)

        ledger.claimSideEffect(plan, pending).getOrThrow()
        val afterClaimView = inspector.inspect(plan)
        val afterClaim = afterClaimView.steps.single()

        assertTrue(afterClaimView.recoveryRequired)
        assertEquals(PlanDurabilityEvidence.CLAIMED_UNRESOLVED, afterClaim.durabilityEvidence)
        assertEquals("durable side-effect claim unresolved", afterClaim.durabilityLabel)
        assertEquals(ToolId("phase128-local-tool"), afterClaim.claimToolId)
        assertEquals(ToolSideEffect.LOCAL_STATE, afterClaim.claimSideEffect)
    }

    @Test
    fun terminalReceiptIsProjectedWithoutInvokingProvider() {
        val runtime = AmperRuntime.reference()
        val ledger = requireNotNull(runtime.plans.receipts)
        val capability = CapabilityId("test.plan.receipt")
        val proposal = ActionProposal(
            requestId = ActionRequestId("phase128-receipt-request"),
            capability = capability,
            reason = "Read one receipt state",
            input = "status"
        )
        val terminal = SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = proposal.capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.EXECUTED,
            outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = proposal,
                toolId = ToolId("phase128-receipt-tool"),
                sideEffect = ToolSideEffect.READ_ONLY,
                output = "ok"
            ),
            boundToolId = ToolId("phase128-receipt-tool"),
            boundSideEffect = ToolSideEffect.READ_ONLY
        )
        val plan = SovereignPlan(
            id = PlanId("phase128-receipt-plan"),
            conversationId = ConversationId("phase128-receipt-thread"),
            goal = "inspect terminal receipt",
            steps = listOf(terminal),
            planningBackendId = "planner"
        )

        ledger.recordTerminal(plan, terminal).getOrThrow()
        val step = SovereignPlanExecutionInspector(ledger).inspect(plan).steps.single()

        assertEquals(PlanDurabilityEvidence.RECEIPTED, step.durabilityEvidence)
        assertEquals("terminal receipt exists", step.durabilityLabel)
        assertNotNull(step.receiptSha256)
        assertNull(step.reconciliationDecision)
    }

    @Test
    fun manualReconciliationTakesPresentationPrecedenceOverReceipt() {
        val runtime = AmperRuntime.reference()
        val ledger = requireNotNull(runtime.plans.receipts)
        val capability = CapabilityId("test.plan.reconcile")
        val proposal = ActionProposal(
            requestId = ActionRequestId("phase128-reconcile-request"),
            capability = capability,
            reason = "Apply one external effect",
            input = "apply"
        )
        val pending = SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = proposal.capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.REQUIRES_CONFIRMATION,
            outcome = ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = ToolId("phase128-external-tool"),
                sideEffect = ToolSideEffect.EXTERNAL,
                detail = "EXTERNAL action requires explicit approval"
            ),
            boundToolId = ToolId("phase128-external-tool"),
            boundSideEffect = ToolSideEffect.EXTERNAL
        )
        val pendingPlan = SovereignPlan(
            id = PlanId("phase128-reconcile-plan"),
            conversationId = ConversationId("phase128-reconcile-thread"),
            goal = "inspect manual reconciliation",
            steps = listOf(pending),
            planningBackendId = "planner"
        )

        ledger.claimSideEffect(pendingPlan, pending).getOrThrow()
        ledger.reconcileClaim(
            pendingPlan,
            pending,
            PlanClaimDecision.CONFIRMED_NOT_EXECUTED,
            "Operator verified no external effect was observed"
        ).getOrThrow()

        val resolved = pending.copy(
            status = PlanStepStatus.FAILED,
            outcome = ActionOutcome(
                status = ActionStatus.FAILED,
                proposal = proposal,
                toolId = ToolId("phase128-external-tool"),
                sideEffect = ToolSideEffect.EXTERNAL,
                detail = "Manual reconciliation confirmed no execution"
            )
        )
        val resolvedPlan = pendingPlan.copy(steps = listOf(resolved))
        ledger.recordTerminal(resolvedPlan, resolved).getOrThrow()

        val step = SovereignPlanExecutionInspector(ledger).inspect(resolvedPlan).steps.single()

        assertEquals(PlanDurabilityEvidence.RECONCILED, step.durabilityEvidence)
        assertEquals("manual reconciliation exists", step.durabilityLabel)
        assertEquals(PlanClaimDecision.CONFIRMED_NOT_EXECUTED, step.reconciliationDecision)
        assertNotNull(step.receiptSha256)
    }

    @Test
    fun legacyV3StepIsExplicitlyUnboundAndNeverInventsProviderIdentity() {
        val runtime = AmperRuntime.reference()
        val content = listOf(
            "AMPER_PLAN_STATE_V3",
            "ID\t${enc("phase128-legacy-plan")}",
            "CONVERSATION\t${enc("phase128-legacy-thread")}",
            "GOAL\t${enc("inspect legacy binding")}",
            "BACKEND\t${enc("legacy-planner-backend")}",
            "CREATED\t128",
            "MODEL\t${enc("legacy-planner-model")}",
            "CAPABILITIES\t${enc(TitanCapabilities.REASONING.value)}",
            listOf(
                "STEP",
                "1",
                enc("phase128-legacy-request"),
                enc("test.legacy.read"),
                enc("Read legacy value"),
                enc("status"),
                "PLANNED",
                "~",
                "~",
                "~",
                "~",
                "~"
            ).joinToString("\t")
        ).joinToString("\n")
        val legacy = SovereignPlanCodec.decode(content).getOrThrow()

        val view = SovereignPlanExecutionInspector(runtime.plans.receipts).inspect(legacy)
        val step = view.steps.single()

        assertEquals("legacy-planner-backend", view.planningBackendId)
        assertEquals(ModelId("legacy-planner-model"), view.planningModelId)
        assertEquals(setOf(TitanCapabilities.REASONING), view.planningSelectedCapabilities)
        assertEquals(PlanToolBindingState.UNBOUND_LEGACY, step.bindingState)
        assertEquals("unbound/legacy", step.bindingLabel)
        assertNull(step.boundToolId)
        assertNull(step.boundSideEffect)
        assertEquals(PlanDurabilityEvidence.NONE, step.durabilityEvidence)
    }

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))
}
