package io.amper.neuroos.core.v2

import io.amper.neuroos.core.MemoryId
import io.amper.neuroos.core.MemoryOs
import io.amper.neuroos.core.MemoryRecord
import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.Provenance
import io.amper.neuroos.core.SovereignPlan
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Immutable provenance link for one proactive task.
 *
 * This record deliberately stores no mutable task state. READY/WAITING_APPROVAL/terminal state is
 * always derived from the canonical persistent SovereignPlan so Phase662 cannot become a second
 * task database.
 */
data class AmperAgentProactiveTaskLifecycleBinding(
    val sourceId: String,
    val configurationSha256: String,
    val observationIdentitySha256: String,
    val taskId: String,
    val planId: PlanId,
    val trigger: AmperAgentTrigger,
    val boundAtEpochMs: Long
) {
    init {
        require(sourceId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(configurationSha256.matches(Regex("[0-9a-f]{64}")))
        require(observationIdentitySha256.matches(Regex("[0-9a-f]{64}")))
        require(taskId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(trigger.triggerId == sourceId)
        require(taskId == "proactive:" + observationIdentitySha256.take(48)) {
            "proactive lifecycle task identity drifted from accepted observation"
        }
        require(
            planId == AmperAgentPendingTriggerDispatchCoordinator
                .deterministicPlanId(observationIdentitySha256)
        ) {
            "proactive lifecycle plan identity drifted from deterministic dispatch binding"
        }
        require(boundAtEpochMs >= trigger.observedAtEpochMs)
    }
}

interface AmperAgentProactiveTaskLifecycleLedger {
    val capacity: Int

    fun record(
        binding: AmperAgentProactiveTaskLifecycleBinding
    ): Result<AmperAgentProactiveTaskLifecycleBinding>

    fun findByPlanId(planId: PlanId): AmperAgentProactiveTaskLifecycleBinding?

    fun recent(limit: Int = 16): List<AmperAgentProactiveTaskLifecycleBinding>

    fun remove(planId: PlanId): Boolean
}

/**
 * A single bounded provenance index inside the existing sovereign MemoryOs.
 *
 * It is not a task queue and stores no task state, planner state, approval decision, tool receipt, or
 * scheduler state. The canonical SovereignPlan remains the only mutable execution source of truth.
 */
class MemoryBackedAmperAgentProactiveTaskLifecycleLedger(
    private val memory: MemoryOs
) : AmperAgentProactiveTaskLifecycleLedger {
    override val capacity: Int
        get() = MAX_BINDINGS

    override fun record(
        binding: AmperAgentProactiveTaskLifecycleBinding
    ): Result<AmperAgentProactiveTaskLifecycleBinding> = runCatching {
        memory.transaction {
            val entries = load()
            entries.firstOrNull {
                it.observationIdentitySha256 == binding.observationIdentitySha256 ||
                    it.taskId == binding.taskId ||
                    it.planId == binding.planId
            }?.let { existing ->
                require(existing == binding) {
                    "proactive lifecycle identity is already bound to different provenance"
                }
                return@transaction existing
            }
            require(entries.size < MAX_BINDINGS) {
                "proactive lifecycle provenance ledger is full"
            }
            save(entries + binding)
            binding
        }
    }

    override fun findByPlanId(planId: PlanId): AmperAgentProactiveTaskLifecycleBinding? =
        memory.transaction {
            load().singleOrNull { it.planId == planId }
        }

    override fun recent(limit: Int): List<AmperAgentProactiveTaskLifecycleBinding> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.transaction {
            load()
                .sortedWith(
                    compareByDescending<AmperAgentProactiveTaskLifecycleBinding> {
                        it.boundAtEpochMs
                    }.thenBy { it.planId.value }
                )
                .take(limit)
        }
    }

    override fun remove(planId: PlanId): Boolean =
        memory.transaction {
            val entries = load()
            val next = entries.filterNot { it.planId == planId }
            if (next.size == entries.size) {
                false
            } else {
                save(next)
                true
            }
        }

    private fun MemoryOs.load(): List<AmperAgentProactiveTaskLifecycleBinding> {
        val record = get(RECORD_ID) ?: return emptyList()
        require(record.kind == KIND) {
            "proactive lifecycle provenance record kind drifted"
        }
        return AmperAgentProactiveTaskLifecycleCodec
            .decode(record.content)
            .getOrThrow()
    }

    private fun MemoryOs.save(
        entries: List<AmperAgentProactiveTaskLifecycleBinding>
    ) {
        require(entries.size <= MAX_BINDINGS)
        if (entries.isEmpty()) {
            forget(RECORD_ID)
            return
        }
        val updatedAt = entries.maxOf { it.boundAtEpochMs }
        remember(
            MemoryRecord(
                id = RECORD_ID,
                kind = KIND,
                content = AmperAgentProactiveTaskLifecycleCodec.encode(entries),
                importance = 0.96,
                provenance = Provenance(
                    source = "proactive-agent-task-provenance",
                    producer = "agent-proactive-lifecycle-ledger",
                    observedAtEpochMs = updatedAt,
                    confidence = 1.0
                ),
                createdAtEpochMs = updatedAt
            )
        )
    }

    companion object {
        const val MAX_BINDINGS: Int = 64
        const val KIND: String = "agent-proactive-task-lifecycle-v1"
        private val RECORD_ID = MemoryId("agent-proactive-task-lifecycle:index")
    }
}

internal object AmperAgentProactiveTaskLifecycleCodec {
    private const val VERSION = "AMPER_AGENT_PROACTIVE_TASK_LIFECYCLE_V1"

    fun encode(
        entries: List<AmperAgentProactiveTaskLifecycleBinding>
    ): String = buildString {
        appendLine(VERSION)
        entries
            .sortedWith(
                compareBy<AmperAgentProactiveTaskLifecycleBinding> { it.boundAtEpochMs }
                    .thenBy { it.planId.value }
            )
            .forEach { binding ->
                appendLine(
                    listOf(
                        "BINDING",
                        enc(binding.sourceId),
                        binding.configurationSha256,
                        binding.observationIdentitySha256,
                        enc(binding.taskId),
                        enc(binding.planId.value),
                        enc(binding.trigger.source),
                        binding.trigger.observedAtEpochMs.toString(),
                        binding.trigger.payloadDigest,
                        binding.boundAtEpochMs.toString()
                    ).joinToString("\t")
                )
            }
    }.trimEnd()

    fun decode(
        content: String
    ): Result<List<AmperAgentProactiveTaskLifecycleBinding>> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == VERSION) {
            "unsupported proactive task lifecycle ledger"
        }
        val entries = lines.drop(1).map { line ->
            val parts = line.split('\t')
            require(parts.size == 10 && parts[0] == "BINDING") {
                "malformed proactive task lifecycle binding"
            }
            val sourceId = dec(parts[1])
            AmperAgentProactiveTaskLifecycleBinding(
                sourceId = sourceId,
                configurationSha256 = parts[2],
                observationIdentitySha256 = parts[3],
                taskId = dec(parts[4]),
                planId = PlanId(dec(parts[5])),
                trigger = AmperAgentTrigger(
                    triggerId = sourceId,
                    source = dec(parts[6]),
                    observedAtEpochMs = parts[7].toLong(),
                    payloadDigest = parts[8]
                ),
                boundAtEpochMs = parts[9].toLong()
            )
        }
        require(entries.size <= MemoryBackedAmperAgentProactiveTaskLifecycleLedger.MAX_BINDINGS)
        require(entries.map { it.observationIdentitySha256 }.distinct().size == entries.size)
        require(entries.map { it.taskId }.distinct().size == entries.size)
        require(entries.map { it.planId }.distinct().size == entries.size)
        entries
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}

