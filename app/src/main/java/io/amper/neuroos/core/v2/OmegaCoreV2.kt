package io.amper.neuroos.core.v2

enum class OmegaComputeMode {
    REFLEX,
    FAST,
    STANDARD,
    REASON,
    DEEP,
    VERIFY
}

data class OmegaComputeBudget(
    val mode: OmegaComputeMode,
    val recurrentCycles: Int,
    val verifyPasses: Int,
    val targetFirstTokenMs: Long?,
    val allowInternetVerification: Boolean,
    val allowToolUse: Boolean
) {
    init {
        require(recurrentCycles >= 0)
        require(verifyPasses >= 0)
        require(targetFirstTokenMs == null || targetFirstTokenMs > 0L)
    }
}

data class OmegaReasoningRequest(
    val userComplexity: Int,
    val uncertainty: Double,
    val highConsequence: Boolean,
    val toolUseful: Boolean,
    val internetUseful: Boolean,
    val explicitDeepReasoning: Boolean = false
) {
    init {
        require(userComplexity in 0..100)
        require(uncertainty in 0.0..1.0)
    }
}

/**
 * Canonical adaptive-compute policy for AMPER OMEGA.
 *
 * One AMPER foundation is reused at different compute depths. The policy never chooses a second
 * model or inference runtime.
 */
object OmegaAdaptiveComputePolicy {
    fun plan(request: OmegaReasoningRequest): OmegaComputeBudget {
        if (
            request.highConsequence ||
            request.explicitDeepReasoning ||
            request.uncertainty >= 0.75
        ) {
            return OmegaComputeBudget(
                mode = if (request.highConsequence) OmegaComputeMode.VERIFY else OmegaComputeMode.DEEP,
                recurrentCycles = if (request.highConsequence) 8 else 6,
                verifyPasses = if (request.highConsequence) 2 else 1,
                targetFirstTokenMs = null,
                allowInternetVerification = request.internetUseful,
                allowToolUse = request.toolUseful
            )
        }

        if (request.userComplexity >= 65 || request.uncertainty >= 0.45) {
            return OmegaComputeBudget(
                mode = OmegaComputeMode.REASON,
                recurrentCycles = 3,
                verifyPasses = 1,
                targetFirstTokenMs = 4_000L,
                allowInternetVerification = request.internetUseful,
                allowToolUse = request.toolUseful
            )
        }

        if (request.userComplexity >= 30) {
            return OmegaComputeBudget(
                mode = OmegaComputeMode.STANDARD,
                recurrentCycles = 1,
                verifyPasses = 0,
                targetFirstTokenMs = 2_500L,
                allowInternetVerification = request.internetUseful,
                allowToolUse = request.toolUseful
            )
        }

        return OmegaComputeBudget(
            mode = OmegaComputeMode.FAST,
            recurrentCycles = 0,
            verifyPasses = 0,
            targetFirstTokenMs = 1_500L,
            allowInternetVerification = false,
            allowToolUse = request.toolUseful
        )
    }
}

enum class OmegaBackgroundMode {
    UI_BOUND,
    FOREGROUND_CONTINUATION,
    PERSISTED_JOB,
    EVENT_WAKE
}

data class OmegaBackgroundWork(
    val expectedRuntimeMs: Long,
    val userInitiated: Boolean,
    val mustSurviveUiExit: Boolean,
    val canBeDeferred: Boolean,
    val hasFutureTrigger: Boolean
) {
    init {
        require(expectedRuntimeMs >= 0L)
    }
}

/**
 * Android-safe background execution policy.
 *
 * UI exit and process death are different events. Long user-started work continues in a visible
 * foreground service; deferrable/proactive work is checkpointed into an OS-persisted job/event wake.
 */
