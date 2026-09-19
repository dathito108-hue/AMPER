package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyStructuralSimilarityTest {
    @Test
    fun canonicalSimilarityPreservesIdentityPrefixAndCoverageSemantics() {
        val a = CapabilityId("audit.a")
        val b = CapabilityId("audit.b")
        val c = CapabilityId("audit.c")

        val identity = StrategyStructuralSimilarity.score(
            StrategySignature(listOf(a, b)),
            StrategySignature(listOf(a, b))
        )
        val prefixExtension = StrategyStructuralSimilarity.score(
            StrategySignature(listOf(a)),
            StrategySignature(listOf(a, b))
        )
        val reordered = StrategyStructuralSimilarity.score(
            StrategySignature(listOf(a, b)),
            StrategySignature(listOf(b, a))
        )
        val disjoint = StrategyStructuralSimilarity.score(
            StrategySignature(listOf(a)),
            StrategySignature(listOf(c))
        )

        assertEquals(1.0, identity, 1e-9)
        assertTrue(prefixExtension in 0.0..1.0)
        assertTrue(reordered in 0.0..1.0)
        assertTrue(identity > prefixExtension)
        assertTrue(prefixExtension > disjoint)
        assertTrue(reordered > disjoint)
        assertEquals(0.0, disjoint, 1e-9)
    }
}
