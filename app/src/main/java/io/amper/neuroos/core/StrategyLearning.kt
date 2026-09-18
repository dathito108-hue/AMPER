package io.amper.neuroos.core

import java.security.MessageDigest

enum class StrategyOutcomeKind {
    SUCCEEDED,
    FAILED,
    ABORTED
}

enum class StrategyOutcomeCause {
    SUCCESS,
    EXECUTION_FAILURE,
    AUTHORITY_BLOCKED,
    ENVIRONMENT_UNAVAILABLE,
    PROTOCOL_FAILURE,
    USER_ABORTED
}

data class StrategyOutcomeAttribution(
    val outcome: StrategyOutcomeKind,
    val cause: StrategyOutcomeCause
) {
    init {
        require(
            when (cause) {
                StrategyOutcomeCause.SUCCESS -> outcome == StrategyOutcomeKind.SUCCEEDED
                StrategyOutcomeCause.USER_ABORTED -> outcome == StrategyOutcomeKind.ABORTED
                StrategyOutcomeCause.EXECUTION_FAILURE,
                StrategyOutcomeCause.AUTHORITY_BLOCKED,
                StrategyOutcomeCause.ENVIRONMENT_UNAVAILABLE,
                StrategyOutcomeCause.PROTOCOL_FAILURE -> outcome == StrategyOutcomeKind.FAILED
            }
        ) { "strategy outcome kind/cause mismatch" }
    }
}

data class StrategySignature(
    val capabilities: List<CapabilityId>
) {
    init {
        require(capabilities.isNotEmpty()) { "strategy signature must contain at least one capability" }
        require(capabilities.size <= TitanPlanProtocol.MAX_STEPS) {
            "strategy signature exceeds bounded plan size"
        }
    }

    val canonical: String
        get() = capabilities.joinToString(">") { it.value }

    val digest: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        fun from(plan: SovereignPlan): StrategySignature =
            StrategySignature(plan.steps.sortedBy { it.index }.map { it.capability })
    }
}

/**
 * Historical evidence about one bounded capability sequence.
 *
 * [failures] remains the backward-compatible aggregate count of all non-aborted terminal
 * failures. Phase181 adds causal counters so only execution-attributable failures influence
 * strategy success rate and adaptive failure streaks. Authority, environment and protocol
 * failures remain observable but do not teach the system that a capability sequence itself is bad.
 *
 * Pre-v3 persisted failures cannot be causally reconstructed. They are loaded as
 * [legacyUnattributedFailures] and deliberately excluded from execution-attributable scoring.
 */
data class StrategyEvidenceSnapshot(
    val signature: StrategySignature,
    val successes: Int = 0,
    val failures: Int = 0,
    val aborted: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastOutcome: StrategyOutcomeKind? = null,
    val executionFailures: Int = failures,
    val authorityBlocked: Int = 0,
    val environmentUnavailable: Int = 0,
    val protocolFailures: Int = 0,
    val legacyUnattributedFailures: Int = 0,
    val lastCause: StrategyOutcomeCause? = null,
    val lastObservedAtEpochMs: Long = 0L
) {
    init {
        require(successes >= 0 && failures >= 0 && aborted >= 0)
        require(executionFailures >= 0)
        require(authorityBlocked >= 0)
        require(environmentUnavailable >= 0)
        require(protocolFailures >= 0)
        require(legacyUnattributedFailures >= 0)
        require(
            executionFailures +
                authorityBlocked +
                environmentUnavailable +
                protocolFailures +
                legacyUnattributedFailures == failures
        ) { "causal failure counters must sum to aggregate failures" }
        require(consecutiveFailures >= 0)
        require(consecutiveFailures <= executionFailures)
        require(lastObservedAtEpochMs >= 0L)
    }

    /** Attempts that actually exercised the strategy and can support skill credit assignment. */
    val completedAttempts: Int
        get() = successes + executionFailures

    val completedSuccessRate: Double?
        get() = completedAttempts.takeIf { it > 0 }?.let {
            successes.toDouble() / it.toDouble()
        }

    val evidenceConfidence: Double
        get() = completedAttempts.toDouble() / (completedAttempts.toDouble() + 4.0)

    val nonExecutionBlocks: Int
        get() = authorityBlocked + environmentUnavailable + protocolFailures +
            legacyUnattributedFailures
}

interface StrategyLearningModel {
    fun observe(plan: SovereignPlan): StrategyEvidenceSnapshot?
    fun snapshot(signature: StrategySignature): StrategyEvidenceSnapshot?
    fun recent(limit: Int = 4): List<StrategyEvidenceSnapshot>
}

