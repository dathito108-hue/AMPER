package io.amper.neuroos.core

data class InferenceRequest(
    val prompt: String,
    val requiredCapabilities: Set<CapabilityId> = setOf(CapabilityId("reasoning")),
    val maxOutputTokens: Int = 512,
    val temperature: Double = 0.7,
    val preferredCapabilityProfiles: List<Set<CapabilityId>> = emptyList(),
    /**
     * Soft turn-local model continuity hint. Routing may ignore this when the model is not capable,
     * installed, healthy, resource-feasible, or when feedback probation/exploration selects another
     * route. It is deliberately not a hard pin and never weakens [requiredCapabilities].
     */
    val preferredModelId: ModelId? = null,
    /**
     * Explicit user-selected soft routing preference. This is stronger than turn continuity but is
     * still subordinate to capability, artifact, backend, feedback, exploration and resource gates.
     * Global routing preference wrappers may populate this only when the caller did not provide one.
     */
    val userPreferredModelId: ModelId? = null,
    /**
     * Conversation-scoped warm-session ranking policy. It never makes an ineligible route eligible
     * and does not bypass context, memory, health, feedback or execution admission.
     */
    val sessionRoutingPreference: TitanSessionRoutingPreference = TitanSessionRoutingPreference.STANDARD,
    val attachments: List<InferenceAttachment> = emptyList()
) {
    init {
        require(prompt.isNotBlank())
        require(requiredCapabilities.isNotEmpty())
        require(maxOutputTokens > 0)
        require(temperature in 0.0..2.0)
        preferredModelId?.let { require(it.value.isNotBlank()) { "preferred model id must not be blank" } }
        userPreferredModelId?.let { require(it.value.isNotBlank()) { "user preferred model id must not be blank" } }
        MultimodalInferencePolicy.validateAttachments(attachments)
        val attachmentCapabilities = MultimodalInferencePolicy.requiredCapabilities(attachments)
        require(requiredCapabilities.containsAll(attachmentCapabilities)) {
            "required capabilities must include all attachment capabilities"
        }
        preferredCapabilityProfiles.forEach { profile ->
            require(profile.isNotEmpty()) { "preferred capability profile must not be empty" }
            require(profile.containsAll(requiredCapabilities)) {
                "preferred capability profile must contain all required capabilities"
            }
        }
    }

    fun capabilityProfiles(): List<Set<CapabilityId>> =
        (preferredCapabilityProfiles + listOf(requiredCapabilities)).distinct()

    /** Explicit user choice outranks continuity; both remain soft routing hints. */
    fun effectivePreferredModelId(): ModelId? = userPreferredModelId ?: preferredModelId

    fun bindSelectedCapabilities(selected: Set<CapabilityId>): InferenceRequest {
        require(selected.isNotEmpty()) { "selected capability profile must not be empty" }
        require(selected.containsAll(requiredCapabilities)) {
            "selected capability profile weakens mandatory requirements"
        }
        require(selected in capabilityProfiles()) {
            "selected capability profile was not declared by the request"
        }
        return copy(
            requiredCapabilities = selected,
            preferredCapabilityProfiles = emptyList()
        )
    }
}

data class InferenceResponse(
    val modelId: ModelId,
    val backendId: String,
    val text: String,
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    val sessionReused: Boolean = false,
    val tokensPerSecond: Double? = null,
    val promptEvalTimeMs: Long? = null,
    val generationTimeMs: Long? = null,
    val selectedCapabilities: Set<CapabilityId> = emptySet()
)

data class InferencePreparation(
    val modelId: ModelId,
    val backendId: String,
    val selectedCapabilities: Set<CapabilityId>,
    val sessionReused: Boolean
)

interface InferenceBackend {
    val id: String
    fun supports(model: InstalledModel): Boolean
    fun infer(model: InstalledModel, source: ModelArtifactSource, request: InferenceRequest): Result<InferenceResponse>
}

interface ModelArtifactResolver {
    fun resolve(model: InstalledModel): ModelArtifactSource?
}

