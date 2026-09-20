package io.amper.neuroos.core.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmcfComputeCycleContractTest {
    private val foundation = AmcfFoundationBinding(
        foundationId = "amper-foundation",
        semanticSha256 = "1".repeat(64)
    )

    @Test
    fun fastModeFinalizesOnSameFoundationWithoutExtraReasoning() {
        val budget = OmegaComputeBudget(
            mode = OmegaComputeMode.FAST,
            recurrentCycles = 0,
            verifyPasses = 0,
            targetFirstTokenMs = 1_500L,
            allowInternetVerification = false,
            allowToolUse = false
        )

        val plan = AmcfComputeCyclePlanner.plan(foundation, budget)

        assertEquals(1, plan.maximumTotalCycles)
        assertEquals(0, plan.maximumDeliberationCycles)
        assertFalse(plan.earlyExitSupported)
        assertEquals(AmcfComputeCycleKind.FINALIZE, plan.cycles.single().kind)
        assertTrue(plan.cycles.all { it.foundation == foundation })
        assertEquals("AMNE2", plan.foundation.executionEngine)
    }

    @Test
    fun reasonModeHasBoundedEarlyExitOnlyAfterMinimumDepth() {
        val budget = OmegaComputeBudget(
            mode = OmegaComputeMode.REASON,
            recurrentCycles = 3,
            verifyPasses = 1,
            targetFirstTokenMs = 4_000L,
            allowInternetVerification = true,
            allowToolUse = true
        )

        val plan = AmcfComputeCyclePlanner.plan(foundation, budget)

        assertEquals(2, plan.minimumDeliberationCycles)
        assertEquals(3, plan.maximumDeliberationCycles)
        assertEquals(1, plan.verifyPasses)
        assertEquals(
            listOf(
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.DELIBERATE,
                AmcfComputeCycleKind.VERIFY,
                AmcfComputeCycleKind.REVISE,
                AmcfComputeCycleKind.FINALIZE
            ),
            plan.cycles.map { it.kind }
        )
        assertFalse(plan.cycles[0].earlyExitEligibleAfter)
        assertFalse(plan.cycles[1].earlyExitEligibleAfter)
        assertTrue(plan.cycles[2].earlyExitEligibleAfter)
        assertTrue(plan.cycles.drop(3).all { it.mandatory })
        assertTrue(plan.cycles.all { it.foundation.semanticSha256 == foundation.semanticSha256 })
    }

    @Test
    fun verifyModeCannotEarlyExitAndPairsEveryVerificationWithRevision() {
        val budget = OmegaComputeBudget(
            mode = OmegaComputeMode.VERIFY,
            recurrentCycles = 8,
            verifyPasses = 2,
            targetFirstTokenMs = null,
            allowInternetVerification = true,
            allowToolUse = true
        )

        val plan = AmcfComputeCyclePlanner.plan(foundation, budget)

        assertEquals(8, plan.minimumDeliberationCycles)
        assertEquals(8, plan.maximumDeliberationCycles)
        assertEquals(2, plan.verifyPasses)
        assertFalse(plan.earlyExitSupported)
        assertEquals(13, plan.maximumTotalCycles)
        assertEquals(2, plan.cycles.count { it.kind == AmcfComputeCycleKind.VERIFY })
        assertEquals(2, plan.cycles.count { it.kind == AmcfComputeCycleKind.REVISE })
        assertEquals(AmcfComputeCycleKind.FINALIZE, plan.cycles.last().kind)
        assertTrue(plan.cycles.all { it.foundation == foundation })
    }

    @Test
    fun invalidExecutionEngineCannotCreateSecondAmcfRuntime() {
        val result = runCatching {
            AmcfFoundationBinding(
                foundationId = "amper-foundation",
                semanticSha256 = "1".repeat(64),
                executionEngine = "OTHER"
            )
        }

        assertTrue(result.isFailure)
    }
}
