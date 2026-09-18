package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TitanRuntimeFeedbackSaturationTest {
    @Test
    fun saturatingIncrementAdvancesNormallyBeforeLimit() {
        assertEquals(1, saturatingFeedbackIncrement(0))
        assertEquals(42, saturatingFeedbackIncrement(41))
        assertEquals(Int.MAX_VALUE, saturatingFeedbackIncrement(Int.MAX_VALUE - 1))
    }

    @Test
    fun saturatingIncrementStaysAtIntMaxInsteadOfWrappingNegative() {
        assertEquals(Int.MAX_VALUE, saturatingFeedbackIncrement(Int.MAX_VALUE))
    }

    @Test
    fun ordinaryScorePenaltyKeepsCanonicalFailureWeight() {
        val snapshot = TitanRouteFeedbackSnapshot(
            consecutiveFailures = 2,
            performancePenalty = 3
        )

        assertEquals(23, snapshot.scorePenalty)
    }

    @Test
    fun scorePenaltySaturatesBeforeFailureMultiplicationCouldOverflow() {
        val firstOverflowingFailureCount = Int.MAX_VALUE / 10 + 1
        val snapshot = TitanRouteFeedbackSnapshot(
            consecutiveFailures = firstOverflowingFailureCount,
            performancePenalty = 0
        )

        assertEquals(Int.MAX_VALUE, snapshot.scorePenalty)
    }

    @Test
    fun scorePenaltyRemainsSaturatedWithMaximumCounters() {
        val snapshot = TitanRouteFeedbackSnapshot(
            consecutiveFailures = Int.MAX_VALUE,
            performancePenalty = Int.MAX_VALUE,
            throughputSamples = Int.MAX_VALUE,
            consecutiveSlowSamples = Int.MAX_VALUE
        )

        assertEquals(Int.MAX_VALUE, snapshot.scorePenalty)
    }
}
