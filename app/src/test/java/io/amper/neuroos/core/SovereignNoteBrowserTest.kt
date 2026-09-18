package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignNoteBrowserTest {
    @Test
    fun recentModeReturnsNewestSovereignNotesOnlyAndHonorsLimit() {
        val memory = InMemoryMemoryOs()
        val store = MemoryBackedSovereignNoteStore(memory, InMemoryWorkspace())
        val oldId = MemoryId("11111111-1111-1111-1111-111111111111")
        val middleId = MemoryId("22222222-2222-2222-2222-222222222222")
        val newId = MemoryId("33333333-3333-3333-3333-333333333333")
        memory.remember(noteRecord(oldId, "old sovereign note", 100L))
        memory.remember(
            MemoryRecord(
                id = MemoryId("99999999-9999-9999-9999-999999999999"),
                kind = "episodic",
                content = "newer generic memory must not leak through note browser",
                importance = 1.0,
                createdAtEpochMs = 900L
            )
        )
        memory.remember(noteRecord(middleId, "middle sovereign note", 200L))
        memory.remember(noteRecord(newId, "new sovereign note", 300L))
        val provider = SovereignNoteSearchToolProvider(store)

        val output = provider.execute("recent:2").getOrThrow()

        assertTrue(output.startsWith("notes=2"))
        assertTrue(output.contains(newId.value))
        assertTrue(output.contains(middleId.value))
        assertFalse(output.contains(oldId.value))
        assertFalse(output.contains("generic memory"))
        assertTrue(output.indexOf(newId.value) < output.indexOf(middleId.value))
    }

    @Test
    fun exactIdModeReturnsOnlyTheRequestedSovereignNote() {
        val runtime = AmperRuntime.reference()
        val first = runtime.notes.remember("Exact note alpha")
        val second = runtime.notes.remember("Exact note beta")
        val provider = SovereignNoteSearchToolProvider(runtime.notes)

        val output = provider.execute("id:${second.id.value}").getOrThrow()

        assertTrue(output.startsWith("notes=1"))
        assertTrue(output.contains(second.id.value))
        assertTrue(output.contains(second.text))
        assertFalse(output.contains(first.id.value))
        assertFalse(output.contains(first.text))
    }

    @Test
    fun exactUnknownIdReturnsNoneWithoutMutation() {
        val runtime = AmperRuntime.reference()
        val existing = runtime.notes.remember("Keep this sovereign note")
        val provider = SovereignNoteSearchToolProvider(runtime.notes)
        val unknown = "00000000-0000-0000-0000-000000000000"

        val output = provider.execute("id:$unknown").getOrThrow()

        assertEquals("notes=none", output)
        assertEquals(existing.text, runtime.notes.get(existing.id)?.text)
    }

    @Test
    fun malformedReservedBrowserCommandsFailClosed() {
        val runtime = AmperRuntime.reference()
        val provider = SovereignNoteSearchToolProvider(runtime.notes)

        assertTrue(provider.execute("recent:0").isFailure)
        assertTrue(provider.execute("recent:6").isFailure)
        assertTrue(provider.execute("recent:not-a-number").isFailure)
        assertTrue(provider.execute("id:not-a-uuid").isFailure)
    }

    @Test
    fun legacyFreeTextSearchRemainsBackwardCompatible() {
        val runtime = AmperRuntime.reference()
        val phoenix = runtime.notes.remember("Project Phoenix release channel is sovereign-stable")
        runtime.notes.remember("Completely unrelated note")
        val provider = SovereignNoteSearchToolProvider(runtime.notes)

        val output = provider.execute("Phoenix").getOrThrow()

        assertTrue(output.contains(phoenix.id.value))
        assertTrue(output.contains(phoenix.text))
        assertFalse(output.contains("Completely unrelated note"))
    }

    @Test
    fun browserKeepsCanonicalBindingAndReadOnlyAutoExecution() {
        val runtime = AmperRuntime.reference()
        runtime.notes.remember("Newest note visible without approval")
        val provider = SovereignNoteSearchToolProvider(runtime.notes)
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val audit = InMemoryToolAuditLog()
        val capability = SovereignNoteSearchToolContract.capability
        val loop = runtime.actionLoop(
            registry,
            AuditedToolFabric(
                DenyByDefaultAuthorityGate(setOf(capability)),
                registry,
                audit
            )
        )

        val outcome = loop.evaluate(
            ActionProposal(
                capability = capability,
                reason = "Show the user's most recent sovereign notes",
                input = "recent"
            )
        )

        assertEquals(ActionStatus.EXECUTED, outcome.status)
        assertEquals(SovereignNoteSearchToolContract.toolId, outcome.toolId)
        assertEquals(ToolSideEffect.READ_ONLY, outcome.sideEffect)
        assertTrue(outcome.output.orEmpty().contains("Newest note visible without approval"))
        assertEquals(1, audit.snapshot().size)
        assertTrue(audit.snapshot().single().authorized)
        assertTrue(audit.snapshot().single().success)
    }

    @Test
    fun descriptorAdvertisesBrowserGrammarWithoutChangingCapabilityIdentity() {
        val provider = SovereignNoteSearchToolProvider(AmperRuntime.reference().notes)

        assertEquals(SovereignNoteSearchToolContract.toolId, provider.descriptor.id)
        assertEquals(SovereignNoteSearchToolContract.capability, provider.descriptor.capability)
        assertEquals(ToolSideEffect.READ_ONLY, provider.descriptor.sideEffect)
        assertTrue(provider.descriptor.inputContract.description.contains("recent:N"))
        assertTrue(provider.descriptor.inputContract.description.contains("id:<canonical UUID>"))
        assertEquals(
            SovereignNoteSearchToolContract.MAX_QUERY_CHARS,
            provider.descriptor.inputContract.maxLength
        )
    }

    private fun noteRecord(id: MemoryId, text: String, createdAt: Long): MemoryRecord = MemoryRecord(
        id = id,
        kind = MemoryBackedSovereignNoteStore.KIND,
        content = text,
        importance = 0.90,
        provenance = Provenance(
            source = "user-approved-sovereign-note",
            producer = SovereignNoteToolContract.toolId.value,
            confidence = 1.0
        ),
        createdAtEpochMs = createdAt
    )
}
