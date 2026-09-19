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

enum class GoalRepairRequalificationState {
    REQUALIFIED,
    INVALIDATED
}

data class GoalRepairRequalificationSnapshot(
    val capability: CapabilityId,
    val practiceCreditObservedAtEpochMs: Long,
    val practiceValidatedAtEpochMs: Long,
    val strategy: StrategySignature,
    val verifiedSuccesses: Int = 0,
    val realFailures: Int = 0,
    val lastSuccessAtEpochMs: Long = 0L,
    val lastFailureAtEpochMs: Long = 0L
) {
    init {
        require(practiceCreditObservedAtEpochMs >= 0L)
        require(practiceValidatedAtEpochMs >= 0L)
        require(verifiedSuccesses >= 0)
        require(realFailures >= 0)
        require(lastSuccessAtEpochMs >= 0L)
        require(lastFailureAtEpochMs >= 0L)
        require(capability in strategy.capabilities)
    }

    val state: GoalRepairRequalificationState
        get() = if (
            verifiedSuccesses > 0 &&
            (lastFailureAtEpochMs == 0L || lastSuccessAtEpochMs > lastFailureAtEpochMs)
        ) {
            GoalRepairRequalificationState.REQUALIFIED
        } else {
            GoalRepairRequalificationState.INVALIDATED
        }

    val evidenceConfidence: Double
        get() = if (state == GoalRepairRequalificationState.REQUALIFIED) {
            verifiedSuccesses.toDouble() / (verifiedSuccesses.toDouble() + 1.0)
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

    fun observeGovernedOutcome(
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): List<GoalRepairRequalificationSnapshot> = emptyList()

    fun requalificationSnapshot(
        capability: CapabilityId
    ): GoalRepairRequalificationSnapshot? = null

    fun requalifiedConfidence(capability: CapabilityId): Double = 0.0

    fun requalifiedTransferConfidence(strategy: StrategySignature): Double = 0.0
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
        val practice = snapshot(signal.capability)
        val practiceRelief = if (
            practice != null &&
            practice.creditObservedAtEpochMs == signal.latestObservedAtEpochMs
        ) {
            practice.validatedConfidence * MAX_LEARNING_PRESSURE_RELIEF
        } else {
            0.0
        }
        val real = requalificationSnapshot(signal.capability)
        val realRelief = if (
            real?.state == GoalRepairRequalificationState.REQUALIFIED &&
            real.lastSuccessAtEpochMs >= signal.latestObservedAtEpochMs
        ) {
            real.evidenceConfidence * MAX_REQUALIFIED_LEARNING_PRESSURE_RELIEF
        } else {
            0.0
        }
        val relief = maxOf(practiceRelief, realRelief).coerceIn(
            0.0,
            MAX_REQUALIFIED_LEARNING_PRESSURE_RELIEF
        )
        return (1.0 - relief).coerceIn(
            1.0 - MAX_REQUALIFIED_LEARNING_PRESSURE_RELIEF,
            1.0
        )
    }

    @Synchronized
    override fun observeGovernedOutcome(
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long
    ): List<GoalRepairRequalificationSnapshot> {
        require(plan.complete)
        require(observedAtEpochMs >= 0L)
        if (
            outcome == GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED ||
            outcome == GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED
        ) {
            return emptyList()
        }

        val strategy = StrategySignature.from(plan)
        return strategy.capabilities.distinct().mapNotNull { capability ->
            val markerId = realOutcomeMarkerId(plan.id, capability)
            memory.get(markerId)
                ?.takeIf { it.kind == REAL_OUTCOME_MARKER_KIND }
                ?.let { marker ->
                    require(marker.content == outcome.name) {
                        "repair real-world outcome changed for an existing plan"
                    }
                    return@mapNotNull requalificationSnapshot(capability)
                }

            val previous = requalificationSnapshot(capability)
            val updated = when (outcome) {
                GoalOutcomeEvidenceKind.VERIFIED_SUCCESS -> {
                    val practice = snapshot(capability)
                        ?.takeIf {
                            it.state == GoalRepairValidationState.VALIDATED &&
                                it.lastPracticeAtEpochMs <= observedAtEpochMs
                        }
                        ?: run {
                            rememberRealOutcomeMarker(markerId, outcome, observedAtEpochMs)
                            return@mapNotNull null
                        }
                    val compatiblePrevious = previous?.takeIf {
                        it.practiceCreditObservedAtEpochMs == practice.creditObservedAtEpochMs &&
                            it.practiceValidatedAtEpochMs == practice.lastPracticeAtEpochMs
                    }
                    (compatiblePrevious ?: GoalRepairRequalificationSnapshot(
                        capability = capability,
                        practiceCreditObservedAtEpochMs = practice.creditObservedAtEpochMs,
                        practiceValidatedAtEpochMs = practice.lastPracticeAtEpochMs,
                        strategy = strategy
                    )).copy(
                        strategy = strategy,
                        verifiedSuccesses = (compatiblePrevious?.verifiedSuccesses ?: 0) + 1,
                        lastSuccessAtEpochMs = maxOf(
                            compatiblePrevious?.lastSuccessAtEpochMs ?: 0L,
                            observedAtEpochMs
                        )
                    )
                }
                GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED,
                GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED -> {
                    val active = previous ?: run {
                        rememberRealOutcomeMarker(markerId, outcome, observedAtEpochMs)
                        return@mapNotNull null
                    }
                    active.copy(
                        realFailures = active.realFailures + 1,
                        lastFailureAtEpochMs = maxOf(active.lastFailureAtEpochMs, observedAtEpochMs)
                    )
                }
                GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED,
                GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED -> return@mapNotNull null
            }
            memory.transaction {
                remember(
                    MemoryRecord(
                        id = requalificationId(capability),
                        kind = REQUALIFICATION_KIND,
                        content = GoalRepairRequalificationCodec.encode(updated),
                        importance = if (updated.state == GoalRepairRequalificationState.REQUALIFIED) 0.78 else 0.66,
                        provenance = Provenance(
                            source = "governed-repair-requalification",
                            producer = "goal-repair-validation",
                            confidence = updated.evidenceConfidence,
                            parents = setOf(snapshotId(capability))
                        ),
                        createdAtEpochMs = observedAtEpochMs
                    )
                )
                remember(
                    MemoryRecord(
                        id = markerId,
                        kind = REAL_OUTCOME_MARKER_KIND,
                        content = outcome.name,
                        importance = 0.50,
                        provenance = Provenance(
                            source = "governed-repair-requalification",
                            producer = "goal-repair-validation-marker",
                            confidence = 1.0
                        ),
                        createdAtEpochMs = observedAtEpochMs
                    )
                )
            }
            updated
        }
    }

    override fun requalificationSnapshot(
        capability: CapabilityId
    ): GoalRepairRequalificationSnapshot? =
        memory.get(requalificationId(capability))
            ?.takeIf { it.kind == REQUALIFICATION_KIND }
            ?.let { GoalRepairRequalificationCodec.decode(it.content) }
            ?.takeIf { it.capability == capability }

    override fun requalifiedConfidence(capability: CapabilityId): Double =
        requalificationSnapshot(capability)
            ?.takeIf { it.state == GoalRepairRequalificationState.REQUALIFIED }
            ?.evidenceConfidence
            ?: 0.0

    override fun requalifiedTransferConfidence(strategy: StrategySignature): Double {
        val targetCapabilities = strategy.capabilities.distinct()
        if (targetCapabilities.isEmpty()) return 0.0
        return targetCapabilities.map { capability ->
            val snapshot = requalificationSnapshot(capability)
                ?.takeIf { it.state == GoalRepairRequalificationState.REQUALIFIED }
                ?: return@map 0.0
            val similarity = structuralSimilarity(snapshot.strategy, strategy)
            if (similarity < MIN_REQUALIFIED_TRANSFER_SIMILARITY) {
                0.0
            } else {
                (snapshot.evidenceConfidence * (0.50 + 0.50 * similarity)).coerceIn(0.0, 1.0)
            }
        }.average().coerceIn(0.0, 1.0)
    }

    private fun structuralSimilarity(
        source: StrategySignature,
        target: StrategySignature
    ): Double {
        val sourceSet = source.capabilities.toSet()
        val targetSet = target.capabilities.toSet()
        val union = sourceSet union targetSet
        val jaccard = if (union.isEmpty()) 0.0 else {
            sourceSet.intersect(targetSet).size.toDouble() / union.size.toDouble()
        }
        val longest = maxOf(source.capabilities.size, target.capabilities.size).coerceAtLeast(1)
        val prefix = source.capabilities.zip(target.capabilities)
            .takeWhile { (left, right) -> left == right }
            .size.toDouble() / longest.toDouble()
        return (jaccard * 0.65 + prefix * 0.35).coerceIn(0.0, 1.0)
    }

    private fun rememberRealOutcomeMarker(
        markerId: MemoryId,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long
    ) {
        memory.remember(
            MemoryRecord(
                id = markerId,
                kind = REAL_OUTCOME_MARKER_KIND,
                content = outcome.name,
                importance = 0.44,
                provenance = Provenance(
                    source = "governed-repair-requalification",
                    producer = "goal-repair-validation-marker",
                    confidence = 1.0
                ),
                createdAtEpochMs = observedAtEpochMs
            )
        )
    }

    private fun currentSignal(
        capability: CapabilityId
    ): GoalHierarchicalLearningSignal? =
        credit.learningSignals(setOf(capability), limit = 1)
            .singleOrNull { it.capability == capability && it.meanCredit < 0.0 }

    private fun snapshotId(capability: CapabilityId): MemoryId =
        MemoryId("goal-repair-validation:" + capability.value)

    private fun requalificationId(capability: CapabilityId): MemoryId =
        MemoryId("goal-repair-requalification:" + capability.value)

    private fun realOutcomeMarkerId(planId: PlanId, capability: CapabilityId): MemoryId =
        MemoryId("goal-repair-real-outcome:" + planId.value + ":" + capability.value)

    private fun evidenceMarkerId(evidenceId: MemoryId): MemoryId =
        MemoryId("goal-repair-validation-evidence:" + evidenceId.value)

    companion object {
        const val SNAPSHOT_KIND = "goal-repair-validation-v1"
        const val EVIDENCE_MARKER_KIND = "goal-repair-validation-evidence-v1"
        const val REQUALIFICATION_KIND = "goal-repair-requalification-v1"
        const val REAL_OUTCOME_MARKER_KIND = "goal-repair-real-outcome-v1"
        const val MIN_VALIDATION_ATTEMPTS = 2
        const val MIN_VALIDATION_PASS_RATE = 0.80
        const val MAX_LEARNING_PRESSURE_RELIEF = 0.35
        const val MAX_REQUALIFIED_LEARNING_PRESSURE_RELIEF = 0.60
        const val MIN_REQUALIFIED_TRANSFER_SIMILARITY = 0.50
    }
}

