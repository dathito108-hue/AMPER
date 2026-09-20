package io.amper.neuroos.core

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

enum class AmneKernelPrimitive {
    DOT_F32,
    MATVEC_F32,
    MATVEC_Q4_0,
    MATVEC_Q8_0,
    MATVEC_Q4_K,
    MATVEC_Q5_K,
    MATVEC_Q6_K,
    RMS_NORM_F32,
    SILU_F32,
    SWIGLU_F32,
    SOFTMAX_F32,
    ROPE_F32
}

enum class AmneTensorEncoding(
    val ggmlTypeId: Long?,
    val blockSize: Int,
    val blockBytes: Int
) {
    F32(0L, 1, 4),
    F16(1L, 1, 2),
    Q4_0(2L, 32, 18),
    Q8_0(8L, 32, 34),
    Q4_K(12L, 256, 144),
    Q5_K(13L, 256, 176),
    Q6_K(14L, 256, 210);

    companion object {
        fun fromGgmlType(typeId: Long): AmneTensorEncoding? =
            values().firstOrNull { it.ggmlTypeId == typeId }
    }
}

data class AmneKernelDescriptor(
    val backendId: String,
    val primitives: Set<AmneKernelPrimitive>,
    val requiredHardware: Set<AmiHardwareFeature>,
    val deterministicReference: Boolean
) {
    init {
        require(backendId.isNotBlank())
        require(primitives.isNotEmpty())
    }
}

/**
 * Stable AMNE math ABI.
 *
 * Optimized ARM64/NEON/Vulkan implementations must preserve these observable semantics within their
 * declared numeric tolerance. Tensor/model routing is intentionally outside this interface.
 */
interface AmneKernelBackend {
    val descriptor: AmneKernelDescriptor

    fun dotF32(left: FloatArray, right: FloatArray): Float

