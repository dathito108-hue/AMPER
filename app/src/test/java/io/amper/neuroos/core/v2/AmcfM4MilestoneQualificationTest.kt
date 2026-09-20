package io.amper.neuroos.core.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmcfM4MilestoneQualificationTest {
    private val foundation = AmcfFoundationBinding(
        foundationId = "amper-foundation",
        semanticSha256 = "1".repeat(64)
    )

    @Test
    fun representativeFastReasonDeepVerifyEvidenceClosesM4() {
        val report = AmcfM4MilestoneQualifier.evaluate(completeEvidence())

        assertTrue(report.qualified)
        assertEquals(foundation, report.foundation)
        assertEquals(AmcfM4MilestoneQualifier.requiredModes, report.observedModes)
        assertEquals(
            AmcfM4QualificationCriterion.values().toSet(),
            report.satisfiedCriteria
        )
        assertTrue(report.missingCriteria.isEmpty())
    }

    @Test
    fun missingModeCannotCloseM4() {
        val evidence = completeEvidence()
            .filterNot { it.mode == OmegaComputeMode.DEEP }

        val report = AmcfM4MilestoneQualifier.evaluate(evidence)

        assertFalse(report.qualified)
        assertFalse(OmegaComputeMode.DEEP in report.observedModes)
        assertTrue(
            AmcfM4QualificationCriterion.BOUNDED_ADAPTIVE_DEPTH in
                report.missingCriteria
        )
    }

    @Test
    fun differentFoundationCannotQualifySingleFoundationArchitecture() {
        val evidence = completeEvidence().map { sample ->
            if (sample.mode == OmegaComputeMode.DEEP) {
                sample.copy(
                    foundation = foundation.copy(
                        semanticSha256 = "2".repeat(64)
                    )
                )
            } else {
                sample
            }
        }

        val report = AmcfM4MilestoneQualifier.evaluate(evidence)

        assertFalse(report.qualified)
        assertTrue(
            AmcfM4QualificationCriterion.SINGLE_FOUNDATION_AMNE2 in
                report.missingCriteria
        )
    }

    @Test
    fun verifyWithoutPairedRevisionCannotCloseM4() {
        val evidence = completeEvidence().map { sample ->
            if (sample.mode == OmegaComputeMode.VERIFY) {
                sample.copy(
                    committedCycleKinds =
                        sample.committedCycleKinds.filterNot {
                            it == AmcfComputeCycleKind.REVISE
                        }
                )
            } else {
                sample
            }
        }

        val report = AmcfM4MilestoneQualifier.evaluate(evidence)

        assertFalse(report.qualified)
        assertTrue(
            AmcfM4QualificationCriterion.VERIFY_REVISE_LOOP in
                report.missingCriteria
        )
    }

    private fun completeEvidence(): List<AmcfM4RunEvidence> = listOf(
        evidence(
            mode = OmegaComputeMode.FAST,
            committed = listOf(AmcfComputeCycleKind.FINALIZE)
        ),
        evidence(
            mode = OmegaComputeMode.REASON,
            committed = listOf(
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.VERIFY,
                AmcfComputeCycleKind.REVISE,
                AmcfComputeCycleKind.FINALIZE
            ),
            earlyExit = listOf(
                AmcfEarlyExitAction.NOT_ELIGIBLE,
                AmcfEarlyExitAction.ADVANCE_TO_VERIFY
            )
        ),
        evidence(
            mode = OmegaComputeMode.DEEP,
            committed = listOf(
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.VERIFY,
                AmcfComputeCycleKind.REVISE,
                AmcfComputeCycleKind.FINALIZE
            ),
            earlyExit = listOf(
                AmcfEarlyExitAction.NOT_ELIGIBLE,
                AmcfEarlyExitAction.NOT_ELIGIBLE,
                AmcfEarlyExitAction.NOT_ELIGIBLE,
                AmcfEarlyExitAction.ADVANCE_TO_VERIFY
            )
        ),
        evidence(
            mode = OmegaComputeMode.VERIFY,
            committed = buildList {
                repeat(8) { add(AmcfComputeCycleKind.DELIBERATE) }
                add(AmcfComputeCycleKind.VERIFY)
                add(AmcfComputeCycleKind.REVISE)
                add(AmcfComputeCycleKind.VERIFY)
                add(AmcfComputeCycleKind.REVISE)
                add(AmcfComputeCycleKind.FINALIZE)
            }
        )
    )

    private fun evidence(
        mode: OmegaComputeMode,
        committed: List<AmcfComputeCycleKind>,
        earlyExit: List<AmcfEarlyExitAction> = emptyList()
    ): AmcfM4RunEvidence {
        val budget = budget(mode)
        val plan = AmcfComputeCyclePlanner.plan(
            foundation = foundation,
            budget = budget
        )
        return AmcfM4RunEvidence(
            mode = mode,
            foundation = foundation,
            cognitiveStateDigest = mode.name
                .lowercase()
                .padEnd(64, 'a')
                .take(64)
                .let(::shaLike),
            plannedTotalCycles = plan.maximumTotalCycles,
            plannedDeliberationCycles = plan.maximumDeliberationCycles,
            committedCycleKinds = committed,
            earlyExitActions = earlyExit,
            terminalStateDigest = mode.ordinal
                .toString(16)
                .padStart(64, '0')
                .takeLast(64)
        )
    }

    private fun budget(mode: OmegaComputeMode): OmegaComputeBudget =
        when (mode) {
            OmegaComputeMode.FAST -> OmegaComputeBudget(
                mode = mode,
                recurrentCycles = 0,
                verifyPasses = 0,
                targetFirstTokenMs = 1_500L,
                allowInternetVerification = false,
                allowToolUse = false
            )
            OmegaComputeMode.REASON -> OmegaComputeBudget(
                mode = mode,
                recurrentCycles = 3,
                verifyPasses = 1,
                targetFirstTokenMs = 4_000L,
                allowInternetVerification = false,
                allowToolUse = false
            )
            OmegaComputeMode.DEEP -> OmegaComputeBudget(
                mode = mode,
                recurrentCycles = 6,
                verifyPasses = 1,
                targetFirstTokenMs = null,
                allowInternetVerification = true,
                allowToolUse = true
            )
            OmegaComputeMode.VERIFY -> OmegaComputeBudget(
                mode = mode,
                recurrentCycles = 8,
                verifyPasses = 2,
                targetFirstTokenMs = null,
                allowInternetVerification = true,
                allowToolUse = true
            )
            else -> error("qualification fixture only supports required M4 modes")
        }

    private fun shaLike(seed: String): String {
        val hex = seed.encodeToByteArray()
            .joinToString("") { "%02x".format(it) }
        return hex.padEnd(64, '0').take(64)
    }
}
