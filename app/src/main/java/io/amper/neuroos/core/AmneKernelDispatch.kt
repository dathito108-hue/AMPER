package io.amper.neuroos.core

/**
 * Deterministic AMNE kernel dispatch.
 *
 * Optimized backends may register later, but a backend is eligible only when every declared
 * hardware requirement is present. More specific compatible hardware backends are preferred;
 * the correctness reference remains the final fallback.
 */
class AmneKernelRegistry(
    backends: List<AmneKernelBackend> = listOf(AmneReferenceCpuKernels)
) {
    private val registered: List<AmneKernelBackend>

    init {
        require(backends.isNotEmpty())
        require(backends.map { it.descriptor.backendId }.distinct().size == backends.size) {
            "AMNE kernel backend ids must be unique"
        }
        require(backends.any { it.descriptor.deterministicReference }) {
            "AMNE registry requires a deterministic reference backend"
        }
        registered = backends.toList()
    }

    fun backendFor(
        primitive: AmneKernelPrimitive,
        hardware: AmiHardwareSnapshot?
    ): AmneKernelBackend {
        val availableFeatures = hardware?.features.orEmpty()
        return registered.asSequence()
            .filter { primitive in it.descriptor.primitives }
            .filter { availableFeatures.containsAll(it.descriptor.requiredHardware) }
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
}

enum class AmneMatrixPath {
    F32,
    Q4_0,
    Q8_0,
    UNSUPPORTED
}

object AmneTensorKernelPlanner {
    fun matrixPath(tensor: AmiTensorDescriptor): AmneMatrixPath =
        when (AmneTensorEncoding.fromGgmlType(tensor.sourceEncodingType)) {
            AmneTensorEncoding.F32 -> AmneMatrixPath.F32
            AmneTensorEncoding.Q4_0 -> AmneMatrixPath.Q4_0
            AmneTensorEncoding.Q8_0 -> AmneMatrixPath.Q8_0
            else -> AmneMatrixPath.UNSUPPORTED
        }

    fun requiredPrimitive(tensor: AmiTensorDescriptor): AmneKernelPrimitive? =
        when (matrixPath(tensor)) {
            AmneMatrixPath.F32 -> AmneKernelPrimitive.MATVEC_F32
            AmneMatrixPath.Q4_0 -> AmneKernelPrimitive.MATVEC_Q4_0
            AmneMatrixPath.Q8_0 -> AmneKernelPrimitive.MATVEC_Q8_0
            AmneMatrixPath.UNSUPPORTED -> null
        }
}
