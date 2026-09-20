package io.amper.neuroos.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmiDirectExecutionAdmissionPolicyTest {
    @Test
    fun referenceOnlyMatrixPathIsNotInteractiveReady() {
        val readiness = AmiDirectExecutionAdmissionPolicy.evaluate(
            requiredMatrixPrimitives = setOf(AmneKernelPrimitive.MATVEC_Q4_K),
            registry = AmneKernelRegistry(),
            hardware = hardware()
        )

        assertFalse(readiness.ready)
        assertTrue(
            AmneKernelPrimitive.MATVEC_Q4_K in readiness.referenceOnlyMatrixPrimitives
        )
    }

    @Test
    fun admittedNativeMatrixPathIsInteractiveReady() {
        val primitive = AmneKernelPrimitive.MATVEC_Q4_0
        val native = object : AmneKernelBackend by AmneReferenceCpuKernels {
            override val descriptor = AmneKernelDescriptor(
                backendId = "test-native",
                primitives = setOf(primitive),
                requiredHardware = setOf(
                    AmiHardwareFeature.ARM64,
                    AmiHardwareFeature.NEON
                ),
                deterministicReference = false
            )
        }
        val benchmark = AmnePrimitiveBenchmark(
            primitive = primitive,
            referenceMedianNs = 2_000L,
            candidateMedianNs = 1_000L,
            speedup = 2.0,
            admitted = true
        )
        val admission = AmneDispatchAdmissionPolicy.evaluate(
            backendId = native.descriptor.backendId,
            numericalQualificationPassed = true,
            measurements = listOf(benchmark)
        )
        val registry = AmneKernelRegistry(
            backends = listOf(AmneReferenceCpuKernels, native),
            admissions = listOf(admission)
        )

        val readiness = AmiDirectExecutionAdmissionPolicy.evaluate(
            requiredMatrixPrimitives = setOf(primitive),
            registry = registry,
            hardware = hardware()
        )

        assertTrue(readiness.ready)
        assertTrue(readiness.referenceOnlyMatrixPrimitives.isEmpty())
    }

    @Test
    fun mixedModelIsRejectedWhenEvenOneMatrixEncodingFallsBackToReference() {
        val nativePrimitive = AmneKernelPrimitive.MATVEC_Q4_0
        val native = object : AmneKernelBackend by AmneReferenceCpuKernels {
            override val descriptor = AmneKernelDescriptor(
                backendId = "test-native",
                primitives = setOf(nativePrimitive),
                requiredHardware = setOf(
                    AmiHardwareFeature.ARM64,
                    AmiHardwareFeature.NEON
                ),
                deterministicReference = false
            )
        }
        val benchmark = AmnePrimitiveBenchmark(
            primitive = nativePrimitive,
            referenceMedianNs = 2_000L,
            candidateMedianNs = 1_000L,
            speedup = 2.0,
            admitted = true
        )
        val registry = AmneKernelRegistry(
            backends = listOf(AmneReferenceCpuKernels, native),
            admissions = listOf(
                AmneDispatchAdmissionPolicy.evaluate(
                    backendId = native.descriptor.backendId,
                    numericalQualificationPassed = true,
                    measurements = listOf(benchmark)
                )
            )
        )

        val readiness = AmiDirectExecutionAdmissionPolicy.evaluate(
            requiredMatrixPrimitives = setOf(
                nativePrimitive,
                AmneKernelPrimitive.MATVEC_Q5_K
            ),
            registry = registry,
            hardware = hardware()
        )

        assertFalse(readiness.ready)
        assertTrue(
            readiness.referenceOnlyMatrixPrimitives ==
                setOf(AmneKernelPrimitive.MATVEC_Q5_K)
        )
    }

    private fun hardware() = AmiHardwareSnapshot(
        features = setOf(
            AmiHardwareFeature.ARM64,
            AmiHardwareFeature.NEON
        ),
        logicalProcessors = 8,
        memoryClassMb = 512,
        lowRamDevice = false
    )
}
