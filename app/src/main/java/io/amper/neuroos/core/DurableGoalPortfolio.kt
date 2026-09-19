package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

enum class DurableGoalDecompositionState {
    NONE,
    ATOMIC,
    DECOMPOSED
}

data class DurableGoalDecompositionSpec(
    val index: Int,
    val objective: String,
    val priority: Double,
    val dependsOnIndices: Set<Int> = emptySet()
) {
    init {
        require(index in 1..DurableGoalRecord.MAX_DECOMPOSITION_CHILDREN)
        require(objective.isNotBlank() && objective.length <= DurableGoalRecord.MAX_OBJECTIVE_CHARS)
        require(priority in 0.0..1.0)
        require(dependsOnIndices.size <= DurableGoalRecord.MAX_DECOMPOSITION_CHILDREN)
        require(dependsOnIndices.all { it in 1 until index }) {
            "decomposition dependencies may only reference earlier subgoals"
        }
    }
}

data class DurableGoalDecompositionApplication(
    val parent: DurableGoalRecord,
    val children: List<DurableGoalRecord>
) {
    init {
        require(parent.decompositionState == DurableGoalDecompositionState.DECOMPOSED)
        require(children.size in 2..DurableGoalRecord.MAX_DECOMPOSITION_CHILDREN)
        require(children.all { it.decompositionDepth == parent.decompositionDepth + 1 })
        require(parent.dependsOnGoalIds.containsAll(children.map { it.sourceGoalId }))
    }

    val authorityBearing: Boolean
        get() = false
}

data class DurableGoalRecord(
    val sourceGoalId: String,
    val objective: String,
    val priority: Double,
    val status: DurableGoalStatus,
    val firstSeenAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val selectionCount: Int = 0,
    val lastSelectedAtEpochMs: Long? = null,
    val dependsOnGoalIds: Set<String> = emptySet(),
    val deadlineEpochMs: Long? = null,
    val decompositionState: DurableGoalDecompositionState = DurableGoalDecompositionState.NONE,
    val decompositionDepth: Int = 0
) {
    init {
        require(sourceGoalId.isNotBlank() && sourceGoalId.length <= MAX_GOAL_ID_CHARS)
        require(objective.isNotBlank() && objective.length <= MAX_OBJECTIVE_CHARS)
        require(priority in 0.0..1.0)
        require(firstSeenAtEpochMs >= 0L)
        require(updatedAtEpochMs >= firstSeenAtEpochMs)
        require(selectionCount >= 0)
        require((selectionCount == 0) == (lastSelectedAtEpochMs == null)) {
            "durable goal selection count/timestamp must be present together"
        }
        lastSelectedAtEpochMs?.let {
            require(it >= firstSeenAtEpochMs) { "durable goal selection predates first observation" }
        }
        require(dependsOnGoalIds.size <= MAX_DEPENDENCIES) {
            "durable goal dependency count exceeds bound"
        }
        dependsOnGoalIds.forEach { dependency ->
            require(dependency.isNotBlank() && dependency.length <= MAX_GOAL_ID_CHARS)
            require(dependency != sourceGoalId) { "durable goal cannot depend on itself" }
        }
        deadlineEpochMs?.let { require(it >= 0L) }
        require(decompositionDepth in 0..MAX_DECOMPOSITION_DEPTH) {
            "durable goal decomposition depth exceeds bound"
        }
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
        const val MAX_DEPENDENCIES = 8
        const val MAX_DECOMPOSITION_CHILDREN = 4
        const val MAX_DECOMPOSITION_DEPTH = 2
    }
}

interface DurableGoalPortfolio {
    fun observe(
        candidates: Collection<DurableGoalCandidate>,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): List<DurableGoalRecord>

    fun pending(limit: Int = MemoryBackedDurableGoalPortfolio.MAX_PENDING): List<DurableGoalRecord>

    fun selectNext(
        excludedGoalId: String? = null,
        selectedAtEpochMs: Long = System.currentTimeMillis()
    ): DurableGoalRecord?

    fun markCompleted(
        sourceGoalId: String,
        completedAtEpochMs: Long = System.currentTimeMillis()
    ): DurableGoalRecord?

    fun setDependencies(
        sourceGoalId: String,
        dependsOnGoalIds: Set<String>,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): DurableGoalRecord?

    fun setDeadline(
        sourceGoalId: String,
        deadlineEpochMs: Long?,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): DurableGoalRecord?

    fun markAtomic(
        sourceGoalId: String,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): DurableGoalRecord?

