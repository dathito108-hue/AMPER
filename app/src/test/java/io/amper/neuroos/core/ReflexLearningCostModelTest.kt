package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexLearningCostModelTest {
    @Test
    fun costModelPersistsThroughputAndPredictsFutureDuration() {
        val memory = InMemoryMemoryOs()
        val model = MemoryBackedReflexLearningCostModel(memory)

        val first = model.observe(
            ReflexLearningCostObservation(
                durationMs = 1_000L,
                exampleCount = 100,
                learningValue = 0.50,
                succeeded = true,
                observedAtEpochMs = 10L
            )
        )

        assertEquals(1, first.samples)
        assertEquals(100.0, requireNotNull(first.examplesPerSecondEwma), 0.0001)
        assertEquals(500L, first.estimateDurationMs(50))
        assertEquals(1.0, requireNotNull(first.successRateEwma), 0.0001)

        val restored = MemoryBackedReflexLearningCostModel(memory).snapshot()

        assertEquals(first, restored)

        val second = model.observe(
            ReflexLearningCostObservation(
                durationMs = 2_000L,
                exampleCount = 100,
                learningValue = 0.20,
                succeeded = false,
                observedAtEpochMs = 20L
            )
        )

        assertEquals(2, second.samples)
        assertEquals(1_250.0, requireNotNull(second.durationEwmaMs), 0.0001)
        assertEquals(87.5, requireNotNull(second.examplesPerSecondEwma), 0.0001)
        assertEquals(0.75, requireNotNull(second.successRateEwma), 0.0001)
        assertTrue(requireNotNull(second.learningValuePerSecondEwma) > 0.0)
    }

    @Test
    fun expensiveUnpluggedUpdateIsLimitedByLearnedCost() {
        val policy = policy()
        val decision = policy.evaluate(
            lowValueDemand(predictedDurationMs = 9_000L)
        )

        assertTrue(decision.allowTraining)
        assertEquals(ReflexLearningResourceMode.LIMITED, decision.mode)
        assertEquals(64, decision.maxFreshExamples)
        assertEquals(64, decision.maxReplayExamples)
        assertTrue(decision.reason.contains("cost model"))
    }

    @Test
    fun extremeUnpluggedCostDefersNonNovelUpdate() {
        val policy = policy()
        val decision = policy.evaluate(
            lowValueDemand(predictedDurationMs = 25_000L)
        )

        assertFalse(decision.allowTraining)
        assertEquals(ReflexLearningResourceMode.DEFERRED, decision.mode)
        assertTrue(decision.reason.contains("historical training cost"))
    }

    @Test
    fun schedulerRequiresStableRecoveryAfterResourceDeferral() {
        var thermal = 3
        val budget = ResourceBudget(
            maxConcurrentAgents = 2,
            memoryMb = 1024,
            thermalClass = 1
        )
        val governor = object : ResourceGovernor {
            override fun currentBudget(): ResourceBudget =
                budget.copy(thermalClass = thermal)

            override fun allows(agentCount: Int): Boolean =
                agentCount <= budget.maxConcurrentAgents && thermal < 4
        }
        val policy = ResourceGovernorReflexLearningResourcePolicy(
            governor = governor,
            deviceStatusSource = DeviceStatusSource {
                DeviceStatusSnapshot(
                    batteryPercent = 80,
                    charging = false,
                    availableMemoryMb = 4096,
                    lowMemory = false,
                    appStorageFreeMb = 10_000,
                    appStorageTotalMb = 20_000,
                    thermalStatus = thermal,
                    processors = 8,
                    sdkInt = 36,
                    supportedAbis = listOf("arm64-v8a")
                )
            }
        )

        val blocked = policy.evaluate(lowValueDemand())
        thermal = 1
        val firstHealthy = policy.evaluate(lowValueDemand())
        val secondHealthy = policy.evaluate(lowValueDemand())

        assertFalse(blocked.allowTraining)
        assertFalse(firstHealthy.allowTraining)
        assertTrue(firstHealthy.reason.contains("hysteresis"))
        assertTrue(secondHealthy.allowTraining)
        assertEquals(ReflexLearningResourceMode.READY, secondHealthy.mode)
    }

    private fun policy(): ResourceGovernorReflexLearningResourcePolicy {
        val budget = ResourceBudget(
            maxConcurrentAgents = 2,
            memoryMb = 1024,
            thermalClass = 1
        )
        val governor = object : ResourceGovernor {
            override fun currentBudget(): ResourceBudget = budget
            override fun allows(agentCount: Int): Boolean =
                agentCount <= budget.maxConcurrentAgents
        }
        return ResourceGovernorReflexLearningResourcePolicy(
            governor = governor,
            deviceStatusSource = DeviceStatusSource {
                DeviceStatusSnapshot(
                    batteryPercent = 80,
                    charging = false,
                    availableMemoryMb = 4096,
                    lowMemory = false,
                    appStorageFreeMb = 10_000,
                    appStorageTotalMb = 20_000,
                    thermalStatus = 1,
                    processors = 8,
                    sdkInt = 36,
                    supportedAbis = listOf("arm64-v8a")
                )
            }
        )
    }

    private fun lowValueDemand(
        predictedDurationMs: Long? = null
    ) = ReflexLearningDemand(
        freshCandidates = 80,
        replayCandidates = 80,
        learningValue = 0.20,
        hardExamples = 4,
        disagreementExamples = 2,
        novelCapabilities = 0,
        predictedDurationMs = predictedDurationMs,
        historicalCostSamples = 2,
        historicalLearningValuePerSecond = 0.05
    )
}
