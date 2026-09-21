package io.amper.neuroos.core

import java.util.PriorityQueue

internal data class MemoryRetentionPlan(
    val evictions: List<MemoryId>
) {
    init {
        require(evictions.distinct().size == evictions.size)
    }
}

/**
 * Phase677 provenance-safe logical retention planner.
 *
 * The planner mutates nothing. It projects the post-write record set, protects the incoming
 * candidate, and peels only provenance leaves. A parent becomes evictable only after every retained
 * child that references it has itself been selected for eviction.
 */
internal object ProvenanceSafeMemoryRetentionPlanner {
    fun plan(
        existing: Collection<MemoryRecord>,
        candidate: MemoryRecord,
        maxRecords: Int
    ): MemoryRetentionPlan {
        require(maxRecords > 0)

        val projected = linkedMapOf<MemoryId, MemoryRecord>()
        existing.forEach { projected[it.id] = it }
        projected[candidate.id] = candidate

        val overflow = projected.size - maxRecords
        if (overflow <= 0) return MemoryRetentionPlan(emptyList())

        val incomingReferences = projected.keys.associateWith { 0 }.toMutableMap()
        projected.values.forEach { child ->
            child.provenance.parents.forEach { parentId ->
                if (parentId != child.id && parentId in projected) {
                    incomingReferences[parentId] = incomingReferences.getValue(parentId) + 1
                }
            }
        }

        val kept = projected.keys.toMutableSet()
        val queue = PriorityQueue(EVICTION_ORDER)
        projected.values
            .filter {
                it.id != candidate.id &&
                    incomingReferences.getValue(it.id) == 0
            }
            .forEach(queue::add)

        val evictions = mutableListOf<MemoryId>()
        while (evictions.size < overflow) {
            val victim = nextLiveLeaf(
                queue = queue,
                kept = kept,
                incomingReferences = incomingReferences,
                protectedCandidateId = candidate.id
            ) ?: throw IllegalStateException(
                "memory retention cannot satisfy capacity without deleting live provenance evidence"
            )

            kept.remove(victim.id)
            evictions += victim.id

            victim.provenance.parents.forEach { parentId ->
                if (
                    parentId != victim.id &&
                    parentId in kept &&
                    parentId in incomingReferences
                ) {
                    val remaining = incomingReferences.getValue(parentId) - 1
                    require(remaining >= 0) {
                        "memory retention reference count underflow"
                    }
                    incomingReferences[parentId] = remaining
                    if (remaining == 0 && parentId != candidate.id) {
                        queue.add(projected.getValue(parentId))
                    }
                }
            }
        }

        require(candidate.id !in evictions) {
            "memory retention must never auto-evict the incoming record"
        }
        return MemoryRetentionPlan(evictions)
    }

    fun blockers(
        records: Collection<MemoryRecord>,
        targetId: MemoryId
    ): List<MemoryId> =
        records.asSequence()
            .filter { record ->
                record.id != targetId &&
                    targetId in record.provenance.parents
            }
            .map { it.id }
            .distinct()
            .sortedBy { it.value }
            .toList()

    private fun nextLiveLeaf(
        queue: PriorityQueue<MemoryRecord>,
        kept: Set<MemoryId>,
        incomingReferences: Map<MemoryId, Int>,
        protectedCandidateId: MemoryId
    ): MemoryRecord? {
        while (queue.isNotEmpty()) {
            val candidate = queue.remove()
            if (
                candidate.id in kept &&
                candidate.id != protectedCandidateId &&
                incomingReferences.getValue(candidate.id) == 0
            ) {
                return candidate
            }
        }
        return null
    }

    private val EVICTION_ORDER =
        compareBy<MemoryRecord> { it.importance }
            .thenBy { it.createdAtEpochMs }
            .thenBy { it.id.value }
}
