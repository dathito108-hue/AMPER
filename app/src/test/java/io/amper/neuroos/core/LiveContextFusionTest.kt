package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveContextFusionTest {
    @Test
    fun explicitLiveSourceIsBoundToItsDeclaredModality() {
        val rejected = runCatching {
            InferenceAttachment.fromBytes(
                kind = InferenceAttachmentKind.AUDIO,
                source = InferenceAttachmentSource.CAMERA_SNAPSHOT,
                mediaType = "audio/wav",
                displayName = "invalid.wav",
                bytes = byteArrayOf(1, 2, 3)
            )
        }

        assertTrue(rejected.isFailure)
    }

    @Test
    fun freshLiveSourceReplacesOlderPayloadButPreservesUserDocument() {
        val userImage = attachment(
            kind = InferenceAttachmentKind.IMAGE,
            source = InferenceAttachmentSource.USER_SELECTED,
            name = "reference.png",
            value = 1
        )
        val oldScreen = attachment(
            kind = InferenceAttachmentKind.IMAGE,
            source = InferenceAttachmentSource.DEVICE_SCREEN,
            name = "old-screen.jpg",
            value = 2
        )
        val freshScreen = attachment(
            kind = InferenceAttachmentKind.IMAGE,
            source = InferenceAttachmentSource.DEVICE_SCREEN,
            name = "fresh-screen.jpg",
            value = 3
        )
        val voice = attachment(
            kind = InferenceAttachmentKind.AUDIO,
            source = InferenceAttachmentSource.VOICE_SESSION,
            name = "voice.wav",
            value = 4
        )

        val bundle = LiveContextFusion.fuse(
            existing = listOf(userImage, oldScreen),
            fresh = listOf(freshScreen, voice)
        ).getOrThrow()

        assertEquals(3, bundle.attachments.size)
        assertTrue(bundle.attachments.any { it.id == userImage.id })
        assertFalse(bundle.attachments.any { it.id == oldScreen.id })
        assertTrue(bundle.attachments.any { it.id == freshScreen.id })
        assertTrue(bundle.attachments.any { it.id == voice.id })
        assertTrue(bundle.crossModal)
        assertEquals(
            setOf(TitanCapabilities.VISION, TitanCapabilities.AUDIO_UNDERSTANDING),
            bundle.requiredCapabilities
        )
        assertEquals(
            setOf(
                InferenceAttachmentSource.USER_SELECTED,
                InferenceAttachmentSource.DEVICE_SCREEN,
                InferenceAttachmentSource.VOICE_SESSION
            ),
            bundle.sources
        )
    }

    @Test
    fun fourExplicitLiveSourcesFuseWithinCanonicalAttachmentLimit() {
        val camera = attachment(
            InferenceAttachmentKind.IMAGE,
            InferenceAttachmentSource.CAMERA_SNAPSHOT,
            "camera.jpg",
            1
        )
        val screen = attachment(
            InferenceAttachmentKind.IMAGE,
            InferenceAttachmentSource.DEVICE_SCREEN,
            "screen.jpg",
            2
        )
        val microphone = attachment(
            InferenceAttachmentKind.AUDIO,
            InferenceAttachmentSource.LIVE_MICROPHONE,
            "mic.wav",
            3
        )
        val voice = attachment(
            InferenceAttachmentKind.AUDIO,
            InferenceAttachmentSource.VOICE_SESSION,
            "voice.wav",
            4
        )

        val bundle = LiveContextFusion.fuse(
            existing = listOf(camera, microphone),
            fresh = listOf(screen, voice)
        ).getOrThrow()

        assertEquals(4, bundle.attachments.size)
        assertEquals(4, bundle.sources.size)
        assertEquals(
            setOf(InferenceAttachmentKind.IMAGE, InferenceAttachmentKind.AUDIO),
            bundle.attachmentKinds
        )
        assertTrue(bundle.crossModal)
    }

    @Test
    fun duplicateFreshLiveSourceFailsClosed() {
        val first = attachment(
            InferenceAttachmentKind.IMAGE,
            InferenceAttachmentSource.DEVICE_SCREEN,
            "screen-a.jpg",
            1
        )
        val second = attachment(
            InferenceAttachmentKind.IMAGE,
            InferenceAttachmentSource.DEVICE_SCREEN,
            "screen-b.jpg",
            2
        )

        val result = LiveContextFusion.fuse(
            existing = emptyList(),
            fresh = listOf(first, second)
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun fusionCannotBypassCanonicalAttachmentCountLimit() {
        val existing = (1..4).map { index ->
            attachment(
                kind = InferenceAttachmentKind.IMAGE,
                source = InferenceAttachmentSource.USER_SELECTED,
                name = "user-$index.jpg",
                value = index
            )
        }
        val freshScreen = attachment(
            InferenceAttachmentKind.IMAGE,
            InferenceAttachmentSource.DEVICE_SCREEN,
            "screen.jpg",
            9
        )

        val result = LiveContextFusion.fuse(
            existing = existing,
            fresh = listOf(freshScreen)
        )

        assertTrue(result.isFailure)
    }

    private fun attachment(
        kind: InferenceAttachmentKind,
        source: InferenceAttachmentSource,
        name: String,
        value: Int
    ): InferenceAttachment =
        InferenceAttachment.fromBytes(
            kind = kind,
            source = source,
            mediaType = when (kind) {
                InferenceAttachmentKind.IMAGE -> "image/jpeg"
                InferenceAttachmentKind.AUDIO -> "audio/wav"
            },
            displayName = name,
            bytes = byteArrayOf(value.toByte(), (value + 1).toByte())
        )
}
