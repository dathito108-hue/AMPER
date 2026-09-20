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
