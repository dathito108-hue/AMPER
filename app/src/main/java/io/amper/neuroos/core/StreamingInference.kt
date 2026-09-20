package io.amper.neuroos.core

import java.util.concurrent.atomic.AtomicBoolean

class InferenceCancelledException(
    message: String = "inference cancelled by user"
) : RuntimeException(message)

class InferenceCancellationSignal {
    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel(): Boolean = cancelled.compareAndSet(false, true)

    fun throwIfCancelled() {
        if (isCancelled) throw InferenceCancelledException()
    }
}

data class InferenceChunk(
    val text: String,
    val index: Int,
    val finished: Boolean = false
) {
    init {
        require(index >= 0)
    }
}

interface StreamingNativeInferenceAdapter : NativeInferenceAdapter {
    fun generateStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        onChunk: (InferenceChunk) -> Unit
    ): Result<Unit>

    /**
     * Cooperative cancellation wrapper for streaming adapters.
     *
     * Existing adapters remain source-compatible. A backend that propagates callback exceptions
     * stops as soon as the signal is observed. If a native engine cannot abort immediately, the
     * authoritative result is still discarded by Titan once control returns.
     */
    fun generateStreamCancellable(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal,
        onChunk: (InferenceChunk) -> Unit
    ): Result<Unit> = runCatching {
        cancellation.throwIfCancelled()
        generateStream(model, source, request) { chunk ->
            cancellation.throwIfCancelled()
            onChunk(chunk)
        }.getOrThrow()
        cancellation.throwIfCancelled()
    }

    override fun generate(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest
    ): Result<String> = runCatching {
        val out = StringBuilder()
        generateStream(model, source, request) { chunk ->
            if (!chunk.finished) out.append(chunk.text)
        }.getOrThrow()
        out.toString()
    }
}

/**
 * Backend capability for true incremental generation.
 *
 * The final response remains authoritative. Chunks are transient observation only and are never
 * persisted by Titan. Backends that cannot stream continue to implement [InferenceBackend] only.
 */
interface StreamingInferenceBackend : InferenceBackend {
    fun inferStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse>
}

/**
 * Optional stronger contract for backends that accept a cooperative user-cancellation signal.
 *
 * Cancellation is not a backend failure and must not be translated into a synthetic success.
 */
/**
 * Latency guard for a caller that requested streaming but selected a backend that can only return
 * a completed response. Without this bound a mobile CPU backend may legally consume a several-
 * hundred-token budget while exposing no partial text, which looks indistinguishable from a hang.
 *
 * True streaming backends retain the caller's complete output budget.
 */
object TitanBlockingStreamFallbackPolicy {
    const val MAX_OUTPUT_TOKENS: Int = 64

    fun bound(
        backend: InferenceBackend,
        request: InferenceRequest
    ): InferenceRequest =
        if (backend is StreamingInferenceBackend) {
            request
        } else {
            request.copy(
                maxOutputTokens = minOf(
                    request.maxOutputTokens,
                    MAX_OUTPUT_TOKENS
                )
            )
        }
}

interface CancellableStreamingInferenceBackend : StreamingInferenceBackend {
    fun inferStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse>
}

class StreamingAdapterBackend(
    private val adapter: StreamingNativeInferenceAdapter
) : ManagedInferenceBackend, CancellableStreamingInferenceBackend, AttachmentAwareInferenceBackend {
    override val id: String = adapter.adapterId

    override fun supports(model: InstalledModel): Boolean =
        adapter.health().state != BackendState.UNAVAILABLE &&
            adapter.supportsFormat(model.descriptor.format)

    override fun health(): BackendHealth = adapter.health()

    override fun supportedAttachmentKinds(model: InstalledModel): Set<InferenceAttachmentKind> =
        (adapter as? AttachmentAwareNativeInferenceAdapter)
            ?.supportedAttachmentKinds(model)
            .orEmpty()

    override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
        adapter.estimate(model, request)

    override fun infer(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest
    ): Result<InferenceResponse> =
        inferStream(model, source, request) { }

    override fun inferStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> =
        inferStream(
            model = model,
            source = source,
            request = request,
            cancellation = InferenceCancellationSignal(),
            onChunk = onChunk
        )

    override fun inferStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> = runCatching {
        val out = StringBuilder()
        adapter.generateStreamCancellable(model, source, request, cancellation) { chunk ->
            if (!chunk.finished) out.append(chunk.text)
            onChunk(chunk)
        }.getOrThrow()
        cancellation.throwIfCancelled()
        InferenceResponse(
            modelId = model.descriptor.id,
            backendId = id,
            text = out.toString()
        )
    }

    override fun unload(modelId: ModelId): Result<Unit> = adapter.unload(modelId)
}
