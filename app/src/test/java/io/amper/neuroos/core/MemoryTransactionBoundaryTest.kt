package io.amper.neuroos.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTransactionBoundaryTest {
    private data class PendingFixture(
        val plan: SovereignPlan,
        val step: SovereignPlanStep
    )

    private fun fixture(
        planId: String,
        requestId: String,
        capabilityValue: String = "test.memory.tx"
    ): PendingFixture {
        val capability = CapabilityId(capabilityValue)
        val proposal = ActionProposal(
            requestId = ActionRequestId(requestId),
            capability = capability,
            reason = "Apply one transaction-bound side effect",
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
                toolId = ToolId("memory-tx-tool"),
                sideEffect = ToolSideEffect.LOCAL_STATE,
                detail = "LOCAL_STATE action requires explicit approval"
            )
        )
        return PendingFixture(
            plan = SovereignPlan(
                id = PlanId(planId),
                conversationId = ConversationId("memory-tx-thread"),
                goal = "memory transaction boundary test",
                steps = listOf(step),
                planningBackendId = "memory-tx-test"
            ),
            step = step
        )
    }

    @Test
    fun separateLedgersSharingOneMemoryOsAllowOneClaimWinner() {
        val memory = PersistentMemoryOs(InMemoryMemoryJournal())
        val ledgers = List(12) { MemoryBackedSovereignPlanReceiptLedger(memory) }
        val f = fixture("shared-memory-claim-plan", "shared-memory-claim-r1")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(ledgers.size)
        try {
            val futures = ledgers.map { ledger ->
                pool.submit<Result<PlanSideEffectClaim>> {
                    start.await()
                    ledger.claimSideEffect(f.plan, f.step)
                }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(ledgers.size - 1, results.count { it.isFailure })
            assertNotNull(ledgers.first().claim(f.plan.id, f.step.requestId))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun reconciliationAndConflictingTerminalReceiptCannotBothCommitAcrossLedgers() {
        val memory = PersistentMemoryOs(InMemoryMemoryJournal())
        val reconciliationLedger = MemoryBackedSovereignPlanReceiptLedger(memory)
        val terminalLedger = MemoryBackedSovereignPlanReceiptLedger(memory)
        val f = fixture("shared-memory-cross-record-plan", "shared-memory-cross-record-r1")
        reconciliationLedger.claimSideEffect(f.plan, f.step).getOrThrow()

        val executedStep = f.step.copy(
            status = PlanStepStatus.EXECUTED,
            outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = f.step.proposal(),
                toolId = ToolId("memory-tx-tool"),
                sideEffect = ToolSideEffect.LOCAL_STATE,
                output = "applied"
            )
        )
        val executedPlan = f.plan.copy(steps = listOf(executedStep))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reconcile = pool.submit<Result<*>> {
                start.await()
                reconciliationLedger.reconcileClaim(
                    f.plan,
                    f.step,
                    PlanClaimDecision.CONFIRMED_NOT_EXECUTED,
                    "Verified that the side effect did not execute"
                )
            }
            val terminal = pool.submit<Result<*>> {
                start.await()
                terminalLedger.recordTerminal(executedPlan, executedStep)
            }
            start.countDown()
            val results = listOf(
                reconcile.get(10, TimeUnit.SECONDS),
                terminal.get(10, TimeUnit.SECONDS)
            )

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })

            val resolution = reconciliationLedger.reconciliation(f.plan.id, f.step.requestId)
            val receipt = terminalLedger.receipt(f.plan.id, f.step.requestId)
            if (resolution != null) {
                assertEquals(PlanClaimDecision.CONFIRMED_NOT_EXECUTED, resolution.decision)
                assertNull(receipt)
                assertTrue(results.filter { it.isFailure }.single().exceptionOrNull()?.message.orEmpty()
                    .contains("conflicts with not-executed reconciliation"))
            } else {
                assertNotNull(receipt)
                assertEquals(ActionStatus.EXECUTED, receipt!!.actionStatus)
                assertTrue(results.filter { it.isFailure }.single().exceptionOrNull()?.message.orEmpty()
                    .contains("terminal receipt already exists"))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun reconciliationAllowsOnlyItsMatchingTerminalOutcome() {
        val memory = PersistentMemoryOs(InMemoryMemoryJournal())
        val ledgerA = MemoryBackedSovereignPlanReceiptLedger(memory)
        val ledgerB = MemoryBackedSovereignPlanReceiptLedger(memory)
        val f = fixture("shared-memory-compatible-plan", "shared-memory-compatible-r1")
        ledgerA.claimSideEffect(f.plan, f.step).getOrThrow()
        ledgerA.reconcileClaim(
            f.plan,
            f.step,
            PlanClaimDecision.CONFIRMED_NOT_EXECUTED,
            "Verified no external state change"
        ).getOrThrow()

        val failedStep = f.step.copy(
            status = PlanStepStatus.FAILED,
            outcome = ActionOutcome(
                status = ActionStatus.FAILED,
                proposal = f.step.proposal(),
                toolId = ToolId("memory-tx-tool"),
                sideEffect = ToolSideEffect.LOCAL_STATE,
                detail = "Manual reconciliation confirmed claimed side effect did not execute; provider was not replayed"
            )
        )
        val failedPlan = f.plan.copy(steps = listOf(failedStep))

        val receipt = ledgerB.recordTerminal(failedPlan, failedStep).getOrThrow()

        assertEquals(ActionStatus.FAILED, receipt.actionStatus)
        assertEquals(PlanStepStatus.FAILED, receipt.stepStatus)
    }
}
