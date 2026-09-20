package io.amper.neuroos.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantNativeSystem2AdmissionPolicyTest {
    @Test
    fun ordinaryModelIdentityQuestionGoesDirectToTitan() {
        assertFalse(
            AssistantNativeSystem2AdmissionPolicy.requiresDeliberation(
                userPrompt = "What model and backend are you using right now?",
                attachments = emptyList(),
                explicitProfiles = emptyList()
            )
        )
    }

    @Test
    fun explicitPlanningTurnRetainsNativeSystem2() {
        assertTrue(
            AssistantNativeSystem2AdmissionPolicy.requiresDeliberation(
                userPrompt = "Build a bounded plan for this task",
                attachments = emptyList(),
                explicitProfiles = listOf(
                    setOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING)
                )
            )
        )
    }

    @Test
    fun complexVietnameseTurnRetainsNativeSystem2() {
        assertTrue(
            AssistantNativeSystem2AdmissionPolicy.requiresDeliberation(
                userPrompt = "Phân tích nguyên nhân và thiết kế kiến trúc sửa lỗi này",
                attachments = emptyList(),
                explicitProfiles = emptyList()
            )
        )
    }

    @Test
    fun multimodalTurnRetainsNativeSystem2() {
        val attachment = InferenceAttachment(
            kind = InferenceAttachmentKind.IMAGE,
            mimeType = "image/jpeg",
            bytes = byteArrayOf(1, 2, 3)
        )
        assertTrue(
            AssistantNativeSystem2AdmissionPolicy.requiresDeliberation(
                userPrompt = "What is this?",
                attachments = listOf(attachment),
                explicitProfiles = emptyList()
            )
        )
    }
}
