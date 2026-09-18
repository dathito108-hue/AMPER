package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TitanRuntimeFeedbackRoutingTest {
    private val reasoning = CapabilityId("reasoning")
    private val code = CapabilityId("code-generation")

    @Test
    fun executionFailureAffectsOnlyFutureTurnsAndDownranksFailedRoute() {
        val backend = FeedbackBackend(failFirstModel = "a-primary")
        val titan = runtime("a-primary", "b-fallback", backend = backend)

        val first = titan.infer(InferenceRequest("first turn"))
        assertTrue(first.isFailure)
        assertEquals(listOf("a-primary"), backend.calls)

        val second = titan.infer(InferenceRequest("second turn")).getOrThrow()
        assertEquals("b-fallback", second.modelId.value)
        assertEquals(listOf("a-primary", "b-fallback"), backend.calls)
    }

    @Test
    fun repeatedSlowValidatedSuccessDownranksRouteAfterConfidenceBuilds() {
        val backend = FeedbackBackend(
            telemetryByModel = mapOf(
                "a-primary" to Telemetry(tokensPerSecond = 1.0, generationTimeMs = 5_000L),
                "b-fallback" to Telemetry(tokensPerSecond = 12.0, generationTimeMs = 500L)
            )
        )
        val titan = runtime("a-primary", "b-fallback", backend = backend)

        val first = titan.infer(InferenceRequest("first turn")).getOrThrow()
        val second = titan.infer(InferenceRequest("second turn")).getOrThrow()
        val third = titan.infer(InferenceRequest("third turn")).getOrThrow()

        assertEquals("a-primary", first.modelId.value)
        assertEquals("a-primary", second.modelId.value)
        assertEquals("b-fallback", third.modelId.value)
        assertEquals(listOf("a-primary", "a-primary", "b-fallback"), backend.calls)
    }

    @Test
    fun singleSlowSampleDoesNotImmediatelyPenalizeRoute() {
        val feedback = TitanRuntimeFeedback()
        val key = feedbackKey("brain")

        feedback.recordSuccess(key, response("brain", "backend", tokensPerSecond = 1.0))

        val snapshot = feedback.snapshot(key)
        assertEquals(0, snapshot.performancePenalty)
        assertEquals(1, snapshot.throughputSamples)
        assertEquals(1, snapshot.consecutiveSlowSamples)
        assertEquals(1.0, snapshot.throughputEwma!!, 0.0001)
    }

    @Test
    fun repeatedSlowSamplesCreatePerformancePenalty() {
        val feedback = TitanRuntimeFeedback()
        val key = feedbackKey("brain")
        val slow = response("brain", "backend", tokensPerSecond = 1.0)

        feedback.recordSuccess(key, slow)
        feedback.recordSuccess(key, slow)

        val snapshot = feedback.snapshot(key)
        assertEquals(1, snapshot.performancePenalty)
        assertEquals(2, snapshot.throughputSamples)
        assertEquals(2, snapshot.consecutiveSlowSamples)
    }

    @Test
    fun longGenerationWithHealthyThroughputDoesNotCreatePenalty() {
        val feedback = TitanRuntimeFeedback()
        val key = feedbackKey("brain")

        feedback.recordSuccess(
            key,
            InferenceResponse(
                modelId = ModelId("brain"),
                backendId = "backend",
                text = "ok",
                outputTokens = 600,
                tokensPerSecond = 10.0,
                generationTimeMs = 60_000L
            )
        )

        assertEquals(0, feedback.snapshot(key).performancePenalty)
    }

    @Test
    fun missingExplicitThroughputIsDerivedAndSmoothedBeforePenalty() {
        val feedback = TitanRuntimeFeedback()
        val key = feedbackKey("brain")
        val response = InferenceResponse(
            modelId = ModelId("brain"),
            backendId = "backend",
            text = "ok",
            outputTokens = 30,
            tokensPerSecond = null,
            generationTimeMs = 30_000L
        )

        feedback.recordSuccess(key, response)
        val first = feedback.snapshot(key)
        assertEquals(0, first.performancePenalty)
        assertEquals(1.0, first.throughputEwma!!, 0.0001)

        feedback.recordSuccess(key, response)
        assertEquals(1, feedback.snapshot(key).performancePenalty)
    }

    @Test
    fun generationDurationWithoutWorkloadSizeDoesNotCreatePenalty() {
        val feedback = TitanRuntimeFeedback()
        val key = feedbackKey("brain")

        feedback.recordSuccess(
            key,
            InferenceResponse(
                modelId = ModelId("brain"),
                backendId = "backend",
                text = "ok",
                outputTokens = null,
                tokensPerSecond = null,
                generationTimeMs = 120_000L
            )
        )

        val snapshot = feedback.snapshot(key)
        assertEquals(0, snapshot.performancePenalty)
        assertEquals(0, snapshot.throughputSamples)
    }

    @Test
    fun invalidExplicitThroughputFallsBackToDerivedThroughput() {
        val feedback = TitanRuntimeFeedback()
        val key = feedbackKey("brain")
        val derivedSlow = InferenceResponse(
            modelId = ModelId("brain"),
            backendId = "backend",
            text = "ok",
            outputTokens = 20,
            tokensPerSecond = Double.NaN,
            generationTimeMs = 20_000L
        )

        feedback.recordSuccess(key, derivedSlow)
        assertEquals(0, feedback.snapshot(key).performancePenalty)
        feedback.recordSuccess(key, derivedSlow)
        assertEquals(1, feedback.snapshot(key).performancePenalty)
    }

    @Test
    fun healthySampleBreaksSlowStreakAndRecoversPenaltyGradually() {
        val feedback = TitanRuntimeFeedback()
        val key = feedbackKey("brain")
        val slow = response("brain", "backend", tokensPerSecond = 1.0)
        val fast = response("brain", "backend", tokensPerSecond = 20.0)

        feedback.recordFailure(key)
        feedback.recordSuccess(key, slow)
        feedback.recordSuccess(key, slow)
        val afterSlow = feedback.snapshot(key)
        assertEquals(0, afterSlow.consecutiveFailures)
        assertEquals(1, afterSlow.performancePenalty)

        feedback.recordSuccess(key, fast)
        val recovered = feedback.snapshot(key)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(0, recovered.performancePenalty)
        assertEquals(0, recovered.deferredPlanningPasses)
        assertEquals(0, recovered.consecutiveSlowSamples)
        assertTrue(recovered.throughputEwma!! > 2.0)
    }

    @Test
    fun ewmaDampensOneNoisySlowSampleAfterHealthyHistory() {
        val feedback = TitanRuntimeFeedback(throughputEwmaAlpha = 0.25)
        val key = feedbackKey("brain")
        val fast = response("brain", "backend", tokensPerSecond = 12.0)
        val noisy = response("brain", "backend", tokensPerSecond = 0.5)

        feedback.recordSuccess(key, fast)
        feedback.recordSuccess(key, fast)
        feedback.recordSuccess(key, noisy)

        val snapshot = feedback.snapshot(key)
        assertEquals(0, snapshot.performancePenalty)
        assertEquals(0, snapshot.consecutiveSlowSamples)
        assertTrue(snapshot.throughputEwma!! > 2.0)
    }

    @Test
    fun repeatedFailuresCreateDeterministicTemporaryDeferralThenProbation() {
        val feedback = TitanRuntimeFeedback(
            quarantineAfterFailures = 2,
            quarantinePlanningPasses = 2
        )
        val key = feedbackKey("brain")

        feedback.recordFailure(key)
        assertFalse(feedback.consumeDeferral(key))
        feedback.recordFailure(key)

        assertTrue(feedback.consumeDeferral(key))
        assertTrue(feedback.consumeDeferral(key))
        assertFalse(feedback.consumeDeferral(key))
        assertEquals(2, feedback.snapshot(key).consecutiveFailures)
    }

    @Test
    fun feedbackMemoryIsBoundedAndEvictsStaleRoutes() {
        val feedback = TitanRuntimeFeedback(maxEntries = 2)
        val first = feedbackKey("a")
        val second = feedbackKey("b")
        val third = feedbackKey("c")

        feedback.recordFailure(first)
        feedback.recordFailure(second)
        feedback.recordFailure(third)

        assertEquals(2, feedback.size())
        assertEquals(0, feedback.snapshot(first).consecutiveFailures)
        assertEquals(1, feedback.snapshot(second).consecutiveFailures)
        assertEquals(1, feedback.snapshot(third).consecutiveFailures)
    }

    @Test
    fun replacingArtifactUnderSameModelAndBackendStartsWithCleanFeedback() {
        val feedback = TitanRuntimeFeedback()
        val oldArtifact = feedbackKey(
            id = "brain",
            locator = "memory://brain-old.gguf",
            sha256 = "a".repeat(64)
        )
        val replacement = feedbackKey(
            id = "brain",
            locator = "memory://brain-new.gguf",
            sha256 = "b".repeat(64)
        )

        feedback.recordFailure(oldArtifact)
        feedback.recordFailure(oldArtifact)

        assertTrue(feedback.snapshot(oldArtifact).consecutiveFailures > 0)
        assertEquals(0, feedback.snapshot(replacement).consecutiveFailures)
        assertFalse(feedback.consumeDeferral(replacement))
    }

    @Test
    fun failureInCodeProfileDoesNotPenalizeReasoningProfileForSameArtifactAndBackend() {
        val feedback = TitanRuntimeFeedback()
        val codeProfile = feedbackKey(
            id = "brain",
            selectedCapabilities = setOf(reasoning, code)
        )
        val reasoningProfile = feedbackKey(
            id = "brain",
            selectedCapabilities = setOf(reasoning)
        )

        feedback.recordFailure(codeProfile)
        feedback.recordFailure(codeProfile)

        assertTrue(feedback.snapshot(codeProfile).consecutiveFailures > 0)
        assertEquals(0, feedback.snapshot(reasoningProfile).consecutiveFailures)
        assertFalse(feedback.consumeDeferral(reasoningProfile))
    }

    @Test
    fun capabilitySetOrderingDoesNotChangeFeedbackIdentity() {
        val first = feedbackKey(
            id = "brain",
            selectedCapabilities = linkedSetOf(reasoning, code)
        )
        val second = feedbackKey(
            id = "brain",
            selectedCapabilities = linkedSetOf(code, reasoning)
        )

        assertEquals(first, second)
    }

    @Test
    fun sameArtifactBackendAndCapabilityProfileProducesSameFeedbackIdentity() {
        val first = feedbackKey("brain")
        val second = feedbackKey("brain")
        assertEquals(first, second)
        assertEquals(ModelId("brain"), first.modelId)
        assertEquals(setOf(reasoning), first.selectedCapabilities)
    }

    private fun runtime(
        vararg ids: String,
        backend: InferenceBackend
    ): TitanCortexRuntime {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        ids.forEach { id ->
            val installed = installed(id)
            models.register(installed.descriptor)
            catalog.put(installed)
        }
        val allowed = ids.toSet()
        val resolver = object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource? =
                if (model.descriptor.id.value in allowed) StaticSource(model.locator) else null
        }
        return TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = resolver,
            backends = InferenceBackendRegistry().apply { register(backend) }
        )
    }

    private fun installed(id: String): InstalledModel {
        val descriptor = ModelDescriptor(
            id = ModelId(id),
            format = "gguf",
            capabilities = setOf(reasoning),
            local = true
        )
        return InstalledModel(
            descriptor = descriptor,
            displayName = "$id.gguf",
            locator = "memory://$id.gguf",
            lengthBytes = 128L,
            sha256 = "a".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL,
            installedAtEpochMs = 1L
        )
    }

    private fun feedbackKey(
        id: String,
        backendId: String = "backend",
        locator: String = "memory://$id.gguf",
        sha256: String = "a".repeat(64),
        selectedCapabilities: Set<CapabilityId> = setOf(reasoning)
    ): TitanRouteFeedbackKey = TitanRouteFeedbackKey(
        runtimeIdentity = ModelRuntimeIdentity(
            modelId = ModelId(id),
            locator = locator,
            sha256 = sha256,
            lengthBytes = 128L,
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL
        ),
        backendId = backendId,
        selectedCapabilities = selectedCapabilities
    )

    private fun response(
        modelId: String,
        backendId: String,
        tokensPerSecond: Double
    ) = InferenceResponse(
        modelId = ModelId(modelId),
        backendId = backendId,
        text = "ok",
        tokensPerSecond = tokensPerSecond,
        generationTimeMs = 500L
    )

    private data class Telemetry(
        val tokensPerSecond: Double,
        val generationTimeMs: Long
    )

    private class FeedbackBackend(
        private val failFirstModel: String? = null,
        private val telemetryByModel: Map<String, Telemetry> = emptyMap()
    ) : InferenceBackend {
        override val id: String = "feedback-backend"
        val calls = mutableListOf<String>()
        private val failures = mutableSetOf<String>()

        override fun supports(model: InstalledModel): Boolean = true

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            val modelId = model.descriptor.id.value
            calls += modelId
            if (modelId == failFirstModel && failures.add(modelId)) {
                return Result.failure(IllegalStateException("synthetic failure:$modelId"))
            }
            val telemetry = telemetryByModel[modelId]
            return Result.success(
                InferenceResponse(
                    modelId = model.descriptor.id,
                    backendId = id,
                    text = "processed:$modelId",
                    tokensPerSecond = telemetry?.tokensPerSecond,
                    generationTimeMs = telemetry?.generationTimeMs
                )
            )
        }
    }

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
