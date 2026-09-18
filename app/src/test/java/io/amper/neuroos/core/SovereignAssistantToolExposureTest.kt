package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignAssistantToolExposureTest {
    @Test
    fun productionExposureIncludesStatusAndFullGovernedNoteLifecycle() {
        assertEquals(
            linkedSetOf(
                DeviceStatusToolContract.capability,
                SovereignStatusToolContract.capability,
                SovereignNoteToolContract.capability,
                SovereignNoteSearchToolContract.capability,
                SovereignNoteDeleteToolContract.capability,
                AndroidSettingsOpenToolContract.capability,
                AndroidTimerPrepareToolContract.capability,
                AndroidShareTextToolContract.capability
            ),
            SovereignAssistantToolExposure.capabilities
        )
    }

    @Test
    fun noteLifecycleKeepsReadWriteAuthorityBoundariesWhenExposed() {
        val runtime = AmperRuntime.reference()
        val writer = SovereignNoteToolProvider(runtime.notes)
        val search = SovereignNoteSearchToolProvider(runtime.notes)
        val deleter = SovereignNoteDeleteToolProvider(runtime.notes)

        assertTrue(SovereignNoteToolContract.capability in SovereignAssistantToolExposure.capabilities)
        assertTrue(SovereignNoteSearchToolContract.capability in SovereignAssistantToolExposure.capabilities)
        assertTrue(SovereignNoteDeleteToolContract.capability in SovereignAssistantToolExposure.capabilities)

        assertEquals(ToolSideEffect.LOCAL_STATE, writer.descriptor.sideEffect)
        assertEquals(ToolSideEffect.READ_ONLY, search.descriptor.sideEffect)
        assertEquals(ToolSideEffect.LOCAL_STATE, deleter.descriptor.sideEffect)

        assertEquals(SovereignNoteToolContract.toolId, writer.descriptor.id)
        assertEquals(SovereignNoteSearchToolContract.toolId, search.descriptor.id)
        assertEquals(SovereignNoteDeleteToolContract.toolId, deleter.descriptor.id)

        assertEquals(SovereignNoteToolContract.MAX_NOTE_CHARS, writer.descriptor.inputContract.maxLength)
        assertEquals(SovereignNoteSearchToolContract.MAX_QUERY_CHARS, search.descriptor.inputContract.maxLength)
        assertEquals(SovereignNoteDeleteToolContract.UUID_CHARS, deleter.descriptor.inputContract.maxLength)
    }
}
