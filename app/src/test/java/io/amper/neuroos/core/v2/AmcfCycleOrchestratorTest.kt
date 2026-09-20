package io.amper.neuroos.core.v2

import io.amper.neuroos.core.InferenceCancellationSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmcfCycleOrchestratorTest {
    private val foundation = AmcfFoundationBinding(
        foundationId = "amper-foundation",
        semanticSha256 = "1".repeat(64)
    )

    @Test
    fun earlyExitSkipsOnlyOptionalDeliberationAndStillVerifiesRevisesFinalizes() {
        val plan = reasonPlan()
        val committed = mutableListOf<AmcfRecurrentState>()
        val orchestrator = AmcfCycleOrchestrator(
            execution = AmcfCycleExecutionPort { request, _ ->
                Result.success(observationFor(request.cycle))
            },
            commitObserver = AmcfRecurrentStateCommitObserver { committed += it }
        )

        val result = orchestrator.run(plan).getOrThrow()

        assertEquals(
            listOf(
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.VERIFY,
                AmcfComputeCycleKind.REVISE,
                AmcfComputeCycleKind.FINALIZE
            ),
            result.committedStates.map { it.completedCycleKind }
        )
        assertEquals(result.committedStates, committed)
        assertEquals(1, result.terminalState.verifiedPasses)
        assertEquals(1, result.terminalState.revisionCount)
        assertEquals(
            AmcfEarlyExitAction.ADVANCE_TO_VERIFY,
            result.earlyExitDecisions.last().action
        )
        assertTrue(result.committedStates.all { it.foundation == foundation })
    }

    @Test
    fun cancellationAfterCycleExecutionCannotCommitThatCycleState() {
        val plan = fastPlan()
        val committed = mutableListOf<AmcfRecurrentState>()
        val cancellation = InferenceCancellationSignal()
        val orchestrator = AmcfCycleOrchestrator(
            execution = AmcfCycleExecutionPort { request, signal ->
                assertEquals(AmcfComputeCycleKind.FINALIZE, request.cycle.kind)
                signal?.cancel()
                Result.success(
                    AmcfCycleObservation(
                        candidateDigest = "2".repeat(64),
                        evidenceDigest = "3".repeat(64),
                        confidence = 0.95,
                        uncertainty = 0.05,
                        evidenceSufficiency = 0.95
                    )
                )
            },
            commitObserver = AmcfRecurrentStateCommitObserver { committed += it }
        )

        val result = orchestrator.run(plan, cancellation)

        assertTrue(result.isFailure)
        assertTrue(committed.isEmpty())
    }

    @Test
    fun failedCycleLeavesOnlyPreviouslyCommittedState() {
        val plan = reasonPlan()
        val committed = mutableListOf<AmcfRecurrentState>()
        val orchestrator = AmcfCycleOrchestrator(
            execution = AmcfCycleExecutionPort { request, _ ->
                if (request.cycle.index == 2) {
                    Result.failure(IllegalStateException("cycle failed"))
                } else {
                    Result.success(observationFor(request.cycle))
                }
            },
            commitObserver = AmcfRecurrentStateCommitObserver { committed += it }
        )

        val result = orchestrator.run(plan)

        assertTrue(result.isFailure)
        assertEquals(1, committed.size)
        assertEquals(1, committed.single().completedCycleIndex)
    }

    @Test
    fun fastPlanRunsExactlyOneFinalizeCycle() {
        val plan = fastPlan()
        val executed = mutableListOf<AmcfComputeCycleKind>()
        val orchestrator = AmcfCycleOrchestrator(
            execution = AmcfCycleExecutionPort { request, _ ->
                executed += request.cycle.kind
                Result.success(observationFor(request.cycle))
            }
        )

        val result = orchestrator.run(plan).getOrThrow()

        assertEquals(listOf(AmcfComputeCycleKind.FINALIZE), executed)
        assertEquals(1, result.committedStates.size)
        assertEquals(AmcfComputeCycleKind.FINALIZE, result.terminalState.completedCycleKind)
    }

    private fun observationFor(cycle: AmcfComputeCycle): AmcfCycleObservation {
        val strong = cycle.index >= 2
        return AmcfCycleObservation(
            candidateDigest = cycle.index.toString().padStart(64, 'a').takeLast(64),
            evidenceDigest = cycle.index.toString().padStart(64, 'b').takeLast(64),
            confidence = if (strong) 0.92 else 0.70,
            uncertainty = if (strong) 0.08 else 0.30,
            evidenceSufficiency = if (strong) 0.92 else 0.65
        )
    }

    private fun reasonPlan(): AmcfComputeCyclePlan =
        AmcfComputeCyclePlanner.plan(
            foundation = foundation,
            budget = OmegaComputeBudget(
                mode = OmegaComputeMode.REASON,
                recurrentCycles = 3,
                verifyPasses = 1,
                targetFirstTokenMs = 4_000L,
                allowInternetVerification = true,
                allowToolUse = true
            )
        )

    private fun fastPlan(): AmcfComputeCyclePlan =
        AmcfComputeCyclePlanner.plan(
            foundation = foundation,
            budget = OmegaComputeBudget(
                mode = OmegaComputeMode.FAST,
                recurrentCycles = 0,
                verifyPasses = 0,
                targetFirstTokenMs = 1_500L,
                allowInternetVerification = false,
                allowToolUse = false
            )
        )
}
