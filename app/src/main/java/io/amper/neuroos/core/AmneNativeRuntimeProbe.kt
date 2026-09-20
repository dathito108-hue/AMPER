package io.amper.neuroos.core

data class AmneDeviceQualificationReport(
    val backendId: String,
    val passed: Boolean,
    val maxAbsoluteError: Float,
    val primitiveErrors: Map<AmneKernelPrimitive, Float>
)

data class AmneDeviceAdmissionReport(
    val qualification: AmneDeviceQualificationReport,
    val admission: AmneBackendAdmission
) {
    init {
        require(qualification.backendId == admission.backendId)
        if (!qualification.passed) {
            require(admission.admittedPrimitives.isEmpty())
        }
    }
}

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
        qualifyBackend(loadBackend(), tolerance)
    }

    fun benchmarkAndAdmit(
        tolerance: Float = 2e-4f,
        minimumSpeedup: Double = AmneDispatchAdmissionPolicy.DEFAULT_MINIMUM_SPEEDUP
    ): Result<AmneDeviceAdmissionReport> = runCatching {
        require(tolerance >= 0f)
        require(minimumSpeedup >= 1.0)

        val backend = loadBackend()
        val qualification = qualifyBackend(backend, tolerance)
        if (!qualification.passed) {
            AmneProcessKernelRuntime.resetToReference()
            val rejected = AmneDispatchAdmissionPolicy.evaluate(
                backendId = backend.descriptor.backendId,
                numericalQualificationPassed = false,
                measurements = emptyList(),
                minimumSpeedup = minimumSpeedup
            )
            return@runCatching AmneDeviceAdmissionReport(
                qualification = qualification,
                admission = rejected
            )
        }

        val measurements = AmneDeviceMicrobenchmark.compare(
            reference = AmneReferenceCpuKernels,
            candidate = backend,
            primitives = backend.descriptor.primitives
        )
        val admission = AmneDispatchAdmissionPolicy.evaluate(
            backendId = backend.descriptor.backendId,
            numericalQualificationPassed = true,
            measurements = measurements,
            minimumSpeedup = minimumSpeedup
        )
        AmneProcessKernelRuntime.install(backend, admission)

        AmneDeviceAdmissionReport(
            qualification = qualification,
            admission = admission
        )
    }

    private fun loadBackend(): AmneKernelBackend {
        val type = Class.forName(BACKEND_CLASS)
        val instance = type.getDeclaredConstructor().newInstance()
        require(instance is AmneKernelBackend) {
            "packaged AMNE native backend does not implement AmneKernelBackend"
        }
        return instance
    }

    private fun qualifyBackend(
        backend: AmneKernelBackend,
        tolerance: Float
    ): AmneDeviceQualificationReport {
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

        val quantVector = FloatArray(32) { index ->
            ((index % 7) - 3).toFloat() * 0.25f
        }
        val q4 = ByteArray(AmneTensorEncoding.Q4_0.blockBytes)
        q4[0] = 0x00
        q4[1] = 0x3c
        for (index in 0 until 16) {
            val low = (index % 16) and 0x0f
            val high = (15 - index) and 0x0f
            q4[2 + index] = ((high shl 4) or low).toByte()
        }
        record(
            AmneKernelPrimitive.MATVEC_Q4_0,
            AmneReferenceCpuKernels.matVecQ4_0(q4, 1, 32, quantVector),
            backend.matVecQ4_0(q4, 1, 32, quantVector)
        )

        val q8 = ByteArray(AmneTensorEncoding.Q8_0.blockBytes)
        q8[0] = 0x00
        q8[1] = 0x38
        for (index in 0 until 32) {
            q8[2 + index] = ((index % 17) - 8).toByte()
        }
        record(
            AmneKernelPrimitive.MATVEC_Q8_0,
            AmneReferenceCpuKernels.matVecQ8_0(q8, 1, 32, quantVector),
            backend.matVecQ8_0(q8, 1, 32, quantVector)
        )

        val kVector = FloatArray(256) { index ->
            ((index % 19) - 9).toFloat() * 0.03125f
        }

        val q4k = deterministicKQuantBlock(AmneTensorEncoding.Q4_K)
        record(
            AmneKernelPrimitive.MATVEC_Q4_K,
            AmneReferenceCpuKernels.matVecQ4K(q4k, 1, 256, kVector),
            backend.matVecQ4K(q4k, 1, 256, kVector)
        )

        val q5k = deterministicKQuantBlock(AmneTensorEncoding.Q5_K)
        record(
            AmneKernelPrimitive.MATVEC_Q5_K,
            AmneReferenceCpuKernels.matVecQ5K(q5k, 1, 256, kVector),
            backend.matVecQ5K(q5k, 1, 256, kVector)
        )

        val q6k = deterministicKQuantBlock(AmneTensorEncoding.Q6_K)
        record(
            AmneKernelPrimitive.MATVEC_Q6_K,
            AmneReferenceCpuKernels.matVecQ6K(q6k, 1, 256, kVector),
            backend.matVecQ6K(q6k, 1, 256, kVector)
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
            AmneKernelPrimitive.MATVEC_Q4_0,
            AmneKernelPrimitive.MATVEC_Q8_0,
            AmneKernelPrimitive.MATVEC_Q4_K,
            AmneKernelPrimitive.MATVEC_Q5_K,
            AmneKernelPrimitive.MATVEC_Q6_K,
            AmneKernelPrimitive.RMS_NORM_F32,
            AmneKernelPrimitive.SILU_F32,
            AmneKernelPrimitive.SWIGLU_F32,
            AmneKernelPrimitive.SOFTMAX_F32,
            AmneKernelPrimitive.ROPE_F32
        )
        require(declared.containsAll(expectedPrimitives)) {
            "AMNE native backend does not declare all Phase616 primitives"
        }

        val max = errors.values.maxOrNull() ?: Float.POSITIVE_INFINITY
        return AmneDeviceQualificationReport(
            backendId = backend.descriptor.backendId,
            passed = errors.keys == expectedPrimitives &&
                errors.values.all {
                    it.isFinite() && it <= tolerance
                },
            maxAbsoluteError = max,
            primitiveErrors = errors
        )
    }

    private fun deterministicKQuantBlock(
        encoding: AmneTensorEncoding
    ): ByteArray {
        require(
            encoding == AmneTensorEncoding.Q4_K ||
                encoding == AmneTensorEncoding.Q5_K ||
                encoding == AmneTensorEncoding.Q6_K
        )
        val bytes = ByteArray(encoding.blockBytes)
        when (encoding) {
            AmneTensorEncoding.Q4_K -> {
                // d=0.0625 (0x2c00), dmin=0.03125 (0x2800)
                bytes[0] = 0x00
                bytes[1] = 0x2c
                bytes[2] = 0x00
                bytes[3] = 0x28
                for (i in 0 until 12) bytes[4 + i] = ((i * 7 + 11) and 0xff).toByte()
                for (i in 0 until 128) bytes[16 + i] = ((i * 13 + 5) and 0xff).toByte()
            }
            AmneTensorEncoding.Q5_K -> {
                bytes[0] = 0x00
                bytes[1] = 0x2c
                bytes[2] = 0x00
                bytes[3] = 0x28
                for (i in 0 until 12) bytes[4 + i] = ((i * 5 + 9) and 0xff).toByte()
                for (i in 0 until 32) bytes[16 + i] = ((i * 3 + 1) and 0xff).toByte()
                for (i in 0 until 128) bytes[48 + i] = ((i * 11 + 7) and 0xff).toByte()
            }
            AmneTensorEncoding.Q6_K -> {
                for (i in 0 until 128) bytes[i] = ((i * 9 + 3) and 0xff).toByte()
                for (i in 0 until 64) bytes[128 + i] = ((i * 5 + 1) and 0xff).toByte()
                for (i in 0 until 16) bytes[192 + i] = ((i % 15) - 7).toByte()
                bytes[208] = 0x00
                bytes[209] = 0x2c
            }
            else -> error("not K-quant")
        }
        return bytes
    }

}
