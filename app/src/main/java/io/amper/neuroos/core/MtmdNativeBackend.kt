package io.amper.neuroos.core

import kotlin.math.max

data class MtmdNativeGeneration(
    val text: String,
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    val tokensPerSecond: Double? = null,
    val promptEvalTimeMs: Long? = null,
    val generationTimeMs: Long? = null
)

/**
 * Engine boundary implemented only by the optional native MTMD source set.
 *
 * The engine receives descriptor-bound model/projector paths and ephemeral request attachments.
 * Cancellation is cooperative and must stop native decode or make its result non-authoritative.
 */
interface MtmdNativePromptTokenEngine {
    fun estimateWarmMtmdPromptTokens(
        sessionKey: String,
        request: InferenceRequest
    ): Result<Int>
}

interface MtmdNativeEngine {
    val engineId: String
    fun health(): BackendHealth

    /** Probe libmtmd's actual projector capabilities without beginning text-model inference. */
    fun probeProjector(projectorPath: String): Result<Set<InferenceAttachmentKind>>

    fun generate(
        modelPath: String,
        projectorPath: String,
        request: InferenceRequest,
        threads: Int,
        contextTokens: Int,
        cancellation: InferenceCancellationSignal,
        onToken: (String) -> Unit
    ): Result<MtmdNativeGeneration>
}

/**
 * Optional runtime acceleration identity.
 *
 * This is descriptive only: route eligibility still uses the canonical backend contracts.
 * The identity is folded into warm-session keys so a CPU/Vulkan runtime transition can never
 * accidentally reuse resident state created under a different execution backend.
 */
interface MtmdNativeAccelerationAwareEngine : NativeAccelerationAwareEngine

/**
 * Optional native capability that retains only immutable GGUF model/projector runtime state.
 *
 * The warm key is supplied by the governed Kotlin backend after descriptor-bound re-verification.
 * Request llama_context state is never retained through this interface.
 */
interface WarmSessionMtmdNativeEngine {
    fun generateWarm(
        sessionKey: String,
        modelPath: String,
        projectorPath: String,
        request: InferenceRequest,
        threads: Int,
        contextTokens: Int,
        cancellation: InferenceCancellationSignal,
        onToken: (String) -> Unit
    ): Result<MtmdNativeGeneration>

    fun releaseWarmSession(sessionKey: String): Result<Unit>
}

interface PreparableWarmSessionMtmdNativeEngine : WarmSessionMtmdNativeEngine {
    fun prepareWarmSession(
        sessionKey: String,
        modelPath: String,
        projectorPath: String,
        threads: Int
    ): Result<Unit>
}

interface CancellablePreparableWarmSessionMtmdNativeEngine :
    PreparableWarmSessionMtmdNativeEngine {
    fun prepareWarmSession(
        sessionKey: String,
        modelPath: String,
        projectorPath: String,
        threads: Int,
        cancellation: InferenceCancellationSignal
    ): Result<Unit>
}

data class OptionalMtmdNativeEngineStatus(
    val engine: MtmdNativeEngine?,
    val available: Boolean,
    val detail: String
)

object OptionalMtmdNativeEngineLoader {
    fun load(
        className: String = "io.amper.neuroos.backend.AndroidMtmdNativeEngine"
    ): OptionalMtmdNativeEngineStatus {
        require(className.isNotBlank())
        return runCatching {
            val instance = Class.forName(className).getDeclaredConstructor().newInstance()
            require(instance is MtmdNativeEngine) { "$className is not an MtmdNativeEngine" }
            val health = instance.health()
            if (health.state == BackendState.UNAVAILABLE) {
                OptionalMtmdNativeEngineStatus(
                    engine = null,
                    available = false,
                    detail = health.detail.ifBlank { "native MTMD engine unavailable" }
                )
            } else {
                OptionalMtmdNativeEngineStatus(
                    engine = instance,
                    available = true,
                    detail = health.detail.ifBlank { "native MTMD engine ready" }
                )
            }
        }.getOrElse { error ->
            OptionalMtmdNativeEngineStatus(
                engine = null,
                available = false,
                detail = "optional MTMD engine not packaged: ${error.message ?: error::class.java.simpleName}"
            )
        }
    }
}