    fun matVecF32(
        matrixRowMajor: FloatArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray

    fun matVecQ4_0(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray

    fun matVecQ8_0(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray

    /**
     * K-quant methods have portable exact-reference defaults so adding the storage ABI does not
     * accidentally advertise native acceleration. Optimized backends must explicitly declare the
     * matching primitive in their descriptor before dispatch can select them.
     */
    fun matVecQ4K(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray = AmneKQuantCodec.matVecQ4K(
        matrixBlocks, rows, columns, vector
    )

    fun matVecQ5K(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray = AmneKQuantCodec.matVecQ5K(
        matrixBlocks, rows, columns, vector
    )

    fun matVecQ6K(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray = AmneKQuantCodec.matVecQ6K(
        matrixBlocks, rows, columns, vector
    )

    fun rmsNormF32(
        input: FloatArray,
        weight: FloatArray,
        epsilon: Float
    ): FloatArray

    fun siluF32(input: FloatArray): FloatArray

    fun swiGluF32(
        gate: FloatArray,
        up: FloatArray
    ): FloatArray

    fun softmaxF32(input: FloatArray): FloatArray

    fun ropeF32(
        input: FloatArray,
        position: Int,
        theta: Float = 10_000f
    ): FloatArray
}

/**
 * Correctness-first AMNE backend used as:
 *  - the executable semantic reference for optimized kernels;
 *  - a portable fallback for small tensors;
 *  - a golden implementation for device qualification.
 *
 * It performs no hidden requantization and has no architecture-specific vector intrinsics.
 */
object AmneReferenceCpuKernels : AmneKernelBackend {
    override val descriptor = AmneKernelDescriptor(
        backendId = "amne-reference-cpu-v1",
        primitives = AmneKernelPrimitive.values().toSet(),
        requiredHardware = emptySet(),
        deterministicReference = true
    )

    override fun dotF32(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size) { "AMNE dot vectors must have equal length" }
        var sum = 0.0
        for (index in left.indices) {
            sum += left[index].toDouble() * right[index].toDouble()
        }
        return sum.toFloat()
    }

    override fun matVecF32(
        matrixRowMajor: FloatArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray {
        require(rows > 0 && columns > 0)
        require(vector.size == columns) { "AMNE matvec vector width mismatch" }
        require(matrixRowMajor.size.toLong() == rows.toLong() * columns.toLong()) {
            "AMNE matvec matrix shape mismatch"
        }

        val output = FloatArray(rows)
        var row = 0
        while (row < rows) {
            var sum = 0.0
            val base = row * columns
            var column = 0
            while (column < columns) {
                sum += matrixRowMajor[base + column].toDouble() * vector[column].toDouble()
                column += 1
            }
            output[row] = sum.toFloat()
            row += 1
        }
        return output
    }

    override fun matVecQ4_0(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray {
        require(rows > 0 && columns > 0)
        require(columns % AmneTensorEncoding.Q4_0.blockSize == 0) {
            "Q4_0 column count must be a multiple of 32"
        }
        require(vector.size == columns)
        val blocksPerRow = columns / AmneTensorEncoding.Q4_0.blockSize
        val expectedBytes = Math.multiplyExact(
            Math.multiplyExact(rows, blocksPerRow),
            AmneTensorEncoding.Q4_0.blockBytes
        )
        require(matrixBlocks.size == expectedBytes) {
            "Q4_0 matrix byte length mismatch"
        }

        val output = FloatArray(rows)
        var byteOffset = 0
        var row = 0
        while (row < rows) {
            var sum = 0.0
            var block = 0
            while (block < blocksPerRow) {
                val scaleBits =
                    (matrixBlocks[byteOffset].toInt() and 0xff) or
                        ((matrixBlocks[byteOffset + 1].toInt() and 0xff) shl 8)
                val scale = halfToFloat(scaleBits)
                val vectorBase = block * 32
                var packedIndex = 0
                while (packedIndex < 16) {
                    val packed = matrixBlocks[byteOffset + 2 + packedIndex].toInt() and 0xff
                    val low = (packed and 0x0f) - 8
                    val high = ((packed ushr 4) and 0x0f) - 8
                    sum +=
                        (scale * low.toFloat()).toDouble() *
                            vector[vectorBase + packedIndex].toDouble()
                    sum +=
                        (scale * high.toFloat()).toDouble() *
                            vector[vectorBase + 16 + packedIndex].toDouble()
                    packedIndex += 1
                }
                byteOffset += AmneTensorEncoding.Q4_0.blockBytes
                block += 1
            }
            output[row] = sum.toFloat()
            row += 1
        }
        return output
    }

    override fun matVecQ8_0(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray {
        require(rows > 0 && columns > 0)
        require(columns % AmneTensorEncoding.Q8_0.blockSize == 0) {
            "Q8_0 column count must be a multiple of 32"
        }
        require(vector.size == columns)
        val blocksPerRow = columns / AmneTensorEncoding.Q8_0.blockSize
        val expectedBytes = Math.multiplyExact(
            Math.multiplyExact(rows, blocksPerRow),
            AmneTensorEncoding.Q8_0.blockBytes
        )
        require(matrixBlocks.size == expectedBytes) {
            "Q8_0 matrix byte length mismatch"
        }

        val output = FloatArray(rows)
        var byteOffset = 0
        var row = 0
        while (row < rows) {
            var sum = 0.0
            var block = 0
            while (block < blocksPerRow) {
                val scaleBits =
                    (matrixBlocks[byteOffset].toInt() and 0xff) or
                        ((matrixBlocks[byteOffset + 1].toInt() and 0xff) shl 8)
                val scale = halfToFloat(scaleBits)
                val vectorBase = block * 32
                var element = 0
                while (element < 32) {
                    val quantized = matrixBlocks[byteOffset + 2 + element].toInt()
                    sum +=
                        (scale * quantized.toFloat()).toDouble() *
                            vector[vectorBase + element].toDouble()
                    element += 1
                }
                byteOffset += AmneTensorEncoding.Q8_0.blockBytes
                block += 1
            }
            output[row] = sum.toFloat()
            row += 1
        }
        return output
    }

    override fun rmsNormF32(
        input: FloatArray,
        weight: FloatArray,
        epsilon: Float
    ): FloatArray {
        require(input.isNotEmpty())
        require(input.size == weight.size)
        require(epsilon > 0f && epsilon.isFinite())

        var sumSquares = 0.0
        input.forEach { value ->
            sumSquares += value.toDouble() * value.toDouble()
        }
        val meanSquares = sumSquares / input.size.toDouble()
        val inverseRms = 1.0 / sqrt(meanSquares + epsilon.toDouble())

        return FloatArray(input.size) { index ->
            (input[index].toDouble() * inverseRms * weight[index].toDouble()).toFloat()
        }
    }

    override fun siluF32(input: FloatArray): FloatArray =
        FloatArray(input.size) { index ->
            val value = input[index].toDouble()
            (value / (1.0 + exp(-value))).toFloat()
        }

    override fun swiGluF32(
        gate: FloatArray,
        up: FloatArray
    ): FloatArray {
        require(gate.size == up.size)
        return FloatArray(gate.size) { index ->
            val gateValue = gate[index].toDouble()
            val silu = gateValue / (1.0 + exp(-gateValue))
            (silu * up[index].toDouble()).toFloat()
        }
    }

    override fun softmaxF32(input: FloatArray): FloatArray {
        require(input.isNotEmpty())
        val max = input.maxOrNull()!!.toDouble()
        val exponentials = DoubleArray(input.size)
        var denominator = 0.0
        input.indices.forEach { index ->
            val value = exp(input[index].toDouble() - max)
            exponentials[index] = value
            denominator += value
        }
        require(denominator.isFinite() && denominator > 0.0)
        return FloatArray(input.size) { index ->
            (exponentials[index] / denominator).toFloat()
        }
    }

    override fun ropeF32(
        input: FloatArray,
        position: Int,
        theta: Float
    ): FloatArray {
        require(input.isNotEmpty() && input.size % 2 == 0) {
            "AMNE RoPE input width must be positive and even"
        }
        require(position >= 0)
        require(theta > 1f && theta.isFinite())

        val output = input.copyOf()
        val dimensions = input.size
        var pair = 0
        while (pair < dimensions / 2) {
            val even = pair * 2
            val odd = even + 1
            val exponent = even.toDouble() / dimensions.toDouble()
            val frequency = 1.0 / Math.pow(theta.toDouble(), exponent)
            val angle = position.toDouble() * frequency
            val c = cos(angle)
            val s = sin(angle)
            val x0 = input[even].toDouble()
            val x1 = input[odd].toDouble()
            output[even] = (x0 * c - x1 * s).toFloat()
            output[odd] = (x0 * s + x1 * c).toFloat()
            pair += 1
        }
        return output
    }

    internal fun halfToFloat(bits: Int): Float {
        val sign = (bits ushr 15) and 0x1
        val exponent = (bits ushr 10) and 0x1f
        val fraction = bits and 0x3ff

        val floatBits = when {
            exponent == 0 && fraction == 0 -> sign shl 31
            exponent == 0 -> {
                var mantissa = fraction
                var shift = 0
                while ((mantissa and 0x400) == 0) {
                    mantissa = mantissa shl 1
                    shift += 1
                }
                mantissa = mantissa and 0x3ff
                val adjustedExponent = 127 - 15 - shift + 1
                (sign shl 31) or
                    (adjustedExponent shl 23) or
                    (mantissa shl 13)
            }
            exponent == 0x1f ->
                (sign shl 31) or 0x7f800000 or (fraction shl 13)
            else -> {
                val adjustedExponent = exponent - 15 + 127
                (sign shl 31) or
                    (adjustedExponent shl 23) or
                    (fraction shl 13)
            }
        }
        return java.lang.Float.intBitsToFloat(floatBits)
    }
}

data class AmneKernelQualification(
    val backendId: String,
    val primitive: AmneKernelPrimitive,
    val passed: Boolean,
    val maxAbsoluteError: Float,
    val tolerance: Float
) {
    init {
        require(maxAbsoluteError >= 0f)
        require(tolerance >= 0f)
    }
}

/**
 * Common numerical comparison used later to admit optimized kernels against the reference backend.
 */
object AmneKernelNumerics {
    fun maxAbsoluteError(expected: FloatArray, actual: FloatArray): Float {
        require(expected.size == actual.size)
        var max = 0f
        expected.indices.forEach { index ->
            val error = kotlin.math.abs(expected[index] - actual[index])
            if (error > max) max = error
        }
        return max
    }

    fun qualifies(
        expected: FloatArray,
        actual: FloatArray,
        tolerance: Float
    ): Boolean {
        require(tolerance >= 0f)
        return maxAbsoluteError(expected, actual) <= tolerance
    }
}
