package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneQualifiedDispatchTest {
    private class FakeBackend(
        override val descriptor: AmneKernelDescriptor
    ) : AmneKernelBackend by AmneReferenceCpuKernels

    @Test
    fun numericalFailureRejectsAllPrimitivesEvenWhenBenchmarkIsFast() {
        val benchmark = AmnePrimitiveBenchmark(
            primitive = AmneKernelPrimitive.MATVEC_Q4_0,
            referenceMedianNs = 10_000L,
            candidateMedianNs = 1_000L,
            speedup = 10.0,
            admitted = true
        )

        val admission = AmneDispatchAdmissionPolicy.evaluate(
            backendId = "bad-native",
            numericalQualificationPassed = false,
            measurements = listOf(benchmark)
        )

        assertTrue(admission.admittedPrimitives.isEmpty())
        assertFalse(admission.benchmarkResults.getValue(
            AmneKernelPrimitive.MATVEC_Q4_0
        ).admitted)
    }

    @Test
    fun admissionRequiresConfiguredPerformanceMargin() {
        val barelyFaster = AmnePrimitiveBenchmark(
            primitive = AmneKernelPrimitive.MATVEC_F32,
            referenceMedianNs = 10_000L,
            candidateMedianNs = 9_800L,
            speedup = 10_000.0 / 9_800.0,
            admitted = true
        )
        val clearlyFaster = AmnePrimitiveBenchmark(
            primitive = AmneKernelPrimitive.MATVEC_Q4_0,
            referenceMedianNs = 10_000L,
            candidateMedianNs = 7_500L,
            speedup = 10_000.0 / 7_500.0,
            admitted = true
        )

        val admission = AmneDispatchAdmissionPolicy.evaluate(
            backendId = "native",
            numericalQualificationPassed = true,
            measurements = listOf(barelyFaster, clearlyFaster),
            minimumSpeedup = 1.05
        )

        assertFalse(AmneKernelPrimitive.MATVEC_F32 in admission.admittedPrimitives)
        assertTrue(AmneKernelPrimitive.MATVEC_Q4_0 in admission.admittedPrimitives)
    }

    @Test
    fun registryRoutesOnlyIndividuallyAdmittedPrimitives() {
        val native = FakeBackend(
            AmneKernelDescriptor(
                backendId = "native",
                primitives = setOf(
                    AmneKernelPrimitive.MATVEC_F32,
                    AmneKernelPrimitive.MATVEC_Q4_0
                ),
                requiredHardware = setOf(
                    AmiHardwareFeature.ARM64,
                    AmiHardwareFeature.NEON
                ),
                deterministicReference = false
            )
        )
        val admission = AmneBackendAdmission(
            backendId = "native",
            admittedPrimitives = setOf(AmneKernelPrimitive.MATVEC_Q4_0),
            numericalQualificationPassed = true,
            benchmarkResults = mapOf(
                AmneKernelPrimitive.MATVEC_F32 to AmnePrimitiveBenchmark(
                    AmneKernelPrimitive.MATVEC_F32,
                    10_000L,
                    9_900L,
                    10_000.0 / 9_900.0,
                    false
                ),
                AmneKernelPrimitive.MATVEC_Q4_0 to AmnePrimitiveBenchmark(
                    AmneKernelPrimitive.MATVEC_Q4_0,
                    10_000L,
                    5_000L,
                    2.0,
                    true
                )
            ),
            minimumSpeedup = 1.05
        )
        val registry = AmneKernelRegistry(
            backends = listOf(AmneReferenceCpuKernels, native),
            admissions = listOf(admission)
        )
        val hardware = AmiHardwareSnapshot(
            features = setOf(
                AmiHardwareFeature.ARM64,
                AmiHardwareFeature.NEON
            ),
            logicalProcessors = 8,
            memoryClassMb = 512,
            lowRamDevice = false
        )

        assertEquals(
            AmneReferenceCpuKernels.descriptor.backendId,
            registry.backendFor(
                AmneKernelPrimitive.MATVEC_F32,
                hardware
            ).descriptor.backendId
        )
        assertEquals(
            "native",
            registry.backendFor(
                AmneKernelPrimitive.MATVEC_Q4_0,
                hardware
            ).descriptor.backendId
        )
    }

    @Test
    fun processRuntimeInstallsQualifiedBackendAndCanReset() {
        val native = FakeBackend(
            AmneKernelDescriptor(
                backendId = "runtime-native",
                primitives = setOf(AmneKernelPrimitive.DOT_F32),
                requiredHardware = setOf(
                    AmiHardwareFeature.ARM64,
                    AmiHardwareFeature.NEON
                ),
                deterministicReference = false
            )
        )
        val admission = AmneBackendAdmission(
            backendId = "runtime-native",
            admittedPrimitives = setOf(AmneKernelPrimitive.DOT_F32),
            numericalQualificationPassed = true,
            benchmarkResults = mapOf(
                AmneKernelPrimitive.DOT_F32 to AmnePrimitiveBenchmark(
                    AmneKernelPrimitive.DOT_F32,
                    2_000L,
                    1_000L,
                    2.0,
                    true
                )
            ),
            minimumSpeedup = 1.05
        )
        val hardware = AmiHardwareSnapshot(
            features = setOf(
                AmiHardwareFeature.ARM64,
                AmiHardwareFeature.NEON
            ),
            logicalProcessors = 8,
            memoryClassMb = 512,
            lowRamDevice = false
        )

        try {
            AmneProcessKernelRuntime.install(native, admission)
            assertEquals(
                "runtime-native",
                AmneProcessKernelRuntime.registry().backendFor(
                    AmneKernelPrimitive.DOT_F32,
                    hardware
                ).descriptor.backendId
            )
        } finally {
            AmneProcessKernelRuntime.resetToReference()
        }

        assertEquals(
            AmneReferenceCpuKernels.descriptor.backendId,
            AmneProcessKernelRuntime.registry().backendFor(
                AmneKernelPrimitive.DOT_F32,
                hardware
            ).descriptor.backendId
        )
    }
}
