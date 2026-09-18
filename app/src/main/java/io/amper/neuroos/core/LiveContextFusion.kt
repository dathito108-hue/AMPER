package io.amper.neuroos.core

data class LiveContextBundle(
    val attachments: List<InferenceAttachment>,
    val sources: Set<InferenceAttachmentSource>,
    val attachmentKinds: Set<InferenceAttachmentKind>,
    val requiredCapabilities: Set<CapabilityId>,
    val totalBytes: Long
) {
    init {
        require(attachments.isNotEmpty()) { "live context bundle is empty" }
        require(totalBytes > 0L)
        require(sources == attachments.map { it.source }.toSet())
        require(attachmentKinds == attachments.map { it.kind }.toSet())
    }

    val crossModal: Boolean
        get() = attachmentKinds.size > 1
}

/**
 * Ephemeral fusion policy for one inference turn.
 *
 * Fresh live sources replace older pending payloads from the same explicit source. User-selected
 * documents are never replaced implicitly. No bytes are persisted, summarized, or converted into
 * model authority here; normal multimodal capability/backend admission still runs afterwards.
 */
object LiveContextFusion {
    private val replaceableLiveSources = setOf(
        InferenceAttachmentSource.CAMERA_SNAPSHOT,
        InferenceAttachmentSource.DEVICE_SCREEN,
        InferenceAttachmentSource.LIVE_MICROPHONE,
        InferenceAttachmentSource.VOICE_SESSION
    )

    fun fuse(
        existing: List<InferenceAttachment>,
        fresh: List<InferenceAttachment>
    ): Result<LiveContextBundle> = runCatching {
        require(fresh.isNotEmpty()) { "no fresh live context attachment is available" }

        val duplicateFreshSources = fresh
            .filter { it.source in replaceableLiveSources }
            .groupBy { it.source }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateFreshSources.isEmpty()) {
            "fresh live context contains duplicate sources: " +
                duplicateFreshSources.joinToString(",")
        }

        val replacing = fresh
            .map { it.source }
            .filter { it in replaceableLiveSources }
            .toSet()

        val merged = buildList {
            existing.forEach { attachment ->
                if (attachment.source !in replacing) add(attachment)
            }
            addAll(fresh)
        }

        val duplicateLiveSources = merged
            .filter { it.source in replaceableLiveSources }
            .groupBy { it.source }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateLiveSources.isEmpty()) {
            "live context contains more than one payload for source: " +
                duplicateLiveSources.joinToString(",")
        }

        MultimodalInferencePolicy.validateAttachments(merged)
        val required = MultimodalInferencePolicy.requiredCapabilities(merged)
        val totalBytes = merged.fold(0L) { acc, attachment ->
            Math.addExact(acc, attachment.lengthBytes.toLong())
        }

        LiveContextBundle(
            attachments = merged.toList(),
            sources = merged.map { it.source }.toSet(),
            attachmentKinds = merged.map { it.kind }.toSet(),
            requiredCapabilities = required,
            totalBytes = totalBytes
        )
    }
}
