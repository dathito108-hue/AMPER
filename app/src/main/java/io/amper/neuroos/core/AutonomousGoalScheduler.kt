package io.amper.neuroos.core

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AutonomousGoalSchedulerStage {
    RAN,
    PLAN_PROGRESS,
    DEFERRED,
    NO_GOAL,
    RESOURCE_GATED,
    THROTTLED,
    BUSY,
    FAILED
}

data class AutonomousGoalSchedulerTick(
    val stage: AutonomousGoalSchedulerStage,
    val observedAtEpochMs: Long,
    val nextDelayMs: Long,
    val checkpointStage: PersistentGoalExecutiveStage? = null,
    val action: CognitiveExecutiveAction? = null,
    val planId: PlanId? = null,
    val planRunStage: AutonomousGovernedPlanRunStage? = null,
    val failureCode: String? = null
) {
    init {
        require(observedAtEpochMs >= 0L)
        require(nextDelayMs >= 0L)
        require(failureCode == null || failureCode.matches(FAILURE_CODE))
        if (checkpointStage == PersistentGoalExecutiveStage.PLANNED) {
            require(planId != null) { "planned scheduler tick requires plan id" }
        }
        require((stage == AutonomousGoalSchedulerStage.PLAN_PROGRESS) == (planRunStage != null)) {
            "plan-progress scheduler tick requires exact autonomous plan-run stage"
        }
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val FAILURE_CODE = Regex("[A-Z0-9_:-]{1,128}")
    }
}

/**
 * Phase296-300 bounded goal scheduler.
 *
 * Phase296 performs at most one PersistentGoalExecutiveCoordinator run per tick.
 * Phase297 applies a host-supplied resource gate before any cognitive work.
 * Phase298 uses checkpoint-aware minimum backoff so WAITING_OBSERVATION can retry quickly while
 * blocked/no-goal states remain quiet.
 * Phase299 prevents overlapping ticks and returns BUSY rather than spawning competing cognition.
 * Phase300 is driven by a process-resident loop; no wake lock, Android background service, alarm,
 * WorkManager dependency, ToolFabric handle, or authority mutation exists here.
 */