/**
 * Persistent, bounded procedural evidence derived from completed governed plans.
 *
 * Raw goals, tool inputs and tool outputs are intentionally excluded. The learned key is only the
 * ordered capability sequence. A deterministic per-plan marker makes observation idempotent, while
 * a bounded index prevents strategy discovery from becoming an unbounded memory surface.
 *
 * Phase181 performs causal credit assignment from the earliest non-executed terminal step:
 * authority denial, environment unavailability and protocol invalidity are recorded separately
 * from execution failure. This prevents governance/environment outcomes from poisoning strategy
 * competence while preserving them as structural evidence.
 */
class MemoryBackedStrategyLearningModel(
    private val memory: MemoryOs,
    private val clock: () -> Long = System::currentTimeMillis
) : StrategyLearningModel {
    override fun observe(plan: SovereignPlan): StrategyEvidenceSnapshot? {
        val attribution = classify(plan) ?: return null
        val signature = StrategySignature.from(plan)
        val markerId = markerId(plan.id)

        return memory.transaction {
            val existingMarker = get(markerId)
            if (existingMarker != null) {
                val marker = StrategyObservationCodec.decodeMarker(existingMarker.content)
                require(marker != null) { "strategy observation marker is malformed" }
                require(marker.signatureDigest == signature.digest) {
                    "completed plan identity changed strategy signature"
                }
                require(marker.outcome == attribution.outcome) {
                    "completed plan identity changed terminal strategy outcome"
                }
                if (marker.cause != null) {
                    require(marker.cause == attribution.cause) {
                        "completed plan identity changed terminal strategy cause"
                    }
                }
                return@transaction snapshotLocked(signature)
            }

            val previous = snapshotLocked(signature) ?: StrategyEvidenceSnapshot(signature)
            val now = clock().coerceAtLeast(previous.lastObservedAtEpochMs)
            val updated = when (attribution.cause) {
                StrategyOutcomeCause.SUCCESS -> previous.copy(
                    successes = previous.successes + 1,
                    consecutiveFailures = 0,
                    lastOutcome = StrategyOutcomeKind.SUCCEEDED,
                    lastCause = StrategyOutcomeCause.SUCCESS,
                    lastObservedAtEpochMs = now
                )

                StrategyOutcomeCause.EXECUTION_FAILURE -> previous.copy(
                    failures = previous.failures + 1,
                    executionFailures = previous.executionFailures + 1,
                    consecutiveFailures = previous.consecutiveFailures + 1,
                    lastOutcome = StrategyOutcomeKind.FAILED,
                    lastCause = StrategyOutcomeCause.EXECUTION_FAILURE,
                    lastObservedAtEpochMs = now
                )

                StrategyOutcomeCause.AUTHORITY_BLOCKED -> previous.copy(
                    failures = previous.failures + 1,
                    authorityBlocked = previous.authorityBlocked + 1,
                    lastOutcome = StrategyOutcomeKind.FAILED,
                    lastCause = StrategyOutcomeCause.AUTHORITY_BLOCKED,
                    lastObservedAtEpochMs = now
                )

                StrategyOutcomeCause.ENVIRONMENT_UNAVAILABLE -> previous.copy(
                    failures = previous.failures + 1,
                    environmentUnavailable = previous.environmentUnavailable + 1,
                    lastOutcome = StrategyOutcomeKind.FAILED,
                    lastCause = StrategyOutcomeCause.ENVIRONMENT_UNAVAILABLE,
                    lastObservedAtEpochMs = now
                )

                StrategyOutcomeCause.PROTOCOL_FAILURE -> previous.copy(
                    failures = previous.failures + 1,
                    protocolFailures = previous.protocolFailures + 1,
                    lastOutcome = StrategyOutcomeKind.FAILED,
                    lastCause = StrategyOutcomeCause.PROTOCOL_FAILURE,
                    lastObservedAtEpochMs = now
                )

                StrategyOutcomeCause.USER_ABORTED -> previous.copy(
                    aborted = previous.aborted + 1,
                    lastOutcome = StrategyOutcomeKind.ABORTED,
                    lastCause = StrategyOutcomeCause.USER_ABORTED,
                    lastObservedAtEpochMs = now
                )
            }

            remember(
                MemoryRecord(
                    id = markerId,
                    kind = MARKER_KIND,
                    content = StrategyObservationCodec.encodeMarker(
                        signatureDigest = signature.digest,
                        attribution = attribution
                    ),
                    importance = 0.58,
                    provenance = Provenance(
                        source = "governed-plan-outcome",
                        producer = "strategy-learning-model",
                        confidence = 1.0
                    ),
                    createdAtEpochMs = now
                )
            )
            remember(
                MemoryRecord(
                    id = aggregateId(signature),
                    kind = SNAPSHOT_KIND,
                    content = StrategyObservationCodec.encodeSnapshot(updated),
                    importance = 0.76,
                    provenance = Provenance(
                        source = "governed-plan-outcome",
                        producer = "strategy-learning-model",
                        confidence = 1.0,
                        parents = setOf(markerId)
                    ),
                    createdAtEpochMs = now
                )
            )
            updateIndexLocked(signature.digest, now)
            updated
        }
    }

    override fun snapshot(signature: StrategySignature): StrategyEvidenceSnapshot? =
        memory.transaction { snapshotLocked(signature) }

    override fun recent(limit: Int): List<StrategyEvidenceSnapshot> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.transaction {
            decodeIndex(get(INDEX_ID)?.content)
                .asReversed()
                .asSequence()
                .mapNotNull { digest -> snapshotByDigestLocked(digest) }
                .take(limit)
                .toList()
        }
    }

    private fun MemoryOs.snapshotLocked(
        signature: StrategySignature
    ): StrategyEvidenceSnapshot? =
        get(aggregateId(signature))
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { StrategyObservationCodec.decodeSnapshot(it.content) }
            ?.takeIf { it.signature == signature }

    private fun MemoryOs.snapshotByDigestLocked(
        digest: String
    ): StrategyEvidenceSnapshot? =
        get(MemoryId("strategy-evidence:$digest"))
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { StrategyObservationCodec.decodeSnapshot(it.content) }
            ?.takeIf { it.signature.digest == digest }

    private fun MemoryOs.updateIndexLocked(digest: String, now: Long) {
        val current = decodeIndex(get(INDEX_ID)?.content)
        val next = (current.filterNot { it == digest } + digest)
            .takeLast(MAX_INDEXED_STRATEGIES)
        remember(
            MemoryRecord(
                id = INDEX_ID,
                kind = INDEX_KIND,
                content = "digests=" + next.joinToString(","),
                importance = 0.86,
                provenance = Provenance(
                    source = "governed-plan-outcome",
                    producer = "strategy-learning-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = now
            )
        )
    }

    private fun decodeIndex(content: String?): List<String> {
        if (content == null || !content.startsWith("digests=")) return emptyList()
        val raw = content.removePrefix("digests=")
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .filter { it.matches(SHA256) }
            .distinct()
            .takeLast(MAX_INDEXED_STRATEGIES)
    }

    private fun markerId(planId: PlanId): MemoryId =
        MemoryId("strategy-observation:${planId.value}")

    private fun aggregateId(signature: StrategySignature): MemoryId =
        MemoryId("strategy-evidence:${signature.digest}")

    private fun classify(plan: SovereignPlan): StrategyOutcomeAttribution? {
        if (!plan.complete) return null
        val firstNonExecuted = plan.steps
            .sortedBy { it.index }
            .firstOrNull { it.status != PlanStepStatus.EXECUTED }

        return when (firstNonExecuted?.status) {
            null -> StrategyOutcomeAttribution(
                outcome = StrategyOutcomeKind.SUCCEEDED,
                cause = StrategyOutcomeCause.SUCCESS
            )
            PlanStepStatus.FAILED -> StrategyOutcomeAttribution(
                outcome = StrategyOutcomeKind.FAILED,
                cause = StrategyOutcomeCause.EXECUTION_FAILURE
            )
            PlanStepStatus.DENIED -> StrategyOutcomeAttribution(
                outcome = StrategyOutcomeKind.FAILED,
                cause = StrategyOutcomeCause.AUTHORITY_BLOCKED
            )
            PlanStepStatus.UNAVAILABLE -> StrategyOutcomeAttribution(
                outcome = StrategyOutcomeKind.FAILED,
                cause = StrategyOutcomeCause.ENVIRONMENT_UNAVAILABLE
            )
            PlanStepStatus.MALFORMED -> StrategyOutcomeAttribution(
                outcome = StrategyOutcomeKind.FAILED,
                cause = StrategyOutcomeCause.PROTOCOL_FAILURE
            )
            PlanStepStatus.REJECTED -> StrategyOutcomeAttribution(
                outcome = StrategyOutcomeKind.ABORTED,
                cause = StrategyOutcomeCause.USER_ABORTED
            )
            PlanStepStatus.PLANNED,
            PlanStepStatus.REQUIRES_CONFIRMATION,
            PlanStepStatus.EXECUTED -> null
        }
    }

    companion object {
        private const val SNAPSHOT_KIND = "strategy-evidence"
        private const val MARKER_KIND = "strategy-observation"
        private const val INDEX_KIND = "strategy-evidence-index"
        private const val MAX_INDEXED_STRATEGIES = 64
        private val INDEX_ID = MemoryId("strategy-evidence:index")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

private data class StrategyObservationMarker(
    val signatureDigest: String,
    val outcome: StrategyOutcomeKind,
    val cause: StrategyOutcomeCause? = null
)

private object StrategyObservationCodec {
    fun encodeMarker(
        signatureDigest: String,
        attribution: StrategyOutcomeAttribution
    ): String = listOf(
        "v=2",
        "signature=$signatureDigest",
        "outcome=${attribution.outcome.name}",
        "cause=${attribution.cause.name}"
    ).joinToString(";")

    fun decodeMarker(content: String): StrategyObservationMarker? = runCatching {
        val fields = fields(content)
        val version = fields["v"]
        require(version == "1" || version == "2")
        val digest = requireNotNull(fields["signature"])
        require(digest.matches(Regex("[0-9a-f]{64}")))
        StrategyObservationMarker(
            signatureDigest = digest,
            outcome = StrategyOutcomeKind.valueOf(requireNotNull(fields["outcome"])),
            cause = if (version == "2") {
                StrategyOutcomeCause.valueOf(requireNotNull(fields["cause"]))
            } else {
                null
            }
        )
    }.getOrNull()

    fun encodeSnapshot(snapshot: StrategyEvidenceSnapshot): String = listOf(
        "v=3",
        "capabilities=${snapshot.signature.capabilities.joinToString(",") { it.value }}",
        "successes=${snapshot.successes}",
        "failures=${snapshot.failures}",
        "aborted=${snapshot.aborted}",
        "execution_failures=${snapshot.executionFailures}",
        "authority_blocked=${snapshot.authorityBlocked}",
        "environment_unavailable=${snapshot.environmentUnavailable}",
        "protocol_failures=${snapshot.protocolFailures}",
        "legacy_unattributed_failures=${snapshot.legacyUnattributedFailures}",
        "failure_streak=${snapshot.consecutiveFailures}",
        "last_outcome=${snapshot.lastOutcome?.name ?: "NONE"}",
        "last_cause=${snapshot.lastCause?.name ?: "NONE"}",
        "last=${snapshot.lastObservedAtEpochMs}"
    ).joinToString(";")

    fun decodeSnapshot(content: String): StrategyEvidenceSnapshot? = runCatching {
        val fields = fields(content)
        val version = fields["v"]
        require(version == "1" || version == "2" || version == "3")
        val capabilities = requireNotNull(fields["capabilities"])
            .split(',')
            .filter { it.isNotBlank() }
            .map { value ->
                require(value.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
                CapabilityId(value)
            }
        val failures = requireNotNull(fields["failures"]).toInt()
        val lastObserved = requireNotNull(fields["last"]).toLong()

        if (version == "3") {
            StrategyEvidenceSnapshot(
                signature = StrategySignature(capabilities),
                successes = requireNotNull(fields["successes"]).toInt(),
                failures = failures,
                aborted = requireNotNull(fields["aborted"]).toInt(),
                consecutiveFailures = requireNotNull(fields["failure_streak"]).toInt(),
                lastOutcome = requireNotNull(fields["last_outcome"])
                    .takeUnless { it == "NONE" }
                    ?.let { StrategyOutcomeKind.valueOf(it) },
                executionFailures = requireNotNull(fields["execution_failures"]).toInt(),
                authorityBlocked = requireNotNull(fields["authority_blocked"]).toInt(),
                environmentUnavailable = requireNotNull(fields["environment_unavailable"]).toInt(),
                protocolFailures = requireNotNull(fields["protocol_failures"]).toInt(),
                legacyUnattributedFailures =
                    requireNotNull(fields["legacy_unattributed_failures"]).toInt(),
                lastCause = requireNotNull(fields["last_cause"])
                    .takeUnless { it == "NONE" }
                    ?.let { StrategyOutcomeCause.valueOf(it) },
                lastObservedAtEpochMs = lastObserved
            )
        } else {
            StrategyEvidenceSnapshot(
                signature = StrategySignature(capabilities),
                successes = requireNotNull(fields["successes"]).toInt(),
                failures = failures,
                aborted = requireNotNull(fields["aborted"]).toInt(),
                consecutiveFailures = 0,
                lastOutcome = if (version == "2") {
                    requireNotNull(fields["last_outcome"])
                        .takeUnless { it == "NONE" }
                        ?.let { StrategyOutcomeKind.valueOf(it) }
                } else {
                    null
                },
                executionFailures = 0,
                authorityBlocked = 0,
                environmentUnavailable = 0,
                protocolFailures = 0,
                legacyUnattributedFailures = failures,
                lastCause = null,
                lastObservedAtEpochMs = lastObserved
            )
        }
    }.getOrNull()

    private fun fields(content: String): Map<String, String> =
        content.split(';').associate { field ->
            val separator = field.indexOf('=')
            require(separator > 0)
            field.substring(0, separator) to field.substring(separator + 1)
        }
}