    fun applyDecomposition(
        sourceGoalId: String,
        specs: List<DurableGoalDecompositionSpec>,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): DurableGoalDecompositionApplication?

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
    override fun selectNext(
        excludedGoalId: String?,
        selectedAtEpochMs: Long
    ): DurableGoalRecord? {
        require(selectedAtEpochMs >= 0L)
        val records = loadMutable()
        val selected = DurableGoalArbitrationPolicy.select(
            records = records.values,
            nowEpochMs = selectedAtEpochMs,
            excludedGoalId = excludedGoalId
        )?.goal ?: return null

        val updated = selected.copy(
            selectionCount = selected.selectionCount + 1,
            lastSelectedAtEpochMs = maxOf(selected.firstSeenAtEpochMs, selectedAtEpochMs),
            updatedAtEpochMs = maxOf(selected.updatedAtEpochMs, selectedAtEpochMs)
        )
        records[selected.sourceGoalId] = updated
        persist(bounded(records.values))
        return updated
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
    override fun setDependencies(
        sourceGoalId: String,
        dependsOnGoalIds: Set<String>,
        updatedAtEpochMs: Long
    ): DurableGoalRecord? {
        require(sourceGoalId.isNotBlank())
        require(updatedAtEpochMs >= 0L)
        require(dependsOnGoalIds.size <= DurableGoalRecord.MAX_DEPENDENCIES)
        require(sourceGoalId !in dependsOnGoalIds)

        val records = loadMutable()
        val existing = records[sourceGoalId] ?: return null
        require(existing.status == DurableGoalStatus.PENDING) {
            "completed durable goal dependency metadata is immutable"
        }
        dependsOnGoalIds.forEach { dependency ->
            require(records.containsKey(dependency)) {
                "durable goal dependency is not present in portfolio: $dependency"
            }
        }

        records[sourceGoalId] = existing.copy(
            dependsOnGoalIds = dependsOnGoalIds.toSortedSet(),
            updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, updatedAtEpochMs)
        )
        require(!hasDependencyCycle(records.values)) {
            "durable goal dependency graph must remain acyclic"
        }

        val bounded = bounded(records.values)
        persist(bounded)
        return bounded.singleOrNull { it.sourceGoalId == sourceGoalId }
    }

