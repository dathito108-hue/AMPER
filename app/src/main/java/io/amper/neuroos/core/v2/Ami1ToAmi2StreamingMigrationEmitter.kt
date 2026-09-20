package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiBinaryReader
import io.amper.neuroos.core.AmiPreservedGgufMetadataReader
import io.amper.neuroos.core.AmiSectionDescriptor
import io.amper.neuroos.core.AmiSectionType
import io.amper.neuroos.core.AmiSourceFormat
import java.io.File

data class Ami2StreamingMigrationResult(
    val sourceAmi1Sha256: String,
    val output: Ami2WrittenBinaryArtifact
)

/**
 * Verified, bounded-memory migration from canonical AMI1 artifacts into AMI2.
 *
 * AMI1 is treated strictly as a migration source. The source is re-read with section digest
 * verification before planning. Large canonical sections are referenced by file range and copied
 * into AMI2 with the writer's bounded streaming buffer.
 */
class Ami1ToAmi2StreamingMigrationEmitter(
    private val legacyReader: AmiBinaryReader = AmiBinaryReader(),
    private val metadataReader: AmiPreservedGgufMetadataReader = AmiPreservedGgufMetadataReader(),
    private val writer: Ami2CanonicalBinaryWriter = Ami2CanonicalBinaryWriter()
) {
    fun migrate(
        sourceAmi1: File,
        destinationAmi2: File
    ): Result<Ami2StreamingMigrationResult> = runCatching {
        require(sourceAmi1.canonicalFile != destinationAmi2.canonicalFile) {
            "AMI1 source and AMI2 destination must be different files"
        }

        val legacy = legacyReader
            .read(sourceAmi1, verifySectionDigests = true)
            .getOrThrow()

        require(legacy.index.manifest.source.sourceFormat == AmiSourceFormat.GGUF) {
            "Phase627 streaming migration supports verified GGUF-origin AMI1 artifacts only"
        }

        val metadata = metadataReader.read(legacy).getOrThrow()
        val preservedChatTemplate = metadata.text("tokenizer.chat_template")

        val plan = Ami2CompilationPlanner.fromLegacyAmi1(
            index = legacy.index,
            legacyContainerSha256 = legacy.fileSha256,
            preservedChatTemplate = preservedChatTemplate
        )

        val sources = Ami2CanonicalPayloadSources(
            tokenizer = sectionSource(legacy.file, legacy.index.sections, AmiSectionType.TOKENIZER),
            chatProtocol = Ami2ByteArrayPayloadSource(
                Ami2CompilationPlanner.canonicalChatProtocolBytes(preservedChatTemplate)
            ),
            logicalGraph = sectionSource(
                legacy.file,
                legacy.index.sections,
                AmiSectionType.GRAPH_IR
            ),
            tensorIndex = sectionSource(
                legacy.file,
                legacy.index.sections,
                AmiSectionType.TENSOR_INDEX
            ),
            foundationWeights = sectionSource(
                legacy.file,
                legacy.index.sections,
                AmiSectionType.FOUNDATION_WEIGHTS
            )
        )

        val output = writer
            .writeFromSources(
                plan = plan,
                payloads = sources,
                destination = destinationAmi2
            )
            .getOrThrow()

        Ami2StreamingMigrationResult(
            sourceAmi1Sha256 = legacy.fileSha256,
            output = output
        )
    }

    private fun sectionSource(
        file: File,
        sections: List<AmiSectionDescriptor>,
        type: AmiSectionType
    ): Ami2FileRangePayloadSource {
        val section = sections.single {
            it.type == type && it.profileId == 0
        }
        return Ami2FileRangePayloadSource(
            file = file,
            sourceOffset = section.offset,
            length = section.length,
            expectedSha256 = section.sha256
        )
    }
}
