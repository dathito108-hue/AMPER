package io.amper.neuroos.core

import kotlin.math.ceil
import kotlin.math.max

/**
 * Admission estimate for GGUF runtimes that memory-map model weights.
 *
 * The GGUF file size is not equivalent to non-reclaimable process memory: mmap-backed weight
 * pages remain file-backed and reclaimable by the kernel. Titan therefore budgets the hot mapped
 * working set plus native/runtime and context overhead instead of charging the entire GGUF file
 * twice against Android's already-conservative available-memory budget.
 *
 * This estimate is intentionally conservative and is only an admission estimate. Android low-memory
 * and thermal signals remain authoritative and the backend is still reconciled/unloaded whenever the
 * live governor budget drops below the resident-session estimate.
 */
internal object MmapGgufMemoryEstimator {
    private const val MIB = 1024L * 1024L
    private const val HOT_WEIGHT_PERCENT = 45L
    private const val MIN_HOT_WEIGHT_MB = 256L
    private const val NATIVE_RUNTIME_OVERHEAD_MB = 192L

    fun estimateMemoryMb(modelLengthBytes: Long?, contextTokens: Int): Int {
        require(contextTokens > 0)

        val modelMb = modelLengthBytes
            ?.takeIf { it > 0L }
            ?.let { (it + MIB - 1L) / MIB }
            ?: return 0

        val hotWeightMb = max(
            MIN_HOT_WEIGHT_MB,
            (modelMb * HOT_WEIGHT_PERCENT + 99L) / 100L
        )

        // Approximate KV/context + graph/scratch growth. Keep the tier deterministic and bounded
        // to the context sizes currently exposed by the mobile llama backend.
        val contextMb = when {
            contextTokens <= 2_048 -> 160L
            contextTokens <= 4_096 -> 256L
            else -> 256L + ceil((contextTokens - 4_096) / 2_048.0).toLong() * 96L
        }

        return (hotWeightMb + contextMb + NATIVE_RUNTIME_OVERHEAD_MB)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }
}
