package io.amper.neuroos.core

/**
 * Global user-selected soft model preference layered beneath any request-scoped user preference.
 *
 * A conversation/request-specific [InferenceRequest.userPreferredModelId] is preserved exactly.
 * The global selection only fills that lane when it is empty, while [InferenceRequest.preferredModelId]
 * remains the lower-priority turn-continuity hint. Titan routing remains authoritative: missing,
 * capability-incompatible, unhealthy, penalized, exploration-selected, or resource-gated preferred
 * models can still be bypassed by the normal route planner.
 *
 * The preference is deliberately shared by assistant and planner callers when they share this port,
 * so one global fallback applies consistently without duplicating routing policy in those layers.
 */
class PreferredModelInferencePort(
    private val delegate: CognitiveInferencePort,
    initialPreferredModelId: ModelId? = null
) : CancellableStreamingCognitiveInferencePort, CancellablePreparableCognitiveInferencePort {
    @Volatile
    private var selectedModelId: ModelId? = initialPreferredModelId

    fun preferredModelId(): ModelId? = selectedModelId

    fun prefer(modelId: ModelId?) {
        selectedModelId = modelId
    }

    override fun infer(request: InferenceRequest): Result<InferenceResponse> =
        delegate.infer(withGlobalPreference(request))

    override fun prepare(
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal
    ): Result<InferencePreparation> {
        cancellation.throwIfCancelled()
        val preparable = delegate as? CancellablePreparableCognitiveInferencePort
        if (preparable != null) {
            return preparable.prepare(withGlobalPreference(request), cancellation)
        }

        val legacy = delegate as? PreparableCognitiveInferencePort
            ?: return Result.failure(
                IllegalStateException("inference delegate does not support preparation")
            )
        val result = legacy.prepare(withGlobalPreference(request))
        cancellation.throwIfCancelled()
        return result
    }

    override fun inferStream(
        request: InferenceRequest,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> =
        inferStream(
            request = request,
            cancellation = InferenceCancellationSignal(),
            onChunk = onChunk
        )

    override fun inferStream(
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> {
        val effective = withGlobalPreference(request)
        cancellation.throwIfCancelled()

        val cancellable = delegate as? CancellableStreamingCognitiveInferencePort
        if (cancellable != null) {
            return cancellable.inferStream(effective, cancellation, onChunk)
        }

        val streaming = delegate as? StreamingCognitiveInferencePort
        if (streaming != null) {
            val result = streaming.inferStream(effective, onChunk)
            cancellation.throwIfCancelled()
            return result
        }

        val result = delegate.infer(effective)
        cancellation.throwIfCancelled()
        result.getOrNull()?.let { response ->
            if (response.text.isNotEmpty()) {
                runCatching { onChunk(InferenceChunk(response.text, index = 0)) }
            }
            runCatching {
                onChunk(
                    InferenceChunk(
                        text = "",
                        index = if (response.text.isEmpty()) 0 else 1,
                        finished = true
                    )
                )
            }
        }
        cancellation.throwIfCancelled()
        return result
    }

    private fun withGlobalPreference(request: InferenceRequest): InferenceRequest {
        val global = selectedModelId
        return if (request.userPreferredModelId != null || global == null) {
            request
        } else {
            request.copy(userPreferredModelId = global)
        }
    }
}
