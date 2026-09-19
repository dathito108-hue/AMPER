package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexLearningSchedulerTelemetryTest {
    @Test
    fun telemetryPersistsAndProducesConservativeProfileUnderRepeatedPressure() {
        val memory = InMemoryMemoryOs()
        val telemetry = MemoryBackedReflexLearningSchedulerTelemetry(memory)
        val demand = lowValueDemand()

        repeat(8) { index ->
            telemetry.observeDecision(
                demand = demand,
                decision = ReflexLearningResourceDecision(
                    mode = ReflexLearningResourceMode.DEFERRED,
                    allowTraining = false,
                    maxFreshExamples = 0,
                    maxReplayExamples = 0,
                    memoryBudgetMb = 220,
                    thermalClass = 3,
                    batteryPercent = 70,
                    charging = false,
                    reason = "training deferred by thermal or memory pressure"
                ),
                observedAtEpochMs = 1_000L + index
            )
        }
        repeat(4) { index ->
            telemetry.observeMaintenanceAttempt(
                queueWaitMs = 120_000L + index,
                completed = false,
                failed = index == 0,
                observedAtEpochMs = 2_000L + index
            )
        }

        val restored = MemoryBackedReflexLearningSchedulerTelemetry(memory)
        val snapshot = restored.snapshot()
        val profile = restored.tuningProfile()

        assertEquals(8, snapshot.decisions)
        assertEquals(8, snapshot.deferredDecisions)
        assertEquals(8, snapshot.thermalMemoryDeferrals)
        assertEquals(4, snapshot.maintenanceAttempts)
        assertEquals(1, snapshot.maintenanceFailed)
        assertTrue(requireNotNull(snapshot.queueWaitEwmaMs) >= 120_000.0)
        assertEquals(0.70, profile.budgetScale, 0.0001)
        assertEquals(0.75, profile.predictedDurationScale, 0.0001)
        assertEquals(0.10, profile.minimumLearningValueBoost, 0.0001)
        assertEquals(3, profile.retryDelayMultiplier)
        assertFalse(profile.authorityBearing)
    }

    @Test
    fun selfTuningCanOnlyShrinkCanonicalHealthyBudgets() {
        val memory = InMemoryMemoryOs()
        val telemetry = MemoryBackedReflexLearningSchedulerTelemetry(memory)
        repeat(8) { index ->
            telemetry.observeDecision(
                demand = lowValueDemand(),
                decision = ReflexLearningResourceDecision(
                    mode = ReflexLearningResourceMode.DEFERRED,
                    allowTraining = false,
                    maxFreshExamples = 0,
                    maxReplayExamples = 0,
                    memoryBudgetMb = 220,
                    thermalClass = 3,
                    batteryPercent = 75,
                    charging = false,
                    reason = "training deferred by thermal or memory pressure"
                ),
                observedAtEpochMs = 10_000L + index
            )
        }

        val policy = ResourceGovernorReflexLearningResourcePolicy(
            governor = healthyGovernor(),
            deviceStatusSource = healthyChargingDevice(),
            telemetry = telemetry,
            clock = { 20_000L }
        )
        val decision = policy.evaluate(lowValueDemand())

        assertTrue(decision.allowTraining)
        assertEquals(ReflexLearningResourceMode.READY, decision.mode)
        assertEquals(67, decision.maxFreshExamples)
        assertEquals(67, decision.maxReplayExamples)
        assertTrue(decision.maxFreshExamples <=
            ResourceGovernorReflexLearningResourcePolicy.NORMAL_FRESH_BUDGET)
        assertTrue(decision.maxReplayExamples <=
            ResourceGovernorReflexLearningResourcePolicy.NORMAL_REPLAY_BUDGET)
    }

    @Test
    fun telemetryNeverRelaxesCriticalThermalGate() {
        val memory = InMemoryMemoryOs()
        val telemetry = MemoryBackedReflexLearningSchedulerTelemetry(memory)
        val governor = object : ResourceGovernor {
            override fun currentBudget() = ResourceBudget(
                maxConcurrentAgents = 2,
                memoryMb = 2048,
                thermalClass = 3
            )

            override fun allows(agentCount: Int): Boolean = true
        }
        val policy = ResourceGovernorReflexLearningResourcePolicy(
            governor = governor,
            telemetry = telemetry,
            clock = { 30_000L }
        )

        val decision = policy.evaluate(
            lowValueDemand().copy(
                learningValue = 1.0,
                novelCapabilities = 1
            )
        )

        assertFalse(decision.allowTraining)
        assertEquals(ReflexLearningResourceMode.DEFERRED, decision.mode)
        assertEquals(1, telemetry.snapshot().thermalMemoryDeferrals)
    }

    private fun lowValueDemand() = ReflexLearningDemand(
        freshCandidates = 80,
        replayCandidates = 80,
        learningValue = 0.30,
        hardExamples = 8,
        disagreementExamples = 4,
        novelCapabilities = 0,
        predictedDurationMs = 4_000L,
        historicalCostSamples = 4,
        historicalLearningValuePerSecond = 0.05
    )

    private fun healthyGovernor() = object : ResourceGovernor {
        private val budget = ResourceBudget(
            maxConcurrentAgents = 2,
            memoryMb = 2048,
            thermalClass = 0
        )

        override fun currentBudget(): ResourceBudget = budget

        override fun allows(agentCount: Int): Boolean =
            agentCount <= budget.maxConcurrentAgents
    }

    private fun healthyChargingDevice() = DeviceStatusSource {
        DeviceStatusSnapshot(
            batteryPercent = 80,
            charging = true,
            availableMemoryMb = 4096,
            lowMemory = false,
            appStorageFreeMb = 10_000,
            appStorageTotalMb = 20_000,
            thermalStatus = 0,
            processors = 8,
            sdkInt = 36,
            supportedAbis = listOf("arm64-v8a")
        )
    }
}
