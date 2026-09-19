package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@JvmInline
value class EvolutionPromotionExecutionId(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{24}"))) }
}

enum class EvolutionPromotionExecutionStage {
    PREPARED,
    APPLIED,
    COMMITTED,
    ROLLED_BACK,
    RECOVERY_REQUIRED
}

data class EvolutionDeploymentCheckpoint(
    val checkpointToken: String,
    val baselineRevision: String,
    val capturedAtEpochMs: Long
) {
    init {
        require(checkpointToken.isNotBlank() && checkpointToken.length <= 1024)
        require(baselineRevision.isNotBlank() && baselineRevision.length <= 512)
        require(capturedAtEpochMs >= 0L)
    }
}

data class EvolutionDeploymentReceipt(
    val artifactDigest: String,
    val activeRevision: String,
    val appliedAtEpochMs: Long
) {
    init {
        require(artifactDigest.matches(Regex("[0-9a-f]{64}")))
        require(activeRevision.isNotBlank() && activeRevision.length <= 512)
        require(appliedAtEpochMs >= 0L)
    }
}

interface EvolutionDeploymentPort {
    /**
     * Capture a rollback checkpoint without mutating the live candidate surface.
     */
    fun checkpoint(proposal: EvolutionPromotionProposal): Result<EvolutionDeploymentCheckpoint>

    /**
     * Apply exactly [proposal]. Implementations must not widen authority as part of deployment.
     */
    fun apply(
        proposal: EvolutionPromotionProposal,
        checkpoint: EvolutionDeploymentCheckpoint
    ): Result<EvolutionDeploymentReceipt>

    /**
     * Restore [checkpoint]. [receipt] may be null when apply failed before returning identity.
     */
    fun rollback(
        checkpoint: EvolutionDeploymentCheckpoint,
        receipt: EvolutionDeploymentReceipt?
    ): Result<Unit>
}

interface EvolutionCanaryEvaluator {
    fun evaluate(
        proposal: EvolutionPromotionProposal,
        receipt: EvolutionDeploymentReceipt
    ): Result<EvolutionBenchmarkSnapshot>
}

