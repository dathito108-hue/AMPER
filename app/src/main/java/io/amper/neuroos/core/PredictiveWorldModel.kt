package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.max

data class WorldStateKey(
    val entity: String,
    val attribute: String
) {
    init {
        require(entity.isNotBlank())
        require(attribute.isNotBlank())
    }

    val canonical: String
        get() = "${entity.trim().lowercase()}::${attribute.trim().lowercase()}"
}

enum class StructuredWorldStateStatus {
    KNOWN,
    UNKNOWN
}

data class WorldStateObservation(
    val key: WorldStateKey,
    val value: String,
    val confidence: Double,
    val evidenceIds: List<MemoryId>,
    val observedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(value.isNotBlank())
        require(confidence in 0.0..1.0)
        require(evidenceIds.isNotEmpty())
        require(observedAtEpochMs >= 0L)
    }
}

data class StructuredWorldState(
    val id: MemoryId,
    val key: WorldStateKey,
    val value: String?,
    val confidence: Double,
    val status: StructuredWorldStateStatus,
    val evidenceIds: List<MemoryId>,
    val observedAtEpochMs: Long
) {
    init {
        require(confidence in 0.0..1.0)
        require(evidenceIds.isNotEmpty())
        require(observedAtEpochMs >= 0L)
        require(status != StructuredWorldStateStatus.KNOWN || !value.isNullOrBlank()) {
            "known world state requires a value"
        }
        require(status != StructuredWorldStateStatus.UNKNOWN || value == null) {
            "unknown world state cannot retain a planning value"
        }
    }
}

data class TemporalWorldTransition(
    val id: MemoryId,
    val key: WorldStateKey,
    val fromValue: String?,
    val toValue: String?,
    val confidence: Double,
    val startedAtEpochMs: Long,
    val observedAtEpochMs: Long,
    val sourceStateId: MemoryId,
    val targetStateId: MemoryId
) {
    init {
        require(confidence in 0.0..1.0)
        require(startedAtEpochMs >= 0L)
        require(observedAtEpochMs >= startedAtEpochMs)
        require(fromValue != toValue) { "transition must change world state" }
    }
}

data class CausalWorldHypothesis(
    val causeKey: WorldStateKey,
    val causeValue: String,
    val effectKey: WorldStateKey,
    val effectValue: String,
    val support: Int,
    val contradictions: Int,
    val confidence: Double,
    val meanLagMs: Long
) {
    init {
        require(causeValue.isNotBlank())
        require(effectValue.isNotBlank())
        require(support > 0)
        require(contradictions >= 0)
        require(confidence in 0.0..1.0)
        require(meanLagMs >= 0L)
    }
}

enum class WorldPredictionBasis {
    TEMPORAL_TRANSITION,
    CAUSAL_HYPOTHESIS,
    PERSISTENCE_PRIOR
}

data class WorldPrediction(
    val id: MemoryId,
    val targetKey: WorldStateKey,
    val predictedValue: String,
    val confidence: Double,
    val basis: WorldPredictionBasis,
    val generatedAtEpochMs: Long,
    val horizonMs: Long,
    val sourceStateId: MemoryId
) {
    init {
        require(predictedValue.isNotBlank())
        require(confidence in 0.0..1.0)
        require(generatedAtEpochMs >= 0L)
        require(horizonMs > 0L)
    }
}

enum class WorldPredictionOutcomeStatus {
    CONFIRMED,
    DISCONFIRMED,
    EXPIRED
}

data class WorldPredictionOutcome(
    val id: MemoryId,
    val predictionId: MemoryId,
    val status: WorldPredictionOutcomeStatus,
    val actualValue: String?,
    val observedAtEpochMs: Long
) {
    init {
        require(observedAtEpochMs >= 0L)
        require(status == WorldPredictionOutcomeStatus.EXPIRED || !actualValue.isNullOrBlank()) {
            "evaluated prediction outcome requires actual value"
        }
    }
}

