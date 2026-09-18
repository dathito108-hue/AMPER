package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInferenceProfileTest {
    @Test
    fun profilePersistsAndBindsOnlyExplicitOverrides() {
        val memory = InMemoryMemoryOs()
        val id = ConversationId("thread")
        val store = MemoryBackedConversationInferenceProfileStore(memory) { it == id }
        val profile = ConversationInferenceProfile(
            maxOutputTokens = 768,
            temperature = null,
            sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE,
            maxPromptChars = 12_000,
            reflectionMode = ConversationReflectionMode.VERIFY
        )

        store.put(id, profile)
        val restarted = MemoryBackedConversationInferenceProfileStore(memory) { it == id }
        val bound = restarted.bind(
            id,
            fallbackMaxOutputTokens = 256,
            fallbackTemperature = 0.55,
            fallbackMaxPromptChars = 7_000
        )

        assertEquals(profile, restarted.profile(id))
        assertEquals(768, bound.maxOutputTokens)
        assertEquals(0.55, bound.temperature, 0.0)
        assertEquals(TitanSessionRoutingPreference.PREFER_REUSE, bound.sessionRoutingPreference)
        assertEquals(12_000, bound.maxPromptChars)
        assertEquals(ConversationReflectionMode.VERIFY, bound.reflectionMode)
    }

    @Test
    fun equivalentWritesAreIdempotentAndClearUsesTombstone() {
        val memory = InMemoryMemoryOs()
        val id = ConversationId("idempotent")
        val store = MemoryBackedConversationInferenceProfileStore(memory) { true }
        val profile = ConversationInferenceProfile(
            512,
            0.3,
            TitanSessionRoutingPreference.IGNORE_REUSE,
            8_000,
            ConversationReflectionMode.VERIFY
        )

        val before = memory.size()
        store.put(id, profile)
        val afterFirst = memory.size()
        store.put(id, profile)
        assertEquals(before + 1, afterFirst)
        assertEquals(afterFirst, memory.size())

        assertTrue(store.clear(id))
        val afterClear = memory.size()
        assertNull(store.profile(id))
        assertFalse(store.clear(id))
        assertEquals(afterClear, memory.size())
    }

    @Test
    fun defaultProfileIsEquivalentToClear() {
        val memory = InMemoryMemoryOs()
        val id = ConversationId("default")
        val store = MemoryBackedConversationInferenceProfileStore(memory) { true }
        store.put(id, ConversationInferenceProfile(maxOutputTokens = 900, maxPromptChars = 12_000))

        store.put(id, ConversationInferenceProfile())

        assertNull(store.profile(id))
        val bound = store.bind(id, 321, 0.7, 7_500)
        assertEquals(321, bound.maxOutputTokens)
        assertEquals(0.7, bound.temperature, 0.0)
        assertEquals(TitanSessionRoutingPreference.STANDARD, bound.sessionRoutingPreference)
        assertEquals(7_500, bound.maxPromptChars)
        assertEquals(ConversationReflectionMode.STANDARD, bound.reflectionMode)
    }

    @Test
    fun legacyPhase140ProfileRecordWithoutContextBudgetStillDecodes() {
        val memory = InMemoryMemoryOs()
        val id = ConversationId("legacy")
        memory.remember(
            MemoryRecord(
                kind = "conversation-inference-profile",
                content = "conversation-inference-profile:${id.value}|SET|512|0.4|PREFER_REUSE",
                importance = 0.57
            )
        )
        val store = MemoryBackedConversationInferenceProfileStore(memory) { true }

        val profile = store.profile(id)
        val bound = store.bind(id, 256, 0.7, 8_500)

        assertEquals(512, profile?.maxOutputTokens)
        assertEquals(0.4, profile?.temperature ?: -1.0, 0.0)
        assertEquals(TitanSessionRoutingPreference.PREFER_REUSE, profile?.sessionRoutingPreference)
        assertNull(profile?.maxPromptChars)
        assertEquals(8_500, bound.maxPromptChars)
        assertEquals(ConversationReflectionMode.STANDARD, bound.reflectionMode)
    }

    @Test
    fun phase140ContextBudgetRecordDefaultsReflectionToStandard() {
        val memory = InMemoryMemoryOs()
        val id = ConversationId("phase140-context")
        memory.remember(
            MemoryRecord(
                kind = "conversation-inference-profile",
                content = "conversation-inference-profile:${id.value}|SET|640|0.2|IGNORE_REUSE|12000",
                importance = 0.57
            )
        )
        val store = MemoryBackedConversationInferenceProfileStore(memory) { true }

        val profile = store.profile(id)

        assertEquals(640, profile?.maxOutputTokens)
        assertEquals(12_000, profile?.maxPromptChars)
        assertEquals(ConversationReflectionMode.STANDARD, profile?.reflectionMode)
    }

    @Test
    fun ghostConversationCannotReceiveUserProfile() {
        val memory = InMemoryMemoryOs()
        val store = MemoryBackedConversationInferenceProfileStore(memory) { false }
        val before = memory.size()

        val result = runCatching {
            store.put(
                ConversationId("missing"),
                ConversationInferenceProfile(
                    maxOutputTokens = 512,
                    maxPromptChars = 8_000,
                    reflectionMode = ConversationReflectionMode.VERIFY
                )
            )
        }

        assertTrue(result.isFailure)
        assertEquals(before, memory.size())
    }

    @Test
    fun repeatedProfileChangesCannotCrowdConversationTurnRecall() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val conversations = SovereignConversationCoordinator(
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
        val id = ConversationId("stable")
        rememberTurn(memory, id, "USER", "first", 1L)
        rememberTurn(memory, id, "ASSISTANT", "second", 2L)
        val store = MemoryBackedConversationInferenceProfileStore(memory) {
            conversations.recent(it, 1).isNotEmpty()
        }

        repeat(40) { index ->
            store.put(
                id,
                ConversationInferenceProfile(
                    maxOutputTokens = 128 + index,
                    temperature = (index % 10) / 10.0,
                    sessionRoutingPreference = TitanSessionRoutingPreference.values()[index % 3],
                    maxPromptChars = 4_096 + index,
                    reflectionMode = if (index % 2 == 0) {
                        ConversationReflectionMode.VERIFY
                    } else {
                        ConversationReflectionMode.STANDARD
                    }
                )
            )
        }

        assertEquals(listOf("first", "second"), conversations.recent(id, 10).map { it.text })
        assertEquals(167, store.profile(id)?.maxOutputTokens)
        assertEquals(4_135, store.profile(id)?.maxPromptChars)
        assertEquals(ConversationReflectionMode.STANDARD, store.profile(id)?.reflectionMode)
    }

    @Test
    fun invalidProfileValuesFailClosed() {
        assertTrue(runCatching { ConversationInferenceProfile(maxOutputTokens = 0) }.isFailure)
        assertTrue(runCatching { ConversationInferenceProfile(temperature = 2.1) }.isFailure)
        assertTrue(runCatching { ConversationInferenceProfile(maxPromptChars = 4_095) }.isFailure)
        assertTrue(runCatching { ConversationInferenceProfile(maxPromptChars = 32_769) }.isFailure)
        assertTrue(
            runCatching {
                BoundConversationInferenceProfile(0, 0.7, TitanSessionRoutingPreference.STANDARD)
            }.isFailure
        )
        assertTrue(
            runCatching {
                BoundConversationInferenceProfile(
                    256,
                    0.7,
                    TitanSessionRoutingPreference.STANDARD,
                    4_095
                )
            }.isFailure
        )
    }

    private fun rememberTurn(
        memory: MemoryOs,
        id: ConversationId,
        role: String,
        text: String,
        timestamp: Long
    ) {
        memory.remember(
            MemoryRecord(
                kind = "conversation-turn",
                content = listOf("conversation:${id.value}", role, text, "~", "~", "~").joinToString("|"),
                importance = 0.8,
                createdAtEpochMs = timestamp
            )
        )
    }
}
