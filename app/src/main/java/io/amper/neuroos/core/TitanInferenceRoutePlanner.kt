package io.amper.neuroos.core

enum class TitanRoutePurpose { INFERENCE, PREPARATION }

data class TitanPreparedRouteHint(
    val runtimeIdentity: ModelRuntimeIdentity,
    val backendId: String,
    val selectedCapabilities: Set<CapabilityId>,
    val maxOutputTokens: Int,
    val temperature: Double,
    val sessionRoutingPreference: TitanSessionRoutingPreference
) {
    fun isRequestCompatible(request: InferenceRequest): Boolean =
        request.maxOutputTokens == maxOutputTokens &&
            request.temperature == temperature &&
            request.sessionRoutingPreference == sessionRoutingPreference &&
            selectedCapabilities in request.capabilityProfiles()

    fun matches(route: TitanInferenceRoute): Boolean =
        route.runtimeIdentity == runtimeIdentity &&
            route.backend.id == backendId &&
            route.selectedCapabilities == selectedCapabilities

    companion object {
        fun from(route: TitanInferenceRoute, request: InferenceRequest): TitanPreparedRouteHint =
            TitanPreparedRouteHint(
                runtimeIdentity = route.runtimeIdentity,
                backendId = route.backend.id,
                selectedCapabilities = route.selectedCapabilities.toSet(),
                maxOutputTokens = request.maxOutputTokens,
                temperature = request.temperature,
                sessionRoutingPreference = request.sessionRoutingPreference
            )
    }
}

data class TitanInferenceRoute(
    val descriptor: ModelDescriptor,
    val installed: InstalledModel,
    val source: ModelArtifactSource,
    val backend: InferenceBackend,
    val runtimeIdentity: ModelRuntimeIdentity,
    val budget: ResourceBudget?,
    val selectedCapabilities: Set<CapabilityId> = descriptor.capabilities,
    val workloadClass: TitanInferenceWorkloadClass = TitanInferenceWorkloadClass.DEFAULT,
    val resourceCondition: TitanResourceConditionClass = TitanResourceConditionClass.UNGOVERNED,
    val backendPolicyScore: Int? = null,
    /** Positive governed memory estimate frozen during backend admission; null means unknown/ungoverned. */
    val estimatedMemoryMb: Int? = null
) {
    fun executionRequest(original: InferenceRequest): InferenceRequest {
        require(descriptor.capabilities.containsAll(selectedCapabilities)) {
            "selected capabilities are not supported by routed model"
        }
        return original.bindSelectedCapabilities(selectedCapabilities)
    }
}

private data class RankedTitanRoute(
    val ordinal: Int,
    val route: TitanInferenceRoute,
    val feedbackPenalty: Int,
    val throughputSamples: Int,
    val throughputEwma: Double?
)

private data class PlannedBackendCandidate(
    val backend: InferenceBackend,
    val policyScore: Int?,
    val estimatedMemoryMb: Int?
)

private data class TitanResourceStabilityScope(
    val selectedCapabilities: Set<CapabilityId>,
    val workloadClass: TitanInferenceWorkloadClass
)

private data class TitanExplorationScope(
    val selectedCapabilities: Set<CapabilityId>,
    val workloadClass: TitanInferenceWorkloadClass,
    val resourceCondition: TitanResourceConditionClass
)

/**
 * Canonical executable-route planner shared by Titan inference and any agent execution path.
 *
 * Capability-profile priority remains absolute. Inside one profile, bounded process-local outcome
 * feedback ranks already-feasible routes. Phase 88 adds deterministic bounded exploration, Phase 89
 * scopes request-shape feedback, Phase 90 isolates exploration cadence by capability/workload,
 * Phase 91 isolates feedback/exploration by coarse mobile resource condition, Phase 92 stabilizes
 * that resource identity with asymmetric hysteresis, Phase 93 makes hysteresis state itself
 * capability/workload-local, and Phase 94 adds low-frequency deterministic probation so a route
 * whose old penalty has expired from quarantine can eventually demonstrate recovery instead of
 * remaining permanently starved behind a healthy alternative. Phase 97 makes backend feasibility
 * request-aware even without a ResourceGovernor. Phase 99 carries the governed backend policy score
 * across model boundaries so equivalent-feedback routes are ranked as complete model/backend pairs.
 * Phase 101 adds a PREPARATION purpose, Phase 102 adds a one-shot prepared-route handoff, Phase
 * 105 carries the frozen governed memory estimate into the selected route. Phase 106 lets runtime
 * planning apply the remaining process-local concurrent budget and, when another execution is
 * active, require a known memory estimate so an impossible unknown-cost route cannot hide a smaller
 * feasible fallback. Phase 108 additionally excludes backend ids whose declared execution capacity
 * is already saturated before backend policy/session-affinity evaluation, preventing a single-session
 * backend from becoming a hidden queue while leaving capability and feedback invariants unchanged.
 * Phase 116 adds a soft turn-local model-continuity preference only to normal healthy ranking;
 * prepared handoff, feedback deferral/penalty, probation, exploration and feasibility remain stronger.
 * Phase 139 separates an explicit user-selected model preference from continuity. User preference
 * wins over continuity inside that same normal healthy ranking, while all stronger gates above remain
 * unchanged and can still bypass the preferred model. Phase 140 additionally binds the request's
 * session-ranking policy into prepared handoff compatibility so preparation cannot pin a route chosen
 * under a different warm-session policy. Phase 144 records bounded read-only route observations after
 * the decision is made; observability never participates in ranking, admission or execution.
 */
