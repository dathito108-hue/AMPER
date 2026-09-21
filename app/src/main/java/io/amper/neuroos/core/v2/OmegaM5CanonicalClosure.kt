package io.amper.neuroos.core.v2

data class OmegaM5CanonicalClosureSlice(
    val phase: Int,
    val capability: String,
    val canonicalOwner: String,
    val requiredInvariant: String
) {
    init {
        require(phase in 647..669)
        require(capability.isNotBlank())
        require(canonicalOwner.isNotBlank())
        require(requiredInvariant.isNotBlank())
    }
}

data class OmegaM5CanonicalClosureReport(
    val phaseCoverageComplete: Boolean,
    val missingInvariantIds: List<String>,
    val missingExitCriteria: List<String>
) {
    val ready: Boolean
        get() =
            phaseCoverageComplete &&
                missingInvariantIds.isEmpty() &&
                missingExitCriteria.isEmpty()
}

/**
 * Phase670 metadata-only closure gate for M5 Agent Core / Always-On.
 *
 * This object executes nothing and persists nothing. It only audits the already-canonical
 * architecture locks produced by Phase647..Phase669 so later refactors cannot silently remove one
 * M5 slice while leaving the milestone text apparently intact.
 */
object OmegaM5CanonicalClosure {
    val slices: List<OmegaM5CanonicalClosureSlice> = listOf(
        slice(647, "canonical agent task contract", "AmperAgentTaskContract",
            "agent-task-origin-and-background-mode-are-canonical"),
        slice(648, "passive persistent-plan binding", "AmperAgentPassiveTaskCoordinator",
            "passive-agent-tasks-reuse-persistent-sovereign-plans"),
        slice(649, "durable execution continuation", "AmperAgentExecutionContinuationCoordinator",
            "agent-continuation-restores-exact-durable-plan-without-replay"),
        slice(650, "verified Android continuation handoff", "AmperAgentAndroidContinuationHandoff",
            "android-continuation-handoff-is-verified-and-non-authoritative"),
        slice(651, "dedicated Android continuation hosts", "AndroidAgentContinuationHosts",
            "android-agent-continuation-hosts-are-dedicated-and-one-step-bounded"),
        slice(652, "canonical continuation execution port",
            "AmperAgentCanonicalContinuationExecutionPort",
            "android-agent-wakes-advance-one-canonical-persistent-step"),
        slice(653, "cold process/reboot canonical bootstrap",
            "AndroidCanonicalSovereignRuntimeBootstrap",
            "cold-persisted-agent-wakes-rebuild-the-same-canonical-runtime-graph"),
        slice(654, "proactive persistent-plan binding", "AmperAgentProactiveTaskCoordinator",
            "proactive-trigger-tasks-reuse-canonical-persistent-plan-engine"),
        slice(655, "verified EVENT_WAKE handoff", "AmperAgentProactiveEventWakeCoordinator",
            "proactive-event-wakes-bind-trigger-and-exact-durable-plan-state"),
        slice(656, "canonical EVENT_WAKE consumer", "AmperAgentCanonicalEventWakeExecutionPort",
            "verified-event-wake-consumption-advances-one-canonical-persistent-step"),
        slice(657, "governed Android EVENT_WAKE scheduler", "AndroidAgentEventWakeScheduler",
            "proactive-event-wake-job-scheduling-is-verified-deduped-and-resource-governed"),
        slice(658, "bounded proactive trigger source", "AmperAgentProactiveTriggerSource",
            "proactive-trigger-sources-are-user-configured-bounded-and-non-polling"),
        slice(659, "persistent proactive trigger registry",
            "AmperAgentPersistentProactiveTriggerRegistry",
            "proactive-trigger-registrations-live-in-encrypted-sovereign-memory"),
        slice(660, "Android trigger-source reconciliation",
            "AndroidAgentProactiveTriggerSourceScheduler",
            "scheduled-trigger-sources-use-bounded-persisted-jobs-not-polling"),
        slice(661, "durable trigger FIFO dispatch", "AmperAgentPendingTriggerDispatchCoordinator",
            "pending-trigger-dispatch-uses-deterministic-canonical-plan-identity"),
        slice(662, "canonical proactive lifecycle surface",
            "AmperAgentProactiveTaskLifecycleCoordinator",
            "proactive-lifecycle-ledger-stores-provenance-not-task-state"),
        slice(663, "approval/terminal attention discoverability",
            "AndroidAgentProactiveAttentionController",
            "proactive-attention-is-lifecycle-derived-discoverability-only"),
        slice(664, "durable revision-scoped attention acknowledgement",
            "MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger",
            "proactive-attention-ack-is-revision-scoped-ui-state-only"),
        slice(665, "read-only proactive history and canonical receipts",
            "AmperAgentProactiveTaskHistoryProjection",
            "proactive-history-is-read-only-projection-not-task-database"),
        slice(666, "exact canonical recovery navigation", "SovereignRecoveryConsole",
            "proactive-recovery-target-binds-plan-step-and-request-identity"),
        slice(667, "read-only proactive controls and status refresh",
            "AmperAgentProactiveTaskControlPolicy",
            "proactive-surface-controls-are-refresh-or-navigation-only"),
        slice(668, "optimistic proactive snapshot coherence",
            "AmperAgentProactiveTaskHistoryProjection",
            "proactive-history-snapshot-coherence-uses-bounded-optimistic-reread"),
        slice(669, "event-driven foreground visibility",
            "AndroidAgentProactiveForegroundVisibilityCoordinator",
            "proactive-foreground-visibility-is-on-resume-event-driven")
    )

