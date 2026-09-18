package io.amper.neuroos.core

enum class TitanRouteSelectionReason {
    PREPARATION_RANKING,
    PREPARED_HANDOFF,
    PROBATION,
    EXPLORATION,
    NORMAL_RANKING
}

/**
 * Read-only process-local explanation of one Titan route-planning decision.
 *
 * Raw prompt text, tool input and model output are deliberately excluded. This surface records only
 * routing identities, coarse workload/resource classes, bounded rejection reasons and the selected
 * route. It never participates in route ranking or execution admission.
 */
data class TitanRouteObservation(
    val purpose: TitanRoutePurpose,
    val requiredCapabilities: Set<CapabilityId>,
    val preferredModelId: ModelId?,
    val selectedModelId: ModelId?,
    val selectedBackendId: String?,
    val selectedCapabilities: Set<CapabilityId>,
    val selectionReason: TitanRouteSelectionReason?,
    val workloadClass: TitanInferenceWorkloadClass,
    val resourceCondition: TitanResourceConditionClass,
    val estimatedMemoryMb: Int?,
    val backendPolicyScore: Int?,
    val rejected: List<String>,
    val failure: String? = null,
    val observedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(requiredCapabilities.isNotEmpty())
        require(rejected.size <= MAX_REJECTIONS)
        selectedBackendId?.let { require(it.isNotBlank()) }
        failure?.let { require(it.isNotBlank()) }
        if (selectedModelId == null) {
            require(selectedBackendId == null)
            require(selectedCapabilities.isEmpty())
            require(selectionReason == null)
        } else {
            require(selectedBackendId != null)
            require(selectedCapabilities.isNotEmpty())
            require(selectionReason != null)
        }
    }

    fun preferredModelOutcome(): String? {
        val preferred = preferredModelId ?: return null
        if (selectedModelId == preferred) return "preferred-selected"
        val prefix = preferred.value + ":"
        val backendPrefix = preferred.value + "/"
        val rejection = rejected.firstOrNull {
            it.startsWith(prefix) || it.startsWith(backendPrefix)
        }
        return rejection ?: if (selectedModelId != null) {
            "preferred-bypassed-by-${selectionReason?.name?.lowercase() ?: "routing"}"
        } else {
            "preferred-not-selected"
        }
    }

    companion object {
        const val MAX_REJECTIONS = 24
    }
}

class TitanRouteObservatory(
    private val maxEntries: Int = 16
) {
    init { require(maxEntries > 0) }

    private val observations = ArrayDeque<TitanRouteObservation>()

    @Synchronized
    fun record(observation: TitanRouteObservation) {
        observations.addLast(observation)
        while (observations.size > maxEntries) observations.removeFirst()
    }

    @Synchronized
    fun latest(): TitanRouteObservation? = observations.lastOrNull()

    @Synchronized
    fun recent(limit: Int = maxEntries): List<TitanRouteObservation> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return observations.takeLast(limit)
    }

    @Synchronized
    fun clear() {
        observations.clear()
    }
}
