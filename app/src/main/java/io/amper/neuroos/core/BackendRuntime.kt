package io.amper.neuroos.core

enum class BackendState { READY, DEGRADED, UNAVAILABLE }

enum class BackendSessionAffinity { COLD, WARM_COMPATIBLE }

data class BackendHealth(
    val state: BackendState,
    val detail: String = "",
    val hardwareAcceleration: Boolean = false,
    val checkedAtEpochMs: Long = System.currentTimeMillis()
)

data class InferenceCost(
    val estimatedMemoryMb: Int = 0,
    val preferredThreads: Int = 1,
    val contextTokens: Int = 4096
) {
    init {
        require(estimatedMemoryMb >= 0)
        require(preferredThreads > 0)
        require(contextTokens > 0)
    }
}

/**
 * Optional request-shape eligibility contract for backends whose model-level support is broader
 * than the requests they should execute.
 *
 * This participates only in feasibility. It never raises ranking, grants capabilities, or weakens
 * attachment/resource checks.
 */
interface RequestAwareInferenceBackend : InferenceBackend {
    fun supportsRequest(model: InstalledModel, request: InferenceRequest): Boolean
    fun requestRejectionReason(
        model: InstalledModel,
        request: InferenceRequest
    ): String = "request-unsupported"
}

/** Optional lifecycle/health surface for production inference engines. */
interface ManagedInferenceBackend : InferenceBackend {
    fun health(): BackendHealth
    fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost

    fun sessionAffinity(
        model: InstalledModel,
        request: InferenceRequest,
        cost: InferenceCost
    ): BackendSessionAffinity = BackendSessionAffinity.COLD

    fun unload(modelId: ModelId): Result<Unit> = Result.success(Unit)
}

/**
 * Optional exact/engine-specific prompt token estimate. Backends that do not expose tokenization
 * use [TitanPromptTokenEstimator], which is deliberately conservative and deterministic.
 */
interface PromptTokenEstimatingInferenceBackend : InferenceBackend {
    fun estimatePromptTokens(model: InstalledModel, request: InferenceRequest): Int
}

/**
 * Deterministic preflight prompt estimator used when a backend cannot expose its tokenizer.
 *
 * This is not presented as tokenizer truth. It intentionally over-reserves non-ASCII text and a
 * small chat-template allowance so routing accounts for both prompt and output instead of treating
 * the entire context window as generation capacity. ASCII text is budgeted at roughly three
 * characters/token; extended Latin at two characters/token; other Unicode code points at one token.
 */
object TitanPromptTokenEstimator {
    private const val CHAT_TEMPLATE_RESERVE_TOKENS = 32

    fun estimate(prompt: String): Int {
        require(prompt.isNotBlank())
        var ascii = 0
        var extendedLatin = 0
        var other = 0
        var offset = 0
        while (offset < prompt.length) {
            val codePoint = Character.codePointAt(prompt, offset)
            when {
                codePoint <= 0x7f -> ascii += 1
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN ->
                    extendedLatin += 1
                else -> other += 1
            }
            offset += Character.charCount(codePoint)
        }
        val asciiTokens = (ascii + 2) / 3
        val extendedLatinTokens = (extendedLatin + 1) / 2
        return (asciiTokens.toLong() + extendedLatinTokens + other + CHAT_TEMPLATE_RESERVE_TOKENS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
    }

    fun requiredContextTokens(
        backend: InferenceBackend,
        model: InstalledModel,
        request: InferenceRequest
    ): Long {
        val promptTokens = if (backend is PromptTokenEstimatingInferenceBackend) {
            backend.estimatePromptTokens(model, request).also {
                require(it > 0) { "backend prompt token estimate must be positive: ${backend.id}" }
            }
        } else {
            estimate(request.prompt)
        }
        return promptTokens.toLong() + request.maxOutputTokens.toLong()
    }
}

/**
 * Optional execution-capacity contract for backends that cannot safely execute arbitrarily many
 * requests in parallel. Titan reserves this capacity together with the global resource lease.
 */
interface ConcurrencyLimitedInferenceBackend : InferenceBackend {
    val maxConcurrentExecutions: Int
}

/**
 * Optional execution-domain contract for multiple logical backends that share one non-reentrant
 * native runtime/device lane.
 *
 * The group limit is enforced by Titan in addition to global slots/memory. It prevents sibling
 * backends such as text llama.cpp and MTMD llama.cpp from being treated as independent execution
 * capacity merely because they expose different backend ids.
 */
interface SharedExecutionGroupInferenceBackend : InferenceBackend {
    val executionGroupId: String
    val maxConcurrentExecutionsInGroup: Int
}

internal fun InferenceBackend.executionGroupId(): String =
    (this as? SharedExecutionGroupInferenceBackend)
        ?.executionGroupId
        ?.takeIf { it.isNotBlank() }
        ?: id

internal fun InferenceBackend.executionGroupConcurrencyLimit(): Int =
    (this as? SharedExecutionGroupInferenceBackend)
        ?.maxConcurrentExecutionsInGroup
        ?: (this as? ConcurrencyLimitedInferenceBackend)
            ?.maxConcurrentExecutions
        ?: Int.MAX_VALUE

data class BackendPreparationResult(
    val sessionReused: Boolean
)

interface PreparableInferenceBackend : ManagedInferenceBackend {
    fun prepare(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest
    ): Result<BackendPreparationResult>
}

/**
 * Stronger preparation contract for routes whose warm-up can be cancelled while native model
 * state is still loading. Cancellation is a user action, not a backend failure, and cancelled
 * preparation must never publish a prepared handoff.
 */
interface CancellablePreparableInferenceBackend : PreparableInferenceBackend {
    fun prepare(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal
    ): Result<BackendPreparationResult>

