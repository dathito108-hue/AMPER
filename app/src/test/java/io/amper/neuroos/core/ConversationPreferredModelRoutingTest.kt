package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPreferredModelRoutingTest {
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
    fun explicitUserPreferenceOutranksContinuityHint() {
        val continuity = ModelId("continuity")
        val conversationChoice = ModelId("conversation-choice")

        val scoped = InferenceRequest(
            prompt = "continue",
            preferredModelId = continuity,
            userPreferredModelId = conversationChoice
        )
        val continuityOnly = InferenceRequest(
            prompt = "continue",
            preferredModelId = continuity
        )

        assertEquals(conversationChoice, scoped.effectivePreferredModelId())
        assertEquals(continuity, continuityOnly.effectivePreferredModelId())
    }

    @Test
    fun preferencePersistsAcrossStoreRecreationAndEquivalentWritesAreIdempotent() {
        val memory = InMemoryMemoryOs()
        val conversationId = ConversationId("routing-thread")
        val modelA = ModelId("model-a")
        val first = MemoryBackedConversationModelRoutingPreferences(memory)

        first.prefer(conversationId, modelA)
        val afterFirstWrite = memory.size()
        first.prefer(conversationId, modelA)

        assertEquals(afterFirstWrite, memory.size())
        assertEquals(
            modelA,
            MemoryBackedConversationModelRoutingPreferences(memory).preferred(conversationId)
        )
    }

    @Test
    fun preferenceSupportsReservedCharactersAndClearState() {
        val memory = InMemoryMemoryOs()
        val conversationId = ConversationId("thread|with%reserved")
        val modelId = ModelId("model|with%reserved")
        val store = MemoryBackedConversationModelRoutingPreferences(memory)

        store.prefer(conversationId, modelId)
        assertEquals(modelId, store.preferred(conversationId))

        store.prefer(conversationId, null)
        assertNull(store.preferred(conversationId))
        assertNull(MemoryBackedConversationModelRoutingPreferences(memory).preferred(conversationId))
    }

    @Test
    fun clearModelOnlyClearsConversationsPointingAtThatModel() {
        val memory = InMemoryMemoryOs()
        val store = MemoryBackedConversationModelRoutingPreferences(memory)
        val modelA = ModelId("model-a")
        val modelB = ModelId("model-b")
        val first = ConversationId("first")
        val second = ConversationId("second")
        val third = ConversationId("third")
        store.prefer(first, modelA)
        store.prefer(second, modelA)
        store.prefer(third, modelB)

        val cleared = store.clearModel(modelA)

        assertEquals(2, cleared)
        assertNull(store.preferred(first))
        assertNull(store.preferred(second))
        assertEquals(modelB, store.preferred(third))
    }

    @Test
    fun conversationPreferenceWriteDoesNotRewriteTurnsPublishWorkspaceOrChangeActivityTime() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        val id = ConversationId("stable")
        rememberTurn(memory, id.value, "USER", "original", 10L)
        rememberTurn(
            memory = memory,
            id = id.value,
            role = "ASSISTANT",
            text = "answer",
            timestamp = 11L,
            backend = "backend",
            model = "previous-model"
        )
        val turnsBefore = conversations.recent(id, 10)
        val latestBefore = conversations.recentThreads().single().latestTurnAtEpochMs
        val workspaceBefore = workspace.snapshot()
        val memoryBefore = memory.size()

        conversations.setPreferredModelId(id, ModelId("conversation-model"))

        assertEquals(ModelId("conversation-model"), conversations.preferredModelId(id))
        assertEquals(turnsBefore, conversations.recent(id, 10))
        assertEquals(latestBefore, conversations.recentThreads().single().latestTurnAtEpochMs)
        assertEquals(workspaceBefore, workspace.snapshot())
        assertEquals(memoryBefore + 1, memory.size())
    }

    @Test
    fun coordinatorRejectsGhostConversationPreference() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val before = memory.size()

        val result = runCatching {
            conversations.setPreferredModelId(
                ConversationId("missing"),
                ModelId("model-a")
            )
        }

        assertTrue(result.isFailure)
        assertEquals(before, memory.size())
        assertNull(conversations.preferredModelId(ConversationId("missing")))
    }

    @Test
    fun repeatedPreferenceChangesDoNotCrowdConversationTurnRecall() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val id = ConversationId("stable-window")
        rememberTurn(memory, id.value, "USER", "first", 1L)
        rememberTurn(
            memory = memory,
            id = id.value,
            role = "ASSISTANT",
            text = "second",
            timestamp = 2L,
            backend = "backend",
            model = "continuity"
        )

        repeat(40) { index ->
            conversations.setPreferredModelId(
                id,
                if (index % 2 == 0) ModelId("model-a") else ModelId("model-b")
            )
        }

        assertEquals(listOf("first", "second"), conversations.recent(id, 10).map { it.text })
        assertEquals(ModelId("model-b"), conversations.preferredModelId(id))
        assertEquals(ModelId("continuity"), conversations.latestAssistantModelId(id))
    }

    private fun rememberTurn(
        memory: MemoryOs,
        id: String,
        role: String,
        text: String,
        timestamp: Long,
        backend: String? = null,
        model: String? = null
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
                    "~"
                ).joinToString("|"),
                importance = 0.8,
                createdAtEpochMs = timestamp
            )
        )
    }
}