interface PredictiveWorldModel {
    fun observe(observation: WorldStateObservation): StructuredWorldState

    fun invalidate(
        key: WorldStateKey,
        evidenceIds: List<MemoryId>,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ): StructuredWorldState

    fun current(key: WorldStateKey): StructuredWorldState?
    fun queryStates(query: String, limit: Int = 8): List<StructuredWorldState>
    fun recentStates(limit: Int = 16): List<StructuredWorldState>
    fun transitions(key: WorldStateKey, limit: Int = 32): List<TemporalWorldTransition>
    fun causalHypotheses(effectKey: WorldStateKey, limit: Int = 8): List<CausalWorldHypothesis>

    fun predict(
        targetKey: WorldStateKey,
        horizonMs: Long = MemoryBackedPredictiveWorldModel.DEFAULT_PREDICTION_HORIZON_MS
    ): WorldPrediction?

    fun predictionOutcomes(limit: Int = 32): List<WorldPredictionOutcome>
}

/**
 * Phase191-195 predictive world model.
 *
 * Phase191 stores queryable structured entity/attribute state.
 * Phase192 records immutable temporal transitions whenever state changes.
 * Phase193 derives explicitly-labelled causal hypotheses from repeated temporal evidence; correlation
 * never becomes authority or a guaranteed causal fact.
 * Phase194 produces bounded predictions from temporal transition evidence, causal hypotheses or a
 * persistence prior.
 * Phase195 evaluates predictions against later observations and calibrates future confidence from
 * confirmed/disconfirmed history. Prediction evidence is cognitive data only and cannot grant tool
 * permission or approve side effects.
 */
