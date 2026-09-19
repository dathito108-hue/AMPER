package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LearnedReflexDecisionRuntimeTest {
    private val deviceDescriptor = ToolDescriptor(
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
    fun noActiveCheckpointUsesDeterministicBootstrap() {
        val controller = controller()

        val decision = controller.decide(
            ReflexDecisionRequest(
                userInput = "Pin và RAM hiện tại còn bao nhiêu?",
                descriptors = listOf(deviceDescriptor)
            )
        )

        assertEquals(ReflexDecisionSource.BOOTSTRAP_DETERMINISTIC, decision.source)
        assertEquals(DeviceStatusToolContract.capability, decision.capability)
    }

    @Test
    fun admittedLearnedRouteUsesSafeDeterministicArgumentBinding() {
        val checkpoint = NativeCheckpointId("learned-reflex-ok")
        val port = fakePort(
            checkpoint = checkpoint,
            prediction = NativeReflexDecisionPrediction(
                disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                capability = DeviceStatusToolContract.capability,
                confidence = 0.999,
                uncertainty = 0.001
            )
        )
        val controller = controller()

        val activation = controller.activate(port).getOrThrow()
        val decision = controller.decide(
            ReflexDecisionRequest(
                userInput = "Pin và RAM hiện tại còn bao nhiêu?",
                descriptors = listOf(deviceDescriptor)
            )
        )

        assertEquals(checkpoint, activation.checkpointId)
        assertEquals(ReflexDecisionSource.NATIVE_SYSTEM1, decision.source)
        assertEquals(DeviceStatusToolContract.capability, decision.capability)
        assertEquals("summary", decision.input)
        assertFalse(decision.authorityBearing)
    }

    @Test
    fun learnedCapabilityWithoutIndependentArgumentBindingEscalates() {
        val checkpoint = NativeCheckpointId("learned-reflex-mismatch")
        val controller = controller()
        controller.activate(
            fakePort(
                checkpoint = checkpoint,
                prediction = NativeReflexDecisionPrediction(
                    disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                    capability = AndroidTimerPrepareToolContract.capability,
                    confidence = 0.999,
                    uncertainty = 0.001
                )
            )
        ).getOrThrow()

        val decision = controller.decide(
            ReflexDecisionRequest(
                userInput = "Pin và RAM hiện tại còn bao nhiêu?",
                descriptors = listOf(
                    deviceDescriptor,
                    ToolDescriptor(
                        id = AndroidTimerPrepareToolContract.toolId,
                        name = "timer",
                        capability = AndroidTimerPrepareToolContract.capability,
                        sideEffect = ToolSideEffect.EXTERNAL,
                        inputContract = ToolInputContract(
                            description = "timer",
                            maxLength = AndroidTimerPrepareToolContract.MAX_INPUT_CHARS
                        )
                    )
                )
            )
        )

        assertEquals(ReflexDecisionDisposition.ESCALATE_SYSTEM2, decision.disposition)
        assertEquals(ReflexDecisionSource.NATIVE_SYSTEM1, decision.source)
    }

    @Test
    fun learnedBackendFailureFallsBackAndRollbackRestoresBootstrapOnly() {
        val checkpoint = NativeCheckpointId("learned-reflex-fail")
        val controller = controller()
        val failing = object : NativeReflexDecisionPort {
            override val checkpointId = checkpoint
            override val weightArtifactSha256 = "a".repeat(64)
            override fun predict(
                input: NativeReflexDecisionInput
            ): Result<NativeReflexDecisionPrediction> =
                Result.failure(IllegalStateException("runtime unavailable"))
        }
        controller.activate(failing).getOrThrow()

        val fallback = controller.decide(
            ReflexDecisionRequest(
                userInput = "Pin và RAM hiện tại còn bao nhiêu?",
                descriptors = listOf(deviceDescriptor)
            )
        )
        assertEquals(ReflexDecisionSource.BOOTSTRAP_DETERMINISTIC, fallback.source)
        assertNotNull(controller.active())

        val removed = controller.rollback()
        assertEquals(checkpoint, removed?.checkpointId)
        assertNull(controller.active())
    }

    @Test
    fun failedActivationNeverReplacesCurrentRuntime() {
        val controller = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate {
                Result.failure(IllegalArgumentException("not admitted"))
            },
            clock = { 10L }
        )
        val result = controller.activate(
            fakePort(
                checkpoint = NativeCheckpointId("rejected-reflex"),
                prediction = NativeReflexDecisionPrediction(
                    disposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
                    confidence = 0.99,
                    uncertainty = 0.01
                )
            )
        )

        assertFalse(result.isSuccess)
        assertNull(controller.active())
    }

    private fun controller(): CanonicalReflexDecisionRuntimeController =
        CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate {
                Result.success(Unit)
            },
            clock = { 5L }
        )

    private fun fakePort(
        checkpoint: NativeCheckpointId,
        prediction: NativeReflexDecisionPrediction
    ): NativeReflexDecisionPort =
        object : NativeReflexDecisionPort {
            override val checkpointId = checkpoint
            override val weightArtifactSha256 = "a".repeat(64)
            override fun predict(
                input: NativeReflexDecisionInput
            ): Result<NativeReflexDecisionPrediction> = Result.success(prediction)
        }
}