/**
 * True model-native multimodal backend.
 *
 * A route is eligible only when:
 *  - the GGUF model explicitly declares the requested modality,
 *  - a durable mmproj binding exists for that exact model id,
 *  - the optional native engine is healthy,
 *  - Phase147 attachment admission accepts the requested kinds.
 *
 * Before generation, both the text GGUF and mmproj are re-hashed through descriptor-bound leases
 * and libmtmd probes the projector capability. No text-backend fallback occurs after this backend
 * begins execution.
 */
class MtmdNativeInferenceBackend(
    private val engine: MtmdNativeEngine,
    private val projectors: MultimodalProjectorCatalog,
    private val projectorArtifacts: MultimodalProjectorArtifactResolver,
    private val modelVerifier: ModelArtifactIdentityVerifier = ModelArtifactIdentityVerifier(),
    private val projectorVerifier: MultimodalProjectorIdentityVerifier =
        MultimodalProjectorIdentityVerifier()
) : CancellablePreparableInferenceBackend,
    CancellableStreamingInferenceBackend,
    PromptTokenEstimatingInferenceBackend,
    RequestAwareInferenceBackend,
    AttachmentAwareInferenceBackend,
    ConcurrencyLimitedInferenceBackend,
    SharedExecutionGroupInferenceBackend,
    ResourceReclaimingInferenceBackend {

    override val id: String = "llama.cpp-mtmd-native"
    override val maxConcurrentExecutions: Int = 1
    override val executionGroupId: String = NativeLlamaExecutionGroup.ID
    override val maxConcurrentExecutionsInGroup: Int =
        NativeLlamaExecutionGroup.MAX_CONCURRENT_EXECUTIONS

    private val warmSessionLock = Any()
    private val promptTokenCache = NativePromptTokenPreflightCache()
    private var warmSession: WarmSessionIdentity? = null

    private data class WarmSessionIdentity(
        val modelId: ModelId,
        val modelSha256: String,
        val projectorSha256: String,
        val threads: Int,
        val accelerationIdentity: String,
        val estimatedMemoryMb: Int,
        val key: String
    )

    override fun health(): BackendHealth = engine.health()

    override fun supports(model: InstalledModel): Boolean {
        if (engine.health().state == BackendState.UNAVAILABLE) return false
        if (!model.descriptor.local) return false
        if (!model.descriptor.format.equals("gguf", ignoreCase = true)) return false
        if (projectors.get(model.descriptor.id) == null) return false
        return model.descriptor.capabilities.any {
            it == TitanCapabilities.VISION || it == TitanCapabilities.AUDIO_UNDERSTANDING
        }
    }

    override fun supportsRequest(
        model: InstalledModel,
        request: InferenceRequest
    ): Boolean = request.attachments.isNotEmpty()

    override fun requestRejectionReason(
        model: InstalledModel,
        request: InferenceRequest
    ): String = "multimodal-attachments-required"

    override fun supportedAttachmentKinds(model: InstalledModel): Set<InferenceAttachmentKind> {
        if (!supports(model)) return emptySet()
        val binding = projectors.get(model.descriptor.id) ?: return emptySet()
        return binding.expectedKinds.filterTo(linkedSetOf()) { kind ->
            when (kind) {
                InferenceAttachmentKind.IMAGE ->
                    TitanCapabilities.VISION in model.descriptor.capabilities
                InferenceAttachmentKind.AUDIO ->
                    TitanCapabilities.AUDIO_UNDERSTANDING in model.descriptor.capabilities
            }
        }
    }

    override fun estimatePromptTokens(
        model: InstalledModel,
        request: InferenceRequest
    ): Int {
        val fallback = TitanPromptTokenEstimator.estimate(request.prompt)
        if (request.attachments.isEmpty()) return fallback
        val estimator = engine as? MtmdNativePromptTokenEngine ?: return fallback
        val binding = projectors.get(model.descriptor.id) ?: return fallback
        return synchronized(warmSessionLock) {
            val current = warmSession
            if (
                current == null ||
                current.modelId != model.descriptor.id ||
                current.modelSha256 != model.sha256 ||
                current.projectorSha256 != binding.sha256 ||
                current.accelerationIdentity != accelerationIdentity()
            ) {
                return@synchronized fallback
            }
            if (!isWarmMtmdResident(current.key)) {
                warmSession = null
                promptTokenCache.clear()
                return@synchronized fallback
            }

            val cacheKey = NativePromptTokenPreflightKey.from(
                sessionKey = current.key,
                request = request,
                includeAttachments = true
            )
            promptTokenCache.get(cacheKey)?.let { return@synchronized it }

            // libmtmd tokenization may use projector-owned state. Keep it under the same backend
            // warm-session lock as generation/preparation so parallel planners never tokenize the
            // same resident MTMD context concurrently.
            estimator
                .estimateWarmMtmdPromptTokens(current.key, request)
                .getOrNull()
                ?.takeIf { it > 0 }
                ?.also { promptTokenCache.put(cacheKey, it) }
                ?: fallback
        }
    }

    override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost {
        val mib = 1024L * 1024L
        val modelMb = model.lengthBytes?.let { ((it + mib - 1L) / mib).toInt() } ?: 0
        val projectorMb = projectors.get(model.descriptor.id)?.lengthBytes
            ?.let { ((it + mib - 1L) / mib).toInt() }
            ?: 0
        val combined = modelMb + projectorMb
        val reserveMb = max(768, combined / 4)
        val requiredTextContext = TitanPromptTokenEstimator.requiredContextTokens(this, model, request)
        val contextTokens = when {
            request.attachments.isNotEmpty() || requiredTextContext > 4_096L -> 8_192
            requiredTextContext > 2_048L -> 4_096
            else -> 2_048
        }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return InferenceCost(
            estimatedMemoryMb = if (combined == 0) 0 else combined + reserveMb,
            preferredThreads = (cores - 1).coerceIn(1, 6),
            contextTokens = contextTokens
        )
    }

    override fun sessionAffinity(
        model: InstalledModel,
        request: InferenceRequest,
        cost: InferenceCost
    ): BackendSessionAffinity {
        if (engine !is WarmSessionMtmdNativeEngine) return BackendSessionAffinity.COLD
        val binding = projectors.get(model.descriptor.id) ?: return BackendSessionAffinity.COLD
        val expected = warmIdentity(model, binding, cost)
        return synchronized(warmSessionLock) {
            if (warmSession != expected) {
                BackendSessionAffinity.COLD
            } else if (isWarmMtmdResident(expected.key)) {
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
        require(source is DescriptorBoundNativeModelPathSource) {
            "native MTMD preparation requires a descriptor-bound text-model path"
        }
        val binding = requireNotNull(projectors.get(model.descriptor.id)) {
            "no multimodal projector is paired with model ${model.descriptor.id.value}"
        }
        val projectorSource = requireNotNull(projectorArtifacts.resolve(binding)) {
            "paired multimodal projector artifact is unavailable"
        }
        require(projectorSource is DescriptorBoundNativeModelPathSource) {
            "native MTMD preparation requires a descriptor-bound projector path"
        }
        val requestedKinds = request.attachments.map { it.kind }.toSet()
        require(supportedAttachmentKinds(model).containsAll(requestedKinds)) {
            "preparation attachment kinds exceed paired model/projector declaration"
        }
        val warmEngine = requireNotNull(
            engine as? PreparableWarmSessionMtmdNativeEngine
        ) {
            "native MTMD engine does not support warm preparation"
        }
        val cost = estimate(model, request)
        val identity = warmIdentity(model, binding, cost)

        source.withNativePath { modelPath ->
            modelVerifier.verifyNativePath(model, source, modelPath).getOrThrow()
            cancellation.throwIfCancelled()
            projectorSource.withNativePath { projectorPath ->
                cancellation.throwIfCancelled()
                projectorVerifier
                    .verifyNativePath(binding, projectorSource, projectorPath)
                    .getOrThrow()
                cancellation.throwIfCancelled()
                val actualKinds = engine.probeProjector(projectorPath).getOrThrow()
                cancellation.throwIfCancelled()
                require(actualKinds.containsAll(requestedKinds)) {
                    "libmtmd projector probe does not support preparation attachment kinds"
                }

                synchronized(warmSessionLock) {
                    cancellation.throwIfCancelled()
                    val previous = warmSession
                    if (previous != null && previous != identity) {
                        warmEngine.releaseWarmSession(previous.key).getOrThrow()
                        warmSession = null
                        promptTokenCache.clear()
                    }
                    val reused =
                        warmSession == identity && isWarmMtmdResident(identity.key)
                    if (!reused) {
                        try {
                            val cancellableWarmEngine =
                                warmEngine as? CancellablePreparableWarmSessionMtmdNativeEngine
                            if (cancellableWarmEngine != null) {
                                cancellableWarmEngine.prepareWarmSession(
                                    sessionKey = identity.key,
                                    modelPath = modelPath,
                                    projectorPath = projectorPath,
                                    threads = cost.preferredThreads,
                                    cancellation = cancellation
                                ).getOrThrow()
                            } else {
                                cancellation.throwIfCancelled()
                                warmEngine.prepareWarmSession(
                                    sessionKey = identity.key,
                                    modelPath = modelPath,
                                    projectorPath = projectorPath,
                                    threads = cost.preferredThreads
                                ).getOrThrow()
                                cancellation.throwIfCancelled()
                            }
                            cancellation.throwIfCancelled()
                            warmSession = identity
                        } catch (error: Throwable) {
                            runCatching {
                                warmEngine.releaseWarmSession(identity.key).getOrThrow()
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
    }

    override fun unload(modelId: ModelId): Result<Unit> = runCatching {
        val warmEngine = engine as? WarmSessionMtmdNativeEngine ?: return@runCatching
        val key = synchronized(warmSessionLock) {
            val current = warmSession
            if (current?.modelId != modelId) return@synchronized null
            warmSession = null
            promptTokenCache.clear()
            current.key
        }
        if (key != null) {
            warmEngine.releaseWarmSession(key).getOrThrow()
        }
    }

    override fun reconcileResources(
        budget: ResourceBudget
    ): Result<BackendResourceReconciliation> = runCatching {
        val warmEngine = engine as? WarmSessionMtmdNativeEngine
            ?: return@runCatching BackendResourceReconciliation()
        val evicted = synchronized(warmSessionLock) {
            val current = warmSession ?: return@synchronized null
            if (!isWarmMtmdResident(current.key)) {
                warmSession = null
                promptTokenCache.clear()
                return@synchronized null
            }
            val residentPressureFloorMb =
                max(256, current.estimatedMemoryMb / 4)
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
            warmEngine.releaseWarmSession(evicted.key).getOrThrow()
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
        inferStream(model, source, request, InferenceCancellationSignal(), onChunk)

    override fun inferStream(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> = runCatching {
        cancellation.throwIfCancelled()
        require(source is DescriptorBoundNativeModelPathSource) {
            "native MTMD requires a descriptor-bound text-model path"
        }
        val binding = requireNotNull(projectors.get(model.descriptor.id)) {
            "no multimodal projector is paired with model ${model.descriptor.id.value}"
        }
        val projectorSource = requireNotNull(projectorArtifacts.resolve(binding)) {
            "paired multimodal projector artifact is unavailable"
        }
        require(projectorSource is DescriptorBoundNativeModelPathSource) {
            "native MTMD requires a descriptor-bound projector path"
        }

        val requestedKinds = request.attachments.map { it.kind }.toSet()
        require(supportedAttachmentKinds(model).containsAll(requestedKinds)) {
            "request attachment kinds exceed paired model/projector declaration"
        }

        val cost = estimate(model, request)
        var chunkIndex = 0
        var sessionReused = false
        val generation = source.withNativePath { modelPath ->
            modelVerifier.verifyNativePath(model, source, modelPath).getOrThrow()
            projectorSource.withNativePath { projectorPath ->
                projectorVerifier
                    .verifyNativePath(binding, projectorSource, projectorPath)
                    .getOrThrow()
                val actualKinds = engine.probeProjector(projectorPath).getOrThrow()
                require(actualKinds.containsAll(requestedKinds)) {
                    "libmtmd projector probe does not support requested attachment kinds"
                }
                cancellation.throwIfCancelled()
                val tokenSink: (String) -> Unit = { tokenText ->
                    cancellation.throwIfCancelled()
                    if (tokenText.isNotEmpty()) {
                        onChunk(InferenceChunk(tokenText, chunkIndex++))
                    }
                }
                val warmEngine = engine as? WarmSessionMtmdNativeEngine
                if (warmEngine == null) {
                    engine.generate(
                        modelPath = modelPath,
                        projectorPath = projectorPath,
                        request = request,
                        threads = cost.preferredThreads,
                        contextTokens = cost.contextTokens,
                        cancellation = cancellation,
                        onToken = tokenSink
                    ).getOrThrow()
                } else {
                    val identity = warmIdentity(model, binding, cost)
                    synchronized(warmSessionLock) {
                        val previous = warmSession
                        if (previous != null && previous != identity) {
                            warmEngine.releaseWarmSession(previous.key).getOrThrow()
                            warmSession = null
                            promptTokenCache.clear()
                        }
                        val wasAlreadyWarm =
                            warmSession == identity && isWarmMtmdResident(identity.key)
                        sessionReused = wasAlreadyWarm
                        try {
                            val generated = warmEngine.generateWarm(
                                sessionKey = identity.key,
                                modelPath = modelPath,
                                projectorPath = projectorPath,
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
                                    warmEngine.releaseWarmSession(identity.key)
                                        .getOrThrow()
                                }.onFailure(error::addSuppressed)
                                warmSession = null
                            promptTokenCache.clear()
                            }
                            throw error
                        }
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

    private fun isWarmMtmdResident(sessionKey: String): Boolean =
        (engine as? NativeWarmSessionResidencyAwareEngine)
            ?.isWarmMtmdSessionResident(sessionKey)
            ?: true

    private fun accelerationIdentity(): String =
        (engine as? MtmdNativeAccelerationAwareEngine)
            ?.accelerationIdentity()
            ?.takeIf { it.isNotBlank() }
            ?: "cpu-or-legacy"

    private fun warmIdentity(
        model: InstalledModel,
        binding: InstalledMultimodalProjector,
        cost: InferenceCost
    ): WarmSessionIdentity {
        val key = buildString {
            append(model.descriptor.id.value)
            append(':')
            append(model.sha256)
            append(':')
            append(binding.sha256)
            append(':')
            append(cost.preferredThreads)
            append(':')
            append(accelerationIdentity())
        }
        return WarmSessionIdentity(
            modelId = model.descriptor.id,
            modelSha256 = model.sha256,
            projectorSha256 = binding.sha256,
            threads = cost.preferredThreads,
            accelerationIdentity = accelerationIdentity(),
            estimatedMemoryMb = cost.estimatedMemoryMb,
            key = key
        )
    }
}
