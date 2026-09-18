package io.amper.neuroos.core

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class EncodedCameraVisionFrame(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val jpegQuality: Int
) {
    init {
        require(width > 0 && height > 0)
        require(jpegQuality in 1..100)
        require(jpegBytes.isNotEmpty())
        require(jpegBytes.size <= InferenceAttachment.MAX_ATTACHMENT_BYTES)
    }

    fun toAttachment(
        displayName: String = "camera-vision.jpg"
    ): InferenceAttachment = InferenceAttachment.fromBytes(
        kind = InferenceAttachmentKind.IMAGE,
        source = InferenceAttachmentSource.CAMERA_SNAPSHOT,
        mediaType = "image/jpeg",
        displayName = displayName,
        bytes = jpegBytes
    )
}

/**
 * Converts a user-approved Android camera bitmap into one bounded model-native IMAGE payload.
 *
 * No file is created. Scaling and JPEG compression happen entirely in process memory. The caller
 * owns the source bitmap; any temporary scaled bitmap created here is recycled before return.
 */
object AndroidCameraVisionAttachmentEncoder {
    const val MAX_EDGE_PX = 1_536
    const val TARGET_MAX_BYTES = 4 * 1024 * 1024
    private const val INITIAL_JPEG_QUALITY = 90
    private const val MIN_JPEG_QUALITY = 55
    private const val JPEG_QUALITY_STEP = 7

    fun encode(bitmap: Bitmap): Result<EncodedCameraVisionFrame> = runCatching {
        require(!bitmap.isRecycled) { "camera bitmap is recycled" }
        require(bitmap.width > 0 && bitmap.height > 0) {
            "camera bitmap dimensions must be positive"
        }

        val scaled = scaleWithin(bitmap, MAX_EDGE_PX)
        try {
            var quality = INITIAL_JPEG_QUALITY
            var encoded: ByteArray? = null
            while (quality >= MIN_JPEG_QUALITY) {
                val out = ByteArrayOutputStream()
                check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    "camera JPEG compression failed"
                }
                val bytes = out.toByteArray()
                if (
                    bytes.isNotEmpty() &&
                    bytes.size <= TARGET_MAX_BYTES &&
                    bytes.size <= InferenceAttachment.MAX_ATTACHMENT_BYTES
                ) {
                    encoded = bytes
                    break
                }
                quality -= JPEG_QUALITY_STEP
            }

            val jpeg = requireNotNull(encoded) {
                "camera frame cannot be compressed within bounded inference payload"
            }
            EncodedCameraVisionFrame(
                jpegBytes = jpeg,
                width = scaled.width,
                height = scaled.height,
                jpegQuality = quality
            )
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    private fun scaleWithin(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxEdge) return bitmap
        val scale = maxEdge.toDouble() / largest.toDouble()
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
