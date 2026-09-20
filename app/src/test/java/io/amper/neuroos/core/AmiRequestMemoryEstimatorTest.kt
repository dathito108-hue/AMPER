package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AmiRequestMemoryEstimatorTest {
    @Test
    fun shortRequestBudgetsOnlyItsKvHorizonNotAdvertisedFullContext() {
        val estimate = AmiRequestMemoryEstimator.estimateGeometry(
            layerKvWidths = List(32) { 1024 },
            hiddenSize = 4096,
            maxQueryWidth = 4096,
            maxFfnWidth = 11008,
            maxContextTokens = 32768,
            promptTokens = 16,
            requestedOutputTokens = 384,
            maxWindowBytes = 8 * 1024 * 1024
        )

        assertEquals(400, estimate.activeContextTokens)
        assertEquals(100L * 1024L * 1024L, estimate.kvCacheBytes)
        assertEquals(64L * 1024L * 1024L, estimate.transientWorkingBytes)
        assertEquals(164, estimate.estimatedMemoryMb)
    }

    @Test
    fun smallerOutputBudgetReducesKvAdmissionCost() {
        val short = AmiRequestMemoryEstimator.estimateGeometry(
            layerKvWidths = List(32) { 1024 },
            hiddenSize = 4096,
            maxQueryWidth = 4096,
            maxFfnWidth = 11008,
            maxContextTokens = 32768,
            promptTokens = 16,
            requestedOutputTokens = 64,
            maxWindowBytes = 8 * 1024 * 1024
        )
        val longer = AmiRequestMemoryEstimator.estimateGeometry(
            layerKvWidths = List(32) { 1024 },
            hiddenSize = 4096,
            maxQueryWidth = 4096,
            maxFfnWidth = 11008,
            maxContextTokens = 32768,
            promptTokens = 16,
            requestedOutputTokens = 384,
            maxWindowBytes = 8 * 1024 * 1024
        )

        assertTrue(short.estimatedMemoryMb < longer.estimatedMemoryMb)
        assertEquals(84, short.estimatedMemoryMb)
        assertEquals(164, longer.estimatedMemoryMb)
    }

    @Test
    fun requestBeyondFoundationContextFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            AmiRequestMemoryEstimator.estimateGeometry(
                layerKvWidths = listOf(256),
                hiddenSize = 1024,
                maxQueryWidth = 1024,
                maxFfnWidth = 4096,
                maxContextTokens = 512,
                promptTokens = 128,
                requestedOutputTokens = 512,
                maxWindowBytes = 8 * 1024 * 1024
            )
        }
    }
}
