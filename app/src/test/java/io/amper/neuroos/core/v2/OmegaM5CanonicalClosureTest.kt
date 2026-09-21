package io.amper.neuroos.core.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaM5CanonicalClosureTest {
    @Test
    fun closureManifestCoversEveryM5BuildSliceExactlyOnce() {
        val phases = OmegaM5CanonicalClosure.slices.map { it.phase }

        assertEquals((647..669).toList(), phases)
        assertEquals(phases.size, phases.distinct().size)
        assertEquals(
            OmegaM5CanonicalClosure.slices.size,
            OmegaM5CanonicalClosure.slices.map { it.capability }.distinct().size
        )
        assertTrue(
            OmegaM5CanonicalClosure.slices.all {
                it.canonicalOwner.isNotBlank() && it.requiredInvariant.isNotBlank()
            }
        )
    }

    @Test
    fun currentCanonicalArchitecturePassesM5ClosureAudit() {
        val report = OmegaM5CanonicalClosure.audit()

        assertTrue(report.phaseCoverageComplete)
        assertTrue(report.missingInvariantIds.isEmpty())
        assertTrue(report.missingExitCriteria.isEmpty())
        assertTrue(report.ready)
    }

    @Test
    fun removingAnySliceInvariantFailsClosure() {
        val required =
            OmegaM5CanonicalClosure.slices
                .single { it.phase == 656 }
                .requiredInvariant
        val report = OmegaM5CanonicalClosure.audit(
            invariants = OmegaArchitectureLock.invariants - required
        )

        assertFalse(report.ready)
        assertEquals(listOf(required), report.missingInvariantIds)
    }

    @Test
    fun removingFoundationalSingleFoundationInvariantFailsClosure() {
        val required = "one-amper-foundation-runtime"
        val report = OmegaM5CanonicalClosure.audit(
            invariants = OmegaArchitectureLock.invariants - required
        )

        assertFalse(report.ready)
        assertTrue(required in report.missingInvariantIds)
    }

    @Test
    fun removingRequiredM5ExitCriterionFailsClosure() {
        val required =
            "persisted JobService cold-starts the same single-foundation runtime graph after process death or reboot"
        val current = OmegaArchitectureLock.milestones
            .single { it.id == OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON }
            .exitCriteria
            .toSet()
        val report = OmegaM5CanonicalClosure.audit(
            m5ExitCriteria = current - required
        )

        assertFalse(report.ready)
        assertEquals(listOf(required), report.missingExitCriteria)
    }
}
