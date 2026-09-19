package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class GoalTransferCalibrationOutcome {
    VERIFIED_SUCCESS,
    EXECUTION_FAILURE,
    GOAL_EVIDENCE_FAILURE,
    AUTHORITY_NEUTRAL,
    PARTIAL_NEUTRAL
}

data class GoalTransferPlanBinding(
    val strategy: StrategySignature,
    val cognitiveStateDigest: String,
    val historicalSupport: Double,
    val contextFit: Double,
    val projectedSupport: Double,
    val calibrationMultiplier: Double,
    val calibratedSupport: Double,
    val staleHistoricalEvidence: Boolean,
    val boundAtEpochMs: Long
) {
    init {
        require(cognitiveStateDigest.matches(Regex("[0-9a-f]{64}")))
        listOf(
            historicalSupport,
            contextFit,
            projectedSupport,
            calibrationMultiplier,
            calibratedSupport
        ).forEach { require(it in 0.0..1.25) }
        require(historicalSupport <= 1.0)
        require(contextFit <= 1.0)
        require(projectedSupport <= 1.0)
        require(calibratedSupport <= 1.0)
        require(boundAtEpochMs >= 0L)
    }

    val authorityBearing: Boolean
        get() = false
}

data class GoalTransferCalibrationSnapshot(
    val strategy: StrategySignature,
    val verifiedSuccesses: Int = 0,
    val executionFailures: Int = 0,
    val goalEvidenceFailures: Int = 0,
    val authorityNeutral: Int = 0,
    val partialNeutral: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastOutcome: GoalTransferCalibrationOutcome? = null,
    val lastAppliedAtEpochMs: Long = 0L,
    val lastSuccessAtEpochMs: Long = 0L,
    val lastFailureAtEpochMs: Long = 0L
) {
    init {
        require(verifiedSuccesses >= 0)
        require(executionFailures >= 0)
        require(goalEvidenceFailures >= 0)
        require(authorityNeutral >= 0)
        require(partialNeutral >= 0)
        require(consecutiveFailures >= 0)
        require(lastAppliedAtEpochMs >= 0L)
        require(lastSuccessAtEpochMs >= 0L)
        require(lastFailureAtEpochMs >= 0L)
        require(consecutiveFailures <= executionFailures + goalEvidenceFailures)
    }

    val comparableAttempts: Int
        get() = verifiedSuccesses + executionFailures + goalEvidenceFailures

    val successRate: Double?
        get() = comparableAttempts.takeIf { it > 0 }?.let {
            verifiedSuccesses.toDouble() / it.toDouble()
        }

    val evidenceConfidence: Double
        get() = comparableAttempts.toDouble() / (comparableAttempts.toDouble() + 3.0)

    val authorityBearing: Boolean
        get() = false
}

data class GoalTransferCalibrationAdjustment(
    val multiplier: Double,
    val calibratedSupport: Double,
    val staleHistoricalEvidence: Boolean,
    val suppressedByFailureStreak: Boolean,
    val snapshot: GoalTransferCalibrationSnapshot?
) {
    init {
        require(multiplier in 0.0..1.25)
        require(calibratedSupport in 0.0..1.0)
    }

    val authorityBearing: Boolean
        get() = false
}

interface GoalTransferCalibrationModel {
    fun observe(
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): GoalTransferCalibrationSnapshot?

    fun snapshot(signature: StrategySignature): GoalTransferCalibrationSnapshot?
}

/**
 * Restart-safe feedback over plans that were actually bound to one exact validated transfer
 * candidate. Plans without [SovereignPlan.goalTransferBinding] never affect this model.
 *
 * Authority/user blocks and partial execution are retained as neutral diagnostics. Only verified
 * success, execution-attributable exhaustion and goal-evidence exhaustion affect transfer quality.
 */
