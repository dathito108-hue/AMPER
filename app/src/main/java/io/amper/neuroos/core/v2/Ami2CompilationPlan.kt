package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiContainerIndex
import io.amper.neuroos.core.AmiSectionType
import io.amper.neuroos.core.AmiSourceFormat
import java.security.MessageDigest

data class Ami2MigrationEvidence(
    val legacyAmi1Sha256: String
) {
    init {
        require(legacyAmi1Sha256.matches(Regex("[0-9a-f]{64}"))) {
            "legacy AMI1 digest must be lowercase SHA-256"
        }
    }
}

data class Ami2CompilationPlan(
    val foundation: Ami2FoundationIdentity,
    val artifacts: Set<Ami2ArtifactRole>,
    val migrationEvidence: Ami2MigrationEvidence
) {
    init {
        require(artifacts == Ami2FoundationContract.mandatoryArtifacts) {
            "initial AMI2 compilation plan must contain canonical foundation artifacts only"
        }
        require(Ami2ArtifactRole.DEVICE_PACK !in artifacts) {
            "device packs are generated after canonical AMI2 foundation semantics are fixed"
        }
    }

    fun asFoundationBundle(): Ami2FoundationBundle =
        Ami2FoundationBundle(
            foundation = foundation,
            artifacts = artifacts
        )
}

/**
 * First M2 compiler stage.
 *
 * The verified AMI1 container is used only as a migration source so Phase625 can reuse the already
 * qualified GGUF scanner and SOURCE_EXACT copier without creating a second parser. This planner
 * deterministically fixes AMI2 semantic identity before a binary AMI2 writer or device-pack
 * compiler is allowed to exist.
 */
object Ami2CompilationPlanner {
    const val FALLBACK_CHAT_PROTOCOL: String = "amper:plain-assistant-cue:v1"

    fun fromLegacyAmi1(
        index: AmiContainerIndex,
        legacyContainerSha256: String,
        preservedChatTemplate: String?
    ): Ami2CompilationPlan {
        require(legacyContainerSha256.matches(Regex("[0-9a-f]{64}"))) {
            "legacy AMI1 container digest must be lowercase SHA-256"
        }
        require(index.manifest.source.sourcePrecisionPreserved) {
            "AMI2 canonical foundation migration requires preserved source precision"
        }

        val importSource = when (index.manifest.source.sourceFormat) {
            AmiSourceFormat.GGUF -> Ami2ImportSource.GGUF_WEIGHTS
            AmiSourceFormat.AMI -> Ami2ImportSource.LEGACY_AMI1
            AmiSourceFormat.OTHER ->
                error("unsupported AMI1 source lineage for AMI2 canonical migration")
        }
        when (importSource) {
            Ami2ImportSource.GGUF_WEIGHTS ->
                require(importSource in Ami2MigrationContract.acceptedWeightImportSources)
            Ami2ImportSource.LEGACY_AMI1 ->
                require(importSource in Ami2MigrationContract.acceptedLegacyMigrationSources)
        }

        fun canonicalSectionDigest(type: AmiSectionType): String =
            index.sections.single { it.type == type && it.profileId == 0 }.sha256

        val tokenizerSha256 = canonicalSectionDigest(AmiSectionType.TOKENIZER)
        val logicalGraphSha256 = canonicalSectionDigest(AmiSectionType.GRAPH_IR)
        val tensorIndexSha256 = canonicalSectionDigest(AmiSectionType.TENSOR_INDEX)
        val canonicalWeightsSha256 = canonicalSectionDigest(AmiSectionType.FOUNDATION_WEIGHTS)
        val chatProtocolSha256 = sha256Bytes(
            canonicalChatProtocolBytes(preservedChatTemplate)
        )

        val lineage = Ami2SourceLineage(
            source = importSource,
            sourceSha256 = index.manifest.source.sourceSha256,
            sourceByteLength = index.manifest.source.sourceByteLength
        )

        val semanticSha256 = computeSemanticSha256(
            architectureId = index.manifest.architecture.value,
            lineage = lineage,
            tokenizerSha256 = tokenizerSha256,
            chatProtocolSha256 = chatProtocolSha256,
            logicalGraphSha256 = logicalGraphSha256,
            tensorIndexSha256 = tensorIndexSha256,
            canonicalWeightsSha256 = canonicalWeightsSha256,
            tensorCount = index.manifest.tensorCount,
            vocabularySize = index.manifest.vocabularySize
        )

        val foundation = Ami2FoundationIdentity(
            foundationId = "amper-" + semanticSha256.take(32),
            architectureId = index.manifest.architecture.value,
            lineage = lineage,
            tokenizerSha256 = tokenizerSha256,
            chatProtocolSha256 = chatProtocolSha256,
            logicalGraphSha256 = logicalGraphSha256,
            tensorIndexSha256 = tensorIndexSha256,
            canonicalWeightsSha256 = canonicalWeightsSha256,
            semanticSha256 = semanticSha256,
            tensorCount = index.manifest.tensorCount,
            vocabularySize = index.manifest.vocabularySize
        )

        return Ami2CompilationPlan(
            foundation = foundation,
            artifacts = Ami2FoundationContract.mandatoryArtifacts,
            migrationEvidence = Ami2MigrationEvidence(legacyContainerSha256)
        )
    }

