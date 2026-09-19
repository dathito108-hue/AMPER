package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexRealModelTest {
    @Test
    fun realReflexLifecycleTrainsEvaluatesAndActivatesOwnedModel() {
        val runtime = AmperRuntime.reference()
        seed(runtime.reflexExperienceDatasets, "real-reflex")
        val artifacts = InMemoryReflexLinearArtifactStore()
        val lifecycle = ReflexNativeModelLifecycle(
            runtime = runtime,
            artifacts = artifacts,
            clock = { 50_000L }
        )

        val report = lifecycle.maintain().getOrThrow()

        assertEquals(ReflexNativeLifecycleStage.ACTIVE, report.stage)
        val checkpointId = requireNotNull(report.checkpointId)
        val checkpoint = requireNotNull(runtime.nativeModelFoundation.getCheckpoint(checkpointId))
        assertTrue(artifacts.size() > 0)
        assertNotNull(artifacts.load(checkpoint.weightArtifactSha256))
        val evaluation = requireNotNull(
            runtime.nativeTrainingPipeline.getEvaluation(checkpointId)
        )
        assertTrue(evaluation.admission.admitted)
        val metrics = requireNotNull(evaluation.evaluation.reflexDecision)
        assertTrue(metrics.exactDecisionAccuracy >= 0.97)
        assertTrue(metrics.actionPrecision >= 0.99)
        assertTrue(metrics.escalationRecall >= 0.98)
        assertTrue(metrics.capabilityAccuracy >= 0.98)

        val decision = runtime.reflexDecisionCortex.decide(
            ReflexDecisionRequest(
                userInput = "check battery and ram right now",
                descriptors = listOf(deviceDescriptor())
            )
        )
        assertEquals(ReflexDecisionSource.NATIVE_SYSTEM1, decision.source)
        assertEquals(ReflexDecisionDisposition.PROPOSE_ACTION, decision.disposition)
        assertEquals(DeviceStatusToolContract.capability, decision.capability)
        assertTrue(decision.confidence >= decision.fastPathConfidenceThreshold)
    }

    @Test
    fun insufficientEvidenceDoesNotCreateContractCheckpointOrArtifact() {
        val runtime = AmperRuntime.reference()
        val artifacts = InMemoryReflexLinearArtifactStore()
        val lifecycle = ReflexNativeModelLifecycle(runtime, artifacts)

        val report = lifecycle.maintain().getOrThrow()

        assertEquals(ReflexNativeLifecycleStage.INSUFFICIENT_EVIDENCE, report.stage)
        assertEquals(0, artifacts.size())
        assertEquals(
            null,
            runtime.nativeModelFoundation.getContract(ReflexNativeModelLifecycle.CONTRACT_ID)
        )
        assertEquals(null, runtime.reflexDecisionRuntime.active())
    }

    @Test
    fun serializedArtifactRoundTripsToSamePrediction() {
        val capabilities = listOf(DeviceStatusToolContract.capability)
        val biases = FloatArray(ReflexLinearModel.CLASS_SLOTS)
        val weights = FloatArray(ReflexLinearModel.PARAMETER_WEIGHTS)
        val features = ReflexLinearFeatureProjector.project(
            featureHashes = setOf("u:1111111111111111"),
            availableCapabilities = setOf(DeviceStatusToolContract.capability)
        )
        biases[1] = 3.0f
        features.forEach {
            weights[ReflexLinearModel.FEATURE_DIMENSION + it] = 2.0f
        }
        val model = ReflexLinearModel(capabilities, biases, weights)
        val bytes = ReflexLinearModelCodec.encode(model)
        val decoded = ReflexLinearModelCodec.decode(bytes)
        val input = NativeReflexDecisionInput(
            featureHashes = setOf("u:1111111111111111"),
            availableCapabilities = setOf(DeviceStatusToolContract.capability)
        )

        val before = model.predict(input)
        val after = decoded.predict(input)

        assertEquals(before.disposition, after.disposition)
        assertEquals(before.capability, after.capability)
        assertEquals(before.confidence, after.confidence, 0.0000001)
    }

    private fun seed(
        store: ReflexExperienceDatasetStore,
        prefix: String
    ) {
        val descriptor = deviceDescriptor()
        repeat(40) { index ->
            val proposal = ActionProposal(
                requestId = ActionRequestId("$prefix-action-$index"),
                capability = descriptor.capability,
                reason = "read device status",
                input = "summary"
            )
            store.observeExecuted(
                userInput = "check battery and ram right now sample $index",
                descriptors = listOf(descriptor),
                action = ActionOutcome(
                    status = ActionStatus.EXECUTED,
                    proposal = proposal,
                    toolId = descriptor.id,
                    sideEffect = descriptor.sideEffect,
                    output = "battery_percent=50"
                ),
                source = ReflexExperienceSource.SYSTEM2_TEACHER,
                labelConfidence = 0.99,
                observedAtEpochMs = 1_000L + index
            )
        }
        repeat(40) { index ->
            store.observeEscalation(
                conversationId = ConversationId("$prefix-thread-$index"),
                userInput = "explain a difficult concept in detail sample $index",
                descriptors = listOf(descriptor),
                response = InferenceResponse(
                    modelId = ModelId("teacher"),
                    backendId = "teacher-backend",
                    text = "reasoned answer $index"
                ),
                labelConfidence = 0.90,
                observedAtEpochMs = 2_000L + index
            )
        }
    }

    private fun deviceDescriptor(): ToolDescriptor =
        ToolDescriptor(
            id = DeviceStatusToolContract.toolId,
            name = "device status",
            capability = DeviceStatusToolContract.capability,
            sideEffect = ToolSideEffect.READ_ONLY,
            inputContract = ToolInputContract(
                description = "status",
                acceptedValues = setOf("summary", "status"),
                maxLength = 16
            )
        )
}
