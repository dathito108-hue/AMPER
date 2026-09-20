package io.amper.neuroos.core

import kotlin.math.max

/**
 * Generic identity surface for optional native accelerators.
 *
 * The value is descriptive and is never an authority grant. Backends fold it into immutable
 * warm-session identity so CPU/Vulkan transitions cannot reuse native state created by a different
 * execution tier.
 */
interface NativeAccelerationAwareEngine {
    fun accelerationIdentity(): String
}

object NativeLlamaExecutionGroup {
    const val ID: String = "llama.cpp-native-runtime"
    const val MAX_CONCURRENT_EXECUTIONS: Int = 1
}

data class LlamaNativeGeneration(
    val text: String,
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    val tokensPerSecond: Double? = null,
    val promptEvalTimeMs: Long? = null,
    val generationTimeMs: Long? = null
)

/**
 * Text-only llama.cpp boundary supplied by the optional native source set.
 *
 * This deliberately accepts no opaque multimodal payloads. A multimodal request must continue
 * through the MTMD backend, while ordinary reasoning/code/planning GGUFs can use the same pinned
 * native llama.cpp runtime and its bounded Vulkan policy without requiring an mmproj pairing.
 */
interface LlamaNativePromptTokenEngine {
    fun estimateWarmTextPromptTokens(
        sessionKey: String,
        prompt: String
    ): Result<Int>
}

interface LlamaNativeTextEngine {
    val engineId: String
    fun health(): BackendHealth

    fun generateText(
        modelPath: String,
        request: InferenceRequest,
        threads: Int,
        contextTokens: Int,
        cancellation: InferenceCancellationSignal,
        onToken: (String) -> Unit
    ): Result<LlamaNativeGeneration>
}

interface NativeWarmSessionResidencyAwareEngine {
    fun isWarmTextSessionResident(sessionKey: String): Boolean
    fun isWarmMtmdSessionResident(sessionKey: String): Boolean
}

/**
 * Optional immutable-model residency for text inference.
 *
 * Only llama_model state may be retained. Every request still receives a fresh llama_context, so
 * conversation continuity remains owned by AMPER and no hidden native KV state crosses turns.
 */
interface WarmSessionLlamaNativeTextEngine {
    fun generateTextWarm(
        sessionKey: String,
        modelPath: String,
        request: InferenceRequest,
        threads: Int,
        contextTokens: Int,
        cancellation: InferenceCancellationSignal,
        onToken: (String) -> Unit
    ): Result<LlamaNativeGeneration>

    fun releaseWarmTextSession(sessionKey: String): Result<Unit>
}

interface PreparableWarmSessionLlamaNativeTextEngine : WarmSessionLlamaNativeTextEngine {
    fun prepareWarmTextSession(
        sessionKey: String,
        modelPath: String,
        threads: Int
    ): Result<Unit>
}

interface CancellablePreparableWarmSessionLlamaNativeTextEngine :
    PreparableWarmSessionLlamaNativeTextEngine {
    fun prepareWarmTextSession(
        sessionKey: String,
        modelPath: String,
        threads: Int,
        cancellation: InferenceCancellationSignal
    ): Result<Unit>
}

/**
 * Phase157 production text GGUF backend.
 *
 * It gives text/code/planning models the same descriptor-bound verification, true token streaming,
 * governed cancellation, bounded CPU/Vulkan acceleration and immutable-model warm residency already
 * used by the native MTMD path. Attachments fail closed so multimodal routing remains isolated.
 */
