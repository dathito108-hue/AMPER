package io.amper.neuroos.core

import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanClaimReconciliationTest {
    private data class Fixture(
        val runtime: AmperRuntime,
        val coordinator: PersistentSovereignPlanCoordinator,
        val audit: InMemoryToolAuditLog,
        val provider: ToolProvider,
        val plan: SovereignPlan,
        val pending: SovereignPlanStep
    )

    private fun fixture(): Fixture {
        val capability = CapabilityId("test.reconcile.state")
        val runtime = AmperRuntime.reference()
        val provider = object : ToolProvider {
            override val descriptor = ToolDescriptor(
                id = ToolId("reconcile-state-tool"),
                name = "Reconcile state tool",
                capability = capability,
                sideEffect = ToolSideEffect.LOCAL_STATE,
                inputContract = ToolInputContract("Apply local state", setOf("apply"), 16)
            )
            override fun execute(input: String): Result<String> = Result.success("applied")
        }
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            DenyByDefaultAuthorityGate(setOf(capability)),
            registry,
            audit
        )
        val actions = runtime.actionLoop(registry, fabric)
        val delegate = SovereignPlanCoordinator(
            runtime = runtime,
            inference = CognitiveInferencePort { Result.failure(IllegalStateException("unused")) },
            actions = actions,
            advertisedCapabilities = setOf(capability)
        )
        val coordinator = PersistentSovereignPlanCoordinator(
            delegate = delegate,
            store = runtime.plans,
            recoveryGuard = SovereignPlanRecoveryGuard(actions, setOf(capability))
        )
        val proposal = ActionProposal(
            requestId = ActionRequestId("reconcile-r1"),
            capability = capability,
            reason = "Apply explicit local state",
            input = "apply"
        )
        val pending = SovereignPlanStep(
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
                detail = "LOCAL_STATE action requires explicit approval"
            )
        )
        val plan = SovereignPlan(
            id = PlanId("reconcile-plan"),
            conversationId = ConversationId("reconcile-thread"),
            goal = "reconcile interrupted side effect",
            steps = listOf(pending),
            planningBackendId = "planner"
        )
        runtime.plans.save(plan)
        runtime.plans.receipts!!.claimSideEffect(plan, pending).getOrThrow()
        return Fixture(runtime, coordinator, audit, provider, plan, pending)
    }

    @Test
    fun confirmedExecutedCreatesReceiptWithoutProviderReplay() {
        val f = fixture()

        val processed = f.coordinator.reconcile(
            f.plan,
            1,
            PlanClaimDecision.CONFIRMED_EXECUTED,
            "Verified local state is already applied"
        ).getOrThrow()

        assertEquals(PlanStepStatus.EXECUTED, processed.step.status)
        assertEquals(ActionStatus.EXECUTED, processed.outcome.status)
        assertEquals(f.provider.descriptor.id, processed.outcome.toolId)
        assertEquals(0, f.audit.snapshot().size)
        assertNotNull(f.runtime.plans.receipts!!.receipt(f.plan.id, f.pending.requestId))
        assertNotNull(f.coordinator.reconciliation(f.plan.id, f.pending.requestId))
        assertTrue(f.coordinator.pendingClaims().isEmpty())
    }

    @Test
    fun confirmedNotExecutedMarksStepFailedWithoutReplay() {
        val f = fixture()

        val processed = f.coordinator.reconcile(
            f.plan,
            1,
            PlanClaimDecision.CONFIRMED_NOT_EXECUTED,
            "Verified target state was not changed"
        ).getOrThrow()

        assertEquals(PlanStepStatus.FAILED, processed.step.status)
        assertEquals(ActionStatus.FAILED, processed.outcome.status)
        assertEquals(0, f.audit.snapshot().size)
    }

    @Test
    fun immutableReconciliationRejectsChangedDecision() {
        val f = fixture()
        val ledger = f.runtime.plans.receipts!!
        ledger.reconcileClaim(
            f.plan,
            f.pending,
            PlanClaimDecision.CONFIRMED_EXECUTED,
            "Verified once"
        ).getOrThrow()

        val changed = ledger.reconcileClaim(
            f.plan,
            f.pending,
            PlanClaimDecision.CONFIRMED_NOT_EXECUTED,
            "Changed decision"
        )

        assertTrue(changed.isFailure)
        assertTrue(changed.exceptionOrNull()?.message.orEmpty().contains("immutable reconciliation"))
        assertEquals(0, f.audit.snapshot().size)
    }

    @Test
    fun sameReconciliationCanFinalizeAfterReceiptBeforePlanSaveCrash() {
        val f = fixture()
        val ledger = f.runtime.plans.receipts!!
        val note = "Verified local state is already applied"
        val resolution = ledger.reconcileClaim(
            f.plan,
            f.pending,
            PlanClaimDecision.CONFIRMED_EXECUTED,
            note
        ).getOrThrow()
        val outcome = ActionOutcome(
            status = ActionStatus.EXECUTED,
            proposal = f.pending.proposal(),
            toolId = resolution.toolId,
            detail = "Manual reconciliation confirmed claimed side effect executed; provider was not replayed"
        )
        val terminalStep = f.pending.copy(status = PlanStepStatus.EXECUTED, outcome = outcome)
        val terminalPlan = f.plan.copy(steps = listOf(terminalStep))
        ledger.recordTerminal(terminalPlan, terminalStep).getOrThrow()

        val recovered = f.coordinator.reconcile(
            f.plan,
            1,
            PlanClaimDecision.CONFIRMED_EXECUTED,
            note
        ).getOrThrow()

        assertEquals(PlanStepStatus.EXECUTED, recovered.step.status)
        assertEquals(0, f.audit.snapshot().size)
        assertEquals(PlanStepStatus.EXECUTED, f.runtime.plans.load(f.plan.id)!!.steps.single().status)
    }

    @Test
    fun reconciliationNoteRemainsEncryptedAtRest() {
        val root = Files.createTempDirectory("amper-reconciliation-encrypted").toFile()
        val key = ByteArray(32) { (it + 31).toByte() }
        val cipher = AesGcmMemoryLineCipher(SecretKeySpec(key, "AES"), "phase21-reconciliation")
        val runtime = AmperRuntime.persistentEncrypted(root, cipher = cipher)
        val capability = CapabilityId("test.reconcile.encrypted")
        val proposal = ActionProposal(
            requestId = ActionRequestId("encrypted-reconcile-r1"),
            capability = capability,
            reason = "encrypted reconcile reason",
            input = "apply"
        )
        val pending = SovereignPlanStep(
            index = 1,
            requestId = proposal.requestId,
            capability = capability,
            reason = proposal.reason,
            input = proposal.input,
            status = PlanStepStatus.REQUIRES_CONFIRMATION,
            outcome = ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = ToolId("encrypted-reconcile-tool"),
                detail = "EXTERNAL action requires explicit approval"
            )
        )
        val plan = SovereignPlan(
            id = PlanId("encrypted-reconcile-plan"),
            conversationId = ConversationId("encrypted-reconcile-thread"),
            goal = "encrypted reconcile goal",
            steps = listOf(pending),
            planningBackendId = "planner"
        )
        val note = "private manual verification note"
        val ledger = runtime.plans.receipts!!
        ledger.claimSideEffect(plan, pending).getOrThrow()
        ledger.reconcileClaim(plan, pending, PlanClaimDecision.CONFIRMED_EXECUTED, note).getOrThrow()

        val stored = root.resolve("amper-sovereign/memory.journal").readText()
        assertTrue(stored.lineSequence().filter { it.isNotBlank() }.all { it.startsWith("E1|") })
        assertFalse(stored.contains(note))
        assertFalse(stored.contains("encrypted-reconcile-tool"))
    }
}