class MemoryBackedPredictiveWorldModel(
    private val memory: MemoryOs,
    private val clock: () -> Long = System::currentTimeMillis
) : PredictiveWorldModel {
    override fun observe(observation: WorldStateObservation): StructuredWorldState {
        val current = current(observation.key)
        if (
            current != null &&
            current.status == StructuredWorldStateStatus.KNOWN &&
            normalize(current.value.orEmpty()) == normalize(observation.value) &&
            current.evidenceIds.toSet() == observation.evidenceIds.toSet()
        ) {
            return current
        }

        val observedAt = maxOf(
            observation.observedAtEpochMs,
            (current?.observedAtEpochMs ?: Long.MIN_VALUE) + 1L
        )
        val next = persistState(
            key = observation.key,
            value = observation.value,
            confidence = observation.confidence,
            status = StructuredWorldStateStatus.KNOWN,
            evidenceIds = observation.evidenceIds,
            observedAtEpochMs = observedAt
        )
        persistTransitionIfChanged(current, next)
        reconcilePredictions(next)
        return next
    }

    override fun invalidate(
        key: WorldStateKey,
        evidenceIds: List<MemoryId>,
        observedAtEpochMs: Long
    ): StructuredWorldState {
        require(evidenceIds.isNotEmpty())
        val current = current(key)
        if (
            current != null &&
            current.status == StructuredWorldStateStatus.UNKNOWN &&
            current.evidenceIds.toSet() == evidenceIds.toSet()
        ) {
            return current
        }
        val at = maxOf(
            observedAtEpochMs,
            (current?.observedAtEpochMs ?: Long.MIN_VALUE) + 1L
        )
        val next = persistState(
            key = key,
            value = null,
            confidence = 0.0,
            status = StructuredWorldStateStatus.UNKNOWN,
            evidenceIds = evidenceIds,
            observedAtEpochMs = at
        )
        persistTransitionIfChanged(current, next)
        reconcilePredictions(next)
        return next
    }

    override fun current(key: WorldStateKey): StructuredWorldState? =
        loadStates(key).maxWithOrNull(STATE_ORDER)

    override fun queryStates(query: String, limit: Int): List<StructuredWorldState> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall(query, (limit * 12).coerceAtLeast(64))
            .asSequence()
            .filter { it.kind == STATE_KIND }
            .mapNotNull(PredictiveWorldCodec::decodeState)
            .groupBy { it.key.canonical }
            .values
            .mapNotNull { it.maxWithOrNull(STATE_ORDER) }
            .sortedWith(
                compareByDescending<StructuredWorldState> { it.observedAtEpochMs }
                    .thenByDescending { it.confidence }
                    .thenBy { it.key.canonical }
            )
            .take(limit)
            .toList()
    }

    override fun recentStates(limit: Int): List<StructuredWorldState> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall("", MAX_RECORD_SCAN)
            .asSequence()
            .filter { it.kind == STATE_KIND }
            .mapNotNull(PredictiveWorldCodec::decodeState)
            .groupBy { it.key.canonical }
            .values
            .mapNotNull { it.maxWithOrNull(STATE_ORDER) }
            .sortedWith(
                compareByDescending<StructuredWorldState> { it.observedAtEpochMs }
                    .thenByDescending { it.confidence }
                    .thenBy { it.key.canonical }
            )
            .take(limit)
            .toList()
    }

    override fun transitions(key: WorldStateKey, limit: Int): List<TemporalWorldTransition> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return loadTransitions()
            .asSequence()
            .filter { it.key.canonical == key.canonical }
            .sortedByDescending { it.observedAtEpochMs }
            .take(limit)
            .toList()
    }

    override fun causalHypotheses(
        effectKey: WorldStateKey,
        limit: Int
    ): List<CausalWorldHypothesis> {
        require(limit >= 0)
        if (limit == 0) return emptyList()

        val all = loadTransitions()
        val effects = all.filter {
            it.key.canonical == effectKey.canonical &&
                it.confidence > 0.0 &&
                !it.toValue.isNullOrBlank()
        }
        if (effects.isEmpty()) return emptyList()

        val candidates = linkedSetOf<CausalPattern>()
        effects.forEach { effect ->
            all.asSequence()
                .filter { cause ->
                    cause.key.canonical != effectKey.canonical &&
                        cause.confidence > 0.0 &&
                        !cause.toValue.isNullOrBlank() &&
                        cause.observedAtEpochMs < effect.observedAtEpochMs &&
                        effect.observedAtEpochMs - cause.observedAtEpochMs <= MAX_CAUSAL_LAG_MS
                }
                .forEach { cause ->
                    candidates += CausalPattern(
                        causeKey = cause.key,
                        causeValue = requireNotNull(cause.toValue),
                        effectValue = requireNotNull(effect.toValue)
                    )
                }
        }

        return candidates.mapNotNull { pattern ->
            val occurrences = all.filter {
                it.key.canonical == pattern.causeKey.canonical &&
                    it.confidence > 0.0 &&
                    normalize(it.toValue.orEmpty()) == normalize(pattern.causeValue)
            }
            if (occurrences.isEmpty()) return@mapNotNull null

            val evidenceByOccurrence = occurrences.map { cause ->
                val lag = effects.asSequence()
                    .filter {
                        normalize(it.toValue.orEmpty()) == normalize(pattern.effectValue) &&
                            it.observedAtEpochMs > cause.observedAtEpochMs
                    }
                    .map { it.observedAtEpochMs - cause.observedAtEpochMs }
                    .filter { it <= MAX_CAUSAL_LAG_MS }
                    .minOrNull()
                cause to lag
            }
            val matchedLags = evidenceByOccurrence.mapNotNull { it.second }
            val support = matchedLags.size
            val now = clock()
            val contradictions = evidenceByOccurrence.count { (cause, lag) ->
                lag == null && now - cause.observedAtEpochMs >= MAX_CAUSAL_LAG_MS
            }
            if (support < MIN_CAUSAL_SUPPORT) return@mapNotNull null
            val confidence = (support + 1.0) / (support + contradictions + 2.0)
            CausalWorldHypothesis(
                causeKey = pattern.causeKey,
                causeValue = pattern.causeValue,
                effectKey = effectKey,
                effectValue = pattern.effectValue,
                support = support,
                contradictions = contradictions,
                confidence = confidence.coerceIn(0.0, 1.0),
                meanLagMs = matchedLags.average().toLong().coerceAtLeast(0L)
            )
        }
            .sortedWith(
                compareByDescending<CausalWorldHypothesis> { it.confidence }
                    .thenByDescending { it.support }
                    .thenBy { it.causeKey.canonical }
                    .thenBy { it.effectValue }
            )
            .take(limit)
    }

    override fun predict(targetKey: WorldStateKey, horizonMs: Long): WorldPrediction? {
        require(horizonMs > 0L)
        val current = current(targetKey)
            ?.takeIf { it.status == StructuredWorldStateStatus.KNOWN && !it.value.isNullOrBlank() }
            ?: return null
        val currentValue = requireNotNull(current.value)

        val temporal = transitions(targetKey, MAX_TRANSITION_SCAN)
            .filter {
                normalize(it.fromValue.orEmpty()) == normalize(currentValue) &&
                    !it.toValue.isNullOrBlank()
            }
            .groupBy { normalize(requireNotNull(it.toValue)) }
            .map { (_, samples) ->
                val exemplar = samples.maxByOrNull { it.observedAtEpochMs }!!
                val support = samples.sumOf { it.confidence }
                Triple(requireNotNull(exemplar.toValue), support, samples.size)
            }
            .maxWithOrNull(
                compareBy<Triple<String, Double, Int>> { it.second }
                    .thenBy { it.first }
            )

        val temporalCandidate = temporal?.let { (value, support, count) ->
            val denominator = transitions(targetKey, MAX_TRANSITION_SCAN)
                .filter {
                    normalize(it.fromValue.orEmpty()) == normalize(currentValue) &&
                        !it.toValue.isNullOrBlank()
                }
                .sumOf { it.confidence }
            PredictionCandidate(
                value = value,
                confidence = ((support + 1.0) / (denominator + 2.0)).coerceIn(0.0, 1.0),
                basis = WorldPredictionBasis.TEMPORAL_TRANSITION,
                evidenceCount = count
            )
        }

        val causalCandidate = causalHypotheses(targetKey, 8)
            .asSequence()
            .filter { hypothesis ->
                val cause = current(hypothesis.causeKey)
                cause?.status == StructuredWorldStateStatus.KNOWN &&
                    normalize(cause.value.orEmpty()) == normalize(hypothesis.causeValue)
            }
            .map {
                PredictionCandidate(
                    value = it.effectValue,
                    confidence = it.confidence,
                    basis = WorldPredictionBasis.CAUSAL_HYPOTHESIS,
                    evidenceCount = it.support
                )
            }
            .maxByOrNull { it.confidence }

        val persistence = PredictionCandidate(
            value = currentValue,
            confidence = PERSISTENCE_PRIOR_CONFIDENCE,
            basis = WorldPredictionBasis.PERSISTENCE_PRIOR,
            evidenceCount = 0
        )
        val selected = listOfNotNull(temporalCandidate, causalCandidate, persistence)
            .maxWithOrNull(
                compareBy<PredictionCandidate> { it.confidence }
                    .thenBy { it.evidenceCount }
                    .thenBy { it.basis.name }
            ) ?: return null

        val generatedAt = maxOf(clock(), current.observedAtEpochMs)
        val calibrated = (
            selected.confidence * calibrationFactor(targetKey, selected.basis)
        ).coerceIn(MIN_PREDICTION_CONFIDENCE, 1.0)

        findEquivalentPendingPrediction(
            key = targetKey,
            predictedValue = selected.value,
            basis = selected.basis,
            sourceStateId = current.id,
            horizonMs = horizonMs,
            now = generatedAt
        )?.let { return it }

        val provisional = WorldPrediction(
            id = MemoryId("pending"),
            targetKey = targetKey,
            predictedValue = selected.value,
            confidence = calibrated,
            basis = selected.basis,
            generatedAtEpochMs = generatedAt,
            horizonMs = horizonMs,
            sourceStateId = current.id
        )
        val record = MemoryRecord(
            kind = PREDICTION_KIND,
            content = PredictiveWorldCodec.encodePrediction(provisional),
            importance = calibrated.coerceIn(0.35, 0.90),
            provenance = Provenance(
                source = "predictive-world-model",
                producer = "world-predictor",
                observedAtEpochMs = generatedAt,
                confidence = calibrated,
                parents = setOf(current.id)
            ),
            createdAtEpochMs = generatedAt
        )
        memory.remember(record)
        return provisional.copy(id = record.id)
    }

    override fun predictionOutcomes(limit: Int): List<WorldPredictionOutcome> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall("", MAX_RECORD_SCAN)
            .asSequence()
            .filter { it.kind == OUTCOME_KIND }
            .mapNotNull(PredictiveWorldCodec::decodeOutcome)
            .sortedByDescending { it.observedAtEpochMs }
            .take(limit)
            .toList()
    }

    private fun persistState(
        key: WorldStateKey,
        value: String?,
        confidence: Double,
        status: StructuredWorldStateStatus,
        evidenceIds: List<MemoryId>,
        observedAtEpochMs: Long
    ): StructuredWorldState {
        val provisional = StructuredWorldState(
            id = MemoryId("pending"),
            key = key,
            value = value,
            confidence = confidence.coerceIn(0.0, 1.0),
            status = status,
            evidenceIds = evidenceIds.distinct().sortedBy { it.value },
            observedAtEpochMs = observedAtEpochMs
        )
        val record = MemoryRecord(
            kind = STATE_KIND,
            content = PredictiveWorldCodec.encodeState(provisional),
            importance = when (status) {
                StructuredWorldStateStatus.KNOWN -> confidence.coerceIn(0.50, 1.0)
                StructuredWorldStateStatus.UNKNOWN -> 0.70
            },
            provenance = Provenance(
                source = "structured-world-state",
                producer = "predictive-world-model",
                observedAtEpochMs = observedAtEpochMs,
                confidence = confidence.coerceIn(0.0, 1.0),
                parents = evidenceIds.toSet()
            ),
            createdAtEpochMs = observedAtEpochMs
        )
        memory.remember(record)
        return provisional.copy(id = record.id)
    }

    private fun persistTransitionIfChanged(
        previous: StructuredWorldState?,
        next: StructuredWorldState
    ) {
        if (previous == null) return
        if (
            previous.status == next.status &&
            normalize(previous.value.orEmpty()) == normalize(next.value.orEmpty())
        ) return

        val confidence = minOf(previous.confidence, next.confidence).coerceIn(0.0, 1.0)
        val provisional = TemporalWorldTransition(
            id = MemoryId("pending"),
            key = next.key,
            fromValue = previous.value,
            toValue = next.value,
            confidence = confidence,
            startedAtEpochMs = previous.observedAtEpochMs,
            observedAtEpochMs = next.observedAtEpochMs,
            sourceStateId = previous.id,
            targetStateId = next.id
        )
        val record = MemoryRecord(
            kind = TRANSITION_KIND,
            content = PredictiveWorldCodec.encodeTransition(provisional),
            importance = confidence.coerceIn(0.40, 0.90),
            provenance = Provenance(
                source = "temporal-world-transition",
                producer = "predictive-world-model",
                observedAtEpochMs = next.observedAtEpochMs,
                confidence = confidence,
                parents = setOf(previous.id, next.id)
            ),
            createdAtEpochMs = next.observedAtEpochMs
        )
        memory.remember(record)
    }

    private fun reconcilePredictions(state: StructuredWorldState) {
        val outcomes = predictionOutcomes(MAX_RECORD_SCAN).map { it.predictionId }.toHashSet()
        loadPredictions(state.key)
            .asSequence()
            .filter { it.id !in outcomes && it.generatedAtEpochMs < state.observedAtEpochMs }
            .forEach { prediction ->
                val outcomeStatus = when {
                    state.observedAtEpochMs > prediction.generatedAtEpochMs + prediction.horizonMs ->
                        WorldPredictionOutcomeStatus.EXPIRED
                    state.status != StructuredWorldStateStatus.KNOWN ->
                        WorldPredictionOutcomeStatus.EXPIRED
                    normalize(prediction.predictedValue) == normalize(state.value.orEmpty()) ->
                        WorldPredictionOutcomeStatus.CONFIRMED
                    else -> WorldPredictionOutcomeStatus.DISCONFIRMED
                }
                persistOutcome(
                    prediction = prediction,
                    status = outcomeStatus,
                    actualValue = state.value,
                    observedAtEpochMs = state.observedAtEpochMs,
                    stateId = state.id
                )
            }
    }

    private fun persistOutcome(
        prediction: WorldPrediction,
        status: WorldPredictionOutcomeStatus,
        actualValue: String?,
        observedAtEpochMs: Long,
        stateId: MemoryId
    ): WorldPredictionOutcome {
        val provisional = WorldPredictionOutcome(
            id = MemoryId("pending"),
            predictionId = prediction.id,
            status = status,
            actualValue = if (status == WorldPredictionOutcomeStatus.EXPIRED) null else actualValue,
            observedAtEpochMs = observedAtEpochMs
        )
        val record = MemoryRecord(
            kind = OUTCOME_KIND,
            content = PredictiveWorldCodec.encodeOutcome(provisional, prediction.targetKey),
            importance = 0.65,
            provenance = Provenance(
                source = "world-prediction-evaluation",
                producer = "predictive-world-model",
                observedAtEpochMs = observedAtEpochMs,
                confidence = 1.0,
                parents = setOf(prediction.id, stateId)
            ),
            createdAtEpochMs = observedAtEpochMs
        )
        memory.remember(record)
        return provisional.copy(id = record.id)
    }

    private fun calibrationFactor(
        key: WorldStateKey,
        basis: WorldPredictionBasis
    ): Double {
        val predictions = loadPredictions(key).associateBy { it.id }
        val relevant = predictionOutcomes(MAX_RECORD_SCAN).filter { outcome ->
            val prediction = predictions[outcome.predictionId]
            prediction != null &&
                prediction.basis == basis &&
                outcome.status != WorldPredictionOutcomeStatus.EXPIRED
        }
        if (relevant.isEmpty()) return 1.0
        val confirmed = relevant.count { it.status == WorldPredictionOutcomeStatus.CONFIRMED }
        val accuracy = (confirmed + 1.0) / (relevant.size + 2.0)
        return (0.75 + accuracy * 0.25).coerceIn(0.75, 1.0)
    }

    private fun findEquivalentPendingPrediction(
        key: WorldStateKey,
        predictedValue: String,
        basis: WorldPredictionBasis,
        sourceStateId: MemoryId,
        horizonMs: Long,
        now: Long
    ): WorldPrediction? {
        val outcomes = predictionOutcomes(MAX_RECORD_SCAN).map { it.predictionId }.toHashSet()
        return loadPredictions(key)
            .asSequence()
            .filter {
                it.id !in outcomes &&
                    it.sourceStateId == sourceStateId &&
                    it.basis == basis &&
                    normalize(it.predictedValue) == normalize(predictedValue) &&
                    it.horizonMs == horizonMs &&
                    now <= it.generatedAtEpochMs + it.horizonMs
            }
            .maxByOrNull { it.generatedAtEpochMs }
    }

    private fun loadStates(key: WorldStateKey): List<StructuredWorldState> =
        memory.recall("${key.entity} ${key.attribute}", MAX_RECORD_SCAN)
            .asSequence()
            .filter { it.kind == STATE_KIND }
            .mapNotNull(PredictiveWorldCodec::decodeState)
            .filter { it.key == key }
            .toList()

    private fun loadTransitions(): List<TemporalWorldTransition> =
        memory.recall("", MAX_RECORD_SCAN)
            .asSequence()
            .filter { it.kind == TRANSITION_KIND }
            .mapNotNull(PredictiveWorldCodec::decodeTransition)
            .toList()

    private fun loadPredictions(key: WorldStateKey): List<WorldPrediction> =
        memory.recall("${key.entity} ${key.attribute}", MAX_RECORD_SCAN)
            .asSequence()
            .filter { it.kind == PREDICTION_KIND }
            .mapNotNull(PredictiveWorldCodec::decodePrediction)
            .filter { it.targetKey.canonical == key.canonical }
            .toList()

    private fun normalize(value: String): String = value.trim().lowercase()

    private data class CausalPattern(
        val causeKey: WorldStateKey,
        val causeValue: String,
        val effectValue: String
    )

    private data class PredictionCandidate(
        val value: String,
        val confidence: Double,
        val basis: WorldPredictionBasis,
        val evidenceCount: Int
    )

    companion object {
        const val DEFAULT_PREDICTION_HORIZON_MS = 5L * 60L * 1000L
        const val STATE_KIND = "predictive-world-state"
        const val TRANSITION_KIND = "predictive-world-transition"
        const val PREDICTION_KIND = "predictive-world-prediction"
        const val OUTCOME_KIND = "predictive-world-prediction-outcome"

        private const val MAX_RECORD_SCAN = 512
        private const val MAX_TRANSITION_SCAN = 128
        private const val MAX_CAUSAL_LAG_MS = 10L * 60L * 1000L
        private const val MIN_CAUSAL_SUPPORT = 2
        private const val PERSISTENCE_PRIOR_CONFIDENCE = 0.50
        private const val MIN_PREDICTION_CONFIDENCE = 0.05

        private val STATE_ORDER = compareBy<StructuredWorldState> { it.observedAtEpochMs }
            .thenBy { it.id.value }
    }
}

