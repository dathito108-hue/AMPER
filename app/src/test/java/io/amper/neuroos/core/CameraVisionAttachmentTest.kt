package io.amper.neuroos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraVisionAttachmentTest {
    @Test
    fun encodedCameraFrameBecomesImageAttachmentWithoutMutation() {
        val jpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0x01, 0x02, 0x03,
            0xff.toByte(), 0xd9.toByte()
        )
        val frame = EncodedCameraVisionFrame(
            jpegBytes = jpeg.copyOf(),
            width = 640,
            height = 480,
            jpegQuality = 90
        )
        val attachment = frame.toAttachment("camera.jpg")

        assertEquals(InferenceAttachmentKind.IMAGE, attachment.kind)
        assertEquals("image/jpeg", attachment.mediaType)
        assertEquals("camera.jpg", attachment.displayName)
        assertEquals(640, frame.width)
        assertEquals(480, frame.height)
        assertArrayEquals(jpeg, attachment.readBytes())

        jpeg[2] = 0x55
        assertTrue(attachment.readBytes()[2] != jpeg[2])
    }

    @Test
    fun encodedCameraFrameRejectsInvalidMetadataAndOversizePayload() {
        assertTrue(
            runCatching {
                EncodedCameraVisionFrame(
                    jpegBytes = byteArrayOf(1),
                    width = 0,
                    height = 480,
                    jpegQuality = 90
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                EncodedCameraVisionFrame(
                    jpegBytes = ByteArray(InferenceAttachment.MAX_ATTACHMENT_BYTES + 1),
                    width = 1,
                    height = 1,
                    jpegQuality = 90
                )
            }.isFailure
        )
    }

    @Test
    fun cameraImageRequiresVisionCapabilityInInferenceRequest() {
        val attachment = EncodedCameraVisionFrame(
            jpegBytes = byteArrayOf(1, 2, 3),
            width = 32,
            height = 32,
            jpegQuality = 80
        ).toAttachment()

        val rejected = runCatching {
            InferenceRequest(
                prompt = "What is in front of the camera?",
                attachments = listOf(attachment)
            )
        }
        assertTrue(rejected.isFailure)

        val accepted = InferenceRequest(
            prompt = "What is in front of the camera?",
            requiredCapabilities = setOf(
                TitanCapabilities.REASONING,
                TitanCapabilities.VISION
            ),
            attachments = listOf(attachment)
        )
        assertTrue(TitanCapabilities.VISION in accepted.requiredCapabilities)
    }
}
