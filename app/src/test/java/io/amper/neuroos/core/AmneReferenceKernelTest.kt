package io.amper.neuroos.core

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneReferenceKernelTest {
    @Test
    fun f32DotAndMatvecMatchExpectedMath() {
        assertEquals(
            32f,
            AmneReferenceCpuKernels.dotF32(
                floatArrayOf(1f, 2f, 3f, 4f),
                floatArrayOf(2f, 3f, 4f, 3f)
            ),
            1e-6f
        )

        val output = AmneReferenceCpuKernels.matVecF32(
            matrixRowMajor = floatArrayOf(
                1f, 2f, 3f,
                4f, 5f, 6f
            ),
            rows = 2,
            columns = 3,
            vector = floatArrayOf(1f, 0.5f, -1f)
        )
        assertArrayEquals(floatArrayOf(-1f, 0.5f), output, 1e-6f)
    }

    @Test
    fun q4_0MatvecDecodesCanonicalBlockLayout() {
        val block = ByteArray(18)
        block[0] = 0x00
        block[1] = 0x3c
        for (index in 0 until 16) {
            block[2 + index] = 0x99.toByte()
        }

        val output = AmneReferenceCpuKernels.matVecQ4_0(
            matrixBlocks = block,
            rows = 1,
            columns = 32,
            vector = FloatArray(32) { 1f }
        )

        assertEquals(32f, output.single(), 1e-5f)
    }

    @Test
    fun q8_0MatvecDecodesSignedInt8Payload() {
        val block = ByteArray(34)
        block[0] = 0x00
        block[1] = 0x3c
        for (index in 0 until 32) {
            block[2 + index] = if (index % 2 == 0) 2.toByte() else (-1).toByte()
        }

        val output = AmneReferenceCpuKernels.matVecQ8_0(
            matrixBlocks = block,
            rows = 1,
            columns = 32,
            vector = FloatArray(32) { 1f }
        )

        assertEquals(16f, output.single(), 1e-5f)
    }

    @Test
    fun rmsNormAndSwiGluAreFiniteAndShapePreserving() {
        val norm = AmneReferenceCpuKernels.rmsNormF32(
            input = floatArrayOf(1f, 2f, 3f, 4f),
            weight = floatArrayOf(1f, 1f, 1f, 1f),
            epsilon = 1e-5f
        )
        assertEquals(4, norm.size)
        assertTrue(norm.all { it.isFinite() })

        val gated = AmneReferenceCpuKernels.swiGluF32(
            gate = floatArrayOf(-1f, 0f, 1f),
            up = floatArrayOf(2f, 3f, 4f)
        )
        assertEquals(3, gated.size)
        assertTrue(gated.all { it.isFinite() })
        assertEquals(0f, gated[1], 1e-6f)
    }

    @Test
    fun stableSoftmaxSumsToOneForLargeLogits() {
        val output = AmneReferenceCpuKernels.softmaxF32(
            floatArrayOf(10_000f, 10_001f, 9_999f)
        )

        assertTrue(output.all { it.isFinite() })
        assertEquals(1f, output.sum(), 1e-6f)
        assertTrue(output[1] > output[0])
        assertTrue(output[0] > output[2])
    }

    @Test
    fun ropeAtPositionZeroIsIdentity() {
        val input = floatArrayOf(1f, -2f, 3f, -4f, 5f, -6f)
        val output = AmneReferenceCpuKernels.ropeF32(
            input = input,
            position = 0
        )

        assertArrayEquals(input, output, 1e-6f)
    }

    @Test
    fun halfDecoderCoversNormalSubnormalInfinityAndSign() {
        assertEquals(1f, AmneReferenceCpuKernels.halfToFloat(0x3c00), 0f)
        assertEquals(-2f, AmneReferenceCpuKernels.halfToFloat(0xc000), 0f)
        assertTrue(AmneReferenceCpuKernels.halfToFloat(0x0001) > 0f)
        assertTrue(AmneReferenceCpuKernels.halfToFloat(0x7c00).isInfinite())
    }

    @Test
    fun kernelNumericsRejectResultOutsideTolerance() {
        val expected = floatArrayOf(1f, 2f, 3f)
        val close = floatArrayOf(1.0001f, 1.9999f, 3.0001f)
        val far = floatArrayOf(1.1f, 2f, 3f)

        assertTrue(AmneKernelNumerics.qualifies(expected, close, 0.001f))
        assertTrue(!AmneKernelNumerics.qualifies(expected, far, 0.001f))
        assertTrue(abs(AmneKernelNumerics.maxAbsoluteError(expected, far) - 0.1f) < 1e-5f)
    }
}

