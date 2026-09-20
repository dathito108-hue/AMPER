package io.amper.neuroos.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmiTokenGenerationTest {
    @Test
    fun greedySamplingReturnsHighestAdjustedLogit() {
        val result = AmiLogitSampler.sample(
            logits = floatArrayOf(0.1f, 3.5f, 1.2f),
            config = AmiSamplingConfig(
                temperature = 0f,
                topK = 0,
                topP = 1f
            )
        )

        assertEquals(1, result.tokenId)
        assertEquals(1f, result.probability, 0f)
        assertEquals(1, result.consideredTokens)
    }

    @Test
    fun repetitionPenaltyCanChangeGreedyChoice() {
        val result = AmiLogitSampler.sample(
            logits = floatArrayOf(5f, 4.5f, 1f),
            config = AmiSamplingConfig(
                temperature = 0f,
                repetitionPenalty = 2f
            ),
            history = intArrayOf(0)
        )

        assertEquals(1, result.tokenId)
    }

    @Test
    fun topKOneIsDeterministicForNonZeroTemperature() {
        repeat(16) { seed ->
            val result = AmiLogitSampler.sample(
                logits = floatArrayOf(-2f, 0.25f, 7f, 1f),
                config = AmiSamplingConfig(
                    temperature = 0.8f,
                    topK = 1,
                    topP = 1f,
                    seed = seed.toLong()
                ),
                random = Random(seed)
            )
            assertEquals(2, result.tokenId)
            assertEquals(1, result.consideredTokens)
            assertEquals(1f, result.probability, 1e-6f)
        }
    }

    @Test
    fun topPRestrictsSamplingToProbabilityPrefix() {
        val logits = floatArrayOf(8f, 7f, -10f, -11f)
        repeat(64) { seed ->
            val result = AmiLogitSampler.sample(
                logits = logits,
                config = AmiSamplingConfig(
                    temperature = 1f,
                    topK = 0,
                    topP = 0.9f,
                    seed = seed.toLong()
                ),
                random = Random(seed)
            )
            assertTrue(result.tokenId == 0 || result.tokenId == 1)
            assertEquals(2, result.consideredTokens)
        }
    }

    @Test
    fun f16MatrixRoutesThroughF32PrimitiveWithoutChangingEncoding() {
        val tensor = AmiTensorDescriptor(
            name = "output.weight",
            dimensions = listOf(8UL, 16UL),
            sourceEncodingType = AmneTensorEncoding.F16.ggmlTypeId!!,
            foundationOffset = 0L,
            storageBytes = 8L * 16L * 2L
        )

        assertEquals(
            AmneMatrixPath.F16,
            AmneTensorKernelPlanner.matrixPath(tensor)
        )
        assertEquals(
            AmneKernelPrimitive.MATVEC_F32,
            AmneTensorKernelPlanner.requiredPrimitive(tensor)
        )
        assertEquals(
            AmneTensorEncoding.F16,
            AmneTensorEncoding.fromGgmlType(tensor.sourceEncodingType)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun samplerRejectsNonFiniteLogits() {
        AmiLogitSampler.sample(
            logits = floatArrayOf(1f, Float.NaN),
            config = AmiSamplingConfig()
        )
    }
}