    fun canonicalChatProtocolBytes(preservedChatTemplate: String?): ByteArray =
        (
            preservedChatTemplate
                ?.takeIf(String::isNotBlank)
                ?.let { "gguf-chat-template:utf8\n$it" }
                ?: FALLBACK_CHAT_PROTOCOL
            ).toByteArray(Charsets.UTF_8)

    fun computeSemanticSha256(
        architectureId: String,
        lineage: Ami2SourceLineage,
        tokenizerSha256: String,
        chatProtocolSha256: String,
        logicalGraphSha256: String,
        tensorIndexSha256: String,
        canonicalWeightsSha256: String,
        tensorCount: Int,
        vocabularySize: Int
    ): String = sha256Text(
        canonicalSemanticDescriptor(
            architectureId = architectureId,
            lineage = lineage,
            tokenizerSha256 = tokenizerSha256,
            chatProtocolSha256 = chatProtocolSha256,
            logicalGraphSha256 = logicalGraphSha256,
            tensorIndexSha256 = tensorIndexSha256,
            canonicalWeightsSha256 = canonicalWeightsSha256,
            tensorCount = tensorCount,
            vocabularySize = vocabularySize
        )
    )

    private fun canonicalSemanticDescriptor(
        architectureId: String,
        lineage: Ami2SourceLineage,
        tokenizerSha256: String,
        chatProtocolSha256: String,
        logicalGraphSha256: String,
        canonicalWeightsSha256: String,
        tensorCount: Int,
        vocabularySize: Int
    ): String = buildString {
        append("AMI2-FOUNDATION-SEMANTICS\n")
        append("version=2.0\n")
        append("architecture=").append(architectureId).append('\n')
        append("source=").append(lineage.source.name).append('\n')
        append("source_sha256=").append(lineage.sourceSha256).append('\n')
        append("source_bytes=").append(lineage.sourceByteLength).append('\n')
        append("tokenizer_sha256=").append(tokenizerSha256).append('\n')
        append("chat_protocol_sha256=").append(chatProtocolSha256).append('\n')
        append("logical_graph_sha256=").append(logicalGraphSha256).append('\n')
        append("tensor_index_sha256=").append(tensorIndexSha256).append('\n')
        append("foundation_weights_sha256=").append(canonicalWeightsSha256).append('\n')
        append("tensor_count=").append(tensorCount).append('\n')
        append("vocabulary_size=").append(vocabularySize).append('\n')
    }

    private fun sha256Text(value: String): String =
        sha256Bytes(value.toByteArray(Charsets.UTF_8))

    private fun sha256Bytes(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }
}