class AmneKernelDispatchTest {
    private class FakeOptimizedBackend(
        override val descriptor: AmneKernelDescriptor
    ) : AmneKernelBackend by AmneReferenceCpuKernels

    @Test
    fun registryPrefersCompatibleHardwareSpecificBackend() {
        val optimized = FakeOptimizedBackend(
            AmneKernelDescriptor(
                backendId = "fake-dotprod",
                primitives = setOf(AmneKernelPrimitive.MATVEC_Q4_0),
                requiredHardware = setOf(
                    AmiHardwareFeature.ARM64,
                    AmiHardwareFeature.NEON,
                    AmiHardwareFeature.DOTPROD
                ),
                deterministicReference = false
            )
        )
        val registry = AmneKernelRegistry(
            listOf(AmneReferenceCpuKernels, optimized)
        )
        val hardware = AmiHardwareSnapshot(
            features = setOf(
                AmiHardwareFeature.ARM64,
                AmiHardwareFeature.NEON,
                AmiHardwareFeature.DOTPROD
            ),
            logicalProcessors = 8,
            memoryClassMb = 512,
            lowRamDevice = false
        )

        assertEquals(
            "fake-dotprod",
            registry.backendFor(
                AmneKernelPrimitive.MATVEC_Q4_0,
                hardware
            ).descriptor.backendId
        )
    }

    @Test
    fun registryFallsBackToReferenceWhenFeatureIsMissing() {
        val optimized = FakeOptimizedBackend(
            AmneKernelDescriptor(
                backendId = "fake-i8mm",
                primitives = setOf(AmneKernelPrimitive.MATVEC_Q8_0),
                requiredHardware = setOf(
                    AmiHardwareFeature.ARM64,
                    AmiHardwareFeature.I8MM
                ),
                deterministicReference = false
            )
        )
        val registry = AmneKernelRegistry(
            listOf(AmneReferenceCpuKernels, optimized)
        )
        val hardware = AmiHardwareSnapshot(
            features = setOf(
                AmiHardwareFeature.ARM64,
                AmiHardwareFeature.NEON
            ),
            logicalProcessors = 4,
            memoryClassMb = 256,
            lowRamDevice = false
        )

        assertEquals(
            AmneReferenceCpuKernels.descriptor.backendId,
            registry.backendFor(
                AmneKernelPrimitive.MATVEC_Q8_0,
                hardware
            ).descriptor.backendId
        )
    }

    @Test
    fun tensorPlannerMapsSupportedSourceEncodingsWithoutGuessingUnknownOnes() {
        val q4 = AmiTensorDescriptor(
            name = "layer.weight",
            dimensions = listOf(32UL, 32UL),
            sourceEncodingType = 2L,
            foundationOffset = 0L,
            storageBytes = 576L
        )
        val unknown = q4.copy(
            name = "layer.future",
            sourceEncodingType = 999L,
            storageBytes = null
        )

        assertEquals(AmneMatrixPath.Q4_0, AmneTensorKernelPlanner.matrixPath(q4))
        assertEquals(
            AmneKernelPrimitive.MATVEC_Q4_0,
            AmneTensorKernelPlanner.requiredPrimitive(q4)
        )
        assertEquals(AmneMatrixPath.UNSUPPORTED, AmneTensorKernelPlanner.matrixPath(unknown))
        assertEquals(null, AmneTensorKernelPlanner.requiredPrimitive(unknown))
    }
}
