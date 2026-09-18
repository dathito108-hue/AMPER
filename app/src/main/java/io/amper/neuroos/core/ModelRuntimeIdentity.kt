package io.amper.neuroos.core

/**
 * Cheap, deterministic identity key used before either cold native load or warm-session reuse.
 *
 * SHA-256 and structural GGUF fields are the durable content identity captured at admission.
 * Locator is included because resolving a different user artifact must never inherit a warm
 * native session merely because its bytes happen to share the same digest. A provider-declared
 * length is checked when available; unknown provider lengths remain valid and are fully verified
 * on cold load by [ModelArtifactIdentityVerifier].
 */
data class ModelRuntimeIdentity(
    val modelId: ModelId,
    val locator: String,
    val sha256: String,
    val lengthBytes: Long?,
    val ggufVersion: Long,
    val tensorCount: ULong,
    val metadataKeyValueCount: ULong
) {
    companion object {
        fun bind(model: InstalledModel, source: ModelArtifactSource): ModelRuntimeIdentity {
            require(source.locator == model.locator) {
                "resolved model locator does not match installed identity"
            }

            val resolvedLength = source.lengthBytes
            require(resolvedLength == null || resolvedLength >= 0L) {
                "resolved model source declared a negative length"
            }
            model.lengthBytes?.let { installedLength ->
                resolvedLength?.let { resolved ->
                    require(resolved == installedLength) {
                        "resolved model length does not match installed identity"
                    }
                }
            }

            return ModelRuntimeIdentity(
                modelId = model.descriptor.id,
                locator = model.locator,
                sha256 = model.sha256,
                lengthBytes = model.lengthBytes,
                ggufVersion = model.ggufVersion,
                tensorCount = model.tensorCount,
                metadataKeyValueCount = model.metadataKeyValueCount
            )
        }
    }
}
