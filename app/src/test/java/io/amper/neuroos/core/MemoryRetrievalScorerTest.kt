package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalScorerTest {
    @Test
    fun recallUsesWholeTokensInsteadOfSubstringMatches() {
        val memory = PersistentMemoryOs(InMemoryMemoryJournal())
        val exact = record(
            id = "exact",
            content = "Deploy the neural runtime safely",
            importance = 0.55,
            createdAt = 10L
        )
        val substringOnly = record(
            id = "substring",
            content = "A runtime note about neuralized pipelines",
            importance = 1.0,
            createdAt = 20L
        )
        memory.remember(substringOnly)
        memory.remember(exact)

        assertEquals(listOf(exact), memory.recall("neural", limit = 8))
    }

    @Test
    fun phraseCoverageOutranksSingleTokenAndTieBreakIsDeterministic() {
        val memory = PersistentMemoryOs(InMemoryMemoryJournal())
        val phrase = record(
            id = "phrase",
            content = "Sovereign memory retrieval remains deterministic",
            importance = 0.50,
            createdAt = 10L
        )
        val scattered = record(
            id = "scattered",
            content = "Sovereign runtime with retrieval support for memory",
            importance = 0.50,
            createdAt = 20L
        )
        val partial = record(
            id = "partial",
            content = "Sovereign runtime only",
            importance = 0.90,
            createdAt = 30L
        )
        memory.remember(partial)
        memory.remember(scattered)
        memory.remember(phrase)

        assertEquals(
            listOf("phrase", "scattered", "partial"),
            memory.recall("sovereign memory retrieval", limit = 8).map { it.id.value }
        )
    }

    @Test
    fun provenanceCanMatchButContentMatchCarriesMoreWeight() {
        val content = record(
            id = "content",
            content = "camera observation accepted",
            importance = 0.40,
            createdAt = 10L
        )
        val provenance = MemoryRecord(
            id = MemoryId("provenance"),
            kind = "episodic",
            content = "sensor observation accepted",
            importance = 0.40,
            provenance = Provenance(source = "camera", producer = "perception", confidence = 1.0),
            createdAtEpochMs = 20L
        )

        val contentScore = MemoryRetrievalScorer.score(content, "camera")
        val provenanceScore = MemoryRetrievalScorer.score(provenance, "camera")
        assertTrue(contentScore.matched)
        assertTrue(provenanceScore.matched)
        assertTrue(contentScore.value > provenanceScore.value)
    }

    @Test
    fun emptyQueryStillReturnsImportanceRankedMemory() {
        val memory = PersistentMemoryOs(InMemoryMemoryJournal())
        val low = record("low", "low importance", 0.2, 20L)
        val high = record("high", "high importance", 0.9, 10L)
        memory.remember(low)
        memory.remember(high)

        assertEquals(listOf("high", "low"), memory.recall("   ", 8).map { it.id.value })
    }

    private fun record(
        id: String,
        content: String,
        importance: Double,
        createdAt: Long
    ): MemoryRecord = MemoryRecord(
        id = MemoryId(id),
        kind = "episodic",
        content = content,
        importance = importance,
        provenance = Provenance(source = "user", producer = "test", confidence = 0.8),
        createdAtEpochMs = createdAt
    )
}
