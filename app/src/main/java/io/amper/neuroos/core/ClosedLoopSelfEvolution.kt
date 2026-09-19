package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

data class EvolutionRuntimeIdentity(
    val revision: String,
    val artifactDigest: String
) {
    init {
        require(revision.isNotBlank() && revision.length <= 512)
        require(artifactDigest.matches(SHA256))
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Identity of the currently active evolvable artifact/revision.
 *
 * Implementations may represent a model, strategy bundle, code package or architecture artifact.
 * Identity is read-only here; all mutation remains behind EvolutionDeploymentPort.
 */
fun interface EvolutionRuntimeIdentityPort {
    fun current(): EvolutionRuntimeIdentity
}

interface EvolutionDeploymentIdentityPort :
    EvolutionDeploymentPort,
    EvolutionRuntimeIdentityPort

data class ClosedLoopEvolutionEvidence(
    val capability: CapabilityId,
    val executed: Int,
    val failed: Int,
    val successRate: Double,
    val evidenceConfidence: Double,
    val severity: Double,
    val evidenceDigest: String
) {
    init {
        require(executed >= 0)
        require(failed >= 0)
        require(executed + failed >= MIN_EXECUTION_ATTEMPTS)
        require(successRate in 0.0..1.0)
        require(evidenceConfidence in 0.0..1.0)
        require(severity in 0.0..1.0)
        require(evidenceDigest.matches(SHA256))
    }

    val executionAttempts: Int
        get() = executed + failed

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MIN_EXECUTION_ATTEMPTS = 2
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

object ClosedLoopEvolutionEvidencePolicy {
    const val TARGET_EXECUTION_SUCCESS_RATE = 0.80

    fun from(
        directive: CognitiveExecutiveDirective,
        competence: CapabilityCompetenceModel
    ): ClosedLoopEvolutionEvidence? {
        if (directive.action != CognitiveExecutiveAction.EVOLVE) return null
        val capability = directive.triggeringCapability ?: return null
        val snapshot = competence.snapshot(capability) ?: return null
        if (snapshot.executionAttempts < ClosedLoopEvolutionEvidence.MIN_EXECUTION_ATTEMPTS) {
            return null
        }
        val successRate = snapshot.executionSuccessRate ?: return null
        if (successRate >= TARGET_EXECUTION_SUCCESS_RATE) return null

        val severity = maxOf(
            directive.learningPressure,
            (
                0.55 +
                    (TARGET_EXECUTION_SUCCESS_RATE - successRate) * 0.45
                ).coerceIn(0.55, 1.0)
        )
        val digest = closedLoopEvolutionSha256(
            listOf(
                "AMPER_CLOSED_LOOP_EVOLUTION_EVIDENCE_V1",
                capability.value,
                snapshot.executed.toString(),
                snapshot.failed.toString(),
                snapshot.lastObservedAtEpochMs.toString(),
                sixClosedLoop(successRate),
                sixClosedLoop(snapshot.evidenceConfidence),
                directive.cognitiveStateDigest,
                directive.executionContextDigest
            ).joinToString("|")
        )
        return ClosedLoopEvolutionEvidence(
            capability = capability,
            executed = snapshot.executed,
            failed = snapshot.failed,
            successRate = successRate,
            evidenceConfidence = snapshot.evidenceConfidence,
            severity = severity,
            evidenceDigest = digest
        )
    }

    fun candidateKinds(
        evidence: ClosedLoopEvolutionEvidence
    ): Set<AutonomousEvolutionCandidateKind> =
        if (
            evidence.executionAttempts >= 4 &&
            evidence.successRate <= 0.50
        ) {
            AutonomousEvolutionCandidateKind.entries.toSet()
        } else {
            setOf(
                AutonomousEvolutionCandidateKind.STRATEGY,
                AutonomousEvolutionCandidateKind.CODE,
                AutonomousEvolutionCandidateKind.MODEL
            )
        }
}

object ClosedLoopExecutionBenchmark {
    const val MIN_SAMPLES = ClosedLoopEvolutionEvidence.MIN_EXECUTION_ATTEMPTS
    const val MIN_CANDIDATE_SCORE = ClosedLoopEvolutionEvidencePolicy.TARGET_EXECUTION_SUCCESS_RATE
    const val REGRESSION_TOLERANCE = 0.02

    fun suite(capability: CapabilityId): EvolutionBenchmarkSuite {
        val capabilityHash = closedLoopEvolutionSha256(capability.value)
        return EvolutionBenchmarkSuite(
            id = EvolutionBenchmarkSuiteId(
                "live-execution-" + capabilityHash.take(20)
            ),
            metrics = listOf(
                EvolutionBenchmarkMetric(
                    id = metricId(capability),
                    weight = 1.0,
                    minSamples = MIN_SAMPLES,
                    minCandidateScore = MIN_CANDIDATE_SCORE,
                    regressionTolerance = REGRESSION_TOLERANCE
                )
            ),
            createdAtEpochMs = 0L
        )
    }

    fun baseline(
        evidence: ClosedLoopEvolutionEvidence,
        identity: EvolutionRuntimeIdentity,
        observedAtEpochMs: Long
    ): EvolutionBaselineState {
        require(observedAtEpochMs >= 0L)
        val suite = suite(evidence.capability)
        return EvolutionBaselineState(
            revision = identity.revision,
            benchmark = EvolutionBenchmarkSnapshot(
                subjectId = EvolutionBenchmarkSubjectId(
                    "live-" +
                        closedLoopEvolutionSha256(
                            identity.revision + "|" + evidence.capability.value
                        ).take(24)
                ),
                suiteId = suite.id,
                suiteDigest = suite.canonicalDigest,
                metrics = mapOf(
                    metricId(evidence.capability) to
                        EvolutionMetricObservation(
                            score = evidence.successRate,
                            samples = evidence.executionAttempts
                        )
                ),
                artifactDigest = identity.artifactDigest,
                observedAtEpochMs = observedAtEpochMs
            )
        )
    }

    fun metricId(capability: CapabilityId): EvolutionBenchmarkMetricId {
        val safe = capability.value.take(88)
        val suffix = closedLoopEvolutionSha256(capability.value).take(12)
        return EvolutionBenchmarkMetricId("exec:$safe:$suffix")
    }
}

/**
 * Single autonomous-evolution transaction baseline.
 *
 * The live weakness snapshot is frozen for the whole campaign. On a committed promotion the canary
 * becomes the new baseline inside this transaction only. A future trigger must recapture fresh real
 * outcome evidence and current deployment identity rather than reusing stale benchmark state.
 */
class SingleRunEvolutionBaselinePort(
    initial: EvolutionBaselineState
) : EvolutionBaselinePort {
    @Volatile
    private var state: EvolutionBaselineState = initial

    override fun current(): EvolutionBaselineState = state

    @Synchronized
    override fun advance(
        expected: EvolutionBaselineState,
        proposal: EvolutionPromotionProposal,
        receipt: EvolutionDeploymentReceipt,
        canary: EvolutionBenchmarkSnapshot
    ): Result<EvolutionBaselineState> = runCatching {
        require(state == expected) { "closed-loop evolution baseline compare-and-set failed" }
        require(receipt.activeRevision == proposal.proposedRevision)
        require(receipt.artifactDigest == proposal.artifactDigest)
        require(canary.suiteId == expected.benchmark.suiteId)
        require(canary.suiteDigest == expected.benchmark.suiteDigest)
        require(canary.artifactDigest == proposal.artifactDigest)
        require(canary.subjectId.value == proposal.candidateId.value)
        val next = EvolutionBaselineState(
            revision = receipt.activeRevision,
            benchmark = canary.copy(
                subjectId = EvolutionBenchmarkSubjectId(
                    "live-baseline-" + proposal.candidateId.value.take(96)
                )
            )
        )
        state = next
        next
    }
}

data class ClosedLoopEvolutionLedgerEntry(
    val capability: CapabilityId,
    val evidenceDigest: String,
    val baselineRevisionDigest: String,
    val terminalStage: EvolutionAutonomyCycleStage,
    val committedCount: Int,
    val observedAtEpochMs: Long
) {
    init {
        require(evidenceDigest.matches(SHA256))
        require(baselineRevisionDigest.matches(SHA256))
        require(committedCount >= 0)
        require(observedAtEpochMs >= 0L)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

interface ClosedLoopEvolutionLedger {
    fun get(capability: CapabilityId): ClosedLoopEvolutionLedgerEntry?

    fun record(
        capability: CapabilityId,
        evidenceDigest: String,
        baselineRevision: String,
        result: EvolutionAutonomyRunResult,
        observedAtEpochMs: Long
    ): ClosedLoopEvolutionLedgerEntry

    fun alreadyAttempted(
        capability: CapabilityId,
        evidenceDigest: String,
        baselineRevision: String
    ): Boolean
}

class MemoryBackedClosedLoopEvolutionLedger(
    private val memory: MemoryOs
) : ClosedLoopEvolutionLedger {
    @Synchronized
    override fun get(capability: CapabilityId): ClosedLoopEvolutionLedgerEntry? =
        memory.get(memoryId(capability))
            ?.takeIf { it.kind == KIND }
            ?.let { decode(it.content) }
            ?.takeIf { it.capability == capability }

    @Synchronized
    override fun alreadyAttempted(
        capability: CapabilityId,
        evidenceDigest: String,
        baselineRevision: String
    ): Boolean {
        require(evidenceDigest.matches(SHA256))
        val entry = get(capability) ?: return false
        if (entry.evidenceDigest != evidenceDigest) return false
        if (entry.committedCount > 0) return true
        return entry.baselineRevisionDigest ==
            closedLoopEvolutionSha256(baselineRevision)
    }

    @Synchronized
    override fun record(
        capability: CapabilityId,
        evidenceDigest: String,
        baselineRevision: String,
        result: EvolutionAutonomyRunResult,
        observedAtEpochMs: Long
    ): ClosedLoopEvolutionLedgerEntry {
        require(evidenceDigest.matches(SHA256))
        require(baselineRevision.isNotBlank())
        require(observedAtEpochMs >= 0L)
        val entry = ClosedLoopEvolutionLedgerEntry(
            capability = capability,
            evidenceDigest = evidenceDigest,
            baselineRevisionDigest = closedLoopEvolutionSha256(baselineRevision),
            terminalStage = result.terminalStage,
            committedCount = result.committedCount,
            observedAtEpochMs = observedAtEpochMs
        )
        memory.remember(
            MemoryRecord(
                id = memoryId(capability),
                kind = KIND,
                content = encode(entry),
                importance = 0.94,
                provenance = Provenance(
                    source = "governed-live-outcomes",
                    producer = "closed-loop-self-evolution",
                    confidence = 1.0
                ),
                createdAtEpochMs = observedAtEpochMs
            )
        )
        return entry
    }

    private fun encode(entry: ClosedLoopEvolutionLedgerEntry): String =
        listOf(
            VERSION,
            encodeText(entry.capability.value),
            entry.evidenceDigest,
            entry.baselineRevisionDigest,
            entry.terminalStage.name,
            entry.committedCount.toString(),
            entry.observedAtEpochMs.toString()
        ).joinToString("|")

    private fun decode(content: String): ClosedLoopEvolutionLedgerEntry {
        val fields = content.split('|')
        require(fields.size == 7 && fields[0] == VERSION) {
            "unsupported closed-loop evolution ledger record"
        }
        return ClosedLoopEvolutionLedgerEntry(
            capability = CapabilityId(decodeText(fields[1])),
            evidenceDigest = fields[2],
            baselineRevisionDigest = fields[3],
            terminalStage = EvolutionAutonomyCycleStage.valueOf(fields[4]),
            committedCount = fields[5].toInt(),
            observedAtEpochMs = fields[6].toLong()
        )
    }

    private fun memoryId(capability: CapabilityId): MemoryId =
        MemoryId(
            "closed-loop-evolution:" +
                closedLoopEvolutionSha256(capability.value).take(24)
        )

    companion object {
        const val KIND = "closed-loop-self-evolution-v1"
        private const val VERSION = "CLSE1"
        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun encodeText(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        private fun decodeText(value: String): String =
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }
}

/**
 * Phase546-550 live-outcome bridge into the existing verified evolution machinery.
 *
 * The executive already detects governed execution weakness. This port freezes that exact real
 * competence evidence into a content-addressed benchmark baseline, deduplicates unchanged evidence,
 * generates candidates through the existing campaign, requires sandbox benchmark/test/invariant
 * evidence, then uses the existing transactional promotion -> canary -> rollback path.
 *
 * It has no ToolFabric or AuthorityGate handle. Authority denials/unavailability/approval waits are
 * excluded upstream by CapabilityCompetenceSnapshot.executionAttempts and therefore cannot teach the
 * system to evolve around governance.
 */
class ClosedLoopSelfEvolutionExecutivePort(
    private val competence: CapabilityCompetenceModel,
    private val evolution: AutonomousEvolutionModel,
    private val identity: EvolutionRuntimeIdentityPort,
    private val ledger: ClosedLoopEvolutionLedger,
    private val orchestratorFactory: (EvolutionBaselinePort) -> AutonomousEvolutionOrchestrator,
    private val maxCandidates: Int = EvolutionCandidateGenerationRequest.MAX_CANDIDATES,
    private val clock: () -> Long = System::currentTimeMillis
) : CognitiveExecutiveEvolutionPort {
    init {
        require(maxCandidates in 1..EvolutionCandidateGenerationRequest.MAX_CANDIDATES)
    }

    @Synchronized
    override fun run(
        directive: CognitiveExecutiveDirective
    ): Result<EvolutionAutonomyRunResult> = runCatching {
        val evidence = requireNotNull(
            ClosedLoopEvolutionEvidencePolicy.from(directive, competence)
        ) {
            "closed-loop evolution requires fresh governed execution weakness"
        }
        val activeIdentity = identity.current()
        val now = clock().coerceAtLeast(0L)
        val baselineState = ClosedLoopExecutionBenchmark.baseline(
            evidence = evidence,
            identity = activeIdentity,
            observedAtEpochMs = now
        )
        val suite = ClosedLoopExecutionBenchmark.suite(evidence.capability)
        evolution.putSuite(suite)

        if (
            ledger.alreadyAttempted(
                capability = evidence.capability,
                evidenceDigest = evidence.evidenceDigest,
                baselineRevision = activeIdentity.revision
            )
        ) {
            return@runCatching duplicateResult(
                evidence = evidence,
                baseline = baselineState,
                now = now
            )
        }

        val baseline = SingleRunEvolutionBaselinePort(baselineState)
        val orchestrator = orchestratorFactory(baseline)
        val result = orchestrator.runBounded(
            id = EvolutionAutonomyRunId(
                "closed-loop-" + closedLoopEvolutionSha256(
                    evidence.evidenceDigest + "|" + activeIdentity.revision
                ).take(24)
            ),
            suiteId = suite.id,
            allowedKinds = ClosedLoopEvolutionEvidencePolicy.candidateKinds(evidence),
            maxCandidates = maxCandidates,
            maxCycles = 1
        )
        ledger.record(
            capability = evidence.capability,
            evidenceDigest = evidence.evidenceDigest,
            baselineRevision = activeIdentity.revision,
            result = result,
            observedAtEpochMs = clock().coerceAtLeast(now)
        )
        result
    }

    private fun duplicateResult(
        evidence: ClosedLoopEvolutionEvidence,
        baseline: EvolutionBaselineState,
        now: Long
    ): EvolutionAutonomyRunResult =
        EvolutionAutonomyRunResult(
            id = EvolutionAutonomyRunId(
                "closed-loop-skip-" + evidence.evidenceDigest.take(24)
            ),
            cycles = listOf(
                EvolutionAutonomyCycleResult(
                    index = 1,
                    campaignId = null,
                    baselineRevision = baseline.revision,
                    baselineArtifactDigest =
                        requireNotNull(baseline.benchmark.artifactDigest),
                    stage = EvolutionAutonomyCycleStage.NO_GAP,
                    failureCode = "UNCHANGED_LIVE_EVIDENCE"
                )
            ),
            startedAtEpochMs = now,
            completedAtEpochMs = now
        )
}

internal fun closedLoopEvolutionSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun sixClosedLoop(value: Double): String =
    java.lang.String.format(java.util.Locale.ROOT, "%.6f", value)
