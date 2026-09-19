package io.amper.neuroos.core

data class CognitiveExecutiveObservationRequest(
    val query: String,
    val preferredModalities: List<PerceptionModality>,
    val cognitiveStateDigest: String,
    val executionContextDigest: String,
    val unresolvedBeliefCount: Int
) {
    init {
        require(query.isNotBlank() && query.length <= MAX_QUERY_CHARS)
        require(preferredModalities.distinct().size == preferredModalities.size)
        require(cognitiveStateDigest.matches(SHA256))
        require(executionContextDigest.matches(SHA256))
        require(unresolvedBeliefCount >= 0)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MAX_QUERY_CHARS = 1_024
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Bounded perception-acquisition surface for the autonomous cognitive executive.
 *
 * Implementations may only use device/perception authority already granted by the host platform.
 * They must not request new Android permissions, start a new MediaProjection consent flow, execute
 * ToolFabric actions, or persist raw image/audio bytes.
 *
 * If [publishesToPerceptionBus] is true, [acquire] must have already published the returned percept
 * into the same runtime cognitive world. Otherwise the executive publishes it through its injected
 * [PerceptionBus] before recapturing cognition.
 */
interface CognitiveExecutiveObservationPort {
    val publishesToPerceptionBus: Boolean
        get() = false

    fun acquire(request: CognitiveExecutiveObservationRequest): Result<Percept>
}

internal fun sanitizeObservationFailure(error: Throwable?): String {
    val raw = error?.javaClass?.simpleName
        ?.uppercase()
        ?.replace(Regex("[^A-Z0-9_:-]"), "_")
        ?.take(96)
        ?.ifBlank { null }
    return raw ?: "OBSERVATION_UNAVAILABLE"
}
