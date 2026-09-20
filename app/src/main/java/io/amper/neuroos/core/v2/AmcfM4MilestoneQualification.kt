package io.amper.neuroos.core.v2

enum class AmcfM4QualificationCriterion {
    SINGLE_FOUNDATION_AMNE2,
    BOUNDED_ADAPTIVE_DEPTH,
    STRUCTURED_RECURRENT_STATE,
    GROUNDED_EARLY_EXIT,
    VERIFY_REVISE_LOOP,
    FROZEN_COGNITIVE_SNAPSHOT,
    FINALIZE_TERMINATION
}

data class AmcfM4RunEvidence(
    val mode: OmegaComputeMode,
    val foundation: AmcfFoundationBinding,
    val cognitiveStateDigest: String,
    val plannedTotalCycles: Int,
    val plannedDeliberationCycles: Int,
    val committedCycleKinds: List<AmcfComputeCycleKind>,
    val earlyExitActions: List<AmcfEarlyExitAction>,
    val terminalStateDigest: String
) {
    init {
        require(cognitiveStateDigest.matches(Regex("[0-9a-f]{64}")))
        require(plannedTotalCycles in 1..AmcfComputeCyclePlanner.MAX_TOTAL_CYCLES)
        require(plannedDeliberationCycles in 0 until plannedTotalCycles)
        require(committedCycleKinds.isNotEmpty())
        require(committedCycleKinds.size <= plannedTotalCycles)
        require(terminalStateDigest.matches(Regex("[0-9a-f]{64}")))
        require(committedCycleKinds.last() == AmcfComputeCycleKind.FINALIZE) {
            "qualified AMCF evidence must terminate at FINALIZE"
        }
    }

    companion object {
        fun from(result: AmcfBoundCognitiveRunResult): AmcfM4RunEvidence {
            val plan = result.cycleRun.plan
            require(result.snapshot.cognitiveStateDigest == result.diagnostics.cognitiveStateDigest)
            require(result.cycleRun.terminalState.stateDigest == result.diagnostics.terminalStateDigest)
            return AmcfM4RunEvidence(
                mode = plan.mode,
                foundation = plan.foundation,
                cognitiveStateDigest = result.snapshot.cognitiveStateDigest,
                plannedTotalCycles = plan.maximumTotalCycles,
                plannedDeliberationCycles = plan.maximumDeliberationCycles,
                committedCycleKinds = result.cycleRun.committedStates.map {
                    it.completedCycleKind
                },
                earlyExitActions = result.cycleRun.earlyExitDecisions.map {
                    it.action
                },
                terminalStateDigest = result.cycleRun.terminalState.stateDigest
            )
        }
    }
}

data class AmcfM4QualificationReport(
    val foundation: AmcfFoundationBinding?,
    val requiredModes: Set<OmegaComputeMode>,
    val observedModes: Set<OmegaComputeMode>,
    val satisfiedCriteria: Set<AmcfM4QualificationCriterion>,
    val missingCriteria: Set<AmcfM4QualificationCriterion>
) {
    init {
        require(requiredModes.isNotEmpty())
        require(observedModes.all { it in requiredModes })
        require(satisfiedCriteria.intersect(missingCriteria).isEmpty())
        require(
            satisfiedCriteria + missingCriteria ==
                AmcfM4QualificationCriterion.values().toSet()
        )
    }

    val qualified: Boolean
        get() =
            foundation != null &&
                observedModes == requiredModes &&
                missingCriteria.isEmpty()
}

/**
 * Consolidated M4 closure gate.
 *
 * This evaluator consumes bounded run evidence only. It does not execute inference, inspect
 * candidate prose, recapture cognitive state, or create authority. A milestone is qualified only
 * when FAST, REASON, DEEP and VERIFY evidence all satisfy the canonical AMCF contracts.
 */
object AmcfM4MilestoneQualifier {
    val requiredModes: Set<OmegaComputeMode> = linkedSetOf(
        OmegaComputeMode.FAST,
        OmegaComputeMode.REASON,
        OmegaComputeMode.DEEP,
        OmegaComputeMode.VERIFY
    )

