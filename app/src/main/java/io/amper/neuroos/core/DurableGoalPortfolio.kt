package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64

data class DurableGoalCandidate(
    val sourceGoalId: String,
    val objective: String,
    val priority: Double
) {
    init {
        require(sourceGoalId.isNotBlank() && sourceGoalId.length <= DurableGoalRecord.MAX_GOAL_ID_CHARS)
        require(objective.isNotBlank() && objective.length <= DurableGoalRecord.MAX_OBJECTIVE_CHARS)
        require(priority in 0.0..1.0)
    }
}

enum class DurableGoalStatus {
    PENDING,
    COMPLETED
}

data class DurableGoalRecord(
    val sourceGoalId: String,
    val objective: String,
    val priority: Double,
    val status: DurableGoalStatus,
    val firstSeenAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val completedAtEpochMs: Long? = null
) {
    init {
        require(sourceGoalId.isNotBlank() && sourceGoalId.length <= MAX_GOAL_ID_CHARS)
        require(objective.isNotBlank() && objective.length <= MAX_OBJECTIVE_CHARS)
        require(priority in 0.0..1.0)
        require(firstSeenAtEpochMs >= 0L)
        require(updatedAtEpochMs >= firstSeenAtEpochMs)
        require((status == DurableGoalStatus.COMPLETED) == (completedAtEpochMs != null)) {
            "completed durable goal requires completion timestamp"
        }
        completedAtEpochMs?.let {
            require(it >= firstSeenAtEpochMs)
        }
    }

    companion object {
        const val MAX_GOAL_ID_CHARS = 256
        const val MAX_OBJECTIVE_CHARS = 1_024
    }
}

interface DurableGoalPortfolio {
    fun observe(
        candidates: Collection<DurableGoalCandidate>,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): List<DurableGoalRecord>

    fun pending(limit: Int = MemoryBackedDurableGoalPortfolio.MAX_PENDING): List<DurableGoalRecord>

    fun markCompleted(
        sourceGoalId: String,
        completedAtEpochMs: Long = System.currentTimeMillis()
    ): DurableGoalRecord?

    fun get(sourceGoalId: String): DurableGoalRecord?

    fun snapshot(): List<DurableGoalRecord>
}

/**
 * Phase311-315 durable multi-goal portfolio.
 *
 * The canonical GoalSystem remains the live in-memory alignment surface. This portfolio mirrors
 * normalized active goals into Memory OS so pending objectives survive Android process restart.
 * A completed record is a tombstone: observing the same source goal again does not resurrect it.
 *
 * The portfolio is deliberately bounded. Up to [MAX_PENDING] pending goals and
 * [MAX_COMPLETED_HISTORY] recent completion tombstones are retained.
 */