class MemoryBackedGoalTransferCalibrationModel(
    private val memory: MemoryOs
) : GoalTransferCalibrationModel {
    @Synchronized
    override fun observe(
        plan: SovereignPlan,
        outcome: GoalOutcomeEvidenceKind,
        observedAtEpochMs: Long
    ): GoalTransferCalibrationSnapshot? {
        val binding = plan.goalTransferBinding ?: return null
        require(plan.complete)
        require(observedAtEpochMs >= 0L)
        require(binding.strategy == StrategySignature.from(plan)) {
            "transfer calibration requires the terminal plan to retain the bound transfer strategy"
        }

        val calibrationOutcome = when (outcome) {
            GoalOutcomeEvidenceKind.VERIFIED_SUCCESS ->
                GoalTransferCalibrationOutcome.VERIFIED_SUCCESS
            GoalOutcomeEvidenceKind.EXECUTION_EXHAUSTED ->
                GoalTransferCalibrationOutcome.EXECUTION_FAILURE
            GoalOutcomeEvidenceKind.EVIDENCE_EXHAUSTED ->
                GoalTransferCalibrationOutcome.GOAL_EVIDENCE_FAILURE
            GoalOutcomeEvidenceKind.AUTHORITY_BLOCKED ->
                GoalTransferCalibrationOutcome.AUTHORITY_NEUTRAL
            GoalOutcomeEvidenceKind.PARTIAL_EXECUTION_BLOCKED ->
                GoalTransferCalibrationOutcome.PARTIAL_NEUTRAL
        }

        val markerId = markerId(plan.id)
        memory.get(markerId)
            ?.takeIf { it.kind == MARKER_KIND }
            ?.let { CalibrationCodec.decodeMarker(it.content) }
            ?.let { marker ->
                require(marker.strategyDigest == binding.strategy.digest) {
                    "terminal transfer calibration changed strategy"
                }
                require(marker.outcome == calibrationOutcome) {
                    "terminal transfer calibration changed outcome"
                }
                return snapshot(binding.strategy)
            }

        val previous = snapshot(binding.strategy)
            ?: GoalTransferCalibrationSnapshot(strategy = binding.strategy)
        val now = observedAtEpochMs.coerceAtLeast(previous.lastAppliedAtEpochMs)
        val updated = when (calibrationOutcome) {
            GoalTransferCalibrationOutcome.VERIFIED_SUCCESS -> previous.copy(
                verifiedSuccesses = previous.verifiedSuccesses + 1,
                consecutiveFailures = 0,
                lastOutcome = calibrationOutcome,
                lastAppliedAtEpochMs = now,
                lastSuccessAtEpochMs = now
            )
            GoalTransferCalibrationOutcome.EXECUTION_FAILURE -> previous.copy(
                executionFailures = previous.executionFailures + 1,
                consecutiveFailures = previous.consecutiveFailures + 1,
                lastOutcome = calibrationOutcome,
                lastAppliedAtEpochMs = now,
                lastFailureAtEpochMs = now
            )
            GoalTransferCalibrationOutcome.GOAL_EVIDENCE_FAILURE -> previous.copy(
                goalEvidenceFailures = previous.goalEvidenceFailures + 1,
                consecutiveFailures = previous.consecutiveFailures + 1,
                lastOutcome = calibrationOutcome,
                lastAppliedAtEpochMs = now,
                lastFailureAtEpochMs = now
            )
            GoalTransferCalibrationOutcome.AUTHORITY_NEUTRAL -> previous.copy(
                authorityNeutral = previous.authorityNeutral + 1,
                lastOutcome = calibrationOutcome,
                lastAppliedAtEpochMs = now
            )
            GoalTransferCalibrationOutcome.PARTIAL_NEUTRAL -> previous.copy(
                partialNeutral = previous.partialNeutral + 1,
                lastOutcome = calibrationOutcome,
                lastAppliedAtEpochMs = now
            )
        }

        memory.remember(
            MemoryRecord(
                id = markerId,
                kind = MARKER_KIND,
                content = CalibrationCodec.encodeMarker(
                    strategyDigest = binding.strategy.digest,
                    outcome = calibrationOutcome
                ),
                importance = 0.55,
                provenance = Provenance(
                    source = "governed-goal-transfer-outcome",
                    producer = "goal-transfer-calibration",
                    confidence = 1.0
                ),
                createdAtEpochMs = now
            )
        )
        memory.remember(
            MemoryRecord(
                id = snapshotId(binding.strategy),
                kind = SNAPSHOT_KIND,
                content = CalibrationCodec.encodeSnapshot(updated),
                importance = 0.78,
                provenance = Provenance(
                    source = "governed-goal-transfer-outcome",
                    producer = "goal-transfer-calibration",
                    confidence = 1.0,
                    parents = setOf(markerId)
                ),
                createdAtEpochMs = now
            )
        )
        return updated
    }

    @Synchronized
    override fun snapshot(signature: StrategySignature): GoalTransferCalibrationSnapshot? =
        memory.get(snapshotId(signature))
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { CalibrationCodec.decodeSnapshot(it.content) }
            ?.takeIf { it.strategy == signature }

    private fun markerId(planId: PlanId): MemoryId =
        MemoryId("goal-transfer-calibration-observation:" + planId.value)

    private fun snapshotId(signature: StrategySignature): MemoryId =
        MemoryId("goal-transfer-calibration:" + signature.digest)

    companion object {
        const val MARKER_KIND = "goal-transfer-calibration-observation-v1"
        const val SNAPSHOT_KIND = "goal-transfer-calibration-v1"
    }
}

object GoalTransferCalibrationPolicy {
    const val STALE_AFTER_MS = 30L * 24L * 60L * 60L * 1_000L
    const val AGED_AFTER_MS = 90L * 24L * 60L * 60L * 1_000L
    const val HARD_STALE_AFTER_MS = 180L * 24L * 60L * 60L * 1_000L

