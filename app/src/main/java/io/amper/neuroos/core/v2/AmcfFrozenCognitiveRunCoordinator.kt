package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.InferenceCancellationSignal
import io.amper.neuroos.core.IntegratedCognitiveStatePacket
import io.amper.neuroos.core.IntegratedCognitiveStateSource
import io.amper.neuroos.core.ToolDescriptor

data class AmcfFrozenCognitiveSnapshot(
    val cognitiveStateDigest: String,
    val queryDigest: String,
    val capturedAtEpochMs: Long,
    val qualitySignals: AmcfIntegratedCognitiveQualitySignals
) {
    init {
        require(cognitiveStateDigest.matches(Regex("[0-9a-f]{64}")))
        require(queryDigest.matches(Regex("[0-9a-f]{64}")))
        require(capturedAtEpochMs >= 0L)
        require(qualitySignals.cognitiveStateDigest == cognitiveStateDigest) {
            "AMCF frozen quality signals must bind to the same cognitive snapshot"
        }
    }

    companion object {
        fun from(packet: IntegratedCognitiveStatePacket): AmcfFrozenCognitiveSnapshot =
            AmcfFrozenCognitiveSnapshot(
                cognitiveStateDigest = packet.canonicalDigest,
                queryDigest = packet.queryDigest,
                capturedAtEpochMs = packet.capturedAtEpochMs,
                qualitySignals = AmcfIntegratedCognitiveQualitySignals.from(packet)
            )
    }
}

fun interface AmcfCognitiveSnapshotProvider {
    fun capture(userPrompt: String): AmcfFrozenCognitiveSnapshot
}

/**
 * Adapter over the existing integrated cognitive state source.
 *
 * Snapshot capture is advisory/non-authority. Tool descriptors are supplied only as existing
 * capability metadata to the cognitive state source; this adapter never executes a tool.
 */
class IntegratedCognitiveAmcfSnapshotProvider(
    private val source: IntegratedCognitiveStateSource,
    private val allowedCapabilities: Set<CapabilityId>,
    private val descriptors: () -> Collection<ToolDescriptor>
) : AmcfCognitiveSnapshotProvider {
    init {
        require(allowedCapabilities.isNotEmpty())
    }

    override fun capture(userPrompt: String): AmcfFrozenCognitiveSnapshot {
        require(userPrompt.isNotBlank())
        val descriptorSnapshot = descriptors().toList()
        val packet = source.capture(
            query = userPrompt,
            allowedCapabilities = allowedCapabilities,
            descriptors = descriptorSnapshot
        )
        return AmcfFrozenCognitiveSnapshot.from(packet)
    }
}

data class AmcfCognitiveRunDiagnostics(
    val foundationId: String,
    val foundationSemanticSha256: String,
    val cognitiveStateDigest: String,
    val queryDigest: String,
    val computeMode: OmegaComputeMode,
    val plannedCycles: Int,
    val committedCycles: Int,
    val earlyExitDecisions: Int,
    val terminalStateDigest: String
) {
    init {
        require(foundationId.matches(Regex("[a-z0-9][a-z0-9._-]{0,95}")))
        require(foundationSemanticSha256.matches(Regex("[0-9a-f]{64}")))
        require(cognitiveStateDigest.matches(Regex("[0-9a-f]{64}")))
        require(queryDigest.matches(Regex("[0-9a-f]{64}")))
        require(plannedCycles in 1..AmcfComputeCyclePlanner.MAX_TOTAL_CYCLES)
        require(committedCycles in 1..plannedCycles)
        require(earlyExitDecisions >= 0)
        require(terminalStateDigest.matches(Regex("[0-9a-f]{64}")))
    }
}

data class AmcfBoundCognitiveRunResult(
    val snapshot: AmcfFrozenCognitiveSnapshot,
    val cycleRun: AmcfCycleRunResult,
    val diagnostics: AmcfCognitiveRunDiagnostics
) {
    init {
        require(snapshot.cognitiveStateDigest == diagnostics.cognitiveStateDigest)
        require(snapshot.queryDigest == diagnostics.queryDigest)
        require(cycleRun.plan.foundation.foundationId == diagnostics.foundationId)
        require(
            cycleRun.plan.foundation.semanticSha256 ==
                diagnostics.foundationSemanticSha256
        )
        require(cycleRun.terminalState.stateDigest == diagnostics.terminalStateDigest)
    }
}

/**
 * Binds one integrated cognitive snapshot to one complete AMCF run.
 *
 * The provider is called exactly once before any AMCF cycle executes. Its immutable quality signals
 * are then reused for all cycles, preventing planner/verify/revise drift from recapturing mutable
 * world or memory state mid-run. A later user turn starts a new coordinator run and may capture a
 * new snapshot.
 */
class AmcfFrozenCognitiveRunCoordinator(
    private val snapshotProvider: AmcfCognitiveSnapshotProvider,
    private val endpoint: AmcfFoundationInferenceEndpoint,
    private val stateObserver: AmcfRecurrentStateObserver =
        AmcfRecurrentStateObserver { }
) {
    fun run(
        plan: AmcfComputeCyclePlan,
        context: AmcfFoundationInferenceContext,
        cancellation: InferenceCancellationSignal? = null
    ): Result<AmcfBoundCognitiveRunResult> = runCatching {
        require(plan.foundation == endpoint.foundation) {
            "AMCF frozen run foundation does not match production inference endpoint"
        }
        val signal = cancellation ?: InferenceCancellationSignal()
        signal.throwIfCancelled()

        val snapshot = snapshotProvider.capture(context.userPrompt)
        signal.throwIfCancelled()

        val qualityEvaluator = AmcfIntegratedCognitiveQualityEvaluator(
            snapshot.qualitySignals
        )
        val execution = AmcfProductionFoundationInferencePort(
            endpoint = endpoint,
            context = context,
            qualityEvaluator = qualityEvaluator
        )
        val cycleRun = AmcfCycleOrchestrator(
            execution = execution,
            stateObserver = stateObserver
        ).run(
            plan = plan,
            cancellation = signal
        ).getOrThrow()

        signal.throwIfCancelled()
        val diagnostics = AmcfCognitiveRunDiagnostics(
            foundationId = plan.foundation.foundationId,
            foundationSemanticSha256 = plan.foundation.semanticSha256,
            cognitiveStateDigest = snapshot.cognitiveStateDigest,
            queryDigest = snapshot.queryDigest,
            computeMode = plan.mode,
            plannedCycles = plan.maximumTotalCycles,
            committedCycles = cycleRun.committedStates.size,
            earlyExitDecisions = cycleRun.earlyExitDecisions.size,
            terminalStateDigest = cycleRun.terminalState.stateDigest
        )

        AmcfBoundCognitiveRunResult(
            snapshot = snapshot,
            cycleRun = cycleRun,
            diagnostics = diagnostics
        )
    }
}
