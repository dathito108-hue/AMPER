package io.amper.neuroos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenVisionFramesTest {
    @Test
    fun latestFrameIsCopiedAndBecomesImageAttachment() {
        val store = EphemeralScreenVisionFrameStore()
        val bytes = byteArrayOf(1, 2, 3, 4)

        store.publish(
            mediaType = "image/jpeg",
            displayName = "screen.jpg",
            bytes = bytes,
            capturedAtEpochMs = 1_000L
        )
        bytes[0] = 99

        val snapshot = store.latest(
            maxAgeMs = 2_000L,
            nowEpochMs = 2_000L
        )!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), snapshot.bytes)

        snapshot.bytes[1] = 88
        val reread = store.latest(
            maxAgeMs = 2_000L,
            nowEpochMs = 2_000L
        )!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), reread.bytes)

        val attachment = reread.toAttachment()
        assertEquals(InferenceAttachmentKind.IMAGE, attachment.kind)
        assertEquals("image/jpeg", attachment.mediaType)
        assertEquals("screen.jpg", attachment.displayName)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), attachment.readBytes())
    }

    @Test
    fun staleOrFutureFrameFailsFreshnessRead() {
        val store = EphemeralScreenVisionFrameStore()
        store.publish(
            mediaType = "image/jpeg",
            displayName = "screen.jpg",
            bytes = byteArrayOf(7),
            capturedAtEpochMs = 1_000L
        )

        assertNull(store.latest(maxAgeMs = 500L, nowEpochMs = 1_501L))
        assertNull(store.latest(maxAgeMs = 500L, nowEpochMs = 999L))
    }

    @Test
    fun publishReplacesPreviousFrameAndClearDropsPixels() {
        val store = EphemeralScreenVisionFrameStore()
        assertFalse(store.hasFrame())

        store.publish(
            mediaType = "image/jpeg",
            displayName = "first.jpg",
            bytes = byteArrayOf(1),
            capturedAtEpochMs = 10L
        )
        store.publish(
            mediaType = "image/jpeg",
            displayName = "second.jpg",
            bytes = byteArrayOf(2),
            capturedAtEpochMs = 20L
        )

        assertTrue(store.hasFrame())
        assertEquals(
            "second.jpg",
            store.latest(maxAgeMs = 100L, nowEpochMs = 20L)!!.displayName
        )

        store.clear()
        assertFalse(store.hasFrame())
        assertNull(store.latest(maxAgeMs = 100L, nowEpochMs = 20L))
    }

    @Test
    fun oversizedScreenFrameIsRejectedBeforePublication() {
        val store = EphemeralScreenVisionFrameStore()
        val oversized = ByteArray(InferenceAttachment.MAX_ATTACHMENT_BYTES + 1)

        val failure = runCatching {
            store.publish(
                mediaType = "image/jpeg",
                displayName = "too-large.jpg",
                bytes = oversized,
                capturedAtEpochMs = 10L
            )
        }

        assertTrue(failure.isFailure)
        assertFalse(store.hasFrame())
    }
}
