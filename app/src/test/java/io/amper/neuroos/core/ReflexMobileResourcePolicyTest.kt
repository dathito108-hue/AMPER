package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexMobileResourcePolicyTest {
    @Test
    fun criticalThermalBlocksLearnedInferenceWithoutDemotingCheckpoint() {
        val controller = controller(
            budget = ResourceBudget(
                maxConcurrentAgents = 1,
                memoryMb = 512,
                thermalClass = 4
            )
        )
        val port = port("resource-critical")
        controller.activate(port).getOrThrow()

        val decision = controller.decide(request())

        assertEquals(ReflexDecisionSource.BOOTSTRAP_DETERMINISTIC, decision.source)
        assertEquals(port.checkpointId, controller.active()?.checkpointId)
        assertEquals(1L, controller.health().resourceSkips)
        assertEquals(0L, controller.health().predictionFailures)
        assertEquals(ReflexRuntimeResourceMode.BLOCKED, controller.health().lastResourceMode)
    }

    @Test
    fun severeMemoryPressureBlocksLearnedInference() {
        val controller = controller(
            budget = ResourceBudget(
                maxConcurrentAgents = 1,
                memoryMb = 128,
                thermalClass = 1
            )
        )
        controller.activate(port("resource-memory")).getOrThrow()

        controller.decide(request())

        assertEquals(1L, controller.health().resourceSkips)
        assertEquals(128, controller.health().lastResourceMemoryMb)
        assertEquals(0L, controller.health().totalPredictions)
    }

    @Test
    fun moderatePressureAllowsFastLearnedReflex() {
        val checkpoint = NativeCheckpointId("resource-moderate-fast")
        val calibration = fakeCalibration(
            checkpoint = checkpoint,
            latencyEwmaMs = 80.0
        )
        val controller = controller(
            budget = ResourceBudget(
                maxConcurrentAgents = 1,
                memoryMb = 300,
                thermalClass = 2
            ),
            calibration = calibration
        )
        controller.activate(port(checkpoint.value)).getOrThrow()

        val decision = controller.decide(request())

        assertEquals(ReflexDecisionSource.NATIVE_SYSTEM1, decision.source)
        assertEquals(ReflexRuntimeResourceMode.PRESSURED, controller.health().lastResourceMode)
        assertEquals(0L, controller.health().resourceSkips)
        assertEquals(1L, controller.health().totalPredictions)
    }

    @Test
    fun moderatePressureSkipsHistoricallySlowLearnedReflexWithoutHealthPenalty() {
        val checkpoint = NativeCheckpointId("resource-moderate-slow")
        val calibration = fakeCalibration(
            checkpoint = checkpoint,
            latencyEwmaMs = 220.0
        )
        val controller = controller(
            budget = ResourceBudget(
                maxConcurrentAgents = 1,
                memoryMb = 300,
                thermalClass = 2
            ),
            calibration = calibration
        )
        controller.activate(port(checkpoint.value)).getOrThrow()

        val decision = controller.decide(request())

        assertEquals(ReflexDecisionSource.BOOTSTRAP_DETERMINISTIC, decision.source)
        assertEquals(1L, controller.health().resourceSkips)
        assertEquals(0L, controller.health().predictionFailures)
        assertEquals(0L, controller.health().slowPredictions)
        assertEquals(checkpoint, controller.active()?.checkpointId)
    }

    private fun controller(
        budget: ResourceBudget,
        calibration: ReflexRuntimeCalibration = StaticReflexRuntimeCalibration
    ): CanonicalReflexDecisionRuntimeController {
        val governor = object : ResourceGovernor {
            override fun currentBudget(): ResourceBudget = budget
            override fun allows(agentCount: Int): Boolean =
                agentCount <= budget.maxConcurrentAgents && budget.thermalClass < 4
        }
        return CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            calibration = calibration,
            resourcePolicy = ResourceGovernorReflexRuntimeResourcePolicy(governor),
            clock = { 1L },
            monotonicNanos = { 0L }
        )
    }

    private fun fakeCalibration(
        checkpoint: NativeCheckpointId,
        latencyEwmaMs: Double
    ): ReflexRuntimeCalibration = object : ReflexRuntimeCalibration {
        override fun policy(checkpointId: NativeCheckpointId) =
            ReflexAdaptiveRuntimePolicy(
                checkpointId = checkpointId,
                minFastPathConfidence = 0.99,
                maxFastPathUncertainty = 0.01,
                maxPredictionLatencyMs = 250.0,
                heldoutActionPrecision = 0.995,
                heldoutCalibrationError = 0.01,
                onlineResolvedActionSamples = 0,
                onlineActionSuccessRate = null,
                predictionSamples = 20,
                latencyEwmaMs = latencyEwmaMs
            )

        override fun observePrediction(checkpointId: NativeCheckpointId, latencyMs: Double) = Unit
        override fun bindAction(
            requestId: ActionRequestId,
            checkpointId: NativeCheckpointId,
            confidence: Double
        ) = Unit
        override fun observeActionOutcome(requestId: ActionRequestId, status: ActionStatus) = Unit
        override fun discardAction(requestId: ActionRequestId) = Unit
        override fun snapshot(checkpointId: NativeCheckpointId) =
            ReflexRuntimeCalibrationSnapshot(checkpointId)
    }

    private fun port(id: String): NativeReflexDecisionPort =
        object : NativeReflexDecisionPort {
            override val checkpointId = NativeCheckpointId(id)
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
        }

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
