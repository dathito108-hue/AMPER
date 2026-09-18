package io.amper.neuroos.core

/**
 * Deterministic lexical retrieval signal for sovereign long-term memory.
 *
 * Phase 66 deliberately stays model-independent: it upgrades token/phrase/provenance ranking
 * without changing the durable memory record or journal formats. Exact Unicode-aware tokens
 * are used instead of substring-only matching, so a query term cannot score merely because it
 * appears inside an unrelated word. Embedding/vector retrieval can be layered on later.
 */
internal object MemoryRetrievalScorer {
    private val TOKEN = Regex("[\\p{L}\\p{N}_-]+")

    internal data class Score(
        val value: Double,
        val matched: Boolean
    )

    fun score(record: MemoryRecord, query: String): Score {
        val normalizedQuery = query.trim().lowercase()
        val queryTokens = tokens(normalizedQuery).distinct()
        if (queryTokens.isEmpty()) {
            return Score(
                value = record.importance + record.provenance.confidence * 0.10,
                matched = true
            )
        }

        val contentNormalized = record.content.lowercase()
        val contentTokens = tokens(contentNormalized).toHashSet()
        val kindTokens = tokens(record.kind.lowercase()).toHashSet()
        val sourceTokens = tokens(record.provenance.source.lowercase()).toHashSet()
        val producerTokens = tokens(record.provenance.producer.lowercase()).toHashSet()

        var matchedTokens = 0
        var lexical = 0.0
        queryTokens.forEach { term ->
            val weight = when {
                term in contentTokens -> 1.00
                term in kindTokens -> 0.70
                term in sourceTokens || term in producerTokens -> 0.45
                else -> 0.0
            }
            if (weight > 0.0) {
                matchedTokens += 1
                lexical += weight
            }
        }

        val coverage = matchedTokens.toDouble() / queryTokens.size.toDouble()
        val phraseBonus = if (
            queryTokens.size > 1 &&
            containsTokenPhrase(contentNormalized, queryTokens)
        ) 0.75 else 0.0

        val matched = matchedTokens > 0
        return Score(
            value = lexical + coverage * 0.50 + phraseBonus +
                record.importance * 0.30 + record.provenance.confidence * 0.15,
            matched = matched
        )
    }

    private fun tokens(value: String): List<String> = TOKEN.findAll(value)
        .map { it.value.lowercase() }
        .toList()

    private fun containsTokenPhrase(content: String, queryTokens: List<String>): Boolean {
        val contentSequence = tokens(content)
        if (queryTokens.size > contentSequence.size) return false
        return contentSequence.windowed(queryTokens.size).any { it == queryTokens }
    }
}
