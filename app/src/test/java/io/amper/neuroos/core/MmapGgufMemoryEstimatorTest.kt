package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmapGgufMemoryEstimatorTest {
    @Test
    fun mmapEstimateDoesNotChargeEntireGgufAsAnonymousMemory() {
        val twoGiB = 2L * 1024L * 1024L * 1024L

        val estimated = MmapGgufMemoryEstimator.estimateMemoryMb(
            modelLengthBytes = twoGiB,
            contextTokens = 2_048
        )

        // 45% hot mapped weight window + 160 MiB context + 192 MiB native/runtime overhead.
        assertEquals(1_274, estimated)
        assertTrue(estimated < 2_048)
    }

    @Test
    fun largerContextRaisesAdmissionEstimate() {
        val oneGiB = 1024L * 1024L * 1024L

        val small = MmapGgufMemoryEstimator.estimateMemoryMb(oneGiB, 2_048)
        val large = MmapGgufMemoryEstimator.estimateMemoryMb(oneGiB, 4_096)

        assertEquals(813, small)
        assertEquals(909, large)
        assertTrue(large > small)
    }

    @Test
    fun unknownModelSizeRemainsUnknownRatherThanInventingHeadroom() {
        assertEquals(0, MmapGgufMemoryEstimator.estimateMemoryMb(null, 2_048))
        assertEquals(0, MmapGgufMemoryEstimator.estimateMemoryMb(0L, 2_048))
    }
}
