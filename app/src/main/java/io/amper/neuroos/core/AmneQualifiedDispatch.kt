package io.amper.neuroos.core

import kotlin.math.max

data class AmnePrimitiveBenchmark(
    val primitive: AmneKernelPrimitive,
    val referenceMedianNs: Long,
    val candidateMedianNs: Long,
    val speedup: Double,
    val admitted: Boolean
) {
    init {
        require(referenceMedianNs > 0L)
        require(candidateMedianNs > 0L)
        require(speedup.isFinite() && speedup > 0.0)
    }
}

data class AmneBackendAdmission(
    val backendId: String,
    val admittedPrimitives: Set<AmneKernelPrimitive>,
    val numericalQualificationPassed: Boolean,
    val benchmarkResults: Map<AmneKernelPrimitive, AmnePrimitiveBenchmark>,
    val minimumSpeedup: Double
) {
    init {
        require(backendId.isNotBlank())
        require(minimumSpeedup >= 1.0)
        require(admittedPrimitives.all { primitive ->
            benchmarkResults[primitive]?.admitted == true
        })
        if (!numericalQualificationPassed) {
            require(admittedPrimitives.isEmpty()) {
                "numerically failed AMNE backend cannot admit primitives"
            }
        }
    }

    fun admits(primitive: AmneKernelPrimitive): Boolean =
        numericalQualificationPassed && primitive in admittedPrimitives
}

/**
 * Conservative performance policy.
 *
 * A native primitive must be measurably faster than the deterministic reference path on the
 * current device. Equal performance is intentionally not enough because JNI/native transitions add
 * complexity and thermal variability without benefit.
 */
object AmneDispatchAdmissionPolicy {
    const val DEFAULT_MINIMUM_SPEEDUP: Double = 1.05

    fun evaluate(
        backendId: String,
        numericalQualificationPassed: Boolean,
        measurements: Collection<AmnePrimitiveBenchmark>,
        minimumSpeedup: Double = DEFAULT_MINIMUM_SPEEDUP
    ): AmneBackendAdmission {
        require(backendId.isNotBlank())
        require(minimumSpeedup >= 1.0)

        val normalized = linkedMapOf<AmneKernelPrimitive, AmnePrimitiveBenchmark>()
        measurements.forEach { result ->
            val recalculated = result.copy(
                admitted = numericalQualificationPassed &&
                    result.speedup >= minimumSpeedup &&
                    result.candidateMedianNs < result.referenceMedianNs
            )
            require(normalized.put(result.primitive, recalculated) == null) {
                "duplicate AMNE benchmark primitive: " + result.primitive
            }
        }

        val admitted = normalized.values
            .filter { it.admitted }
            .mapTo(linkedSetOf()) { it.primitive }

        return AmneBackendAdmission(
            backendId = backendId,
            admittedPrimitives = admitted,
            numericalQualificationPassed = numericalQualificationPassed,
            benchmarkResults = normalized,
            minimumSpeedup = minimumSpeedup
        )
    }
}

/**
 * Device-local microbenchmark for AMNE primitive dispatch.
 *
 * Workloads are intentionally representative of decoder inference rather than tiny qualification
 * vectors. Measurement order alternates candidate/reference to reduce systematic thermal/order
 * bias. The median of five samples is used; no result is persisted across process/device state.
 */
object AmneDeviceMicrobenchmark {
    private const val SAMPLE_COUNT = 5
    private const val WARMUP_ROUNDS = 2

    @Volatile
    private var sink: Float = 0f

    fun compare(
        reference: AmneKernelBackend,
        candidate: AmneKernelBackend,
        primitives: Set<AmneKernelPrimitive> = candidate.descriptor.primitives
    ): List<AmnePrimitiveBenchmark> {
        require(reference.descriptor.deterministicReference) {
            "AMNE benchmark reference backend must be deterministic"
        }

        val workloads = createWorkloads()
        return primitives
            .sortedBy { it.ordinal }
            .mapNotNull { primitive ->
                val workload = workloads[primitive] ?: return@mapNotNull null
                benchmarkOne(
                    primitive = primitive,
                    innerIterations = workload.innerIterations,
                    reference = { workload.invoke(reference) },
                    candidate = { workload.invoke(candidate) }
                )
            }
    }

