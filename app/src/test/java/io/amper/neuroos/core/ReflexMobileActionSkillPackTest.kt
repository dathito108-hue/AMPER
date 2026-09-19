package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexMobileActionSkillPackTest {
    private fun descriptor(
        capability: CapabilityId,
        id: ToolId,
        sideEffect: ToolSideEffect,
        accepted: Set<String> = emptySet(),
        maxLength: Int = 256
    ) = ToolDescriptor(
        id = id,
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
            AndroidAppLaunchToolContract.capability,
            AndroidAppLaunchToolContract.toolId,
            ToolSideEffect.EXTERNAL,
            maxLength = AndroidAppLaunchToolContract.MAX_PACKAGE_CHARS
        ),
        descriptor(
            AndroidWebSearchToolContract.capability,
            AndroidWebSearchToolContract.toolId,
            ToolSideEffect.EXTERNAL,
            maxLength = AndroidWebSearchToolContract.MAX_QUERY_CHARS
        ),
        descriptor(
            AndroidClipboardWriteToolContract.capability,
            AndroidClipboardWriteToolContract.toolId,
            ToolSideEffect.LOCAL_STATE,
            maxLength = AndroidClipboardWriteToolContract.MAX_TEXT_CHARS
        ),
        descriptor(
            AndroidFilesBrowseToolContract.capability,
            AndroidFilesBrowseToolContract.toolId,
            ToolSideEffect.EXTERNAL,
            setOf(AndroidFilesBrowseToolContract.COMMAND),
            16
        ),
        descriptor(
            AndroidContactComposeToolContract.capability,
            AndroidContactComposeToolContract.toolId,
            ToolSideEffect.EXTERNAL,
            maxLength = AndroidContactComposeToolContract.MAX_INPUT_CHARS
        ),
        descriptor(
            AndroidCalendarComposeToolContract.capability,
            AndroidCalendarComposeToolContract.toolId,
            ToolSideEffect.EXTERNAL,
            maxLength = AndroidCalendarComposeToolContract.MAX_INPUT_CHARS
        ),
        descriptor(
            AndroidAlarmPrepareToolContract.capability,
            AndroidAlarmPrepareToolContract.toolId,
            ToolSideEffect.EXTERNAL,
            maxLength = AndroidAlarmPrepareToolContract.MAX_INPUT_CHARS
        ),
        descriptor(
            AndroidMediaOpenToolContract.capability,
            AndroidMediaOpenToolContract.toolId,
            ToolSideEffect.EXTERNAL,
            maxLength = AndroidMediaOpenToolContract.MAX_URL_CHARS
        ),
        descriptor(
            AndroidNotificationSettingsToolContract.capability,
            AndroidNotificationSettingsToolContract.toolId,
            ToolSideEffect.EXTERNAL,
            setOf(AndroidNotificationSettingsToolContract.COMMAND),
            8
        ),
        descriptor(
            AndroidHomeOpenToolContract.capability,
            AndroidHomeOpenToolContract.toolId,
            ToolSideEffect.EXTERNAL,
            setOf(AndroidHomeOpenToolContract.COMMAND),
            8
        )
    )

    @Test
    fun bootstrapCompilesUniversalMobileRequestsIntoTypedContracts() {
        val cases = listOf(
            Triple(
                "Mở ứng dụng com.example.reader",
                AndroidAppLaunchToolContract.capability,
                "com.example.reader"
            ),
            Triple(
                "Tìm web: AMPER mobile AGI",
                AndroidWebSearchToolContract.capability,
                "AMPER mobile AGI"
            ),
            Triple(
                "Sao chép: nội dung an toàn",
                AndroidClipboardWriteToolContract.capability,
                "nội dung an toàn"
            ),
            Triple(
                "Mở tệp",
                AndroidFilesBrowseToolContract.capability,
                AndroidFilesBrowseToolContract.COMMAND
            ),
            Triple(
                "Liên hệ: name=Ada;phone=+1 555 0100;email=ada@example.com",
                AndroidContactComposeToolContract.capability,
                "name=Ada;phone=+1 555 0100;email=ada@example.com"
            ),
            Triple(
                "Lịch: title=Review;start_epoch_ms=1789830000000;duration_minutes=45;location=Office",
                AndroidCalendarComposeToolContract.capability,
                "title=Review;start_epoch_ms=1789830000000;duration_minutes=45;location=Office"
            ),
            Triple(
                "Đặt báo thức 07:30",
                AndroidAlarmPrepareToolContract.capability,
                "hour=7;minute=30"
            ),
            Triple(
                "Mở media: https://example.com/a.mp3",
                AndroidMediaOpenToolContract.capability,
                "https://example.com/a.mp3"
            ),
            Triple(
                "Mở cài đặt thông báo",
                AndroidNotificationSettingsToolContract.capability,
                AndroidNotificationSettingsToolContract.COMMAND
            ),
            Triple(
                "Về màn hình chính",
                AndroidHomeOpenToolContract.capability,
                AndroidHomeOpenToolContract.COMMAND
            )
        )

        cases.forEach { (input, capability, expectedInput) ->
            val decision = DeterministicReflexDecisionCortex.decide(
                ReflexDecisionRequest(input, descriptors)
            )
            assertEquals(input, ReflexDecisionDisposition.PROPOSE_ACTION, decision.disposition)
            assertEquals(input, capability, decision.capability)
            assertEquals(input, expectedInput, decision.input)
            assertTrue(input, decision.fastPathEligible)
            assertFalse(input, decision.authorityBearing)
        }
    }

    @Test
    fun malformedOrAmbiguousUniversalRequestsEscalate() {
        val inputs = listOf(
            "Mở ứng dụng YouTube",
            "Tìm giúp tôi thứ gì đó",
            "copy this without an explicit colon",
            "Liên hệ: name=;phone=;email=",
            "Lịch: title=Review;start_epoch_ms=bad;duration_minutes=30",
            "Đặt báo thức 25:99",
            "Mở media: file:///sdcard/private.txt",
            "thay đổi cài đặt thông báo cho tôi"
        )

        inputs.forEach { input ->
            val decision = DeterministicReflexDecisionCortex.decide(
                ReflexDecisionRequest(input, descriptors)
            )
            assertEquals(
                input,
                ReflexDecisionDisposition.ESCALATE_SYSTEM2,
                decision.disposition
            )
        }
    }

    @Test
    fun learnedSystem1CanUseNewCapabilityOnlyWhenDeterministicBinderIndependentlyAgrees() {
        val controller = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate {
                Result.success(Unit)
            },
            clock = { 10L }
        )
        controller.activate(
            fakePort(
                capability = AndroidWebSearchToolContract.capability
            )
        ).getOrThrow()

        val bound = controller.decide(
            ReflexDecisionRequest(
                userInput = "Tìm web: on-device inference",
                descriptors = descriptors
            )
        )
        assertEquals(ReflexDecisionSource.NATIVE_SYSTEM1, bound.source)
        assertEquals(ReflexDecisionDisposition.PROPOSE_ACTION, bound.disposition)
        assertEquals(AndroidWebSearchToolContract.capability, bound.capability)
        assertEquals("on-device inference", bound.input)

        val rejected = controller.decide(
            ReflexDecisionRequest(
                userInput = "Mở tệp",
                descriptors = descriptors
            )
        )
        assertEquals(ReflexDecisionSource.NATIVE_SYSTEM1, rejected.source)
        assertEquals(ReflexDecisionDisposition.ESCALATE_SYSTEM2, rejected.disposition)
    }

    @Test
    fun fastRendererDoesNotClaimUserMediatedExternalActionsWereCompleted() {
        val contact = ActionOutcome(
            status = ActionStatus.EXECUTED,
            proposal = ActionProposal(
                capability = AndroidContactComposeToolContract.capability,
                reason = "compose",
                input = "name=Ada"
            ),
            toolId = AndroidContactComposeToolContract.toolId,
            sideEffect = ToolSideEffect.EXTERNAL,
            output = "contact_ui_opened;has_name=true;has_phone=false;has_email=false;saved=false"
        )
        val files = ActionOutcome(
            status = ActionStatus.EXECUTED,
            proposal = ActionProposal(
                capability = AndroidFilesBrowseToolContract.capability,
                reason = "browse",
                input = AndroidFilesBrowseToolContract.COMMAND
            ),
            toolId = AndroidFilesBrowseToolContract.toolId,
            sideEffect = ToolSideEffect.EXTERNAL,
            output = "document_picker_opened;selection_performed=false"
        )

        val contactText = ReflexFastResponseRenderer.render(contact)
        val filesText = ReflexFastResponseRenderer.render(files)

        assertTrue(contactText.contains("not been saved"))
        assertTrue(filesText.contains("no document was selected"))
    }

    private fun fakePort(
        capability: CapabilityId
    ): NativeReflexDecisionPort = object : NativeReflexDecisionPort {
        override val checkpointId = NativeCheckpointId("mobile-skill-test")
        override val weightArtifactSha256 = "a".repeat(64)

        override fun predict(
            input: NativeReflexDecisionInput
        ): Result<NativeReflexDecisionPrediction> = Result.success(
            NativeReflexDecisionPrediction(
                disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                capability = capability,
                confidence = 0.999,
                uncertainty = 0.001
            )
        )
    }
}
