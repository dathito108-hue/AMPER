package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiRequestMemoryEstimate
import io.amper.neuroos.core.AmneKernelPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Amne2RuntimeReadinessTelemetryTest {
    @Test
    fun acceleratedModelProducesReadyConsolidatedSnapshot() {
        val required = setOf(
            AmneKernelPrimitive.MATVEC_F32,
            AmneKernelPrimitive.MATVEC_Q4_0
        )
        val snapshot = Amne2RuntimeReadinessTelemetry.snapshot(
            foundationId = "amper-foundation",
            semanticSha256 = "1".repeat(64),
            artifactSha256 = "2".repeat(64),
            sourceSha256 = "3".repeat(64),
            memoryBudget = budget(),
            requiredMatrixPrimitives = required,
            referenceOnlyMatrixPrimitives = emptySet(),
            backendByPrimitive = mapOf(
                AmneKernelPrimitive.MATVEC_F32 to "amne-native",
                AmneKernelPrimitive.MATVEC_Q4_0 to "amne-native"
            ),
            hotSession = Amne2HotSessionTelemetry(
                state = Amne2HotSessionTelemetryState.ACTIVE_MATCHING_MODEL,
                kvPosition = 64,
                committedTokens = 64,
                maxContextTokens = 256
            )
        )

        assertEquals(Amne2RuntimeReadinessState.READY, snapshot.state)
        assertTrue(snapshot.hardwareAccelerationReady)
        assertTrue(snapshot.degradedReasons.isEmpty())
        assertEquals(256, snapshot.safeContextTokens)
        assertEquals(4_096, snapshot.modelMaxContextTokens)
        assertEquals(4 * 1024 * 1024, snapshot.mmapWindowBytes)
        assertEquals(
            Amne2HotSessionTelemetryState.ACTIVE_MATCHING_MODEL,
            snapshot.hotSession.state
        )
    }

    @Test
    fun referenceMatrixPathIsExplicitDegradedReason() {
        val required = setOf(
            AmneKernelPrimitive.MATVEC_F32,
            AmneKernelPrimitive.MATVEC_Q4_0
        )
        val snapshot = Amne2RuntimeReadinessTelemetry.snapshot(
            foundationId = "amper-foundation",
            semanticSha256 = "1".repeat(64),
            artifactSha256 = "2".repeat(64),
            sourceSha256 = "3".repeat(64),
            memoryBudget = budget(),
            requiredMatrixPrimitives = required,
            referenceOnlyMatrixPrimitives = setOf(
                AmneKernelPrimitive.MATVEC_Q4_0
            ),
            backendByPrimitive = mapOf(
                AmneKernelPrimitive.MATVEC_F32 to "amne-native",
                AmneKernelPrimitive.MATVEC_Q4_0 to "amne-reference"
            ),
            hotSession = Amne2HotSessionTelemetry.none()
        )

        assertEquals(Amne2RuntimeReadinessState.DEGRADED, snapshot.state)
        assertFalse(snapshot.hardwareAccelerationReady)
        assertEquals(1, snapshot.degradedReasons.size)
        assertEquals(
            "reference-only-matvec-q4-0",
            snapshot.degradedReasons.single().code
        )
        assertEquals(
            Amne2HotSessionTelemetryState.NONE,
            snapshot.hotSession.state
        )
    }

    @Test
    fun hotSessionTelemetryRejectsKvHistoryMismatch() {
        val result = runCatching {
            Amne2HotSessionTelemetry(
                state = Amne2HotSessionTelemetryState.ACTIVE_MATCHING_MODEL,
                kvPosition = 10,
                committedTokens = 9,
                maxContextTokens = 128
            )
        }

        assertTrue(result.isFailure)
    }

    private fun budget(): Amne2MemoryBudget =
        Amne2MemoryBudget(
            hardwareFingerprint = Amne2HardwareFingerprint(
                features = emptySet(),
                logicalProcessors = 8,
                memoryClassMb = 512,
                lowRamDevice = false
            ),
            memoryClassMb = 512,
            reservedHeadroomMb = 128,
            sessionBudgetMb = 256,
            mmapWindowBytes = 4 * 1024 * 1024,
            modelMaxContextTokens = 4_096,
            safeContextTokens = 256,
            estimateAtSafeContext = AmiRequestMemoryEstimate(
                promptTokens = 255,
                requestedOutputTokens = 1,
                activeContextTokens = 256,
                kvCacheBytes = 8L * 1024L * 1024L,
                transientWorkingBytes = 64L * 1024L * 1024L,
                mmapWindowBytes = 4L * 1024L * 1024L,
                estimatedMemoryMb = 72
            )
        )
}
