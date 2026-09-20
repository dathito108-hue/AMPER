package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiHardwareFeature
import io.amper.neuroos.core.AmiHardwareSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Amne2MemoryBudgetTest {
    private val geometry = Amne2DecoderMemoryGeometry(
        layerKvWidths = List(32) { 1_024 },
        hiddenSize = 4_096,
        maxQueryWidth = 4_096,
        maxFfnWidth = 11_008,
        maxContextTokens = 32_768
    )

    @Test
    fun mobileBudgetCapsAdvertisedModelContext() {
        val budget = Amne2MemoryBudgetPolicy
            .deriveGeometry(
                geometry = geometry,
                hardware = hardware(memoryClassMb = 256),
                configuredMaxWindowBytes = 8 * 1024 * 1024
            )
            .getOrThrow()

        assertTrue(budget.safeContextTokens < geometry.maxContextTokens)
        assertTrue(budget.safeContextTokens >= 2)
        assertTrue(budget.estimateAtSafeContext.estimatedMemoryMb <= budget.sessionBudgetMb)
        assertEquals(
            budget.safeContextTokens,
            budget.estimateAtSafeContext.activeContextTokens
        )
    }

    @Test
    fun largerMemoryClassNeverGetsSmallerSafeContextForSameGeometry() {
        val small = Amne2MemoryBudgetPolicy
            .deriveGeometry(
                geometry = geometry,
                hardware = hardware(memoryClassMb = 256),
                configuredMaxWindowBytes = 8 * 1024 * 1024
            )
            .getOrThrow()
        val large = Amne2MemoryBudgetPolicy
            .deriveGeometry(
                geometry = geometry,
                hardware = hardware(memoryClassMb = 768),
                configuredMaxWindowBytes = 8 * 1024 * 1024
            )
            .getOrThrow()

        assertTrue(large.sessionBudgetMb > small.sessionBudgetMb)
        assertTrue(large.safeContextTokens >= small.safeContextTokens)
        assertTrue(large.mmapWindowBytes >= small.mmapWindowBytes)
    }

    @Test
    fun lowRamClassificationReservesAtLeastAsMuchHeadroom() {
        val normal = Amne2MemoryBudgetPolicy
            .deriveGeometry(
                geometry = geometry,
                hardware = hardware(memoryClassMb = 384, lowRam = false),
                configuredMaxWindowBytes = 8 * 1024 * 1024
            )
            .getOrThrow()
        val lowRam = Amne2MemoryBudgetPolicy
            .deriveGeometry(
                geometry = geometry,
                hardware = hardware(memoryClassMb = 384, lowRam = true),
                configuredMaxWindowBytes = 8 * 1024 * 1024
            )
            .getOrThrow()

        assertTrue(lowRam.reservedHeadroomMb >= normal.reservedHeadroomMb)
        assertTrue(lowRam.sessionBudgetMb <= normal.sessionBudgetMb)
        assertTrue(lowRam.safeContextTokens <= normal.safeContextTokens)
        assertTrue(lowRam.mmapWindowBytes <= normal.mmapWindowBytes)
    }

    @Test
    fun configuredMmapCeilingIsNeverExceeded() {
        val configured = 2 * 1024 * 1024
        val window = Amne2MemoryBudgetPolicy.maxWindowBytes(
            hardware = hardware(memoryClassMb = 1_024),
            configuredMaxWindowBytes = configured
        )

        assertTrue(window in 1..configured)
    }

    @Test
    fun extremelySmallMemoryClassFailsClosedInsteadOfOvercommitting() {
        val result = Amne2MemoryBudgetPolicy.deriveGeometry(
            geometry = geometry,
            hardware = hardware(memoryClassMb = 64, lowRam = true),
            configuredMaxWindowBytes = 8 * 1024 * 1024
        )

        assertTrue(result.isFailure)
    }

    private fun hardware(
        memoryClassMb: Int,
        lowRam: Boolean = false
    ): AmiHardwareSnapshot =
        AmiHardwareSnapshot(
            features = setOf(
                AmiHardwareFeature.ARM64,
                AmiHardwareFeature.NEON
            ),
            logicalProcessors = 8,
            memoryClassMb = memoryClassMb,
            lowRamDevice = lowRam
        )
}