internal object PredictiveWorldCodec {
    fun encodeState(state: StructuredWorldState): String = buildString {
        append("search=")
        append(searchable(state.key.entity))
        append(' ')
        append(searchable(state.key.attribute))
        append(' ')
        append(searchable(state.value.orEmpty()))
        append('\n')
        append("payload=")
        append(
            listOf(
                enc(state.key.entity),
                enc(state.key.attribute),
                state.value?.let(::enc) ?: "~",
                enc(state.confidence.toString()),
                state.status.name,
                encodeIds(state.evidenceIds),
                state.observedAtEpochMs.toString()
            ).joinToString("|")
        )
    }

    fun decodeState(record: MemoryRecord): StructuredWorldState? = runCatching {
        val p = payload(record).split('|')
        require(p.size == 7)
        StructuredWorldState(
            id = record.id,
            key = WorldStateKey(dec(p[0]), dec(p[1])),
            value = p[2].takeUnless { it == "~" }?.let(::dec),
            confidence = dec(p[3]).toDouble(),
            status = StructuredWorldStateStatus.valueOf(p[4]),
            evidenceIds = decodeIds(p[5]),
            observedAtEpochMs = p[6].toLong()
        )
    }.getOrNull()

    fun encodeTransition(transition: TemporalWorldTransition): String = buildString {
        append("search=")
        append(searchable(transition.key.entity))
        append(' ')
        append(searchable(transition.key.attribute))
        append(' ')
        append(searchable(transition.fromValue.orEmpty()))
        append(' ')
        append(searchable(transition.toValue.orEmpty()))
        append('\n')
        append("payload=")
        append(
            listOf(
                enc(transition.key.entity),
                enc(transition.key.attribute),
                transition.fromValue?.let(::enc) ?: "~",
                transition.toValue?.let(::enc) ?: "~",
                enc(transition.confidence.toString()),
                transition.startedAtEpochMs.toString(),
                transition.observedAtEpochMs.toString(),
                enc(transition.sourceStateId.value),
                enc(transition.targetStateId.value)
            ).joinToString("|")
        )
    }

