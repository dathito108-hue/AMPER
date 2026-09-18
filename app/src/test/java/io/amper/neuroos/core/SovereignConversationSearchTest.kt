package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignConversationSearchTest {
    private fun coordinator(
        memory: MemoryOs,
        workspace: GlobalWorkspace
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
    fun searchMatchesAnyPersistedTurnButReturnsCompleteLatestThreadSummary() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        rememberTurn(memory, "phoenix", "USER", "Investigate cedar-947 deployment", 10L)
        rememberTurn(
            memory,
            "phoenix",
            "ASSISTANT",
            "Deployment review completed",
            11L,
            backend = "llama-local",
            model = "coder-model",
            capabilities = "planning,reasoning"
        )
        rememberTurn(memory, "other", "USER", "Unrelated conversation", 20L)

        val result = conversations.searchThreads("cedar-947")

        assertEquals(1, result.size)
        val thread = result.single()
        assertEquals("phoenix", thread.conversationId.value)
        assertEquals(2, thread.turnCount)
        assertEquals("Deployment review completed", thread.latestTurnPreview)
        assertEquals("llama-local", thread.latestAssistantBackendId)
        assertEquals(ModelId("coder-model"), thread.latestAssistantModelId)
        assertEquals(
            setOf(CapabilityId("planning"), CapabilityId("reasoning")),
            thread.latestAssistantSelectedCapabilities
        )
    }

    @Test
    fun searchSupportsRoutingMetadataAndConversationIdWithoutGenericMemoryLeakage() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        rememberTurn(
            memory,
            "thread-special-42",
            "ASSISTANT",
            "Completed normally",
            5L,
            backend = "backend-orchid",
            model = "model-saffron",
            capabilities = "code-generation"
        )
        memory.remember(
            MemoryRecord(
                kind = "episodic",
                content = "backend-orchid should never create a fake conversation search hit",
                importance = 1.0,
                createdAtEpochMs = 100L
            )
        )

        assertEquals("thread-special-42", conversations.searchThreads("orchid").single().conversationId.value)
        assertEquals("thread-special-42", conversations.searchThreads("saffron").single().conversationId.value)
        assertEquals("thread-special-42", conversations.searchThreads("code-generation").single().conversationId.value)
        assertEquals("thread-special-42", conversations.searchThreads("special-42").single().conversationId.value)
        assertTrue(conversations.searchThreads("fake conversation search hit").isEmpty())
    }

    @Test
    fun searchIsReadOnlyBlankFallsBackToRecentAndLimitsFailClosed() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        rememberTurn(memory, "older", "USER", "Alpha searchable", 1L)
        rememberTurn(memory, "newer", "USER", "Beta searchable", 2L)
        val memoryBefore = memory.size()
        val workspaceBefore = workspace.snapshot()

        assertEquals(
            conversations.recentThreads(limit = 8),
            conversations.searchThreads("   ", limit = 8)
        )
        assertEquals(listOf("newer"), conversations.searchThreads("searchable", limit = 1).map { it.conversationId.value })
        assertTrue(conversations.searchThreads("searchable", limit = 0).isEmpty())
        assertTrue(
            runCatching {
                conversations.searchThreads("x".repeat(SovereignConversationCoordinator.MAX_THREAD_SEARCH_CHARS + 1))
            }.isFailure
        )
        assertEquals(memoryBefore, memory.size())
        assertEquals(workspaceBefore, workspace.snapshot())
    }

    @Test
    fun searchIsCaseInsensitiveAndDoesNotMutatePersistedText() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        val id = ConversationId("case-search")
        conversations.prepare(id, "Project OMEGA keeps exact Case")
        val before = conversations.recent(id).single().text

        val result = conversations.searchThreads("omega")

        assertEquals("case-search", result.single().conversationId.value)
        assertEquals(before, conversations.recent(id).single().text)
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
