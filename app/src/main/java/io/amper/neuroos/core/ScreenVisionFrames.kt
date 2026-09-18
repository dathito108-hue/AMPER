package io.amper.neuroos.core

import java.util.concurrent.atomic.AtomicReference

data class ScreenVisionFrameSnapshot(
    val mediaType: String,
    val displayName: String,
    val bytes: ByteArray,
    val capturedAtEpochMs: Long
) {
    init {
        require(mediaType.startsWith("image/")) { "screen frame must be an image" }
        require(displayName.isNotBlank())
        require(bytes.isNotEmpty())
        require(bytes.size <= InferenceAttachment.MAX_ATTACHMENT_BYTES) {
            "screen frame exceeds inference attachment limit"
        }
        require(capturedAtEpochMs >= 0L)
    }

    fun toAttachment(): InferenceAttachment =
        InferenceAttachment.fromBytes(
            kind = InferenceAttachmentKind.IMAGE,
            source = InferenceAttachmentSource.DEVICE_SCREEN,
            mediaType = mediaType,
            displayName = displayName,
            bytes = bytes
        )
}

/**
 * Process-local, single-latest-frame store for a user-authorized screen projection session.
 *
 * The store is deliberately non-durable: screen pixels never enter sovereign memory or disk.
 * Writers replace the complete immutable snapshot atomically; readers always receive a fresh copy.
 */
class EphemeralScreenVisionFrameStore {
    private val latest = AtomicReference<ScreenVisionFrameSnapshot?>(null)

    fun publish(
        mediaType: String,
        displayName: String,
        bytes: ByteArray,
        capturedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        latest.set(
            ScreenVisionFrameSnapshot(
                mediaType = mediaType,
                displayName = displayName,
                bytes = bytes.copyOf(),
                capturedAtEpochMs = capturedAtEpochMs
            )
        )
    }

    fun latest(
        maxAgeMs: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ScreenVisionFrameSnapshot? {
        require(maxAgeMs > 0L) { "screen frame max age must be positive" }
        require(nowEpochMs >= 0L)
        val snapshot = latest.get() ?: return null
        val age = nowEpochMs - snapshot.capturedAtEpochMs
        if (age < 0L || age > maxAgeMs) return null
        return snapshot.copy(bytes = snapshot.bytes.copyOf())
    }

    fun clear() {
        latest.set(null)
    }

    fun hasFrame(): Boolean = latest.get() != null
}