class MemoryBackedDurableGoalPortfolio(
    private val memory: MemoryOs
) : DurableGoalPortfolio {
    @Synchronized
    override fun observe(
        candidates: Collection<DurableGoalCandidate>,
        observedAtEpochMs: Long
    ): List<DurableGoalRecord> {
        require(observedAtEpochMs >= 0L)
        val records = loadMutable()
        candidates
            .distinctBy { it.sourceGoalId }
            .forEach { candidate ->
                val existing = records[candidate.sourceGoalId]
                records[candidate.sourceGoalId] = when {
                    existing?.status == DurableGoalStatus.COMPLETED -> existing
                    existing != null -> existing.copy(
                        objective = candidate.objective,
                        priority = candidate.priority,
                        updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, observedAtEpochMs)
                    )
                    else -> DurableGoalRecord(
                        sourceGoalId = candidate.sourceGoalId,
                        objective = candidate.objective,
                        priority = candidate.priority,
                        status = DurableGoalStatus.PENDING,
                        firstSeenAtEpochMs = observedAtEpochMs,
                        updatedAtEpochMs = observedAtEpochMs
                    )
                }
            }
        val bounded = bounded(records.values)
        persist(bounded)
        return bounded
    }

    @Synchronized
    override fun pending(limit: Int): List<DurableGoalRecord> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return snapshot()
            .asSequence()
            .filter { it.status == DurableGoalStatus.PENDING }
            .sortedWith(
                compareByDescending<DurableGoalRecord> { it.priority }
                    .thenBy { it.firstSeenAtEpochMs }
                    .thenBy { it.sourceGoalId }
            )
            .take(limit)
            .toList()
    }

    @Synchronized
    override fun markCompleted(
        sourceGoalId: String,
        completedAtEpochMs: Long
    ): DurableGoalRecord? {
        require(sourceGoalId.isNotBlank())
        require(completedAtEpochMs >= 0L)
        val records = loadMutable()
        val existing = records[sourceGoalId] ?: return null
        val completed = if (existing.status == DurableGoalStatus.COMPLETED) {
            existing
        } else {
            existing.copy(
                status = DurableGoalStatus.COMPLETED,
                updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, completedAtEpochMs),
                completedAtEpochMs = maxOf(existing.firstSeenAtEpochMs, completedAtEpochMs)
            )
        }
        records[sourceGoalId] = completed
        persist(bounded(records.values))
        return completed
    }

    @Synchronized
    override fun get(sourceGoalId: String): DurableGoalRecord? =
        snapshot().singleOrNull { it.sourceGoalId == sourceGoalId }

    @Synchronized
    override fun snapshot(): List<DurableGoalRecord> =
        memory.get(INDEX_ID)
            ?.takeIf { it.kind == INDEX_KIND }
            ?.let { DurableGoalPortfolioCodec.decode(it.content).getOrNull() }
            .orEmpty()

    private fun loadMutable(): LinkedHashMap<String, DurableGoalRecord> =
        linkedMapOf<String, DurableGoalRecord>().also { target ->
            snapshot().forEach { target[it.sourceGoalId] = it }
        }

    private fun bounded(records: Collection<DurableGoalRecord>): List<DurableGoalRecord> {
        val pending = records
            .asSequence()
            .filter { it.status == DurableGoalStatus.PENDING }
            .sortedWith(
                compareByDescending<DurableGoalRecord> { it.priority }
                    .thenBy { it.firstSeenAtEpochMs }
                    .thenBy { it.sourceGoalId }
            )
            .take(MAX_PENDING)
            .toList()
        val completed = records
            .asSequence()
            .filter { it.status == DurableGoalStatus.COMPLETED }
            .sortedWith(
                compareByDescending<DurableGoalRecord> { it.completedAtEpochMs ?: 0L }
                    .thenByDescending { it.updatedAtEpochMs }
                    .thenBy { it.sourceGoalId }
            )
            .take(MAX_COMPLETED_HISTORY)
            .toList()
        return pending + completed
    }

    private fun persist(records: List<DurableGoalRecord>) {
        memory.remember(
            MemoryRecord(
                id = INDEX_ID,
                kind = INDEX_KIND,
                content = DurableGoalPortfolioCodec.encode(records),
                importance = 0.94,
                provenance = Provenance(
                    source = "durable-goal-portfolio",
                    producer = "goal-portfolio-store",
                    confidence = 1.0
                )
            )
        )
    }

    companion object {
        const val INDEX_KIND = "durable-goal-portfolio-v1"
        const val MAX_PENDING = 32
        const val MAX_COMPLETED_HISTORY = 16
        private val INDEX_ID = MemoryId("durable-goal-portfolio:index")
    }
}

internal object DurableGoalPortfolioCodec {
    private const val VERSION = "AMPER_DURABLE_GOAL_PORTFOLIO_V1"

    fun encode(records: List<DurableGoalRecord>): String = buildString {
        appendLine(VERSION)
        records.forEach { record ->
            append("GOAL\t")
            append(enc(record.sourceGoalId)).append('\t')
            append(enc(record.objective)).append('\t')
            append(record.priority).append('\t')
            append(record.status.name).append('\t')
            append(record.firstSeenAtEpochMs).append('\t')
            append(record.updatedAtEpochMs).append('\t')
            append(record.completedAtEpochMs?.toString() ?: "~")
            appendLine()
        }
    }.trimEnd()

    fun decode(content: String): Result<List<DurableGoalRecord>> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == VERSION) { "unsupported durable goal portfolio state" }
        val records = lines.drop(1).map { line ->
            val parts = line.split('\t')
            require(parts.size == 8 && parts[0] == "GOAL") {
                "invalid durable goal portfolio record"
            }
            DurableGoalRecord(
                sourceGoalId = dec(parts[1]),
                objective = dec(parts[2]),
                priority = parts[3].toDouble(),
                status = DurableGoalStatus.valueOf(parts[4]),
                firstSeenAtEpochMs = parts[5].toLong(),
                updatedAtEpochMs = parts[6].toLong(),
                completedAtEpochMs = parts[7].takeUnless { it == "~" }?.toLong()
            )
        }
        require(records.map { it.sourceGoalId }.toSet().size == records.size) {
            "durable goal portfolio ids must be unique"
        }
        require(records.count { it.status == DurableGoalStatus.PENDING } <=
            MemoryBackedDurableGoalPortfolio.MAX_PENDING)
        require(records.count { it.status == DurableGoalStatus.COMPLETED } <=
            MemoryBackedDurableGoalPortfolio.MAX_COMPLETED_HISTORY)
        records
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
