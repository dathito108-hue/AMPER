package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexLearningResourcePolicyTest {
    @Test
    fun severeThermalPressureDefersTrainingWithoutBudget() {
        val policy = policy(
            budget = ResourceBudget(
                maxConcurrentAgents = 1,
                memoryMb = 1024,
                thermalClass = 3
            ),
            battery = 90,
            charging = true
        )

        val decision = policy.evaluate(highValueDemand())

        assertEquals(ReflexLearningResourceMode.DEFERRED, decision.mode)
        assertFalse(decision.allowTraining)
        assertEquals(0, decision.maxFreshExamples)
        assertEquals(0, decision.maxReplayExamples)
    }

    @Test
    fun lowBatteryDefersTrainingWhileChampionCanRemainLive() {
        val policy = policy(
            budget = ResourceBudget(
                maxConcurrentAgents = 2,
                memoryMb = 1024,
                thermalClass = 1
            ),
            battery = 15,
            charging = false
        )

        val decision = policy.evaluate(highValueDemand())

        assertFalse(decision.allowTraining)
        assertEquals(15, decision.batteryPercent)
        assertTrue(decision.reason.contains("battery"))
    }

    @Test
    fun chargingAllowsFullLearningBudgetWhenResourcesAreHealthy() {
        val policy = policy(
            budget = ResourceBudget(
                maxConcurrentAgents = 2,
                memoryMb = 1536,
                thermalClass = 1
            ),
            battery = 12,
            charging = true
        )

        val decision = policy.evaluate(lowValueDemand())

        assertTrue(decision.allowTraining)
        assertEquals(ReflexLearningResourceMode.READY, decision.mode)
        assertEquals(96, decision.maxFreshExamples)
        assertEquals(96, decision.maxReplayExamples)
    }

    @Test
    fun mobilePressureSpendsBudgetOnlyOnHighValueLearning() {
        val policy = policy(
            budget = ResourceBudget(
                maxConcurrentAgents = 1,
                memoryMb = 480,
                thermalClass = 1
            ),
            battery = 80,
            charging = false
        )

        val low = policy.evaluate(lowValueDemand())
        val high = policy.evaluate(highValueDemand())

        assertFalse(low.allowTraining)
        assertTrue(high.allowTraining)
        assertEquals(ReflexLearningResourceMode.LIMITED, high.mode)
        assertEquals(64, high.maxFreshExamples)
        assertEquals(64, high.maxReplayExamples)
    }

    @Test
    fun batteryConservationAllowsOnlyHighValueBoundedUpdate() {
        val policy = policy(
            budget = ResourceBudget(
                maxConcurrentAgents = 2,
                memoryMb = 1024,
                thermalClass = 1
            ),
            battery = 30,
            charging = false
        )

        val low = policy.evaluate(lowValueDemand())
        val high = policy.evaluate(highValueDemand())

        assertFalse(low.allowTraining)
        assertTrue(high.allowTraining)
        assertEquals(64, high.maxFreshExamples)
        assertEquals(48, high.maxReplayExamples)
    }

    private fun highValueDemand() = ReflexLearningDemand(
        freshCandidates = 80,
        replayCandidates = 80,
        learningValue = 0.80,
        hardExamples = 40,
        disagreementExamples = 20,
        novelCapabilities = 1
    )

    private fun lowValueDemand() = ReflexLearningDemand(
        freshCandidates = 80,
        replayCandidates = 80,
        learningValue = 0.20,
        hardExamples = 4,
        disagreementExamples = 2,
        novelCapabilities = 0
    )

    private fun policy(
        budget: ResourceBudget,
        battery: Int?,
        charging: Boolean?
    ): ResourceGovernorReflexLearningResourcePolicy {
        val governor = object : ResourceGovernor {
            override fun currentBudget(): ResourceBudget = budget
            override fun allows(agentCount: Int): Boolean =
                agentCount <= budget.maxConcurrentAgents && budget.thermalClass < 4
        }
        return ResourceGovernorReflexLearningResourcePolicy(
            governor = governor,
            deviceStatusSource = DeviceStatusSource {
                DeviceStatusSnapshot(
                    batteryPercent = battery,
                    charging = charging,
                    availableMemoryMb = 4096,
                    lowMemory = false,
                    appStorageFreeMb = 10_000,
                    appStorageTotalMb = 20_000,
                    thermalStatus = budget.thermalClass,
                    processors = 8,
                    sdkInt = 36,
                    supportedAbis = listOf("arm64-v8a")
                )
            }
        )
    }
}