    private data class Workload(
        val innerIterations: Int,
        val invoke: (AmneKernelBackend) -> Float
    )

    private fun createWorkloads(): Map<AmneKernelPrimitive, Workload> {
        val dotLeft = FloatArray(4096) { index ->
            ((index % 29) - 14).toFloat() * 0.03125f
        }
        val dotRight = FloatArray(4096) { index ->
            ((index % 31) - 15).toFloat() * 0.02734375f
        }

        val columns = 1024
        val rows = 128
        val vector = FloatArray(columns) { index ->
            ((index % 23) - 11).toFloat() * 0.0625f
        }
        val f32Matrix = FloatArray(rows * columns) { index ->
            ((index % 37) - 18).toFloat() * 0.015625f
        }

        val q4Blocks = ByteArray(
            rows *
                (columns / AmneTensorEncoding.Q4_0.blockSize) *
                AmneTensorEncoding.Q4_0.blockBytes
        )
        fillQ4Blocks(q4Blocks)

        val q8Blocks = ByteArray(
            rows *
                (columns / AmneTensorEncoding.Q8_0.blockSize) *
                AmneTensorEncoding.Q8_0.blockBytes
        )
        fillQ8Blocks(q8Blocks)

        val hidden = FloatArray(4096) { index ->
            ((index % 41) - 20).toFloat() * 0.05f
        }
        val normWeight = FloatArray(hidden.size) { index ->
            0.75f + (index % 17).toFloat() * 0.015625f
        }

        val ffnGate = FloatArray(11008) { index ->
            ((index % 43) - 21).toFloat() * 0.035f
        }
        val ffnUp = FloatArray(ffnGate.size) { index ->
            ((index % 47) - 23).toFloat() * 0.03f
        }

        val logits = FloatArray(1024) { index ->
            ((index % 113) - 56).toFloat() * 0.08f
        }
        val rope = FloatArray(128) { index ->
            ((index % 19) - 9).toFloat() * 0.1f
        }

        return linkedMapOf(
            AmneKernelPrimitive.DOT_F32 to Workload(16) { backend ->
                backend.dotF32(dotLeft, dotRight)
            },
            AmneKernelPrimitive.MATVEC_F32 to Workload(2) { backend ->
                consume(backend.matVecF32(f32Matrix, rows, columns, vector))
            },
            AmneKernelPrimitive.MATVEC_Q4_0 to Workload(2) { backend ->
                consume(backend.matVecQ4_0(q4Blocks, rows, columns, vector))
            },
            AmneKernelPrimitive.MATVEC_Q8_0 to Workload(2) { backend ->
                consume(backend.matVecQ8_0(q8Blocks, rows, columns, vector))
            },
            AmneKernelPrimitive.RMS_NORM_F32 to Workload(8) { backend ->
                consume(backend.rmsNormF32(hidden, normWeight, 1e-5f))
            },
            AmneKernelPrimitive.SILU_F32 to Workload(8) { backend ->
                consume(backend.siluF32(ffnGate))
            },
            AmneKernelPrimitive.SWIGLU_F32 to Workload(8) { backend ->
                consume(backend.swiGluF32(ffnGate, ffnUp))
            },
            AmneKernelPrimitive.SOFTMAX_F32 to Workload(16) { backend ->
                consume(backend.softmaxF32(logits))
            },
            AmneKernelPrimitive.ROPE_F32 to Workload(32) { backend ->
                consume(backend.ropeF32(rope, position = 127))
            }
        )
    }

