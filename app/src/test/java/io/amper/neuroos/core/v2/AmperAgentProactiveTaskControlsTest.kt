package io.amper.neuroos.core.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperAgentProactiveTaskControlsTest {
    @Test
    fun missingPlanExposesRefreshOnly() {
        assertEquals(
            setOf(AmperAgentProactiveTaskControl.REFRESH_STATUS),
            AmperAgentProactiveTaskControlPolicy.controls(
                AmperAgentProactiveTaskControlContext(
                    planAvailable = false,
                    exactRecoveryTargetAvailable = false
                )
            )
        )
    }

    @Test
    fun canonicalPlanAddsNavigationButNoMutationControl() {
        val controls = AmperAgentProactiveTaskControlPolicy.controls(
            AmperAgentProactiveTaskControlContext(
                planAvailable = true,
                exactRecoveryTargetAvailable = false
            )
        )

        assertTrue(AmperAgentProactiveTaskControl.REFRESH_STATUS in controls)
        assertTrue(AmperAgentProactiveTaskControl.OPEN_GOVERNED_PLAN in controls)
        assertFalse(AmperAgentProactiveTaskControl.OPEN_RECOVERY in controls)

        val names = AmperAgentProactiveTaskControl.entries.map { it.name }
        assertTrue(names.none { it.contains("APPROVE") })
        assertTrue(names.none { it.contains("REJECT") })
        assertTrue(names.none { it.contains("CANCEL") })
        assertTrue(names.none { it.contains("ADVANCE") })
        assertTrue(names.none { it.contains("EXECUTE") })
        assertTrue(names.none { it.contains("RECONCILE") })
    }

    @Test
    fun exactRecoveryEvidenceAddsNavigationOnly() {
        assertEquals(
            setOf(
                AmperAgentProactiveTaskControl.REFRESH_STATUS,
                AmperAgentProactiveTaskControl.OPEN_GOVERNED_PLAN,
                AmperAgentProactiveTaskControl.OPEN_RECOVERY
            ),
            AmperAgentProactiveTaskControlPolicy.controls(
                AmperAgentProactiveTaskControlContext(
                    planAvailable = true,
                    exactRecoveryTargetAvailable = true
                )
            )
        )
    }

    @Test
    fun recoveryControlCannotExistWithoutCanonicalPlan() {
        assertTrue(
            runCatching {
                AmperAgentProactiveTaskControlContext(
                    planAvailable = false,
                    exactRecoveryTargetAvailable = true
                )
            }.isFailure
        )
    }

    @Test
    fun refreshRevisionIsTransientMonotonicWithBoundedWrap() {
        assertEquals(1L, AmperAgentProactiveSurfaceRefreshRevision.next(0L))
        assertEquals(42L, AmperAgentProactiveSurfaceRefreshRevision.next(41L))
        assertEquals(0L, AmperAgentProactiveSurfaceRefreshRevision.next(Long.MAX_VALUE))
    }
}