class LocatorArtifactResolver(
    private val factories: List<(InstalledModel) -> ModelArtifactSource?>
) : ModelArtifactResolver {
    override fun resolve(model: InstalledModel): ModelArtifactSource? = factories.firstNotNullOfOrNull { it(model) }
}

internal data class TitanEvaluatedBackendCandidate(
    val backend: InferenceBackend,
    val policyScore: Int,
    val estimatedMemoryMb: Int? = null
)

private data class RankedBackendCandidate(
    val ordinal: Int,
    val backend: InferenceBackend,
    val evaluation: TitanBackendEvaluation
)

internal data class TitanAdmittedValue<T>(
    val value: T,
    val lease: TitanExecutionAdmissionLease
)

/**
 * Closes the bounded race between a residual-capacity planning snapshot and final atomic acquire.
 *
 * Each retry re-snapshots process-local execution availability against the exact same immutable
 * [baseBudget] that the runtime read before planning. It never re-reads the ResourceGovernor and it
 * never waits. The default permits exactly one replan after a lost admission race. Phase 108 folds
 * backend-specific execution capacity into the same final atomic acquire.
 */
internal class TitanBoundedAdmissionReplanner(
    private val admission: TitanExecutionAdmissionGate,
    private val maxReplans: Int = 1
) {
    init {
        require(maxReplans >= 0)
    }

    fun <T> acquire(
        baseBudget: ResourceBudget,
        estimatedMemoryMb: (T) -> Int?,
        backendAdmission: (T) -> TitanBackendExecutionAdmission? = { null },
        plan: (TitanExecutionPlanningAvailability) -> T
    ): TitanAdmittedValue<T> {
        var replans = 0
        while (true) {
            val availability = admission.planningAvailability(baseBudget).getOrThrow()
            val value = plan(availability)
            val acquired = admission.acquire(
                budget = baseBudget,
                estimatedMemoryMb = estimatedMemoryMb(value),
                backendAdmission = backendAdmission(value)
            )
            if (acquired.isSuccess) {
                return TitanAdmittedValue(value = value, lease = acquired.getOrThrow())
            }
            if (replans >= maxReplans) {
                throw acquired.exceptionOrNull()
                    ?: IllegalStateException("execution admission failed without a cause")
            }
            replans += 1
        }
    }
}

