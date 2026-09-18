package io.amper.neuroos.core

import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanExecutionReceiptsTest {
    private fun readProvider(capability: CapabilityId): ToolProvider = object : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId("receipt-read-tool"),
            name = "Receipt read tool",
            capability = capability,
            sideEffect = ToolSideEffect.READ_ONLY,
            inputContract = ToolInputContract("Read status", setOf("status"), 16)
        )
        override fun execute(input: String): Result<String> = Result.success("status=ok")
    }

    private fun planner(
        runtime: AmperRuntime,
        registry: InMemoryToolRegistry,
        audit: InMemoryToolAuditLog,
        capability: CapabilityId
    ): PersistentSovereignPlanCoordinator {
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
        return PersistentSovereignPlanCoordinator(
            delegate = delegate,
            store = runtime.plans,
            recoveryGuard = SovereignPlanRecoveryGuard(actions, setOf(capability))
        )
    }

    @Test
    fun completedStepReceiptBlocksTamperedPlanFromAdvancing() {
        val capability = CapabilityId("test.receipt.read")
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also { it.register(readProvider(capability)) }
        val audit = InMemoryToolAuditLog()
        val coordinator = planner(runtime, registry, audit, capability)
        val plan = SovereignPlan(
            id = PlanId("receipt-plan"),
            conversationId = ConversationId("receipt-thread"),
            goal = "verify execution receipts",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId("receipt-r1"),
                    capability = capability,
                    reason = "Read first status",
                    input = "status",
                    boundToolId = ToolId("receipt-read-tool"),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                ),
                SovereignPlanStep(
                    index = 2,
                    requestId = ActionRequestId("receipt-r2"),
                    capability = capability,
                    reason = "Read second status",
                    input = "status",
                    boundToolId = ToolId("receipt-read-tool"),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "planner"
        )

        val first = coordinator.advance(plan).getOrThrow() as PlanAdvanceResult.StepProcessed
        assertEquals(1, audit.snapshot().size)
        assertNotNull(runtime.plans.receipts?.receipt(plan.id, ActionRequestId("receipt-r1")))

        val tampered = first.plan.copy(
            steps = first.plan.steps.map {
                if (it.index == 1) it.copy(reason = "Tampered reason after execution") else it
            }
        )
        val second = coordinator.advance(tampered)

        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message.orEmpty().contains("receipt mismatch"))
        assertEquals(1, audit.snapshot().size)
    }

    @Test
    fun persistedSideEffectClaimPreventsAutomaticApprovalReplay() {
        val capability = CapabilityId("test.receipt.state")
        val runtime = AmperRuntime.reference()
        val provider = object : ToolProvider {
            override val descriptor = ToolDescriptor(
                id = ToolId("receipt-state-tool"),
                name = "Receipt state tool",
                capability = capability,
                sideEffect = ToolSideEffect.LOCAL_STATE,
                inputContract = ToolInputContract("Apply local state", setOf("apply"), 16)
            )
            override fun execute(input: String): Result<String> = Result.success("applied")
        }
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val audit = InMemoryToolAuditLog()
        val coordinator = planner(runtime, registry, audit, capability)
        val proposal = ActionProposal(
            requestId = ActionRequestId("side-effect-r1"),
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
            id = PlanId("side-effect-plan"),
            conversationId = ConversationId("side-effect-thread"),
            goal = "test interrupted approval",
            steps = listOf(pending),
            planningBackendId = "planner"
        )

        runtime.plans.receipts!!.claimSideEffect(plan, pending).getOrThrow()
        val replay = coordinator.approve(plan, 1)

        assertTrue(replay.isFailure)
        assertTrue(replay.exceptionOrNull()?.message.orEmpty().contains("recovery-locked"))
        assertEquals(0, audit.snapshot().size)
    }

    @Test
    fun receiptAndClaimRemainEncryptedAtRest() {
        val root = Files.createTempDirectory("amper-receipt-encrypted").toFile()
        val key = ByteArray(32) { (it + 73).toByte() }
        val cipher = AesGcmMemoryLineCipher(SecretKeySpec(key, "AES"), "phase20-receipt")
        val runtime = AmperRuntime.persistentEncrypted(root, cipher = cipher)
        val capability = CapabilityId("test.encrypted.state")
        val proposal = ActionProposal(
            requestId = ActionRequestId("encrypted-claim"),
            capability = capability,
            reason = "secret receipt reason",
            input = "secret-input"
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
                toolId = ToolId("encrypted-tool"),
                detail = "EXTERNAL action requires explicit approval"
            )
        )
        val plan = SovereignPlan(
            id = PlanId("encrypted-receipt-plan"),
            conversationId = ConversationId("encrypted-thread"),
            goal = "encrypted receipt goal",
            steps = listOf(pending),
            planningBackendId = "planner"
        )

        runtime.plans.receipts!!.claimSideEffect(plan, pending).getOrThrow()
        val rejected = plan.copy(steps = listOf(pending.copy(status = PlanStepStatus.REJECTED)))
        runtime.plans.receipts!!.recordTerminal(rejected, rejected.steps.single()).getOrThrow()

        val stored = root.resolve("amper-sovereign/memory.journal").readText()
        assertTrue(stored.lineSequence().filter { it.isNotBlank() }.all { it.startsWith("E1|") })
        assertFalse(stored.contains("secret receipt reason"))
        assertFalse(stored.contains("secret-input"))
        assertFalse(stored.contains("encrypted-tool"))
    }
}
