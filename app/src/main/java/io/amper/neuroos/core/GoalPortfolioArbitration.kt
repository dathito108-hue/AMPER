package io.amper.neuroos.core

data class DurableGoalArbitration(
    val goal: DurableGoalRecord,
    val arbitrationScore: Double,
    val deadlineUrgency: Double,
    val waitingAgeMs: Long,
    val starved: Boolean,
    val blockedByGoalIds: Set<String> = emptySet()
) {
    init {
        require(arbitrationScore in 0.0..1.0)
        require(deadlineUrgency in 0.0..1.0)
        require(waitingAgeMs >= 0L)
        require(blockedByGoalIds.none { it.isBlank() })
    }

    val runnable: Boolean
        get() = goal.status == DurableGoalStatus.PENDING && blockedByGoalIds.isEmpty()

    val authorityBearing: Boolean
        get() = false
}

/**
 * Phase321-325 deterministic dependency/deadline/fairness arbitration.
 *
 * Dependencies are a hard prerequisite. Among runnable goals, the Phase316-320 hard starvation lane
 * remains strongest. Only when no runnable goal is starved does deadline urgency combine with durable
 * priority. Scheduling evidence cannot bypass authority, approvals, resource admission or plan
 * execution governance.
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
                val waitingAge = waitingAgeMs(goal, nowEpochMs)
                DurableGoalArbitration(
                    goal = goal,
                    arbitrationScore = (
                        goal.priority * PRIORITY_WEIGHT +
                            urgency * DEADLINE_WEIGHT
                        ).coerceIn(0.0, 1.0),
                    deadlineUrgency = urgency,
                    waitingAgeMs = waitingAge,
                    starved =
                        waitingAge >= MemoryBackedDurableGoalPortfolio.STARVATION_THRESHOLD_MS,
                    blockedByGoalIds = blocked
                )
            }
            .sortedWith(
                compareByDescending<DurableGoalArbitration> { it.runnable }
                    .thenByDescending { it.starved }
                    .thenByDescending { it.arbitrationScore }
                    .thenBy { it.goal.deadlineEpochMs ?: Long.MAX_VALUE }
                    .thenByDescending { it.goal.priority }
                    .thenBy { waitAnchorEpochMs(it.goal) }
                    .thenBy { it.goal.sourceGoalId }
            )
            .toList()
    }

    fun select(
        records: Collection<DurableGoalRecord>,
        nowEpochMs: Long,
        excludedGoalId: String? = null
    ): DurableGoalArbitration? {
        val runnable = evaluate(records, nowEpochMs)
            .filter {
                it.runnable && it.goal.sourceGoalId != excludedGoalId
            }
        if (runnable.isEmpty()) return null

        val starved = runnable.filter { it.starved }
        return if (starved.isNotEmpty()) {
            starved.sortedWith(
                compareBy<DurableGoalArbitration> { waitAnchorEpochMs(it.goal) }
                    .thenByDescending { it.arbitrationScore }
                    .thenBy { it.goal.deadlineEpochMs ?: Long.MAX_VALUE }
                    .thenByDescending { it.goal.priority }
                    .thenBy { it.goal.sourceGoalId }
            ).first()
        } else {
            runnable.sortedWith(
                compareByDescending<DurableGoalArbitration> { it.arbitrationScore }
                    .thenBy { it.goal.deadlineEpochMs ?: Long.MAX_VALUE }
                    .thenByDescending { it.goal.priority }
                    .thenBy { waitAnchorEpochMs(it.goal) }
                    .thenBy { it.goal.sourceGoalId }
            ).first()
        }
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

    private fun waitAnchorEpochMs(goal: DurableGoalRecord): Long =
        goal.lastSelectedAtEpochMs ?: goal.firstSeenAtEpochMs

    private fun waitingAgeMs(goal: DurableGoalRecord, nowEpochMs: Long): Long =
        (nowEpochMs - waitAnchorEpochMs(goal)).coerceAtLeast(0L)
}
