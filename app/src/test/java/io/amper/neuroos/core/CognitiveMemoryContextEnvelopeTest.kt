package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitiveMemoryContextEnvelopeTest {
    @Test
    fun runtimeContextUsesExplicitEpisodicLaneWithoutGenericDuplication() {
        val memory = InMemoryMemoryOs()
        val episodic = CanonicalEpisodicMemoryStore(memory)
        episodic.admit(
            EpisodicObservation(
                origin = EpisodicMemoryOrigin.USER_INTENT,
                content = "phase676 episodic marker",
                importance = 0.9,
                provenance = Provenance(
                    source = "phase676",
                    producer = "unit",
                    observedAtEpochMs = 10L
                ),
                observedAtEpochMs = 10L
            )
        ).getOrThrow()
        memory.remember(
            MemoryRecord(
                kind = "sovereign-note",
                content = "phase676 generic marker",
                importance = 0.8,
                provenance = Provenance("phase676", "note")
            )
        )
        val workspace = InMemoryWorkspace().also {
            it.publish(CognitiveEvent(topic = "working", payload = "phase676", salience = 0.8))
        }
        val envelope = CognitiveMemoryContextEnvelope()
        val source = CanonicalSovereignContextSource(
            workspace = workspace,
            memory = memory,
            selfModel = CanonicalSelfModel(),
            goals = CanonicalGoalSystem(),
            world = CanonicalWorldModel(),
            episodicMemoryStore = episodic,
            memoryEnvelope = envelope
        )

        val snapshot = source.capture("phase676")

        assertEquals(1, snapshot.episodicMemories.size)
        assertEquals("phase676 episodic marker", snapshot.episodicMemories.single().content)
        assertFalse(
            snapshot.memories.any { it.kind == CanonicalEpisodicMemoryStore.KIND }
        )
        assertTrue(snapshot.memories.any { it.kind == "sovereign-note" })
        val usage = envelope.validate(snapshot)
        assertEquals(1, usage.workingItems)
        assertEquals(1, usage.episodicItems)
        assertTrue(usage.totalCognitiveItems <= envelope.maxTotalCognitiveItems)
    }

    @Test
    fun groundedPromptRendersExplicitEpisodeExactlyOnce() {
        val memory = InMemoryMemoryOs()
        val episodic = CanonicalEpisodicMemoryStore(memory)
        episodic.admit(
            EpisodicObservation(
                origin = EpisodicMemoryOrigin.USER_INTENT,
                content = "unique phase676 episode",
                importance = 0.9,
                provenance = Provenance(
                    source = "phase676",
                    producer = "unit",
                    observedAtEpochMs = 20L
                ),
                observedAtEpochMs = 20L
            )
        ).getOrThrow()
        val source = CanonicalSovereignContextSource(
            InMemoryWorkspace(),
            memory,
            CanonicalSelfModel(),
            CanonicalGoalSystem(),
            CanonicalWorldModel(),
            episodicMemoryStore = episodic
        )

        val prompt = source.groundedPrompt("phase676", 2400)

        assertTrue(prompt.contains("episodic_memory:"))
        assertEquals(
            1,
            Regex("unique phase676 episode").findAll(prompt).count()
        )
    }

    @Test
    fun callerOverRequestsAreClampedToEnvelopeWithoutExpandingContext() {
        val memory = InMemoryMemoryOs()
        repeat(12) { index ->
            memory.remember(
                MemoryRecord(
                    kind = "sovereign-note",
                    content = "quota-marker-" + index,
                    importance = 0.7,
                    provenance = Provenance("phase676", "quota-test")
                )
            )
        }
        val workspace = InMemoryWorkspace().also { ws ->
            repeat(12) { index ->
                ws.publish(
                    CognitiveEvent(
                        topic = "quota-working-" + index,
                        payload = "quota",
                        salience = 0.8
                    )
                )
            }
        }
        val envelope = CognitiveMemoryContextEnvelope()
        val source = CanonicalSovereignContextSource(
            workspace,
            memory,
            CanonicalSelfModel(),
            CanonicalGoalSystem(),
            CanonicalWorldModel(),
            memoryEnvelope = envelope
        )

        val snapshot = source.capture(
            query = "quota",
            memoryLimit = envelope.maxGenericMemories + 10,
            workspaceLimit = envelope.maxWorkingItems + 10
        )

        assertTrue(snapshot.memories.size <= envelope.maxGenericMemories)
        assertEquals(envelope.maxWorkingItems, snapshot.workspaceEvents.size)
        envelope.validate(snapshot)
    }

    @Test
    fun defaultEnvelopeIsFiniteAcrossAllCognitiveMemoryDomains() {
        val envelope = CognitiveMemoryContextEnvelope()

        assertEquals(6, envelope.maxWorkingItems)
        assertEquals(6, envelope.maxEpisodicItems)
        assertEquals(6, envelope.maxSemanticKnowledgeItems)
        assertEquals(6, envelope.maxEpistemicBeliefItems)
        assertEquals(4, envelope.maxProceduralItems)
        assertEquals(28, envelope.maxTotalCognitiveItems)
    }
}
