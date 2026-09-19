package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexChampionHealthTest {
    private val descriptor = ToolDescriptor(
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

    @Test
    fun rejectedChallengerNeverReplacesChampion() {
        val store = VolatileReflexDecisionRuntimeActivationStore()
        val controller = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            replacementGate = ReflexDecisionRuntimeReplacementGate { _, _ ->
                Result.failure(IllegalArgumentException("challenger regressed"))
            },
            activationStore = store,
            clock = { 10L }
        )
        val champion = port("champion", succeed = true)
        val challenger = port("challenger-rejected", succeed = true)

        controller.activate(champion).getOrThrow()
        val result = controller.activate(challenger)

        assertTrue(result.isFailure)
        assertEquals(champion.checkpointId, controller.active()?.checkpointId)
        assertEquals(champion.checkpointId, store.load()?.checkpointId)
    }

    @Test
    fun unhealthyChallengerAutomaticallyRestoresStandbyChampion() {
        val store = VolatileReflexDecisionRuntimeActivationStore()
        var now = 20L
        val controller = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            replacementGate = ReflexDecisionRuntimeReplacementGate { _, _ -> Result.success(Unit) },
            activationStore = store,
            healthPolicy = ReflexRuntimeHealthPolicy(
                maxConsecutivePredictionFailures = 2,
                maxPredictionLatencyMs = 1_000.0,
                maxConsecutiveSlowPredictions = 2
            ),
            clock = { now++ }
        )
        val champion = port("champion-stable", succeed = true)
        val challenger = port("challenger-failing", succeed = false)

        controller.activate(champion).getOrThrow()
        controller.activate(challenger).getOrThrow()
        repeat(2) {
            controller.decide(
                ReflexDecisionRequest(
                    userInput = "Pin hiện tại bao nhiêu?",
                    descriptors = listOf(descriptor)
                )
            )
        }

        assertEquals(champion.checkpointId, controller.active()?.checkpointId)
        assertEquals(champion.checkpointId, store.load()?.checkpointId)
        assertEquals(1L, controller.health().automaticRollbacks)
        assertEquals(champion.checkpointId, controller.health().checkpointId)
    }

    @Test
    fun unhealthyFirstLearnedModelDisablesDurableIntentAndFallsBack() {
        val store = VolatileReflexDecisionRuntimeActivationStore()
        var now = 30L
        val controller = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            activationStore = store,
            healthPolicy = ReflexRuntimeHealthPolicy(
                maxConsecutivePredictionFailures = 1,
                maxPredictionLatencyMs = 1_000.0,
                maxConsecutiveSlowPredictions = 2
            ),
            clock = { now++ }
        )
        controller.activate(port("only-failing", succeed = false)).getOrThrow()

        val decision = controller.decide(
            ReflexDecisionRequest(
                userInput = "Pin hiện tại bao nhiêu?",
                descriptors = listOf(descriptor)
            )
        )

        assertNull(controller.active())
        assertEquals(ReflexRuntimeActivationIntentStatus.DISABLED, store.load()?.status)
        assertEquals(ReflexDecisionSource.BOOTSTRAP_DETERMINISTIC, decision.source)
        assertEquals(1L, controller.health().automaticRollbacks)
    }

    @Test
    fun repeatedSlowPredictionsTriggerAutomaticRollback() {
        val store = VolatileReflexDecisionRuntimeActivationStore()
        var nano = 0L
        val controller = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            activationStore = store,
            healthPolicy = ReflexRuntimeHealthPolicy(
                maxConsecutivePredictionFailures = 3,
                maxPredictionLatencyMs = 100.0,
                maxConsecutiveSlowPredictions = 2
            ),
            clock = { 40L },
            monotonicNanos = {
                val current = nano
                nano += 150_000_000L
                current
            }
        )
        controller.activate(port("slow-model", succeed = true)).getOrThrow()

        repeat(2) {
            controller.decide(
                ReflexDecisionRequest(
                    userInput = "Pin hiện tại bao nhiêu?",
                    descriptors = listOf(descriptor)
                )
            )
        }

        assertNull(controller.active())
        assertEquals(ReflexRuntimeActivationIntentStatus.DISABLED, store.load()?.status)
        assertEquals(1L, controller.health().automaticRollbacks)
        assertFalse(controller.health().checkpointId != null)
    }

    private fun port(
        id: String,
        succeed: Boolean
    ): NativeReflexDecisionPort = object : NativeReflexDecisionPort {
        override val checkpointId = NativeCheckpointId(id)
        override val weightArtifactSha256 = "a".repeat(64)

        override fun predict(
            input: NativeReflexDecisionInput
        ): Result<NativeReflexDecisionPrediction> =
            if (!succeed) {
                Result.failure(IllegalStateException("runtime failure"))
            } else {
                Result.success(
                    NativeReflexDecisionPrediction(
                        disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                        capability = DeviceStatusToolContract.capability,
                        confidence = 0.999,
                        uncertainty = 0.001
                    )
                )
            }
    }
}
