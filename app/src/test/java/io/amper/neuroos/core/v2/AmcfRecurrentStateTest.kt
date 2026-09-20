package io.amper.neuroos.core.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmcfRecurrentStateTest {
    private val foundation = AmcfFoundationBinding(
        foundationId = "amper-foundation",
        semanticSha256 = "1".repeat(64)
    )

    @Test
    fun strongReasonStateCanExitRemainingDeliberationButNotVerification() {
        val plan = reasonPlan()
        val first = AmcfRecurrentStateTransition.record(
            cycle = plan.cycles[0],
            previous = null,
            candidateDigest = "2".repeat(64),
            evidenceDigest = "3".repeat(64),
            confidence = 0.70,
            uncertainty = 0.30,
            evidenceSufficiency = 0.65
        )
        val second = AmcfRecurrentStateTransition.record(
            cycle = plan.cycles[1],
            previous = first,
            candidateDigest = "4".repeat(64),
            evidenceDigest = "5".repeat(64),
            confidence = 0.90,
            uncertainty = 0.10,
            evidenceSufficiency = 0.90
        )

        val decision = AmcfEarlyExitGate.decide(
            plan = plan,
            completedCycle = plan.cycles[1],
            state = second
        )

        assertEquals(AmcfEarlyExitAction.ADVANCE_TO_VERIFY, decision.action)
        assertEquals("recurrent-evidence-sufficient", decision.reasonCode)
        assertTrue(plan.cycles[3].mandatory)
        assertEquals(AmcfComputeCycleKind.VERIFY, plan.cycles[3].kind)
        assertEquals(AmcfComputeCycleKind.REVISE, plan.cycles[4].kind)
    }

    @Test
    fun weakReasonStateContinuesDeliberation() {
        val plan = reasonPlan()
        val state = AmcfRecurrentStateTransition.record(
            cycle = plan.cycles[1],
            previous = AmcfRecurrentStateTransition.record(
                cycle = plan.cycles[0],
                previous = null,
                candidateDigest = "2".repeat(64),
                evidenceDigest = "3".repeat(64),
                confidence = 0.60,
                uncertainty = 0.35,
                evidenceSufficiency = 0.60
            ),
            candidateDigest = "4".repeat(64),
            evidenceDigest = "5".repeat(64),
            confidence = 0.82,
            uncertainty = 0.19,
            evidenceSufficiency = 0.79
        )

        val decision = AmcfEarlyExitGate.decide(
            plan = plan,
            completedCycle = plan.cycles[1],
            state = state
        )

        assertEquals(AmcfEarlyExitAction.CONTINUE_DELIBERATION, decision.action)
        assertEquals("recurrent-evidence-insufficient", decision.reasonCode)
    }

    @Test
    fun verifyModeNeverMarksDeliberationAsEarlyExitEligible() {
        val plan = AmcfComputeCyclePlanner.plan(
            foundation = foundation,
            budget = OmegaComputeBudget(
                mode = OmegaComputeMode.VERIFY,
                recurrentCycles = 8,
                verifyPasses = 2,
                targetFirstTokenMs = null,
                allowInternetVerification = true,
                allowToolUse = true
            )
        )
        val state = AmcfRecurrentStateTransition.record(
            cycle = plan.cycles[7],
            previous = null,
            candidateDigest = "2".repeat(64),
            evidenceDigest = "3".repeat(64),
            confidence = 1.0,
            uncertainty = 0.0,
            evidenceSufficiency = 1.0
        )

        val decision = AmcfEarlyExitGate.decide(
            plan = plan,
            completedCycle = plan.cycles[7],
            state = state
        )

        assertEquals(AmcfEarlyExitAction.NOT_ELIGIBLE, decision.action)
    }

    @Test
    fun revisionRequiresAnUnmatchedVerification() {
        val plan = reasonPlan()
        val result = runCatching {
            AmcfRecurrentStateTransition.record(
                cycle = plan.cycles[4],
                previous = AmcfRecurrentStateTransition.record(
                    cycle = plan.cycles[1],
                    previous = null,
                    candidateDigest = "2".repeat(64),
                    evidenceDigest = "3".repeat(64),
                    confidence = 0.9,
                    uncertainty = 0.1,
                    evidenceSufficiency = 0.9
                ),
                candidateDigest = "4".repeat(64),
                evidenceDigest = "5".repeat(64),
                confidence = 0.9,
                uncertainty = 0.1,
                evidenceSufficiency = 0.9
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun recurrentDigestChangesWithStructuredStateWithoutRawReasoningText() {
        val plan = reasonPlan()
        val first = AmcfRecurrentStateTransition.record(
            cycle = plan.cycles[0],
            previous = null,
            candidateDigest = "2".repeat(64),
            evidenceDigest = "3".repeat(64),
            confidence = 0.81,
            uncertainty = 0.19,
            evidenceSufficiency = 0.80
        )
        val changed = first.copy(confidence = 0.82)

        assertTrue(first.stateDigest.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first.stateDigest, changed.stateDigest)
    }

    private fun reasonPlan(): AmcfComputeCyclePlan =
        AmcfComputeCyclePlanner.plan(
            foundation = foundation,
            budget = OmegaComputeBudget(
                mode = OmegaComputeMode.REASON,
                recurrentCycles = 3,
                verifyPasses = 1,
                targetFirstTokenMs = 4_000L,
                allowInternetVerification = true,
                allowToolUse = true
            )
        )
}