    override fun prepare(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest
    ): Result<BackendPreparationResult> = prepare(
        model = model,
        source = source,
        request = request,
        cancellation = InferenceCancellationSignal()
    )
}

data class BackendResourceReconciliation(
    val releasedModelIds: Set<ModelId> = emptySet()
)

interface ResourceReclaimingInferenceBackend : ManagedInferenceBackend {
    fun reconcileResources(budget: ResourceBudget): Result<BackendResourceReconciliation>
}

interface NativeInferenceAdapter {
    val adapterId: String
    fun health(): BackendHealth
    fun supportsFormat(format: String): Boolean
    fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost
    fun generate(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest
    ): Result<String>
    fun unload(modelId: ModelId): Result<Unit> = Result.success(Unit)
}

class AdapterInferenceBackend(
    private val adapter: NativeInferenceAdapter
) : ManagedInferenceBackend {
    override val id: String = adapter.adapterId

    override fun supports(model: InstalledModel): Boolean =
        adapter.health().state != BackendState.UNAVAILABLE &&
            adapter.supportsFormat(model.descriptor.format)

    override fun health(): BackendHealth = adapter.health()

    override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
        adapter.estimate(model, request)

    override fun infer(
        model: InstalledModel,
        source: ModelArtifactSource,
        request: InferenceRequest
    ): Result<InferenceResponse> = adapter.generate(model, source, request).map { text ->
        InferenceResponse(modelId = model.descriptor.id, backendId = id, text = text)
    }

    override fun unload(modelId: ModelId): Result<Unit> = adapter.unload(modelId)
}

internal data class TitanBackendEvaluation(
    val eligible: Boolean,
    val score: Int,
    /** Positive managed-backend estimate sampled during this exact admission pass; null means unknown. */
    val estimatedMemoryMb: Int? = null,
    /** Read-only diagnostic only; never participates in ranking. */
    val rejectionReason: String? = null
)

object TitanBackendPolicy {
    private const val READY_SCORE = 1_000
    private const val DEGRADED_SCORE = 400
    private const val UNMANAGED_SCORE = 10
    private const val HARDWARE_ACCELERATION_BONUS = 20
    private const val MAX_MEMORY_HEADROOM_BONUS = 100
    private const val WARM_SESSION_BONUS = 150
    private const val PREFER_REUSE_WARM_SESSION_BONUS = 300

    fun eligible(
        backend: InferenceBackend,
        model: InstalledModel,
        request: InferenceRequest,
        budget: ResourceBudget
    ): Boolean = evaluate(backend, model, request, budget).eligible

    internal fun eligibleForRequest(
        backend: InferenceBackend,
        model: InstalledModel,
        request: InferenceRequest
    ): Boolean {
        if (!backend.supports(model)) return false
        if (
            backend is RequestAwareInferenceBackend &&
            !backend.supportsRequest(model, request)
        ) {
            return false
        }
        if (!MultimodalInferencePolicy.backendSupports(backend, model, request.attachments)) return false
        if (backend !is ManagedInferenceBackend) return true
        val health = backend.health()
        if (health.state == BackendState.UNAVAILABLE) return false
        val cost = backend.estimate(model, request)
        return TitanPromptTokenEstimator.requiredContextTokens(backend, model, request) <=
            cost.contextTokens.toLong()
    }

