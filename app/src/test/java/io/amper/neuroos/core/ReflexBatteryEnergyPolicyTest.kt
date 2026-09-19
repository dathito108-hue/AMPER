package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexBatteryEnergyPolicyTest {
    private val normalBudget = ResourceBudget(
        maxConcurrentAgents = 2,
        memoryMb = 512,
        thermalClass = 1
    )

    @Test
    fun criticalBatteryBlocksLearnedReflexWhenNotCharging() {
        val policy = policy(battery = 5, charging = false)

        val decision = policy.evaluate(adaptive(latencyMs = 60.0))

        assertEquals(ReflexRuntimeResourceMode.BLOCKED, decision.mode)
        assertEquals(false, decision.allowLearnedInference)
        assertEquals(5, decision.batteryPercent)
        assertEquals(ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION, decision.residencyHint)
    }

    @Test
    fun chargingRemovesBatteryRestrictionButKeepsResourceGate() {
        val policy = policy(battery = 5, charging = true)

        val decision = policy.evaluate(adaptive(latencyMs = 220.0))

        assertEquals(ReflexRuntimeResourceMode.NORMAL, decision.mode)
        assertTrue(decision.allowLearnedInference)
        assertEquals(true, decision.charging)
        assertEquals(ReflexRuntimeResidencyHint.KEEP_WARM, decision.residencyHint)
    }

    @Test
    fun lowBatteryAllowsOnlyProvenFastCheckpointAndRequestsTransientRelease() {
        val policy = policy(battery = 12, charging = false)

        val fast = policy.evaluate(adaptive(latencyMs = 90.0))
        val slow = policy.evaluate(adaptive(latencyMs = 160.0))

        assertTrue(fast.allowLearnedInference)
        assertEquals(2_000L, fast.minInterInferenceMs)
        assertEquals(ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION, fast.residencyHint)
        assertEquals(false, slow.allowLearnedInference)
    }

    @Test
    fun residencyAwarePortReleasesTransientResourcesAndTracksWorkCost() {
        var releaseCalls = 0
        var nanos = 0L
        val resourcePolicy = ReflexRuntimeResourcePolicy {
            ReflexRuntimeResourceDecision(
                mode = ReflexRuntimeResourceMode.PRESSURED,
                allowLearnedInference = true,
                memoryBudgetMb = 256,
                thermalClass = 2,
                batteryPercent = 20,
                charging = false,
                residencyHint = ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION,
                reason = "test energy shaping"
            )
        }
        val port = object : NativeReflexResidencyAwarePort {
            override val checkpointId = NativeCheckpointId("battery-residency")
            override val weightArtifactSha256 = "a".repeat(64)

            override fun predict(
                input: NativeReflexDecisionInput
            ): Result<NativeReflexDecisionPrediction> = Result.success(
                NativeReflexDecisionPrediction(
                    disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                    capability = DeviceStatusToolContract.capability,
                    confidence = 0.999,
                    uncertainty = 0.001
                )
            )

            override fun releaseTransientResources(): Result<Unit> {
                releaseCalls += 1
                return Result.success(Unit)
            }
        }
        val controller = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            resourcePolicy = resourcePolicy,
            clock = { 1L },
            monotonicNanos = {
                val current = nanos
                nanos += 50_000_000L
                current
            }
        )
        controller.activate(port).getOrThrow()

        controller.decide(request())

        assertEquals(1, releaseCalls)
        assertEquals(1L, controller.health().residencyReleases)
        assertTrue(controller.health().lastWorkCostScore > 50.0)
        assertTrue((controller.health().workCostEwma ?: 0.0) > 0.0)
    }

    @Test
    fun burstThrottleSkipsSecondLearnedPredictionWithoutModelPenalty() {
        var now = 0L
        var predictions = 0
        val resourcePolicy = ReflexRuntimeResourcePolicy {
            ReflexRuntimeResourceDecision(
                mode = ReflexRuntimeResourceMode.PRESSURED,
                allowLearnedInference = true,
                memoryBudgetMb = 512,
                thermalClass = 1,
                batteryPercent = 25,
                charging = false,
                minInterInferenceMs = 1_000L,
                residencyHint = ReflexRuntimeResidencyHint.RELEASE_AFTER_DECISION,
                reason = "battery burst throttle"
            )
        }
        val port = object : NativeReflexDecisionPort {
            override val checkpointId = NativeCheckpointId("battery-throttle")
            override val weightArtifactSha256 = "b".repeat(64)

            override fun predict(
                input: NativeReflexDecisionInput
            ): Result<NativeReflexDecisionPrediction> {
                predictions += 1
                return Result.success(
                    NativeReflexDecisionPrediction(
                        disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                        capability = DeviceStatusToolContract.capability,
                        confidence = 0.999,
                        uncertainty = 0.001
                    )
                )
            }
        }
        val controller = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            resourcePolicy = resourcePolicy,
            clock = { now },
            monotonicNanos = { 0L }
        )
        controller.activate(port).getOrThrow()

        now = 1_000L
        controller.decide(request())
        now = 1_200L
        val second = controller.decide(request())

        assertEquals(1, predictions)
        assertEquals(1L, controller.health().throttleSkips)
        assertEquals(0L, controller.health().predictionFailures)
        assertEquals(ReflexDecisionSource.BOOTSTRAP_DETERMINISTIC, second.source)
        assertEquals(port.checkpointId, controller.active()?.checkpointId)
    }

    private fun policy(
        battery: Int,
        charging: Boolean
    ): ResourceGovernorReflexRuntimeResourcePolicy {
        val governor = object : ResourceGovernor {
            override fun currentBudget(): ResourceBudget = normalBudget
            override fun allows(agentCount: Int): Boolean =
                agentCount <= normalBudget.maxConcurrentAgents
        }
        return ResourceGovernorReflexRuntimeResourcePolicy(
            governor = governor,
            deviceStatusSource = DeviceStatusSource {
                DeviceStatusSnapshot(
                    batteryPercent = battery,
                    charging = charging,
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

    private fun adaptive(latencyMs: Double) = ReflexAdaptiveRuntimePolicy(
        checkpointId = NativeCheckpointId("battery-policy"),
        minFastPathConfidence = 0.99,
        maxFastPathUncertainty = 0.01,
        maxPredictionLatencyMs = 250.0,
        heldoutActionPrecision = 0.995,
        heldoutCalibrationError = 0.01,
        onlineResolvedActionSamples = 0,
        onlineActionSuccessRate = null,
        predictionSamples = 20,
        latencyEwmaMs = latencyMs
    )

    private fun request() = ReflexDecisionRequest(
        userInput = "Pin hiện tại còn bao nhiêu?",
        descriptors = listOf(
            ToolDescriptor(
                id = DeviceStatusToolContract.toolId,
                name = "device-status",
                capability = DeviceStatusToolContract.capability,
                sideEffect = ToolSideEffect.READ_ONLY,
                inputContract = ToolInputContract(
                    description = "status",
                    acceptedValues = setOf("summary", "status"),
                    maxLength = 16
                )
            )
        )
    )
}