    fun decodeTransition(record: MemoryRecord): TemporalWorldTransition? = runCatching {
        val p = payload(record).split('|')
        require(p.size == 9)
        TemporalWorldTransition(
            id = record.id,
            key = WorldStateKey(dec(p[0]), dec(p[1])),
            fromValue = p[2].takeUnless { it == "~" }?.let(::dec),
            toValue = p[3].takeUnless { it == "~" }?.let(::dec),
            confidence = dec(p[4]).toDouble(),
            startedAtEpochMs = p[5].toLong(),
            observedAtEpochMs = p[6].toLong(),
            sourceStateId = MemoryId(dec(p[7])),
            targetStateId = MemoryId(dec(p[8]))
        )
    }.getOrNull()

    fun encodePrediction(prediction: WorldPrediction): String = buildString {
        append("search=")
        append(searchable(prediction.targetKey.entity))
        append(' ')
        append(searchable(prediction.targetKey.attribute))
        append(' ')
        append(searchable(prediction.predictedValue))
        append('\n')
        append("payload=")
        append(
            listOf(
                enc(prediction.targetKey.entity),
                enc(prediction.targetKey.attribute),
                enc(prediction.predictedValue),
                enc(prediction.confidence.toString()),
                prediction.basis.name,
                prediction.generatedAtEpochMs.toString(),
                prediction.horizonMs.toString(),
                enc(prediction.sourceStateId.value)
            ).joinToString("|")
        )
    }