class AutonomousGoalScheduler(
    private val runGoal: (ConversationId) -> Result<PersistentGoalExecutiveResult>,
    private val resourceAllowed: () -> Boolean,
    private val runPlan: ((PlanId) -> Result<AutonomousGovernedPlanRunResult>)? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val busy = AtomicBoolean(false)

    @Volatile
    private var nextEligibleAtEpochMs: Long = 0L

    fun tick(conversationId: ConversationId): AutonomousGoalSchedulerTick {
        val now = clock().coerceAtLeast(0L)
        val remaining = (nextEligibleAtEpochMs - now).coerceAtLeast(0L)
        if (remaining > 0L) {
            return AutonomousGoalSchedulerTick(
                stage = AutonomousGoalSchedulerStage.THROTTLED,
                observedAtEpochMs = now,
                nextDelayMs = remaining
            )
        }
        if (!busy.compareAndSet(false, true)) {
            return AutonomousGoalSchedulerTick(
                stage = AutonomousGoalSchedulerStage.BUSY,
                observedAtEpochMs = now,
                nextDelayMs = BUSY_RETRY_MS
            )
        }

        return try {
            if (!resourceAllowed()) {
                scheduleFrom(now, RESOURCE_RETRY_MS)
                AutonomousGoalSchedulerTick(
                    stage = AutonomousGoalSchedulerStage.RESOURCE_GATED,
                    observedAtEpochMs = now,
                    nextDelayMs = RESOURCE_RETRY_MS
                )
            } else {
                val result = runGoal(conversationId)
                result.fold(
                    onSuccess = { outcome -> fromGoalResult(outcome, now) },
                    onFailure = { error ->
                        scheduleFrom(now, FAILURE_RETRY_MS)
                        AutonomousGoalSchedulerTick(
                            stage = AutonomousGoalSchedulerStage.FAILED,
                            observedAtEpochMs = now,
                            nextDelayMs = FAILURE_RETRY_MS,
                            failureCode = schedulerFailureCode(error)
                        )
                    }
                )
            }
        } finally {
            busy.set(false)
        }
    }

    fun nextEligibleAtEpochMs(): Long = nextEligibleAtEpochMs

    private fun fromGoalResult(
        result: PersistentGoalExecutiveResult,
        now: Long
    ): AutonomousGoalSchedulerTick {
        return when (result) {
            is PersistentGoalExecutiveResult.NoGoal -> {
                scheduleFrom(now, NO_GOAL_RETRY_MS)
                AutonomousGoalSchedulerTick(
                    stage = AutonomousGoalSchedulerStage.NO_GOAL,
                    observedAtEpochMs = now,
                    nextDelayMs = NO_GOAL_RETRY_MS
                )
            }
            is PersistentGoalExecutiveResult.Deferred -> {
                maybeRunPlanned(
                    checkpoint = result.checkpoint,
                    now = now
                ) ?: run {
                    val delay = deferredDelay(result.checkpoint.stage)
                    scheduleFrom(now, delay)
                    AutonomousGoalSchedulerTick(
                        stage = AutonomousGoalSchedulerStage.DEFERRED,
                        observedAtEpochMs = now,
                        nextDelayMs = delay,
                        checkpointStage = result.checkpoint.stage,
                        action = result.checkpoint.lastAction,
                        planId = result.checkpoint.plannedPlanId
                    )
                }
            }
            is PersistentGoalExecutiveResult.Ran -> {
                maybeRunPlanned(
                    checkpoint = result.checkpoint,
                    now = now
                ) ?: run {
                    val delay = ranDelay(result.checkpoint.stage)
                    scheduleFrom(now, delay)
                    AutonomousGoalSchedulerTick(
                        stage = AutonomousGoalSchedulerStage.RAN,
                        observedAtEpochMs = now,
                        nextDelayMs = delay,
                        checkpointStage = result.checkpoint.stage,
                        action = result.checkpoint.lastAction,
                        planId = result.checkpoint.plannedPlanId
                    )
                }
            }
        }
    }

    private fun maybeRunPlanned(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        now: Long
    ): AutonomousGoalSchedulerTick? {
        val runner = runPlan ?: return null
        if (checkpoint.stage != PersistentGoalExecutiveStage.PLANNED) return null
        val planId = checkpoint.plannedPlanId ?: return null

        return runner(planId).fold(
            onSuccess = { planRun ->
                val delay = when (planRun.stage) {
                    AutonomousGovernedPlanRunStage.WAITING_APPROVAL -> PLANNED_RETRY_MS
                    AutonomousGovernedPlanRunStage.EXECUTION_PAUSED -> BLOCKED_RETRY_MS
                    AutonomousGovernedPlanRunStage.STEP_LIMIT -> PLAN_PROGRESS_RETRY_MS
                    AutonomousGovernedPlanRunStage.CONTEXT_REFRESHED,
                    AutonomousGovernedPlanRunStage.COMPLETED,
                    AutonomousGovernedPlanRunStage.TERMINAL_RECOVERY -> ACTIVE_RETRY_MS
                }
                scheduleFrom(now, delay)
                AutonomousGoalSchedulerTick(
                    stage = AutonomousGoalSchedulerStage.PLAN_PROGRESS,
                    observedAtEpochMs = now,
                    nextDelayMs = delay,
                    checkpointStage = planRun.goalCheckpointStage,
                    action = CognitiveExecutiveAction.PLAN,
                    planId = planRun.activePlanId,
                    planRunStage = planRun.stage
                )
            },
            onFailure = { error ->
                scheduleFrom(now, FAILURE_RETRY_MS)
                AutonomousGoalSchedulerTick(
                    stage = AutonomousGoalSchedulerStage.FAILED,
                    observedAtEpochMs = now,
                    nextDelayMs = FAILURE_RETRY_MS,
                    checkpointStage = checkpoint.stage,
                    action = checkpoint.lastAction,
                    planId = checkpoint.plannedPlanId,
                    failureCode = schedulerFailureCode(error)
                )
            }
        )
    }

    private fun ranDelay(stage: PersistentGoalExecutiveStage): Long = when (stage) {
        PersistentGoalExecutiveStage.WAITING_OBSERVATION -> OBSERVATION_RETRY_MS
        PersistentGoalExecutiveStage.LEARNING_PAUSED,
        PersistentGoalExecutiveStage.EVOLUTION_PAUSED,
        PersistentGoalExecutiveStage.RECOVERY_QUEUED,
        PersistentGoalExecutiveStage.QUEUED -> ACTIVE_RETRY_MS
        PersistentGoalExecutiveStage.PLANNED -> PLANNED_RETRY_MS
        PersistentGoalExecutiveStage.COMPLETED -> ACTIVE_RETRY_MS
        PersistentGoalExecutiveStage.RECOVERY_BLOCKED,
        PersistentGoalExecutiveStage.EXECUTION_PAUSED,
        PersistentGoalExecutiveStage.PARTIAL_EXECUTION_BLOCKED,
        PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED -> BLOCKED_RETRY_MS
    }

    private fun deferredDelay(stage: PersistentGoalExecutiveStage): Long = when (stage) {
        PersistentGoalExecutiveStage.WAITING_OBSERVATION -> OBSERVATION_RETRY_MS
        PersistentGoalExecutiveStage.RECOVERY_QUEUED,
        PersistentGoalExecutiveStage.QUEUED,
        PersistentGoalExecutiveStage.LEARNING_PAUSED,
        PersistentGoalExecutiveStage.EVOLUTION_PAUSED -> ACTIVE_RETRY_MS
        PersistentGoalExecutiveStage.PLANNED -> PLANNED_RETRY_MS
        PersistentGoalExecutiveStage.COMPLETED -> ACTIVE_RETRY_MS
        PersistentGoalExecutiveStage.RECOVERY_BLOCKED,
        PersistentGoalExecutiveStage.EXECUTION_PAUSED,
        PersistentGoalExecutiveStage.PARTIAL_EXECUTION_BLOCKED,
        PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED -> BLOCKED_RETRY_MS
    }

    private fun scheduleFrom(now: Long, delay: Long) {
        nextEligibleAtEpochMs = now + delay
    }

    companion object {
        const val OBSERVATION_RETRY_MS = 5_000L
        const val PLAN_PROGRESS_RETRY_MS = 5_000L
        const val ACTIVE_RETRY_MS = 10_000L
        const val RESOURCE_RETRY_MS = 30_000L
        const val FAILURE_RETRY_MS = 30_000L
        const val BLOCKED_RETRY_MS = 60_000L
        const val PLANNED_RETRY_MS = 60_000L
        const val NO_GOAL_RETRY_MS = 60_000L
        const val BUSY_RETRY_MS = 1_000L
    }
}