data class AmperAgentProactiveTaskLifecycleView(
    val binding: AmperAgentProactiveTaskLifecycleBinding,
    val taskState: AmperAgentTaskState?,
    val completedSteps: Int,
    val totalSteps: Int,
    val waitingApprovalStepIndex: Int?,
    val goal: String?,
    val planAvailable: Boolean
) {
    init {
        require(completedSteps >= 0)
        require(totalSteps >= 0)
        require(completedSteps <= totalSteps)
        require(planAvailable == (taskState != null))
        require(planAvailable == (goal != null))
        require(
            (taskState == AmperAgentTaskState.WAITING_APPROVAL) ==
                (waitingApprovalStepIndex != null)
        )
    }

    val terminal: Boolean
        get() = taskState in setOf(
            AmperAgentTaskState.COMPLETED,
            AmperAgentTaskState.FAILED,
            AmperAgentTaskState.CANCELLED
        )
}

/**
 * Phase662 lifecycle inspector/bridge around canonical proactive plans.
 *
 * It never approves/rejects a tool action. Governed decisions remain owned by
 * PersistentSovereignPlanCoordinator. After that external decision this coordinator can reconstruct
 * the exact proactive admission and emit only a verified Phase655 handoff from current plan state.
 */
class AmperAgentProactiveTaskLifecycleCoordinator(
    private val ledger: AmperAgentProactiveTaskLifecycleLedger,
    private val plans: AmperAgentPersistentPlanPort,
    private val admissions: AmperAgentTaskAdmissionRegistry,
    private val proactive: AmperAgentProactiveTaskCoordinator,
    private val eventWake: AmperAgentProactiveEventWakeCoordinator,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun recordDispatch(
        dispatch: AmperAgentPendingTriggerDispatchBinding
    ): Result<AmperAgentProactiveTaskLifecycleBinding> = runCatching {
        compactTerminal(keepRecentTerminal = 8).getOrThrow()
        val trigger = requireNotNull(dispatch.admission.request.trigger)
        val plan = requireNotNull(plans.load(dispatch.planId)) {
            "canonical proactive plan disappeared before lifecycle binding"
        }
        val binding = AmperAgentProactiveTaskLifecycleBinding(
            sourceId = dispatch.sourceId,
            configurationSha256 = dispatch.configurationSha256,
            observationIdentitySha256 = dispatch.observationIdentitySha256,
            taskId = dispatch.admission.request.taskId,
            planId = dispatch.planId,
            trigger = trigger,
            // Stable across crash/retry: checkpoint timestamps may change, the durable plan
            // creation time and accepted observation time do not.
            boundAtEpochMs = maxOf(
                trigger.observedAtEpochMs,
                plan.createdAtEpochMs
            )
        )
        ledger.record(binding).getOrThrow()
    }

    fun inspect(limit: Int = 16): List<AmperAgentProactiveTaskLifecycleView> =
        ledger.recent(limit).map(::inspect)

    fun findByPlanId(planId: PlanId): AmperAgentProactiveTaskLifecycleView? =
        ledger.findByPlanId(planId)?.let(::inspect)

    fun openPlan(planId: PlanId): SovereignPlan? =
        ledger.findByPlanId(planId)?.let { plans.load(it.planId) }

    /**
     * Recreate the current verified Phase655 handoff without advancing the plan.
     *
     * READY plans become CHECKPOINTED EVENT_WAKE handoffs, approval-blocked plans stay non-runnable,
     * and terminal plans emit TERMINAL_NOOP. This is safe for foreground reconciliation.
     */
    fun currentHandoff(planId: PlanId): Result<AmperAgentEventWakeHandoff?> = runCatching {
        val binding = ledger.findByPlanId(planId) ?: return@runCatching null
        val plan = requireNotNull(plans.load(planId)) {
            "tracked proactive lifecycle plan is unavailable"
        }
        val admission = admission(binding, plan)
        val checkpoint = checkpoint(binding, plan)
        eventWake.handoff(admission, checkpoint).getOrThrow()
    }

    /**
     * Called only after the existing governed plan surface has approved or rejected its exact
     * REQUIRES_CONFIRMATION step. No approval is performed here.
     */
    fun resumeAfterGovernedDecision(
        planId: PlanId
    ): Result<AmperAgentEventWakeHandoff?> = runCatching {
        val binding = ledger.findByPlanId(planId) ?: return@runCatching null
        val plan = requireNotNull(plans.load(planId)) {
            "tracked proactive lifecycle plan is unavailable"
        }
        val admission = admission(binding, plan)
        val waiting = AmperAgentPlanTaskCheckpoint(
            taskId = binding.taskId,
            planId = planId,
            backgroundMode = OmegaBackgroundMode.EVENT_WAKE,
            taskState = AmperAgentTaskState.WAITING_APPROVAL,
            completedSteps = completedSteps(plan),
            totalSteps = plan.steps.size,
            updatedAtEpochMs = maxOf(plan.createdAtEpochMs, clock())
        )
        val resumed = proactive
            .resumeAfterGovernedApproval(admission, waiting)
            .getOrThrow()
        eventWake.handoff(admission, resumed.checkpoint).getOrThrow()
    }

    /**
     * Bounded ledger maintenance. Only canonical plans already proven terminal may be forgotten;
     * active, approval-blocked, and missing-plan entries remain fail-closed.
     */
    fun compactTerminal(keepRecentTerminal: Int = 8): Result<Int> = runCatching {
        require(keepRecentTerminal >= 0)
        val all = ledger.recent(ledger.capacity)
        val terminal = all
            .filter { binding ->
                plans.load(binding.planId)?.let(::deriveState) in setOf(
                    AmperAgentTaskState.COMPLETED,
                    AmperAgentTaskState.FAILED
                )
            }
            .sortedByDescending { it.boundAtEpochMs }
        val remove = terminal.drop(keepRecentTerminal)
        remove.count { ledger.remove(it.planId) }
    }

    private fun inspect(
        binding: AmperAgentProactiveTaskLifecycleBinding
    ): AmperAgentProactiveTaskLifecycleView {
        val plan = plans.load(binding.planId)
            ?: return AmperAgentProactiveTaskLifecycleView(
                binding = binding,
                taskState = null,
                completedSteps = 0,
                totalSteps = 0,
                waitingApprovalStepIndex = null,
                goal = null,
                planAvailable = false
            )
        val state = deriveState(plan)
        val waiting = if (state == AmperAgentTaskState.WAITING_APPROVAL) {
            plan.steps.first { it.status == PlanStepStatus.REQUIRES_CONFIRMATION }.index
        } else {
            null
        }
        return AmperAgentProactiveTaskLifecycleView(
            binding = binding,
            taskState = state,
            completedSteps = completedSteps(plan),
            totalSteps = plan.steps.size,
            waitingApprovalStepIndex = waiting,
            goal = plan.goal,
            planAvailable = true
        )
    }

    private fun admission(
        binding: AmperAgentProactiveTaskLifecycleBinding,
        plan: SovereignPlan
    ): AmperAgentTaskAdmission {
        admissions.get(binding.taskId)?.let { existing ->
            require(existing.request.origin == AmperAgentTaskOrigin.PROACTIVE_TRIGGER)
            require(existing.request.trigger == binding.trigger)
            require(existing.request.objective == plan.goal)
            require(plan.steps.all { it.capability in existing.request.allowedCapabilities })
            return existing
        }
        val reconstructed = AmperAgentTaskAdmissionPolicy.admit(
            AmperAgentTaskRequest(
                taskId = binding.taskId,
                origin = AmperAgentTaskOrigin.PROACTIVE_TRIGGER,
                objective = plan.goal,
                allowedCapabilities = plan.steps.mapTo(linkedSetOf()) { it.capability },
                expectedRuntimeMs = 0L,
                mustSurviveUiExit = true,
                canBeDeferred = true,
                createdAtEpochMs = binding.trigger.observedAtEpochMs,
                trigger = binding.trigger
            )
        )
        return admissions.register(reconstructed)
    }

    private fun checkpoint(
        binding: AmperAgentProactiveTaskLifecycleBinding,
        plan: SovereignPlan
    ): AmperAgentPlanTaskCheckpoint =
        AmperAgentPlanTaskCheckpoint(
            taskId = binding.taskId,
            planId = plan.id,
            backgroundMode = OmegaBackgroundMode.EVENT_WAKE,
            taskState = deriveState(plan),
            completedSteps = completedSteps(plan),
            totalSteps = plan.steps.size,
            updatedAtEpochMs = maxOf(plan.createdAtEpochMs, clock())
        )

    private fun deriveState(plan: SovereignPlan): AmperAgentTaskState {
        if (plan.complete) {
            return if (plan.steps.all { it.status == PlanStepStatus.EXECUTED }) {
                AmperAgentTaskState.COMPLETED
            } else {
                AmperAgentTaskState.FAILED
            }
        }
        val active = plan.steps.firstOrNull {
            it.status == PlanStepStatus.PLANNED ||
                it.status == PlanStepStatus.REQUIRES_CONFIRMATION
        } ?: error("tracked proactive plan has no active step")
        return if (active.status == PlanStepStatus.REQUIRES_CONFIRMATION) {
            AmperAgentTaskState.WAITING_APPROVAL
        } else {
            AmperAgentTaskState.CHECKPOINTED
        }
    }

    private fun completedSteps(plan: SovereignPlan): Int =
        plan.steps.count {
            it.status != PlanStepStatus.PLANNED &&
                it.status != PlanStepStatus.REQUIRES_CONFIRMATION
        }
}
