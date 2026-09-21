package io.amper.neuroos.core.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaCoreV2Test {
    @Test
    fun architectureLockKeepsSingleCoreAndEightMilestones() {
        assertEquals("AMPER-MOBILE-OMEGA", OmegaArchitectureLock.architectureId)
        assertEquals(2, OmegaArchitectureLock.architectureVersion)
        assertEquals(8, OmegaArchitectureLock.milestones.size)
        assertTrue("one-amper-foundation-runtime" in OmegaArchitectureLock.invariants)
        assertTrue("ami2-is-canonical-model-format" in OmegaArchitectureLock.invariants)
        assertTrue("amne2-is-canonical-execution-engine" in OmegaArchitectureLock.invariants)
        assertTrue("conversation-hot-state-is-identity-bound" in OmegaArchitectureLock.invariants)
        assertTrue("hardware-autotuning-is-measured-and-fail-closed" in OmegaArchitectureLock.invariants)
        assertTrue("memory-and-context-are-hardware-budgeted" in OmegaArchitectureLock.invariants)
        assertTrue("context-pressure-never-silently-evicts-kv" in OmegaArchitectureLock.invariants)
        assertTrue(
            "runtime-readiness-is-consolidated-and-model-specific" in
                OmegaArchitectureLock.invariants
        )
        assertTrue("amcf-cycles-stay-on-one-foundation" in OmegaArchitectureLock.invariants)
        assertTrue(
            "amcf-recurrent-state-is-structured-and-bounded" in
                OmegaArchitectureLock.invariants
        )
        assertTrue("amcf-cycle-state-commit-is-transactional" in OmegaArchitectureLock.invariants)
        assertTrue(
            "amcf-production-port-reuses-single-core-endpoint" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "amcf-quality-reuses-integrated-cognitive-readiness" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "amcf-run-cognitive-snapshot-is-frozen" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "m4-amcf-foundation-has-consolidated-qualification-gate" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "agent-task-origin-and-background-mode-are-canonical" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "agent-core-never-owns-tool-authority" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "passive-agent-tasks-reuse-persistent-sovereign-plans" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "agent-continuation-restores-exact-durable-plan-without-replay" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "android-continuation-handoff-is-verified-and-non-authoritative" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "android-agent-continuation-hosts-are-dedicated-and-one-step-bounded" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "android-agent-wakes-advance-one-canonical-persistent-step" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "cold-persisted-agent-wakes-rebuild-the-same-canonical-runtime-graph" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-trigger-tasks-reuse-canonical-persistent-plan-engine" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-event-wakes-bind-trigger-and-exact-durable-plan-state" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "canonical-agent-wake-execution-is-serialized-against-duplicate-replay" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "verified-event-wake-consumption-advances-one-canonical-persistent-step" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "event-wake-consumer-acquires-canonical-runtime-only-after-verification" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-event-wake-job-scheduling-is-verified-deduped-and-resource-governed" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "event-wake-jobs-chain-only-fresh-checkpoints" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-trigger-sources-are-user-configured-bounded-and-non-polling" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "qualified-trigger-observations-enter-canonical-event-wake-admission" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-trigger-registrations-live-in-encrypted-sovereign-memory" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "accepted-trigger-observations-are-atomically-deduped-with-source-state" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "scheduled-trigger-sources-use-bounded-persisted-jobs-not-polling" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "app-local-trigger-sources-remain-event-driven" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "accepted-trigger-observations-queue-inside-the-same-source-record" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "pending-trigger-observations-lock-source-revision-and-removal" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "pending-trigger-dispatch-uses-deterministic-canonical-plan-identity" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "pending-trigger-fifo-acks-only-after-durable-verified-dispatch-binding" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "pending-trigger-dispatch-reuses-governed-event-wake-scheduler" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "pending-trigger-dispatch-adds-no-planner-tool-or-model-path" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "pending-trigger-dispatch-job-identities-fail-closed-on-collision" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-lifecycle-binding-persists-before-trigger-fifo-ack" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-lifecycle-ledger-stores-provenance-not-task-state" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-task-state-is-derived-only-from-canonical-persistent-plan" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-governed-decisions-reuse-existing-plan-approval-and-event-wake" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-event-wake-plans-disable-manual-ui-advance" in
                OmegaArchitectureLock.invariants
        )
        assertTrue(
            "proactive-lifecycle-ledger-is-bounded-and-terminal-compacted" in
                OmegaArchitectureLock.invariants
        )
        assertTrue("gguf-is-import-source-only" in OmegaArchitectureLock.invariants)
        assertTrue("internet-is-governed-tool-not-model" in OmegaArchitectureLock.invariants)
        assertTrue("foreground-work-survives-ui-exit" in OmegaArchitectureLock.invariants)
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M1_ARCHITECTURE_CONSOLIDATION }
                .exitCriteria
                .contains("AMI/AMNE v1 migration path to AMI2/AMNE2 is explicit")
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M2_AMI2_COMPILER }
                .exitCriteria
                .contains(
                    "new GGUF imports publish direct canonical AMI2 before any legacy runtime bridge"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M3_AMNE2_RUNTIME }
                .exitCriteria
                .contains("verified AMI2 to AMNE2 execution admission boundary")
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M3_AMNE2_RUNTIME }
                .exitCriteria
                .contains("bounded AMI2 mmap execution view")
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M3_AMNE2_RUNTIME }
                .exitCriteria
                .contains(
                    "verified AMI2 decoder semantic binding reuses the qualified decoder stack"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M3_AMNE2_RUNTIME }
                .exitCriteria
                .contains(
                    "single AMNE2 execution session owns decoder plan KV state and cancellation"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M3_AMNE2_RUNTIME }
                .exitCriteria
                .contains(
                    "production AMPER Core inference consumes canonical AMI2 through AMNE2 sessions"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M3_AMNE2_RUNTIME }
                .exitCriteria
                .contains(
                    "conversation hot-state reuse is bound to lifecycle and verified artifact identity"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M3_AMNE2_RUNTIME }
                .exitCriteria
                .contains(
                    "hardware autotuning is process-local, measured and fail-closed to reference kernels"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M3_AMNE2_RUNTIME }
                .exitCriteria
                .contains(
                    "mmap windows and session context are bounded by portable device memory budget"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M3_AMNE2_RUNTIME }
                .exitCriteria
                .contains(
                    "context pressure chooses exact reuse, full rebuild, or explicit compaction without hidden KV eviction"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M3_AMNE2_RUNTIME }
                .exitCriteria
                .contains(
                    "consolidated AMNE2 readiness exposes identity, memory budget, kernel dispatch, hot-session state and degraded reasons"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M4_AMCF_FOUNDATION }
                .exitCriteria
                .contains(
                    "OMEGA compute modes map to bounded AMCF cycles over one verified foundation"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M4_AMCF_FOUNDATION }
                .exitCriteria
                .contains(
                    "structured recurrent state carries confidence uncertainty and evidence without raw hidden reasoning"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M4_AMCF_FOUNDATION }
                .exitCriteria
                .contains(
                    "AMCF cycle orchestration is bounded cancellation-aware and commits recurrent state only after successful cycles"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M4_AMCF_FOUNDATION }
                .exitCriteria
                .contains(
                    "AMCF production cycles execute through the existing single-core AMI2/AMNE2 inference endpoint"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M4_AMCF_FOUNDATION }
                .exitCriteria
                .contains(
                    "AMCF quality and early-exit reuse grounded integrated cognitive readiness without a judge model"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M4_AMCF_FOUNDATION }
                .exitCriteria
                .contains(
                    "one immutable integrated cognitive snapshot is bound to the complete AMCF run"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M4_AMCF_FOUNDATION }
                .exitCriteria
                .contains(
                    "FAST REASON DEEP VERIFY evidence passes one consolidated M4 qualification gate"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains("canonical user-request and proactive-trigger task contract")
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "passive user-request tasks reuse persistent sovereign plans and governed approvals"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "foreground and persisted user tasks restore exact durable state without replay"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "dedicated Android foreground and persisted-job hosts consume verified handoffs"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "warm Android continuation chains one-step checkpoints through the canonical persistent planner"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "persisted JobService cold-starts the same single-foundation runtime graph after process death or reboot"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "proactive EVENT_WAKE tasks reuse the same persistent sovereign-plan engine and governed approval boundary"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "verified proactive EVENT_WAKE handoffs bind trigger provenance and exact durable plan digest"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "duplicate concurrent Agent wakes cannot race exact restore into repeated plan execution"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "verified EVENT_WAKE consumption advances at most one proactive persistent-plan step and returns a fresh checkpoint"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "Android EVENT_WAKE consumption verifies approval/terminal state before acquiring the canonical runtime graph"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "persisted EVENT_WAKE scheduling uses verified stable dedupe plus battery thermal and memory-aware cadence"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "EVENT_WAKE jobs chain only from fresh verified checkpoints and never schedule approval-blocked or terminal work"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "proactive trigger sources are finite user-configured scheduled windows or app-local events with bounded cooldown"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "qualified trigger observations produce canonical PROACTIVE_TRIGGER EVENT_WAKE admission without tool or planning authority"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "user-configured trigger registrations and cooldown checkpoints survive restart in canonical encrypted sovereign memory"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "accepted proactive observations atomically update source dedupe state without a second task or plan database"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "scheduled-window trigger sources reconcile to bounded reboot-persistent Android jobs while app-local sources remain unscheduled"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "accepted trigger observations remain in a bounded FIFO inside the same encrypted source record until canonical dispatch acknowledges them"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "pending trigger observations prevent source revision or removal until their provenance is acknowledged"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "oldest pending trigger observations bind to deterministic canonical persistent-plan identity across crash and retry"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "pending trigger FIFO entries acknowledge only after verified durable handoff and required Phase657 scheduling succeed"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "pending trigger dispatch uses one bounded resource-governed Android one-shot job per source instead of polling"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "foreground reconciliation re-enable and new source observations recover durable pending trigger dispatch"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "proactive trigger-to-task-to-plan provenance is durably bound before accepted-observation FIFO acknowledgement"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "proactive lifecycle metadata stores immutable provenance only while live task state remains canonical persistent-plan state"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "foreground proactive lifecycle reconciliation reuses Phase657 to schedule ready plans and cancel approval-blocked or terminal wakes"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "governed proactive approve/reject remains the existing plan surface and re-arms only a verified Phase655 handoff afterward"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "proactive EVENT_WAKE-owned plans cannot be manually advanced from the UI execution console"
                )
        )
        assertTrue(
            OmegaArchitectureLock.milestones
                .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
                .exitCriteria
                .contains(
                    "proactive lifecycle provenance is bounded and compacts only canonically terminal plans"
                )
        )
    }

    @Test
    fun simpleRequestsChooseFastCompute() {
        val budget = OmegaAdaptiveComputePolicy.plan(
            OmegaReasoningRequest(
                userComplexity = 10,
                uncertainty = 0.1,
                highConsequence = false,
                toolUseful = false,
                internetUseful = false
            )
        )

        assertEquals(OmegaComputeMode.FAST, budget.mode)
        assertEquals(0, budget.recurrentCycles)
        assertEquals(0, budget.verifyPasses)
        assertEquals(1_500L, budget.targetFirstTokenMs)
        assertFalse(budget.allowInternetVerification)
    }

    @Test
    fun highConsequenceRequestsChooseVerifiedDeepCompute() {
        val budget = OmegaAdaptiveComputePolicy.plan(
            OmegaReasoningRequest(
                userComplexity = 75,
                uncertainty = 0.8,
                highConsequence = true,
                toolUseful = true,
                internetUseful = true
            )
        )

        assertEquals(OmegaComputeMode.VERIFY, budget.mode)
        assertTrue(budget.recurrentCycles >= 6)
        assertTrue(budget.verifyPasses >= 2)
        assertTrue(budget.allowInternetVerification)
        assertTrue(budget.allowToolUse)
    }

    @Test
    fun backgroundPolicySeparatesUiExitFromProcessDeath() {
        assertEquals(
            OmegaBackgroundMode.FOREGROUND_CONTINUATION,
            OmegaBackgroundExecutionPolicy.choose(
                OmegaBackgroundWork(
                    expectedRuntimeMs = 60_000L,
                    userInitiated = true,
                    mustSurviveUiExit = true,
                    canBeDeferred = false,
                    hasFutureTrigger = false
                )
            )
        )
        assertEquals(
            OmegaBackgroundMode.PERSISTED_JOB,
            OmegaBackgroundExecutionPolicy.choose(
                OmegaBackgroundWork(
                    expectedRuntimeMs = 30_000L,
                    userInitiated = false,
                    mustSurviveUiExit = true,
                    canBeDeferred = true,
                    hasFutureTrigger = false
                )
            )
        )
        assertEquals(
            OmegaBackgroundMode.EVENT_WAKE,
            OmegaBackgroundExecutionPolicy.choose(
                OmegaBackgroundWork(
                    expectedRuntimeMs = 1_000L,
                    userInitiated = false,
                    mustSurviveUiExit = true,
                    canBeDeferred = true,
                    hasFutureTrigger = true
                )
            )
        )
    }
}