    @Synchronized
    override fun setDeadline(
        sourceGoalId: String,
        deadlineEpochMs: Long?,
        updatedAtEpochMs: Long
    ): DurableGoalRecord? {
        require(sourceGoalId.isNotBlank())
        require(updatedAtEpochMs >= 0L)
        deadlineEpochMs?.let { require(it >= 0L) }

        val records = loadMutable()
        val existing = records[sourceGoalId] ?: return null
        require(existing.status == DurableGoalStatus.PENDING) {
            "completed durable goal deadline metadata is immutable"
        }

        records[sourceGoalId] = existing.copy(
            deadlineEpochMs = deadlineEpochMs,
            updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, updatedAtEpochMs)
        )
        val bounded = bounded(records.values)
        persist(bounded)
        return bounded.singleOrNull { it.sourceGoalId == sourceGoalId }
    }

    @Synchronized
    override fun markAtomic(
        sourceGoalId: String,
        updatedAtEpochMs: Long
    ): DurableGoalRecord? {
        require(sourceGoalId.isNotBlank())
        require(updatedAtEpochMs >= 0L)
        val records = loadMutable()
        val existing = records[sourceGoalId] ?: return null
        require(existing.status == DurableGoalStatus.PENDING) {
            "completed durable goal decomposition metadata is immutable"
        }
        val atomic = when (existing.decompositionState) {
            DurableGoalDecompositionState.NONE -> existing.copy(
                decompositionState = DurableGoalDecompositionState.ATOMIC,
                updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, updatedAtEpochMs)
            )
            DurableGoalDecompositionState.ATOMIC -> existing
            DurableGoalDecompositionState.DECOMPOSED ->
                error("decomposed durable goal cannot be reclassified atomic")
        }
        records[sourceGoalId] = atomic
        val bounded = bounded(records.values)
        persist(bounded)
        return bounded.singleOrNull { it.sourceGoalId == sourceGoalId }
    }

    @Synchronized
    override fun applyDecomposition(
        sourceGoalId: String,
        specs: List<DurableGoalDecompositionSpec>,
        updatedAtEpochMs: Long
    ): DurableGoalDecompositionApplication? {
        require(sourceGoalId.isNotBlank())
        require(updatedAtEpochMs >= 0L)
        require(specs.size in 2..DurableGoalRecord.MAX_DECOMPOSITION_CHILDREN)
        require(specs.map { it.index } == (1..specs.size).toList()) {
            "decomposition subgoal indices must be contiguous from 1"
        }

        val records = loadMutable()
        val parent = records[sourceGoalId] ?: return null
        require(parent.status == DurableGoalStatus.PENDING) {
            "completed durable goal cannot be decomposed"
        }
        require(parent.decompositionState == DurableGoalDecompositionState.NONE) {
            "durable goal decomposition may be applied only once"
        }
        require(parent.decompositionDepth < DurableGoalRecord.MAX_DECOMPOSITION_DEPTH) {
            "durable goal reached maximum decomposition depth"
        }
        val pendingCount = records.values.count { it.status == DurableGoalStatus.PENDING }
        require(pendingCount + specs.size <= MAX_PENDING) {
            "durable goal decomposition would exceed pending portfolio capacity"
        }

        val childDepth = parent.decompositionDepth + 1
        val idsByIndex = specs.associate { spec ->
            spec.index to childGoalId(
                parentGoalId = parent.sourceGoalId,
                depth = childDepth,
                index = spec.index,
                objective = spec.objective
            )
        }
        require(idsByIndex.values.toSet().size == specs.size) {
            "durable goal decomposition produced duplicate child ids"
        }
        require(idsByIndex.values.none(records::containsKey)) {
            "durable goal decomposition child id already exists"
        }

        val children = specs.map { spec ->
            DurableGoalRecord(
                sourceGoalId = idsByIndex.getValue(spec.index),
                objective = spec.objective,
                priority = minOf(parent.priority, spec.priority),
                status = DurableGoalStatus.PENDING,
                firstSeenAtEpochMs = updatedAtEpochMs,
                updatedAtEpochMs = updatedAtEpochMs,
                dependsOnGoalIds = spec.dependsOnIndices
                    .map(idsByIndex::getValue)
                    .toSortedSet(),
                deadlineEpochMs = parent.deadlineEpochMs,
                decompositionState = DurableGoalDecompositionState.NONE,
                decompositionDepth = childDepth
            )
        }
        val parentDependencies = (parent.dependsOnGoalIds + children.map { it.sourceGoalId })
            .toSortedSet()
        require(parentDependencies.size <= DurableGoalRecord.MAX_DEPENDENCIES) {
            "durable goal decomposition would exceed parent dependency bound"
        }

        children.forEach { records[it.sourceGoalId] = it }
        val decomposedParent = parent.copy(
            dependsOnGoalIds = parentDependencies,
            decompositionState = DurableGoalDecompositionState.DECOMPOSED,
            updatedAtEpochMs = maxOf(parent.updatedAtEpochMs, updatedAtEpochMs)
        )
        records[sourceGoalId] = decomposedParent
        require(!hasDependencyCycle(records.values)) {
            "durable goal decomposition must preserve an acyclic dependency graph"
        }

        val bounded = bounded(records.values)
        require(children.all { child -> bounded.any { it.sourceGoalId == child.sourceGoalId } }) {
            "bounded portfolio unexpectedly pruned decomposition children"
        }
        persist(bounded)
        return DurableGoalDecompositionApplication(
            parent = requireNotNull(bounded.singleOrNull { it.sourceGoalId == sourceGoalId }),
            children = children.map { child ->
                requireNotNull(bounded.singleOrNull { it.sourceGoalId == child.sourceGoalId })
            }
        )
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

    private fun hasDependencyCycle(records: Collection<DurableGoalRecord>): Boolean {
        val byId = records.associateBy { it.sourceGoalId }
        val visiting = linkedSetOf<String>()
        val visited = linkedSetOf<String>()

        fun visit(id: String): Boolean {
            if (id in visited) return false
            if (!visiting.add(id)) return true
            val record = byId[id]
            if (record != null) {
                record.dependsOnGoalIds.forEach { dependency ->
                    if (dependency in byId && visit(dependency)) return true
                }
            }
            visiting.remove(id)
            visited.add(id)
            return false
        }

        return byId.keys.any(::visit)
    }

    private fun childGoalId(
        parentGoalId: String,
        depth: Int,
        index: Int,
        objective: String
    ): String {
        val material = "$parentGoalId\n$depth\n$index\n$objective"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        val suffix = "::subgoal:$depth:$index:$digest"
        val prefixBudget = DurableGoalRecord.MAX_GOAL_ID_CHARS - suffix.length
        return parentGoalId.take(prefixBudget.coerceAtLeast(1)) + suffix
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
        const val MAX_COMPLETED_HISTORY = 64
        const val STARVATION_THRESHOLD_MS = 24L * 60L * 60L * 1_000L
        private val INDEX_ID = MemoryId("durable-goal-portfolio:index")
    }
}

