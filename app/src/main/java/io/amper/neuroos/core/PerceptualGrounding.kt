package io.amper.neuroos.core

import java.util.Locale

enum class PerceptualFreshness {
    FRESH,
    RECENT,
    STALE
}

data class PerceptualGroundingEvidence(
    val modality: PerceptionModality,
    val summary: String,
    val source: String,
    val producer: String,
    val confidence: Double,
    val observedAtEpochMs: Long,
    val ageMs: Long,
    val freshness: PerceptualFreshness,
    val queryRelevance: Double
) {
    init {
        require(summary.isNotBlank() && summary.length <= MAX_SUMMARY_CHARS)
        require(source.isNotBlank() && source.length <= MAX_LABEL_CHARS)
        require(producer.isNotBlank() && producer.length <= MAX_LABEL_CHARS)
        require(confidence in 0.0..1.0)
        require(observedAtEpochMs >= 0L)
        require(ageMs >= 0L)
        require(queryRelevance in 0.0..1.0)
    }

    val planningEligible: Boolean
        get() = freshness != PerceptualFreshness.STALE && confidence >= MIN_PLANNING_CONFIDENCE

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MAX_SUMMARY_CHARS = 384
        const val MAX_LABEL_CHARS = 128
        const val MIN_PLANNING_CONFIDENCE = 0.50
    }
}

interface PerceptualGroundingSource {
    fun capture(
        query: String,
        limit: Int = 6
    ): List<PerceptualGroundingEvidence>
}

/**
 * Phase261-265 live perceptual grounding.
 *
 * Phase261 projects canonical perception.* world facts into typed, bounded percept evidence.
 * Phase262 preserves provenance/confidence and never touches raw attachment bytes.
 * Phase263 classifies freshness so stale live observations cannot silently remain planning facts.
 * Phase264 exposes bounded query relevance and planning eligibility for cognitive fusion.
 * Phase265 lets the integrated cognitive packet bind planner and critic to the same percept snapshot.
 */
class WorldBackedPerceptualGroundingSource(
    private val world: WorldModel,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshAfterMs: Long = DEFAULT_FRESH_MS,
    private val staleAfterMs: Long = DEFAULT_STALE_MS
) : PerceptualGroundingSource {
    init {
        require(freshAfterMs > 0L)
        require(staleAfterMs > freshAfterMs)
    }

    override fun capture(
        query: String,
        limit: Int
    ): List<PerceptualGroundingEvidence> {
        require(query.isNotBlank())
        require(limit >= 0)
        if (limit == 0) return emptyList()

        val now = clock()
        return world.query("", MAX_SCAN)
            .asSequence()
            .filter { it.subject.startsWith(PERCEPTION_PREFIX) }
            .mapNotNull { fact -> toEvidence(fact, query, now) }
            .distinctBy { evidence ->
                evidence.modality.name + "|" + evidence.source.lowercase(Locale.ROOT)
            }
            .sortedWith(
                compareByDescending<PerceptualGroundingEvidence> { it.planningEligible }
                    .thenByDescending { it.queryRelevance }
                    .thenBy { freshnessRank(it.freshness) }
                    .thenByDescending { it.confidence }
                    .thenBy { it.ageMs }
                    .thenBy { it.modality.name }
            )
            .take(limit)
            .toList()
    }

    private fun toEvidence(
        fact: WorldFact,
        query: String,
        now: Long
    ): PerceptualGroundingEvidence? {
        val modalityName = fact.subject.removePrefix(PERCEPTION_PREFIX)
            .trim()
            .uppercase(Locale.ROOT)
        val modality = runCatching { PerceptionModality.valueOf(modalityName) }.getOrNull()
            ?: return null
        val age = (now - fact.provenance.observedAtEpochMs).coerceAtLeast(0L)
        val freshness = when {
            age <= freshAfterMs -> PerceptualFreshness.FRESH
            age <= staleAfterMs -> PerceptualFreshness.RECENT
            else -> PerceptualFreshness.STALE
        }
        return PerceptualGroundingEvidence(
            modality = modality,
            summary = boundedText(fact.statement, PerceptualGroundingEvidence.MAX_SUMMARY_CHARS),
            source = boundedText(fact.provenance.source, PerceptualGroundingEvidence.MAX_LABEL_CHARS),
            producer = boundedText(fact.provenance.producer, PerceptualGroundingEvidence.MAX_LABEL_CHARS),
            confidence = fact.confidence.coerceIn(0.0, 1.0),
            observedAtEpochMs = fact.provenance.observedAtEpochMs.coerceAtLeast(0L),
            ageMs = age,
            freshness = freshness,
            queryRelevance = relevance(query, modality, fact.statement)
        )
    }

    private fun relevance(
        query: String,
        modality: PerceptionModality,
        summary: String
    ): Double {
        val normalizedQuery = query.lowercase(Locale.ROOT)
        val queryTokens = TOKENS.findAll(normalizedQuery).map { it.value }.toSet()
        val summaryTokens = TOKENS.findAll(summary.lowercase(Locale.ROOT)).map { it.value }.toSet()
        val overlap = if (queryTokens.isEmpty()) {
            0.0
        } else {
            queryTokens.count { it in summaryTokens }.toDouble() /
                queryTokens.size.coerceAtMost(6).toDouble()
        }
        val modalityCue = modalityCues[modality].orEmpty().any(normalizedQuery::contains)
        val liveCue = liveCues.any(normalizedQuery::contains)
        return maxOf(
            overlap.coerceIn(0.0, 1.0),
            if (modalityCue) 0.85 else 0.0,
            if (liveCue) 0.55 else 0.0,
            BASE_RECENT_RELEVANCE
        ).coerceIn(0.0, 1.0)
    }

    private fun boundedText(value: String, limit: Int): String =
        value.replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(limit)
            .ifBlank { "unknown" }

    private fun freshnessRank(value: PerceptualFreshness): Int = when (value) {
        PerceptualFreshness.FRESH -> 0
        PerceptualFreshness.RECENT -> 1
        PerceptualFreshness.STALE -> 2
    }

    companion object {
        const val DEFAULT_FRESH_MS = 15_000L
        const val DEFAULT_STALE_MS = 120_000L
        const val MAX_EVIDENCE = 6

        private const val MAX_SCAN = 32
        private const val PERCEPTION_PREFIX = "perception."
        private const val BASE_RECENT_RELEVANCE = 0.15
        private val TOKENS = Regex("""[\p{L}\p{N}._:-]+""")
        private val liveCues = listOf(
            "now",
            "current",
            "live",
            "device",
            "right now",
            "hiện tại",
            "bây giờ",
            "thiết bị"
        )
        private val modalityCues = mapOf(
            PerceptionModality.IMAGE to listOf("image", "picture", "ảnh", "hình"),
            PerceptionModality.CAMERA to listOf("camera", "see", "look", "nhìn", "thấy"),
            PerceptionModality.SCREEN to listOf("screen", "display", "màn hình"),
            PerceptionModality.AUDIO to listOf("audio", "sound", "hear", "mic", "âm thanh", "nghe"),
            PerceptionModality.SENSOR to listOf(
                "sensor",
                "accelerometer",
                "gyroscope",
                "light",
                "proximity",
                "cảm biến"
            ),
            PerceptionModality.TEXT to listOf("text", "văn bản")
        )
    }
}
