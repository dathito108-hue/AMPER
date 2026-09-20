package io.amper.neuroos.core

data class AmneDeviceQualificationReport(
    val backendId: String,
    val passed: Boolean,
    val maxAbsoluteError: Float,
    val primitiveErrors: Map<AmneKernelPrimitive, Float>
)

/**
 * Runtime-safe optional AMNE native discovery.
 *
 * The canonical APK does not package the native class. Reflection keeps the base source set free of
 * a hard dependency while an AMNE-enabled APK can instantiate the JNI backend and qualify it.
 */
object AmneNativeRuntimeProbe {
    private const val BACKEND_CLASS =
        "io.amper.neuroos.core.AmneNativeNeonKernels"

    fun isPackaged(): Boolean =
        runCatching { Class.forName(BACKEND_CLASS) }.isSuccess

    fun qualify(
        tolerance: Float = 2e-4f
    ): Result<AmneDeviceQualificationReport> = runCatching {
        require(tolerance >= 0f)

        val type = Class.forName(BACKEND_CLASS)
        val instance = type.getDeclaredConstructor().newInstance()
        require(instance is AmneKernelBackend) {
            "packaged AMNE native backend does not implement AmneKernelBackend"
        }
        val backend = instance
        val errors = linkedMapOf<AmneKernelPrimitive, Float>()

        fun record(
            primitive: AmneKernelPrimitive,
            expected: FloatArray,
            actual: FloatArray
        ) {
            errors[primitive] =
                AmneKernelNumerics.maxAbsoluteError(expected, actual)
        }

        val left =
            floatArrayOf(-1.25f, 0.5f, 3f, -2f, 0.125f, 0.25f, -0.75f)
        val right =
            floatArrayOf(0.75f, -4f, 0.25f, 1.5f, 8f, -2f, 0.5f)
        record(
            AmneKernelPrimitive.DOT_F32,
            floatArrayOf(AmneReferenceCpuKernels.dotF32(left, right)),
            floatArrayOf(backend.dotF32(left, right))
        )

        val matrix = floatArrayOf(
            1f, 2f, 3f,
            -1f, 0.5f, 4f,
            0.25f, -2f, 1.5f
        )
        val vector = floatArrayOf(2f, -1f, 0.5f)
        record(
            AmneKernelPrimitive.MATVEC_F32,
            AmneReferenceCpuKernels.matVecF32(matrix, 3, 3, vector),
            backend.matVecF32(matrix, 3, 3, vector)
        )

        val normInput = floatArrayOf(-2f, -1f, 0.5f, 4f)
        val normWeight = floatArrayOf(1f, 0.75f, 1.25f, 0.5f)
        record(
            AmneKernelPrimitive.RMS_NORM_F32,
            AmneReferenceCpuKernels.rmsNormF32(
                normInput,
                normWeight,
                1e-5f
            ),
            backend.rmsNormF32(normInput, normWeight, 1e-5f)
        )

        record(
            AmneKernelPrimitive.SILU_F32,
            AmneReferenceCpuKernels.siluF32(normInput),
            backend.siluF32(normInput)
        )

        val gate = floatArrayOf(-1.25f, 0.25f, 1.5f, 3f)
        val up = floatArrayOf(2f, -3f, 0.5f, 1.25f)
        record(
            AmneKernelPrimitive.SWIGLU_F32,
            AmneReferenceCpuKernels.swiGluF32(gate, up),
            backend.swiGluF32(gate, up)
        )

        val logits = floatArrayOf(-3f, 0.5f, 1.25f, 4f)
        record(
            AmneKernelPrimitive.SOFTMAX_F32,
            AmneReferenceCpuKernels.softmaxF32(logits),
            backend.softmaxF32(logits)
        )

        val rope = floatArrayOf(1f, 2f, 3f, 4f, -1f, 0.5f)
        record(
            AmneKernelPrimitive.ROPE_F32,
            AmneReferenceCpuKernels.ropeF32(rope, 7, 10_000f),
            backend.ropeF32(rope, 7, 10_000f)
        )

        val declared = backend.descriptor.primitives
        val expectedPrimitives = setOf(
            AmneKernelPrimitive.DOT_F32,
            AmneKernelPrimitive.MATVEC_F32,
            AmneKernelPrimitive.RMS_NORM_F32,
            AmneKernelPrimitive.SILU_F32,
            AmneKernelPrimitive.SWIGLU_F32,
            AmneKernelPrimitive.SOFTMAX_F32,
            AmneKernelPrimitive.ROPE_F32
        )
        require(declared.containsAll(expectedPrimitives)) {
            "AMNE native backend does not declare all Phase603 F32 primitives"
        }

        val max = errors.values.maxOrNull() ?: Float.POSITIVE_INFINITY
        AmneDeviceQualificationReport(
            backendId = backend.descriptor.backendId,
            passed = errors.keys == expectedPrimitives &&
                errors.values.all {
                    it.isFinite() && it <= tolerance
                },
            maxAbsoluteError = max,
            primitiveErrors = errors
        )
    }
}