data class EvolutionPromotionExecution(
    val id: EvolutionPromotionExecutionId,
    val proposalTicketDigest: String,
    val tournamentId: EvolutionTournamentId,
    val candidateId: EvolutionId,
    val kind: AutonomousEvolutionCandidateKind,
    val artifactDigest: String,
    val baseRevision: String,
    val proposedRevision: String,
    val suiteId: EvolutionBenchmarkSuiteId,
    val suiteDigest: String,
    val baselineSubjectId: EvolutionBenchmarkSubjectId,
    val baseline: EvolutionBenchmarkSnapshot,
    val rollbackToken: String,
    val stage: EvolutionPromotionExecutionStage,
    val checkpoint: EvolutionDeploymentCheckpoint,
    val receipt: EvolutionDeploymentReceipt? = null,
    val canaryAggregateDelta: Double? = null,
    val failureCode: String? = null,
    val preparedAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    init {
        require(proposalTicketDigest.matches(Regex("[0-9a-f]{64}")))
        require(artifactDigest.matches(Regex("[0-9a-f]{64}")))
        require(baseRevision.isNotBlank())
        require(proposedRevision.isNotBlank())
        require(suiteDigest.matches(Regex("[0-9a-f]{64}")))
        require(baseline.subjectId == baselineSubjectId)
        require(baseline.suiteId == suiteId)
        require(baseline.suiteDigest == suiteDigest)
        requireNotNull(baseline.artifactDigest)
        require(rollbackToken.isNotBlank())
        require(canaryAggregateDelta == null || canaryAggregateDelta.isFinite())
        require(failureCode == null || failureCode.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(preparedAtEpochMs >= 0L)
        require(updatedAtEpochMs >= preparedAtEpochMs)

        when (stage) {
            EvolutionPromotionExecutionStage.PREPARED ->
                require(receipt == null)
            EvolutionPromotionExecutionStage.APPLIED,
            EvolutionPromotionExecutionStage.COMMITTED ->
                require(receipt != null)
            EvolutionPromotionExecutionStage.ROLLED_BACK,
            EvolutionPromotionExecutionStage.RECOVERY_REQUIRED -> Unit
        }
    }

    val liveCommitted: Boolean
        get() = stage == EvolutionPromotionExecutionStage.COMMITTED

    val authorityBearing: Boolean
        get() = false
}

interface AutonomousEvolutionPromotionExecutor {
    fun prepare(
        proposal: EvolutionPromotionProposal,
        baseline: EvolutionBenchmarkSnapshot,
        deployment: EvolutionDeploymentPort
    ): EvolutionPromotionExecution

    fun apply(
        id: EvolutionPromotionExecutionId,
        proposal: EvolutionPromotionProposal,
        deployment: EvolutionDeploymentPort
    ): EvolutionPromotionExecution

    fun canaryAndCommit(
        id: EvolutionPromotionExecutionId,
        proposal: EvolutionPromotionProposal,
        baseline: EvolutionBenchmarkSnapshot,
        deployment: EvolutionDeploymentPort,
        canary: EvolutionCanaryEvaluator
    ): EvolutionPromotionExecution

    fun rollbackCommitted(
        id: EvolutionPromotionExecutionId,
        proposal: EvolutionPromotionProposal,
        deployment: EvolutionDeploymentPort
    ): EvolutionPromotionExecution

    fun rollbackCommitted(
        id: EvolutionPromotionExecutionId,
        deployment: EvolutionDeploymentPort
    ): EvolutionPromotionExecution

    fun recoverIncomplete(
        deployment: EvolutionDeploymentPort
    ): List<EvolutionPromotionExecution>

    fun get(id: EvolutionPromotionExecutionId): EvolutionPromotionExecution?
    fun recent(limit: Int = 16): List<EvolutionPromotionExecution>
}

/**
 * Phase236-240 promotion transaction executor.
 *
 * Phase236 accepts only an immutable promotion ticket previously issued by AutonomousEvolutionModel
 * and captures a rollback checkpoint before any live mutation.
 *
 * Phase237 applies the exact content-addressed artifact/revision and persists APPLIED identity before
 * canary evaluation.
 *
 * Phase238 commits only when a fresh canary covers the exact benchmark suite, candidate artifact and
 * sample floors without material regression. CanonicalEvolutionGate promotion happens only here.
 *
 * Phase239 automatically rolls back apply failures, identity mismatches and canary regressions.
 *
 * Phase240 persists a bounded execution index so a restarted runtime can roll back every PREPARED,
 * APPLIED or RECOVERY_REQUIRED transaction instead of guessing whether a partial promotion is safe.
 */
class MemoryBackedAutonomousEvolutionPromotionExecutor(
    private val memory: MemoryOs,
    private val evolution: AutonomousEvolutionModel,
    private val gate: VerifiedEvolution,
    private val clock: () -> Long = System::currentTimeMillis
) : AutonomousEvolutionPromotionExecutor {
    @Synchronized
    override fun prepare(
        proposal: EvolutionPromotionProposal,
        baseline: EvolutionBenchmarkSnapshot,
        deployment: EvolutionDeploymentPort
    ): EvolutionPromotionExecution {
        validateKnownProposal(proposal)
        val suite = validateBaseline(proposal, baseline)
        require(suite.canonicalDigest == proposal.suiteDigest)

        val id = executionId(proposal)
        get(id)?.let { existing ->
            require(existing.proposalTicketDigest == proposal.ticketDigest) {
                "promotion execution identity collision"
            }
            return existing
        }

        val checkpoint = deployment.checkpoint(proposal).getOrThrow()
        require(checkpoint.baselineRevision == proposal.baseRevision) {
            "deployment checkpoint baseline revision mismatch"
        }
        val now = clock().coerceAtLeast(proposal.createdAtEpochMs)
        val prepared = EvolutionPromotionExecution(
            id = id,
            proposalTicketDigest = proposal.ticketDigest,
            tournamentId = proposal.tournamentId,
            candidateId = proposal.candidateId,
            kind = proposal.kind,
            artifactDigest = proposal.artifactDigest,
            baseRevision = proposal.baseRevision,
            proposedRevision = proposal.proposedRevision,
            suiteId = proposal.suiteId,
            suiteDigest = proposal.suiteDigest,
            baselineSubjectId = proposal.baselineSubjectId,
            baseline = baseline,
            rollbackToken = proposal.rollbackToken,
            stage = EvolutionPromotionExecutionStage.PREPARED,
            checkpoint = checkpoint,
            preparedAtEpochMs = now,
            updatedAtEpochMs = now
        )
        persist(prepared)
        return prepared
    }

    @Synchronized
    override fun apply(
        id: EvolutionPromotionExecutionId,
        proposal: EvolutionPromotionProposal,
        deployment: EvolutionDeploymentPort
    ): EvolutionPromotionExecution {
        validateKnownProposal(proposal)
        val current = requireNotNull(get(id)) { "promotion execution not found" }
        validateExecutionProposal(current, proposal)
        if (current.stage != EvolutionPromotionExecutionStage.PREPARED) return current

        val applied = deployment.apply(proposal, current.checkpoint)
        val receipt = applied.getOrNull()
        val identityValid = receipt != null &&
            receipt.artifactDigest == proposal.artifactDigest &&
            receipt.activeRevision == proposal.proposedRevision

        if (applied.isFailure || !identityValid) {
            val failureCode = if (applied.isFailure) {
                sanitizeFailure(applied.exceptionOrNull())
            } else {
                "DEPLOYMENT_IDENTITY_MISMATCH"
            }
            return rollbackIncomplete(
                current = current,
                deployment = deployment,
                receipt = receipt,
                failureCode = failureCode
            )
        }

        val updated = current.copy(
            stage = EvolutionPromotionExecutionStage.APPLIED,
            receipt = requireNotNull(receipt),
            updatedAtEpochMs = clock().coerceAtLeast(current.updatedAtEpochMs)
        )
        persist(updated)
        return updated
    }

    @Synchronized
    override fun canaryAndCommit(
        id: EvolutionPromotionExecutionId,
        proposal: EvolutionPromotionProposal,
        baseline: EvolutionBenchmarkSnapshot,
        deployment: EvolutionDeploymentPort,
        canary: EvolutionCanaryEvaluator
    ): EvolutionPromotionExecution {
        validateKnownProposal(proposal)
        val current = requireNotNull(get(id)) { "promotion execution not found" }
        validateExecutionProposal(current, proposal)
        if (current.stage == EvolutionPromotionExecutionStage.COMMITTED ||
            current.stage == EvolutionPromotionExecutionStage.ROLLED_BACK ||
            current.stage == EvolutionPromotionExecutionStage.RECOVERY_REQUIRED
        ) {
            return current
        }
        require(current.stage == EvolutionPromotionExecutionStage.APPLIED) {
            "canary requires an APPLIED promotion execution"
        }

        val suite = validateBaseline(proposal, baseline)
        val receipt = requireNotNull(current.receipt)
        val canaryResult = canary.evaluate(proposal, receipt)
        val snapshot = canaryResult.getOrNull()
        if (snapshot == null) {
            return rollbackIncomplete(
                current = current,
                deployment = deployment,
                receipt = receipt,
                failureCode = sanitizeFailure(canaryResult.exceptionOrNull())
            )
        }

        val canaryDecision = evaluateCanary(
            proposal = proposal,
            suite = suite,
            baseline = baseline,
            candidate = snapshot
        )
        if (!canaryDecision.accepted) {
            return rollbackIncomplete(
                current = current,
                deployment = deployment,
                receipt = receipt,
                failureCode = "CANARY_REGRESSION"
            )
        }

        gate.promote(proposal.verifiedDecision)
        val committed = current.copy(
            stage = EvolutionPromotionExecutionStage.COMMITTED,
            canaryAggregateDelta = canaryDecision.aggregateDelta,
            failureCode = null,
            updatedAtEpochMs = clock().coerceAtLeast(current.updatedAtEpochMs)
        )
        persist(committed)
        return committed
    }

    @Synchronized
    override fun rollbackCommitted(
        id: EvolutionPromotionExecutionId,
        proposal: EvolutionPromotionProposal,
        deployment: EvolutionDeploymentPort
    ): EvolutionPromotionExecution {
        validateKnownProposal(proposal)
        val current = requireNotNull(get(id)) { "promotion execution not found" }
        validateExecutionProposal(current, proposal)
        return rollbackCommittedInternal(current, deployment)
    }

    @Synchronized
    override fun rollbackCommitted(
        id: EvolutionPromotionExecutionId,
        deployment: EvolutionDeploymentPort
    ): EvolutionPromotionExecution {
        val current = requireNotNull(get(id)) { "promotion execution not found" }
        return rollbackCommittedInternal(current, deployment)
    }

    private fun rollbackCommittedInternal(
        current: EvolutionPromotionExecution,
        deployment: EvolutionDeploymentPort
    ): EvolutionPromotionExecution {
        require(current.stage == EvolutionPromotionExecutionStage.COMMITTED) {
            "only a committed promotion may use committed rollback"
        }
        deployment.rollback(current.checkpoint, current.receipt).getOrThrow()
        val verified = EvolutionDecision(
            candidateId = current.candidateId,
            stage = EvolutionStage.VERIFIED,
            promotable = true,
            reasons = emptyList(),
            rollbackToken = current.rollbackToken
        )
        gate.rollback(gate.promote(verified))
        val rolledBack = current.copy(
            stage = EvolutionPromotionExecutionStage.ROLLED_BACK,
            failureCode = "POST_COMMIT_ROLLBACK",
            updatedAtEpochMs = clock().coerceAtLeast(current.updatedAtEpochMs)
        )
        persist(rolledBack)
        return rolledBack
    }

    @Synchronized
    override fun recoverIncomplete(
        deployment: EvolutionDeploymentPort
    ): List<EvolutionPromotionExecution> =
        indexedExecutions()
            .filter {
                it.stage == EvolutionPromotionExecutionStage.PREPARED ||
                    it.stage == EvolutionPromotionExecutionStage.APPLIED ||
                    it.stage == EvolutionPromotionExecutionStage.RECOVERY_REQUIRED
            }
            .map { current ->
                rollbackIncomplete(
                    current = current,
                    deployment = deployment,
                    receipt = current.receipt,
                    failureCode = "CRASH_RECOVERY"
                )
            }

    override fun get(id: EvolutionPromotionExecutionId): EvolutionPromotionExecution? =
        memory.get(executionMemoryId(id))
            ?.takeIf { it.kind == EXECUTION_KIND }
            ?.let { EvolutionPromotionExecutionCodec.decode(it.content) }
            ?.takeIf { it.id == id }

    override fun recent(limit: Int): List<EvolutionPromotionExecution> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return indexedExecutions().takeLast(limit).asReversed()
    }

    private fun validateKnownProposal(proposal: EvolutionPromotionProposal) {
        require(evolution.isKnownPromotionProposal(proposal)) {
            "promotion proposal was not issued by canonical evolution tournament"
        }
    }

    private fun validateExecutionProposal(
        execution: EvolutionPromotionExecution,
        proposal: EvolutionPromotionProposal
    ) {
        require(execution.proposalTicketDigest == proposal.ticketDigest)
        require(execution.candidateId == proposal.candidateId)
        require(execution.artifactDigest == proposal.artifactDigest)
        require(execution.baseRevision == proposal.baseRevision)
        require(execution.proposedRevision == proposal.proposedRevision)
        require(execution.suiteId == proposal.suiteId)
        require(execution.suiteDigest == proposal.suiteDigest)
        require(execution.baselineSubjectId == proposal.baselineSubjectId)
        require(execution.rollbackToken == proposal.rollbackToken)
    }

    private fun validateBaseline(
        proposal: EvolutionPromotionProposal,
        baseline: EvolutionBenchmarkSnapshot
    ): EvolutionBenchmarkSuite {
        require(baseline.subjectId == proposal.baselineSubjectId)
        require(baseline.suiteId == proposal.suiteId)
        require(baseline.suiteDigest == proposal.suiteDigest)
        requireNotNull(baseline.artifactDigest) {
            "promotion baseline must be content-addressed"
        }
        val suite = requireNotNull(evolution.getSuite(proposal.suiteId)) {
            "promotion benchmark suite unavailable"
        }
        require(suite.canonicalDigest == proposal.suiteDigest)
        val expected = suite.metrics.map { it.id }.toSet()
        require(baseline.metrics.keys == expected)
        suite.metrics.forEach { metric ->
            require(requireNotNull(baseline.metrics[metric.id]).samples >= metric.minSamples) {
                "promotion baseline metric has insufficient samples: " + metric.id.value
            }
        }
        return suite
    }

    private data class CanaryDecision(
        val accepted: Boolean,
        val aggregateDelta: Double
    )

    private fun evaluateCanary(
        proposal: EvolutionPromotionProposal,
        suite: EvolutionBenchmarkSuite,
        baseline: EvolutionBenchmarkSnapshot,
        candidate: EvolutionBenchmarkSnapshot
    ): CanaryDecision {
        if (candidate.subjectId.value != proposal.candidateId.value) {
            return CanaryDecision(false, 0.0)
        }
        if (candidate.suiteId != suite.id ||
            candidate.suiteDigest != suite.canonicalDigest ||
            candidate.artifactDigest != proposal.artifactDigest
        ) {
            return CanaryDecision(false, 0.0)
        }
        val expected = suite.metrics.map { it.id }.toSet()
        if (candidate.metrics.keys != expected) {
            return CanaryDecision(false, 0.0)
        }

        var weightedDelta = 0.0
        var totalWeight = 0.0
        for (metric in suite.metrics) {
            val base = requireNotNull(baseline.metrics[metric.id])
            val observed = requireNotNull(candidate.metrics[metric.id])
            if (observed.samples < metric.minSamples) return CanaryDecision(false, 0.0)
            if (observed.score < metric.minCandidateScore) return CanaryDecision(false, 0.0)
            val delta = observed.score - base.score
            if (delta < -metric.regressionTolerance) return CanaryDecision(false, delta)
            weightedDelta += delta * metric.weight
            totalWeight += metric.weight
        }
        val aggregateDelta = if (totalWeight > 0.0) weightedDelta / totalWeight else 0.0
        return CanaryDecision(
            accepted = aggregateDelta >= MIN_CANARY_AGGREGATE_DELTA,
            aggregateDelta = aggregateDelta
        )
    }

    private fun rollbackIncomplete(
        current: EvolutionPromotionExecution,
        deployment: EvolutionDeploymentPort,
        receipt: EvolutionDeploymentReceipt?,
        failureCode: String
    ): EvolutionPromotionExecution {
        val rollback = deployment.rollback(current.checkpoint, receipt)
        val stage = if (rollback.isSuccess) {
            EvolutionPromotionExecutionStage.ROLLED_BACK
        } else {
            EvolutionPromotionExecutionStage.RECOVERY_REQUIRED
        }
        val updated = current.copy(
            stage = stage,
            receipt = receipt ?: current.receipt,
            failureCode = failureCode,
            updatedAtEpochMs = clock().coerceAtLeast(current.updatedAtEpochMs)
        )
        persist(updated)
        return updated
    }

    private fun persist(execution: EvolutionPromotionExecution) {
        memory.transaction {
            remember(
                MemoryRecord(
                    id = executionMemoryId(execution.id),
                    kind = EXECUTION_KIND,
                    content = EvolutionPromotionExecutionCodec.encode(execution),
                    importance = when (execution.stage) {
                        EvolutionPromotionExecutionStage.COMMITTED -> 0.99
                        EvolutionPromotionExecutionStage.RECOVERY_REQUIRED -> 1.0
                        else -> 0.95
                    },
                    provenance = Provenance(
                        source = "autonomous-evolution-promotion",
                        producer = "promotion-transaction-executor",
                        confidence = 1.0
                    ),
                    createdAtEpochMs = execution.updatedAtEpochMs
                )
            )
            updateIndexLocked(execution.id, execution.updatedAtEpochMs)
        }
    }

    private fun indexedExecutions(): List<EvolutionPromotionExecution> =
        memory.transaction {
            decodeIndex(get(INDEX_ID)?.content)
                .mapNotNull { id ->
                    get(executionMemoryId(id))
                        ?.takeIf { it.kind == EXECUTION_KIND }
                        ?.let { EvolutionPromotionExecutionCodec.decode(it.content) }
                }
        }

    private fun MemoryOs.updateIndexLocked(
        id: EvolutionPromotionExecutionId,
        now: Long
    ) {
        val current = decodeIndex(get(INDEX_ID)?.content)
        val next = (current.filterNot { it == id } + id).takeLast(MAX_INDEXED_EXECUTIONS)
        remember(
            MemoryRecord(
                id = INDEX_ID,
                kind = INDEX_KIND,
                content = next.joinToString(",") { it.value },
                importance = 0.98,
                provenance = Provenance(
                    source = "autonomous-evolution-promotion",
                    producer = "promotion-transaction-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = now
            )
        )
    }

    private fun decodeIndex(content: String?): List<EvolutionPromotionExecutionId> {
        if (content.isNullOrBlank()) return emptyList()
        return content.split(',')
            .mapNotNull { value ->
                runCatching { EvolutionPromotionExecutionId(value) }.getOrNull()
            }
            .distinct()
            .takeLast(MAX_INDEXED_EXECUTIONS)
    }

    private fun executionId(proposal: EvolutionPromotionProposal): EvolutionPromotionExecutionId =
        EvolutionPromotionExecutionId(
            promotionExecSha256(proposal.ticketDigest).take(24)
        )

    private fun executionMemoryId(id: EvolutionPromotionExecutionId): MemoryId =
        MemoryId("evolution-promotion-execution:" + id.value)

    private fun sanitizeFailure(failure: Throwable?): String =
        failure?.javaClass?.simpleName
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }
            ?: "DEPLOYMENT_FAILURE"

    companion object {
        const val EXECUTION_KIND = "evolution-promotion-execution"
        const val INDEX_KIND = "evolution-promotion-index"
        const val MIN_CANARY_AGGREGATE_DELTA = 0.0
        private const val MAX_INDEXED_EXECUTIONS = 32
        private val INDEX_ID = MemoryId("evolution-promotion:index")
    }
}

