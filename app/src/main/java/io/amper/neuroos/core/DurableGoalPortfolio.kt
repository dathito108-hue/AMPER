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
    COMPLETED,
    SUPERSEDED
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
        require(children.all { it.parentGoalId == parent.sourceGoalId })
        require(parent.dependsOnGoalIds.containsAll(children.map { it.sourceGoalId }))
    }

    val authorityBearing: Boolean
        get() = false
}

data class DurableGoalBranchReplacement(
    val parentGoalId: String,
    val superseded: DurableGoalRecord,
    val replacements: List<DurableGoalRecord>
) {
    init {
        require(parentGoalId.isNotBlank())
        require(superseded.status == DurableGoalStatus.SUPERSEDED)
        require(replacements.size in 1..DurableGoalRecord.MAX_ADAPTIVE_REPLACEMENTS)
        require(replacements.all { it.status == DurableGoalStatus.PENDING })
        require(replacements.all { it.parentGoalId == parentGoalId })
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
    val decompositionDepth: Int = 0,
    val parentGoalId: String? = null,
    val supersededAtEpochMs: Long? = null,
    val replanGeneration: Int = 0,
    val adaptiveReplanAttemptedAtEpochMs: Long? = null
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
        parentGoalId?.let {
            require(it.isNotBlank() && it.length <= MAX_GOAL_ID_CHARS)
            require(it != sourceGoalId) { "durable goal cannot be its own hierarchy parent" }
        }
        require(replanGeneration in 0..MAX_ADAPTIVE_REPLAN_GENERATIONS) {
            "durable goal adaptive replan generation exceeds bound"
        }
        adaptiveReplanAttemptedAtEpochMs?.let {
            require(it >= firstSeenAtEpochMs) { "adaptive replan attempt predates first observation" }
        }
        require((status == DurableGoalStatus.COMPLETED) == (completedAtEpochMs != null)) {
            "completed durable goal requires completion timestamp"
        }
        require((status == DurableGoalStatus.SUPERSEDED) == (supersededAtEpochMs != null)) {
            "superseded durable goal requires superseded timestamp"
        }
        require(completedAtEpochMs == null || supersededAtEpochMs == null) {
            "durable goal cannot be both completed and superseded"
        }
        completedAtEpochMs?.let {
            require(it >= firstSeenAtEpochMs)
        }
        supersededAtEpochMs?.let {
            require(it >= firstSeenAtEpochMs)
        }
    }

    companion object {
        const val MAX_GOAL_ID_CHARS = 256
        const val MAX_OBJECTIVE_CHARS = 1_024
        const val MAX_DEPENDENCIES = 8
        const val MAX_DECOMPOSITION_CHILDREN = 4
        const val MAX_DECOMPOSITION_DEPTH = 2
        const val MAX_ADAPTIVE_REPLACEMENTS = 3
        const val MAX_ADAPTIVE_REPLAN_GENERATIONS = 2
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

    fun markAdaptiveReplanAttempt(
        sourceGoalId: String,
        attemptedAtEpochMs: Long = System.currentTimeMillis()
    ): DurableGoalRecord?

    fun replacePendingLeaf(
        sourceGoalId: String,
        specs: List<DurableGoalDecompositionSpec>,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): DurableGoalBranchReplacement?

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
                    existing != null && existing.status != DurableGoalStatus.PENDING -> existing
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
        require(existing.status != DurableGoalStatus.SUPERSEDED) {
            "superseded durable goal cannot be completed by a late result"
        }
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
                decompositionDepth = childDepth,
                parentGoalId = parent.sourceGoalId,
                replanGeneration = parent.replanGeneration
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
    override fun markAdaptiveReplanAttempt(
        sourceGoalId: String,
        attemptedAtEpochMs: Long
    ): DurableGoalRecord? {
        require(sourceGoalId.isNotBlank())
        require(attemptedAtEpochMs >= 0L)
        val records = loadMutable()
        val existing = records[sourceGoalId] ?: return null
        require(existing.status == DurableGoalStatus.PENDING) {
            "adaptive replanning requires a pending durable goal"
        }
        require(existing.parentGoalId != null) {
            "adaptive replanning is limited to hierarchical subgoals"
        }
        require(existing.decompositionState != DurableGoalDecompositionState.DECOMPOSED) {
            "adaptive replanning may replace only a leaf subgoal"
        }
        require(existing.replanGeneration < DurableGoalRecord.MAX_ADAPTIVE_REPLAN_GENERATIONS) {
            "adaptive replanning reached the bounded lineage limit"
        }
        require(existing.adaptiveReplanAttemptedAtEpochMs == null) {
            "adaptive replanning may be attempted only once per leaf"
        }
        require(records.values.none { it.parentGoalId == sourceGoalId }) {
            "adaptive replanning may replace only a hierarchy leaf"
        }

        val marked = existing.copy(
            adaptiveReplanAttemptedAtEpochMs =
                maxOf(existing.firstSeenAtEpochMs, attemptedAtEpochMs),
            updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, attemptedAtEpochMs)
        )
        records[sourceGoalId] = marked
        persist(bounded(records.values))
        return marked
    }

    @Synchronized
    override fun replacePendingLeaf(
        sourceGoalId: String,
        specs: List<DurableGoalDecompositionSpec>,
        updatedAtEpochMs: Long
    ): DurableGoalBranchReplacement? {
        require(sourceGoalId.isNotBlank())
        require(updatedAtEpochMs >= 0L)
        require(specs.size in 1..DurableGoalRecord.MAX_ADAPTIVE_REPLACEMENTS)
        require(specs.map { it.index } == (1..specs.size).toList()) {
            "adaptive replacement indices must be contiguous from 1"
        }

        val records = loadMutable()
        val source = records[sourceGoalId] ?: return null
        require(source.status == DurableGoalStatus.PENDING) {
            "adaptive replacement requires a pending durable goal"
        }
        val parentGoalId = requireNotNull(source.parentGoalId) {
            "adaptive replacement is limited to hierarchical subgoals"
        }
        require(source.decompositionState != DurableGoalDecompositionState.DECOMPOSED) {
            "adaptive replacement may replace only a leaf subgoal"
        }
        require(source.replanGeneration < DurableGoalRecord.MAX_ADAPTIVE_REPLAN_GENERATIONS) {
            "adaptive replacement reached the bounded lineage limit"
        }
        require(source.adaptiveReplanAttemptedAtEpochMs != null) {
            "adaptive replacement requires a persisted replan-attempt marker"
        }
        require(records.values.none { it.parentGoalId == sourceGoalId }) {
            "adaptive replacement may replace only a hierarchy leaf"
        }
        val parent = requireNotNull(records[parentGoalId]) {
            "adaptive replacement hierarchy parent is unavailable"
        }
        require(
            parent.status == DurableGoalStatus.PENDING &&
                parent.decompositionState == DurableGoalDecompositionState.DECOMPOSED
        ) {
            "adaptive replacement hierarchy parent is not active"
        }
        require(
            records.values.none {
                it.status == DurableGoalStatus.COMPLETED &&
                    sourceGoalId in it.dependsOnGoalIds
            }
        ) {
            "adaptive replacement cannot rewrite dependencies of completed evidence"
        }

        val pendingCount = records.values.count { it.status == DurableGoalStatus.PENDING }
        require(pendingCount - 1 + specs.size <= MAX_PENDING) {
            "adaptive replacement would exceed pending portfolio capacity"
        }

        val nextGeneration = source.replanGeneration + 1
        val idsByIndex = specs.associate { spec ->
            spec.index to replacementGoalId(
                sourceGoalId = source.sourceGoalId,
                generation = nextGeneration,
                index = spec.index,
                objective = spec.objective
            )
        }
        require(idsByIndex.values.toSet().size == specs.size) {
            "adaptive replacement produced duplicate durable goal ids"
        }
        require(idsByIndex.values.none(records::containsKey)) {
            "adaptive replacement durable goal id already exists"
        }
        val replacementIds = idsByIndex.values.toSortedSet()
        val replacements = specs.map { spec ->
            val dependencies = (
                source.dependsOnGoalIds +
                    spec.dependsOnIndices.map(idsByIndex::getValue)
                ).toSortedSet()
            require(dependencies.size <= DurableGoalRecord.MAX_DEPENDENCIES) {
                "adaptive replacement would exceed child dependency bound"
            }
            DurableGoalRecord(
                sourceGoalId = idsByIndex.getValue(spec.index),
                objective = spec.objective,
                priority = minOf(parent.priority, source.priority, spec.priority),
                status = DurableGoalStatus.PENDING,
                firstSeenAtEpochMs = updatedAtEpochMs,
                updatedAtEpochMs = updatedAtEpochMs,
                dependsOnGoalIds = dependencies,
                deadlineEpochMs = source.deadlineEpochMs,
                decompositionState = DurableGoalDecompositionState.NONE,
                decompositionDepth = source.decompositionDepth,
                parentGoalId = parentGoalId,
                replanGeneration = nextGeneration
            )
        }

        val rewired = records.values
            .asSequence()
            .filter {
                it.status == DurableGoalStatus.PENDING &&
                    it.sourceGoalId != sourceGoalId &&
                    sourceGoalId in it.dependsOnGoalIds
            }
            .associate { dependent ->
                val dependencies =
                    (dependent.dependsOnGoalIds - sourceGoalId + replacementIds).toSortedSet()
                require(dependencies.size <= DurableGoalRecord.MAX_DEPENDENCIES) {
                    "adaptive replacement would exceed dependent goal dependency bound"
                }
                dependent.sourceGoalId to dependent.copy(
                    dependsOnGoalIds = dependencies,
                    updatedAtEpochMs = maxOf(dependent.updatedAtEpochMs, updatedAtEpochMs)
                )
            }

        rewired.forEach { (id, record) -> records[id] = record }
        val superseded = source.copy(
            status = DurableGoalStatus.SUPERSEDED,
            supersededAtEpochMs = maxOf(source.firstSeenAtEpochMs, updatedAtEpochMs),
            updatedAtEpochMs = maxOf(source.updatedAtEpochMs, updatedAtEpochMs)
        )
        records[sourceGoalId] = superseded
        replacements.forEach { records[it.sourceGoalId] = it }

        require(!hasDependencyCycle(records.values)) {
            "adaptive replacement must preserve an acyclic dependency graph"
        }
        val bounded = bounded(records.values)
        require(bounded.any { it.sourceGoalId == sourceGoalId && it.status == DurableGoalStatus.SUPERSEDED }) {
            "bounded portfolio unexpectedly dropped adaptive-replan tombstone"
        }
        require(replacements.all { replacement ->
            bounded.any { it.sourceGoalId == replacement.sourceGoalId }
        }) {
            "bounded portfolio unexpectedly pruned adaptive replacements"
        }
        persist(bounded)
        return DurableGoalBranchReplacement(
            parentGoalId = parentGoalId,
            superseded = superseded,
            replacements = replacements.map { replacement ->
                requireNotNull(bounded.singleOrNull { it.sourceGoalId == replacement.sourceGoalId })
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

    private fun replacementGoalId(
        sourceGoalId: String,
        generation: Int,
        index: Int,
        objective: String
    ): String {
        val material = "$sourceGoalId\n$generation\n$index\n$objective"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        val suffix = "::replan:$generation:$index:$digest"
        val prefixBudget = DurableGoalRecord.MAX_GOAL_ID_CHARS - suffix.length
        return sourceGoalId.take(prefixBudget.coerceAtLeast(1)) + suffix
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
        val terminal = records
            .asSequence()
            .filter { it.status != DurableGoalStatus.PENDING }
            .sortedWith(
                compareByDescending<DurableGoalRecord> {
                    it.completedAtEpochMs ?: it.supersededAtEpochMs ?: 0L
                }
                    .thenByDescending { it.updatedAtEpochMs }
                    .thenBy { it.sourceGoalId }
            )
            .take(MAX_COMPLETED_HISTORY)
            .toList()
        return pending + terminal
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
    private const val VERSION_V4 = "AMPER_DURABLE_GOAL_PORTFOLIO_V4"
    private const val VERSION = "AMPER_DURABLE_GOAL_PORTFOLIO_V5"

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
            append(record.decompositionDepth).append('\t')
            append(record.parentGoalId?.let(::enc) ?: "~").append('\t')
            append(record.supersededAtEpochMs?.toString() ?: "~").append('\t')
            append(record.replanGeneration).append('\t')
            append(record.adaptiveReplanAttemptedAtEpochMs?.toString() ?: "~")
            appendLine()
        }
    }.trimEnd()

    fun decode(content: String): Result<List<DurableGoalRecord>> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        val version = lines.firstOrNull()
        require(
            version == VERSION ||
                version == VERSION_V4 ||
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
                    require(parts.size == 18) { "invalid V5 durable goal portfolio record" }
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
                        decompositionDepth = parts[13].toInt(),
                        parentGoalId = parts[14].takeUnless { it == "~" }?.let(::dec),
                        supersededAtEpochMs = parts[15].takeUnless { it == "~" }?.toLong(),
                        replanGeneration = parts[16].toInt(),
                        adaptiveReplanAttemptedAtEpochMs =
                            parts[17].takeUnless { it == "~" }?.toLong()
                    )
                }
                VERSION_V4 -> {
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
        val migrated = if (version == VERSION_V4) inferV4HierarchyParents(records) else records
        require(migrated.map { it.sourceGoalId }.toSet().size == migrated.size) {
            "durable goal portfolio ids must be unique"
        }
        require(migrated.count { it.status == DurableGoalStatus.PENDING } <=
            MemoryBackedDurableGoalPortfolio.MAX_PENDING)
        require(migrated.count { it.status != DurableGoalStatus.PENDING } <=
            MemoryBackedDurableGoalPortfolio.MAX_COMPLETED_HISTORY)
        migrated
    }

    private fun inferV4HierarchyParents(
        records: List<DurableGoalRecord>
    ): List<DurableGoalRecord> {
        val parents = records.filter {
            it.decompositionState == DurableGoalDecompositionState.DECOMPOSED
        }
        return records.map { child ->
            if (child.parentGoalId != null || child.decompositionDepth == 0) {
                child
            } else {
                val candidate = parents.singleOrNull { parent ->
                    child.decompositionDepth == parent.decompositionDepth + 1 &&
                        child.sourceGoalId in parent.dependsOnGoalIds &&
                        child.sourceGoalId.contains("::subgoal:${child.decompositionDepth}:")
                }
                if (candidate == null) child else child.copy(parentGoalId = candidate.sourceGoalId)
            }
        }
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
