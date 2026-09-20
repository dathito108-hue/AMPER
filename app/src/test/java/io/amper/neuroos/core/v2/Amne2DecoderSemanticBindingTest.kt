package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiHardwareFeature
import io.amper.neuroos.core.AmiHardwareSnapshot
import io.amper.neuroos.core.AmiSectionType
import io.amper.neuroos.core.AmiTestFixtures
import io.amper.neuroos.core.ByteArrayModelArtifactSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Amne2DecoderSemanticBindingTest {
    @Test
    fun verifiedAmi2ProjectsIntoExistingDecoderSemanticReaders() {
        withCanonicalAmi2 { file ->
            val view = Amne2ExecutionViewFactory(
                maxWindowBytes = 1024 * 1024
            ).open(file, arm64Hardware()).getOrThrow()

            val binding = Amne2DecoderSemanticBindingFactory()
                .bind(view)
                .getOrThrow()

            assertEquals(view.identity.foundationId, binding.identity.foundationId)
            assertEquals(view.identity.semanticSha256, binding.identity.semanticSha256)
            assertEquals(view.identity.artifactSha256, binding.identity.artifactSha256)
            assertEquals(
                view.loaded.bundle.foundation.tensorIndexSha256,
                binding.identity.tensorIndexSha256
            )
            assertEquals(
                view.loaded.bundle.foundation.canonicalWeightsSha256,
                binding.identity.foundationWeightsSha256
            )
            assertEquals(
                view.loaded.bundle.foundation.architectureId,
                binding.tensorGraph.architecture.value
            )
            assertEquals(
                view.loaded.bundle.foundation.tensorCount,
                binding.tensorGraph.tensors.size
            )
            assertNotNull(binding.tensorGraph.tensor("token_embd.weight"))
            assertEquals(
                view.loaded.bundle.foundation.lineage.sourceSha256,
                binding.decoderArtifactView.index.manifest.source.sourceSha256
            )
            assertEquals(
                view.loaded.bundle.foundation.vocabularySize,
                binding.decoderArtifactView.index.manifest.vocabularySize
            )
            assertTrue(
                binding.decoderArtifactView.index.sections.any {
                    it.type == AmiSectionType.FOUNDATION_WEIGHTS
                }
            )
        }
    }

    @Test
    fun decoderProjectionUsesAmi2FoundationOffsetsNotASecondModelCopy() {
        withCanonicalAmi2 { file ->
            val view = Amne2ExecutionViewFactory().open(
                file,
                arm64Hardware()
            ).getOrThrow()
            val binding = Amne2DecoderSemanticBindingFactory()
                .bind(view)
                .getOrThrow()

            val projectedFoundation = binding.decoderArtifactView.index.sections.single {
                it.type == AmiSectionType.FOUNDATION_WEIGHTS
            }
            val ami2Foundation = view.descriptor(Ami2ArtifactRole.FOUNDATION_WEIGHTS)

            assertEquals(file.canonicalFile, binding.decoderArtifactView.file.canonicalFile)
            assertEquals(ami2Foundation.offset, projectedFoundation.offset)
            assertEquals(ami2Foundation.length, projectedFoundation.length)
            assertEquals(ami2Foundation.sha256, projectedFoundation.sha256)
        }
    }

    private fun arm64Hardware() = AmiHardwareSnapshot(
        features = setOf(
            AmiHardwareFeature.ARM64,
            AmiHardwareFeature.NEON
        ),
        logicalProcessors = 8,
        memoryClassMb = 512,
        lowRamDevice = false
    )

    private inline fun withCanonicalAmi2(block: (File) -> Unit) {
        val file = File.createTempFile("amper-phase632-", ".ami")
        try {
            file.delete()
            GgufToAmi2StreamingCompiler().compile(
                source = ByteArrayModelArtifactSource(
                    AmiTestFixtures.compilerReadyGguf()
                ),
                destination = file
            ).getOrThrow()
            block(file)
        } finally {
            file.setWritable(true)
            file.delete()
            File(file.parentFile, file.name + ".partial").delete()
        }
    }
}
