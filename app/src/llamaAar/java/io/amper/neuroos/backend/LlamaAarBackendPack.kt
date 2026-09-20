package io.amper.neuroos.backend

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import io.amper.neuroos.BuildConfig
import io.amper.neuroos.core.BackendHealth
import io.amper.neuroos.core.BackendPreparationResult
import io.amper.neuroos.core.BackendResourceReconciliation
import io.amper.neuroos.core.BackendSessionAffinity
import io.amper.neuroos.core.BackendState
import io.amper.neuroos.core.ConcurrencyLimitedInferenceBackend
import io.amper.neuroos.core.GgufInspector
import io.amper.neuroos.core.GgufTensorLayoutProfile
import io.amper.neuroos.core.GgufTensorLayoutProfiles
import io.amper.neuroos.core.InferenceBackendRegistry
import io.amper.neuroos.core.InferenceCost
import io.amper.neuroos.core.InferenceRequest
import io.amper.neuroos.core.InferenceResponse
import io.amper.neuroos.core.InstalledModel
import io.amper.neuroos.core.ModelArtifactIdentityVerifier
import io.amper.neuroos.core.ModelArtifactSource
import io.amper.neuroos.core.ModelId
import io.amper.neuroos.core.ModelRuntimeIdentity
import io.amper.neuroos.core.MmapGgufMemoryEstimator
import io.amper.neuroos.core.PreparableInferenceBackend
import io.amper.neuroos.core.ResourceBudget
import io.amper.neuroos.core.ResourceReclaimingInferenceBackend
import io.amper.neuroos.core.requireTrustedNativePathSource
import io.amper.neuroos.core.TitanBackendPack
import io.amper.neuroos.core.TitanPromptTokenEstimator
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.runBlocking
import kotlin.math.max

class LlamaAarBackendPack : TitanBackendPack {
    override val id: String = "llama-aar-${BuildConfig.LLAMA_ANDROID_VERSION}"

    override fun attach(registry: InferenceBackendRegistry): Result<Int> = runCatching {
        val profile = GgufTensorLayoutProfiles.requireLlamaAndroidVersion(
            BuildConfig.LLAMA_ANDROID_VERSION
        )
        require(profile.id == "llama-android:${BuildConfig.LLAMA_ANDROID_VERSION}") {
            "llama Android GGUF ABI profile/version mismatch"
        }
        Llama.getSystemInfo()
        registry.register(LlamaAarInferenceBackend(profile))
        1
    }
}

private data class ActiveLlamaSession(
    val identity: ModelRuntimeIdentity,
    val contextTokens: Int,
    val threads: Int,
    val temperature: Float,
    val estimatedMemoryMb: Int,
    val model: LlamaModel
)

private data class EnsuredLlamaSession(
    val session: ActiveLlamaSession,
    val reused: Boolean
)

/**
 * Mobile-first single-model session backend. Cold loads and explicit preparation share one
 * descriptor-bound verification path. Warm compatibility is exposed to Titan routing, while
 * Phase 103 also permits the resident session to be reclaimed when a newer resource-budget
 * snapshot says retaining it is unsafe. Phase 108 declares the backend's single execution slot to
 * Titan admission and makes planning/reconciliation lock inspection non-blocking so concurrent
 * requests can route elsewhere instead of queueing behind the native session lock. Phase 110 sizes
 * the mobile context tier from estimated prompt + output demand rather than output alone.
 */
