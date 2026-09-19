package io.amper.neuroos.core

import java.util.ArrayDeque

data class DurableGoalHierarchyProgress(
    val rootGoalId: String,
    val trackedGoalIds: Set<String>,
    val pendingGoalIds: Set<String>,
    val completedGoalIds: Set<String>,
    val supersededGoalIds: Set<String>,
    val runnablePendingGoalIds: Set<String>,
    val blockedPendingGoalIds: Set<String>,
    val completionRatio: Double
) {
    init {
        require(rootGoalId.isNotBlank())
        require(rootGoalId in trackedGoalIds)
        require(completionRatio in 0.0..1.0)
        require(pendingGoalIds.intersect(completedGoalIds).isEmpty())
        require(pendingGoalIds.intersect(supersededGoalIds).isEmpty())
        require(completedGoalIds.intersect(supersededGoalIds).isEmpty())
        require(runnablePendingGoalIds + blockedPendingGoalIds == pendingGoalIds)
    }

    val stalled: Boolean
        get() = pendingGoalIds.isNotEmpty() && runnablePendingGoalIds.isEmpty()

    val authorityBearing: Boolean
        get() = false
}

/**
 * Phase331 deterministic progress projection over the durable hierarchy.
 *
 * Progress is derived from persisted portfolio state only. SUPERSEDED nodes remain visible as
 * lineage tombstones but never contribute to completion ratio. A pending node is runnable only when
 * every durable dependency is present and COMPLETED; missing/pruned/superseded prerequisites block.
 */
object DurableGoalHierarchyProgressPolicy {
    fun snapshot(
        records: Collection<DurableGoalRecord>,
        rootGoalId: String
    ): DurableGoalHierarchyProgress {
        require(rootGoalId.isNotBlank())
        val byId = records.associateBy { it.sourceGoalId }
        require(byId.containsKey(rootGoalId)) {
            "hierarchical progress root is not present in durable portfolio"
        }

        val childrenByParent = records
            .asSequence()
            .filter { it.parentGoalId != null }
            .groupBy { requireNotNull(it.parentGoalId) }

        val trackedIds = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootGoalId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!trackedIds.add(current)) continue
            childrenByParent[current]
                .orEmpty()
                .sortedBy { it.sourceGoalId }
                .forEach { queue.addLast(it.sourceGoalId) }
        }

        val tracked = trackedIds.mapNotNull(byId::get)
        val pending = tracked
            .filter { it.status == DurableGoalStatus.PENDING }
            .mapTo(linkedSetOf()) { it.sourceGoalId }
        val completed = tracked
            .filter { it.status == DurableGoalStatus.COMPLETED }
            .mapTo(linkedSetOf()) { it.sourceGoalId }
        val superseded = tracked
            .filter { it.status == DurableGoalStatus.SUPERSEDED }
            .mapTo(linkedSetOf()) { it.sourceGoalId }

        val runnable = tracked
            .asSequence()
            .filter { it.status == DurableGoalStatus.PENDING }
            .filter { goal ->
                goal.dependsOnGoalIds.all { dependency ->
                    byId[dependency]?.status == DurableGoalStatus.COMPLETED
                }
            }
            .mapTo(linkedSetOf()) { it.sourceGoalId }
        val blocked = (pending - runnable).toCollection(linkedSetOf())

        val activeDenominator = pending.size + completed.size
        val ratio = if (activeDenominator == 0) {
            1.0
        } else {
            completed.size.toDouble() / activeDenominator.toDouble()
        }

        return DurableGoalHierarchyProgress(
            rootGoalId = rootGoalId,
            trackedGoalIds = trackedIds,
            pendingGoalIds = pending,
            completedGoalIds = completed,
            supersededGoalIds = superseded,
            runnablePendingGoalIds = runnable,
            blockedPendingGoalIds = blocked,
            completionRatio = ratio.coerceIn(0.0, 1.0)
        )
    }
}