    /**
     * One coherent governed evaluation for one backend/model/request tuple.
     *
     * [requireKnownMemoryEstimate] is enabled only when another known-memory execution is already
     * active. In that state an unmanaged backend or a managed backend that reports zero/unknown
     * memory cannot be admitted during planning because the final concurrent lease would require a
     * positive estimate. This lets Titan fall through to a smaller known-cost route instead of
     * selecting an execution that is guaranteed to fail admission.
     *
     * Phase 110 also proves that estimated prompt tokens plus requested output fit the backend's
     * context window before execution begins. Phase 140 lets a conversation tune only the ranking
     * bonus for an already-compatible warm session; eligibility and all admission checks remain
     * identical regardless of that preference.
     */
    internal fun evaluate(
        backend: InferenceBackend,
        model: InstalledModel,
        request: InferenceRequest,
        budget: ResourceBudget,
        requireKnownMemoryEstimate: Boolean = false
    ): TitanBackendEvaluation {
        if (budget.thermalClass >= 4 || budget.memoryMb < 0) {
            return TitanBackendEvaluation(
                eligible = false,
                score = 0,
                rejectionReason = "resource-critical"
            )
        }
        if (!backend.supports(model)) {
            return TitanBackendEvaluation(
                eligible = false,
                score = 0,
                rejectionReason = "backend-unsupported"
            )
        }
        if (
            backend is RequestAwareInferenceBackend &&
            !backend.supportsRequest(model, request)
        ) {
            return TitanBackendEvaluation(
                eligible = false,
                score = 0,
                rejectionReason = backend.requestRejectionReason(model, request)
                    .ifBlank { "request-unsupported" }
            )
        }
        MultimodalInferencePolicy.rejectionReason(
            backend = backend,
            model = model,
            attachments = request.attachments
        )?.let { reason ->
            return TitanBackendEvaluation(
                eligible = false,
                score = 0,
                rejectionReason = reason
            )
        }
        if (backend !is ManagedInferenceBackend) {
            return if (requireKnownMemoryEstimate) {
                TitanBackendEvaluation(
                    eligible = false,
                    score = 0,
                    rejectionReason = "memory-estimate-required"
                )
            } else {
                TitanBackendEvaluation(eligible = true, score = UNMANAGED_SCORE)
            }
        }

        val health = backend.health()
        if (health.state == BackendState.UNAVAILABLE) {
            return TitanBackendEvaluation(
                eligible = false,
                score = 0,
                rejectionReason = "backend-unavailable"
            )
        }
        val cost = backend.estimate(model, request)
        if (
            TitanPromptTokenEstimator.requiredContextTokens(backend, model, request) >
            cost.contextTokens.toLong()
        ) {
            return TitanBackendEvaluation(
                eligible = false,
                score = 0,
                rejectionReason = "context-window"
            )
        }
        if (requireKnownMemoryEstimate && cost.estimatedMemoryMb <= 0) {
            return TitanBackendEvaluation(
                eligible = false,
                score = 0,
                rejectionReason = "memory-estimate-required"
            )
        }
        if (cost.estimatedMemoryMb != 0 && cost.estimatedMemoryMb > budget.memoryMb) {
            return TitanBackendEvaluation(
                eligible = false,
                score = 0,
                rejectionReason = "memory-budget"
            )
        }

        val healthScore = when (health.state) {
            BackendState.READY -> READY_SCORE
            BackendState.DEGRADED -> DEGRADED_SCORE
            BackendState.UNAVAILABLE -> 0
        }
        val accelerationBonus = if (health.hardwareAcceleration) HARDWARE_ACCELERATION_BONUS else 0
        val headroomBonus = memoryHeadroomBonus(cost.estimatedMemoryMb, budget.memoryMb)
        val warmSessionBonus = when (backend.sessionAffinity(model, request, cost)) {
            BackendSessionAffinity.COLD -> 0
            BackendSessionAffinity.WARM_COMPATIBLE -> when (request.sessionRoutingPreference) {
                TitanSessionRoutingPreference.STANDARD -> WARM_SESSION_BONUS
                TitanSessionRoutingPreference.PREFER_REUSE -> PREFER_REUSE_WARM_SESSION_BONUS
                TitanSessionRoutingPreference.IGNORE_REUSE -> 0
            }
        }
        return TitanBackendEvaluation(
            eligible = true,
            score = healthScore + accelerationBonus + headroomBonus + warmSessionBonus,
            estimatedMemoryMb = cost.estimatedMemoryMb.takeIf { it > 0 }
        )
    }

    fun score(backend: InferenceBackend): Int = when (backend) {
        is ManagedInferenceBackend -> {
            val h = backend.health()
            (if (h.state == BackendState.READY) 100 else 40) +
                (if (h.hardwareAcceleration) 20 else 0)
        }
        else -> 10
    }

    private fun memoryHeadroomBonus(estimatedMemoryMb: Int, budgetMemoryMb: Int): Int {
        if (estimatedMemoryMb <= 0 || budgetMemoryMb <= 0) return 0
        val headroomMb = (budgetMemoryMb.toLong() - estimatedMemoryMb.toLong()).coerceAtLeast(0L)
        return ((headroomMb * MAX_MEMORY_HEADROOM_BONUS) / budgetMemoryMb.toLong())
            .toInt()
            .coerceIn(0, MAX_MEMORY_HEADROOM_BONUS)
    }
}