object OmegaBackgroundExecutionPolicy {
    fun choose(work: OmegaBackgroundWork): OmegaBackgroundMode = when {
        work.hasFutureTrigger -> OmegaBackgroundMode.EVENT_WAKE
        work.canBeDeferred -> OmegaBackgroundMode.PERSISTED_JOB
        work.mustSurviveUiExit && work.userInitiated ->
            OmegaBackgroundMode.FOREGROUND_CONTINUATION
        else -> OmegaBackgroundMode.UI_BOUND
    }
}

enum class OmegaMilestoneId {
    M1_ARCHITECTURE_CONSOLIDATION,
    M2_AMI2_COMPILER,
    M3_AMNE2_RUNTIME,
    M4_AMCF_FOUNDATION,
    M5_AGENT_CORE_ALWAYS_ON,
    M6_COGNITIVE_MEMORY,
    M7_MULTIMODAL_VOICE,
    M8_3D_EMBODIMENT_HARDENING
}

data class OmegaMilestone(
    val id: OmegaMilestoneId,
    val exitCriteria: List<String>
) {
    init {
        require(exitCriteria.isNotEmpty())
        require(exitCriteria.all(String::isNotBlank))
    }
}

/**
 * Architecture lock for AMPER MOBILE OMEGA.
 *
 * This is intentionally code, not only documentation: unit tests can fail if the canonical roadmap
 * or invariants drift back toward multiple model engines.
 */
object OmegaArchitectureLock {
    const val architectureId: String = "AMPER-MOBILE-OMEGA"
    const val architectureVersion: Int = 2

