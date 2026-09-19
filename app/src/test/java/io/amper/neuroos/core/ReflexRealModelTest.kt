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
            artifacts = artifacts
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
    fun freshEvidenceTrainsParentedChallengerAndReplacesChampion() {
        val runtime = AmperRuntime.reference()
        seed(runtime.reflexExperienceDatasets, "continual-base")
        val artifacts = InMemoryReflexLinearArtifactStore()
        val lifecycle = ReflexNativeModelLifecycle(runtime, artifacts)

        val first = lifecycle.maintain().getOrThrow()
        assertEquals(ReflexNativeLifecycleStage.ACTIVE, first.stage)
        val championId = requireNotNull(first.checkpointId)
        val championTrainingIds = requireNotNull(
            runtime.nativeModelFoundation.getCheckpoint(championId)
        ).datasetShardIds.flatMap { shardId ->
            requireNotNull(runtime.reflexExperienceDatasets.getShard(shardId)).exampleIds
        }.toSet()

        seedFreshWebSearch(runtime.reflexExperienceDatasets, "continual-fresh")
        val replacement = lifecycle.maintain().getOrThrow()

        assertEquals(ReflexNativeLifecycleStage.REPLACED, replacement.stage)
        val challengerId = requireNotNull(replacement.checkpointId)
        assertTrue(challengerId != championId)
        assertEquals(challengerId, runtime.reflexDecisionRuntime.active()?.checkpointId)
        val challenger = requireNotNull(
            runtime.nativeModelFoundation.getCheckpoint(challengerId)
        )
        assertEquals(championId, challenger.parentCheckpointId)
        val challengerTrainingIds = challenger.datasetShardIds.flatMap { shardId ->
            requireNotNull(runtime.reflexExperienceDatasets.getShard(shardId)).exampleIds
        }.toSet()
        assertTrue(
            challengerTrainingIds.intersect(championTrainingIds).isNotEmpty()
        )

        val preservedDecision = runtime.reflexDecisionCortex.decide(
            ReflexDecisionRequest(
                userInput = "check battery and ram right now",
                descriptors = listOf(deviceDescriptor())
            )
        )
        assertEquals(ReflexDecisionSource.NATIVE_SYSTEM1, preservedDecision.source)
        assertEquals(DeviceStatusToolContract.capability, preservedDecision.capability)

        val settled = lifecycle.maintain().getOrThrow()
        assertEquals(
            ReflexNativeLifecycleStage.WAITING_FOR_FRESH_EVIDENCE,
            settled.stage
        )
        assertEquals(0, settled.actionExamples)
        assertEquals(0, settled.escalationExamples)
    }

    @Test
    fun resourceDeferralKeepsChampionAndLaterResumesSamePendingEvidence() {
        val runtime = AmperRuntime.reference()
        seed(runtime.reflexExperienceDatasets, "resource-resume-base")
        val artifacts = InMemoryReflexLinearArtifactStore()
        var allowTraining = true
        val policy = ReflexLearningResourcePolicy {
            if (allowTraining) {
                ReflexLearningResourceDecision(
                    mode = ReflexLearningResourceMode.READY,
                    allowTraining = true,
                    maxFreshExamples = 96,
                    maxReplayExamples = 96,
                    memoryBudgetMb = 1024,
                    thermalClass = 1,
                    reason = "test resources ready"
                )
            } else {
                ReflexLearningResourceDecision(
                    mode = ReflexLearningResourceMode.DEFERRED,
                    allowTraining = false,
                    maxFreshExamples = 0,
                    maxReplayExamples = 0,
                    memoryBudgetMb = 128,
                    thermalClass = 3,
                    reason = "test resources deferred"
                )
            }
        }
        val lifecycle = ReflexNativeModelLifecycle(
            runtime = runtime,
            artifacts = artifacts,
            learningResourcePolicy = policy
        )

        val initial = lifecycle.maintain().getOrThrow()
        assertEquals(ReflexNativeLifecycleStage.ACTIVE, initial.stage)
        val championId = requireNotNull(initial.checkpointId)

        seedFreshWebSearch(runtime.reflexExperienceDatasets, "resource-resume-fresh")
        allowTraining = false
        val deferred = lifecycle.maintain().getOrThrow()

        assertEquals(ReflexNativeLifecycleStage.RESOURCE_DEFERRED, deferred.stage)
        assertEquals(championId, deferred.checkpointId)
        assertEquals(championId, runtime.reflexDecisionRuntime.active()?.checkpointId)

        allowTraining = true
        val resumed = lifecycle.maintain().getOrThrow()

        assertEquals(ReflexNativeLifecycleStage.REPLACED, resumed.stage)
        assertTrue(requireNotNull(resumed.checkpointId) != championId)
        assertEquals(resumed.checkpointId, runtime.reflexDecisionRuntime.active()?.checkpointId)
    }

    @Test
    fun stableFreshEvidenceIsBatchedInsteadOfRetrainingEverySmallWindow() {
        val runtime = AmperRuntime.reference()
        seed(runtime.reflexExperienceDatasets, "batch-base")
        val artifacts = InMemoryReflexLinearArtifactStore()
        val lifecycle = ReflexNativeModelLifecycle(runtime, artifacts)

        val first = lifecycle.maintain().getOrThrow()
        val championId = requireNotNull(first.checkpointId)
        seedFreshDeviceStatus(runtime.reflexExperienceDatasets, "batch-fresh")

        val report = lifecycle.maintain().getOrThrow()

        assertEquals(ReflexNativeLifecycleStage.WAITING_FOR_FRESH_EVIDENCE, report.stage)
        assertEquals(championId, report.checkpointId)
        assertEquals(championId, runtime.reflexDecisionRuntime.active()?.checkpointId)
        assertTrue(report.detail.contains("batched retrain floor"))
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

    private fun seedFreshDeviceStatus(
        store: ReflexExperienceDatasetStore,
        prefix: String
    ) {
        val descriptor = deviceDescriptor()
        repeat(24) { index ->
            val proposal = ActionProposal(
                requestId = ActionRequestId("$prefix-action-$index"),
                capability = descriptor.capability,
                reason = "read device status",
                input = "summary"
            )
            store.observeExecuted(
                userInput = "check battery and ram right now fresh sample $index",
                descriptors = listOf(descriptor),
                action = ActionOutcome(
                    status = ActionStatus.EXECUTED,
                    proposal = proposal,
                    toolId = descriptor.id,
                    sideEffect = descriptor.sideEffect,
                    output = "battery_percent=55"
                ),
                source = ReflexExperienceSource.SYSTEM2_TEACHER,
                labelConfidence = 0.99,
                observedAtEpochMs = 5_000L + index
            )
        }
        repeat(24) { index ->
            store.observeEscalation(
                conversationId = ConversationId("$prefix-thread-$index"),
                userInput = "explain a difficult concept in detail fresh sample $index",
                descriptors = listOf(descriptor),
                response = InferenceResponse(
                    modelId = ModelId("teacher"),
                    backendId = "teacher-backend",
                    text = "reasoned fresh answer $index"
                ),
                labelConfidence = 0.90,
                observedAtEpochMs = 6_000L + index
            )
        }
    }

    private fun seedFreshWebSearch(
        store: ReflexExperienceDatasetStore,
        prefix: String
    ) {
        val descriptor = webSearchDescriptor()
        repeat(24) { index ->
            val proposal = ActionProposal(
                requestId = ActionRequestId("$prefix-action-$index"),
                capability = descriptor.capability,
                reason = "search the web",
                input = "android battery optimization sample $index"
            )
            store.observeExecuted(
                userInput = "search web for android battery optimization sample $index",
                descriptors = listOf(descriptor),
                action = ActionOutcome(
                    status = ActionStatus.EXECUTED,
                    proposal = proposal,
                    toolId = descriptor.id,
                    sideEffect = descriptor.sideEffect,
                    output = "web_search_requested"
                ),
                source = ReflexExperienceSource.SYSTEM2_TEACHER,
                labelConfidence = 0.99,
                observedAtEpochMs = 3_000L + index
            )
        }
        repeat(24) { index ->
            store.observeEscalation(
                conversationId = ConversationId("$prefix-thread-$index"),
                userInput = "explain quantum field theory carefully sample $index",
                descriptors = listOf(descriptor),
                response = InferenceResponse(
                    modelId = ModelId("teacher"),
                    backendId = "teacher-backend",
                    text = "long reasoned answer $index"
                ),
                labelConfidence = 0.90,
                observedAtEpochMs = 4_000L + index
            )
        }
    }

    private fun webSearchDescriptor(): ToolDescriptor =
        ToolDescriptor(
            id = AndroidWebSearchToolContract.toolId,
            name = "web search",
            capability = AndroidWebSearchToolContract.capability,
            sideEffect = ToolSideEffect.EXTERNAL,
            inputContract = ToolInputContract(
                description = "web query",
                maxLength = AndroidWebSearchToolContract.MAX_QUERY_CHARS
            )
        )

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
