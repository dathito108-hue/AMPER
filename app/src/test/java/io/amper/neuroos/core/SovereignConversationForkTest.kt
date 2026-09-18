package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignConversationForkTest {
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
    fun forkInheritsExactPrefixWithoutMutatingSourceOrCopyingUserMetadata() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        val profiles = MemoryBackedConversationInferenceProfileStore(memory) {
            conversations.recent(it, 1).isNotEmpty()
        }
        val source = ConversationId("source")
        rememberTurn(memory, source, "USER", "question-one", 10L)
        rememberTurn(
            memory,
            source,
            "ASSISTANT",
            "answer-one",
            20L,
            backend = "backend-a",
            model = "model-a",
            capabilities = "coding,reasoning"
        )
        rememberTurn(memory, source, "USER", "question-two", 30L)
        rememberTurn(memory, source, "ASSISTANT", "answer-two", 40L, backend = "backend-b", model = "model-b")
        conversations.setTitle(source, "Source title")
        conversations.setPinned(source, true)
        conversations.setPreferredModelId(source, ModelId("manual-source-model"))
        profiles.put(
            source,
            ConversationInferenceProfile(
                maxOutputTokens = 700,
                temperature = 0.25,
                sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE,
                maxPromptChars = 6_000
            )
        )
        val sourceBefore = conversations.recent(source, 16)
        val workspaceBefore = workspace.snapshot()
        val memoryBefore = memory.size()

        val fork = conversations.forkConversation(source, throughTurnAtEpochMs = 20L)

        assertEquals(memoryBefore + 1, memory.size())
        assertEquals(workspaceBefore, workspace.snapshot())
        assertEquals(sourceBefore, conversations.recent(source, 16))
        val inherited = conversations.recent(fork, 16)
        assertEquals(listOf("question-one", "answer-one"), inherited.map { it.text })
        assertTrue(inherited.all { it.conversationId == fork })
        assertEquals("backend-a", inherited.last().backendId)
        assertEquals(ModelId("model-a"), inherited.last().modelId)
        assertEquals(
            setOf(CapabilityId("coding"), CapabilityId("reasoning")),
            inherited.last().selectedCapabilities
        )
        assertNull(conversations.title(fork))
        assertFalse(conversations.pinned(fork))
        assertNull(conversations.preferredModelId(fork))
        assertNull(profiles.profile(fork))
        assertEquals(ModelId("model-a"), conversations.latestAssistantModelId(fork))
        assertEquals(
            setOf(CapabilityId("coding"), CapabilityId("reasoning")),
            conversations.latestAssistantSelectedCapabilities(fork)
        )
        val info = conversations.forkInfo(fork)
        assertEquals(source, info?.sourceConversationId)
        assertEquals(20L, info?.throughTurnAtEpochMs)
        assertTrue(conversations.searchThreads("answer-one", 16).any { it.conversationId == fork })
        assertTrue(conversations.recentThreads(16).any { it.conversationId == fork })

        conversations.setTitle(fork, "Fork title")
        conversations.setPreferredModelId(fork, ModelId("fork-model"))
        profiles.put(fork, ConversationInferenceProfile(maxPromptChars = 5_000))
        assertEquals("Source title", conversations.title(source))
        assertEquals(ModelId("manual-source-model"), conversations.preferredModelId(source))
        assertEquals(6_000, profiles.profile(source)?.maxPromptChars)
        assertEquals("Fork title", conversations.title(fork))
        assertEquals(ModelId("fork-model"), conversations.preferredModelId(fork))
        assertEquals(5_000, profiles.profile(fork)?.maxPromptChars)
    }

    @Test
    fun sourceContinuationAfterBranchPointCannotLeakIntoForkAndForkContinuationStaysIndependent() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val source = ConversationId("source-independent")
        rememberTurn(memory, source, "USER", "shared-user", 10L)
        rememberTurn(memory, source, "ASSISTANT", "shared-assistant", 20L)
        val fork = conversations.forkConversation(source, 20L)

        rememberTurn(memory, source, "USER", "source-after-fork", 30L)
        rememberTurn(memory, fork, "USER", "fork-only", 40L)

        assertEquals(
            listOf("shared-user", "shared-assistant", "fork-only"),
            conversations.recent(fork, 16).map { it.text }
        )
        assertEquals(
            listOf("shared-user", "shared-assistant", "source-after-fork"),
            conversations.recent(source, 16).map { it.text }
        )
        assertFalse(conversations.recent(fork, 16).any { it.text == "source-after-fork" })
        assertFalse(conversations.recent(source, 16).any { it.text == "fork-only" })
    }

    @Test
    fun forkOfForkResolvesInheritedPrefixAtItsOwnExactBranchPoint() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val source = ConversationId("root")
        rememberTurn(memory, source, "USER", "u1", 10L)
        rememberTurn(memory, source, "ASSISTANT", "a1", 20L, model = "m1")
        rememberTurn(memory, source, "USER", "u2", 30L)
        rememberTurn(memory, source, "ASSISTANT", "a2", 40L, model = "m2")

        val firstFork = conversations.forkConversation(source, 30L)
        val secondFork = conversations.forkConversation(firstFork, 20L)

        assertEquals(listOf("u1", "a1", "u2"), conversations.recent(firstFork, 16).map { it.text })
        assertEquals(listOf("u1", "a1"), conversations.recent(secondFork, 16).map { it.text })
        assertEquals(firstFork, conversations.forkInfo(secondFork)?.sourceConversationId)
        assertEquals(ModelId("m1"), conversations.latestAssistantModelId(secondFork))
    }

    @Test
    fun newForkUsesCreationCheckpointForBrowserRecencyWithoutChangingLatestTurnTime() {
        val memory = InMemoryMemoryOs()
        val conversations = coordinator(memory)
        val oldSource = ConversationId("old-source")
        val other = ConversationId("other")
        rememberTurn(memory, oldSource, "USER", "old-root", 10L)
        rememberTurn(memory, other, "USER", "newer-existing", 100L)

        val fork = conversations.forkConversation(oldSource, 10L)
        val summaries = conversations.recentThreads(8)
        val forkSummary = summaries.single { it.conversationId == fork }

        assertEquals(fork, summaries.first().conversationId)
        assertEquals(10L, forkSummary.latestTurnAtEpochMs)
        assertEquals("old-root", forkSummary.latestTurnPreview)
    }

    @Test
    fun invalidForkPointOrMissingSourceCannotManufactureConversation() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        val source = ConversationId("valid-source")
        rememberTurn(memory, source, "USER", "only-turn", 10L)
        val before = memory.size()
        val workspaceBefore = workspace.snapshot()

        assertTrue(runCatching { conversations.forkConversation(source, 11L) }.isFailure)
        assertTrue(
            runCatching {
                conversations.forkConversation(ConversationId("missing-source"), 10L)
            }.isFailure
        )

        assertEquals(before, memory.size())
        assertEquals(workspaceBefore, workspace.snapshot())
        assertEquals(1, conversations.recentThreads(16).size)
    }

    @Test
    fun forkLineageSurvivesPersistentMemoryRestart() {
        val root = Files.createTempDirectory("amper-conversation-fork").toFile()
        try {
            val journal = File(root, "memory.journal")
            val firstMemory = PersistentMemoryOs(FileMemoryJournal(journal))
            val first = coordinator(firstMemory)
            val source = ConversationId("persistent-source")
            rememberTurn(firstMemory, source, "USER", "persistent-user", 10L)
            rememberTurn(firstMemory, source, "ASSISTANT", "persistent-answer", 20L, model = "persistent-model")
            rememberTurn(firstMemory, source, "USER", "not-in-fork", 30L)
            val fork = first.forkConversation(source, 20L)

            val restartedMemory = PersistentMemoryOs(FileMemoryJournal(journal))
            val restarted = coordinator(restartedMemory)

            assertEquals(listOf("persistent-user", "persistent-answer"), restarted.recent(fork, 16).map { it.text })
            assertEquals(source, restarted.forkInfo(fork)?.sourceConversationId)
            assertEquals(20L, restarted.forkInfo(fork)?.throughTurnAtEpochMs)
            assertEquals(ModelId("persistent-model"), restarted.latestAssistantModelId(fork))
            assertTrue(restarted.recentThreads(16).any { it.conversationId == fork })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun transcriptReportsForkLineageWithoutMutation() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = coordinator(memory, workspace)
        val source = ConversationId("transcript-source")
        rememberTurn(memory, source, "USER", "hello", 10L)
        rememberTurn(memory, source, "ASSISTANT", "world", 20L)
        val fork = conversations.forkConversation(source, 20L)
        val memoryBefore = memory.size()
        val workspaceBefore = workspace.snapshot()

        val transcript = SovereignConversationTranscriptBrowser(conversations).open(fork)

        assertEquals(fork, transcript.conversationId)
        assertEquals(source, transcript.forkInfo?.sourceConversationId)
        assertEquals(20L, transcript.forkInfo?.throughTurnAtEpochMs)
        assertEquals(listOf("hello", "world"), transcript.turns.map { it.text })
        assertEquals(memoryBefore, memory.size())
        assertEquals(workspaceBefore, workspace.snapshot())
    }

    private fun rememberTurn(
        memory: MemoryOs,
        id: ConversationId,
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
                    "conversation:${id.value}",
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
