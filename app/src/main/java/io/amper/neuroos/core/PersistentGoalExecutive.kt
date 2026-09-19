package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64

enum class PersistentGoalExecutiveStage {
    QUEUED,
    WAITING_OBSERVATION,
    LEARNING_PAUSED,
    EVOLUTION_PAUSED,
    PLANNED,
    COMPLETED
}

data class PersistentGoalExecutiveCheckpoint(
    val sourceGoalId: String,
    val objective: String,
    val conversationId: ConversationId,
    val priority: Double,
    val stage: PersistentGoalExecutiveStage,
    val attemptCount: Int = 0,
    val lastAction: CognitiveExecutiveAction? = null,
    val lastCognitiveStateDigest: String? = null,
    val lastExecutionContextDigest: String? = null,
    val plannedPlanId: PlanId? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(sourceGoalId.isNotBlank() && sourceGoalId.length <= MAX_GOAL_ID_CHARS)
        require(objective.isNotBlank() && objective.length <= MAX_OBJECTIVE_CHARS)
        require(priority in 0.0..1.0)
        require(attemptCount >= 0)
        require(updatedAtEpochMs >= 0L)
        require((lastCognitiveStateDigest == null) == (lastExecutionContextDigest == null)) {
            "persistent goal executive requires both cognitive digests or neither"
        }
        lastCognitiveStateDigest?.let {
            require(it.matches(SHA256)) { "invalid persistent goal cognitive-state digest" }
        }
        lastExecutionContextDigest?.let {
            require(it.matches(SHA256)) { "invalid persistent goal execution-context digest" }
        }
        if (stage == PersistentGoalExecutiveStage.PLANNED) {
            require(plannedPlanId != null) { "planned goal checkpoint requires a plan id" }
        }
        if (stage !in setOf(
                PersistentGoalExecutiveStage.PLANNED,
                PersistentGoalExecutiveStage.COMPLETED
            )
        ) {
            require(plannedPlanId == null) { "non-planned goal checkpoint cannot retain a plan id" }
        }
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MAX_GOAL_ID_CHARS = 256
        const val MAX_OBJECTIVE_CHARS = 1024
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

interface PersistentGoalExecutiveStore {
    fun load(): PersistentGoalExecutiveCheckpoint?
    fun save(checkpoint: PersistentGoalExecutiveCheckpoint): PersistentGoalExecutiveCheckpoint
    fun clear(): Boolean
}

/**
 * Phase281-285 restart-safe single-checkpoint goal journal.
 *
 * The checkpoint intentionally stores the selected goal objective because the in-memory GoalSystem
 * is not itself a durable scheduler. Android production Memory OS is encrypted at rest.
 */
class MemoryBackedPersistentGoalExecutiveStore(
    private val memory: MemoryOs
) : PersistentGoalExecutiveStore {
    override fun load(): PersistentGoalExecutiveCheckpoint? =
        memory.get(CHECKPOINT_ID)
            ?.takeIf { it.kind == CHECKPOINT_KIND }
            ?.let { PersistentGoalExecutiveCodec.decode(it.content).getOrNull() }

    override fun save(
        checkpoint: PersistentGoalExecutiveCheckpoint
    ): PersistentGoalExecutiveCheckpoint {
        memory.remember(
            MemoryRecord(
                id = CHECKPOINT_ID,
                kind = CHECKPOINT_KIND,
                content = PersistentGoalExecutiveCodec.encode(checkpoint),
                importance = 0.95,
                provenance = Provenance(
                    source = "persistent-goal-executive",
                    producer = "goal-executive-store",
                    confidence = 1.0
                ),
                createdAtEpochMs = checkpoint.updatedAtEpochMs
            )
        )
        return checkpoint
    }

    override fun clear(): Boolean = memory.forget(CHECKPOINT_ID)

    companion object {
        const val CHECKPOINT_KIND = "persistent-goal-executive-v1"
        private val CHECKPOINT_ID = MemoryId("persistent-goal-executive:current")
    }
}

sealed interface PersistentGoalExecutiveResult {
    data class NoGoal(
        val completedGoalId: String? = null
    ) : PersistentGoalExecutiveResult

    data class Deferred(
        val checkpoint: PersistentGoalExecutiveCheckpoint,
        val reason: String
    ) : PersistentGoalExecutiveResult {
        init { require(reason.isNotBlank()) }
    }

    data class Ran(
        val checkpoint: PersistentGoalExecutiveCheckpoint,
        val run: CognitiveExecutiveRunResult
    ) : PersistentGoalExecutiveResult
}

/**
 * Persistent goal handoff over the Phase276-280 bounded cognitive executive.
 *
 * Phase281 selects the highest-priority active sovereign goal.
 * Phase282 persists one encrypted-memory checkpoint so the selected goal survives process restart.
 * Phase283 runs only the existing bounded cognitive executive and persists a generated plan.
 * Phase284 resumes WAITING/PRACTICE/EVOLUTION checkpoints, while PLANNED blocks duplicate planning.
 * Phase285 requires explicit plan-completion acknowledgement before the checkpoint is closed and a
 * different active goal may be selected. This coordinator has no ToolFabric/AuthorityGate handle.
 */
class PersistentGoalExecutiveCoordinator(
    private val context: SovereignContextSource,
    private val executive: AutonomousCognitiveExecutive,
    private val store: PersistentGoalExecutiveStore,
    private val plans: SovereignPlanStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun runNext(
        conversationId: ConversationId
    ): Result<PersistentGoalExecutiveResult> = runCatching {
        val existing = store.load()
        if (existing?.stage == PersistentGoalExecutiveStage.PLANNED) {
            return@runCatching PersistentGoalExecutiveResult.Deferred(
                checkpoint = existing,
                reason = "planned goal is already handed off; complete or abandon that plan before replanning"
            )
        }

        val checkpoint = when {
            existing == null -> selectActiveGoal(conversationId, excludedGoalId = null)
            existing.stage == PersistentGoalExecutiveStage.COMPLETED ->
                selectActiveGoal(conversationId, excludedGoalId = existing.sourceGoalId)
            else -> existing
        } ?: return@runCatching PersistentGoalExecutiveResult.NoGoal(
            completedGoalId = existing
                ?.takeIf { it.stage == PersistentGoalExecutiveStage.COMPLETED }
                ?.sourceGoalId
        )

        val run = executive.runBounded(
            conversationId = checkpoint.conversationId,
            userGoal = checkpoint.objective
        ).getOrThrow()
        val terminal = run.cycles.last()
        val planned = terminal as? CognitiveExecutiveCycleResult.Planned
        planned?.let { plans.save(it.plan) }

        val nextStage = when (terminal.directive.action) {
            CognitiveExecutiveAction.PLAN -> PersistentGoalExecutiveStage.PLANNED
            CognitiveExecutiveAction.OBSERVE -> PersistentGoalExecutiveStage.WAITING_OBSERVATION
            CognitiveExecutiveAction.PRACTICE -> PersistentGoalExecutiveStage.LEARNING_PAUSED
            CognitiveExecutiveAction.EVOLVE -> PersistentGoalExecutiveStage.EVOLUTION_PAUSED
        }
        val next = checkpoint.copy(
            stage = nextStage,
            attemptCount = checkpoint.attemptCount + 1,
            lastAction = terminal.directive.action,
            lastCognitiveStateDigest = terminal.directive.cognitiveStateDigest,
            lastExecutionContextDigest = terminal.directive.executionContextDigest,
            plannedPlanId = planned?.plan?.id,
            updatedAtEpochMs = clock().coerceAtLeast(checkpoint.updatedAtEpochMs)
        )
        store.save(next)
        PersistentGoalExecutiveResult.Ran(next, run)
    }

    @Synchronized
    fun completePlanned(
        planId: PlanId
    ): Result<PersistentGoalExecutiveCheckpoint> = runCatching {
        val current = requireNotNull(store.load()) {
            "persistent goal executive has no active checkpoint"
        }
        require(current.stage == PersistentGoalExecutiveStage.PLANNED) {
            "persistent goal executive is not awaiting plan completion"
        }
        require(current.plannedPlanId == planId) {
            "completed plan does not match persistent goal handoff"
        }
        val completedPlan = requireNotNull(plans.load(planId)) {
            "completed persistent goal plan is unavailable from plan store"
        }
        require(completedPlan.steps.all { it.status == PlanStepStatus.EXECUTED }) {
            "persistent goal plan has not completed successfully"
        }

        store.save(
            current.copy(
                stage = PersistentGoalExecutiveStage.COMPLETED,
                updatedAtEpochMs = clock().coerceAtLeast(current.updatedAtEpochMs)
            )
        )
    }

    fun current(): PersistentGoalExecutiveCheckpoint? = store.load()

    private fun selectActiveGoal(
        conversationId: ConversationId,
        excludedGoalId: String?
    ): PersistentGoalExecutiveCheckpoint? {
        val snapshot = context.capture(
            query = GOAL_SCAN_QUERY,
            memoryLimit = 0,
            worldLimit = 0,
            workspaceLimit = 0
        )
        val goal = snapshot.goals
            .asSequence()
            .filterNot { it.id.value == excludedGoalId }
            .sortedWith(
                compareByDescending<GoalState> { it.priority }
                    .thenBy { it.id.value }
            )
            .firstOrNull()
            ?: return null
        val now = clock().coerceAtLeast(0L)
        return store.save(
            PersistentGoalExecutiveCheckpoint(
                sourceGoalId = goal.id.value.take(PersistentGoalExecutiveCheckpoint.MAX_GOAL_ID_CHARS),
                objective = goal.objective.take(PersistentGoalExecutiveCheckpoint.MAX_OBJECTIVE_CHARS),
                conversationId = conversationId,
                priority = goal.priority,
                stage = PersistentGoalExecutiveStage.QUEUED,
                updatedAtEpochMs = now
            )
        )
    }

    companion object {
        private const val GOAL_SCAN_QUERY = "active sovereign goals"
    }
}

internal object PersistentGoalExecutiveCodec {
    private const val VERSION = "AMPER_PERSISTENT_GOAL_EXECUTIVE_V1"

    fun encode(checkpoint: PersistentGoalExecutiveCheckpoint): String = buildString {
        appendLine(VERSION)
        appendLine("GOAL_ID\t" + enc(checkpoint.sourceGoalId))
        appendLine("OBJECTIVE\t" + enc(checkpoint.objective))
        appendLine("CONVERSATION\t" + enc(checkpoint.conversationId.value))
        appendLine("PRIORITY\t" + checkpoint.priority)
        appendLine("STAGE\t" + checkpoint.stage.name)
        appendLine("ATTEMPTS\t" + checkpoint.attemptCount)
        appendLine("LAST_ACTION\t" + (checkpoint.lastAction?.name ?: "~"))
        appendLine("COGNITIVE_DIGEST\t" + (checkpoint.lastCognitiveStateDigest ?: "~"))
        appendLine("EXECUTION_DIGEST\t" + (checkpoint.lastExecutionContextDigest ?: "~"))
        appendLine("PLAN_ID\t" + (checkpoint.plannedPlanId?.value?.let(::enc) ?: "~"))
        append("UPDATED\t" + checkpoint.updatedAtEpochMs)
    }

    fun decode(content: String): Result<PersistentGoalExecutiveCheckpoint> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == VERSION) { "unsupported persistent goal executive state" }
        val fields = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val parts = line.split('\t')
            require(parts.size == 2) { "invalid persistent goal executive field" }
            require(fields.put(parts[0], parts[1]) == null) {
                "duplicate persistent goal executive field"
            }
        }
        val required = setOf(
            "GOAL_ID",
            "OBJECTIVE",
            "CONVERSATION",
            "PRIORITY",
            "STAGE",
            "ATTEMPTS",
            "LAST_ACTION",
            "COGNITIVE_DIGEST",
            "EXECUTION_DIGEST",
            "PLAN_ID",
            "UPDATED"
        )
        require(fields.keys.containsAll(required)) { "persistent goal executive state is incomplete" }

        PersistentGoalExecutiveCheckpoint(
            sourceGoalId = dec(fields.getValue("GOAL_ID")),
            objective = dec(fields.getValue("OBJECTIVE")),
            conversationId = ConversationId(dec(fields.getValue("CONVERSATION"))),
            priority = fields.getValue("PRIORITY").toDouble(),
            stage = PersistentGoalExecutiveStage.valueOf(fields.getValue("STAGE")),
            attemptCount = fields.getValue("ATTEMPTS").toInt(),
            lastAction = fields.getValue("LAST_ACTION")
                .takeUnless { it == "~" }
                ?.let(CognitiveExecutiveAction::valueOf),
            lastCognitiveStateDigest = fields.getValue("COGNITIVE_DIGEST")
                .takeUnless { it == "~" },
            lastExecutionContextDigest = fields.getValue("EXECUTION_DIGEST")
                .takeUnless { it == "~" },
            plannedPlanId = fields.getValue("PLAN_ID")
                .takeUnless { it == "~" }
                ?.let(::dec)
                ?.let(::PlanId),
            updatedAtEpochMs = fields.getValue("UPDATED").toLong()
        )
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