class InferenceBackendRegistry private constructor(
    private val sealedSingleCoreBackendId: String? = null
) {
    constructor() : this(null)

    private val backends = linkedMapOf<String, InferenceBackend>()

    @Synchronized
    fun register(backend: InferenceBackend) {
        require(backend.id.isNotBlank())
        if (sealedSingleCoreBackendId != null) {
            require(backend.id == sealedSingleCoreBackendId) {
                "AMPER single-core registry rejects foreign backend: ${backend.id}"
            }
            require(backends.isEmpty()) {
                "AMPER single-core registry is sealed after core registration"
            }
        }
        if (backend is ConcurrencyLimitedInferenceBackend) {
            require(backend.maxConcurrentExecutions > 0) {
                "backend execution concurrency must be positive: ${backend.id}"
            }
        }
        if (backend is SharedExecutionGroupInferenceBackend) {
            require(backend.executionGroupId.isNotBlank()) {
                "backend execution group id must not be blank: ${backend.id}"
            }
            require(backend.maxConcurrentExecutionsInGroup > 0) {
                "backend execution group concurrency must be positive: ${backend.id}"
            }
        }
        backends[backend.id] = backend
    }

    @Synchronized
    fun route(model: InstalledModel): InferenceBackend? = candidates(model).firstOrNull()

    @Synchronized
    fun route(
        model: InstalledModel,
        request: InferenceRequest,
        budget: ResourceBudget
    ): InferenceBackend? = candidates(model, request, budget).firstOrNull()

    @Synchronized
    fun candidates(model: InstalledModel): List<InferenceBackend> =
        backends.values.filter { backend ->
            isolatedAdmission { backend.supports(model) } == true
        }

    @Synchronized
    fun candidates(model: InstalledModel, request: InferenceRequest): List<InferenceBackend> =
        backends.values.filter { backend ->
            isolatedAdmission {
                TitanBackendPolicy.eligibleForRequest(backend, model, request)
            } == true
        }

    @Synchronized
    fun candidates(
        model: InstalledModel,
        request: InferenceRequest,
        budget: ResourceBudget
    ): List<InferenceBackend> = evaluatedCandidates(model, request, budget).map { it.backend }

    @Synchronized
    internal fun evaluatedCandidates(
        model: InstalledModel,
        request: InferenceRequest,
        budget: ResourceBudget,
        requireKnownMemoryEstimate: Boolean = false,
        excludedBackendIds: Set<String> = emptySet(),
        excludedExecutionGroupIds: Set<String> = emptySet(),
        onRejected: ((String) -> Unit)? = null
    ): List<TitanEvaluatedBackendCandidate> = backends.values
        .filterNot {
            it.id in excludedBackendIds ||
                it.executionGroupId() in excludedExecutionGroupIds
        }
        .mapIndexedNotNull { ordinal, backend ->
            isolatedAdmission {
                RankedBackendCandidate(
                    ordinal = ordinal,
                    backend = backend,
                    evaluation = TitanBackendPolicy.evaluate(
                        backend = backend,
                        model = model,
                        request = request,
                        budget = budget,
                        requireKnownMemoryEstimate = requireKnownMemoryEstimate
                    )
                )
            }
        }
        .asSequence()
        .mapNotNull { ranked ->
            if (ranked.evaluation.eligible) {
                ranked
            } else {
                val reason = ranked.evaluation.rejectionReason ?: "backend-ineligible"
                runCatching { onRejected?.invoke("${ranked.backend.id}:$reason") }
                null
            }
        }
        .sortedWith(
            compareByDescending<RankedBackendCandidate> { it.evaluation.score }
                .thenBy { it.ordinal }
        )
        .map {
            TitanEvaluatedBackendCandidate(
                backend = it.backend,
                policyScore = it.evaluation.score,
                estimatedMemoryMb = it.evaluation.estimatedMemoryMb
            )
        }
        .toList()

    @Synchronized
    fun list(): List<InferenceBackend> = backends.values.toList()

    fun isSealedSingleCore(): Boolean = sealedSingleCoreBackendId != null

    companion object {
        fun singleCore(core: InferenceBackend): InferenceBackendRegistry =
            InferenceBackendRegistry(core.id).also { registry ->
                registry.register(core)
                require(registry.list().size == 1) {
                    "AMPER single-core registry must contain exactly one inference endpoint"
                }
            }
    }

    private inline fun <T> isolatedAdmission(block: () -> T): T? = try {
        block()
    } catch (fatal: VirtualMachineError) {
        throw fatal
    } catch (fatal: ThreadDeath) {
        throw fatal
    } catch (_: Throwable) {
        null
    }
}