class TitanInferenceRoutePlanner(
    private val models: ModelRegistry,
    private val catalog: InstalledModelCatalog,
    private val artifacts: ModelArtifactResolver,
    private val backends: InferenceBackendRegistry,
    private val feedback: TitanRuntimeFeedback = TitanRuntimeFeedback(),
    private val explorationEveryPlanningPasses: Int = 4,
    private val minThroughputSamplesForRanking: Int = 2,
    private val maxExplorationScopes: Int = 64,
    private val resourceConditionStabilizer: TitanResourceConditionStabilizer = TitanResourceConditionStabilizer(),
    private val maxResourceStabilityScopes: Int = 64,
    private val probationEveryPlanningPasses: Int = 16,
    private val observatory: TitanRouteObservatory = TitanRouteObservatory()
) {
    init {
        require(explorationEveryPlanningPasses > 0)
        require(minThroughputSamplesForRanking > 0)
        require(maxExplorationScopes > 0)
        require(maxResourceStabilityScopes > 0)
        require(probationEveryPlanningPasses > 0)
    }

    private val planningPassesByScope = linkedMapOf<TitanExplorationScope, Long>()
    private val resourceStabilizersByScope =
        linkedMapOf<TitanResourceStabilityScope, TitanResourceConditionStabilizer>()

    @Synchronized
    fun plan(
        request: InferenceRequest,
        budget: ResourceBudget? = null,
        purpose: TitanRoutePurpose = TitanRoutePurpose.INFERENCE,
        preparedHint: TitanPreparedRouteHint? = null,
        requireKnownMemoryEstimate: Boolean = false,
        saturatedBackendIds: Set<String> = emptySet(),
        saturatedExecutionGroupIds: Set<String> = emptySet(),
        cancellation: InferenceCancellationSignal? = null
    ): Result<TitanInferenceRoute> {
        val workloadClass = TitanInferenceWorkloadClass.from(request)
        val preferredModelId = request.effectivePreferredModelId()
        val rejected = mutableListOf<String>()
        var lastResourceCondition = TitanResourceConditionClass.UNGOVERNED
        var observationRecorded = false

        val result = runCatching {
            cancellation?.throwIfCancelled()
            require(!requireKnownMemoryEstimate || budget != null) {
                "known-memory routing requires a governed budget"
            }
            val applicablePreparedHint = preparedHint?.takeIf {
                purpose == TitanRoutePurpose.INFERENCE && it.isRequestCompatible(request)
            }
            val deferredForPlan = mutableSetOf<TitanRouteFeedbackKey>()
            var sawCandidate = false

        for (profile in request.capabilityProfiles()) {
            cancellation?.throwIfCancelled()
            val candidates = models.candidates(profile)
            if (candidates.isEmpty()) {
                rejected += "${renderProfile(profile)}:no-model"
                continue
            }
            sawCandidate = true

            val stabilityScope = TitanResourceStabilityScope(
                selectedCapabilities = profile.toSet(),
                workloadClass = workloadClass
            )
            val resourceCondition = stabilizedResourceCondition(stabilityScope, budget)
            lastResourceCondition = resourceCondition
            val feasible = mutableListOf<RankedTitanRoute>()
            var ordinal = 0

            for (descriptor in candidates) {
                cancellation?.throwIfCancelled()
                val installed = catalog.get(descriptor.id)
                if (installed == null) {
                    rejected += "${descriptor.id.value}:not-installed"
                    continue
                }
                if (installed.descriptor != descriptor) {
                    rejected += "${descriptor.id.value}:descriptor-mismatch"
                    continue
                }

                val source = artifacts.resolve(installed)
                if (source == null) {
                    rejected += "${descriptor.id.value}:artifact-unavailable"
                    continue
                }

                val identity = runCatching { ModelRuntimeIdentity.bind(installed, source) }
                cancellation?.throwIfCancelled()
                if (identity.isFailure) {
                    rejected += "${descriptor.id.value}:artifact-identity-mismatch"
                    continue
                }

                val backendCandidates = if (budget != null) {
                    backends.evaluatedCandidates(
                        model = installed,
                        request = request,
                        budget = budget,
                        requireKnownMemoryEstimate = requireKnownMemoryEstimate,
                        excludedBackendIds = saturatedBackendIds,
                        excludedExecutionGroupIds = saturatedExecutionGroupIds,
                        onRejected = { backendReason ->
                            rejected += "${descriptor.id.value}/$backendReason"
                        }
                    ).map {
                        PlannedBackendCandidate(
                            backend = it.backend,
                            policyScore = it.policyScore,
                            estimatedMemoryMb = it.estimatedMemoryMb
                        )
                    }
                } else {
                    backends.candidates(installed, request)
                        .filterNot {
                            it.id in saturatedBackendIds ||
                                it.executionGroupId() in saturatedExecutionGroupIds
                        }
                        .map {
                            PlannedBackendCandidate(
                                backend = it,
                                policyScore = null,
                                estimatedMemoryMb = null
                            )
                        }
                }.filter { candidate ->
                    purpose != TitanRoutePurpose.PREPARATION ||
                        candidate.backend is PreparableInferenceBackend
                }
                // Backend policy may perform exact warm tokenizer preflight. Some native helpers are
                // blocking, so cancellation is re-checked immediately after they return and the
                // computed route is discarded instead of becoming authoritative.
                cancellation?.throwIfCancelled()

                if (backendCandidates.isEmpty()) {
                    rejected += if (purpose == TitanRoutePurpose.PREPARATION) {
                        "${descriptor.id.value}:no-preparable-backend"
                    } else {
                        "${descriptor.id.value}:no-eligible-backend"
                    }
                    continue
                }

                for (candidate in backendCandidates) {
                    cancellation?.throwIfCancelled()
                    val backend = candidate.backend
                    val route = TitanInferenceRoute(
                        descriptor = descriptor,
                        installed = installed,
                        source = source,
                        backend = backend,
                        runtimeIdentity = identity.getOrThrow(),
                        budget = budget,
                        selectedCapabilities = profile,
                        workloadClass = workloadClass,
                        resourceCondition = resourceCondition,
                        backendPolicyScore = candidate.policyScore,
                        estimatedMemoryMb = candidate.estimatedMemoryMb
                    )
                    val key = TitanRouteFeedbackKey.from(route)
                    val deferred = purpose == TitanRoutePurpose.INFERENCE &&
                        (key in deferredForPlan || feedback.consumeDeferral(key))
                    if (deferred) {
                        deferredForPlan += key
                        rejected += "${descriptor.id.value}/${backend.id}:feedback-deferred"
                        continue
                    }
                    val snapshot = feedback.snapshot(key)
                    feasible += RankedTitanRoute(
                        ordinal = ordinal++,
                        route = route,
                        feedbackPenalty = snapshot.scorePenalty,
                        throughputSamples = snapshot.throughputSamples,
                        throughputEwma = snapshot.throughputEwma
                    )
                }
            }

            if (feasible.isNotEmpty()) {
                if (purpose == TitanRoutePurpose.PREPARATION) {
                    feasible.minWithOrNull(routeComparator(preferredModelId))
                        ?.let { ranked ->
                            observationRecorded = true
                            return@runCatching recordSelection(
                                request = request,
                                purpose = purpose,
                                route = ranked.route,
                                preferredModelId = preferredModelId,
                                reason = TitanRouteSelectionReason.PREPARATION_RANKING,
                                rejected = rejected,
                                cancellation = cancellation
                            )
                        }
                }

                val scope = TitanExplorationScope(
                    selectedCapabilities = profile.toSet(),
                    workloadClass = workloadClass,
                    resourceCondition = resourceCondition
                )
                cancellation?.throwIfCancelled()
                val planningPass = nextPlanningPass(scope)

                applicablePreparedHint
                    ?.takeIf { it.selectedCapabilities == profile }
                    ?.let { hint ->
                        feasible.firstOrNull { ranked ->
                            ranked.feedbackPenalty == 0 && hint.matches(ranked.route)
                        }?.let { ranked ->
                            observationRecorded = true
                            return@runCatching recordSelection(
                                request = request,
                                purpose = purpose,
                                route = ranked.route,
                                preferredModelId = preferredModelId,
                                reason = TitanRouteSelectionReason.PREPARED_HANDOFF,
                                rejected = rejected,
                                cancellation = cancellation
                            )
                        }
                    }

                probationCandidate(feasible, planningPass)?.let { ranked ->
                    observationRecorded = true
                    return@runCatching recordSelection(
                        request = request,
                        purpose = purpose,
                        route = ranked.route,
                        preferredModelId = preferredModelId,
                        reason = TitanRouteSelectionReason.PROBATION,
                        rejected = rejected,
                        cancellation = cancellation
                    )
                }

                val exploreThisPass = planningPass % explorationEveryPlanningPasses.toLong() == 0L
                if (exploreThisPass) {
                    feasible.asSequence()
                        .filter { it.feedbackPenalty == 0 }
                        .filter { it.throughputSamples < minThroughputSamplesForRanking }
                        .minWithOrNull(
                            compareBy<RankedTitanRoute> { it.throughputSamples }
                                .thenBy { it.ordinal }
                        )
                        ?.let { ranked ->
                            observationRecorded = true
                            return@runCatching recordSelection(
                                request = request,
                                purpose = purpose,
                                route = ranked.route,
                                preferredModelId = preferredModelId,
                                reason = TitanRouteSelectionReason.EXPLORATION,
                                rejected = rejected,
                                cancellation = cancellation
                            )
                        }
                }

                feasible.minWithOrNull(routeComparator(preferredModelId))
                    ?.let { ranked ->
                        observationRecorded = true
                        return@runCatching recordSelection(
                            request = request,
                            purpose = purpose,
                            route = ranked.route,
                            preferredModelId = preferredModelId,
                            reason = TitanRouteSelectionReason.NORMAL_RANKING,
                            rejected = rejected,
                            cancellation = cancellation
                        )
                    }
            }
        }

        val baseline = request.requiredCapabilities.map { it.value }.sorted()
        if (!sawCandidate) {
            error("no model satisfies $baseline; rejected=${rejected.joinToString(",")}")
        }
        error(
            "no feasible model route for capabilities $baseline" +
                if (rejected.isEmpty()) "" else "; rejected=${rejected.joinToString(",")}"
        )
        }

        if (
            result.isFailure &&
            !observationRecorded &&
            result.exceptionOrNull() !is InferenceCancelledException
        ) {
            runCatching {
                observatory.record(
                    TitanRouteObservation(
                        purpose = purpose,
                        requiredCapabilities = request.requiredCapabilities.toSet(),
                        preferredModelId = preferredModelId,
                        selectedModelId = null,
                        selectedBackendId = null,
                        selectedCapabilities = emptySet(),
                        selectionReason = null,
                        workloadClass = workloadClass,
                        resourceCondition = lastResourceCondition,
                        estimatedMemoryMb = null,
                        backendPolicyScore = null,
                        rejected = boundedRejections(rejected, preferredModelId),
                        failure = result.exceptionOrNull()?.message
                            ?.take(512)
                            ?.takeIf { it.isNotBlank() }
                            ?: "route planning failed"
                    )
                )
            }
        }
        return result
    }

    fun latestObservation(): TitanRouteObservation? = observatory.latest()

    fun recentObservations(limit: Int = 16): List<TitanRouteObservation> =
        observatory.recent(limit)

    private fun recordSelection(
        request: InferenceRequest,
        purpose: TitanRoutePurpose,
        route: TitanInferenceRoute,
        preferredModelId: ModelId?,
        reason: TitanRouteSelectionReason,
        rejected: List<String>,
        cancellation: InferenceCancellationSignal?
    ): TitanInferenceRoute {
        cancellation?.throwIfCancelled()
        runCatching {
            observatory.record(
                TitanRouteObservation(
                    purpose = purpose,
                    requiredCapabilities = request.requiredCapabilities.toSet(),
                    preferredModelId = preferredModelId,
                    selectedModelId = route.descriptor.id,
                    selectedBackendId = route.backend.id,
                    selectedCapabilities = route.selectedCapabilities.toSet(),
                    selectionReason = reason,
                    workloadClass = route.workloadClass,
                    resourceCondition = route.resourceCondition,
                    estimatedMemoryMb = route.estimatedMemoryMb,
                    backendPolicyScore = route.backendPolicyScore,
                    rejected = boundedRejections(rejected, preferredModelId)
                )
            )
        }
        return route
    }

    private fun boundedRejections(
        rejected: List<String>,
        preferredModelId: ModelId?
    ): List<String> {
        if (rejected.size <= TitanRouteObservation.MAX_REJECTIONS) return rejected.toList()
        val preferred = preferredModelId?.value
        val prioritized = if (preferred == null) {
            emptyList()
        } else {
            rejected.filter {
                it.startsWith("$preferred:") || it.startsWith("$preferred/")
            }
        }
        return (prioritized + rejected)
            .distinct()
            .take(TitanRouteObservation.MAX_REJECTIONS)
    }

    fun recordFailure(route: TitanInferenceRoute) {
        feedback.recordFailure(TitanRouteFeedbackKey.from(route))
    }

    fun recordSuccess(route: TitanInferenceRoute, response: InferenceResponse) {
        feedback.recordSuccess(TitanRouteFeedbackKey.from(route), response)
    }

    private fun probationCandidate(
        feasible: List<RankedTitanRoute>,
        planningPass: Long
    ): RankedTitanRoute? {
        if (planningPass % probationEveryPlanningPasses.toLong() != 0L) return null
        val penalized = feasible
            .asSequence()
            .filter { it.feedbackPenalty > 0 }
            .sortedBy { it.ordinal }
            .toList()
        if (penalized.isEmpty()) return null

        val probationRound = planningPass / probationEveryPlanningPasses.toLong()
        val index = ((probationRound - 1L) % penalized.size.toLong()).toInt()
        return penalized[index]
    }

    private fun stabilizedResourceCondition(
        scope: TitanResourceStabilityScope,
        budget: ResourceBudget?
    ): TitanResourceConditionClass {
        val stabilizer = resourceStabilizersByScope.remove(scope)
            ?: resourceConditionStabilizer.newSibling()
        val condition = stabilizer.observe(budget)
        resourceStabilizersByScope[scope] = stabilizer
        while (resourceStabilizersByScope.size > maxResourceStabilityScopes) {
            val eldest = resourceStabilizersByScope.keys.firstOrNull() ?: break
            resourceStabilizersByScope.remove(eldest)
        }
        return condition
    }

    private fun nextPlanningPass(scope: TitanExplorationScope): Long {
        val current = planningPassesByScope.remove(scope) ?: 0L
        val next = if (current == Long.MAX_VALUE) 1L else current + 1L
        planningPassesByScope[scope] = next
        while (planningPassesByScope.size > maxExplorationScopes) {
            val eldest = planningPassesByScope.keys.firstOrNull() ?: break
            planningPassesByScope.remove(eldest)
        }
        return next
    }

    private fun routeComparator(preferredModelId: ModelId?): Comparator<RankedTitanRoute> = Comparator { left, right ->
        val penalty = left.feedbackPenalty.compareTo(right.feedbackPenalty)
        if (penalty != 0) return@Comparator penalty

        if (preferredModelId != null) {
            val leftPreferred = left.route.descriptor.id == preferredModelId
            val rightPreferred = right.route.descriptor.id == preferredModelId
            if (leftPreferred != rightPreferred) {
                return@Comparator if (leftPreferred) -1 else 1
            }
        }

        val leftConfident = left.throughputSamples >= minThroughputSamplesForRanking && left.throughputEwma != null
        val rightConfident = right.throughputSamples >= minThroughputSamplesForRanking && right.throughputEwma != null
        if (leftConfident && rightConfident) {
            val throughput = right.throughputEwma!!.compareTo(left.throughputEwma!!)
            if (throughput != 0) return@Comparator throughput
        }

        val leftPolicyScore = left.route.backendPolicyScore
        val rightPolicyScore = right.route.backendPolicyScore
        if (leftPolicyScore != null && rightPolicyScore != null) {
            val policy = rightPolicyScore.compareTo(leftPolicyScore)
            if (policy != 0) return@Comparator policy
        }

        left.ordinal.compareTo(right.ordinal)
    }

    private fun renderProfile(profile: Set<CapabilityId>): String =
        profile.map { it.value }.sorted().joinToString(prefix = "[", postfix = "]", separator = "+")
}
