package io.amper.neuroos.backend

import io.amper.neuroos.core.AmiHardwareFeature
import io.amper.neuroos.core.AmneKernelBackend
import io.amper.neuroos.core.AmneKernelBackendProvider
import io.amper.neuroos.core.AmneKernelDescriptor
import io.amper.neuroos.core.AmneKernelPrimitive
import io.amper.neuroos.core.AmneReferenceCpuKernels
import io.amper.neuroos.core.AmneKernelNumerics

internal object AmneNativeBridge {
    init {
        System.loadLibrary("amne")
    }

    external fun abiVersion(): Int
    external fun dotF32(left: FloatArray, right: FloatArray): Float
    external fun matVecF32(
        matrix: FloatArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray
    external fun matVecQ40(
        matrix: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray
    external fun matVecQ80(
        matrix: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray
    external fun rmsNormF32(
        input: FloatArray,
        weight: FloatArray,
        epsilon: Float
    ): FloatArray
    external fun siluF32(input: FloatArray): FloatArray
    external fun swiGluF32(gate: FloatArray, up: FloatArray): FloatArray
    external fun softmaxF32(input: FloatArray): FloatArray
    external fun ropeF32(input: FloatArray, position: Int, theta: Float): FloatArray
}

class AmneNativeKernelBackend : AmneKernelBackend {
    override val descriptor = AmneKernelDescriptor(
        backendId = "amne-native-arm64-v1",
        primitives = AmneKernelPrimitive.values().toSet(),
        requiredHardware = setOf(AmiHardwareFeature.ARM64),
        deterministicReference = false
    )

    init {
        require(AmneNativeBridge.abiVersion() == NATIVE_ABI_VERSION) {
            "libamne ABI mismatch"
        }
    }

    override fun dotF32(left: FloatArray, right: FloatArray): Float =
        AmneNativeBridge.dotF32(left, right)

    override fun matVecF32(
        matrixRowMajor: FloatArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray =
        AmneNativeBridge.matVecF32(matrixRowMajor, rows, columns, vector)

    override fun matVecQ4_0(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray =
        AmneNativeBridge.matVecQ40(matrixBlocks, rows, columns, vector)

    override fun matVecQ8_0(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray =
        AmneNativeBridge.matVecQ80(matrixBlocks, rows, columns, vector)

    override fun rmsNormF32(
        input: FloatArray,
        weight: FloatArray,
        epsilon: Float
    ): FloatArray =
        AmneNativeBridge.rmsNormF32(input, weight, epsilon)

    override fun siluF32(input: FloatArray): FloatArray =
        AmneNativeBridge.siluF32(input)

    override fun swiGluF32(
        gate: FloatArray,
        up: FloatArray
    ): FloatArray =
        AmneNativeBridge.swiGluF32(gate, up)

    override fun softmaxF32(input: FloatArray): FloatArray =
        AmneNativeBridge.softmaxF32(input)

    override fun ropeF32(
        input: FloatArray,
        position: Int,
        theta: Float
    ): FloatArray =
        AmneNativeBridge.ropeF32(input, position, theta)

    fun qualify(): Result<AmneNativeQualificationReport> = runCatching {
        val checks = linkedMapOf<AmneKernelPrimitive, Float>()

        val f32Matrix = floatArrayOf(
            1f, 2f, 3f,
            4f, 5f, 6f
        )
        val f32Vector = floatArrayOf(1f, 0.5f, -1f)
        checks[AmneKernelPrimitive.MATVEC_F32] =
            AmneKernelNumerics.maxAbsoluteError(
                AmneReferenceCpuKernels.matVecF32(f32Matrix, 2, 3, f32Vector),
                matVecF32(f32Matrix, 2, 3, f32Vector)
            )

        val q4 = ByteArray(18)
        q4[0] = 0x00
        q4[1] = 0x3c
        repeat(16) { q4[2 + it] = 0x99.toByte() }
        val qVector = FloatArray(32) { 1f }
        checks[AmneKernelPrimitive.MATVEC_Q4_0] =
            AmneKernelNumerics.maxAbsoluteError(
                AmneReferenceCpuKernels.matVecQ4_0(q4, 1, 32, qVector),
                matVecQ4_0(q4, 1, 32, qVector)
            )

        val q8 = ByteArray(34)
        q8[0] = 0x00
        q8[1] = 0x3c
        repeat(32) {
            q8[2 + it] = if (it % 2 == 0) 2.toByte() else (-1).toByte()
        }
        checks[AmneKernelPrimitive.MATVEC_Q8_0] =
            AmneKernelNumerics.maxAbsoluteError(
                AmneReferenceCpuKernels.matVecQ8_0(q8, 1, 32, qVector),
                matVecQ8_0(q8, 1, 32, qVector)
            )

        val normInput = floatArrayOf(1f, 2f, 3f, 4f)
        val normWeight = FloatArray(4) { 1f }
        checks[AmneKernelPrimitive.RMS_NORM_F32] =
            AmneKernelNumerics.maxAbsoluteError(
                AmneReferenceCpuKernels.rmsNormF32(normInput, normWeight, 1e-5f),
                rmsNormF32(normInput, normWeight, 1e-5f)
            )

        val logits = floatArrayOf(10_000f, 10_001f, 9_999f)
        checks[AmneKernelPrimitive.SOFTMAX_F32] =
            AmneKernelNumerics.maxAbsoluteError(
                AmneReferenceCpuKernels.softmaxF32(logits),
                softmaxF32(logits)
            )

        val rope = floatArrayOf(1f, -2f, 3f, -4f)
        checks[AmneKernelPrimitive.ROPE_F32] =
            AmneKernelNumerics.maxAbsoluteError(
                AmneReferenceCpuKernels.ropeF32(rope, 7, 10_000f),
                ropeF32(rope, 7, 10_000f)
            )

        val maxError = checks.values.maxOrNull() ?: 0f
        require(maxError <= QUALIFICATION_TOLERANCE) {
            "libamne numerical qualification failed: maxError=$maxError"
        }

        AmneNativeQualificationReport(
            abiVersion = AmneNativeBridge.abiVersion(),
            checks = checks,
            maxAbsoluteError = maxError,
            tolerance = QUALIFICATION_TOLERANCE
        )
    }

    companion object {
        const val NATIVE_ABI_VERSION = 1
        const val QUALIFICATION_TOLERANCE = 1e-5f
    }
}

data class AmneNativeQualificationReport(
    val abiVersion: Int,
    val checks: Map<AmneKernelPrimitive, Float>,
    val maxAbsoluteError: Float,
    val tolerance: Float
)

class AmneNativeKernelProvider : AmneKernelBackendProvider {
    override fun create(): Result<AmneKernelBackend> = runCatching {
        AmneNativeKernelBackend().also {
            it.qualify().getOrThrow()
        }
    }
}