private object EvolutionPromotionExecutionCodec {
    fun encode(value: EvolutionPromotionExecution): String = listOf(
        "v=1",
        "id=" + value.id.value,
        "ticket=" + value.proposalTicketDigest,
        "tournament=" + enc(value.tournamentId.value),
        "candidate=" + enc(value.candidateId.value),
        "kind=" + value.kind.name,
        "artifact=" + value.artifactDigest,
        "base=" + enc(value.baseRevision),
        "proposed=" + enc(value.proposedRevision),
        "suite=" + enc(value.suiteId.value),
        "suite_digest=" + value.suiteDigest,
        "baseline=" + enc(value.baselineSubjectId.value),
        "baseline_artifact=" + requireNotNull(value.baseline.artifactDigest),
        "baseline_observed=" + value.baseline.observedAtEpochMs,
        "baseline_metrics=" + value.baseline.metrics.entries
            .sortedBy { it.key.value }
            .joinToString(",") { (id, observation) ->
                enc(id.value) + "." +
                    enc(observation.score.toString()) + "." +
                    observation.samples
            },
        "rollback_token=" + enc(value.rollbackToken),
        "stage=" + value.stage.name,
        "checkpoint_token=" + enc(value.checkpoint.checkpointToken),
        "checkpoint_revision=" + enc(value.checkpoint.baselineRevision),
        "checkpoint_at=" + value.checkpoint.capturedAtEpochMs,
        "receipt_artifact=" + (value.receipt?.artifactDigest ?: "~"),
        "receipt_revision=" + (value.receipt?.activeRevision?.let(::enc) ?: "~"),
        "receipt_at=" + (value.receipt?.appliedAtEpochMs?.toString() ?: "~"),
        "canary_delta=" + (value.canaryAggregateDelta?.toString()?.let(::enc) ?: "~"),
        "failure=" + (value.failureCode ?: "~"),
        "prepared=" + value.preparedAtEpochMs,
        "updated=" + value.updatedAtEpochMs
    ).joinToString(";")

