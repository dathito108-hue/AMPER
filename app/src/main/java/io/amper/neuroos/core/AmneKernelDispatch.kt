package io.amper.neuroos.core

/**
 * Deterministic AMNE kernel dispatch.
 *
 * Optimized backends may register later, but a backend is eligible only when every declared
 * hardware requirement is present. More specific compatible hardware backends are preferred;
 * the correctness reference remains the final fallback.
 */
class AmneKernelRegistry(
    backends: List<AmneKernelBackend> = listOf(AmneReferenceCpuKernels),
    admissions: List<AmneBackendAdmission> = emptyList()
) {
    private val registered: List<AmneKernelBackend>
    private val admissionByBackendId: Map<String, AmneBackendAdmission>

    init {
        require(backends.isNotEmpty())
        require(backends.map { it.descriptor.backendId }.distinct().size == backends.size) {
            "AMNE kernel backend ids must be unique"
        }
        require(backends.any { it.descriptor.deterministicReference }) {
            "AMNE registry requires a deterministic reference backend"
        }
        require(admissions.map { it.backendId }.distinct().size == admissions.size) {
            "AMNE admission backend ids must be unique"
        }
        val backendIds = backends.mapTo(linkedSetOf()) { it.descriptor.backendId }
        require(admissions.all { it.backendId in backendIds }) {
            "AMNE admission references an unregistered backend"
        }
        registered = backends.toList()
        admissionByBackendId = admissions.associateBy { it.backendId }
    }

    fun backendFor(
        primitive: AmneKernelPrimitive,
        hardware: AmiHardwareSnapshot?
    ): AmneKernelBackend {
        val availableFeatures = hardware?.features.orEmpty()
        return registered.asSequence()
            .filter { primitive in it.descriptor.primitives }
            .filter { availableFeatures.containsAll(it.descriptor.requiredHardware) }
            .filter { backend ->
                backend.descriptor.deterministicReference ||
                    admissionByBackendId[backend.descriptor.backendId]?.admits(primitive) == true
            }
            .sortedWith(
                compareByDescending<AmneKernelBackend> {
                    it.descriptor.requiredHardware.size
                }.thenBy {
                    if (it.descriptor.deterministicReference) 1 else 0
                }.thenBy {
                    it.descriptor.backendId
                }
            )
            .firstOrNull()
            ?: error("no AMNE kernel backend supports $primitive on current hardware")
    }

    fun descriptors(): List<AmneKernelDescriptor> =
        registered.map { it.descriptor }

    fun admissionFor(backendId: String): AmneBackendAdmission? =
        admissionByBackendId[backendId]
}

enum class AmneMatrixPath {
    F32,
    F16,
    Q4_0,
    Q8_0,
    Q4_K,
    Q5_K,
    Q6_K,
    UNSUPPORTED
}

object AmneTensorKernelPlanner {
    fun matrixPath(tensor: AmiTensorDescriptor): AmneMatrixPath =
        when (AmneTensorEncoding.fromGgmlType(tensor.sourceEncodingType)) {
            AmneTensorEncoding.F32 -> AmneMatrixPath.F32
            AmneTensorEncoding.F16 -> AmneMatrixPath.F16
            AmneTensorEncoding.Q4_0 -> AmneMatrixPath.Q4_0
            AmneTensorEncoding.Q8_0 -> AmneMatrixPath.Q8_0
            AmneTensorEncoding.Q4_K -> AmneMatrixPath.Q4_K
            AmneTensorEncoding.Q5_K -> AmneMatrixPath.Q5_K
            AmneTensorEncoding.Q6_K -> AmneMatrixPath.Q6_K
            else -> AmneMatrixPath.UNSUPPORTED
        }

    fun requiredPrimitive(tensor: AmiTensorDescriptor): AmneKernelPrimitive? =
        when (matrixPath(tensor)) {
            AmneMatrixPath.F32 -> AmneKernelPrimitive.MATVEC_F32
            AmneMatrixPath.F16 -> AmneKernelPrimitive.MATVEC_F32
            AmneMatrixPath.Q4_0 -> AmneKernelPrimitive.MATVEC_Q4_0
            AmneMatrixPath.Q8_0 -> AmneKernelPrimitive.MATVEC_Q8_0
            AmneMatrixPath.Q4_K -> AmneKernelPrimitive.MATVEC_Q4_K
            AmneMatrixPath.Q5_K -> AmneKernelPrimitive.MATVEC_Q5_K
            AmneMatrixPath.Q6_K -> AmneKernelPrimitive.MATVEC_Q6_K
            AmneMatrixPath.UNSUPPORTED -> null
        }
}