internal object DurableGoalPortfolioCodec {
    private const val VERSION_V1 = "AMPER_DURABLE_GOAL_PORTFOLIO_V1"
    private const val VERSION_V2 = "AMPER_DURABLE_GOAL_PORTFOLIO_V2"
    private const val VERSION_V3 = "AMPER_DURABLE_GOAL_PORTFOLIO_V3"
    private const val VERSION = "AMPER_DURABLE_GOAL_PORTFOLIO_V4"

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
            append(record.completedAtEpochMs?.toString() ?: "~").append('\t')
            append(record.selectionCount).append('\t')
            append(record.lastSelectedAtEpochMs?.toString() ?: "~").append('\t')
            append(
                if (record.dependsOnGoalIds.isEmpty()) {
                    "~"
                } else {
                    enc(record.dependsOnGoalIds.sorted().joinToString("\n"))
                }
            ).append('\t')
            append(record.deadlineEpochMs?.toString() ?: "~").append('\t')
            append(record.decompositionState.name).append('\t')
            append(record.decompositionDepth)
            appendLine()
        }
    }.trimEnd()

    fun decode(content: String): Result<List<DurableGoalRecord>> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        val version = lines.firstOrNull()
        require(
            version == VERSION ||
                version == VERSION_V3 ||
                version == VERSION_V2 ||
                version == VERSION_V1
        ) {
            "unsupported durable goal portfolio state"
        }
        val records = lines.drop(1).map { line ->
            val parts = line.split('\t')
            require(parts[0] == "GOAL") { "invalid durable goal portfolio record" }
            when (version) {
                VERSION -> {
                    require(parts.size == 14) { "invalid V4 durable goal portfolio record" }
                    DurableGoalRecord(
                        sourceGoalId = dec(parts[1]),
                        objective = dec(parts[2]),
                        priority = parts[3].toDouble(),
                        status = DurableGoalStatus.valueOf(parts[4]),
                        firstSeenAtEpochMs = parts[5].toLong(),
                        updatedAtEpochMs = parts[6].toLong(),
                        completedAtEpochMs = parts[7].takeUnless { it == "~" }?.toLong(),
                        selectionCount = parts[8].toInt(),
                        lastSelectedAtEpochMs = parts[9].takeUnless { it == "~" }?.toLong(),
                        dependsOnGoalIds = parts[10]
                            .takeUnless { it == "~" }
                            ?.let(::dec)
                            ?.lineSequence()
                            ?.filter { it.isNotBlank() }
                            ?.toSet()
                            .orEmpty(),
                        deadlineEpochMs = parts[11].takeUnless { it == "~" }?.toLong(),
                        decompositionState = DurableGoalDecompositionState.valueOf(parts[12]),
                        decompositionDepth = parts[13].toInt()
                    )
                }
                VERSION_V3 -> {
                    require(parts.size == 12) { "invalid V3 durable goal portfolio record" }
                    DurableGoalRecord(
                        sourceGoalId = dec(parts[1]),
                        objective = dec(parts[2]),
                        priority = parts[3].toDouble(),
                        status = DurableGoalStatus.valueOf(parts[4]),
                        firstSeenAtEpochMs = parts[5].toLong(),
                        updatedAtEpochMs = parts[6].toLong(),
                        completedAtEpochMs = parts[7].takeUnless { it == "~" }?.toLong(),
                        selectionCount = parts[8].toInt(),
                        lastSelectedAtEpochMs = parts[9].takeUnless { it == "~" }?.toLong(),
                        dependsOnGoalIds = parts[10]
                            .takeUnless { it == "~" }
                            ?.let(::dec)
                            ?.lineSequence()
                            ?.filter { it.isNotBlank() }
                            ?.toSet()
                            .orEmpty(),
                        deadlineEpochMs = parts[11].takeUnless { it == "~" }?.toLong()
                    )
                }
                VERSION_V2 -> {
                    require(parts.size == 10) { "invalid V2 durable goal portfolio record" }
                    DurableGoalRecord(
                        sourceGoalId = dec(parts[1]),
                        objective = dec(parts[2]),
                        priority = parts[3].toDouble(),
                        status = DurableGoalStatus.valueOf(parts[4]),
                        firstSeenAtEpochMs = parts[5].toLong(),
                        updatedAtEpochMs = parts[6].toLong(),
                        completedAtEpochMs = parts[7].takeUnless { it == "~" }?.toLong(),
                        selectionCount = parts[8].toInt(),
                        lastSelectedAtEpochMs = parts[9].takeUnless { it == "~" }?.toLong()
                    )
                }
                VERSION_V1 -> {
                    require(parts.size == 8) { "invalid V1 durable goal portfolio record" }
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
                else -> error("unsupported durable goal portfolio version")
            }
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