    fun evaluate(
        evidence: Collection<AmcfM4RunEvidence>
    ): AmcfM4QualificationReport {
        val byMode = evidence
            .filter { it.mode in requiredModes }
            .groupBy(AmcfM4RunEvidence::mode)

        val observedModes = byMode.keys
        val uniqueEvidence = requiredModes.all { mode ->
            byMode[mode]?.size == 1
        }

        val samples = if (uniqueEvidence) {
            requiredModes.map { mode -> requireNotNull(byMode[mode]).single() }
        } else {
            emptyList()
        }

        val commonFoundation = samples
            .map(AmcfM4RunEvidence::foundation)
            .distinct()
            .singleOrNull()
            ?.takeIf { it.executionEngine == Ami2MigrationContract.productionExecutionEngine }

        val satisfied = linkedSetOf<AmcfM4QualificationCriterion>()

        if (commonFoundation != null) {
            satisfied += AmcfM4QualificationCriterion.SINGLE_FOUNDATION_AMNE2
        }

        if (samples.isNotEmpty() && adaptiveDepthIsBounded(samples)) {
            satisfied += AmcfM4QualificationCriterion.BOUNDED_ADAPTIVE_DEPTH
        }

        if (
            samples.isNotEmpty() &&
            samples.all { sample ->
                sample.committedCycleKinds.size <= sample.plannedTotalCycles &&
                    sample.terminalStateDigest.matches(Regex("[0-9a-f]{64}"))
            }
        ) {
            satisfied += AmcfM4QualificationCriterion.STRUCTURED_RECURRENT_STATE
        }

        byMode[OmegaComputeMode.REASON]
            ?.singleOrNull()
            ?.takeIf { reason ->
                reason.earlyExitActions.any { action ->
                    action == AmcfEarlyExitAction.ADVANCE_TO_VERIFY ||
                        action == AmcfEarlyExitAction.ADVANCE_TO_FINALIZE
                }
            }
            ?.let {
                satisfied += AmcfM4QualificationCriterion.GROUNDED_EARLY_EXIT
            }

        byMode[OmegaComputeMode.VERIFY]
            ?.singleOrNull()
            ?.takeIf(::hasVerifyReviseLoop)
            ?.let {
                satisfied += AmcfM4QualificationCriterion.VERIFY_REVISE_LOOP
            }

        if (
            samples.isNotEmpty() &&
            samples.all {
                it.cognitiveStateDigest.matches(Regex("[0-9a-f]{64}"))
            }
        ) {
            satisfied += AmcfM4QualificationCriterion.FROZEN_COGNITIVE_SNAPSHOT
        }

        if (
            samples.isNotEmpty() &&
            samples.all { it.committedCycleKinds.lastOrNull() == AmcfComputeCycleKind.FINALIZE }
        ) {
            satisfied += AmcfM4QualificationCriterion.FINALIZE_TERMINATION
        }

        val allCriteria = AmcfM4QualificationCriterion.values().toSet()
        return AmcfM4QualificationReport(
            foundation = commonFoundation,
            requiredModes = requiredModes,
            observedModes = observedModes,
            satisfiedCriteria = satisfied,
            missingCriteria = allCriteria - satisfied
        )
    }

    private fun adaptiveDepthIsBounded(
        samples: List<AmcfM4RunEvidence>
    ): Boolean {
        val byMode = samples.associateBy(AmcfM4RunEvidence::mode)
        val fast = requireNotNull(byMode[OmegaComputeMode.FAST])
        val reason = requireNotNull(byMode[OmegaComputeMode.REASON])
        val deep = requireNotNull(byMode[OmegaComputeMode.DEEP])
        val verify = requireNotNull(byMode[OmegaComputeMode.VERIFY])

        return fast.plannedDeliberationCycles == 0 &&
            reason.plannedDeliberationCycles > fast.plannedDeliberationCycles &&
            deep.plannedDeliberationCycles > reason.plannedDeliberationCycles &&
            verify.plannedDeliberationCycles >= deep.plannedDeliberationCycles &&
            samples.all { it.plannedTotalCycles <= AmcfComputeCyclePlanner.MAX_TOTAL_CYCLES }
    }

    private fun hasVerifyReviseLoop(
        sample: AmcfM4RunEvidence
    ): Boolean {
        val kinds = sample.committedCycleKinds
        if (kinds.lastOrNull() != AmcfComputeCycleKind.FINALIZE) return false

        var verifyCount = 0
        var index = 0
        while (index < kinds.size - 1) {
            if (kinds[index] == AmcfComputeCycleKind.VERIFY) {
                if (kinds.getOrNull(index + 1) != AmcfComputeCycleKind.REVISE) {
                    return false
                }
                verifyCount += 1
                index += 2
            } else {
                index += 1
            }
        }
        return verifyCount > 0
    }
}
