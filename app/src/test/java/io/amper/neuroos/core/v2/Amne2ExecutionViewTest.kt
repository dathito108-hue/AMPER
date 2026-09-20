package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiHardwareFeature
import io.amper.neuroos.core.AmiHardwareSnapshot
import io.amper.neuroos.core.AmiTestFixtures
import io.amper.neuroos.core.ByteArrayModelArtifactSource
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Amne2ExecutionViewTest {
    @Test
    fun foundationWeightsAreReadThroughBoundedMmapWindow() {
        val tensorBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        withCanonicalAmi2(tensorBytes) { file ->
            val view = Amne2ExecutionViewFactory(
                maxWindowBytes = 1024
            ).open(file, arm64Hardware()).getOrThrow()

            val section = view.descriptor(Ami2ArtifactRole.FOUNDATION_WEIGHTS)
            assertTrue(section.length >= tensorBytes.size.toLong())

            val mapped = view.mapWindow(
                role = Ami2ArtifactRole.FOUNDATION_WEIGHTS,
                relativeOffset = 0L,
                length = tensorBytes.size
            )
            val observed = ByteArray(tensorBytes.size)
            mapped.get(observed)

            assertTrue(tensorBytes.contentEquals(observed))
            assertEquals(
                view.loaded.bundle.foundation.semanticSha256,
                view.identity.semanticSha256
            )
            assertEquals(
                view.loaded.fileSha256,
                view.identity.artifactSha256
            )
        }
    }

    @Test
    fun boundedControlReadUsesVerifiedSectionOffsets() {
        withCanonicalAmi2(byteArrayOf(1, 2, 3, 4)) { file ->
            val view = Amne2ExecutionViewFactory(
                maxWindowBytes = 1024
            ).open(file, arm64Hardware()).getOrThrow()
            val graph = view.descriptor(Ami2ArtifactRole.LOGICAL_GRAPH)
            val bytes = view.readBytes(
                role = Ami2ArtifactRole.LOGICAL_GRAPH,
                length = graph.length.toInt()
            )

            assertTrue(bytes.toString(Charsets.UTF_8).contains("graph_ir="))
        }
    }

    @Test
    fun mmapWindowCannotEscapeSectionOrConfiguredBound() {
        withCanonicalAmi2(byteArrayOf(5, 6, 7, 8)) { file ->
            val view = Amne2ExecutionViewFactory(
                maxWindowBytes = 2
            ).open(file, arm64Hardware()).getOrThrow()
            val weights = view.descriptor(Ami2ArtifactRole.FOUNDATION_WEIGHTS)

            val tooLarge = runCatching {
                view.mapWindow(
                    role = Ami2ArtifactRole.FOUNDATION_WEIGHTS,
                    relativeOffset = 0L,
                    length = 3
                )
            }
            val escapesSection = runCatching {
                view.mapWindow(
                    role = Ami2ArtifactRole.FOUNDATION_WEIGHTS,
                    relativeOffset = weights.length,
                    length = 1
                )
            }

            assertTrue(tooLarge.isFailure)
            assertTrue(escapesSection.isFailure)
        }
    }

    @Test
    fun tamperedArtifactCannotOpenExecutionView() {
        withCanonicalAmi2(byteArrayOf(9, 8, 7, 6)) { file ->
            val loaded = Ami2CanonicalBinaryReader().read(file).getOrThrow()
            val weights = loaded.sections.single {
                it.role == Ami2ArtifactRole.FOUNDATION_WEIGHTS
            }
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(weights.offset)
                val original = raf.read()
                require(original >= 0)
                raf.seek(weights.offset)
                raf.write(original xor 0x01)
            }

            val result = Amne2ExecutionViewFactory()
                .open(file, arm64Hardware())

            assertTrue(result.isFailure)
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

    private inline fun withCanonicalAmi2(
        tensorBytes: ByteArray,
        block: (File) -> Unit
    ) {
        val file = File.createTempFile("amper-phase631-", ".ami")
        try {
            file.delete()
            GgufToAmi2StreamingCompiler().compile(
                source = ByteArrayModelArtifactSource(
                    AmiTestFixtures.compilerReadyGguf(tensorBytes = tensorBytes)
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
