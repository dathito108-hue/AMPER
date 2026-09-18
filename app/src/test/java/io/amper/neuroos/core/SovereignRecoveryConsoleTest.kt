package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignRecoveryConsoleTest {
    private val capability = CapabilityId("test.recovery.state")

    private fun provider(): ToolProvider = object : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId("recovery-state-tool"),
            name = "Recovery state tool",
            capability = capability,
            sideEffect = ToolSideEffect.LOCAL_STATE,
            inputContract = ToolInputContract("Apply local state", setOf("apply"), 16)
        )

        override fun execute(input: String): Result<String> = Result.success("applied")
    }

    private fun pendingPlan(provider: ToolProvider): SovereignPlan {
        val proposal = ActionProposal(
            requestId = ActionRequestId("recovery-request-1"),
            capability = capability,
            reason = "Apply state once",
            input = "apply"
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
                toolId = provider.descriptor.id,
                detail = "LOCAL_STATE action requires explicit approval"
            )
        )
        return SovereignPlan(
            id = PlanId("recovery-plan-1"),
            conversationId = ConversationId("recovery-thread"),
            goal = "recover interrupted state change",
            steps = listOf(step),
            planningBackendId = "planner"
        )
    }

    private data class Fixture(
        val runtime: AmperRuntime,
        val audit: InMemoryToolAuditLog,
        val coordinator: PersistentSovereignPlanCoordinator,
        val provider: ToolProvider
    )

    private fun fixture(): Fixture {
        val runtime = AmperRuntime.reference()
        val provider = provider()
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
        return Fixture(
            runtime = runtime,
            audit = audit,
            coordinator = PersistentSovereignPlanCoordinator(delegate, runtime.plans),
            provider = provider
        )
    }

    @Test
    fun validClaimCanBeResolvedWithoutProviderReplay() {
        val fx = fixture()
        val plan = pendingPlan(fx.provider)
        fx.runtime.plans.save(plan)
        fx.runtime.plans.receipts!!.claimSideEffect(plan, plan.steps.single()).getOrThrow()
        val console = SovereignRecoveryConsole(fx.runtime.plans, fx.coordinator)

        val item = console.pending().getOrThrow().single()
        val processed = console.reconcile(
            item,
            PlanClaimDecision.CONFIRMED_NOT_EXECUTED,
            "Verified device state remained unchanged"
        ).getOrThrow()

        assertEquals(PlanStepStatus.FAILED, processed.step.status)
        assertEquals(0, fx.audit.snapshot().size)
        assertTrue(console.pending().getOrThrow().isEmpty())
    }

    @Test
    fun orphanClaimFailsClosedInsteadOfBeingHidden() {
        val fx = fixture()
        val plan = pendingPlan(fx.provider)
        fx.runtime.plans.receipts!!.claimSideEffect(plan, plan.steps.single()).getOrThrow()
        val console = SovereignRecoveryConsole(fx.runtime.plans, fx.coordinator)

        val pending = console.pending()

        assertTrue(pending.isFailure)
        assertTrue(pending.exceptionOrNull()?.message.orEmpty().contains("no persisted plan"))
        assertEquals(0, fx.audit.snapshot().size)
    }
}
