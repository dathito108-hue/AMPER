package io.amper.neuroos.core

data class DurableGoalArbitration(
    val goal: DurableGoalRecord,
    val arbitrationScore: Double,
    val deadlineUrgency: Double,
    val blockedByGoalIds: Set<String> = emptySet()
) {
    init {
        require(arbitrationScore in 0.0..1.0)
        require(deadlineUrgency in 0.0..1.0)
        require(blockedByGoalIds.none { it.isBlank() })
    }

    val runnable: Boolean
        get() = goal.status == DurableGoalStatus.PENDING && blockedByGoalIds.isEmpty()

    val authorityBearing: Boolean
        get() = false
}

/**
 * Phase316-320 deterministic durable-goal arbitration.
 *
 * Dependency completion is a hard prerequisite. Deadline urgency is advisory scheduling evidence
 * only: it may reorder already-runnable goals but cannot bypass dependencies, authority, approval,
 * resource admission, or the governed plan runner.
 */
object DurableGoalArbitrationPolicy {
    const val DEADLINE_HORIZON_MS = 24L * 60L * 60L * 1_000L
    private const val PRIORITY_WEIGHT = 0.75
    private const val DEADLINE_WEIGHT = 0.25

    fun evaluate(
        records: Collection<DurableGoalRecord>,
        nowEpochMs: Long
    ): List<DurableGoalArbitration> {
        require(nowEpochMs >= 0L)
        val byId = records.associateBy { it.sourceGoalId }

        return records
            .asSequence()
            .filter { it.status == DurableGoalStatus.PENDING }
            .map { goal ->
                val blocked = goal.dependsOnGoalIds
                    .filterTo(linkedSetOf()) { dependencyId ->
                        byId[dependencyId]?.status != DurableGoalStatus.COMPLETED
                    }
                val urgency = deadlineUrgency(goal.deadlineEpochMs, nowEpochMs)
                DurableGoalArbitration(
                    goal = goal,
                    arbitrationScore = (
                        goal.priority * PRIORITY_WEIGHT +
                            urgency * DEADLINE_WEIGHT
                        ).coerceIn(0.0, 1.0),
                    deadlineUrgency = urgency,
                    blockedByGoalIds = blocked
                )
            }
            .sortedWith(
                compareByDescending<DurableGoalArbitration> { it.runnable }
                    .thenByDescending { it.arbitrationScore }
                    .thenBy { it.goal.deadlineEpochMs ?: Long.MAX_VALUE }
                    .thenByDescending { it.goal.priority }
                    .thenBy { it.goal.firstSeenAtEpochMs }
                    .thenBy { it.goal.sourceGoalId }
            )
            .toList()
    }

    fun select(
        records: Collection<DurableGoalRecord>,
        nowEpochMs: Long,
        excludedGoalId: String? = null
    ): DurableGoalArbitration? =
        evaluate(records, nowEpochMs)
            .firstOrNull {
                it.runnable && it.goal.sourceGoalId != excludedGoalId
            }

    fun deadlineUrgency(
        deadlineEpochMs: Long?,
        nowEpochMs: Long
    ): Double {
        require(nowEpochMs >= 0L)
        if (deadlineEpochMs == null) return 0.0
        require(deadlineEpochMs >= 0L)

        val remaining = deadlineEpochMs - nowEpochMs
        if (remaining <= 0L) return 1.0
        if (remaining >= DEADLINE_HORIZON_MS) return 0.0
        return (1.0 - remaining.toDouble() / DEADLINE_HORIZON_MS.toDouble())
            .coerceIn(0.0, 1.0)
    }
}
