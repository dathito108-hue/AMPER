package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexDecisionCortexTest {
    private fun descriptor(
        capability: CapabilityId,
        sideEffect: ToolSideEffect,
        accepted: Set<String> = emptySet(),
        maxLength: Int = 256
    ) = ToolDescriptor(
        id = ToolId("test-" + capability.value),
        name = capability.value,
        capability = capability,
        sideEffect = sideEffect,
        inputContract = ToolInputContract(
            description = "test contract",
            acceptedValues = accepted,
            maxLength = maxLength
        )
    )

    private val descriptors = listOf(
        descriptor(
            DeviceStatusToolContract.capability,
            ToolSideEffect.READ_ONLY,
            setOf("summary", "status"),
            16
        ),
        descriptor(
            SovereignStatusToolContract.capability,
            ToolSideEffect.READ_ONLY,
            setOf("summary", "models", "backends", "resources"),
            16
        ),
        descriptor(
            AndroidSettingsOpenToolContract.capability,
            ToolSideEffect.EXTERNAL,
            AndroidSettingsTarget.commands,
            32
        ),
        descriptor(
            AndroidTimerPrepareToolContract.capability,
            ToolSideEffect.EXTERNAL,
            maxLength = AndroidTimerPrepareToolContract.MAX_INPUT_CHARS
        ),
        descriptor(
            AndroidShareTextToolContract.capability,
            ToolSideEffect.EXTERNAL,
            maxLength = AndroidShareTextToolContract.MAX_TEXT_CHARS
        )
    )

    @Test
    fun currentDeviceStatusRoutesToTypedReadOnlyAction() {
        val decision = DeterministicReflexDecisionCortex.decide(
            ReflexDecisionRequest(
                "Pin và RAM hiện tại còn bao nhiêu?",
                descriptors
            )
        )

        assertTrue(decision.fastPathEligible)
        assertEquals(DeviceStatusToolContract.capability, decision.capability)
        assertEquals("summary", decision.input)
        assertFalse(decision.authorityBearing)
    }

    @Test
    fun ambiguousKnowledgeQuestionEscalatesToSystem2() {
        val decision = DeterministicReflexDecisionCortex.decide(
            ReflexDecisionRequest(
                "Pin lithium hoạt động như thế nào?",
                descriptors
            )
        )

        assertEquals(ReflexDecisionDisposition.ESCALATE_SYSTEM2, decision.disposition)
        assertFalse(decision.fastPathEligible)
    }

    @Test
    fun explicitTimerRequestCompilesToBoundedTimerContract() {
        val decision = DeterministicReflexDecisionCortex.decide(
            ReflexDecisionRequest(
                "Hẹn giờ 5 phút",
                descriptors
            )
        )

        assertTrue(decision.fastPathEligible)
        assertEquals(AndroidTimerPrepareToolContract.capability, decision.capability)
        assertEquals("seconds=300", decision.input)
    }

    @Test
    fun explicitSettingsAndShareRequestsStayTyped() {
        val settings = DeterministicReflexDecisionCortex.decide(
            ReflexDecisionRequest("Mở cài đặt Bluetooth", descriptors)
        )
        val share = DeterministicReflexDecisionCortex.decide(
            ReflexDecisionRequest("Chia sẻ: xin chào AMPER", descriptors)
        )

        assertEquals(AndroidSettingsOpenToolContract.capability, settings.capability)
        assertEquals("bluetooth", settings.input)
        assertEquals(AndroidShareTextToolContract.capability, share.capability)
        assertEquals("xin chào AMPER", share.input)
    }

    @Test
    fun missingLiveDescriptorForcesSystem2Fallback() {
        val decision = DeterministicReflexDecisionCortex.decide(
            ReflexDecisionRequest(
                "Hẹn giờ 5 phút",
                descriptors.filterNot {
                    it.capability == AndroidTimerPrepareToolContract.capability
                }
            )
        )

        assertEquals(ReflexDecisionDisposition.ESCALATE_SYSTEM2, decision.disposition)
    }
}
