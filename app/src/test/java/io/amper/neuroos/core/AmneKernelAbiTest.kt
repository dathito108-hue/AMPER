package io.amper.neuroos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneKernelAbiTest {
    @Test
    fun referenceDotAndMatVecMatchExpectedMath() {
        val dot = AmneReferenceKernels.dotF32(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(4f, 5f, 6f)
        )
        assertEquals(32f, dot, 1e-6f)

        val output = FloatArray(2)
        AmneReferenceKernels.matVecF32(
            matrix = floatArrayOf(
                1f, 2f, 3f,
                4f, 5f, 6f
            ),
            rows = 2,
            columns = 3,
            vector = floatArrayOf(1f, 0.5f, -1f),
            output = output
        )
        assertArrayEquals(floatArrayOf(-1f, 0.5f), output, 1e-6f)
    }

    @Test
    fun referenceRmsNormProducesUnitMeanSquareWithinTolerance() {
        val input = floatArrayOf(-2f, -1f, 0.5f, 4f)
        val output = FloatArray(input.size)

        AmneReferenceKernels.rmsNormF32(input, output, 1e-6f)

        val meanSquare = output
            .map { it.toDouble() * it.toDouble() }
            .average()
        assertEquals(1.0, meanSquare, 1e-5)
    }

    @Test
    fun ropeAtPositionZeroIsIdentity() {
        val input = floatArrayOf(1f, 2f, 3f, 4f)
        val output = FloatArray(input.size)

        AmneReferenceKernels.ropeF32(
            input = input,
            output = output,
            headDimension = 4,
            position = 0
        )

        assertArrayEquals(input, output, 1e-6f)
    }

    @Test
    fun referenceBackendQualifiesAgainstItsOwnContract() {
        val qualification = AmneKernelQualifier.qualifyF32(AmneReferenceKernels)

        assertTrue(qualification.passed)
        assertEquals(0f, qualification.maxAbsoluteError, 0f)
        assertEquals(
            setOf(
                AmneKernelId.DOT_F32,
                AmneKernelId.MATVEC_F32,
                AmneKernelId.RMS_NORM_F32,
                AmneKernelId.SILU_F32,
                AmneKernelId.ROPE_F32
            ),
            qualification.checkedKernels
        )
    }

    @Test
    fun candidateMissingRequiredPrimitiveFailsQualification() {
        val candidate = object : AmneKernelBackend {
            override val capabilities = AmneKernelCapabilities(
                supportedKernels = setOf(AmneKernelId.DOT_F32),
                supportedScalarTypes = setOf(AmneScalarType.F32),
                preferredVectorWidthBits = 128,
                implementationId = "incomplete"
            )

            override fun dotF32(left: FloatArray, right: FloatArray): Float =
                AmneReferenceKernels.dotF32(left, right)

            override fun matVecF32(
                matrix: FloatArray,
                rows: Int,
                columns: Int,
                vector: FloatArray,
                output: FloatArray
            ) = Unit

            override fun rmsNormF32(
                input: FloatArray,
                output: FloatArray,
                epsilon: Float
            ) = Unit

            override fun siluF32(input: FloatArray, output: FloatArray) = Unit

            override fun ropeF32(
                input: FloatArray,
                output: FloatArray,
                headDimension: Int,
                position: Int,
                theta: Float
            ) = Unit
        }

        val qualification = AmneKernelQualifier.qualifyF32(candidate)

        assertFalse(qualification.passed)
        assertTrue(qualification.maxAbsoluteError.isInfinite())
        assertTrue(qualification.checkedKernels.isEmpty())
    }

    @Test
    fun tensorViewUsesOverflowCheckedElementCount() {
        val view = AmneTensorView(
            name = "test.weight",
            scalarType = AmneScalarType.F32,
            shape = listOf(8, 16, 32),
            byteOffset = 4096,
            byteLength = 8L * 16L * 32L * 4L
        )

        assertEquals(4096L, view.elementCount)
    }
}
