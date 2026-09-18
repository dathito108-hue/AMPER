package io.amper.neuroos.core

import java.io.File

/**
 * Revalidates an installed model against the durable admission identity captured in
 * [InstalledModel] before native code is allowed to load it.
 *
 * The verifier intentionally compares the full GGUF content identity: SHA-256, observed
 * length and structural header counters. When a native backend supplies a path inside an
 * already-held descriptor lease (for example /proc/self/fd/<n> from an Android content URI),
 * [verifyNativePath] inspects that exact descriptor-backed path before the native loader runs.
 * Phase 78 shares [ModelRuntimeIdentity] with warm-session reuse so locator/declared-length
 * checks cannot be skipped simply because a native model is already resident.
 */
class ModelArtifactIdentityVerifier(
    private val inspector: GgufInspector = GgufInspector()
) {
    fun verifySource(
        model: InstalledModel,
        source: ModelArtifactSource
    ): Result<GgufArtifactInspection> = runCatching {
        ModelRuntimeIdentity.bind(model, source)
        val inspection = inspector.inspect(source).getOrThrow()
        requireMatches(model, inspection)
        inspection
    }

    fun verifyNativePath(
        model: InstalledModel,
        source: ModelArtifactSource,
        nativePath: String
    ): Result<GgufArtifactInspection> = runCatching {
        require(nativePath.isNotBlank()) { "native model path is blank" }
        ModelRuntimeIdentity.bind(model, source)
        val inspection = inspector.inspect(FileModelArtifactSource(File(nativePath))).getOrThrow()
        requireMatches(model, inspection)
        inspection
    }

    private fun requireMatches(model: InstalledModel, inspection: GgufArtifactInspection) {
        require(model.descriptor.format.equals("gguf", ignoreCase = true)) {
            "installed model format is not GGUF"
        }
        require(inspection.sha256 == model.sha256) {
            "model artifact SHA-256 no longer matches installed identity"
        }
        model.lengthBytes?.let { installedLength ->
            require(inspection.lengthBytes == installedLength) {
                "model artifact length no longer matches installed identity"
            }
        }
        require(inspection.header.version == model.ggufVersion) {
            "model artifact GGUF version no longer matches installed identity"
        }
        require(inspection.header.tensorCount == model.tensorCount) {
            "model artifact tensor count no longer matches installed identity"
        }
        require(inspection.header.metadataKeyValueCount == model.metadataKeyValueCount) {
            "model artifact metadata count no longer matches installed identity"
        }
    }
}
