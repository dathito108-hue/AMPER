package io.amper.neuroos.core

import java.security.MessageDigest

/**
 * One-entry, process-local cache for exact tokenizer preflight.
 *
 * The key stores only the resident native session id plus SHA-256 identities for prompt text and
 * ephemeral attachment bytes. User prompt/attachment contents are never retained by the cache.
 * A single entry is intentional: it eliminates duplicate estimator calls inside one routing pass
 * without turning transient inference inputs into a general-purpose memory surface.
 */
internal class NativePromptTokenPreflightCache {
    private var entry: Entry? = null

    @Synchronized
    fun get(key: NativePromptTokenPreflightKey): Int? =
        entry?.takeIf { it.key == key }?.tokens

    @Synchronized
    fun put(key: NativePromptTokenPreflightKey, tokens: Int) {
        require(tokens > 0) { "prompt token cache requires a positive token count" }
        entry = Entry(key, tokens)
    }

    @Synchronized
    fun clear() {
        entry = null
    }

    private data class Entry(
        val key: NativePromptTokenPreflightKey,
        val tokens: Int
    )
}

internal data class NativePromptTokenPreflightKey(
    val sessionKey: String,
    val promptSha256: String,
    val attachments: List<NativePromptAttachmentIdentity>
) {
    init {
        require(sessionKey.isNotBlank())
        require(promptSha256.length == 64)
    }

    companion object {
        fun from(
            sessionKey: String,
            request: InferenceRequest,
            includeAttachments: Boolean
        ): NativePromptTokenPreflightKey {
            val promptDigest = MessageDigest.getInstance("SHA-256")
                .digest(request.prompt.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val attachmentIdentities = if (includeAttachments) {
                request.attachments.map {
                    NativePromptAttachmentIdentity(
                        kind = it.kind,
                        sha256 = it.sha256
                    )
                }
            } else {
                emptyList()
            }
            return NativePromptTokenPreflightKey(
                sessionKey = sessionKey,
                promptSha256 = promptDigest,
                attachments = attachmentIdentities
            )
        }
    }
}

internal data class NativePromptAttachmentIdentity(
    val kind: InferenceAttachmentKind,
    val sha256: String
) {
    init {
        require(sha256.length == 64)
    }
}