class LlamaNativeTextInferenceBackend(
    private val engine: LlamaNativeTextEngine,
    private val verifier: ModelArtifactIdentityVerifier = ModelArtifactIdentityVerifier()
) : CancellablePreparableInferenceBackend,
    CancellableStreamingInferenceBackend,
    PromptTokenEstimatingInferenceBackend,
    AttachmentAwareInferenceBackend,
    ConcurrencyLimitedInferenceBackend,
    SharedExecutionGroupInferenceBackend,
    ResourceReclaimingInferenceBackend {

    override val id: String = "llama.cpp-native-text"
    override val maxConcurrentExecutions: Int = 1
    override val executionGroupId: String = NativeLlamaExecutionGroup.ID
    override val maxConcurrentExecutionsInGroup: Int =
        NativeLlamaExecutionGroup.MAX_CONCURRENT_EXECUTIONS

    private data class WarmTextIdentity(
        val modelId: ModelId,
        val locator: String,
        val sha256: String,
        val threads: Int,
        val accelerationIdentity: String,
        val estimatedMemoryMb: Int,
        val key: String
    )

    private val warmLock = Any()
    private val promptTokenCache = NativePromptTokenPreflightCache()
    private var warmSession: WarmTextIdentity? = null

    override fun health(): BackendHealth = engine.health()

    override fun supports(model: InstalledModel): Boolean =
        engine.health().state != BackendState.UNAVAILABLE &&
            model.descriptor.local &&
            model.descriptor.format.equals("gguf", ignoreCase = true)

    override fun supportedAttachmentKinds(
        model: InstalledModel
    ): Set<InferenceAttachmentKind> = emptySet()

    override fun estimatePromptTokens(
        model: InstalledModel,
        request: InferenceRequest
    ): Int {
        val fallback = TitanPromptTokenEstimator.estimate(request.prompt)
        if (request.attachments.isNotEmpty()) return fallback
        val estimator = engine as? LlamaNativePromptTokenEngine ?: return fallback

        return synchronized(warmLock) {
            val current = warmSession
            if (
                current == null ||
                current.modelId != model.descriptor.id ||
                current.locator != model.locator ||
                current.sha256 != model.sha256
            ) {
                return@synchronized fallback
            }
            if (!isWarmTextResident(current.key)) {
                warmSession = null
                promptTokenCache.clear()
                return@synchronized fallback
            }

            val cacheKey = NativePromptTokenPreflightKey.from(
                sessionKey = current.key,
                request = request,
                includeAttachments = false
            )
            promptTokenCache.get(cacheKey)?.let { return@synchronized it }

            estimator
                .estimateWarmTextPromptTokens(current.key, request.prompt)
                .getOrNull()
                ?.takeIf { it > 0 }
                ?.also { promptTokenCache.put(cacheKey, it) }
                ?: fallback
        }
    }

    override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost {
        val mib = 1024L * 1024L
        val modelMb = model.lengthBytes
            ?.let { ((it + mib - 1L) / mib).toInt() }
            ?: 0
        val reserveMb = max(512, modelMb / 4)
        val required = TitanPromptTokenEstimator.requiredContextTokens(this, model, request)
        val contextTokens = when {
            required > 4_096L -> 8_192
            required > 2_048L -> 4_096
            else -> 2_048
        }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return InferenceCost(
            estimatedMemoryMb = if (modelMb == 0) 0 else modelMb + reserveMb,
            preferredThreads = (cores - 1).coerceIn(1, 6),
            contextTokens = contextTokens
        )
    }

    override fun sessionAffinity(
        model: InstalledModel,
        request: InferenceRequest,
        cost: InferenceCost
    ): BackendSessionAffinity {
        if (engine !is WarmSessionLlamaNativeTextEngine) {
            return BackendSessionAffinity.COLD
        }
        val expected = warmIdentity(model, cost)
        return synchronized(warmLock) {
            if (warmSession != expected) {
                BackendSessionAffinity.COLD
            } else if (isWarmTextResident(expected.key)) {
                BackendSessionAffinity.WARM_COMPATIBLE
            } else {
                warmSession = null
                promptTokenCache.clear()
                BackendSessionAffinity.COLD
            }
        }
    }

    override fun prepare(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal
    ): Result<BackendPreparationResult> = runCatching {
        cancellation.throwIfCancelled()
        require(request.attachments.isEmpty()) {
            "native text preparation does not accept multimodal attachments"
        }
        val nativeSource = source.requireTrustedNativePathSource(
            "native text preparation"
        )
        val warmEngine = requireNotNull(
            engine as? PreparableWarmSessionLlamaNativeTextEngine
        ) {
            "native text engine does not support warm preparation"
        }
        val cost = estimate(model, request)
        val identity = warmIdentity(model, cost)

        nativeSource.withNativePath { modelPath ->
            verifier.verifyNativePath(model, nativeSource, modelPath).getOrThrow()
            cancellation.throwIfCancelled()
            synchronized(warmLock) {
                cancellation.throwIfCancelled()
                val previous = warmSession
                if (previous != null && previous != identity) {
                    warmEngine.releaseWarmTextSession(previous.key).getOrThrow()
                    warmSession = null
                    promptTokenCache.clear()
                }

                val reused =
                    warmSession == identity && isWarmTextResident(identity.key)
                if (!reused) {
                    try {
                        val cancellableWarmEngine =
                            warmEngine as? CancellablePreparableWarmSessionLlamaNativeTextEngine
                        if (cancellableWarmEngine != null) {
                            cancellableWarmEngine.prepareWarmTextSession(
                                sessionKey = identity.key,
                                modelPath = modelPath,
                                threads = cost.preferredThreads,
                                cancellation = cancellation
                            ).getOrThrow()
                        } else {
                            cancellation.throwIfCancelled()
                            warmEngine.prepareWarmTextSession(
                                sessionKey = identity.key,
                                modelPath = modelPath,
                                threads = cost.preferredThreads
                            ).getOrThrow()
                            cancellation.throwIfCancelled()
                        }
                        cancellation.throwIfCancelled()
                        warmSession = identity
                    } catch (error: Throwable) {
                        runCatching {
                            warmEngine.releaseWarmTextSession(identity.key).getOrThrow()
                        }.onFailure(error::addSuppressed)
                        warmSession = null
                        promptTokenCache.clear()
                        throw error
                    }
                }
                BackendPreparationResult(sessionReused = reused)
            }
        }
    }

    override fun unload(modelId: ModelId): Result<Unit> = runCatching {
        val warmEngine = engine as? WarmSessionLlamaNativeTextEngine ?: return@runCatching
        val key = synchronized(warmLock) {
            val current = warmSession
            if (current?.modelId != modelId) return@synchronized null
            warmSession = null
            promptTokenCache.clear()
            current.key
        }
        if (key != null) {
            warmEngine.releaseWarmTextSession(key).getOrThrow()
        }
    }

    override fun reconcileResources(
        budget: ResourceBudget
    ): Result<BackendResourceReconciliation> = runCatching {
        val warmEngine = engine as? WarmSessionLlamaNativeTextEngine
            ?: return@runCatching BackendResourceReconciliation()
        val evicted = synchronized(warmLock) {
            val current = warmSession ?: return@synchronized null
            if (!isWarmTextResident(current.key)) {
                warmSession = null
                promptTokenCache.clear()
                return@synchronized null
            }
            val residentPressureFloorMb = max(256, current.estimatedMemoryMb / 4)
            val mustRelease =
                budget.thermalClass >= 4 ||
                    budget.memoryMb < residentPressureFloorMb
            if (!mustRelease) return@synchronized null
            warmSession = null
            promptTokenCache.clear()
            current
        }
        if (evicted == null) {
            BackendResourceReconciliation()
        } else {
            warmEngine.releaseWarmTextSession(evicted.key).getOrThrow()
            BackendResourceReconciliation(
                releasedModelIds = setOf(evicted.modelId)
            )
        }
    }

    override fun infer(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest
    ): Result<InferenceResponse> =
        inferStream(model, source, request, InferenceCancellationSignal()) { }

    override fun inferStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> =
        inferStream(
            model,
            source,
            request,
            InferenceCancellationSignal(),
            onChunk
        )

    override fun inferStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> = runCatching {
        cancellation.throwIfCancelled()
        require(request.attachments.isEmpty()) {
            "native text backend does not accept multimodal attachments"
        }
        val nativeSource = source.requireTrustedNativePathSource(
            "native text backend"
        )

        val cost = estimate(model, request)
        var chunkIndex = 0
        var sessionReused = false
        val generation = nativeSource.withNativePath { modelPath ->
            verifier.verifyNativePath(model, nativeSource, modelPath).getOrThrow()
            cancellation.throwIfCancelled()

            val tokenSink: (String) -> Unit = { tokenText ->
                cancellation.throwIfCancelled()
                if (tokenText.isNotEmpty()) {
                    onChunk(InferenceChunk(tokenText, chunkIndex++))
                }
            }

            val warmEngine = engine as? WarmSessionLlamaNativeTextEngine
            if (warmEngine == null) {
                engine.generateText(
                    modelPath = modelPath,
                    request = request,
                    threads = cost.preferredThreads,
                    contextTokens = cost.contextTokens,
                    cancellation = cancellation,
                    onToken = tokenSink
                ).getOrThrow()
            } else {
                val identity = warmIdentity(model, cost)
                synchronized(warmLock) {
                    val previous = warmSession
                    if (previous != null && previous != identity) {
                        warmEngine.releaseWarmTextSession(previous.key).getOrThrow()
                        warmSession = null
                        promptTokenCache.clear()
                    }

                    val wasAlreadyWarm =
                        warmSession == identity && isWarmTextResident(identity.key)
                    sessionReused = wasAlreadyWarm
                    try {
                        val generated = warmEngine.generateTextWarm(
                            sessionKey = identity.key,
                            modelPath = modelPath,
                            request = request,
                            threads = cost.preferredThreads,
                            contextTokens = cost.contextTokens,
                            cancellation = cancellation,
                            onToken = tokenSink
                        ).getOrThrow()
                        warmSession = identity
                        generated
                    } catch (error: Throwable) {
                        if (!wasAlreadyWarm) {
                            runCatching {
                                warmEngine.releaseWarmTextSession(identity.key).getOrThrow()
                            }.onFailure(error::addSuppressed)
                            warmSession = null
                            promptTokenCache.clear()
                        }
                        throw error
                    }
                }
            }
        }

        cancellation.throwIfCancelled()
        onChunk(InferenceChunk("", chunkIndex, finished = true))
        InferenceResponse(
            modelId = model.descriptor.id,
            backendId = id,
            text = generation.text,
            promptTokens = generation.promptTokens,
            outputTokens = generation.outputTokens,
            sessionReused = sessionReused,
            tokensPerSecond = generation.tokensPerSecond,
            promptEvalTimeMs = generation.promptEvalTimeMs,
            generationTimeMs = generation.generationTimeMs
        )
    }

    private fun isWarmTextResident(sessionKey: String): Boolean =
        (engine as? NativeWarmSessionResidencyAwareEngine)
            ?.isWarmTextSessionResident(sessionKey)
            ?: true

    private fun accelerationIdentity(): String =
        (engine as? NativeAccelerationAwareEngine)
            ?.accelerationIdentity()
            ?.takeIf { it.isNotBlank() }
            ?: "cpu-or-legacy"

    private fun warmIdentity(
        model: InstalledModel,
        cost: InferenceCost
    ): WarmTextIdentity {
        val acceleration = accelerationIdentity()
        val key = buildString {
            append(model.descriptor.id.value)
            append(':')
            append(model.locator)
            append(':')
            append(model.sha256)
            append(':')
            append(cost.preferredThreads)
            append(':')
            append(acceleration)
        }
        return WarmTextIdentity(
            modelId = model.descriptor.id,
            locator = model.locator,
            sha256 = model.sha256,
            threads = cost.preferredThreads,
            accelerationIdentity = acceleration,
            estimatedMemoryMb = cost.estimatedMemoryMb,
            key = key
        )
    }
}
