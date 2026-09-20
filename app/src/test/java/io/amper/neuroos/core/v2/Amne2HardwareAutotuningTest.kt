package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiHardwareFeature
import io.amper.neuroos.core.AmiHardwareSnapshot
import io.amper.neuroos.core.AmneBackendAdmission
import io.amper.neuroos.core.AmneDeviceAdmissionReport
import io.amper.neuroos.core.AmneDeviceQualificationReport
import io.amper.neuroos.core.AmneKernelPrimitive
import io.amper.neuroos.core.AmnePrimitiveBenchmark
import io.amper.neuroos.core.AmneReferenceCpuKernels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Amne2HardwareAutotuningTest {
    private class FakeProbe(
        private val packaged: Boolean,
        private val report: AmneDeviceAdmissionReport? = null,
        private val failure: Throwable? = null
    ) : Amne2NativeAutotuneProbe {
        var benchmarkCalls: Int = 0

        override fun isPackaged(): Boolean = packaged

        override fun benchmarkAndAdmit(
            minimumSpeedup: Double
        ): Result<AmneDeviceAdmissionReport> {
            benchmarkCalls += 1
            failure?.let { return Result.failure(it) }
            return Result.success(requireNotNull(report))
        }
    }

    @Test
    fun absentNativeBackendKeepsReferenceWithoutBenchmark() {
        var resets = 0
        val probe = FakeProbe(packaged = false)
        val tuner = Amne2HardwareAutotuner(
            nativeProbe = probe,
            resetToReference = { resets += 1 }
        )
        val hardware = arm64Hardware()

        val first = tuner.tune(hardware).getOrThrow()
        val second = tuner.tune(hardware).getOrThrow()

        assertTrue(first.usesReferenceOnly)
        assertEquals(AmneReferenceCpuKernels.descriptor.backendId, first.referenceBaselineBackendId)
        assertEquals(first, second)
        assertEquals(0, probe.benchmarkCalls)
        assertEquals(1, resets)
    }

    @Test
    fun qualifiedMeasuredBackendIsAdmittedOncePerHardwareFingerprint() {
        var resets = 0
        val primitive = AmneKernelPrimitive.MATVEC_Q4_0
        val admission = AmneBackendAdmission(
            backendId = "amne-arm64-neon-v1",
            admittedPrimitives = setOf(primitive),
            numericalQualificationPassed = true,
            benchmarkResults = mapOf(
                primitive to AmnePrimitiveBenchmark(
                    primitive = primitive,
                    referenceMedianNs = 10_000L,
                    candidateMedianNs = 5_000L,
                    speedup = 2.0,
                    admitted = true
                )
            ),
            minimumSpeedup = 1.05
        )
        val report = AmneDeviceAdmissionReport(
            qualification = AmneDeviceQualificationReport(
                backendId = admission.backendId,
                passed = true,
                maxAbsoluteError = 0f,
                primitiveErrors = mapOf(primitive to 0f)
            ),
            admission = admission
        )
        val probe = FakeProbe(packaged = true, report = report)
        val tuner = Amne2HardwareAutotuner(
            nativeProbe = probe,
            resetToReference = { resets += 1 }
        )
        val hardware = arm64Hardware()

        val first = tuner.tune(hardware).getOrThrow()
        val second = tuner.tune(hardware).getOrThrow()

        assertEquals("amne-arm64-neon-v1", first.activeAcceleratedBackendId)
        assertTrue(primitive in first.admittedPrimitives)
        assertTrue(first.benchmarkExecuted)
        assertEquals(first, second)
        assertEquals(1, probe.benchmarkCalls)
        assertEquals(1, resets)
    }

    @Test
    fun benchmarkFailureFailsClosedToReferenceAndCachesDecision() {
        var resets = 0
        val probe = FakeProbe(
            packaged = true,
            failure = IllegalStateException("benchmark failed")
        )
        val tuner = Amne2HardwareAutotuner(
            nativeProbe = probe,
            resetToReference = { resets += 1 }
        )
        val hardware = arm64Hardware()

        val first = tuner.tune(hardware).getOrThrow()
        val second = tuner.tune(hardware).getOrThrow()

        assertTrue(first.usesReferenceOnly)
        assertTrue(first.nativeBackendPackaged)
        assertTrue(first.benchmarkExecuted)
        assertEquals(first, second)
        assertEquals(1, probe.benchmarkCalls)
        assertEquals(2, resets)
    }

    @Test
    fun hardwareFingerprintChangeForcesRetune() {
        val probe = FakeProbe(packaged = false)
        val tuner = Amne2HardwareAutotuner(
            nativeProbe = probe,
            resetToReference = {}
        )

        tuner.tune(arm64Hardware()).getOrThrow()
        val changed = tuner.tune(
            arm64Hardware().copy(memoryClassMb = 768)
        ).getOrThrow()

        assertEquals(768, changed.fingerprint.memoryClassMb)
    }

    @Test
    fun vulkanRequiresPhysicalQualifiedBenchmarkAndPerformanceWin() {
        val hardware = arm64Hardware(
            extra = setOf(AmiHardwareFeature.VULKAN)
        )
        val winning = Amne2VulkanBenchmarkEvidence(
            referenceCpuMedianNs = 10_000L,
            vulkanMedianNs = 7_000L,
            numericalQualificationPassed = true,
            measuredOnPhysicalDevice = true
        )
        val virtual = winning.copy(measuredOnPhysicalDevice = false)
        val numericallyBad = winning.copy(numericalQualificationPassed = false)
        val tooSlow = winning.copy(vulkanMedianNs = 9_900L)

        assertTrue(Amne2VulkanAdmissionPolicy.admits(hardware, winning))
        assertFalse(Amne2VulkanAdmissionPolicy.admits(hardware, virtual))
        assertFalse(Amne2VulkanAdmissionPolicy.admits(hardware, numericallyBad))
        assertFalse(Amne2VulkanAdmissionPolicy.admits(hardware, tooSlow))
        assertFalse(
            Amne2VulkanAdmissionPolicy.admits(
                arm64Hardware(),
                winning
            )
        )
    }

    private fun arm64Hardware(
        extra: Set<AmiHardwareFeature> = emptySet()
    ): AmiHardwareSnapshot =
        AmiHardwareSnapshot(
            features = linkedSetOf(
                AmiHardwareFeature.ARM64,
                AmiHardwareFeature.NEON,
                AmiHardwareFeature.DOTPROD,
                AmiHardwareFeature.I8MM,
                AmiHardwareFeature.FP16
            ) + extra,
            logicalProcessors = 8,
            memoryClassMb = 512,
            lowRamDevice = false
        )
}
