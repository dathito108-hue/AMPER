package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalEpisodicMemoryStoreTest {
    @Test
    fun admitsBoundedUserIntentIntoSameMemoryOs() {
        val memory = InMemoryMemoryOs()
        val store = CanonicalEpisodicMemoryStore(
            memory,
            EpisodicMemoryPolicy(maxContentChars = 64)
        )
        val result = store.admit(
            observation("x".repeat(200), 100L)
        ).getOrThrow()

        assertEquals(EpisodicAdmissionStatus.ADMITTED, result.status)
        assertEquals(1, memory.size())
        assertEquals(64, requireNotNull(result.entry).content.length)
        assertEquals(CanonicalEpisodicMemoryStore.KIND, memory.get(result.entry.id)?.kind)
    }

    @Test
    fun duplicateWithinWindowDoesNotCreateSecondRecord() {
        val memory = InMemoryMemoryOs()
        val store = CanonicalEpisodicMemoryStore(memory)

        assertEquals(
            EpisodicAdmissionStatus.ADMITTED,
            store.admit(observation("same event", 100L)).getOrThrow().status
        )
        assertEquals(
            EpisodicAdmissionStatus.DUPLICATE,
            store.admit(observation("same event", 101L)).getOrThrow().status
        )
        assertEquals(1, memory.size())
    }

    @Test
    fun sameContentOutsideDuplicateWindowCanBecomeNewEpisode() {
        val memory = InMemoryMemoryOs()
        val store = CanonicalEpisodicMemoryStore(
            memory,
            EpisodicMemoryPolicy(duplicateWindowMs = 10L)
        )
        store.admit(observation("repeat later", 100L)).getOrThrow()
        val later = store.admit(observation("repeat later", 111L)).getOrThrow()

        assertEquals(EpisodicAdmissionStatus.ADMITTED, later.status)
        assertEquals(2, memory.size())
    }

    @Test
    fun retentionDropsWeakCandidateWithoutWriteChurn() {
        val memory = InMemoryMemoryOs()
        val store = CanonicalEpisodicMemoryStore(
            memory,
            EpisodicMemoryPolicy(
                maxEpisodes = 2,
                minImportance = 0.0,
                maxScanRecords = 16
            )
        )
        store.admit(observation("high", 100L, 0.9)).getOrThrow()
        store.admit(observation("mid", 101L, 0.7)).getOrThrow()
        val weak = store.admit(observation("weak", 102L, 0.1)).getOrThrow()

        assertEquals(EpisodicAdmissionStatus.RETENTION_DROPPED, weak.status)
        assertEquals(
            setOf("high", "mid"),
            store.recent(limit = 8).getOrThrow().map { it.content }.toSet()
        )
        assertEquals(2, memory.size())
    }

    @Test
    fun strongerEpisodeEvictsWeakestOldEpisode() {
        val memory = InMemoryMemoryOs()
        val store = CanonicalEpisodicMemoryStore(
            memory,
            EpisodicMemoryPolicy(
                maxEpisodes = 2,
                minImportance = 0.0,
                maxScanRecords = 16
            )
        )
        store.admit(observation("weak", 100L, 0.2)).getOrThrow()
        store.admit(observation("mid", 101L, 0.7)).getOrThrow()
        store.admit(observation("high", 102L, 0.9)).getOrThrow()

        assertEquals(
            setOf("mid", "high"),
            store.recent(limit = 8).getOrThrow().map { it.content }.toSet()
        )
        assertEquals(2, memory.size())
    }

    @Test
    fun conversationSemanticAndProceduralOriginsAreNeverCopied() {
        val memory = InMemoryMemoryOs()
        val store = CanonicalEpisodicMemoryStore(memory)
        listOf(
            EpisodicMemoryOrigin.CONVERSATION_TURN,
            EpisodicMemoryOrigin.SEMANTIC_KNOWLEDGE,
            EpisodicMemoryOrigin.PROCEDURAL_MEMORY
        ).forEach { origin ->
            val result = store.admit(
                observation(
                    content = origin.name,
                    observedAtEpochMs = origin.ordinal.toLong() + 1L,
                    origin = origin
                )
            ).getOrThrow()
            assertEquals(EpisodicAdmissionStatus.DISALLOWED_ORIGIN, result.status)
        }
        assertEquals(0, memory.size())
    }

    @Test
    fun recentProjectionIgnoresNonEpisodicMemoryKinds() {
        val memory = InMemoryMemoryOs()
        val store = CanonicalEpisodicMemoryStore(memory)
        store.admit(observation("episodic needle", 100L)).getOrThrow()
        memory.remember(
            MemoryRecord(
                kind = "conversation-turn",
                content = "needle conversation",
                importance = 1.0,
                provenance = Provenance(source = "test", producer = "conversation")
            )
        )

        val entries = store.recent("needle", 8).getOrThrow()

        assertEquals(1, entries.size)
        assertEquals("episodic needle", entries.single().content)
    }

    @Test
    fun corruptedEpisodicRecordFailsVisible() {
        val memory = InMemoryMemoryOs()
        memory.remember(
            MemoryRecord(
                kind = CanonicalEpisodicMemoryStore.KIND,
                content = "corrupt",
                importance = 0.8,
                provenance = Provenance(source = "test", producer = "test")
            )
        )
        val store = CanonicalEpisodicMemoryStore(memory)

        assertTrue(store.recent(limit = 8).isFailure)
        assertTrue(store.admit(observation("new", 100L)).isFailure)
    }

    @Test
    fun scanBoundFailsVisibleInsteadOfUsingPartialHistory() {
        val memory = InMemoryMemoryOs()
        repeat(3) { index ->
            memory.remember(
                MemoryRecord(
                    kind = "other",
                    content = "other-" + index,
                    importance = 0.5,
                    provenance = Provenance(source = "test", producer = "test")
                )
            )
        }
        val store = CanonicalEpisodicMemoryStore(
            memory,
            EpisodicMemoryPolicy(maxEpisodes = 1, maxScanRecords = 2)
        )

        assertTrue(store.recent(limit = 1).isFailure)
    }

    private fun observation(
        content: String,
        observedAtEpochMs: Long,
        importance: Double = 0.8,
        origin: EpisodicMemoryOrigin = EpisodicMemoryOrigin.USER_INTENT
    ) = EpisodicObservation(
        origin = origin,
        content = content,
        importance = importance,
        provenance = Provenance(
            source = "test-source",
            producer = "test-producer",
            observedAtEpochMs = observedAtEpochMs,
            confidence = 1.0
        ),
        observedAtEpochMs = observedAtEpochMs
    )
}
