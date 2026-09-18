package io.amper.neuroos.core

/**
 * Engine-neutral facade for the llama.cpp Android/JNI implementation.
 * The concrete engine can use official llama.cpp JNI bindings while this adapter
 * preserves AMPER's canonical backend contract.
 */
interface LlamaCppEngine {
    fun health(): BackendHealth
    fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost
    fun supports(source: ModelArtifactSource): Boolean
    fun generate(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        onToken: (String) -> Unit
    ): Result<Unit>
    fun unload(modelId: ModelId): Result<Unit> = Result.success(Unit)
}

interface AttachmentAwareLlamaCppEngine : LlamaCppEngine {
    fun supportedAttachmentKinds(model: InstalledModel): Set<InferenceAttachmentKind>
}

class LlamaCppAdapter(
    private val engine: LlamaCppEngine,
    override val adapterId: String = "llama.cpp"
) : StreamingNativeInferenceAdapter, AttachmentAwareNativeInferenceAdapter {
    override fun health(): BackendHealth = engine.health()
    override fun supportsFormat(format: String): Boolean = format.equals("gguf", ignoreCase = true)
    override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost = engine.estimate(model, request)

    override fun supportedAttachmentKinds(model: InstalledModel): Set<InferenceAttachmentKind> =
        (engine as? AttachmentAwareLlamaCppEngine)
            ?.supportedAttachmentKinds(model)
            .orEmpty()

    override fun generateStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        onChunk: (InferenceChunk) -> Unit
    ): Result<Unit> = runCatching {
        check(health().state != BackendState.UNAVAILABLE) { "llama.cpp engine unavailable: ${health().detail}" }
        require(engine.supports(source)) { "llama.cpp engine cannot access artifact ${source.locator}" }
        var index = 0
        engine.generate(model, source, request) { token ->
            if (token.isNotEmpty()) onChunk(InferenceChunk(token, index++))
        }.getOrThrow()
        onChunk(InferenceChunk("", index, finished = true))
    }

    override fun unload(modelId: ModelId): Result<Unit> = engine.unload(modelId)
}

/**
 * Safe probe for an optional native library. Loading failure never crashes the
 * sovereign runtime; it only marks this backend unavailable.
 */
class OptionalNativeLibraryProbe(
    private val libraryName: String,
    private val loader: (String) -> Unit = System::loadLibrary
) {
    @Volatile private var attempted = false
    @Volatile private var failure: Throwable? = null

    @Synchronized
    fun health(): BackendHealth {
        if (!attempted) {
            attempted = true
            failure = runCatching { loader(libraryName) }.exceptionOrNull()
        }
        val problem = failure
        return if (problem == null) {
            BackendHealth(BackendState.READY, "native library $libraryName loaded")
        } else {
            BackendHealth(BackendState.UNAVAILABLE, "native library $libraryName unavailable: ${problem.message ?: problem::class.java.simpleName}")
        }
    }
}