class TitanCortexRuntime(
    models: ModelRegistry,
    private val catalog: InstalledModelCatalog,
    artifacts: ModelArtifactResolver,
    private val backends: InferenceBackendRegistry,
    private val governor: ResourceGovernor? = null,
    routePlanner: TitanInferenceRoutePlanner? = null
) {
    constructor(
        models: ModelRegistry,
        catalog: InstalledModelCatalog,
        artifacts: ModelArtifactResolver,
        core: AmperCoreInferencePort,
        governor: ResourceGovernor? = null
    ) : this(
        models = models,
        catalog = catalog,
        artifacts = artifacts,
        backends = core.titanRegistry(),
        governor = governor
    )

    private val routePlanner = routePlanner ?: TitanInferenceRoutePlanner(
        models = models,
        catalog = catalog,
        artifacts = artifacts,
        backends = backends
    )
    private val executionAdmission = TitanExecutionAdmissionGate()
    private val boundedAdmissionReplanner = TitanBoundedAdmissionReplanner(executionAdmission)
    private val preparedHandoffLock = Any()
    private var preparedHandoff: TitanPreparedRouteHint? = null

    fun prepare(request: InferenceRequest): Result<InferencePreparation> =
        prepare(request, InferenceCancellationSignal())

    fun prepare(
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal
    ): Result<InferencePreparation> = runCatching {
        cancellation.throwIfCancelled()
        clearPreparedHandoff()
        val baseBudget = governor?.currentBudget()
        baseBudget?.let(::reconcileResources)
        cancellation.throwIfCancelled()
        val admitted = baseBudget?.let {
            planGovernedRoute(
                baseBudget = it,
                request = request,
                purpose = TitanRoutePurpose.PREPARATION,
                cancellation = cancellation
            )
        }
        val route = admitted?.value ?: routePlanner
            .plan(
                request = request,
                purpose = TitanRoutePurpose.PREPARATION,
                cancellation = cancellation
            )
            .getOrThrow()

        try {
            cancellation.throwIfCancelled()
            val backend = route.backend as? PreparableInferenceBackend
                ?: error("selected preparation route is not preparable")
            val executionRequest = route.executionRequest(request)
            val preparation = when (backend) {
                is CancellablePreparableInferenceBackend -> backend
                    .prepare(
                        route.installed,
                        route.source,
                        executionRequest,
                        cancellation
                    )
                    .getOrThrow()
                else -> {
                    cancellation.throwIfCancelled()
                    backend
                        .prepare(route.installed, route.source, executionRequest)
                        .getOrThrow()
                        .also { cancellation.throwIfCancelled() }
                }
            }
            cancellation.throwIfCancelled()
            val hint = TitanPreparedRouteHint.from(route, request)
            synchronized(preparedHandoffLock) {
                cancellation.throwIfCancelled()
                preparedHandoff = hint
            }
            InferencePreparation(
                modelId = route.installed.descriptor.id,
                backendId = backend.id,
                selectedCapabilities = route.selectedCapabilities,
                sessionReused = preparation.sessionReused
            )
        } finally {
            admitted?.lease?.close()
        }
    }

    fun infer(request: InferenceRequest): Result<InferenceResponse> =
        infer(request, InferenceCancellationSignal())

    fun infer(
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal
    ): Result<InferenceResponse> = runCatching {
        cancellation.throwIfCancelled()
        val baseBudget = governor?.currentBudget()
        baseBudget?.let(::reconcileResources)
        cancellation.throwIfCancelled()
        val preparedHint = claimCompatiblePreparedHandoff(request)
        val admitted = baseBudget?.let {
            planGovernedRoute(
                baseBudget = it,
                request = request,
                purpose = TitanRoutePurpose.INFERENCE,
                preparedHint = preparedHint,
                cancellation = cancellation
            )
        }
        val route = admitted?.value ?: routePlanner
            .plan(
                request = request,
                purpose = TitanRoutePurpose.INFERENCE,
                preparedHint = preparedHint,
                cancellation = cancellation
            )
            .getOrThrow()

        try {
            try {
                cancellation.throwIfCancelled()
                val executionRequest = route.executionRequest(request)
                val response = route.backend
                    .infer(route.installed, route.source, executionRequest)
                    .getOrThrow()
                cancellation.throwIfCancelled()
                require(response.modelId == route.installed.descriptor.id) {
                    "backend response model identity does not match selected route"
                }
                require(response.backendId == route.backend.id) {
                    "backend response id does not match selected route"
                }
                val validated = response.copy(selectedCapabilities = route.selectedCapabilities)
                routePlanner.recordSuccess(route, validated)
                validated
            } catch (error: Throwable) {
                if (error !is InferenceCancelledException) {
                    // Only a route whose backend actually started inference reaches this block.
                    routePlanner.recordFailure(route)
                }
                throw error
            }
        } finally {
            admitted?.lease?.close()
        }
    }


    /**
     * Executes the same governed route/admission path as [infer] while exposing transient chunks
     * when the selected backend supports [StreamingInferenceBackend].
     *
     * Chunks never change route selection, feedback, persistence or authority. Observer exceptions
     * are isolated from backend health. A blocking backend emits one complete text chunk only after
     * inference has finished, so AMPER never presents synthetic streaming as realtime generation.
     * Phase595 bounds the output budget of that blocking fallback so an interactive "streaming"
     * request cannot silently spend hundreds of mobile CPU tokens before exposing its first text.
     */
    fun inferStream(
        request: InferenceRequest,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> =
        inferStream(request, InferenceCancellationSignal(), onChunk)

    fun inferStream(
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> = runCatching {
        cancellation.throwIfCancelled()
        val baseBudget = governor?.currentBudget()
        baseBudget?.let(::reconcileResources)
        val preparedHint = claimCompatiblePreparedHandoff(request)
        val admitted = baseBudget?.let {
            planGovernedRoute(
                baseBudget = it,
                request = request,
                purpose = TitanRoutePurpose.INFERENCE,
                preparedHint = preparedHint,
                cancellation = cancellation
            )
        }
        val route = admitted?.value ?: routePlanner
            .plan(
                request = request,
                purpose = TitanRoutePurpose.INFERENCE,
                preparedHint = preparedHint,
                cancellation = cancellation
            )
            .getOrThrow()
        val executionRequest = TitanBlockingStreamFallbackPolicy.bound(
            backend = route.backend,
            request = route.executionRequest(request)
        )
        val streamedText = StringBuilder()
        var nextChunkIndex = 0
        var terminalSeen = false
        val governedObserver: (InferenceChunk) -> Unit = { chunk ->
            cancellation.throwIfCancelled()
            require(!terminalSeen) { "streaming backend emitted chunk after terminal marker" }
            require(chunk.index == nextChunkIndex) {
                "streaming backend chunk index mismatch: expected $nextChunkIndex, got ${chunk.index}"
            }
            nextChunkIndex += 1
            if (chunk.finished) {
                require(chunk.text.isEmpty()) { "terminal streaming chunk must not carry text" }
                terminalSeen = true
            } else {
                streamedText.append(chunk.text)
            }
            runCatching { onChunk(chunk) }
        }

        try {
            try {
                cancellation.throwIfCancelled()
                val response = when (val backend = route.backend) {
                    is CancellableStreamingInferenceBackend -> backend
                        .inferStream(
                            route.installed,
                            route.source,
                            executionRequest,
                            cancellation,
                            governedObserver
                        )
                        .getOrThrow()
                    is StreamingInferenceBackend -> backend
                        .inferStream(route.installed, route.source, executionRequest, governedObserver)
                        .getOrThrow()
                    else -> backend
                        .infer(route.installed, route.source, executionRequest)
                        .getOrThrow()
                        .also { completed ->
                            cancellation.throwIfCancelled()
                            if (completed.text.isNotEmpty()) {
                                governedObserver(InferenceChunk(completed.text, index = 0))
                            }
                            governedObserver(
                                InferenceChunk(
                                    text = "",
                                    index = if (completed.text.isEmpty()) 0 else 1,
                                    finished = true
                                )
                            )
                        }
                }
                cancellation.throwIfCancelled()
                require(terminalSeen) { "streaming execution completed without terminal marker" }
                require(streamedText.toString() == response.text) {
                    "streamed text does not match authoritative inference response"
                }
                require(response.modelId == route.installed.descriptor.id) {
                    "backend response model identity does not match selected route"
                }
                require(response.backendId == route.backend.id) {
                    "backend response id does not match selected route"
                }
                val validated = response.copy(selectedCapabilities = route.selectedCapabilities)
                routePlanner.recordSuccess(route, validated)
                validated
            } catch (error: Throwable) {
                if (error !is InferenceCancelledException) {
                    routePlanner.recordFailure(route)
                }
                throw error
            }
        } finally {
            admitted?.lease?.close()
        }
    }

    fun latestRouteObservation(): TitanRouteObservation? =
        routePlanner.latestObservation()

    fun recentRouteObservations(limit: Int = 16): List<TitanRouteObservation> =
        routePlanner.recentObservations(limit)

    fun reconcileResources(): Result<Set<ModelId>> = runCatching {
        val activeGovernor = requireNotNull(governor) {
            "resource reconciliation requires a resource governor"
        }
        reconcileResources(activeGovernor.currentBudget())
    }

    private fun planGovernedRoute(
        baseBudget: ResourceBudget,
        request: InferenceRequest,
        purpose: TitanRoutePurpose,
        preparedHint: TitanPreparedRouteHint? = null,
        cancellation: InferenceCancellationSignal? = null
    ): TitanAdmittedValue<TitanInferenceRoute> = boundedAdmissionReplanner.acquire(
        baseBudget = baseBudget,
        estimatedMemoryMb = { route -> route.estimatedMemoryMb },
        backendAdmission = { route -> backendExecutionAdmission(route.backend) }
    ) { availability ->
        cancellation?.throwIfCancelled()
        routePlanner
            .plan(
                request = request,
                budget = availability.budget,
                purpose = purpose,
                preparedHint = preparedHint,
                requireKnownMemoryEstimate = availability.requireKnownMemoryEstimate,
                saturatedBackendIds = availability.saturatedBackendIds,
                saturatedExecutionGroupIds = availability.saturatedExecutionGroupIds,
                cancellation = cancellation
            )
            .getOrThrow()
    }

    /**
     * Every governed execution carries backend identity, even when it has no finite concurrency
     * limit. Phase 109 uses that identity to make backend maintenance mutually exclusive with active
     * execution. Finite limits from Phase 108 remain unchanged.
     */
    private fun backendExecutionAdmission(
        backend: InferenceBackend
    ): TitanBackendExecutionAdmission = TitanBackendExecutionAdmission(
        backendId = backend.id,
        maxConcurrentExecutions = backend.executionGroupConcurrencyLimit(),
        executionGroupId = backend.executionGroupId()
    )

    private fun reconcileResources(budget: ResourceBudget): Set<ModelId> {
        val released = linkedSetOf<ModelId>()
        val failures = mutableListOf<String>()

        backends.list()
            .filterIsInstance<ResourceReclaimingInferenceBackend>()
            .forEach { backend ->
                val maintenanceLease = executionAdmission.tryAcquireBackendMaintenance(
                    backendId = backend.id,
                    executionGroupId = backend.executionGroupId()
                ) ?: return@forEach
                try {
                    val result = backend.reconcileResources(budget)
                    val failure = result.exceptionOrNull()
                    if (failure != null) {
                        failures += "${backend.id}:${failure.message ?: failure::class.java.simpleName}"
                    } else {
                        result.getOrThrow().releasedModelIds.forEach { modelId ->
                            released += modelId
                            clearPreparedHandoff(modelId)
                        }
                    }
                } finally {
                    maintenanceLease.close()
                }
            }

        if (failures.isNotEmpty()) {
            error("failed to reconcile Titan backend resources: ${failures.joinToString("; ")}")
        }
        return released.toSet()
    }

    fun unload(modelId: ModelId): Result<Unit> = runCatching {
        clearPreparedHandoff(modelId)
        val failures = backends.list()
            .filterIsInstance<ManagedInferenceBackend>()
            .mapNotNull { it.unload(modelId).exceptionOrNull() }
        if (failures.isNotEmpty()) {
            val summary = failures.joinToString("; ") { it.message ?: it::class.java.simpleName }
            error("failed to unload Titan backend state: $summary")
        }
    }

    fun unloadAll(): Result<Unit> = runCatching {
        clearPreparedHandoff()
        catalog.list()
            .map { it.descriptor.id }
            .distinct()
            .forEach { unload(it).getOrThrow() }
    }

    private fun claimCompatiblePreparedHandoff(request: InferenceRequest): TitanPreparedRouteHint? =
        synchronized(preparedHandoffLock) {
            val current = preparedHandoff ?: return@synchronized null
            if (!current.isRequestCompatible(request)) return@synchronized null
            preparedHandoff = null
            current
        }

    private fun clearPreparedHandoff(modelId: ModelId? = null) {
        synchronized(preparedHandoffLock) {
            val current = preparedHandoff ?: return@synchronized
            if (modelId == null || current.runtimeIdentity.modelId == modelId) {
                preparedHandoff = null
            }
        }
    }
}
