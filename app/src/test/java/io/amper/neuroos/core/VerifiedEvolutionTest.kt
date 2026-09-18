package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedEvolutionTest {
    private val self = CanonicalSelfModel()
    private val gate = CanonicalEvolutionGate(self)
    private val candidate = EvolutionCandidate(
        description = "candidate capability upgrade",
        baseRevision = "base",
        proposedRevision = "candidate",
        artifactDigest = "sha256:test"
    )

    @Test
    fun missingCanonicalInvariantRejectsCandidate() {
        val decision = gate.evaluate(candidate, VerificationEvidence(
            sandboxPassed = true,
            testsPassed = true,
            invariantResults = emptyMap(),
            rollbackToken = "rollback-1"
        ))
        assertEquals(EvolutionStage.REJECTED, decision.stage)
        assertFalse(decision.promotable)
        assertTrue(decision.reasons.any { it.contains("not evaluated") })
    }

    @Test
    fun verifiedCandidateCanPromoteAndRollback() {
        val invariants = self.snapshot().invariants.associateWith { true }
        val verified = gate.evaluate(candidate, VerificationEvidence(
            sandboxPassed = true,
            testsPassed = true,
            invariantResults = invariants,
            rollbackToken = "rollback-2"
        ))
        assertEquals(EvolutionStage.VERIFIED, verified.stage)
        assertTrue(verified.promotable)

        val promoted = gate.promote(verified)
        assertEquals(EvolutionStage.PROMOTED, promoted.stage)
        assertFalse(promoted.promotable)

        val rolledBack = gate.rollback(promoted)
        assertEquals(EvolutionStage.ROLLED_BACK, rolledBack.stage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectedCandidateCannotPromote() {
        val rejected = gate.evaluate(candidate, VerificationEvidence(
            sandboxPassed = false,
            testsPassed = true,
            invariantResults = self.snapshot().invariants.associateWith { true },
            rollbackToken = "rollback-3"
        ))
        gate.promote(rejected)
    }
}