    fun adjust(
        candidate: GoalStrategyTransferCandidate,
        snapshot: GoalTransferCalibrationSnapshot?,
        nowEpochMs: Long
    ): GoalTransferCalibrationAdjustment {
        require(nowEpochMs >= 0L)

        val ageMs = candidate.latestAnalogousObservedAtEpochMs
            .takeIf { it > 0L }
            ?.let { (nowEpochMs - it).coerceAtLeast(0L) }
        val recencyMultiplier = when {
            ageMs == null -> 1.0
            ageMs < STALE_AFTER_MS -> 1.0
            ageMs < AGED_AFTER_MS -> 0.88
            ageMs < HARD_STALE_AFTER_MS -> 0.70
            else -> 0.52
        }
        val stale = ageMs != null && ageMs >= STALE_AFTER_MS

        val learnedMultiplier = snapshot?.let { calibrated ->
            val rate = calibrated.successRate ?: 0.50
            val confidence = calibrated.evidenceConfidence
            val outcomeFactor = 1.0 + (((0.75 + 0.50 * rate) - 1.0) * confidence)
            val streakFactor = when (calibrated.consecutiveFailures) {
                0 -> 1.0
                1 -> 0.90
                2 -> 0.75
                else -> 0.55
            }
            (outcomeFactor * streakFactor).coerceIn(0.35, 1.20)
        } ?: 1.0

        val suppressed = snapshot?.let {
            it.comparableAttempts >= 3 &&
                it.consecutiveFailures >= 3 &&
                (it.successRate ?: 0.0) < 0.34
        } ?: false

        val multiplier = if (suppressed) {
            0.0
        } else {
            (learnedMultiplier * recencyMultiplier).coerceIn(0.0, 1.20)
        }
        return GoalTransferCalibrationAdjustment(
            multiplier = multiplier,
            calibratedSupport = (candidate.transferSupport * multiplier).coerceIn(0.0, 1.0),
            staleHistoricalEvidence = stale,
            suppressedByFailureStreak = suppressed,
            snapshot = snapshot
        )
    }
}

private data class GoalTransferCalibrationMarker(
    val strategyDigest: String,
    val outcome: GoalTransferCalibrationOutcome
)

private object CalibrationCodec {
    private const val SNAPSHOT_VERSION = "AMPER_GOAL_TRANSFER_CALIBRATION_V1"
    private const val MARKER_VERSION = "AMPER_GOAL_TRANSFER_CALIBRATION_MARKER_V1"

    fun encodeMarker(
        strategyDigest: String,
        outcome: GoalTransferCalibrationOutcome
    ): String = listOf(
        MARKER_VERSION,
        strategyDigest,
        outcome.name
    ).joinToString("\t")

    fun decodeMarker(content: String): GoalTransferCalibrationMarker? = runCatching {
        val parts = content.split('\t')
        require(parts.size == 3 && parts[0] == MARKER_VERSION)
        require(parts[1].matches(Regex("[0-9a-f]{64}")))
        GoalTransferCalibrationMarker(
            strategyDigest = parts[1],
            outcome = GoalTransferCalibrationOutcome.valueOf(parts[2])
        )
    }.getOrNull()

    fun encodeSnapshot(snapshot: GoalTransferCalibrationSnapshot): String = listOf(
        SNAPSHOT_VERSION,
        snapshot.strategy.capabilities.joinToString(",") { it.value },
        snapshot.verifiedSuccesses.toString(),
        snapshot.executionFailures.toString(),
        snapshot.goalEvidenceFailures.toString(),
        snapshot.authorityNeutral.toString(),
        snapshot.partialNeutral.toString(),
        snapshot.consecutiveFailures.toString(),
        snapshot.lastOutcome?.name ?: "~",
        snapshot.lastAppliedAtEpochMs.toString(),
        snapshot.lastSuccessAtEpochMs.toString(),
        snapshot.lastFailureAtEpochMs.toString()
    ).joinToString("\t")

    fun decodeSnapshot(content: String): GoalTransferCalibrationSnapshot? = runCatching {
        val parts = content.split('\t')
        require(parts.size == 12 && parts[0] == SNAPSHOT_VERSION)
        GoalTransferCalibrationSnapshot(
            strategy = StrategySignature(
                parts[1].split(',')
                    .filter { it.isNotBlank() }
                    .map(::CapabilityId)
            ),
            verifiedSuccesses = parts[2].toInt(),
            executionFailures = parts[3].toInt(),
            goalEvidenceFailures = parts[4].toInt(),
            authorityNeutral = parts[5].toInt(),
            partialNeutral = parts[6].toInt(),
            consecutiveFailures = parts[7].toInt(),
            lastOutcome = parts[8].takeUnless { it == "~" }
                ?.let(GoalTransferCalibrationOutcome::valueOf),
            lastAppliedAtEpochMs = parts[9].toLong(),
            lastSuccessAtEpochMs = parts[10].toLong(),
            lastFailureAtEpochMs = parts[11].toLong()
        )
    }.getOrNull()
}
