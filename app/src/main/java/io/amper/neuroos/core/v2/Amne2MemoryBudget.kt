package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiDecoderStackPlan
import io.amper.neuroos.core.AmiHardwareSnapshot
import io.amper.neuroos.core.AmiRequestMemoryEstimate
import io.amper.neuroos.core.AmiRequestMemoryEstimator

data class Amne2DecoderMemoryGeometry(
    val layerKvWidths: List<Int>,
    val hiddenSize: Int,
    val maxQueryWidth: Int,
    val maxFfnWidth: Int,
    val maxContextTokens: Int
) {
    init {
        require(layerKvWidths.isNotEmpty())
        require(layerKvWidths.all { it > 0 })
        require(hiddenSize > 0)
        require(maxQueryWidth > 0)
        require(maxFfnWidth > 0)
        require(maxContextTokens >= 2)
    }

    companion object {
        fun from(plan: AmiDecoderStackPlan): Amne2DecoderMemoryGeometry =
            Amne2DecoderMemoryGeometry(
                layerKvWidths = plan.layers.map { it.attention.kvWidth },
                hiddenSize = plan.hiddenSize,
                maxQueryWidth = plan.layers.maxOf { it.attention.queryWidth },
                maxFfnWidth = plan.layers.maxOf { it.ffn.feedForwardSize },
                maxContextTokens = plan.maxContextTokens
            )
    }
}

data class Amne2MemoryBudget(
    val hardwareFingerprint: Amne2HardwareFingerprint,
    val memoryClassMb: Int,
    val reservedHeadroomMb: Int,
    val sessionBudgetMb: Int,
    val mmapWindowBytes: Int,
    val modelMaxContextTokens: Int,
    val safeContextTokens: Int,
    val estimateAtSafeContext: AmiRequestMemoryEstimate
) {
    init {
        require(memoryClassMb > 0)
        require(reservedHeadroomMb > 0)
        require(sessionBudgetMb > 0)
        require(reservedHeadroomMb + sessionBudgetMb <= memoryClassMb)
        require(mmapWindowBytes > 0)
        require(modelMaxContextTokens >= 2)
        require(safeContextTokens in 2..modelMaxContextTokens)
        require(estimateAtSafeContext.activeContextTokens == safeContextTokens)
        require(estimateAtSafeContext.estimatedMemoryMb <= sessionBudgetMb)
    }
}

/**
 * Mobile memory admission for one AMNE2 execution session.
 *
 * The model's advertised context is an upper bound, not a promise that the device can safely hold
 * that much KV state. This policy leaves process headroom, scales mmap windows down on smaller
 * devices, then binary-searches the largest context whose existing request-memory estimate fits the
 * session budget.
 */
object Amne2MemoryBudgetPolicy {
    private const val MIB: Long = 1024L * 1024L
    private const val MIN_MMAP_WINDOW_BYTES: Int = 1 * 1024 * 1024
    private const val MAX_SESSION_BUDGET_MB: Int = 768
    private const val NORMAL_MIN_RESERVE_MB: Int = 40
    private const val LOW_RAM_MIN_RESERVE_MB: Int = 48

    fun derive(
        plan: AmiDecoderStackPlan,
        hardware: AmiHardwareSnapshot,
        configuredMaxWindowBytes: Int
    ): Result<Amne2MemoryBudget> =
        deriveGeometry(
            geometry = Amne2DecoderMemoryGeometry.from(plan),
            hardware = hardware,
            configuredMaxWindowBytes = configuredMaxWindowBytes
        )

