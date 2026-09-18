package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignConversationTitleTest {
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
    fun titlePersistsAcrossCoordinatorRecreationAndAppearsInSummary() {
        val memory = InMemoryMemoryOs()
        rememberTurn(memory, "named", "USER", "neutral request", 1L)
        val first = coordinator(memory)

        val saved = first.setTitle(ConversationId("named"), "  Project\n  Atlas  ")

        assertEquals("Project Atlas", saved)
        assertEquals("Project Atlas", first.title(ConversationId("named")))
        val restarted = coordinator(memory)
        assertEquals("Project Atlas", restarted.title(ConversationId("named")))
        assertEquals("Project Atlas", restarted.recentThreads().single().title)
    }

    @Test
    fun searchUsesOnlyCurrentTitleAfterRenameAndClear() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val id = ConversationId("rename")
        rememberTurn(memory, id.value, "USER", "neutral request", 1L)

        conversations.setTitle(id, "Alpha Workspace")
        assertEquals(listOf(id), conversations.searchThreads("alpha").map { it.conversationId })

        conversations.setTitle(id, "Beta Workspace")
        assertTrue(conversations.searchThreads("alpha").isEmpty())
        assertEquals(listOf(id), conversations.searchThreads("BETA").map { it.conversationId })

        conversations.setTitle(id, null)
        assertNull(conversations.title(id))
        assertTrue(conversations.searchThreads("beta").isEmpty())
        assertNull(conversations.recentThreads().single().title)
    }

    @Test
    fun reservedLookingAndEscapedCharactersRoundTripAsNormalTitleText() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val id = ConversationId("escaping")
        rememberTurn(memory, id.value, "USER", "neutral request", 1L)
        val title = "~ SET | 100%"

        conversations.setTitle(id, title)

        assertEquals(title, conversations.title(id))
        assertEquals(title, conversations.recentThreads().single().title)
        assertEquals(listOf(id), conversations.searchThreads("100%").map { it.conversationId })
    }

    @Test
    fun titleWritesNeverRewriteTurnsOrPublishWorkspaceEvents() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        val id = ConversationId("read-only-turns")
        rememberTurn(memory, id.value, "USER", "original turn", 1L)
        val turnsBefore = conversations.recent(id, 10)
        val workspaceBefore = workspace.snapshot()
        val sizeBefore = memory.size()

        conversations.setTitle(id, "Display Name")

        assertEquals(turnsBefore, conversations.recent(id, 10))
        assertEquals(workspaceBefore, workspace.snapshot())
        assertEquals(sizeBefore + 1, memory.size())
    }

    @Test
    fun settingEquivalentNormalizedTitleIsIdempotent() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val id = ConversationId("idempotent")
        rememberTurn(memory, id.value, "USER", "neutral request", 1L)

        conversations.setTitle(id, "Project Atlas")
        val afterFirst = memory.size()
        conversations.setTitle(id, "  Project   Atlas  ")

        assertEquals(afterFirst, memory.size())
        assertEquals("Project Atlas", conversations.title(id))
    }

    @Test
    fun titleCannotCreateGhostConversationAndOverlongTitleDoesNotMutateMemory() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val missing = ConversationId("missing")
        val beforeMissing = memory.size()

        val missingResult = runCatching { conversations.setTitle(missing, "Ghost") }
        assertTrue(missingResult.isFailure)
        assertEquals(beforeMissing, memory.size())

        val id = ConversationId("bounded")
        rememberTurn(memory, id.value, "USER", "neutral request", 1L)
        val beforeLong = memory.size()
        val longResult = runCatching {
            conversations.setTitle(id, "x".repeat(SovereignConversationCoordinator.MAX_THREAD_TITLE_CHARS + 1))
        }
        assertTrue(longResult.isFailure)
        assertEquals(beforeLong, memory.size())
        assertNull(conversations.title(id))
    }

    @Test
    fun repeatedRenamesDoNotCrowdConversationTurnRecall() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val id = ConversationId("stable-turn-window")
        rememberTurn(memory, id.value, "USER", "first", 1L)
        rememberTurn(memory, id.value, "ASSISTANT", "second", 2L, backend = "backend")

        repeat(24) { index -> conversations.setTitle(id, "Title $index") }

        assertEquals(listOf("first", "second"), conversations.recent(id, 10).map { it.text })
        assertEquals("Title 23", conversations.title(id))
    }

    @Test
    fun titleUpdateTimeDoesNotReorderThreadsByConversationActivity() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        rememberTurn(memory, "older", "USER", "older turn", 10L)
        rememberTurn(memory, "newer", "USER", "newer turn", 20L)

        conversations.setTitle(ConversationId("older"), "Renamed much later")

        assertEquals(
            listOf("newer", "older"),
            conversations.recentThreads().map { it.conversationId.value }
        )
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