    fun decodePrediction(record: MemoryRecord): WorldPrediction? = runCatching {
        val p = payload(record).split('|')
        require(p.size == 8)
        WorldPrediction(
            id = record.id,
            targetKey = WorldStateKey(dec(p[0]), dec(p[1])),
            predictedValue = dec(p[2]),
            confidence = dec(p[3]).toDouble(),
            basis = WorldPredictionBasis.valueOf(p[4]),
            generatedAtEpochMs = p[5].toLong(),
            horizonMs = p[6].toLong(),
            sourceStateId = MemoryId(dec(p[7]))
        )
    }.getOrNull()

    fun encodeOutcome(
        outcome: WorldPredictionOutcome,
        key: WorldStateKey
    ): String = buildString {
        append("search=")
        append(searchable(key.entity))
        append(' ')
        append(searchable(key.attribute))
        append(' ')
        append(searchable(outcome.actualValue.orEmpty()))
        append('\n')
        append("payload=")
        append(
            listOf(
                enc(outcome.predictionId.value),
                outcome.status.name,
                outcome.actualValue?.let(::enc) ?: "~",
                outcome.observedAtEpochMs.toString()
            ).joinToString("|")
        )
    }

    fun decodeOutcome(record: MemoryRecord): WorldPredictionOutcome? = runCatching {
        val p = payload(record).split('|')
        require(p.size == 4)
        WorldPredictionOutcome(
            id = record.id,
            predictionId = MemoryId(dec(p[0])),
            status = WorldPredictionOutcomeStatus.valueOf(p[1]),
            actualValue = p[2].takeUnless { it == "~" }?.let(::dec),
            observedAtEpochMs = p[3].toLong()
        )
    }.getOrNull()

    private fun payload(record: MemoryRecord): String =
        record.content.lineSequence()
            .first { it.startsWith("payload=") }
            .removePrefix("payload=")

    private fun encodeIds(ids: List<MemoryId>): String =
        ids.distinct().sortedBy { it.value }.joinToString(",") { enc(it.value) }

    private fun decodeIds(value: String): List<MemoryId> {
        require(value.isNotBlank())
        return value.split(',').map { MemoryId(dec(it)) }
    }

    private fun searchable(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(160)

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
