package io.amper.neuroos.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

class AndroidInferenceAttachmentLoader(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun load(
        uri: Uri,
        kind: InferenceAttachmentKind
    ): Result<InferenceAttachment> = runCatching {
        val metadata = queryMetadata(uri)
        val mediaType = resolver.getType(uri)
            ?: when (kind) {
                InferenceAttachmentKind.IMAGE -> "image/*"
                InferenceAttachmentKind.AUDIO -> "audio/*"
            }
        val normalized = mediaType.lowercase()
        when (kind) {
            InferenceAttachmentKind.IMAGE ->
                require(normalized.startsWith("image/")) {
                    "selected document is not an image"
                }
            InferenceAttachmentKind.AUDIO ->
                require(normalized.startsWith("audio/")) {
                    "selected document is not audio"
                }
        }
        metadata.second?.let { declared ->
            require(declared > 0L) { "attachment is empty" }
            require(declared <= InferenceAttachment.MAX_ATTACHMENT_BYTES.toLong()) {
                "attachment exceeds per-item limit"
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total = Math.addExact(total, read)
                require(total <= InferenceAttachment.MAX_ATTACHMENT_BYTES) {
                    "attachment exceeds per-item limit while reading"
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: throw IOException("unable to open attachment URI: $uri")
        require(bytes.isNotEmpty()) { "attachment is empty" }
        InferenceAttachment.fromBytes(
            kind = kind,
            mediaType = mediaType,
            displayName = metadata.first,
            bytes = bytes
        )
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long?> {
        var name = uri.lastPathSegment ?: "attachment"
        var size: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        return name to size
    }
}
