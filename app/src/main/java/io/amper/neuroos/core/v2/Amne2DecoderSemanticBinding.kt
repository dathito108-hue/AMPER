package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiArchitectureId
import io.amper.neuroos.core.AmiContainerIndex
import io.amper.neuroos.core.AmiGgufMetadataSnapshot
import io.amper.neuroos.core.AmiLoadedArtifact
import io.amper.neuroos.core.AmiManifest
import io.amper.neuroos.core.AmiPrecisionPolicy
import io.amper.neuroos.core.AmiPreservedGgufMetadataReader
import io.amper.neuroos.core.AmiSectionDescriptor
import io.amper.neuroos.core.AmiSectionType
import io.amper.neuroos.core.AmiSourceFormat
import io.amper.neuroos.core.AmiSourceLineage
import io.amper.neuroos.core.AmiTensorGraph
import io.amper.neuroos.core.AmiTensorGraphReader

data class Amne2DecoderSemanticIdentity(
    val foundationId: String,
    val semanticSha256: String,
    val artifactSha256: String,
    val tensorIndexSha256: String,
    val foundationWeightsSha256: String
)

data class Amne2DecoderSemanticBinding(
    val identity: Amne2DecoderSemanticIdentity,
    val decoderArtifactView: AmiLoadedArtifact,
    val tensorGraph: AmiTensorGraph,
    val preservedMetadata: AmiGgufMetadataSnapshot
) {
    init {
        require(identity.artifactSha256 == decoderArtifactView.fileSha256)
        require(
            tensorGraph.architecture ==
                decoderArtifactView.index.manifest.architecture
        )
    }
}

/**
 * Projects one already-verified AMI2 execution view onto the semantic contracts consumed by the
 * existing qualified decoder planners/executors.
 *
 * This is not an AMI1 runtime and it never reparses AMI2 as AMI1. The projection only builds an
 * in-memory compatibility index over the verified AMI2 section offsets because the decoder semantic
 * readers already understand the preserved TOKENIZER/GRAPH_IR/TENSOR_INDEX/FOUNDATION_WEIGHTS
 * payload encodings. The underlying file remains canonical AMI2 and execution identity remains
 * bound to the Phase631 verified view.
 */
class Amne2DecoderSemanticBindingFactory(
    private val graphReader: AmiTensorGraphReader = AmiTensorGraphReader(),
    private val metadataReader: AmiPreservedGgufMetadataReader =
        AmiPreservedGgufMetadataReader()
) {
    fun bind(view: Amne2ExecutionView): Result<Amne2DecoderSemanticBinding> = runCatching {
        val foundation = view.loaded.bundle.foundation

        require(view.identity.foundationId == foundation.foundationId) {
            "AMNE2 decoder binding foundation id differs from verified AMI2"
        }
        require(view.identity.semanticSha256 == foundation.semanticSha256) {
            "AMNE2 decoder binding semantic digest differs from verified AMI2"
        }
        require(view.identity.artifactSha256 == view.loaded.fileSha256) {
            "AMNE2 decoder binding artifact digest differs from verified AMI2"
        }

        val projected = AmiLoadedArtifact(
            file = view.file,
            index = AmiContainerIndex(
                manifest = AmiManifest(
                    architecture = AmiArchitectureId(foundation.architectureId),
                    tensorCount = foundation.tensorCount,
                    vocabularySize = foundation.vocabularySize,
                    source = AmiSourceLineage(
                        sourceFormat = when (foundation.lineage.source) {
                            Ami2ImportSource.GGUF_WEIGHTS -> AmiSourceFormat.GGUF
                            Ami2ImportSource.LEGACY_AMI1 -> AmiSourceFormat.AMI
                        },
                        sourceSha256 = foundation.lineage.sourceSha256,
                        sourceByteLength = foundation.lineage.sourceByteLength,
                        sourcePrecisionPreserved = true
                    ),
                    canonicalPrecisionPolicy = AmiPrecisionPolicy.SOURCE_EXACT
                ),
                sections = projectedSections(view)
            ),
            fileSha256 = view.loaded.fileSha256
        )

        val graph = graphReader.read(projected).getOrThrow()
        val metadata = metadataReader.read(projected).getOrThrow()

        require(graph.tensors.size == foundation.tensorCount) {
            "AMNE2 decoder graph tensor count differs from AMI2 foundation identity"
        }
        require(graph.architecture.value == foundation.architectureId) {
            "AMNE2 decoder graph architecture differs from AMI2 foundation identity"
        }

        Amne2DecoderSemanticBinding(
            identity = Amne2DecoderSemanticIdentity(
                foundationId = foundation.foundationId,
                semanticSha256 = foundation.semanticSha256,
                artifactSha256 = view.loaded.fileSha256,
                tensorIndexSha256 = foundation.tensorIndexSha256,
                foundationWeightsSha256 = foundation.canonicalWeightsSha256
            ),
            decoderArtifactView = projected,
            tensorGraph = graph,
            preservedMetadata = metadata
        )
    }

    private fun projectedSections(
        view: Amne2ExecutionView
    ): List<AmiSectionDescriptor> = listOf(
        project(view, Ami2ArtifactRole.MANIFEST, AmiSectionType.MANIFEST),
        project(view, Ami2ArtifactRole.TOKENIZER, AmiSectionType.TOKENIZER),
        project(view, Ami2ArtifactRole.LOGICAL_GRAPH, AmiSectionType.GRAPH_IR),
        project(view, Ami2ArtifactRole.TENSOR_INDEX, AmiSectionType.TENSOR_INDEX),
        project(
            view,
            Ami2ArtifactRole.FOUNDATION_WEIGHTS,
            AmiSectionType.FOUNDATION_WEIGHTS
        ),
        project(view, Ami2ArtifactRole.INTEGRITY, AmiSectionType.INTEGRITY)
    )

    private fun project(
        view: Amne2ExecutionView,
        role: Ami2ArtifactRole,
        type: AmiSectionType
    ): AmiSectionDescriptor {
        val descriptor = view.descriptor(role)
        return AmiSectionDescriptor(
            type = type,
            offset = descriptor.offset,
            length = descriptor.length,
            alignmentBytes = descriptor.alignmentBytes,
            sha256 = descriptor.sha256
        )
    }
}
