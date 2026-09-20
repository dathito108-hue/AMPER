package io.amper.neuroos.core.v2

import io.amper.neuroos.core.InferenceCancellationSignal

data class AmcfCycleObservation(
    val candidateDigest: String,
    val evidenceDigest: String,
    val confidence: Double,
    val uncertainty: Double,
    val evidenceSufficiency: Double
) {
    init {
        require(candidateDigest.matches(Regex("[0-9a-f]{64}")))
        require(evidenceDigest.matches(Regex("[0-9a-f]{64}")))
        require(confidence in 0.0..1.0)
        require(uncertainty in 0.0..1.0)
        require(evidenceSufficiency in 0.0..1.0)
    }
}

data class AmcfCycleExecutionRequest(
    val plan: AmcfComputeCyclePlan,
    val cycle: AmcfComputeCycle,
    val previousState: AmcfRecurrentState?
) {
    init {
        require(cycle in plan.cycles)
        require(cycle.foundation == plan.foundation)
        previousState?.let {
            require(it.foundation == plan.foundation)
            require(it.completedCycleIndex < cycle.index)
        }
    }
}

/**
 * Side-effect-free cognitive execution port.
 *
 * Implementations may call the existing AMPER foundation inference/cognitive stack, but must not
 * invoke ToolFabric or authority-bearing actions. The orchestrator owns recurrent-state commit.
 */
fun interface AmcfCycleExecutionPort {
    fun execute(
        request: AmcfCycleExecutionRequest,
        cancellation: InferenceCancellationSignal?
    ): Result<AmcfCycleObservation>
}

/**
 * Best-effort observability hook only. It does not own recurrent-state persistence/commit and its
 * failure cannot change the authoritative orchestrator result.
 */
fun interface AmcfRecurrentStateObserver {
    fun observed(state: AmcfRecurrentState)
}

data class AmcfCycleRunResult(
    val plan: AmcfComputeCyclePlan,
    val committedStates: List<AmcfRecurrentState>,
    val earlyExitDecisions: List<AmcfEarlyExitDecision>
) {
    init {
        require(committedStates.isNotEmpty())
        require(committedStates.size <= plan.maximumTotalCycles)
        require(committedStates.all { it.foundation == plan.foundation })
        require(committedStates.zipWithNext().all { (left, right) ->
            left.completedCycleIndex < right.completedCycleIndex
        })
        require(committedStates.last().completedCycleKind == AmcfComputeCycleKind.FINALIZE) {
            "successful AMCF run must commit FINALIZE"
        }
    }

    val terminalState: AmcfRecurrentState
        get() = committedStates.last()
}

/**
 * Bounded M4 cycle orchestrator over the Phase640 plan and Phase641 recurrent-state contract.
 *
 * The orchestrator does not own a second model/runtime. It only sequences an injected existing
 * cognitive/inference port, applies deterministic early-exit control, and commits structured state
 * after successful, non-cancelled cycles.
 */
class AmcfCycleOrchestrator(
    private val execution: AmcfCycleExecutionPort,
    private val stateObserver: AmcfRecurrentStateObserver =
        AmcfRecurrentStateObserver { }
) {
    fun run(
        plan: AmcfComputeCyclePlan,
        cancellation: InferenceCancellationSignal? = null
    ): Result<AmcfCycleRunResult> = runCatching {
        require(plan.cycles.isNotEmpty())
        require(plan.cycles.size <= AmcfComputeCyclePlanner.MAX_TOTAL_CYCLES)

        val states = mutableListOf<AmcfRecurrentState>()
        val decisions = mutableListOf<AmcfEarlyExitDecision>()
        var cursor = 0

        while (cursor < plan.cycles.size) {
            cancellation?.throwIfCancelled()
            val cycle = plan.cycles[cursor]
            val previous = states.lastOrNull()

            val observation = execution.execute(
                request = AmcfCycleExecutionRequest(
                    plan = plan,
                    cycle = cycle,
                    previousState = previous
                ),
                cancellation = cancellation
            ).getOrThrow()

            // Transaction boundary: a cancelled/failed cycle never creates or publishes new state.
            cancellation?.throwIfCancelled()
            val state = AmcfRecurrentStateTransition.record(
                cycle = cycle,
                previous = previous,
                candidateDigest = observation.candidateDigest,
                evidenceDigest = observation.evidenceDigest,
                confidence = observation.confidence,
                uncertainty = observation.uncertainty,
                evidenceSufficiency = observation.evidenceSufficiency
            )
            cancellation?.throwIfCancelled()
            states += state
            runCatching { stateObserver.observed(state) }

            if (cycle.kind == AmcfComputeCycleKind.DELIBERATE) {
                val decision = AmcfEarlyExitGate.decide(
                    plan = plan,
                    completedCycle = cycle,
                    state = state
                )
                decisions += decision
                cursor = nextCursor(
                    plan = plan,
                    currentCursor = cursor,
                    decision = decision
                )
            } else {
                cursor += 1
            }
        }

        AmcfCycleRunResult(
            plan = plan,
            committedStates = states.toList(),
            earlyExitDecisions = decisions.toList()
        )
    }

    private fun nextCursor(
        plan: AmcfComputeCyclePlan,
        currentCursor: Int,
        decision: AmcfEarlyExitDecision
    ): Int =
        when (decision.action) {
            AmcfEarlyExitAction.NOT_ELIGIBLE,
            AmcfEarlyExitAction.CONTINUE_DELIBERATION -> currentCursor + 1

            AmcfEarlyExitAction.ADVANCE_TO_VERIFY -> {
                val nextVerify = plan.cycles.indexOfFirst { cycle ->
                    cycle.index > plan.cycles[currentCursor].index &&
                        cycle.kind == AmcfComputeCycleKind.VERIFY
                }
                require(nextVerify >= 0) {
                    "AMCF early-exit requested verification but plan has no remaining VERIFY cycle"
                }
                nextVerify
            }

            AmcfEarlyExitAction.ADVANCE_TO_FINALIZE -> {
                val finalize = plan.cycles.indexOfLast {
                    it.kind == AmcfComputeCycleKind.FINALIZE
                }
                require(finalize > currentCursor) {
                    "AMCF early-exit requested FINALIZE but no later FINALIZE cycle exists"
                }
                finalize
            }
        }
}
