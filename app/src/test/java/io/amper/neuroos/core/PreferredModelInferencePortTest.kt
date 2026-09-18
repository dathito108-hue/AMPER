package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferredModelInferencePortTest {
    private class RecordingPreparableInference :
        CancellablePreparableCognitiveInferencePort {
        val preparedRequests = mutableListOf<InferenceRequest>()
        var seenCancellation: InferenceCancellationSignal? = null

        override fun infer(request: InferenceRequest): Result<InferenceResponse> =
            Result.success(
                InferenceResponse(
                    modelId = request.effectivePreferredModelId() ?: ModelId("auto-model"),
                    backendId = "recording",
                    text = "ok"
                )
            )

        override fun prepare(
            request: InferenceRequest,
            cancellation: InferenceCancellationSignal
        ): Result<InferencePreparation> = runCatching {
            cancellation.throwIfCancelled()
            preparedRequests += request
            seenCancellation = cancellation
            InferencePreparation(
                modelId = request.effectivePreferredModelId() ?: ModelId("auto-model"),
                backendId = "recording-prepare",
                selectedCapabilities = request.requiredCapabilities,
                sessionReused = false
            )
        }
    }

    private class RecordingInference : CognitiveInferencePort {
        val requests = mutableListOf<InferenceRequest>()

        override fun infer(request: InferenceRequest): Result<InferenceResponse> {
            requests += request
            return Result.success(
                InferenceResponse(
                    modelId = request.effectivePreferredModelId() ?: ModelId("auto-model"),
                    backendId = "recording",
                    text = "ok"
                )
            )
        }
    }

    @Test
    fun globalPreferenceOutranksContinuityWithoutOverwritingContinuityLane() {
        val recording = RecordingInference()
        val port = PreferredModelInferencePort(recording, ModelId("user-choice"))
        val continuity = ModelId("previous-turn-model")

        port.infer(
            InferenceRequest(
                prompt = "continue",
                preferredModelId = continuity
            )
        ).getOrThrow()

        val captured = recording.requests.single()
        assertEquals(continuity, captured.preferredModelId)
        assertEquals(ModelId("user-choice"), captured.userPreferredModelId)
        assertEquals(ModelId("user-choice"), captured.effectivePreferredModelId())
    }

    @Test
    fun requestScopedPreferenceOutranksGlobalPreference() {
        val recording = RecordingInference()
        val port = PreferredModelInferencePort(recording, ModelId("global-choice"))
        val conversationChoice = ModelId("conversation-choice")
        val continuity = ModelId("previous-turn-model")

        port.infer(
            InferenceRequest(
                prompt = "continue",
                preferredModelId = continuity,
                userPreferredModelId = conversationChoice
            )
        ).getOrThrow()

        val captured = recording.requests.single()
        assertEquals(continuity, captured.preferredModelId)
        assertEquals(conversationChoice, captured.userPreferredModelId)
        assertEquals(conversationChoice, captured.effectivePreferredModelId())
    }

    @Test
    fun automaticModePreservesExistingContinuityHint() {
        val recording = RecordingInference()
        val port = PreferredModelInferencePort(recording)
        val continuity = ModelId("previous-turn-model")

        port.infer(
            InferenceRequest(
                prompt = "continue",
                preferredModelId = continuity
            )
        ).getOrThrow()

        val captured = recording.requests.single()
        assertEquals(continuity, captured.preferredModelId)
        assertNull(captured.userPreferredModelId)
        assertEquals(continuity, captured.effectivePreferredModelId())
        assertNull(port.preferredModelId())
    }

    @Test
    fun clearingGlobalPreferenceRestoresAutomaticRoutingHints() {
        val recording = RecordingInference()
        val port = PreferredModelInferencePort(recording, ModelId("manual"))
        port.prefer(null)

        port.infer(
            InferenceRequest(
                prompt = "continue",
                preferredModelId = ModelId("continuity")
            )
        ).getOrThrow()

        val captured = recording.requests.single()
        assertNull(port.preferredModelId())
        assertEquals(ModelId("continuity"), captured.preferredModelId)
        assertNull(captured.userPreferredModelId)
    }

    @Test
    fun preparationUsesSameGlobalPreferenceAndCancellationSignal() {
        val recording = RecordingPreparableInference()
        val preferred = ModelId("warm-choice")
        val port = PreferredModelInferencePort(recording, preferred)
        val cancellation = InferenceCancellationSignal()

        val prepared = port.prepare(
            InferenceRequest(
                prompt = "warm next assistant turn",
                requiredCapabilities = setOf(TitanCapabilities.REASONING)
            ),
            cancellation
        ).getOrThrow()

        val captured = recording.preparedRequests.single()
        assertEquals(preferred, captured.userPreferredModelId)
        assertEquals(preferred, prepared.modelId)
        assertEquals(cancellation, recording.seenCancellation)
    }

    @Test
    fun requestScopedPreparationPreferenceStillOutranksGlobalPreference() {
        val recording = RecordingPreparableInference()
        val port = PreferredModelInferencePort(recording, ModelId("global"))
        val scoped = ModelId("scoped")

        port.prepare(
            InferenceRequest(
                prompt = "warm scoped model",
                userPreferredModelId = scoped
            ),
            InferenceCancellationSignal()
        ).getOrThrow()

        assertEquals(scoped, recording.preparedRequests.single().userPreferredModelId)
    }

    @Test
    fun sharedPortAppliesSameGlobalFallbackAcrossAssistantAndPlannerStyleRequests() {
        val recording = RecordingInference()
        val preferred = ModelId("user-selected-gguf")
        val port = PreferredModelInferencePort(recording, preferred)

        port.infer(
            InferenceRequest(
                prompt = "assistant turn",
                requiredCapabilities = setOf(TitanCapabilities.REASONING)
            )
        ).getOrThrow()
        port.infer(
            InferenceRequest(
                prompt = "planning turn",
                requiredCapabilities = setOf(TitanCapabilities.REASONING),
                preferredCapabilityProfiles = listOf(
                    setOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING)
                )
            )
        ).getOrThrow()

        assertEquals(
            listOf(preferred, preferred),
            recording.requests.map { it.userPreferredModelId }
        )
        assertEquals(listOf(null, null), recording.requests.map { it.preferredModelId })
    }
}
