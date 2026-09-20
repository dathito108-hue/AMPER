package io.amper.neuroos.core

/**
 * JNI-backed ARM64/NEON AMNE kernel backend.
 *
 * Phase604 adds native Q4_0/Q8_0 matrix-vector execution alongside the Phase603 F32 kernels.
 * Only primitives declared in [descriptor] are eligible for registry dispatch.
 */
class AmneNativeNeonKernels : AmneKernelBackend {
    override val descriptor = AmneKernelDescriptor(
        backendId = BACKEND_ID,
        primitives = setOf(
            AmneKernelPrimitive.DOT_F32,
            AmneKernelPrimitive.MATVEC_F32,
            AmneKernelPrimitive.MATVEC_Q4_0,
            AmneKernelPrimitive.MATVEC_Q8_0,
            AmneKernelPrimitive.RMS_NORM_F32,
            AmneKernelPrimitive.SILU_F32,
            AmneKernelPrimitive.SWIGLU_F32,
            AmneKernelPrimitive.SOFTMAX_F32,
            AmneKernelPrimitive.ROPE_F32
        ),
        requiredHardware = setOf(
            AmiHardwareFeature.ARM64,
            AmiHardwareFeature.NEON
        ),
        deterministicReference = false
    )

    init {
        require(nativeAbiVersion() == NATIVE_ABI_VERSION) {
            "AMNE native ABI mismatch"
        }
    }

    override fun dotF32(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size)
        return nativeDotF32(left, right)
    }

    override fun matVecF32(
        matrixRowMajor: FloatArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray {
        require(rows > 0 && columns > 0)
        require(vector.size == columns)
        require(matrixRowMajor.size.toLong() == rows.toLong() * columns.toLong())
        return nativeMatVecF32(matrixRowMajor, rows, columns, vector)
    }

    override fun matVecQ4_0(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray {
        require(rows > 0 && columns > 0)
        require(columns % AmneTensorEncoding.Q4_0.blockSize == 0)
        require(vector.size == columns)
        val blocksPerRow = columns / AmneTensorEncoding.Q4_0.blockSize
        require(
            matrixBlocks.size ==
                Math.multiplyExact(
                    Math.multiplyExact(rows, blocksPerRow),
                    AmneTensorEncoding.Q4_0.blockBytes
                )
        )
        return nativeMatVecQ4_0(matrixBlocks, rows, columns, vector)
    }

    override fun matVecQ8_0(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray {
        require(rows > 0 && columns > 0)
        require(columns % AmneTensorEncoding.Q8_0.blockSize == 0)
        require(vector.size == columns)
        val blocksPerRow = columns / AmneTensorEncoding.Q8_0.blockSize
        require(
            matrixBlocks.size ==
                Math.multiplyExact(
                    Math.multiplyExact(rows, blocksPerRow),
                    AmneTensorEncoding.Q8_0.blockBytes
                )
        )
        return nativeMatVecQ8_0(matrixBlocks, rows, columns, vector)
    }

    override fun rmsNormF32(
        input: FloatArray,
        weight: FloatArray,
        epsilon: Float
    ): FloatArray {
        require(input.isNotEmpty())
        require(input.size == weight.size)
        require(epsilon > 0f && epsilon.isFinite())
        return nativeRmsNormF32(input, weight, epsilon)
    }

    override fun siluF32(input: FloatArray): FloatArray =
        nativeSiluF32(input)

    override fun swiGluF32(
        gate: FloatArray,
        up: FloatArray
    ): FloatArray {
        require(gate.size == up.size)
        return nativeSwiGluF32(gate, up)
    }

    override fun softmaxF32(input: FloatArray): FloatArray {
        require(input.isNotEmpty())
        require(input.all { it.isFinite() })
        return nativeSoftmaxF32(input)
    }

    override fun ropeF32(
        input: FloatArray,
        position: Int,
        theta: Float
    ): FloatArray {
        require(input.isNotEmpty() && input.size % 2 == 0)
        require(position >= 0)
        require(theta > 1f && theta.isFinite())
        return nativeRopeF32(input, position, theta)
    }

    private external fun nativeDotF32(
        left: FloatArray,
        right: FloatArray
    ): Float

    private external fun nativeMatVecF32(
        matrix: FloatArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray

    private external fun nativeMatVecQ4_0(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray

    private external fun nativeMatVecQ8_0(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray

    private external fun nativeRmsNormF32(
        input: FloatArray,
        weight: FloatArray,
        epsilon: Float
    ): FloatArray

    private external fun nativeSiluF32(
        input: FloatArray
    ): FloatArray

    private external fun nativeSwiGluF32(
        gate: FloatArray,
        up: FloatArray
    ): FloatArray

    private external fun nativeSoftmaxF32(
        input: FloatArray
    ): FloatArray

    private external fun nativeRopeF32(
        input: FloatArray,
        position: Int,
        theta: Float
    ): FloatArray

    private external fun nativeAbiVersion(): Int

    companion object {
        const val BACKEND_ID: String = "amne-arm64-neon-v1"
        const val NATIVE_ABI_VERSION: Int = 2

        init {
            System.loadLibrary("amper_amne")
        }
    }
}