    fun decode(content: String): EvolutionPromotionExecution? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        val receiptArtifact = requireNotNull(f["receipt_artifact"])
        val receiptRevision = requireNotNull(f["receipt_revision"])
        val receiptAt = requireNotNull(f["receipt_at"])
        val receipt = if (
            receiptArtifact == "~" &&
            receiptRevision == "~" &&
            receiptAt == "~"
        ) {
            null
        } else {
            EvolutionDeploymentReceipt(
                artifactDigest = receiptArtifact,
                activeRevision = dec(receiptRevision),
                appliedAtEpochMs = receiptAt.toLong()
            )
        }

        EvolutionPromotionExecution(
            id = EvolutionPromotionExecutionId(requireNotNull(f["id"])),
            proposalTicketDigest = requireNotNull(f["ticket"]),
            tournamentId = EvolutionTournamentId(dec(requireNotNull(f["tournament"]))),
            candidateId = EvolutionId(dec(requireNotNull(f["candidate"]))),
            kind = AutonomousEvolutionCandidateKind.valueOf(requireNotNull(f["kind"])),
            artifactDigest = requireNotNull(f["artifact"]),
            baseRevision = dec(requireNotNull(f["base"])),
            proposedRevision = dec(requireNotNull(f["proposed"])),
            suiteId = EvolutionBenchmarkSuiteId(dec(requireNotNull(f["suite"]))),
            suiteDigest = requireNotNull(f["suite_digest"]),
            baselineSubjectId = EvolutionBenchmarkSubjectId(
                dec(requireNotNull(f["baseline"]))
            ),
            baseline = EvolutionBenchmarkSnapshot(
                subjectId = EvolutionBenchmarkSubjectId(dec(requireNotNull(f["baseline"]))),
                suiteId = EvolutionBenchmarkSuiteId(dec(requireNotNull(f["suite"]))),
                suiteDigest = requireNotNull(f["suite_digest"]),
                metrics = requireNotNull(f["baseline_metrics"])
                    .split(',')
                    .filter { it.isNotBlank() }
                    .associate { encoded ->
                        val parts = encoded.split('.')
                        require(parts.size == 3)
                        EvolutionBenchmarkMetricId(dec(parts[0])) to
                            EvolutionMetricObservation(
                                score = dec(parts[1]).toDouble(),
                                samples = parts[2].toInt()
                            )
                    },
                artifactDigest = requireNotNull(f["baseline_artifact"]),
                observedAtEpochMs = requireNotNull(f["baseline_observed"]).toLong()
            ),
            rollbackToken = dec(requireNotNull(f["rollback_token"])),
            stage = EvolutionPromotionExecutionStage.valueOf(requireNotNull(f["stage"])),
            checkpoint = EvolutionDeploymentCheckpoint(
                checkpointToken = dec(requireNotNull(f["checkpoint_token"])),
                baselineRevision = dec(requireNotNull(f["checkpoint_revision"])),
                capturedAtEpochMs = requireNotNull(f["checkpoint_at"]).toLong()
            ),
            receipt = receipt,
            canaryAggregateDelta = requireNotNull(f["canary_delta"])
                .takeUnless { it == "~" }
                ?.let(::dec)
                ?.toDouble(),
            failureCode = requireNotNull(f["failure"]).takeUnless { it == "~" },
            preparedAtEpochMs = requireNotNull(f["prepared"]).toLong(),
            updatedAtEpochMs = requireNotNull(f["updated"]).toLong()
        )
    }.getOrNull()

    private fun fields(content: String): Map<String, String> =
        content.split(';').associate { field ->
            val separator = field.indexOf('=')
            require(separator > 0)
            field.substring(0, separator) to field.substring(separator + 1)
        }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}

private fun promotionExecSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }