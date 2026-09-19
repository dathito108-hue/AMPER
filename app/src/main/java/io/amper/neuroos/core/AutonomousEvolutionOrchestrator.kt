package io.amper.neuroos.core

@JvmInline
value class EvolutionAutonomyRunId(val value: String) {
    init { require(value.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
}

data class EvolutionBaselineState(
    val revision: String,
    val benchmark: EvolutionBenchmarkSnapshot
) {
    init {
        require(revision.isNotBlank() && revision.length <= 512)
        requireNotNull(benchmark.artifactDigest) {
            "autonomous evolution baseline must be content-addressed"
        }
    }
}

interface EvolutionBaselinePort {
    fun current(): EvolutionBaselineState

    /**
     * Advance the canonical benchmark baseline only after a committed promotion.
     * Implementations must compare-and-set [expected] to prevent stale concurrent advancement.
     */
    fun advance(
        expected: EvolutionBaselineState,
        proposal: EvolutionPromotionProposal,
        receipt: EvolutionDeploymentReceipt,
        canary: EvolutionBenchmarkSnapshot
    ): Result<EvolutionBaselineState>
}

enum class EvolutionAutonomyCycleStage {
    NO_GAP,
    NO_CANDIDATE,
    NO_WINNER,
    ROLLED_BACK,
    RECOVERY_BLOCKED,
    COMMITTED
}

data class EvolutionAutonomyCycleResult(
    val index: Int,
    val campaignId: EvolutionCampaignId?,
    val baselineRevision: String,
    val baselineArtifactDigest: String,
    val stage: EvolutionAutonomyCycleStage,
    val promotionCandidateId: EvolutionId? = null,
    val promotionExecutionId: EvolutionPromotionExecutionId? = null,
    val nextBaselineRevision: String? = null,
    val failureCode: String? = null
) {
    init {
        require(index > 0)
        require(baselineRevision.isNotBlank())
        require(baselineArtifactDigest.matches(Regex("[0-9a-f]{64}")))
        require(failureCode == null || failureCode.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        if (stage == EvolutionAutonomyCycleStage.COMMITTED) {
            require(promotionCandidateId != null)
            require(promotionExecutionId != null)
            require(!nextBaselineRevision.isNullOrBlank())
        }
    }

    val authorityBearing: Boolean
        get() = false
}

data class EvolutionAutonomyRunResult(
    val id: EvolutionAutonomyRunId,
    val cycles: List<EvolutionAutonomyCycleResult>,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long
) {
    init {
        require(cycles.isNotEmpty())
        require(cycles.size <= AutonomousEvolutionOrchestrator.MAX_CYCLES)
        require(startedAtEpochMs >= 0L)
        require(completedAtEpochMs >= startedAtEpochMs)
    }

    val committedCount: Int
        get() = cycles.count { it.stage == EvolutionAutonomyCycleStage.COMMITTED }

    val terminalStage: EvolutionAutonomyCycleStage
        get() = cycles.last().stage

    val authorityBearing: Boolean
        get() = false
}

/**
 * Phase246-250 bounded closed-loop autonomous evolution.
 *
 * Phase246 recovers incomplete promotion transactions before starting any new candidate campaign.
 * Phase247 reads an exact content-addressed baseline and runs the existing Phase241-245 campaign.
 * Phase248 hands only a canonical promotion ticket to the Phase236-240 transactional executor.
 * Phase249 advances the canonical benchmark baseline only after canary-backed COMMITTED deployment.
 * Phase250 repeats the loop under a strict cycle cap and stop conditions; no unbounded self-loop,
 * no ToolFabric path, and no authority mutation exists in this coordinator.
 */
class AutonomousEvolutionOrchestrator(
    private val evolution: AutonomousEvolutionModel,
    private val campaign: MemoryBackedAutonomousEvolutionCampaignCoordinator,
    private val promotion: AutonomousEvolutionPromotionExecutor,
    private val baseline: EvolutionBaselinePort,
    private val deployment: EvolutionDeploymentPort,
    private val canary: EvolutionCanaryEvaluator,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun runBounded(
        id: EvolutionAutonomyRunId,
        suiteId: EvolutionBenchmarkSuiteId,
        allowedKinds: Set<AutonomousEvolutionCandidateKind> =
            AutonomousEvolutionCandidateKind.entries.toSet(),
        maxCandidates: Int = EvolutionCandidateGenerationRequest.MAX_CANDIDATES,
        maxCycles: Int = MAX_CYCLES
    ): EvolutionAutonomyRunResult {
        require(maxCycles in 1..MAX_CYCLES)
        require(maxCandidates in 1..EvolutionCandidateGenerationRequest.MAX_CANDIDATES)
        require(allowedKinds.isNotEmpty())

        val started = clock()
        val cycles = mutableListOf<EvolutionAutonomyCycleResult>()

        val recovered = promotion.recoverIncomplete(deployment)
        if (recovered.any { it.stage == EvolutionPromotionExecutionStage.RECOVERY_REQUIRED }) {
            val state = baseline.current()
            cycles += EvolutionAutonomyCycleResult(
                index = 1,
                campaignId = null,
                baselineRevision = state.revision,
                baselineArtifactDigest = requireNotNull(state.benchmark.artifactDigest),
                stage = EvolutionAutonomyCycleStage.RECOVERY_BLOCKED,
                failureCode = "RECOVERY_REQUIRED"
            )
            return EvolutionAutonomyRunResult(
                id = id,
                cycles = cycles,
                startedAtEpochMs = started,
                completedAtEpochMs = clock().coerceAtLeast(started)
            )
        }

        for (index in 1..maxCycles) {
            val current = baseline.current()
            require(current.benchmark.suiteId == suiteId) {
                "autonomous evolution baseline suite mismatch"
            }
            val suite = requireNotNull(evolution.getSuite(suiteId)) {
                "autonomous evolution benchmark suite unavailable"
            }
            require(current.benchmark.suiteDigest == suite.canonicalDigest) {
                "autonomous evolution baseline suite digest mismatch"
            }
            val artifactDigest = requireNotNull(current.benchmark.artifactDigest)
            val objectives = EvolutionGapDetector.detect(suite, current.benchmark)
            if (objectives.isEmpty()) {
                cycles += EvolutionAutonomyCycleResult(
                    index = index,
                    campaignId = null,
                    baselineRevision = current.revision,
                    baselineArtifactDigest = artifactDigest,
                    stage = EvolutionAutonomyCycleStage.NO_GAP
                )
                break
            }

            val campaignId = EvolutionCampaignId(id.value + ":cycle:" + index)
            val campaignResult = runCatching {
                campaign.run(
                    campaignId = campaignId,
                    baselineRevision = current.revision,
                    suiteId = suiteId,
                    baseline = current.benchmark,
                    allowedKinds = allowedKinds,
                    maxCandidates = maxCandidates
                )
            }.getOrElse { failure ->
                cycles += EvolutionAutonomyCycleResult(
                    index = index,
                    campaignId = campaignId,
                    baselineRevision = current.revision,
                    baselineArtifactDigest = artifactDigest,
                    stage = EvolutionAutonomyCycleStage.NO_CANDIDATE,
                    failureCode = sanitizeAutonomyFailure(failure)
                )
                break
            }

            val proposal = campaignResult.promotionProposal
            if (campaignResult.candidateIds.isEmpty()) {
                cycles += EvolutionAutonomyCycleResult(
                    index = index,
                    campaignId = campaignId,
                    baselineRevision = current.revision,
                    baselineArtifactDigest = artifactDigest,
                    stage = EvolutionAutonomyCycleStage.NO_CANDIDATE
                )
                break
            }
            if (proposal == null) {
                cycles += EvolutionAutonomyCycleResult(
                    index = index,
                    campaignId = campaignId,
                    baselineRevision = current.revision,
                    baselineArtifactDigest = artifactDigest,
                    stage = EvolutionAutonomyCycleStage.NO_WINNER
                )
                break
            }

            val prepared = promotion.prepare(
                proposal = proposal,
                baseline = current.benchmark,
                deployment = deployment
            )
            val applied = promotion.apply(
                id = prepared.id,
                proposal = proposal,
                deployment = deployment
            )
            if (applied.stage == EvolutionPromotionExecutionStage.ROLLED_BACK ||
                applied.stage == EvolutionPromotionExecutionStage.RECOVERY_REQUIRED
            ) {
                cycles += EvolutionAutonomyCycleResult(
                    index = index,
                    campaignId = campaignId,
                    baselineRevision = current.revision,
                    baselineArtifactDigest = artifactDigest,
                    stage = if (applied.stage == EvolutionPromotionExecutionStage.RECOVERY_REQUIRED) {
                        EvolutionAutonomyCycleStage.RECOVERY_BLOCKED
                    } else {
                        EvolutionAutonomyCycleStage.ROLLED_BACK
                    },
                    promotionCandidateId = proposal.candidateId,
                    promotionExecutionId = applied.id,
                    failureCode = applied.failureCode
                )
                break
            }

            val recordingCanary = RecordingEvolutionCanaryEvaluator(canary)
            val committed = promotion.canaryAndCommit(
                id = applied.id,
                proposal = proposal,
                baseline = current.benchmark,
                deployment = deployment,
                canary = recordingCanary
            )
            if (committed.stage != EvolutionPromotionExecutionStage.COMMITTED) {
                cycles += EvolutionAutonomyCycleResult(
                    index = index,
                    campaignId = campaignId,
                    baselineRevision = current.revision,
                    baselineArtifactDigest = artifactDigest,
                    stage = if (committed.stage == EvolutionPromotionExecutionStage.RECOVERY_REQUIRED) {
                        EvolutionAutonomyCycleStage.RECOVERY_BLOCKED
                    } else {
                        EvolutionAutonomyCycleStage.ROLLED_BACK
                    },
                    promotionCandidateId = proposal.candidateId,
                    promotionExecutionId = committed.id,
                    failureCode = committed.failureCode
                )
                break
            }

            val receipt = requireNotNull(committed.receipt)
            val canarySnapshot = requireNotNull(recordingCanary.snapshot) {
                "committed promotion is missing captured canary snapshot"
            }
            val next = baseline.advance(
                expected = current,
                proposal = proposal,
                receipt = receipt,
                canary = canarySnapshot
            ).getOrElse { failure ->
                promotion.rollbackCommitted(
                    id = committed.id,
                    proposal = proposal,
                    deployment = deployment
                )
                cycles += EvolutionAutonomyCycleResult(
                    index = index,
                    campaignId = campaignId,
                    baselineRevision = current.revision,
                    baselineArtifactDigest = artifactDigest,
                    stage = EvolutionAutonomyCycleStage.ROLLED_BACK,
                    promotionCandidateId = proposal.candidateId,
                    promotionExecutionId = committed.id,
                    failureCode = sanitizeAutonomyFailure(failure)
                )
                break
            }

            cycles += EvolutionAutonomyCycleResult(
                index = index,
                campaignId = campaignId,
                baselineRevision = current.revision,
                baselineArtifactDigest = artifactDigest,
                stage = EvolutionAutonomyCycleStage.COMMITTED,
                promotionCandidateId = proposal.candidateId,
                promotionExecutionId = committed.id,
                nextBaselineRevision = next.revision
            )
        }

        require(cycles.isNotEmpty())
        return EvolutionAutonomyRunResult(
            id = id,
            cycles = cycles,
            startedAtEpochMs = started,
            completedAtEpochMs = clock().coerceAtLeast(started)
        )
    }

    companion object {
        const val MAX_CYCLES = 4
    }
}

private class RecordingEvolutionCanaryEvaluator(
    private val delegate: EvolutionCanaryEvaluator
) : EvolutionCanaryEvaluator {
    var snapshot: EvolutionBenchmarkSnapshot? = null
        private set

    override fun evaluate(
        proposal: EvolutionPromotionProposal,
        receipt: EvolutionDeploymentReceipt
    ): Result<EvolutionBenchmarkSnapshot> {
        val result = delegate.evaluate(proposal, receipt)
        result.getOrNull()?.let { snapshot = it }
        return result
    }
}

private fun sanitizeAutonomyFailure(failure: Throwable?): String =
    failure?.javaClass?.simpleName
        ?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }
        ?: "EVOLUTION_CYCLE_FAILURE"
