package io.amper.neuroos.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicReceiptLedgerTest {
    private data class PendingFixture(
        val plan: SovereignPlan,
        val step: SovereignPlanStep
    )

    private fun pendingFixture(
        planId: String,
        requestId: String,
        capabilityValue: String = "test.atomic.state"
    ): PendingFixture {
        val capability = CapabilityId(capabilityValue)
        val proposal = ActionProposal(
            requestId = ActionRequestId(requestId),
            capability = capability,
            reason = "Apply one atomic side effect",
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
                toolId = ToolId("atomic-tool"),
                sideEffect = ToolSideEffect.LOCAL_STATE,
                detail = "LOCAL_STATE action requires explicit approval"
            )
        )
        return PendingFixture(
            plan = SovereignPlan(
                id = PlanId(planId),
                conversationId = ConversationId("atomic-thread"),
                goal = "atomic mutation test",
                steps = listOf(step),
                planningBackendId = "atomic-test"
            ),
            step = step
        )
    }

    @Test
    fun concurrentClaimAllowsExactlyOneWinner() {
        val ledger = MemoryBackedSovereignPlanReceiptLedger(InMemoryMemoryOs())
        val fixture = pendingFixture("atomic-claim-plan", "atomic-claim-r1")
        val workers = 16
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = (1..workers).map {
                pool.submit<Result<PlanSideEffectClaim>> {
                    start.await()
                    ledger.claimSideEffect(fixture.plan, fixture.step)
                }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(workers - 1, results.count { it.isFailure })
            assertNotNull(ledger.claim(fixture.plan.id, fixture.step.requestId))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun conflictingTerminalReceiptsCannotBothCommit() {
        val ledger = MemoryBackedSovereignPlanReceiptLedger(InMemoryMemoryOs())
        val fixture = pendingFixture("atomic-receipt-plan", "atomic-receipt-r1")
        ledger.claimSideEffect(fixture.plan, fixture.step).getOrThrow()

        val executedStep = fixture.step.copy(
            status = PlanStepStatus.EXECUTED,
            outcome = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = fixture.step.proposal(),
                toolId = ToolId("atomic-tool"),
                sideEffect = ToolSideEffect.LOCAL_STATE,
                output = "applied"
            )
        )
        val failedStep = fixture.step.copy(
            status = PlanStepStatus.FAILED,
            outcome = ActionOutcome(
                status = ActionStatus.FAILED,
                proposal = fixture.step.proposal(),
                toolId = ToolId("atomic-tool"),
                sideEffect = ToolSideEffect.LOCAL_STATE,
                detail = "provider failed"
            )
        )
        val executedPlan = fixture.plan.copy(steps = listOf(executedStep))
        val failedPlan = fixture.plan.copy(steps = listOf(failedStep))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Result<PlanExecutionReceipt>> {
                start.await()
                ledger.recordTerminal(executedPlan, executedStep)
            }
            val b = pool.submit<Result<PlanExecutionReceipt>> {
                start.await()
                ledger.recordTerminal(failedPlan, failedStep)
            }
            start.countDown()
            val results = listOf(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })
            assertNotNull(ledger.receipt(fixture.plan.id, fixture.step.requestId))
            assertTrue(results.filter { it.isFailure }.single().exceptionOrNull()?.message.orEmpty()
                .contains("immutable execution receipt mismatch"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun conflictingReconciliationsCannotBothCommit() {
        val ledger = MemoryBackedSovereignPlanReceiptLedger(InMemoryMemoryOs())
        val fixture = pendingFixture("atomic-reconcile-plan", "atomic-reconcile-r1")
        ledger.claimSideEffect(fixture.plan, fixture.step).getOrThrow()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Result<PlanClaimReconciliation>> {
                start.await()
                ledger.reconcileClaim(
                    fixture.plan,
                    fixture.step,
                    PlanClaimDecision.CONFIRMED_EXECUTED,
                    "Observed the state change"
                )
            }
            val b = pool.submit<Result<PlanClaimReconciliation>> {
                start.await()
                ledger.reconcileClaim(
                    fixture.plan,
                    fixture.step,
                    PlanClaimDecision.CONFIRMED_NOT_EXECUTED,
                    "Observed no state change"
                )
            }
            start.countDown()
            val results = listOf(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })
            assertNotNull(ledger.reconciliation(fixture.plan.id, fixture.step.requestId))
            assertTrue(results.filter { it.isFailure }.single().exceptionOrNull()?.message.orEmpty()
                .contains("immutable reconciliation already exists"))
        } finally {
            pool.shutdownNow()
        }
    }
}
