package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignConversationTranscriptTest {
    private fun coordinator(
        memory: MemoryOs,
        workspace: GlobalWorkspace = InMemoryWorkspace()
    ): SovereignConversationCoordinator = SovereignConversationCoordinator(
        memory = memory,
        workspace = workspace,
        context = CanonicalSovereignContextSource(
            workspace = workspace,
            memory = memory,
            selfModel = CanonicalSelfModel(),
            goals = CanonicalGoalSystem(),
            world = CanonicalWorldModel()
        )
    )

    @Test
    fun transcriptPreservesPersistedTurnAndRouteMetadataWithoutMutation() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        val id = ConversationId("transcript")
        rememberTurn(memory, id.value, "USER", "question", 10L)
        rememberTurn(
            memory = memory,
            id = id.value,
            role = "ASSISTANT",
            text = "answer",
            timestamp = 20L,
            backend = "backend-a",
            model = "model-a",
            capabilities = "coding,reasoning"
        )
        conversations.setTitle(id, "Transcript Test")
        val memoryBefore = memory.size()
        val workspaceBefore = workspace.snapshot()

        val transcript = SovereignConversationTranscriptBrowser(conversations).open(id)

        assertEquals(id, transcript.conversationId)
        assertEquals("Transcript Test", transcript.title)
        assertFalse(transcript.truncated)
        assertEquals(listOf(ConversationRole.USER, ConversationRole.ASSISTANT), transcript.turns.map { it.role })
        assertEquals(listOf("question", "answer"), transcript.turns.map { it.text })
        val assistant = transcript.turns.last()
        assertEquals(20L, assistant.createdAtEpochMs)
        assertEquals("backend-a", assistant.backendId)
        assertEquals(ModelId("model-a"), assistant.modelId)
        assertEquals(
            setOf(CapabilityId("coding"), CapabilityId("reasoning")),
            assistant.selectedCapabilities
        )
        assertEquals(memoryBefore, memory.size())
        assertEquals(workspaceBefore, workspace.snapshot())
    }

    @Test
    fun boundedTranscriptReturnsNewestTurnsInChronologicalOrder() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val id = ConversationId("bounded")
        (1L..5L).forEach { index ->
            rememberTurn(memory, id.value, "USER", "turn-$index", index)
        }

        val transcript = SovereignConversationTranscriptBrowser(conversations).open(id, limit = 3)

        assertTrue(transcript.truncated)
        assertEquals(listOf("turn-3", "turn-4", "turn-5"), transcript.turns.map { it.text })
        assertEquals(listOf(3L, 4L, 5L), transcript.turns.map { it.createdAtEpochMs })
    }

    @Test
    fun exactLimitIsNotReportedAsTruncated() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val id = ConversationId("exact")
        repeat(3) { index ->
            rememberTurn(memory, id.value, "USER", "turn-$index", (index + 1).toLong())
        }

        val transcript = SovereignConversationTranscriptBrowser(conversations).open(id, limit = 3)

        assertFalse(transcript.truncated)
        assertEquals(3, transcript.turns.size)
    }

    @Test
    fun invalidLimitsFailBeforeBrowsing() {
        val browser = SovereignConversationTranscriptBrowser(coordinator(InMemoryMemoryOs()))
        val id = ConversationId("missing")

        assertTrue(runCatching { browser.open(id, 0) }.isFailure)
        assertTrue(
            runCatching {
                browser.open(id, SovereignConversationTranscriptBrowser.MAX_TRANSCRIPT_TURNS + 1)
            }.isFailure
        )
    }

    @Test
    fun missingConversationCannotManufactureTranscript() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val browser = SovereignConversationTranscriptBrowser(coordinator(memory, workspace))
        val before = memory.size()

        val result = runCatching { browser.open(ConversationId("missing")) }

        assertTrue(result.isFailure)
        assertEquals(before, memory.size())
        assertTrue(workspace.snapshot().isEmpty())
    }

    private fun rememberTurn(
        memory: MemoryOs,
        id: String,
        role: String,
        text: String,
        timestamp: Long,
        backend: String? = null,
        model: String? = null,
        capabilities: String? = null
    ) {
        memory.remember(
            MemoryRecord(
                kind = "conversation-turn",
                content = listOf(
                    "conversation:$id",
                    role,
                    text,
                    backend ?: "~",
                    model ?: "~",
                    capabilities ?: "~"
                ).joinToString("|"),
                importance = 0.8,
                createdAtEpochMs = timestamp
            )
        )
    }
}
