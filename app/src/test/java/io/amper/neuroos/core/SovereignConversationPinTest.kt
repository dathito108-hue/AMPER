package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignConversationPinTest {
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
    fun pinnedOlderThreadAppearsBeforeNewerUnpinnedThread() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        rememberTurn(memory, "older", "USER", "older", 10L)
        rememberTurn(memory, "newer", "USER", "newer", 20L)

        conversations.setPinned(ConversationId("older"), true)

        val threads = conversations.recentThreads()
        assertEquals(listOf("older", "newer"), threads.map { it.conversationId.value })
        assertTrue(threads.first().pinned)
        assertFalse(threads.last().pinned)
    }

    @Test
    fun pinnedThreadsRemainOrderedByConversationActivityNotPinTime() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        rememberTurn(memory, "older", "USER", "older", 10L)
        rememberTurn(memory, "newer", "USER", "newer", 20L)

        conversations.setPinned(ConversationId("newer"), true)
        conversations.setPinned(ConversationId("older"), true)

        assertEquals(
            listOf("newer", "older"),
            conversations.recentThreads().map { it.conversationId.value }
        )
    }

    @Test
    fun unpinRestoresNormalActivityOrdering() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val older = ConversationId("older")
        rememberTurn(memory, older.value, "USER", "older", 10L)
        rememberTurn(memory, "newer", "USER", "newer", 20L)

        conversations.setPinned(older, true)
        assertEquals("older", conversations.recentThreads().first().conversationId.value)
        conversations.setPinned(older, false)

        assertFalse(conversations.pinned(older))
        assertEquals(
            listOf("newer", "older"),
            conversations.recentThreads().map { it.conversationId.value }
        )
    }

    @Test
    fun pinPersistsAcrossCoordinatorRecreationAndOrdersSearchMatches() {
        val memory = InMemoryMemoryOs()
        val first = coordinator(memory)
        val older = ConversationId("older")
        rememberTurn(memory, older.value, "USER", "shared topic older", 10L)
        rememberTurn(memory, "newer", "USER", "shared topic newer", 20L)
        first.setPinned(older, true)

        val restarted = coordinator(memory)

        assertTrue(restarted.pinned(older))
        assertEquals(
            listOf("older", "newer"),
            restarted.searchThreads("shared topic").map { it.conversationId.value }
        )
    }

    @Test
    fun pinWriteDoesNotRewriteTurnsPublishWorkspaceOrChangeActivityTime() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        val id = ConversationId("stable")
        rememberTurn(memory, id.value, "USER", "original", 10L)
        rememberTurn(memory, id.value, "ASSISTANT", "answer", 11L, backend = "backend")
        val turnsBefore = conversations.recent(id, 10)
        val latestBefore = conversations.recentThreads().single().latestTurnAtEpochMs
        val workspaceBefore = workspace.snapshot()
        val memoryBefore = memory.size()

        conversations.setPinned(id, true)

        assertEquals(turnsBefore, conversations.recent(id, 10))
        assertEquals(latestBefore, conversations.recentThreads().single().latestTurnAtEpochMs)
        assertEquals(workspaceBefore, workspace.snapshot())
        assertEquals(memoryBefore + 1, memory.size())
    }

    @Test
    fun equivalentPinUpdatesAreIdempotent() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val id = ConversationId("idempotent")
        rememberTurn(memory, id.value, "USER", "request", 1L)

        conversations.setPinned(id, false)
        val beforePin = memory.size()
        conversations.setPinned(id, true)
        val afterPin = memory.size()
        conversations.setPinned(id, true)

        assertEquals(beforePin + 1, afterPin)
        assertEquals(afterPin, memory.size())
        assertTrue(conversations.pinned(id))
    }

    @Test
    fun pinCannotCreateGhostConversation() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val before = memory.size()

        val result = runCatching {
            conversations.setPinned(ConversationId("missing"), true)
        }

        assertTrue(result.isFailure)
        assertEquals(before, memory.size())
        assertTrue(conversations.recentThreads().isEmpty())
    }

    @Test
    fun repeatedPinTogglesDoNotCrowdConversationTurnRecall() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val id = ConversationId("stable-window")
        rememberTurn(memory, id.value, "USER", "first", 1L)
        rememberTurn(memory, id.value, "ASSISTANT", "second", 2L, backend = "backend")

        repeat(40) { index -> conversations.setPinned(id, index % 2 == 0) }

        assertEquals(listOf("first", "second"), conversations.recent(id, 10).map { it.text })
        assertFalse(conversations.pinned(id))
    }

    private fun rememberTurn(
        memory: MemoryOs,
        id: String,
        role: String,
        text: String,
        timestamp: Long,
        backend: String? = null
    ) {
        memory.remember(
            MemoryRecord(
                kind = "conversation-turn",
                content = listOf(
                    "conversation:$id",
                    role,
                    text,
                    backend ?: "~",
                    "~",
                    "~"
                ).joinToString("|"),
                importance = 0.8,
                createdAtEpochMs = timestamp
            )
        )
    }
}
