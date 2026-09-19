package io.amper.neuroos.core

enum class GoalRepairValidationState {
    PRACTICING,
    VALIDATED
}

data class GoalRepairValidationSnapshot(
    val capability: CapabilityId,
    val creditObservedAtEpochMs: Long,
    val passed: Int = 0,
    val failed: Int = 0,
    val lastPracticeAtEpochMs: Long = 0L
) {
    init {
        require(creditObservedAtEpochMs >= 0L)
        require(passed >= 0 && failed >= 0)
        require(lastPracticeAtEpochMs >= 0L)
    }

    val attempts: Int
        get() = passed + failed

    val passRate: Double?
        get() = attempts.takeIf { it > 0 }?.let {
            passed.toDouble() / it.toDouble()
        }

    val evidenceConfidence: Double
        get() {
            val rate = passRate ?: return 0.0
            val depth = attempts.toDouble() / (attempts.toDouble() + 2.0)
            return (rate * depth).coerceIn(0.0, 1.0)
        }

    val state: GoalRepairValidationState
        get() = if (
            attempts >= MemoryBackedGoalRepairValidationModel.MIN_VALIDATION_ATTEMPTS &&
            (passRate ?: 0.0) >= MemoryBackedGoalRepairValidationModel.MIN_VALIDATION_PASS_RATE
        ) {
            GoalRepairValidationState.VALIDATED
        } else {
            GoalRepairValidationState.PRACTICING
        }

    val validatedConfidence: Double
        get() = if (state == GoalRepairValidationState.VALIDATED) {
            evidenceConfidence
        } else {
            0.0
        }

    val authorityBearing: Boolean
        get() = false
}

interface GoalRepairValidationModel {
    fun observe(
        task: AutonomousPracticeTask,
        evidence: AutonomousPracticeEvidence
    ): GoalRepairValidationSnapshot?

    fun snapshot(capability: CapabilityId): GoalRepairValidationSnapshot?

    fun validatedConfidence(capability: CapabilityId): Double

    fun pressureMultiplier(signal: GoalHierarchicalLearningSignal): Double
}

/**
 * Phase366-370 validation layer for credit-directed repair practice.
 *
 * Practice can validate only protocol-level planning repair. It never creates execution success,
 * skill credit, approval or authority. Every validation generation is bound to the timestamp of the
 * newest negative governed credit evidence; newer negative execution evidence immediately makes an
 * older practice validation stale.
 */
class MemoryBackedGoalRepairValidationModel(
    private val memory: MemoryOs,
    private val credit: GoalHierarchicalStrategyCreditModel
) : GoalRepairValidationModel {
    @Synchronized
    override fun observe(
        task: AutonomousPracticeTask,
        evidence: AutonomousPracticeEvidence
    ): GoalRepairValidationSnapshot? {
        require(task.id == evidence.taskId)
        require(task.capability == evidence.capability)
        require(task.kind == evidence.kind)
        if (LearningNeedKind.HIERARCHICAL_STRATEGY_REPAIR !in task.sourceNeeds) {
            return null
        }

        val signal = currentSignal(task.capability) ?: return null
        if (evidence.observedAtEpochMs < signal.latestObservedAtEpochMs) {
            return null
        }

        val markerId = evidenceMarkerId(evidence.id)
        if (memory.get(markerId)?.kind == EVIDENCE_MARKER_KIND) {
            return snapshot(task.capability)
        }

        val previous = snapshot(task.capability)
            ?.takeIf {
                it.creditObservedAtEpochMs == signal.latestObservedAtEpochMs
            }
            ?: GoalRepairValidationSnapshot(
                capability = task.capability,
                creditObservedAtEpochMs = signal.latestObservedAtEpochMs
            )
        val updated = previous.copy(
            passed = previous.passed +
                if (evidence.verdict == AutonomousPracticeVerdict.PASS) 1 else 0,
            failed = previous.failed +
                if (evidence.verdict == AutonomousPracticeVerdict.FAIL) 1 else 0,
            lastPracticeAtEpochMs = maxOf(
                previous.lastPracticeAtEpochMs,
                evidence.observedAtEpochMs
            )
        )

        memory.transaction {
            remember(
                MemoryRecord(
                    id = snapshotId(task.capability),
                    kind = SNAPSHOT_KIND,
                    content = GoalRepairValidationCodec.encode(updated),
                    importance = 0.68,
                    provenance = Provenance(
                        source = "credit-directed-zero-tool-repair",
                        producer = "goal-repair-validation",
                        confidence = updated.evidenceConfidence,
                        parents = setOf(evidence.id)
                    ),
                    createdAtEpochMs = updated.lastPracticeAtEpochMs
                )
            )
            remember(
                MemoryRecord(
                    id = markerId,
                    kind = EVIDENCE_MARKER_KIND,
                    content = listOf(
                        "v=1",
                        task.capability.value,
                        signal.latestObservedAtEpochMs.toString(),
                        evidence.verdict.name
                    ).joinToString("\t"),
                    importance = 0.42,
                    provenance = Provenance(
                        source = "credit-directed-zero-tool-repair",
                        producer = "goal-repair-validation-marker",
                        confidence = 1.0,
                        parents = setOf(evidence.id)
                    ),
                    createdAtEpochMs = evidence.observedAtEpochMs
                )
            )
        }
        return updated
    }

    override fun snapshot(capability: CapabilityId): GoalRepairValidationSnapshot? =
        memory.get(snapshotId(capability))
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { GoalRepairValidationCodec.decode(it.content) }
            ?.takeIf { it.capability == capability }

    override fun validatedConfidence(capability: CapabilityId): Double {
        val signal = currentSignal(capability) ?: return 0.0
        val snapshot = snapshot(capability) ?: return 0.0
        if (snapshot.creditObservedAtEpochMs != signal.latestObservedAtEpochMs) {
            return 0.0
        }
        return snapshot.validatedConfidence
    }

    override fun pressureMultiplier(signal: GoalHierarchicalLearningSignal): Double {
        val snapshot = snapshot(signal.capability) ?: return 1.0
        if (snapshot.creditObservedAtEpochMs != signal.latestObservedAtEpochMs) {
            return 1.0
        }
        val relief = (
            snapshot.validatedConfidence * MAX_LEARNING_PRESSURE_RELIEF
            ).coerceIn(0.0, MAX_LEARNING_PRESSURE_RELIEF)
        return (1.0 - relief).coerceIn(
            1.0 - MAX_LEARNING_PRESSURE_RELIEF,
            1.0
        )
    }

    private fun currentSignal(
        capability: CapabilityId
    ): GoalHierarchicalLearningSignal? =
        credit.learningSignals(setOf(capability), limit = 1)
            .singleOrNull { it.capability == capability && it.meanCredit < 0.0 }

    private fun snapshotId(capability: CapabilityId): MemoryId =
        MemoryId("goal-repair-validation:" + capability.value)

    private fun evidenceMarkerId(evidenceId: MemoryId): MemoryId =
        MemoryId("goal-repair-validation-evidence:" + evidenceId.value)

    companion object {
        const val SNAPSHOT_KIND = "goal-repair-validation-v1"
        const val EVIDENCE_MARKER_KIND = "goal-repair-validation-evidence-v1"
        const val MIN_VALIDATION_ATTEMPTS = 2
        const val MIN_VALIDATION_PASS_RATE = 0.80
        const val MAX_LEARNING_PRESSURE_RELIEF = 0.35
    }
}

