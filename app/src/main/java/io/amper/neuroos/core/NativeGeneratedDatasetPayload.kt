package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class NativeGeneratedDatasetKind {
    VERIFIED_GOAL_EXPERIENCE,
    REFLEX_DECISION_EXPERIENCE
}

/**
 * Canonical trainer-facing envelope for any AMPER-generated dataset payload.
 *
 * Dataset-specific stores remain responsible for their schema and admission rules. The native
 * training pipeline sees only one payload abstraction, preventing one request field/resolver branch
 * per future dataset family.
 */
data class NativeGeneratedDatasetPayload(
    val kind: NativeGeneratedDatasetKind,
    val manifest: NativeDatasetShardManifest,
    val payload: String
) {
    init {
        require(manifest.rights == NativeDatasetRights.GENERATED_INTERNAL)
        require(payload.isNotEmpty())
        require(
            payload.toByteArray(StandardCharsets.UTF_8).size.toLong() == manifest.byteCount
        ) {
            "generated dataset payload byte count does not match manifest"
        }
        require(generatedDatasetSha256(payload) == manifest.sha256) {
            "generated dataset payload digest does not match manifest"
        }
    }

    val authorityBearing: Boolean
        get() = false
}

fun interface NativeGeneratedDatasetResolver {
    fun resolve(id: NativeDatasetShardId): NativeGeneratedDatasetPayload?
}

class CompositeNativeGeneratedDatasetResolver(
    private val resolvers: List<NativeGeneratedDatasetResolver>
) : NativeGeneratedDatasetResolver {
    init {
        require(resolvers.isNotEmpty())
    }

    override fun resolve(id: NativeDatasetShardId): NativeGeneratedDatasetPayload? {
        val matches = resolvers.mapNotNull { it.resolve(id) }
        require(matches.size <= 1) {
            "generated dataset id resolves through multiple payload providers: " + id.value
        }
        return matches.singleOrNull()
    }
}

class NativeExperienceGeneratedDatasetResolver(
    private val store: NativeExperienceDatasetStore
) : NativeGeneratedDatasetResolver {
    override fun resolve(id: NativeDatasetShardId): NativeGeneratedDatasetPayload? =
        store.getShard(id)?.let { shard ->
            NativeGeneratedDatasetPayload(
                kind = NativeGeneratedDatasetKind.VERIFIED_GOAL_EXPERIENCE,
                manifest = shard.manifest,
                payload = shard.payload
            )
        }
}

class ReflexExperienceGeneratedDatasetResolver(
    private val store: ReflexExperienceDatasetStore
) : NativeGeneratedDatasetResolver {
    override fun resolve(id: NativeDatasetShardId): NativeGeneratedDatasetPayload? =
        store.getShard(id)?.let { shard ->
            NativeGeneratedDatasetPayload(
                kind = NativeGeneratedDatasetKind.REFLEX_DECISION_EXPERIENCE,
                manifest = shard.manifest,
                payload = shard.payload
            )
        }
}

private fun generatedDatasetSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
