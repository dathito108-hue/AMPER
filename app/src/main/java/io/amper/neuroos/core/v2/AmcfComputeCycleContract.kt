package io.amper.neuroos.core.v2

/**
 * Immutable identity binding for every AMCF cognitive pass.
 *
 * AMCF may spend more or fewer reasoning cycles, but every cycle must remain on the exact same
 * verified AMPER foundation and the canonical AMNE2 execution engine.
 */
data class AmcfFoundationBinding(
    val foundationId: String,
    val semanticSha256: String,
    val executionEngine: String = Ami2MigrationContract.productionExecutionEngine
) {
    init {
        require(foundationId.matches(Regex("[a-z0-9][a-z0-9._-]{0,95}"))) {
            "AMCF foundation id must be stable lowercase text"
        }
        require(semanticSha256.matches(Regex("[0-9a-f]{64}"))) {
            "AMCF semantic identity must be lowercase SHA-256"
        }
        require(executionEngine == "AMNE2") {
            "AMCF cognitive cycles must execute through canonical AMNE2"
        }
    }
}

enum class AmcfComputeCycleKind {
    DELIBERATE,
    VERIFY,
    REVISE,
    FINALIZE
}

data class AmcfComputeCycle(
    val index: Int,
    val kind: AmcfComputeCycleKind,
    val foundation: AmcfFoundationBinding,
    val mandatory: Boolean,
    val earlyExitEligibleAfter: Boolean
) {
    init {
        require(index > 0)
        if (kind != AmcfComputeCycleKind.DELIBERATE) {
            require(!earlyExitEligibleAfter) {
                "AMCF early exit is allowed only after bounded deliberation cycles"
            }
        }
        if (
            kind == AmcfComputeCycleKind.VERIFY ||
            kind == AmcfComputeCycleKind.REVISE ||
            kind == AmcfComputeCycleKind.FINALIZE
        ) {
            require(mandatory) {
                "AMCF verify/revise/finalize cycles are mandatory once scheduled"
            }
        }
    }
}

data class AmcfComputeCyclePlan(
    val mode: OmegaComputeMode,
    val foundation: AmcfFoundationBinding,
    val cycles: List<AmcfComputeCycle>,
    val minimumDeliberationCycles: Int,
    val maximumDeliberationCycles: Int,
    val verifyPasses: Int,
    val targetFirstTokenMs: Long?,
    val allowToolUse: Boolean,
    val allowInternetVerification: Boolean
) {
    init {
        require(cycles.isNotEmpty())
        require(cycles.size <= AmcfComputeCyclePlanner.MAX_TOTAL_CYCLES)
        require(cycles.map(AmcfComputeCycle::index) == (1..cycles.size).toList())
        require(cycles.all { it.foundation == foundation }) {
            "AMCF cannot change foundation identity between cognitive cycles"
        }
        require(minimumDeliberationCycles in 0..maximumDeliberationCycles)
        require(
            maximumDeliberationCycles ==
                cycles.count { it.kind == AmcfComputeCycleKind.DELIBERATE }
        )
        require(verifyPasses >= 0)
        require(verifyPasses == cycles.count { it.kind == AmcfComputeCycleKind.VERIFY })
        require(verifyPasses == cycles.count { it.kind == AmcfComputeCycleKind.REVISE }) {
            "AMCF verification requires a paired revise opportunity"
        }
        require(cycles.last().kind == AmcfComputeCycleKind.FINALIZE)
        require(cycles.last().mandatory)
        require(
            cycles.filter { it.earlyExitEligibleAfter }
                .all { it.kind == AmcfComputeCycleKind.DELIBERATE }
        )
        require(targetFirstTokenMs == null || targetFirstTokenMs > 0L)
    }

    val maximumTotalCycles: Int
        get() = cycles.size

    val earlyExitSupported: Boolean
        get() = cycles.any(AmcfComputeCycle::earlyExitEligibleAfter)
}

/**
 * M4 entry contract: translate the existing OMEGA adaptive-compute budget into a bounded sequence
 * of cognitive passes over one foundation.
 *
 * This planner does not execute inference and does not create another reasoning model. Later M4
 * phases may execute these passes through the existing production AMI2/AMNE2 endpoint.
 */
object AmcfComputeCyclePlanner {
    const val MAX_TOTAL_CYCLES: Int = 16

    fun plan(
        foundation: AmcfFoundationBinding,
        budget: OmegaComputeBudget
    ): AmcfComputeCyclePlan {
        val maxDeliberation = budget.recurrentCycles
        val minDeliberation = minimumDeliberationCycles(
            mode = budget.mode,
            maximum = maxDeliberation
        )

        val cycles = mutableListOf<AmcfComputeCycle>()
        repeat(maxDeliberation) { zeroBased ->
            val completed = zeroBased + 1
            val earlyExit =
                budget.mode != OmegaComputeMode.VERIFY &&
                    completed >= minDeliberation &&
                    completed < maxDeliberation
            cycles += AmcfComputeCycle(
                index = cycles.size + 1,
                kind = AmcfComputeCycleKind.DELIBERATE,
                foundation = foundation,
                mandatory = completed <= minDeliberation,
                earlyExitEligibleAfter = earlyExit
            )
        }

        repeat(budget.verifyPasses) {
            cycles += AmcfComputeCycle(
                index = cycles.size + 1,
                kind = AmcfComputeCycleKind.VERIFY,
                foundation = foundation,
                mandatory = true,
                earlyExitEligibleAfter = false
            )
            cycles += AmcfComputeCycle(
                index = cycles.size + 1,
                kind = AmcfComputeCycleKind.REVISE,
                foundation = foundation,
                mandatory = true,
                earlyExitEligibleAfter = false
            )
        }

        cycles += AmcfComputeCycle(
            index = cycles.size + 1,
            kind = AmcfComputeCycleKind.FINALIZE,
            foundation = foundation,
            mandatory = true,
            earlyExitEligibleAfter = false
        )

        require(cycles.size <= MAX_TOTAL_CYCLES) {
            "OMEGA compute budget exceeds bounded AMCF cycle capacity"
        }

        return AmcfComputeCyclePlan(
            mode = budget.mode,
            foundation = foundation,
            cycles = cycles,
            minimumDeliberationCycles = minDeliberation,
            maximumDeliberationCycles = maxDeliberation,
            verifyPasses = budget.verifyPasses,
            targetFirstTokenMs = budget.targetFirstTokenMs,
            allowToolUse = budget.allowToolUse,
            allowInternetVerification = budget.allowInternetVerification
        )
    }

    private fun minimumDeliberationCycles(
        mode: OmegaComputeMode,
        maximum: Int
    ): Int {
        if (maximum == 0) return 0
        val requested = when (mode) {
            OmegaComputeMode.REFLEX,
            OmegaComputeMode.FAST -> 0
            OmegaComputeMode.STANDARD -> 1
            OmegaComputeMode.REASON -> 2
            OmegaComputeMode.DEEP -> 4
            OmegaComputeMode.VERIFY -> maximum
        }
        return minOf(maximum, requested)
    }
}