    private fun benchmarkOne(
        primitive: AmneKernelPrimitive,
        innerIterations: Int,
        reference: () -> Float,
        candidate: () -> Float
    ): AmnePrimitiveBenchmark {
        require(innerIterations > 0)
        repeat(WARMUP_ROUNDS) {
            sink += reference()
            sink += candidate()
        }

        val referenceSamples = LongArray(SAMPLE_COUNT)
        val candidateSamples = LongArray(SAMPLE_COUNT)
        for (sample in 0 until SAMPLE_COUNT) {
            if (sample % 2 == 0) {
                referenceSamples[sample] = measure(reference, innerIterations)
                candidateSamples[sample] = measure(candidate, innerIterations)
            } else {
                candidateSamples[sample] = measure(candidate, innerIterations)
                referenceSamples[sample] = measure(reference, innerIterations)
            }
        }

        val referenceMedian = median(referenceSamples)
        val candidateMedian = median(candidateSamples)
        val speedup =
            referenceMedian.toDouble() / max(1L, candidateMedian).toDouble()

        return AmnePrimitiveBenchmark(
            primitive = primitive,
            referenceMedianNs = referenceMedian,
            candidateMedianNs = candidateMedian,
            speedup = speedup,
            admitted = speedup >= AmneDispatchAdmissionPolicy.DEFAULT_MINIMUM_SPEEDUP &&
                candidateMedian < referenceMedian
        )
    }

    private fun measure(
        operation: () -> Float,
        iterations: Int
    ): Long {
        require(iterations > 0)
        val started = System.nanoTime()
        repeat(iterations) {
            sink += operation()
        }
        return max(1L, (System.nanoTime() - started) / iterations.toLong())
    }

    private fun consume(values: FloatArray): Float {
        require(values.isNotEmpty())
        return values[0] + values[values.lastIndex]
    }

    private fun median(values: LongArray): Long {
        require(values.isNotEmpty())
        val copy = values.copyOf()
        copy.sort()
        return copy[copy.size / 2]
    }

    private fun fillQ4Blocks(bytes: ByteArray) {
        var offset = 0
        var block = 0
        while (offset < bytes.size) {
            // FP16 scale 0.0625 = 0x2c00, little endian.
            bytes[offset] = 0x00
            bytes[offset + 1] = 0x2c
            for (index in 0 until 16) {
                val low = (block + index) and 0x0f
                val high = (15 - ((block + index) and 0x0f)) and 0x0f
                bytes[offset + 2 + index] = ((high shl 4) or low).toByte()
            }
            offset += AmneTensorEncoding.Q4_0.blockBytes
            block += 1
        }
    }

    private fun fillQ8Blocks(bytes: ByteArray) {
        var offset = 0
        var block = 0
        while (offset < bytes.size) {
            // FP16 scale 0.0625 = 0x2c00, little endian.
            bytes[offset] = 0x00
            bytes[offset + 1] = 0x2c
            for (index in 0 until 32) {
                bytes[offset + 2 + index] =
                    (((block + index) % 31) - 15).toByte()
            }
            offset += AmneTensorEncoding.Q8_0.blockBytes
            block += 1
        }
    }
}

/**
 * Process-local AMNE dispatch state.
 *
 * Benchmark admission is intentionally not persisted: thermal state, OS scheduling and runtime
 * implementation can change between launches. Until a backend is admitted, reference kernels remain
 * the only routable implementation.
 */
object AmneProcessKernelRuntime {
    @Volatile
    private var currentRegistry: AmneKernelRegistry =
        AmneKernelRegistry()

    @Volatile
    private var currentAdmission: AmneBackendAdmission? = null

    @Synchronized
    fun install(
        backend: AmneKernelBackend,
        admission: AmneBackendAdmission
    ) {
        require(backend.descriptor.backendId == admission.backendId)
        require(admission.numericalQualificationPassed)
        currentAdmission = admission
        currentRegistry = AmneKernelRegistry(
            backends = listOf(AmneReferenceCpuKernels, backend),
            admissions = listOf(admission)
        )
    }

    @Synchronized
    fun resetToReference() {
        currentAdmission = null
        currentRegistry = AmneKernelRegistry()
    }

    fun registry(): AmneKernelRegistry = currentRegistry

    fun admission(): AmneBackendAdmission? = currentAdmission
}
