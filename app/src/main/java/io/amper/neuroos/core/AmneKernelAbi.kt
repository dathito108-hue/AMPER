package io.amper.neuroos.core

import kotlin.math.exp
import kotlin.math.sqrt

enum class AmneScalarType {
    F32,
    F16,
    BF16,
    Q4_0,
    Q4_K,
    Q5_K,
    Q6_K,
    Q8_0
}

enum class AmneKernelId {
    DOT_F32,
    MATVEC_F32,
    RMS_NORM_F32,
    SILU_F32,
    ROPE_F32
}

data class AmneTensorView(
    val name: String,
    val scalarType: AmneScalarType,
    val shape: List<Int>,
    val byteOffset: Long,
    val byteLength: Long
) {
    init {
        require(name.isNotBlank())
        require(shape.isNotEmpty())
        require(shape.all { it > 0 })
        require(byteOffset >= 0L)
        require(byteLength > 0L)
    }

    val elementCount: Long
        get() {
            var count = 1L
            shape.forEach { dimension ->
                count = Math.multiplyExact(count, dimension.toLong())
            }
            return count
        }
}

data class AmneKernelCapabilities(
    val supportedKernels: Set<AmneKernelId>,
    val supportedScalarTypes: Set<AmneScalarType>,
    val preferredVectorWidthBits: Int,
    val implementationId: String
) {
    init {
        require(implementationId.isNotBlank())
        require(preferredVectorWidthBits > 0)
    }
}

/**
 * Stable AMNE kernel ABI.
 *
 * The interface deliberately exposes primitive operations rather than model-specific layers.
 * Optimized ARM64/NEON/DOTPROD/I8MM/Vulkan implementations can replace individual primitives
 * while AMI graph semantics and Titan routing remain unchanged.
 */
interface AmneKernelBackend {
    val capabilities: AmneKernelCapabilities

    fun dotF32(
        left: FloatArray,
        right: FloatArray
    ): Float

    /**
     * Dense row-major matrix-vector product.
     *
     * [matrix] shape is rows x columns. [vector] length must equal columns.
     * [output] length must equal rows.
     */
    fun matVecF32(
        matrix: FloatArray,
        rows: Int,
        columns: Int,
        vector: FloatArray,
        output: FloatArray
    )

    /**
     * RMSNorm without affine weight:
     * y_i = x_i / sqrt(mean(x^2) + eps)
     */
    fun rmsNormF32(
        input: FloatArray,
        output: FloatArray,
        epsilon: Float
    )

    fun siluF32(
        input: FloatArray,
        output: FloatArray
    )

    /**
     * In-place-style RoPE transform from [input] into [output].
     *
     * [headDimension] must be even. [position] is the absolute token position and [theta] the
     * model's rotary base. This reference primitive rotates each consecutive pair.
     */
    fun ropeF32(
        input: FloatArray,
        output: FloatArray,
        headDimension: Int,
        position: Int,
        theta: Float = 10_000f
    )
}

/**
 * Portable correctness oracle for future native AMNE kernels.
 *
 * This implementation intentionally favors simple, auditable math over speed. Native kernels are
 * accepted only when they reproduce this contract within explicit tolerances.
 */
object AmneReferenceKernels : AmneKernelBackend {
    override val capabilities = AmneKernelCapabilities(
        supportedKernels = AmneKernelId.values().toSet(),
        supportedScalarTypes = setOf(AmneScalarType.F32),
        preferredVectorWidthBits = 32,
        implementationId = "amne-reference-f32-v1"
    )

    override fun dotF32(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size)
        var sum = 0.0
        for (index in left.indices) {
            sum += left[index].toDouble() * right[index].toDouble()
        }
        return sum.toFloat()
    }

    override fun matVecF32(
        matrix: FloatArray,
        rows: Int,
        columns: Int,
        vector: FloatArray,
        output: FloatArray
    ) {
        require(rows > 0)
        require(columns > 0)
        require(matrix.size == Math.multiplyExact(rows, columns))
        require(vector.size == columns)
        require(output.size == rows)

        for (row in 0 until rows) {
            var sum = 0.0
            val base = row * columns
            for (column in 0 until columns) {
                sum += matrix[base + column].toDouble() * vector[column].toDouble()
            }
            output[row] = sum.toFloat()
        }
    }

    override fun rmsNormF32(
        input: FloatArray,
        output: FloatArray,
        epsilon: Float
    ) {
        require(input.isNotEmpty())
        require(output.size == input.size)
        require(epsilon > 0f)

        var sumSquares = 0.0
        input.forEach { value ->
            sumSquares += value.toDouble() * value.toDouble()
        }
        val meanSquares = sumSquares / input.size.toDouble()
        val inverseRms = 1.0 / sqrt(meanSquares + epsilon.toDouble())

        for (index in input.indices) {
            output[index] = (input[index].toDouble() * inverseRms).toFloat()
        }
    }

    override fun siluF32(
        input: FloatArray,
        output: FloatArray
    ) {
        require(output.size == input.size)
        for (index in input.indices) {
            val value = input[index].toDouble()
            output[index] = (value / (1.0 + exp(-value))).toFloat()
        }
    }

    override fun ropeF32(
        input: FloatArray,
        output: FloatArray,
        headDimension: Int,
        position: Int,
        theta: Float
    ) {
        require(headDimension > 0 && headDimension % 2 == 0)
        require(position >= 0)
        require(theta > 1f)
        require(input.size % headDimension == 0)
        require(output.size == input.size)

        var headBase = 0
        while (headBase < input.size) {
            var pair = 0
            while (pair < headDimension) {
                val pairIndex = pair / 2
                val exponent = (2.0 * pairIndex.toDouble()) / headDimension.toDouble()
                val frequency = 1.0 / Math.pow(theta.toDouble(), exponent)
                val angle = position.toDouble() * frequency
                val cos = kotlin.math.cos(angle)
                val sin = kotlin.math.sin(angle)

                val x0 = input[headBase + pair].toDouble()
                val x1 = input[headBase + pair + 1].toDouble()
                output[headBase + pair] = (x0 * cos - x1 * sin).toFloat()
                output[headBase + pair + 1] = (x0 * sin + x1 * cos).toFloat()
                pair += 2
            }
            headBase += headDimension
        }
    }
}

