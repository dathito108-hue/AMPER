package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexOnlineCalibrationTest {
    @Test
    fun excellentHeldoutEvidenceCanLowerConfidenceOnlyToHardFloor() {
        val memory = InMemoryMemoryOs()
        val checkpoint = NativeCheckpointId("calibration-excellent")
        val calibration = MemoryBackedReflexRuntimeCalibration(
            memory = memory,
            heldout = ReflexHeldoutCalibrationSource {
                metrics(actionPrecision = 0.999, calibrationError = 0.010)
            },
            clock = { 10L }
        )

        val policy = calibration.policy(checkpoint)

        assertEquals(0.985, policy.minFastPathConfidence, 0.0000001)
        assertEquals(0.015, policy.maxFastPathUncertainty, 0.0000001)
    }

    @Test
    fun repeatedSuccessfulGovernedActionsAdaptWithinBoundedRange() {
        val memory = InMemoryMemoryOs()
        val checkpoint = NativeCheckpointId("calibration-online")
        var now = 20L
        val calibration = MemoryBackedReflexRuntimeCalibration(
            memory = memory,
            heldout = ReflexHeldoutCalibrationSource {
                metrics(actionPrecision = 0.992, calibrationError = 0.020)
            },
            clock = { now++ }
        )
        val before = calibration.policy(checkpoint)
        repeat(32) { index ->
            val requestId = ActionRequestId("calibration-action-$index")
            calibration.bindAction(requestId, checkpoint, confidence = 0.991)
            calibration.observeActionOutcome(requestId, ActionStatus.EXECUTED)
        }

        val after = calibration.policy(checkpoint)

        assertEquals(0.990, before.minFastPathConfidence, 0.0000001)
        assertEquals(0.988, after.minFastPathConfidence, 0.0000001)
        assertEquals(32L, after.onlineResolvedActionSamples)
        assertEquals(1.0, after.onlineActionSuccessRate ?: 0.0, 0.0000001)
        assertTrue(after.minFastPathConfidence >= ReflexAdaptiveRuntimePolicy.HARD_MIN_CONFIDENCE)
    }

    @Test
    fun malformedOrUnavailableOutcomesTightenRatherThanLoosenConfidence() {
        val memory = InMemoryMemoryOs()
        val checkpoint = NativeCheckpointId("calibration-negative")
        var now = 100L
        val calibration = MemoryBackedReflexRuntimeCalibration(
            memory = memory,
            heldout = ReflexHeldoutCalibrationSource {
                metrics(actionPrecision = 0.992, calibrationError = 0.020)
            },
            clock = { now++ }
        )
        repeat(32) { index ->
            val requestId = ActionRequestId("calibration-negative-$index")
            calibration.bindAction(requestId, checkpoint, confidence = 0.995)
            calibration.observeActionOutcome(
                requestId,
                if (index < 24) ActionStatus.EXECUTED else ActionStatus.UNAVAILABLE
            )
        }

        val policy = calibration.policy(checkpoint)

        assertEquals(0.992, policy.minFastPathConfidence, 0.0000001)
        assertEquals(0.75, policy.onlineActionSuccessRate ?: 0.0, 0.0000001)
    }

    @Test
    fun latencyBudgetLearnsFromMeasuredRuntimeButCannotEscapeHardBounds() {
        val memory = InMemoryMemoryOs()
        val checkpoint = NativeCheckpointId("calibration-latency")
        val calibration = MemoryBackedReflexRuntimeCalibration(
            memory = memory,
            heldout = ReflexHeldoutCalibrationSource { null },
            clock = { 200L }
        )
        repeat(16) {
            calibration.observePrediction(checkpoint, latencyMs = 40.0)
        }
        val fast = calibration.policy(checkpoint)
        assertEquals(100.0, fast.maxPredictionLatencyMs, 0.0000001)

        repeat(32) {
            calibration.observePrediction(checkpoint, latencyMs = 900.0)
        }
        val slow = calibration.policy(checkpoint)
        assertTrue(slow.maxPredictionLatencyMs <= ReflexAdaptiveRuntimePolicy.HARD_MAX_LATENCY_MS)
        assertTrue(slow.maxPredictionLatencyMs >= ReflexAdaptiveRuntimePolicy.HARD_MIN_LATENCY_MS)
    }

    @Test
    fun pendingBindingSurvivesCalibrationObjectRestartWithoutRawPayload() {
        val memory = InMemoryMemoryOs()
        val checkpoint = NativeCheckpointId("calibration-restart")
        val requestId = ActionRequestId("calibration-pending")
        val first = MemoryBackedReflexRuntimeCalibration(
            memory = memory,
            heldout = ReflexHeldoutCalibrationSource { null },
            clock = { 300L }
        )
        first.bindAction(requestId, checkpoint, confidence = 0.991)

        val restarted = MemoryBackedReflexRuntimeCalibration(
            memory = memory,
            heldout = ReflexHeldoutCalibrationSource { null },
            clock = { 301L }
        )
        restarted.observeActionOutcome(requestId, ActionStatus.EXECUTED)

        val snapshot = restarted.snapshot(checkpoint)
        assertEquals(1L, snapshot.resolvedActionSamples)
        assertEquals(1L, snapshot.executedActions)
    }

    private fun metrics(
        actionPrecision: Double,
        calibrationError: Double
    ) = ReflexDecisionHeldoutMetrics(
        holdoutShardId = NativeDatasetShardId("calibration-holdout"),
        holdoutPayloadSha256 = "a".repeat(64),
        exactDecisionAccuracy = 0.995,
        actionPrecision = actionPrecision,
        escalationRecall = 0.995,
        capabilityAccuracy = 0.995,
        calibrationMeanAbsoluteError = calibrationError,
        totalSamples = 64,
        actionSamples = 32,
        escalationSamples = 32
    )
}