    internal fun deriveGeometry(
        geometry: Amne2DecoderMemoryGeometry,
        hardware: AmiHardwareSnapshot,
        configuredMaxWindowBytes: Int
    ): Result<Amne2MemoryBudget> = runCatching {
        require(configuredMaxWindowBytes > 0)

        val reservedHeadroomMb = reservedHeadroomMb(hardware)
        val sessionBudgetMb = (hardware.memoryClassMb - reservedHeadroomMb)
            .coerceAtMost(MAX_SESSION_BUDGET_MB)
        require(sessionBudgetMb > 0) {
            "AMNE2 memory class leaves no safe execution-session budget"
        }

        val mmapWindowBytes = maxWindowBytes(
            hardware = hardware,
            configuredMaxWindowBytes = configuredMaxWindowBytes,
            sessionBudgetMb = sessionBudgetMb
        )

        fun estimate(contextTokens: Int): AmiRequestMemoryEstimate =
            AmiRequestMemoryEstimator.estimateGeometry(
                layerKvWidths = geometry.layerKvWidths,
                hiddenSize = geometry.hiddenSize,
                maxQueryWidth = geometry.maxQueryWidth,
                maxFfnWidth = geometry.maxFfnWidth,
                maxContextTokens = geometry.maxContextTokens,
                promptTokens = contextTokens - 1,
                requestedOutputTokens = 1,
                maxWindowBytes = mmapWindowBytes
            )

        fun fits(contextTokens: Int): Boolean =
            runCatching {
                estimate(contextTokens).estimatedMemoryMb <= sessionBudgetMb
            }.getOrDefault(false)

        require(fits(2)) {
            "AMNE2 device memory budget cannot safely admit the minimum decoder context"
        }

        var low = 2
        var high = geometry.maxContextTokens
        var safe = 2
        while (low <= high) {
            val middle = low + (high - low) / 2
            if (fits(middle)) {
                safe = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        val safeEstimate = estimate(safe)
        Amne2MemoryBudget(
            hardwareFingerprint = Amne2HardwareFingerprint.from(hardware),
            memoryClassMb = hardware.memoryClassMb,
            reservedHeadroomMb = reservedHeadroomMb,
            sessionBudgetMb = sessionBudgetMb,
            mmapWindowBytes = mmapWindowBytes,
            modelMaxContextTokens = geometry.maxContextTokens,
            safeContextTokens = safe,
            estimateAtSafeContext = safeEstimate
        )
    }

    fun maxWindowBytes(
        hardware: AmiHardwareSnapshot,
        configuredMaxWindowBytes: Int
    ): Int {
        require(configuredMaxWindowBytes > 0)
        val reserve = reservedHeadroomMb(hardware)
        val budget = (hardware.memoryClassMb - reserve)
            .coerceAtLeast(1)
            .coerceAtMost(MAX_SESSION_BUDGET_MB)
        return maxWindowBytes(hardware, configuredMaxWindowBytes, budget)
    }

    private fun maxWindowBytes(
        hardware: AmiHardwareSnapshot,
        configuredMaxWindowBytes: Int,
        sessionBudgetMb: Int
    ): Int {
        require(configuredMaxWindowBytes > 0)
        require(sessionBudgetMb > 0)

        val budgetBytes = Math.multiplyExact(sessionBudgetMb.toLong(), MIB)
        val fractionDivisor = if (hardware.lowRamDevice) 24L else 16L
        val byBudget = (budgetBytes / fractionDivisor)
            .coerceAtLeast(MIN_MMAP_WINDOW_BYTES.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return minOf(configuredMaxWindowBytes, byBudget)
    }

    private fun reservedHeadroomMb(hardware: AmiHardwareSnapshot): Int {
        val proportional = if (hardware.lowRamDevice) {
            (hardware.memoryClassMb * 40L / 100L).toInt()
        } else {
            (hardware.memoryClassMb * 30L / 100L).toInt()
        }
        val minimum = if (hardware.lowRamDevice) {
            LOW_RAM_MIN_RESERVE_MB
        } else {
            NORMAL_MIN_RESERVE_MB
        }
        return maxOf(minimum, proportional)
            .coerceAtMost(hardware.memoryClassMb - 1)
    }
}