    val foundationalInvariantIds: Set<String> = linkedSetOf(
        "one-amper-foundation-runtime",
        "agent-core-never-owns-tool-authority",
        "canonical-agent-wake-execution-is-serialized-against-duplicate-replay",
        "gguf-is-import-source-only",
        "internet-is-governed-tool-not-model",
        "background-work-is-checkpointed",
        "foreground-work-survives-ui-exit",
        "agent-actions-remain-audited"
    )

    val requiredM5ExitCriteria: Set<String> = linkedSetOf(
        "canonical user-request and proactive-trigger task contract",
        "persisted JobService cold-starts the same single-foundation runtime graph after process death or reboot",
        "proactive EVENT_WAKE tasks reuse the same persistent sovereign-plan engine and governed approval boundary",
        "EVENT_WAKE jobs chain only from fresh verified checkpoints and never schedule approval-blocked or terminal work",
        "proactive trigger sources are finite user-configured scheduled windows or app-local events with bounded cooldown",
        "accepted trigger observations remain in a bounded FIFO inside the same encrypted source record until canonical dispatch acknowledges them",
        "proactive lifecycle metadata stores immutable provenance only while live task state remains canonical persistent-plan state",
        "proactive attention is derived only from Phase662 lifecycle state and adds no planner scheduler approval or execution authority",
        "proactive attention acknowledgement stores only PlanId revision fingerprint and acknowledgement time as UI state in existing encrypted sovereign memory",
        "proactive task history is a bounded read-only projection over Phase662 lifecycle state and never persists a second task or history record",
        "proactive recovery handling continues to use the existing canonical Recovery Console and receipt ledger with no second recovery authority",
        "proactive task controls are limited to status refresh governed-plan navigation and exact recovery navigation",
        "proactive lifecycle history uses bounded optimistic reread instead of holding locks across plan scheduler or receipt operations",
        "foreground proactive visibility reconciliation is driven only by MainActivity ON_RESUME lifecycle events and has no polling cadence",
        "passive tool execution",
        "proactive goals and triggers",
        "foreground continuation after UI exit",
        "persisted jobs restore after process death/reboot",
        "internet observation and task checkpointing"
    )

    fun audit(
        invariants: Set<String> = OmegaArchitectureLock.invariants,
        m5ExitCriteria: Set<String> = OmegaArchitectureLock.milestones
            .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
            .exitCriteria
            .toSet()
    ): OmegaM5CanonicalClosureReport {
        val phases = slices.map { it.phase }
        val phaseCoverage =
            phases == (647..669).toList() &&
                phases.distinct().size == phases.size
        val requiredInvariants =
            foundationalInvariantIds + slices.map { it.requiredInvariant }
        return OmegaM5CanonicalClosureReport(
            phaseCoverageComplete = phaseCoverage,
            missingInvariantIds = requiredInvariants
                .filterNot(invariants::contains)
                .sorted(),
            missingExitCriteria = requiredM5ExitCriteria
                .filterNot(m5ExitCriteria::contains)
                .sorted()
        )
    }

    private fun slice(
        phase: Int,
        capability: String,
        canonicalOwner: String,
        requiredInvariant: String
    ) = OmegaM5CanonicalClosureSlice(
        phase = phase,
        capability = capability,
        canonicalOwner = canonicalOwner,
        requiredInvariant = requiredInvariant
    )
}
