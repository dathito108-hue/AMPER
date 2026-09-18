package io.amper.neuroos.core

import java.util.UUID

@JvmInline value class LearningId(val value: String)

data class LearningEpisode(
    val id: LearningId = LearningId(UUID.randomUUID().toString()),
    val topic: String,
    val observation: String,
    val outcomeScore: Double,
    val provenance: Provenance
) {
    init {
        require(topic.isNotBlank())
        require(observation.isNotBlank())
        require(outcomeScore in -1.0..1.0)
    }
}

data class ConsolidationResult(
    val topic: String,
    val evidenceCount: Int,
    val memory: MemoryRecord?
)

interface LearningConsolidator {
    fun record(episode: LearningEpisode): MemoryRecord
    fun consolidate(topic: String, minEvidence: Int = 2): ConsolidationResult
}

class EvidenceLearningConsolidator(private val memoryOs: MemoryOs) : LearningConsolidator {
    override fun record(episode: LearningEpisode): MemoryRecord {
        val record = MemoryRecord(
            kind = "learning-episode",
            content = "topic=${episode.topic};observation=${episode.observation};outcome=${episode.outcomeScore}",
            importance = ((episode.outcomeScore + 1.0) / 2.0).coerceIn(0.2, 1.0),
            provenance = episode.provenance
        )
        memoryOs.remember(record)
        return record
    }

    override fun consolidate(topic: String, minEvidence: Int): ConsolidationResult {
        require(topic.isNotBlank())
        require(minEvidence > 0)
        val evidence = memoryOs.recall(topic, limit = 64)
            .filter { it.kind == "learning-episode" && it.content.contains("topic=$topic", ignoreCase = true) }
        if (evidence.size < minEvidence) {
            return ConsolidationResult(topic = topic, evidenceCount = evidence.size, memory = null)
        }

        val averageImportance = evidence.map { it.importance }.average()
        val averageConfidence = evidence.map { it.provenance.confidence }.average()
        val consolidated = MemoryRecord(
            kind = "consolidated-pattern",
            content = "topic=$topic;evidence=${evidence.size};avgImportance=${"%.4f".format(java.util.Locale.US, averageImportance)}",
            importance = averageImportance.coerceIn(0.0, 1.0),
            provenance = Provenance(
                source = "learning-consolidation",
                producer = "evidence-consolidator",
                confidence = averageConfidence.coerceIn(0.0, 1.0),
                parents = evidence.map { it.id }.toSet()
            )
        )
        memoryOs.remember(consolidated)
        return ConsolidationResult(topic = topic, evidenceCount = evidence.size, memory = consolidated)
    }
}