    val invariants: Set<String> = linkedSetOf(
        "one-amper-foundation-runtime",
        "ami2-is-canonical-model-format",
        "amne2-is-canonical-execution-engine",
        "conversation-hot-state-is-identity-bound",
        "hardware-autotuning-is-measured-and-fail-closed",
        "memory-and-context-are-hardware-budgeted",
        "context-pressure-never-silently-evicts-kv",
        "runtime-readiness-is-consolidated-and-model-specific",
        "amcf-cycles-stay-on-one-foundation",
        "amcf-recurrent-state-is-structured-and-bounded",
        "amcf-cycle-state-commit-is-transactional",
        "amcf-production-port-reuses-single-core-endpoint",
        "amcf-quality-reuses-integrated-cognitive-readiness",
        "amcf-run-cognitive-snapshot-is-frozen",
        "m4-amcf-foundation-has-consolidated-qualification-gate",
        "agent-task-origin-and-background-mode-are-canonical",
        "agent-core-never-owns-tool-authority",
        "passive-agent-tasks-reuse-persistent-sovereign-plans",
        "agent-continuation-restores-exact-durable-plan-without-replay",
        "android-continuation-handoff-is-verified-and-non-authoritative",
        "android-agent-continuation-hosts-are-dedicated-and-one-step-bounded",
        "android-agent-wakes-advance-one-canonical-persistent-step",
        "cold-persisted-agent-wakes-rebuild-the-same-canonical-runtime-graph",
        "proactive-trigger-tasks-reuse-canonical-persistent-plan-engine",
        "proactive-event-wakes-bind-trigger-and-exact-durable-plan-state",
        "canonical-agent-wake-execution-is-serialized-against-duplicate-replay",
        "verified-event-wake-consumption-advances-one-canonical-persistent-step",
        "event-wake-consumer-acquires-canonical-runtime-only-after-verification",
        "proactive-event-wake-job-scheduling-is-verified-deduped-and-resource-governed",
        "event-wake-jobs-chain-only-fresh-checkpoints",
        "proactive-trigger-sources-are-user-configured-bounded-and-non-polling",
        "qualified-trigger-observations-enter-canonical-event-wake-admission",
        "proactive-trigger-registrations-live-in-encrypted-sovereign-memory",
        "accepted-trigger-observations-are-atomically-deduped-with-source-state",
        "scheduled-trigger-sources-use-bounded-persisted-jobs-not-polling",
        "app-local-trigger-sources-remain-event-driven",
        "accepted-trigger-observations-queue-inside-the-same-source-record",
        "pending-trigger-observations-lock-source-revision-and-removal",
        "pending-trigger-dispatch-uses-deterministic-canonical-plan-identity",
        "pending-trigger-fifo-acks-only-after-durable-verified-dispatch-binding",
        "pending-trigger-dispatch-reuses-governed-event-wake-scheduler",
        "pending-trigger-dispatch-adds-no-planner-tool-or-model-path",
        "pending-trigger-dispatch-job-identities-fail-closed-on-collision",
        "proactive-lifecycle-binding-persists-before-event-wake-and-trigger-fifo-ack",
        "proactive-lifecycle-ledger-stores-provenance-not-task-state",
        "proactive-task-state-is-derived-only-from-canonical-persistent-plan",
        "proactive-governed-decisions-reuse-existing-plan-approval-and-event-wake",
        "proactive-event-wake-plans-disable-manual-ui-advance",
        "proactive-lifecycle-ledger-is-bounded-and-terminal-compacted",
        "proactive-lifecycle-reconciliation-does-not-register-process-admissions",
        "proactive-lifecycle-provenance-corruption-fails-closed",
        "proactive-attention-is-lifecycle-derived-discoverability-only",
        "proactive-attention-never-approves-schedules-or-executes",
        "proactive-notification-copy-excludes-goal-source-payload-and-tool-input",
        "proactive-notification-navigation-validates-full-canonical-plan-id",
        "proactive-attention-failure-never-gates-event-wake-or-trigger-fifo-ack",
        "proactive-terminal-attention-surfaces-only-on-background-transition",
        "proactive-attention-ack-is-revision-scoped-ui-state-only",
        "proactive-attention-ack-lives-in-existing-encrypted-sovereign-memory",
        "stale-attention-revision-cannot-ack-new-canonical-state",
        "acknowledged-attention-never-mutates-plan-approval-scheduler-or-fifo",
        "attention-ack-corruption-fails-visible",
        "attention-ack-ledger-is-bounded-without-silent-eviction",
        "attention-ack-pruning-follows-lifecycle-membership-only",
        "proactive-history-is-read-only-projection-not-task-database",
        "proactive-history-reuses-canonical-plan-receipt-ledger",
        "proactive-history-reuses-existing-execution-inspector",
        "proactive-history-receipt-view-excludes-reason-input-output",
        "proactive-history-is-bounded-by-lifecycle-window",
        "proactive-history-missing-plan-never-synthesizes-receipts",
        "proactive-history-recovery-state-comes-from-canonical-receipt-evidence",
        "proactive-recovery-target-exists-only-for-unresolved-canonical-claim",
        "proactive-recovery-target-binds-plan-step-and-request-identity",
        "proactive-recovery-navigation-revalidates-current-canonical-recovery-state",
        "stale-proactive-recovery-target-never-falls-through-to-another-claim",
        "proactive-recovery-navigation-reuses-existing-sovereign-recovery-console",
        "proactive-recovery-navigation-never-reconciles-or-replays-provider",
        "proactive-surface-controls-are-refresh-or-navigation-only",
        "proactive-surface-exposes-no-approve-reject-cancel-advance-execute-control",
        "proactive-surface-refresh-revision-is-transient-ui-state-only",
        "proactive-surface-refresh-never-mutates-canonical-task-state",
        "successful-canonical-user-mutations-invalidate-proactive-read-model",
        "failed-canonical-user-mutations-do-not-claim-new-proactive-state",
        "foreground-lifecycle-reconciliation-invalidates-proactive-read-model",
        "proactive-history-snapshot-coherence-uses-bounded-optimistic-reread",
        "proactive-history-loaded-plan-view-avoids-second-plan-load",
        "proactive-history-lifecycle-before-after-must-match",
        "proactive-history-receipt-evidence-must-repeat-stably",
        "proactive-history-churn-fails-visible-after-bounded-retries",
        "proactive-history-coherence-adds-no-lock-scheduler-or-snapshot-store",
        "proactive-foreground-visibility-is-on-resume-event-driven",
        "proactive-foreground-visibility-reuses-phase662-and-phase663-reconcile",
        "proactive-foreground-visibility-adds-no-polling-loop-service-or-scheduler",
        "foreground-lifecycle-reconcile-gates-read-model-refresh",
        "foreground-attention-failure-never-gates-lifecycle-refresh",
        "foreground-terminal-attention-is-not-reposted",
        "main-activity-owns-one-proactive-lifecycle-observer",
        "m5-always-on-canonical-chain-has-closure-audit",
        "gguf-is-import-source-only",
        "adaptive-compute-fast-to-deep",
        "verification-before-confidence",
        "internet-is-governed-tool-not-model",
        "background-work-is-checkpointed",
        "foreground-work-survives-ui-exit",
        "portable-hardware-profile-not-device-name",
        "agent-actions-remain-audited",
        "memory-is-working-episodic-semantic-procedural",
        "m6-cognitive-memory-has-one-durable-memory-os",
        "working-memory-is-global-workspace-plus-memory-os-continuity",
        "episodic-semantic-procedural-memory-share-canonical-memory-os",
        "cognitive-memory-domains-are-non-authority",
        "cognitive-memory-has-no-model-or-backend-specific-silo",
        "m6-memory-topology-is-contract-not-second-memory-facade",
        "working-memory-is-capacity-salience-and-expiry-bounded",
        "working-memory-capacity-evicts-lowest-salience-oldest-first",
        "working-memory-continuity-checkpoints-use-same-memory-os",
        "working-memory-checkpoints-store-digest-metadata-only",
        "working-memory-continuity-chain-is-verified",
        "working-memory-checkpoint-write-cadence-is-bounded",
        "working-memory-continuity-is-non-authority",
        "working-memory-continuity-failure-does-not-block-transient-cognition",
        "episodic-memory-uses-canonical-memory-os-only",
        "episodic-memory-admission-is-importance-bounded",
        "episodic-memory-duplicate-suppression-is-window-bounded",
        "episodic-memory-retention-is-importance-and-recency-bounded",
        "episodic-memory-index-is-read-only-memory-os-projection",
        "episodic-memory-does-not-copy-conversation-semantic-or-procedural-stores",
        "episodic-memory-corruption-and-scan-overflow-fail-visible",
        "semantic-memory-query-and-reconcile-are-mobile-bounded",
        "semantic-memory-current-view-requires-fresh-epistemic-lineage",
        "semantic-memory-stale-evidence-is-hidden-before-reconciliation",
        "semantic-memory-changed-evidence-requires-new-version",
        "semantic-memory-corruption-fails-visible",
        "semantic-memory-uses-existing-memory-os-without-second-index",
        "semantic-memory-remains-non-authority",
        "3d-avatar-is-embodiment-not-cognition"
    )