class ProcessResidentAutonomyLoop(
    private val scheduler: AutonomousGoalScheduler,
    private val conversationId: () -> ConversationId,
    private val onTick: (AutonomousGoalSchedulerTick) -> Unit = {}
) : AutoCloseable {
    private val active = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "amper-autonomy-loop").apply {
            isDaemon = true
        }
    }

    @Volatile
    private var scheduled: ScheduledFuture<*>? = null

    fun start(): Boolean {
        if (!active.compareAndSet(false, true)) return false
        schedule(0L)
        return true
    }

    fun stop(): Boolean {
        if (!active.compareAndSet(true, false)) return false
        scheduled?.cancel(false)
        scheduled = null
        return true
    }

    fun isActive(): Boolean = active.get()

    private fun schedule(delayMs: Long) {
        if (!active.get()) return
        scheduled = executor.schedule(
            { driveOnce() },
            delayMs.coerceAtLeast(MIN_DRIVER_DELAY_MS),
            TimeUnit.MILLISECONDS
        )
    }

    private fun driveOnce() {
        if (!active.get()) return
        val tick = scheduler.tick(conversationId())
        runCatching { onTick(tick) }
        if (active.get()) schedule(tick.nextDelayMs)
    }

    override fun close() {
        active.set(false)
        scheduled?.cancel(false)
        scheduled = null
        executor.shutdownNow()
    }

    companion object {
        private const val MIN_DRIVER_DELAY_MS = 250L
    }
}

private fun schedulerFailureCode(error: Throwable?): String =
    error?.javaClass?.simpleName
        ?.uppercase()
        ?.replace(Regex("[^A-Z0-9_:-]"), "_")
        ?.take(96)
        ?.ifBlank { null }
        ?: "AUTONOMY_TICK_FAILURE"
