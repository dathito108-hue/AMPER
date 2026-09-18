package io.amper.neuroos.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

enum class InferenceAttachmentKind {
    IMAGE,
    AUDIO
}

enum class InferenceAttachmentSource {
    USER_SELECTED,
    CAMERA_SNAPSHOT,
    DEVICE_SCREEN,
    LIVE_MICROPHONE,
    VOICE_SESSION
}

/**
 * Ephemeral bounded attachment for one inference turn.
 *
 * Bytes are owned by this object and never written to sovereign memory by the core runtime.
 * Backends may consume them only during the active request. Construction copies the caller buffer
 * so later UI mutation cannot change the admitted payload.
 */
class InferenceAttachment private constructor(
    val id: String,
    val kind: InferenceAttachmentKind,
    val source: InferenceAttachmentSource,
    val mediaType: String,
    val displayName: String,
    bytes: ByteArray
) {
    private val payload = bytes.copyOf()

    val lengthBytes: Int
        get() = payload.size

    val sha256: String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it) }

    init {
        require(id.isNotBlank())
        require(mediaType.isNotBlank())
        require(displayName.isNotBlank())
        require(payload.isNotEmpty())
        require(payload.size <= MAX_ATTACHMENT_BYTES) {
            "attachment exceeds per-item limit: ${payload.size} > $MAX_ATTACHMENT_BYTES"
        }
        when (kind) {
            InferenceAttachmentKind.IMAGE ->
                require(mediaType.lowercase().startsWith("image/")) {
                    "image attachment must use an image/* media type"
                }
            InferenceAttachmentKind.AUDIO ->
                require(mediaType.lowercase().startsWith("audio/")) {
                    "audio attachment must use an audio/* media type"
                }
        }
        when (source) {
            InferenceAttachmentSource.CAMERA_SNAPSHOT,
            InferenceAttachmentSource.DEVICE_SCREEN ->
                require(kind == InferenceAttachmentKind.IMAGE) {
                    "$source must be bound to an IMAGE attachment"
                }

            InferenceAttachmentSource.LIVE_MICROPHONE,
            InferenceAttachmentSource.VOICE_SESSION ->
                require(kind == InferenceAttachmentKind.AUDIO) {
                    "$source must be bound to an AUDIO attachment"
                }

            InferenceAttachmentSource.USER_SELECTED -> Unit
        }
    }

    fun openStream(): InputStream = ByteArrayInputStream(payload)

    fun readBytes(): ByteArray = payload.copyOf()

    override fun toString(): String =
        "InferenceAttachment(id=$id, kind=$kind, source=$source, mediaType=$mediaType, displayName=$displayName, lengthBytes=$lengthBytes, sha256=$sha256)"

    companion object {
        const val MAX_ATTACHMENT_BYTES: Int = 8 * 1024 * 1024
        const val MAX_ATTACHMENTS_PER_REQUEST: Int = 4
        const val MAX_TOTAL_ATTACHMENT_BYTES: Int = 16 * 1024 * 1024

        fun fromBytes(
            kind: InferenceAttachmentKind,
            mediaType: String,
            displayName: String,
            bytes: ByteArray,
            source: InferenceAttachmentSource = InferenceAttachmentSource.USER_SELECTED,
            id: String = UUID.randomUUID().toString()
        ): InferenceAttachment = InferenceAttachment(
            id = id,
            kind = kind,
            source = source,
            mediaType = mediaType,
            displayName = displayName,
            bytes = bytes
        )
    }
}

/**
 * Backend opt-in for model-native attachment consumption.
 *
 * Merely declaring a model capability is insufficient: Titan also requires the selected backend to
 * explicitly advertise every attachment kind in the request. Text-only backends therefore fail
 * admission before execution and can never receive opaque multimodal payloads accidentally.
 */
interface AttachmentAwareInferenceBackend : InferenceBackend {
    fun supportedAttachmentKinds(model: InstalledModel): Set<InferenceAttachmentKind>
}

/** Optional native-adapter surface used by [StreamingAdapterBackend]. */
interface AttachmentAwareNativeInferenceAdapter : NativeInferenceAdapter {
    fun supportedAttachmentKinds(model: InstalledModel): Set<InferenceAttachmentKind>
}

object MultimodalInferencePolicy {
    fun requiredCapabilities(
        attachments: List<InferenceAttachment>
    ): Set<CapabilityId> = buildSet {
        attachments.forEach { attachment ->
            when (attachment.kind) {
                InferenceAttachmentKind.IMAGE -> add(TitanCapabilities.VISION)
                InferenceAttachmentKind.AUDIO -> add(TitanCapabilities.AUDIO_UNDERSTANDING)
            }
        }
    }

    fun validateAttachments(attachments: List<InferenceAttachment>) {
        require(attachments.size <= InferenceAttachment.MAX_ATTACHMENTS_PER_REQUEST) {
            "too many inference attachments: ${attachments.size}"
        }
        val total = attachments.fold(0L) { acc, attachment ->
            Math.addExact(acc, attachment.lengthBytes.toLong())
        }
        require(total <= InferenceAttachment.MAX_TOTAL_ATTACHMENT_BYTES.toLong()) {
            "inference attachments exceed total byte limit: $total"
        }
        require(attachments.map { it.id }.toSet().size == attachments.size) {
            "inference attachment ids must be unique"
        }
    }

    fun backendSupports(
        backend: InferenceBackend,
        model: InstalledModel,
        attachments: List<InferenceAttachment>
    ): Boolean {
        if (attachments.isEmpty()) return true
        val aware = backend as? AttachmentAwareInferenceBackend ?: return false
        val supported = aware.supportedAttachmentKinds(model)
        return attachments.all { it.kind in supported }
    }

    fun rejectionReason(
        backend: InferenceBackend,
        model: InstalledModel,
        attachments: List<InferenceAttachment>
    ): String? {
        if (attachments.isEmpty()) return null
        val aware = backend as? AttachmentAwareInferenceBackend
            ?: return "multimodal-backend-required"
        val supported = aware.supportedAttachmentKinds(model)
        val missing = attachments.map { it.kind }.filterNot { it in supported }.distinct()
        return if (missing.isEmpty()) null
        else "attachment-kind-unsupported:${missing.joinToString(",") { it.name.lowercase() }}"
    }
}
