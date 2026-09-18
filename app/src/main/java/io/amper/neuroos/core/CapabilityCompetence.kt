package io.amper.neuroos.core

/**
 * Evidence-backed self-knowledge for AMPER's tool capabilities.
 *
 * This model is deliberately descriptive. It never grants authority, makes a tool executable,
 * changes capability admission, or overrides user approval. Its only input is an observed
 * [ActionOutcome] produced by the governed action layer.
 */
data class CapabilityCompetenceSnapshot(
    val capability: CapabilityId,
    val executed: Int = 0,
    val failed: Int = 0,
    val denied: Int = 0,
    val unavailable: Int = 0,
    val malformed: Int = 0,
    val requiresConfirmation: Int = 0,
    val lastObservedAtEpochMs: Long = 0L
) {
    init {
        require(
            listOf(
                executed,
                failed,
                denied,
                unavailable,
                malformed,
                requiresConfirmation
            ).all { it >= 0 }
        ) { "capability competence counts must be non-negative" }
        require(lastObservedAtEpochMs >= 0L)
    }

    /**
     * Only actual execution successes/failures count as competence evidence.
     *
     * DENIED is an authority result, UNAVAILABLE is an environment result, MALFORMED is a
     * protocol/input result and REQUIRES_CONFIRMATION is a pending user-decision state. Treating
     * those as skill failures would teach the self-model the wrong lesson.
     */
    val executionAttempts: Int
        get() = executed + failed

    val executionSuccessRate: Double?
        get() = executionAttempts.takeIf { it > 0 }?.let {
            executed.toDouble() / it.toDouble()
        }

    /** Bounded evidence confidence; four virtual unknown observations prevent early overconfidence. */
    val evidenceConfidence: Double
        get() = executionAttempts.toDouble() / (executionAttempts.toDouble() + 4.0)
}

interface CapabilityCompetenceModel {
    fun observe(outcome: ActionOutcome): CapabilityCompetenceSnapshot?
    fun snapshot(capability: CapabilityId): CapabilityCompetenceSnapshot?
    fun all(limit: Int = 8): List<CapabilityCompetenceSnapshot>
}

/**
 * Durable competence aggregate backed by [MemoryOs].
 *
 * Each capability uses one deterministic memory id, so updates replace the logical live aggregate
 * during journal replay rather than growing an unbounded competence-history surface. A separate
 * bounded index stores only capability ids and is used to enumerate snapshots.
 */