object GoalRepairRefinementPolicy {
    const val MAX_NEGATIVE_PENALTY_ATTENUATION = 0.50
    const val MAX_REQUALIFIED_NEGATIVE_PENALTY_ATTENUATION = 0.80

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
            val requalifiedTransfer = validation.requalifiedTransferConfidence(
                candidate.assessment.candidate.strategy
            )
            if (validationCoverage <= 0.0 && requalifiedTransfer <= 0.0) {
                return@map candidate
            }

            val attenuation = maxOf(
                validationCoverage * MAX_NEGATIVE_PENALTY_ATTENUATION,
                requalifiedTransfer * MAX_REQUALIFIED_NEGATIVE_PENALTY_ATTENUATION
            ).coerceIn(0.0, MAX_REQUALIFIED_NEGATIVE_PENALTY_ATTENUATION)
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


private object GoalRepairRequalificationCodec {
    private const val VERSION = "AMPER_GOAL_REPAIR_REQUALIFICATION_V1"

    fun encode(snapshot: GoalRepairRequalificationSnapshot): String = listOf(
        VERSION,
        snapshot.capability.value,
        snapshot.practiceCreditObservedAtEpochMs.toString(),
        snapshot.practiceValidatedAtEpochMs.toString(),
        snapshot.strategy.capabilities.joinToString(",") { it.value },
        snapshot.verifiedSuccesses.toString(),
        snapshot.realFailures.toString(),
        snapshot.lastSuccessAtEpochMs.toString(),
        snapshot.lastFailureAtEpochMs.toString()
    ).joinToString("\t")

    fun decode(content: String): GoalRepairRequalificationSnapshot? = runCatching {
        val p = content.split('\t')
        require(p.size == 9 && p[0] == VERSION)
        GoalRepairRequalificationSnapshot(
            capability = CapabilityId(p[1]),
            practiceCreditObservedAtEpochMs = p[2].toLong(),
            practiceValidatedAtEpochMs = p[3].toLong(),
            strategy = StrategySignature(p[4].split(',').filter { it.isNotBlank() }.map(::CapabilityId)),
            verifiedSuccesses = p[5].toInt(),
            realFailures = p[6].toInt(),
            lastSuccessAtEpochMs = p[7].toLong(),
            lastFailureAtEpochMs = p[8].toLong()
        )
    }.getOrNull()
}