object GoalRepairRefinementPolicy {
    const val MAX_NEGATIVE_PENALTY_ATTENUATION = 0.50

    fun apply(
        candidates: List<GoalStrategyPortfolioCandidate>,
        validation: GoalRepairValidationModel
    ): List<GoalStrategyPortfolioCandidate> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.map { candidate ->
            if (candidate.hierarchicalCreditAdjustment >= 0.0) {
                return@map candidate
            }
            val capabilities = candidate.assessment.candidate.strategy.capabilities.distinct()
            if (capabilities.isEmpty()) return@map candidate
            val validationCoverage = (
                capabilities.sumOf(validation::validatedConfidence) /
                    capabilities.size.toDouble()
                ).coerceIn(0.0, 1.0)
            if (validationCoverage <= 0.0) return@map candidate

            val attenuation = (
                validationCoverage * MAX_NEGATIVE_PENALTY_ATTENUATION
                ).coerceIn(0.0, MAX_NEGATIVE_PENALTY_ATTENUATION)
            val refinedAdjustment = (
                candidate.hierarchicalCreditAdjustment * (1.0 - attenuation)
                ).coerceIn(
                    candidate.hierarchicalCreditAdjustment,
                    0.0
                )
            candidate.copy(
                hierarchicalCreditAdjustment = refinedAdjustment,
                portfolioScore = (
                    candidate.portfolioScore +
                        (refinedAdjustment - candidate.hierarchicalCreditAdjustment)
                    ).coerceIn(0.0, 1.0)
            )
        }.sortedWith(
            compareByDescending<GoalStrategyPortfolioCandidate> { it.portfolioScore }
                .thenByDescending { it.exploitationScore }
                .thenByDescending { it.assessment.calibratedSupport }
                .thenBy { it.assessment.candidate.strategy.canonical }
        )
    }
}

private object GoalRepairValidationCodec {
    private const val VERSION = "AMPER_GOAL_REPAIR_VALIDATION_V1"

    fun encode(snapshot: GoalRepairValidationSnapshot): String = listOf(
        VERSION,
        snapshot.capability.value,
        snapshot.creditObservedAtEpochMs.toString(),
        snapshot.passed.toString(),
        snapshot.failed.toString(),
        snapshot.lastPracticeAtEpochMs.toString()
    ).joinToString("\t")

    fun decode(content: String): GoalRepairValidationSnapshot? = runCatching {
        val p = content.split('\t')
        require(p.size == 6 && p[0] == VERSION)
        GoalRepairValidationSnapshot(
            capability = CapabilityId(p[1]),
            creditObservedAtEpochMs = p[2].toLong(),
            passed = p[3].toInt(),
            failed = p[4].toInt(),
            lastPracticeAtEpochMs = p[5].toLong()
        )
    }.getOrNull()
}
