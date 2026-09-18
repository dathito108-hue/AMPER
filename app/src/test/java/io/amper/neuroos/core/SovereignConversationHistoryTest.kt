package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignConversationHistoryTest {
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
    fun recentThreadsGroupsNewestFirstAndCarriesLatestAssistantRoute() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)

        rememberTurn(memory, "alpha", "USER", "Alpha first request", 10L)
        rememberTurn(
            memory,
            "alpha",
            "ASSISTANT",
            "Alpha completed answer",
            11L,
            backend = "backend-alpha",
            model = "model-alpha",
            capabilities = "planning,reasoning"
        )
        rememberTurn(memory, "beta", "USER", "Beta newest request", 20L)
        rememberTurn(
            memory,
            "beta",
            "ASSISTANT",
            "Beta newest answer",
            21L,
            backend = "backend-beta",
            model = "model-beta",
            capabilities = "reasoning"
        )
        memory.remember(
            MemoryRecord(
                kind = "assistant-response",
                content = "must not become a conversation thread",
                importance = 1.0,
                createdAtEpochMs = 30L
            )
        )

        val threads = conversations.recentThreads(limit = 8)

        assertEquals(listOf("beta", "alpha"), threads.map { it.conversationId.value })
        val beta = threads.first()
        assertEquals(2, beta.turnCount)
        assertEquals("Beta newest request", beta.firstUserPreview)
        assertEquals("Beta newest answer", beta.latestTurnPreview)
        assertEquals(ConversationRole.ASSISTANT, beta.latestRole)
        assertEquals("backend-beta", beta.latestAssistantBackendId)
        assertEquals(ModelId("model-beta"), beta.latestAssistantModelId)
        assertEquals(setOf(CapabilityId("reasoning")), beta.latestAssistantSelectedCapabilities)
    }

    @Test
    fun browsingThreadsIsReadOnlyAndDoesNotPublishWorkspaceEvents() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        rememberTurn(memory, "read-only", "USER", "Persisted request", 1L)
        rememberTurn(memory, "read-only", "ASSISTANT", "Persisted response", 2L, backend = "backend")
        val memoryBefore = memory.size()
        val workspaceBefore = workspace.snapshot()

        repeat(3) {
            val threads = conversations.recentThreads()
            assertEquals(1, threads.size)
        }

        assertEquals(memoryBefore, memory.size())
        assertEquals(workspaceBefore, workspace.snapshot())
    }

    @Test
    fun recentThreadsHonorsLimitAndOmitsMalformedConversationRecords() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        rememberTurn(memory, "older", "USER", "Older", 1L)
        rememberTurn(memory, "newer", "USER", "Newer", 2L)
        memory.remember(
            MemoryRecord(
                kind = "conversation-turn",
                content = "malformed",
                importance = 0.8,
                createdAtEpochMs = 3L
            )
        )

        assertTrue(conversations.recentThreads(limit = 0).isEmpty())
        assertEquals(listOf("newer"), conversations.recentThreads(limit = 1).map { it.conversationId.value })
    }

    @Test
    fun previewsCollapseLineBreaksWithoutChangingPersistedTurnText() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        val id = ConversationId("preview")

        conversations.prepare(id, "first line\nsecond line")
        val before = conversations.recent(id).single().text
        val summary = conversations.recentThreads().single()

        assertEquals("first line\nsecond line", before)
        assertEquals("first line second line", summary.firstUserPreview)
        assertEquals("first line second line", summary.latestTurnPreview)
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