    val milestones: List<OmegaMilestone> = listOf(
        OmegaMilestone(
            OmegaMilestoneId.M1_ARCHITECTURE_CONSOLIDATION,
            listOf(
                "OMEGA invariants locked in code and docs",
                "single AMPER Core production boundary retained",
                "connected internet gateway established",
                "background execution modes defined for Android",
                "AMI/AMNE v1 migration path to AMI2/AMNE2 is explicit"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M2_AMI2_COMPILER,
            listOf(
                "GGUF/source weights compile into AMI2",
                "tokenizer/chat protocol preserved",
                "device-independent logical graph separated from device packs",
                "deterministic lineage and integrity verified",
                "new GGUF imports publish direct canonical AMI2 before any legacy runtime bridge"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M3_AMNE2_RUNTIME,
            listOf(
                "verified AMI2 to AMNE2 execution admission boundary",
                "bounded AMI2 mmap execution view",
                "verified AMI2 decoder semantic binding reuses the qualified decoder stack",
                "single AMNE2 execution session owns decoder plan KV state and cancellation",
                "production AMPER Core inference consumes canonical AMI2 through AMNE2 sessions",
                "conversation hot-state reuse is bound to lifecycle and verified artifact identity",
                "hardware autotuning is process-local, measured and fail-closed to reference kernels",
                "mmap windows and session context are bounded by portable device memory budget",
                "context pressure chooses exact reuse, full rebuild, or explicit compaction without hidden KV eviction",
                "consolidated AMNE2 readiness exposes identity, memory budget, kernel dispatch, hot-session state and degraded reasons",
                "ARM64 kernel dispatch",
                "NEON DOTPROD I8MM FP16 qualification",
                "mmap paging and KV management",
                "device autotuner",
                "Vulkan enabled only when physical benchmark wins"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M4_AMCF_FOUNDATION,
            listOf(
                "single mobile cognitive architecture",
                "OMEGA compute modes map to bounded AMCF cycles over one verified foundation",
                "structured recurrent state carries confidence uncertainty and evidence without raw hidden reasoning",
                "AMCF cycle orchestration is bounded cancellation-aware and commits recurrent state only after successful cycles",
                "AMCF production cycles execute through the existing single-core AMI2/AMNE2 inference endpoint",
                "AMCF quality and early-exit reuse grounded integrated cognitive readiness without a judge model",
                "one immutable integrated cognitive snapshot is bound to the complete AMCF run",
                "adaptive depth and recurrent reasoning",
                "deep reasoning and verify/revise loop",
                "early exit for fast requests",
                "FAST REASON DEEP VERIFY evidence passes one consolidated M4 qualification gate"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON,
            listOf(
                "canonical user-request and proactive-trigger task contract",
                "passive user-request tasks reuse persistent sovereign plans and governed approvals",
                "foreground and persisted user tasks restore exact durable state without replay",
                "dedicated Android foreground and persisted-job hosts consume verified handoffs",
                "warm Android continuation chains one-step checkpoints through the canonical persistent planner",
                "persisted JobService cold-starts the same single-foundation runtime graph after process death or reboot",
                "proactive EVENT_WAKE tasks reuse the same persistent sovereign-plan engine and governed approval boundary",
                "verified proactive EVENT_WAKE handoffs bind trigger provenance and exact durable plan digest",
                "duplicate concurrent Agent wakes cannot race exact restore into repeated plan execution",
                "verified EVENT_WAKE consumption advances at most one proactive persistent-plan step and returns a fresh checkpoint",
                "Android EVENT_WAKE consumption verifies approval/terminal state before acquiring the canonical runtime graph",
                "persisted EVENT_WAKE scheduling uses verified stable dedupe plus battery thermal and memory-aware cadence",
                "EVENT_WAKE jobs chain only from fresh verified checkpoints and never schedule approval-blocked or terminal work",
                "proactive trigger sources are finite user-configured scheduled windows or app-local events with bounded cooldown",
                "qualified trigger observations produce canonical PROACTIVE_TRIGGER EVENT_WAKE admission without tool or planning authority",
                "user-configured trigger registrations and cooldown checkpoints survive restart in canonical encrypted sovereign memory",
                "accepted proactive observations atomically update source dedupe state without a second task or plan database",
                "scheduled-window trigger sources reconcile to bounded reboot-persistent Android jobs while app-local sources remain unscheduled",
                "accepted trigger observations remain in a bounded FIFO inside the same encrypted source record until canonical dispatch acknowledges them",
                "pending trigger observations prevent source revision or removal until their provenance is acknowledged",
                "oldest pending trigger observations bind to deterministic canonical persistent-plan identity across crash and retry",
                "pending trigger FIFO entries acknowledge only after verified durable handoff and required Phase657 scheduling succeed",
                "pending trigger dispatch uses one bounded resource-governed Android one-shot job per source instead of polling",
                "foreground reconciliation re-enable and new source observations recover durable pending trigger dispatch",
                "proactive trigger-to-task-to-plan provenance is durably bound before accepted-observation FIFO acknowledgement",
                "proactive lifecycle metadata stores immutable provenance only while live task state remains canonical persistent-plan state",
                "foreground proactive lifecycle reconciliation reuses Phase657 to schedule ready plans and cancel approval-blocked or terminal wakes",
                "governed proactive approve/reject remains the existing plan surface and re-arms only a verified Phase655 handoff afterward",
                "proactive EVENT_WAKE-owned plans cannot be manually advanced from the UI execution console",
                "proactive lifecycle provenance is bounded and compacts only canonically terminal plans",
                "proactive attention is derived only from Phase662 lifecycle state and adds no planner scheduler approval or execution authority",
                "approval-required and missing-plan attention remain discoverable while terminal notifications surface only on background transitions",
                "notification copy contains no goal source trigger payload or tool input and exposes only bounded generic state",
                "notification taps open only a validated full canonical proactive plan identity and never approve directly",
                "notification permission settings or delivery failure never gate Phase657 EVENT_WAKE outcomes or Phase660 FIFO acknowledgement",
                "proactive attention acknowledgement stores only PlanId revision fingerprint and acknowledgement time as UI state in existing encrypted sovereign memory",
                "notification taps acknowledge only when their embedded attention revision still matches the current lifecycle-derived canonical attention state",
                "acknowledging attention suppresses only that exact discoverability revision and never mutates plan approval scheduler EVENT_WAKE or trigger FIFO state",
                "acknowledgement read or decode failure defaults to surfacing attention instead of silently suppressing it",
                "the acknowledgement ledger is bounded without silent eviction and full reconciliation prunes only entries whose PlanIds are no longer lifecycle-tracked",
                "proactive task history is a bounded read-only projection over Phase662 lifecycle state and never persists a second task or history record",
                "proactive receipt presentation reuses the existing SovereignPlanExecutionInspector and canonical SovereignPlanReceiptLedger without writing during reads",
                "proactive receipt presentation exposes only bounded step status capability durability evidence receipt digest and reconciliation decision without reason input or output",
                "proactive history limits are bounded by the lifecycle capacity and missing canonical plans remain visible without synthetic receipt evidence",
                "proactive recovery presentation is derived only from existing unresolved claim and reconciliation evidence and cannot mutate or replay providers",
                "proactive recovery navigation targets exist only for CLAIMED_UNRESOLVED canonical receipt evidence",
                "each proactive recovery target binds exact PlanId step index and request id and is revalidated by the existing SovereignRecoveryConsole",
                "stale resolved or mismatched proactive recovery targets select no claim and never fall through to a different unresolved claim",
                "proactive recovery navigation is focus-only and cannot reconcile approve execute or replay a provider",
                "proactive recovery handling continues to use the existing canonical Recovery Console and receipt ledger with no second recovery authority",
                "proactive task controls are limited to status refresh governed-plan navigation and exact recovery navigation",
                "the proactive lifecycle/history surface contains no approve reject cancel advance execute or reconcile mutation authority",
                "proactive surface refresh revision is transient in-process UI invalidation state and is never persisted or used in plan lifecycle scheduler receipt or execution identity",
                "successful canonical cancellation approval rejection and recovery mutations invalidate the proactive read model after durable state changes",
                "failed canonical mutations do not advance the proactive surface refresh revision or synthesize success state",
                "foreground lifecycle reconciliation invalidates the proactive read model so durable process-death or background changes are re-read on app entry",
                "proactive lifecycle history uses bounded optimistic reread instead of holding locks across plan scheduler or receipt operations",
                "the exact loaded canonical plan derives its lifecycle view without a second plan load and must equal the lifecycle view captured before projection",
                "lifecycle views captured before and after a proactive history projection must match before the snapshot is presented",
                "canonical receipt evidence is read twice from the same loaded plan and must match before a proactive history entry is accepted",
                "continuous plan or receipt churn fails visible after three bounded attempts instead of presenting a mixed snapshot",
                "snapshot coherence persists no cache snapshot task state lock lease or scheduler state",
                "foreground proactive visibility reconciliation is driven only by MainActivity ON_RESUME lifecycle events and has no polling cadence",
                "foreground visibility reuses the existing Phase662 lifecycle controller and Phase663 attention controller without a second observer service worker or scheduler",
                "successful foreground lifecycle reconciliation invalidates the Phase667 proactive read model while lifecycle failure remains fail-closed",
                "foreground attention reconciliation failure cannot block a successful canonical lifecycle refresh",
                "completed and failed proactive terminal notifications are cancelled rather than reposted during FOREGROUND_RECONCILE while approval and missing-plan attention keep existing Phase663 policy",
                "the proactive lifecycle observer is registered and removed inside the existing MainActivity DisposableEffect lifecycle",
                "M5 canonical closure audit covers every Phase647 through Phase669 slice plus foundational single-foundation authority background and internet constraints",
                "passive tool execution",
                "proactive goals and triggers",
                "foreground continuation after UI exit",
                "persisted jobs restore after process death/reboot",
                "internet observation and task checkpointing"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M6_COGNITIVE_MEMORY,
            listOf(
                "working memory",
                "episodic memory",
                "semantic memory",
                "procedural skill memory",
                "canonical cognitive memory topology exposes working episodic semantic and procedural domains over one durable MemoryOs",
                "working memory uses the existing GlobalWorkspace for transient cognition while durable continuity snapshots remain in the same MemoryOs",
                "episodic semantic and procedural durable memory are canonical MemoryOs domains rather than model or backend specific stores",
                "cognitive memory is evidence and state only and carries no tool approval scheduler or execution authority",
                "the M6 topology seal exposes no second remember recall or persistence facade",
                "canonical working memory has bounded capacity salience admission and clock-based expiry",
                "working-memory overflow evicts lowest-salience oldest events deterministically",
                "working-memory continuity uses one periodically updated digest-only checkpoint in the same MemoryOs",
                "working-memory continuity checkpoint chaining verifies sequence and previous checkpoint digest before overwrite",
                "working-memory continuity persists no CognitiveEvent topic payload or raw hidden reasoning",
                "working-memory checkpoint write cadence is bounded to reduce mobile journal write amplification",
                "working-memory continuity failure is fail-visible but does not block bounded transient cognition",
                "canonical episodic admission writes only episodic-v1 records into the existing MemoryOs",
                "episodic admission applies importance threshold bounded content and duplicate-window suppression",
                "episodic retention keeps at most 256 episodes and prefers higher importance then newer evidence",
                "episodic retrieval is a bounded read-only projection over MemoryOs and creates no second index database",
                "conversation turns semantic knowledge and procedural memory are disallowed episodic origins and are never copied",
                "episodic corruption or MemoryOs scan-bound overflow fails visible instead of returning partial history",
                "semantic query and reconcile expose at most 16 results with at most 192 candidate semantic records scanned",
                "semantic current and query results require planning-eligible current epistemic evidence with matching value and evidence lineage",
                "stale contested uncertain or changed evidence hides stored active semantic knowledge until canonical reconcile revises or retracts it",
                "semantic record corruption fails visible instead of being silently skipped",
                "semantic freshness hardening reuses MemoryBackedSemanticKnowledgeStore and MemoryOs with no second semantic index or model store"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M7_MULTIMODAL_VOICE,
            listOf(
                "vision",
                "screen context",
                "speech input",
                "speech output",
                "multimodal tool execution"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M8_3D_EMBODIMENT_HARDENING,
            listOf(
                "interactive 3D avatar",
                "lip sync gaze gestures and state animation",
                "thermal battery and memory adaptation",
                "crash/update migration",
                "cross-device physical qualification suite"
            )
        )
    )
}