class MemoryBackedCapabilityCompetenceModel(
    private val memory: MemoryOs,
    private val clock: () -> Long = System::currentTimeMillis
) : CapabilityCompetenceModel {
    override fun observe(outcome: ActionOutcome): CapabilityCompetenceSnapshot? {
        val capability = outcome.proposal?.capability ?: return null
        if (outcome.status == ActionStatus.NO_ACTION) return null

        return memory.transaction {
            val previous = snapshotLocked(capability) ?: CapabilityCompetenceSnapshot(capability)
            val now = clock().coerceAtLeast(previous.lastObservedAtEpochMs)
            val updated = when (outcome.status) {
                ActionStatus.EXECUTED -> previous.copy(
                    executed = previous.executed + 1,
                    lastObservedAtEpochMs = now
                )
                ActionStatus.FAILED -> previous.copy(
                    failed = previous.failed + 1,
                    lastObservedAtEpochMs = now
                )
                ActionStatus.DENIED -> previous.copy(
                    denied = previous.denied + 1,
                    lastObservedAtEpochMs = now
                )
                ActionStatus.UNAVAILABLE -> previous.copy(
                    unavailable = previous.unavailable + 1,
                    lastObservedAtEpochMs = now
                )
                ActionStatus.MALFORMED -> previous.copy(
                    malformed = previous.malformed + 1,
                    lastObservedAtEpochMs = now
                )
                ActionStatus.REQUIRES_CONFIRMATION -> previous.copy(
                    requiresConfirmation = previous.requiresConfirmation + 1,
                    lastObservedAtEpochMs = now
                )
                ActionStatus.NO_ACTION -> previous
            }

            remember(
                MemoryRecord(
                    id = snapshotId(capability),
                    kind = SNAPSHOT_KIND,
                    content = CapabilityCompetenceCodec.encode(updated),
                    importance = 0.72,
                    provenance = Provenance(
                        source = "governed-action-outcomes",
                        producer = "capability-competence-model",
                        confidence = 1.0
                    ),
                    createdAtEpochMs = now
                )
            )
            updateIndexLocked(capability, now)
            updated
        }
    }

    override fun snapshot(capability: CapabilityId): CapabilityCompetenceSnapshot? =
        memory.transaction { snapshotLocked(capability) }

    override fun all(limit: Int): List<CapabilityCompetenceSnapshot> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.transaction {
            val capabilities = decodeIndex(get(INDEX_ID)?.content)
            capabilities
                .asReversed()
                .asSequence()
                .mapNotNull { capability -> snapshotLocked(capability) }
                .take(limit)
                .toList()
        }
    }

    private fun MemoryOs.snapshotLocked(
        capability: CapabilityId
    ): CapabilityCompetenceSnapshot? =
        get(snapshotId(capability))
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { CapabilityCompetenceCodec.decode(it.content) }
            ?.takeIf { it.capability == capability }

    private fun MemoryOs.updateIndexLocked(capability: CapabilityId, now: Long) {
        val current = decodeIndex(get(INDEX_ID)?.content)
        val next = (current.filterNot { it == capability } + capability)
            .takeLast(MAX_INDEXED_CAPABILITIES)
        remember(
            MemoryRecord(
                id = INDEX_ID,
                kind = INDEX_KIND,
                content = encodeIndex(next),
                importance = 0.55,
                provenance = Provenance(
                    source = "governed-action-outcomes",
                    producer = "capability-competence-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = now
            )
        )
    }

    private fun encodeIndex(capabilities: List<CapabilityId>): String =
        "capabilities=" + capabilities.joinToString(",") { it.value }

    private fun decodeIndex(content: String?): List<CapabilityId> {
        if (content == null || !content.startsWith("capabilities=")) return emptyList()
        val raw = content.removePrefix("capabilities=")
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .asSequence()
            .filter { it.matches(CAPABILITY_PATTERN) }
            .distinct()
            .map(::CapabilityId)
            .toList()
            .takeLast(MAX_INDEXED_CAPABILITIES)
    }

    private fun snapshotId(capability: CapabilityId): MemoryId =
        MemoryId("capability-competence:${capability.value}")

    companion object {
        private const val SNAPSHOT_KIND = "capability-competence"
        private const val INDEX_KIND = "capability-competence-index"
        private const val MAX_INDEXED_CAPABILITIES = 64
        private val INDEX_ID = MemoryId("capability-competence:index")
        private val CAPABILITY_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

private object CapabilityCompetenceCodec {
    fun encode(snapshot: CapabilityCompetenceSnapshot): String = listOf(
        "v=1",
        "capability=${snapshot.capability.value}",
        "executed=${snapshot.executed}",
        "failed=${snapshot.failed}",
        "denied=${snapshot.denied}",
        "unavailable=${snapshot.unavailable}",
        "malformed=${snapshot.malformed}",
        "confirmation=${snapshot.requiresConfirmation}",
        "last=${snapshot.lastObservedAtEpochMs}"
    ).joinToString(";")

    fun decode(content: String): CapabilityCompetenceSnapshot? = runCatching {
        val fields = content.split(';')
            .associate { field ->
                val separator = field.indexOf('=')
                require(separator > 0)
                field.substring(0, separator) to field.substring(separator + 1)
            }
        require(fields["v"] == "1")
        val capability = requireNotNull(fields["capability"])
        require(capability.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        CapabilityCompetenceSnapshot(
            capability = CapabilityId(capability),
            executed = requireNotNull(fields["executed"]).toInt(),
            failed = requireNotNull(fields["failed"]).toInt(),
            denied = requireNotNull(fields["denied"]).toInt(),
            unavailable = requireNotNull(fields["unavailable"]).toInt(),
            malformed = requireNotNull(fields["malformed"]).toInt(),
            requiresConfirmation = requireNotNull(fields["confirmation"]).toInt(),
            lastObservedAtEpochMs = requireNotNull(fields["last"]).toLong()
        )
    }.getOrNull()
}