data class AmneKernelQualification(
    val implementationId: String,
    val passed: Boolean,
    val maxAbsoluteError: Float,
    val checkedKernels: Set<AmneKernelId>
)

/**
 * Small deterministic qualification gate for accelerated implementations.
 *
 * It is not a performance benchmark. It only verifies that a candidate backend obeys the AMNE
 * primitive contract closely enough to be eligible for routing.
 */
object AmneKernelQualifier {
    fun qualifyF32(
        candidate: AmneKernelBackend,
        tolerance: Float = 1e-4f
    ): AmneKernelQualification {
        require(tolerance > 0f)

        val required = setOf(
            AmneKernelId.DOT_F32,
            AmneKernelId.MATVEC_F32,
            AmneKernelId.RMS_NORM_F32,
            AmneKernelId.SILU_F32,
            AmneKernelId.ROPE_F32
        )
        if (!candidate.capabilities.supportedKernels.containsAll(required)) {
            return AmneKernelQualification(
                implementationId = candidate.capabilities.implementationId,
                passed = false,
                maxAbsoluteError = Float.POSITIVE_INFINITY,
                checkedKernels = emptySet()
            )
        }
        if (AmneScalarType.F32 !in candidate.capabilities.supportedScalarTypes) {
            return AmneKernelQualification(
                implementationId = candidate.capabilities.implementationId,
                passed = false,
                maxAbsoluteError = Float.POSITIVE_INFINITY,
                checkedKernels = emptySet()
            )
        }

        var maxError = 0f

        fun observe(expected: Float, actual: Float) {
            require(expected.isFinite())
            require(actual.isFinite())
            val error = kotlin.math.abs(expected - actual)
            if (error > maxError) maxError = error
        }

        val left = floatArrayOf(-1.25f, 0.5f, 3f, -2f, 0.125f)
        val right = floatArrayOf(0.75f, -4f, 0.25f, 1.5f, 8f)
        observe(
            AmneReferenceKernels.dotF32(left, right),
            candidate.dotF32(left, right)
        )

        val matrix = floatArrayOf(
            1f, 2f, 3f,
            -1f, 0.5f, 4f
        )
        val vector = floatArrayOf(2f, -1f, 0.5f)
        val expectedMv = FloatArray(2)
        val actualMv = FloatArray(2)
        AmneReferenceKernels.matVecF32(matrix, 2, 3, vector, expectedMv)
        candidate.matVecF32(matrix, 2, 3, vector, actualMv)
        expectedMv.indices.forEach { observe(expectedMv[it], actualMv[it]) }

        val normInput = floatArrayOf(-2f, -1f, 0.5f, 4f)
        val expectedNorm = FloatArray(normInput.size)
        val actualNorm = FloatArray(normInput.size)
        AmneReferenceKernels.rmsNormF32(normInput, expectedNorm, 1e-5f)
        candidate.rmsNormF32(normInput, actualNorm, 1e-5f)
        expectedNorm.indices.forEach { observe(expectedNorm[it], actualNorm[it]) }

        val expectedSilu = FloatArray(normInput.size)
        val actualSilu = FloatArray(normInput.size)
        AmneReferenceKernels.siluF32(normInput, expectedSilu)
        candidate.siluF32(normInput, actualSilu)
        expectedSilu.indices.forEach { observe(expectedSilu[it], actualSilu[it]) }

        val ropeInput = floatArrayOf(1f, 2f, 3f, 4f)
        val expectedRope = FloatArray(ropeInput.size)
        val actualRope = FloatArray(ropeInput.size)
        AmneReferenceKernels.ropeF32(ropeInput, expectedRope, 4, 7)
        candidate.ropeF32(ropeInput, actualRope, 4, 7)
        expectedRope.indices.forEach { observe(expectedRope[it], actualRope[it]) }

        return AmneKernelQualification(
            implementationId = candidate.capabilities.implementationId,
            passed = maxError <= tolerance,
            maxAbsoluteError = maxError,
            checkedKernels = required
        )
    }
}