private class LlamaAarInferenceBackend(
    tensorLayoutProfile: GgufTensorLayoutProfile
) : PreparableInferenceBackend,
    ResourceReclaimingInferenceBackend,
    ConcurrencyLimitedInferenceBackend {
    override val id: String = "llama.cpp-aar"
    override val maxConcurrentExecutions: Int = 1
    private val sessionLock = ReentrantLock()
    private val artifactVerifier = ModelArtifactIdentityVerifier(
        GgufInspector(tensorLayoutProfile = tensorLayoutProfile)
    )
    private var activeSession: ActiveLlamaSession? = null

    override fun health(): BackendHealth = runCatching {
        val info = Llama.getSystemInfo().lineSequence().firstOrNull().orEmpty().take(160)
        BackendHealth(
            state = BackendState.READY,
            detail = if (info.isBlank()) "llama.cpp AAR ready" else info,
            hardwareAcceleration = false
        )
    }.getOrElse { error ->
        BackendHealth(
            state = BackendState.UNAVAILABLE,
            detail = error.message ?: error::class.java.simpleName
        )
    }

    override fun supports(model: InstalledModel): Boolean =
        health().state != BackendState.UNAVAILABLE &&
            model.descriptor.format.equals("gguf", ignoreCase = true)

    override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost {
        val requiredContext = TitanPromptTokenEstimator.requiredContextTokens(this, model, request)
        val contextTokens = if (requiredContext > 2_048L) 4_096 else 2_048
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val threads = (cores - 1).coerceIn(1, 6)
        return InferenceCost(
            estimatedMemoryMb = MmapGgufMemoryEstimator.estimateMemoryMb(
                modelLengthBytes = model.lengthBytes,
                contextTokens = contextTokens
            ),
            preferredThreads = threads,
            contextTokens = contextTokens
        )
    }

    override fun sessionAffinity(
        model: InstalledModel,
        request: InferenceRequest,
        cost: InferenceCost
    ): BackendSessionAffinity {
        if (!sessionLock.tryLock()) return BackendSessionAffinity.COLD
        return try {
            if (
                isCompatibleSessionLocked(
                    session = activeSession,
                    model = model,
                    cost = cost,
                    temperature = request.temperature.toFloat()
                )
            ) {
                BackendSessionAffinity.WARM_COMPATIBLE
            } else {
                BackendSessionAffinity.COLD
            }
        } finally {
            sessionLock.unlock()
        }
    }

    override fun prepare(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest
    ): Result<BackendPreparationResult> = runCatching {
        val nativeSource = source.requireTrustedNativePathSource("llama.cpp AAR")
        val identity = ModelRuntimeIdentity.bind(model, source)
        val cost = estimate(model, request)
        val temperature = request.temperature.toFloat()

        sessionLock.withLock {
            val ensured = ensureSessionLocked(
                model = model,
                source = nativeSource,
                identity = identity,
                cost = cost,
                temperature = temperature
            )
            BackendPreparationResult(sessionReused = ensured.reused)
        }
    }

    override fun infer(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest
    ): Result<InferenceResponse> = runCatching {
        val nativeSource = source.requireTrustedNativePathSource("llama.cpp AAR")

        val identity = ModelRuntimeIdentity.bind(model, source)
        val cost = estimate(model, request)
        val temperature = request.temperature.toFloat()

        sessionLock.withLock {
            val ensured = ensureSessionLocked(
                model = model,
                source = source,
                identity = identity,
                cost = cost,
                temperature = temperature
            )
            try {
                val result = runBlocking {
                    Llama.complete(
                        model = ensured.session.model,
                        prompt = request.prompt,
                        maxTokens = request.maxOutputTokens
                    )
                }
                InferenceResponse(
                    modelId = model.descriptor.id,
                    backendId = id,
                    text = result.text,
                    outputTokens = result.tokensGenerated,
                    sessionReused = ensured.reused,
                    tokensPerSecond = result.tokensPerSecond.toDouble(),
                    promptEvalTimeMs = result.promptEvalTimeMs,
                    generationTimeMs = result.generateTimeMs
                )
            } catch (error: Throwable) {
                releaseActiveLocked()
                throw error
            }
        }
    }

    override fun reconcileResources(
        budget: ResourceBudget
    ): Result<BackendResourceReconciliation> = runCatching {
        if (!sessionLock.tryLock()) {
            return@runCatching BackendResourceReconciliation()
        }
        try {
            val session = activeSession ?: return@runCatching BackendResourceReconciliation()
            val mustRelease = budget.thermalClass >= 4 ||
                budget.memoryMb < 0 ||
                (session.estimatedMemoryMb > 0 && session.estimatedMemoryMb > budget.memoryMb)
            if (!mustRelease) {
                return@runCatching BackendResourceReconciliation()
            }
            val modelId = session.identity.modelId
            releaseActiveLocked()
            BackendResourceReconciliation(setOf(modelId))
        } finally {
            sessionLock.unlock()
        }
    }

    override fun unload(modelId: ModelId): Result<Unit> = runCatching {
        sessionLock.withLock {
            if (activeSession?.identity?.modelId == modelId) releaseActiveLocked()
        }
    }

    private fun ensureSessionLocked(
        model: InstalledModel,
        source: io.amper.neuroos.core.NativeModelPathSource,
        identity: ModelRuntimeIdentity,
        cost: InferenceCost,
        temperature: Float
    ): EnsuredLlamaSession {
        val current = activeSession
        if (
            isCompatibleSessionLocked(
                session = current,
                model = model,
                cost = cost,
                temperature = temperature,
                exactIdentity = identity
            )
        ) {
            return EnsuredLlamaSession(checkNotNull(current), reused = true)
        }

        releaseActiveLocked()
        val loaded = source.withNativePath { nativePath ->
            artifactVerifier.verifyNativePath(model, source, nativePath).getOrThrow()
            runBlocking {
                Llama.loadModel(
                    nativePath,
                    LlamaConfig(
                        contextSize = cost.contextTokens,
                        threads = cost.preferredThreads,
                        gpuLayers = 0,
                        temperature = temperature
                    )
                )
            }
        }
        val created = ActiveLlamaSession(
            identity = identity,
            contextTokens = cost.contextTokens,
            threads = cost.preferredThreads,
            temperature = temperature,
            estimatedMemoryMb = cost.estimatedMemoryMb,
            model = loaded
        )
        activeSession = created
        return EnsuredLlamaSession(created, reused = false)
    }

    private fun isCompatibleSessionLocked(
        session: ActiveLlamaSession?,
        model: InstalledModel,
        cost: InferenceCost,
        temperature: Float,
        exactIdentity: ModelRuntimeIdentity? = null
    ): Boolean {
        if (session == null || !session.model.isLoaded) return false
        val identity = session.identity
        if (identity.modelId != model.descriptor.id) return false
        if (identity.locator != model.locator) return false
        if (identity.sha256 != model.sha256) return false
        if (identity.lengthBytes != model.lengthBytes) return false
        if (identity.ggufVersion != model.ggufVersion) return false
        if (identity.tensorCount != model.tensorCount) return false
        if (identity.metadataKeyValueCount != model.metadataKeyValueCount) return false
        if (exactIdentity != null && identity != exactIdentity) return false
        if (session.contextTokens < cost.contextTokens) return false
        if (session.threads != cost.preferredThreads) return false
        if (session.temperature != temperature) return false
        return true
    }

    private fun releaseActiveLocked() {
        val session = activeSession
        activeSession = null
        if (session?.model?.isLoaded == true) Llama.releaseModel(session.model)
    }
}
