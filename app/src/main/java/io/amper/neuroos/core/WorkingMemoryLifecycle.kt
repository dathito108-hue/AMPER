package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class WorkingMemoryLifecyclePolicy(
    val maxEvents: Int = DEFAULT_MAX_EVENTS,
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    val minSalience: Double = DEFAULT_MIN_SALIENCE,
    val checkpointEveryMutations: Int = DEFAULT_CHECKPOINT_EVERY_MUTATIONS
) {
    init {
        require(maxEvents in 1..MAX_EVENTS_LIMIT)
        require(maxAgeMs > 0L)
        require(minSalience in 0.0..1.0)
        require(checkpointEveryMutations in 1..MAX_CHECKPOINT_INTERVAL)
    }

    companion object {
        const val DEFAULT_MAX_EVENTS: Int = 128
        const val MAX_EVENTS_LIMIT: Int = 512
        const val DEFAULT_MAX_AGE_MS: Long = 15L * 60L * 1000L
        const val DEFAULT_MIN_SALIENCE: Double = 0.05
        const val DEFAULT_CHECKPOINT_EVERY_MUTATIONS: Int = 8
        const val MAX_CHECKPOINT_INTERVAL: Int = 64
    }
}

data class WorkingMemoryContinuityCheckpoint(
    val sequence: Long,
    val previousCheckpointSha256: String?,
    val workspaceStateSha256: String,
    val retainedEvents: Int,
    val capturedAtEpochMs: Long,
    val checkpointSha256: String
) {
    init {
        require(sequence > 0L)
        previousCheckpointSha256?.let { require(it.matches(SHA256)) }
        require(workspaceStateSha256.matches(SHA256))
        require(retainedEvents >= 0)
        require(capturedAtEpochMs >= 0L)
        require(checkpointSha256.matches(SHA256))
        require(
            checkpointSha256 == WorkingMemoryContinuityCodec.checkpointDigest(
                sequence = sequence,
                previousCheckpointSha256 = previousCheckpointSha256,
                workspaceStateSha256 = workspaceStateSha256,
                retainedEvents = retainedEvents,
                capturedAtEpochMs = capturedAtEpochMs
            )
        ) {
            "working-memory continuity checkpoint digest mismatch"
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

interface WorkingMemoryContinuityStore {
    fun latest(): Result<WorkingMemoryContinuityCheckpoint?>
    fun save(checkpoint: WorkingMemoryContinuityCheckpoint): Result<Unit>
}

/**
 * One-record continuity metadata store inside the existing sovereign MemoryOs.
 *
 * It persists hashes/count/time only. No CognitiveEvent topic or payload is stored here.
 */
class MemoryBackedWorkingMemoryContinuityStore(
    private val memory: MemoryOs
) : WorkingMemoryContinuityStore {
    override fun latest(): Result<WorkingMemoryContinuityCheckpoint?> = runCatching {
        memory.get(RECORD_ID)?.let { record ->
            require(record.kind == KIND) {
                "working-memory continuity record kind mismatch"
            }
            WorkingMemoryContinuityCodec.decode(record.content)
        }
    }

    override fun save(checkpoint: WorkingMemoryContinuityCheckpoint): Result<Unit> = runCatching {
        memory.transaction {
            val current = get(RECORD_ID)?.let { record ->
                require(record.kind == KIND) {
                    "working-memory continuity record kind mismatch"
                }
                WorkingMemoryContinuityCodec.decode(record.content)
            }
            require(checkpoint.sequence == (current?.sequence ?: 0L) + 1L) {
                "working-memory continuity sequence drift"
            }
            require(checkpoint.previousCheckpointSha256 == current?.checkpointSha256) {
                "working-memory continuity chain drift"
            }
            remember(
                MemoryRecord(
                    id = RECORD_ID,
                    kind = KIND,
                    content = WorkingMemoryContinuityCodec.encode(checkpoint),
                    importance = 0.58,
                    provenance = Provenance(
                        source = "working-memory-continuity",
                        producer = "canonical-working-memory",
                        confidence = 1.0
                    ),
                    createdAtEpochMs = checkpoint.capturedAtEpochMs
                )
            )
        }
    }

    companion object {
        val RECORD_ID = MemoryId("working-memory-continuity:latest")
        const val KIND = "working-memory-continuity-v1"
    }
}

/**
 * Bounded transient working memory for the canonical runtime.
 *
 * Raw CognitiveEvent payloads never leave this in-process workspace through the continuity path.
 * Capacity eviction is deterministic: lowest salience first, oldest first on ties. Expiry is
 * clock-bounded. Periodic durable continuity stores only verified digest metadata in the same
 * MemoryOs and is never authority-bearing.
 */
class CanonicalWorkingMemoryWorkspace(
    private val continuity: WorkingMemoryContinuityStore,
    private val policy: WorkingMemoryLifecyclePolicy = WorkingMemoryLifecyclePolicy(),
    private val clock: () -> Long = System::currentTimeMillis
) : GlobalWorkspace {
    private data class Entry(
        val event: CognitiveEvent,
        val publishedAtEpochMs: Long
    )

    private val entries = mutableListOf<Entry>()
    private var mutationsSinceCheckpoint = 0
    private var lastCheckpoint: WorkingMemoryContinuityCheckpoint?
    private var continuityFailure: Throwable?

    init {
        val restored = continuity.latest()
        lastCheckpoint = restored.getOrNull()
        continuityFailure = restored.exceptionOrNull()
    }

    @Synchronized
    override fun publish(event: CognitiveEvent) {
        val now = clock()
        val changedByExpiry = pruneExpired(now)
        if (normalizedSalience(event) < policy.minSalience) {
            if (changedByExpiry) recordMutationAndMaybeCheckpoint(now)
            return
        }

        entries += Entry(event, now)
        trimToCapacity()
        recordMutationAndMaybeCheckpoint(now)
    }

    @Synchronized
    override fun snapshot(): List<CognitiveEvent> {
        val now = clock()
        if (pruneExpired(now)) {
            recordMutationAndMaybeCheckpoint(now)
        }
        return entries.map { it.event }
    }

    @Synchronized
    fun continuityStatus(): Result<WorkingMemoryContinuityCheckpoint?> =
        continuityFailure?.let { Result.failure(it) }
            ?: Result.success(lastCheckpoint)

    @Synchronized
    fun forceContinuityCheckpoint(): Result<WorkingMemoryContinuityCheckpoint?> {
        if (mutationsSinceCheckpoint == 0) return continuityStatus()
        if (continuityFailure != null) return continuityStatus()
        persistCheckpoint(clock())
        return continuityStatus()
    }

    private fun pruneExpired(now: Long): Boolean {
        val before = entries.size
        entries.removeAll { entry ->
            now >= entry.publishedAtEpochMs &&
                now - entry.publishedAtEpochMs >= policy.maxAgeMs
        }
        return entries.size != before
    }

    private fun trimToCapacity() {
        while (entries.size > policy.maxEvents) {
            val victim = entries.withIndex().minWithOrNull(
                compareBy<IndexedValue<Entry>> { normalizedSalience(it.value.event) }
                    .thenBy { it.value.publishedAtEpochMs }
                    .thenBy { it.index }
            ) ?: return
            entries.removeAt(victim.index)
        }
    }

    private fun recordMutationAndMaybeCheckpoint(now: Long) {
        mutationsSinceCheckpoint += 1
        if (
            mutationsSinceCheckpoint >= policy.checkpointEveryMutations &&
            continuityFailure == null
        ) {
            persistCheckpoint(now)
        }
    }

    private fun persistCheckpoint(now: Long) {
        val checkpoint = WorkingMemoryContinuityCodec.create(
            sequence = (lastCheckpoint?.sequence ?: 0L) + 1L,
            previousCheckpointSha256 = lastCheckpoint?.checkpointSha256,
            workspaceStateSha256 = workspaceStateDigest(entries),
            retainedEvents = entries.size,
            capturedAtEpochMs = now
        )
        continuity.save(checkpoint)
            .onSuccess {
                lastCheckpoint = checkpoint
                mutationsSinceCheckpoint = 0
            }
            .onFailure { continuityFailure = it }
    }

    private fun workspaceStateDigest(values: List<Entry>): String {
        val canonical = buildString {
            append("AMPER_WORKING_MEMORY_STATE_V1")
            values.forEach { entry ->
                append('\n')
                append(entry.publishedAtEpochMs)
                append('|')
                append(eventDigest(entry.event))
            }
        }
        return sha256(canonical)
    }

    private fun eventDigest(event: CognitiveEvent): String =
        sha256(
            listOf(
                "AMPER_WORKING_MEMORY_EVENT_V1",
                event.id,
                event.topic,
                event.payload,
                String.format(Locale.US, "%.6f", normalizedSalience(event))
            ).joinToString("|")
        )

    private fun normalizedSalience(event: CognitiveEvent): Double =
        event.salience.coerceIn(0.0, 1.0)
}

internal object WorkingMemoryContinuityCodec {
    private const val VERSION = "AMPER_WORKING_MEMORY_CONTINUITY_V1"

    fun create(
        sequence: Long,
        previousCheckpointSha256: String?,
        workspaceStateSha256: String,
        retainedEvents: Int,
        capturedAtEpochMs: Long
    ): WorkingMemoryContinuityCheckpoint =
        WorkingMemoryContinuityCheckpoint(
            sequence = sequence,
            previousCheckpointSha256 = previousCheckpointSha256,
            workspaceStateSha256 = workspaceStateSha256,
            retainedEvents = retainedEvents,
            capturedAtEpochMs = capturedAtEpochMs,
            checkpointSha256 = checkpointDigest(
                sequence,
                previousCheckpointSha256,
                workspaceStateSha256,
                retainedEvents,
                capturedAtEpochMs
            )
        )

    fun encode(checkpoint: WorkingMemoryContinuityCheckpoint): String =
        listOf(
            VERSION,
            checkpoint.sequence.toString(),
            checkpoint.previousCheckpointSha256 ?: "~",
            checkpoint.workspaceStateSha256,
            checkpoint.retainedEvents.toString(),
            checkpoint.capturedAtEpochMs.toString(),
            checkpoint.checkpointSha256
        ).joinToString("|")

    fun decode(content: String): WorkingMemoryContinuityCheckpoint {
        val fields = content.split('|')
        require(fields.size == 7 && fields[0] == VERSION) {
            "unsupported working-memory continuity checkpoint"
        }
        return WorkingMemoryContinuityCheckpoint(
            sequence = fields[1].toLong(),
            previousCheckpointSha256 = fields[2].takeUnless { it == "~" },
            workspaceStateSha256 = fields[3],
            retainedEvents = fields[4].toInt(),
            capturedAtEpochMs = fields[5].toLong(),
            checkpointSha256 = fields[6]
        )
    }

    fun checkpointDigest(
        sequence: Long,
        previousCheckpointSha256: String?,
        workspaceStateSha256: String,
        retainedEvents: Int,
        capturedAtEpochMs: Long
    ): String =
        sha256(
            listOf(
                VERSION,
                sequence.toString(),
                previousCheckpointSha256 ?: "~",
                workspaceStateSha256,
                retainedEvents.toString(),
                capturedAtEpochMs.toString()
            ).joinToString("|")
        )
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
