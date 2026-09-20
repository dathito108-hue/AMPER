package io.amper.neuroos.core.v2

/**
 * Semantic contract for the canonical AMI2 foundation container.
 *
 * This is intentionally not a second runtime or compiler. It defines the identity that M2 must
 * materialize and M3 must execute: one AMPER foundation, with imported sources converted into AMI2
 * and hardware-specific device packs treated only as disposable acceleration artifacts.
 */
object Ami2FoundationContract {
    const val fileExtension: String = ".ami"
    const val magicAscii: String = "AMI2"
    const val majorVersion: Int = 2
    const val minorVersion: Int = 0
    const val foundationSlots: Int = 1

    val mandatoryArtifacts: Set<Ami2ArtifactRole> = linkedSetOf(
        Ami2ArtifactRole.MANIFEST,
        Ami2ArtifactRole.SOURCE_LINEAGE,
        Ami2ArtifactRole.TOKENIZER,
        Ami2ArtifactRole.CHAT_PROTOCOL,
        Ami2ArtifactRole.LOGICAL_GRAPH,
        Ami2ArtifactRole.TENSOR_INDEX,
        Ami2ArtifactRole.FOUNDATION_WEIGHTS,
        Ami2ArtifactRole.INTEGRITY
    )
}

enum class Ami2ImportSource {
    GGUF_WEIGHTS,
    LEGACY_AMI1
}

enum class Ami2ArtifactRole {
    MANIFEST,
    SOURCE_LINEAGE,
    TOKENIZER,
    CHAT_PROTOCOL,
    LOGICAL_GRAPH,
    TENSOR_INDEX,
    FOUNDATION_WEIGHTS,
    DEVICE_PACK,
    ADAPTATION_DELTA,
    INTEGRITY
}

enum class Ami2HardwareFeature {
    ARM64,
    NEON,
    DOTPROD,
    I8MM,
    FP16,
    VULKAN,
    NPU
}

data class Ami2SourceLineage(
    val source: Ami2ImportSource,
    val sourceSha256: String,
    val sourceByteLength: Long
) {
    init {
        requireSha256(sourceSha256, "AMI2 source")
        require(sourceByteLength > 0L) { "AMI2 source must be non-empty" }
    }
}

/**
 * Stable semantic identity for exactly one AMPER foundation.
 *
 * The semantic digest is compiler-defined in M2 and binds graph/protocol/weights semantics. Device
 * packs may change physical layout or quantization representation, but must still bind to this same
 * semantic identity and therefore can never become an independent model/backend.
 */
data class Ami2FoundationIdentity(
    val foundationId: String,
    val architectureId: String,
    val lineage: Ami2SourceLineage,
    val tokenizerSha256: String,
    val chatProtocolSha256: String,
    val logicalGraphSha256: String,
    val canonicalWeightsSha256: String,
    val semanticSha256: String,
    val tensorCount: Int,
    val vocabularySize: Int
) {
    init {
        requireStableId(foundationId, "AMI2 foundation id")
        requireStableId(architectureId, "AMI2 architecture id")
        requireSha256(tokenizerSha256, "AMI2 tokenizer")
        requireSha256(chatProtocolSha256, "AMI2 chat protocol")
        requireSha256(logicalGraphSha256, "AMI2 logical graph")
        requireSha256(canonicalWeightsSha256, "AMI2 canonical weights")
        requireSha256(semanticSha256, "AMI2 foundation semantics")
        require(tensorCount > 0) { "AMI2 tensor count must be positive" }
        require(vocabularySize > 0) { "AMI2 vocabulary size must be positive" }
    }
}

data class Ami2DevicePack(
    val packId: String,
    val foundationId: String,
    val foundationSemanticSha256: String,
    val packSha256: String,
    val requiredFeatures: Set<Ami2HardwareFeature>
) {
    init {
        requireStableId(packId, "AMI2 device-pack id")
        requireStableId(foundationId, "AMI2 device-pack foundation id")
        requireSha256(foundationSemanticSha256, "AMI2 device-pack foundation semantics")
        requireSha256(packSha256, "AMI2 device pack")
        require(Ami2HardwareFeature.ARM64 in requiredFeatures) {
            "AMI2 mobile device packs require ARM64"
        }
    }
}

data class Ami2FoundationBundle(
    val foundation: Ami2FoundationIdentity,
    val artifacts: Set<Ami2ArtifactRole>,
    val devicePacks: List<Ami2DevicePack> = emptyList()
) {
    init {
        require(artifacts.containsAll(Ami2FoundationContract.mandatoryArtifacts)) {
            "AMI2 bundle is missing mandatory foundation artifacts"
        }
        require(devicePacks.distinctBy(Ami2DevicePack::packId).size == devicePacks.size) {
            "AMI2 device-pack ids must be unique"
        }
        require((Ami2ArtifactRole.DEVICE_PACK in artifacts) == devicePacks.isNotEmpty()) {
            "AMI2 DEVICE_PACK artifact declaration must match concrete device packs"
        }
        devicePacks.forEach { pack ->
            require(pack.foundationId == foundation.foundationId) {
                "AMI2 device pack belongs to a different foundation"
            }
            require(pack.foundationSemanticSha256 == foundation.semanticSha256) {
                "AMI2 device pack cannot alter foundation semantics"
            }
        }
    }
}

/**
 * M1 migration boundary. Legacy containers and GGUF are inputs to migration/import only; neither is
 * a selectable production backend. M2 emits AMI2 and M3 executes it through AMNE2.
 */
object Ami2MigrationContract {
    const val productionFormat: String = "AMI2"
    const val productionExecutionEngine: String = "AMNE2"
    const val legacyAmi1DirectProductionRuntimeAllowed: Boolean = false
    const val ggufDirectProductionRuntimeAllowed: Boolean = false
    const val multipleFoundationRoutingAllowed: Boolean = false

    val acceptedWeightImportSources: Set<Ami2ImportSource> = setOf(
        Ami2ImportSource.GGUF_WEIGHTS
    )
    val acceptedLegacyMigrationSources: Set<Ami2ImportSource> = setOf(
        Ami2ImportSource.LEGACY_AMI1
    )
}

private val AMI2_STABLE_ID = Regex("[a-z0-9][a-z0-9._-]{0,95}")
private val AMI2_SHA256 = Regex("[0-9a-f]{64}")

private fun requireStableId(value: String, label: String) {
    require(value.matches(AMI2_STABLE_ID)) { "$label must be a stable lowercase identifier" }
}

private fun requireSha256(value: String, label: String) {
    require(value.matches(AMI2_SHA256)) { "$label digest must be lowercase SHA-256" }
}
