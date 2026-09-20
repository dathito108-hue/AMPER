package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiBinaryReader
import io.amper.neuroos.core.AmiSectionType
import io.amper.neuroos.core.AmiTestFixtures
import io.amper.neuroos.core.ByteArrayModelArtifactSource
import io.amper.neuroos.core.GgufToAmiCompiler
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ami1ToAmi2StreamingMigrationEmitterTest {
    @Test
    fun verifiedAmi1MigratesToCanonicalAmi2WithSectionIdentityPreserved() {
        val source = File.createTempFile("amper-phase627-source-", ".ami")
        val destination = File.createTempFile("amper-phase627-destination-", ".ami")
        try {
            destination.delete()
            val tensorBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
            GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(
                    AmiTestFixtures.compilerReadyGguf(tensorBytes = tensorBytes)
                ),
                source
            ).getOrThrow()

            val legacy = AmiBinaryReader().read(source).getOrThrow()
            val migration = Ami1ToAmi2StreamingMigrationEmitter()
                .migrate(source, destination)
                .getOrThrow()
            val loaded = Ami2CanonicalBinaryReader()
                .read(destination)
                .getOrThrow()

            assertEquals(legacy.fileSha256, migration.sourceAmi1Sha256)
            assertEquals(
                legacy.fileSha256,
                requireNotNull(loaded.migrationEvidence).legacyAmi1Sha256
            )
            assertEquals(planSectionSha(legacy, AmiSectionType.TOKENIZER),
                loaded.bundle.foundation.tokenizerSha256)
            assertEquals(planSectionSha(legacy, AmiSectionType.GRAPH_IR),
                loaded.bundle.foundation.logicalGraphSha256)
            assertEquals(planSectionSha(legacy, AmiSectionType.TENSOR_INDEX),
                loaded.bundle.foundation.tensorIndexSha256)
            assertEquals(planSectionSha(legacy, AmiSectionType.FOUNDATION_WEIGHTS),
                loaded.bundle.foundation.canonicalWeightsSha256)
            assertEquals(
                64 * 1024,
                Ami2BinaryLayout.STREAM_COPY_BUFFER_BYTES
            )

            val weights = loaded.sections.single {
                it.role == Ami2ArtifactRole.FOUNDATION_WEIGHTS
            }
            val copiedWeights = ByteArray(tensorBytes.size)
            RandomAccessFile(destination, "r").use { raf ->
                raf.seek(weights.offset)
                raf.readFully(copiedWeights)
            }
            assertTrue(tensorBytes.contentEquals(copiedWeights))
        } finally {
            source.setWritable(true)
            source.delete()
            destination.setWritable(true)
            destination.delete()
            File(destination.parentFile, destination.name + ".partial").delete()
        }
    }

    @Test
    fun corruptAmi1IsRejectedBeforeStreamingEmission() {
        val source = File.createTempFile("amper-phase627-corrupt-", ".ami")
        val destination = File.createTempFile("amper-phase627-reject-", ".ami")
        try {
            destination.delete()
            val compiled = GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(AmiTestFixtures.compilerReadyGguf()),
                source
            ).getOrThrow()
            val foundation = compiled.index.sections.single {
                it.type == AmiSectionType.FOUNDATION_WEIGHTS
            }

            RandomAccessFile(source, "rw").use { raf ->
                raf.seek(foundation.offset)
                val original = raf.read()
                require(original >= 0)
                raf.seek(foundation.offset)
                raf.write(original xor 0x01)
            }

            val result = Ami1ToAmi2StreamingMigrationEmitter()
                .migrate(source, destination)

            assertTrue(result.isFailure)
            assertTrue(!destination.exists())
        } finally {
            source.setWritable(true)
            source.delete()
            destination.delete()
            File(destination.parentFile, destination.name + ".partial").delete()
        }
    }

    @Test
    fun streamingWriterRejectsFileRangeChangedAfterPlanning() {
        val tokenizerFile = File.createTempFile("amper-phase627-range-", ".bin")
        val destination = File.createTempFile("amper-phase627-range-out-", ".ami")
        try {
            destination.delete()
            val tokenizer = "tokenizer-v2".toByteArray()
            tokenizerFile.writeBytes(tokenizer)
            val chatProtocol = Ami2CompilationPlanner.canonicalChatProtocolBytes(null)
            val graph = "logical-graph-v2".toByteArray()
            val tensorIndex = "tensor-index-v2".toByteArray()
            val weights = ByteArray(257) { index -> (index and 0xff).toByte() }

            val lineage = Ami2SourceLineage(
                source = Ami2ImportSource.GGUF_WEIGHTS,
                sourceSha256 = "0".repeat(64),
                sourceByteLength = 1_048_576L
            )
            val tokenizerSha = sha256(tokenizer)
            val chatSha = sha256(chatProtocol)
            val graphSha = sha256(graph)
            val tensorIndexSha = sha256(tensorIndex)
            val weightsSha = sha256(weights)
            val semanticSha = Ami2CompilationPlanner.computeSemanticSha256(
                architectureId = "llama",
                lineage = lineage,
                tokenizerSha256 = tokenizerSha,
                chatProtocolSha256 = chatSha,
                logicalGraphSha256 = graphSha,
                tensorIndexSha256 = tensorIndexSha,
                canonicalWeightsSha256 = weightsSha,
                tensorCount = 128,
                vocabularySize = 32_000
            )
            val plan = Ami2CompilationPlan(
                foundation = Ami2FoundationIdentity(
                    foundationId = Ami2CompilationPlanner.foundationIdForSemantic(semanticSha),
                    architectureId = "llama",
                    lineage = lineage,
                    tokenizerSha256 = tokenizerSha,
                    chatProtocolSha256 = chatSha,
                    logicalGraphSha256 = graphSha,
                    tensorIndexSha256 = tensorIndexSha,
                    canonicalWeightsSha256 = weightsSha,
                    semanticSha256 = semanticSha,
                    tensorCount = 128,
                    vocabularySize = 32_000
                ),
                artifacts = Ami2FoundationContract.mandatoryArtifacts,
                migrationEvidence = Ami2MigrationEvidence("f".repeat(64))
            )
            val tokenizerSource = Ami2FileRangePayloadSource(
                file = tokenizerFile,
                sourceOffset = 0L,
                length = tokenizer.size.toLong(),
                expectedSha256 = tokenizerSha
            )

            RandomAccessFile(tokenizerFile, "rw").use { raf ->
                raf.seek(0L)
                raf.write('X'.code)
            }

            val result = Ami2CanonicalBinaryWriter().writeFromSources(
                plan = plan,
                payloads = Ami2CanonicalPayloadSources(
                    tokenizer = tokenizerSource,
                    chatProtocol = Ami2ByteArrayPayloadSource(chatProtocol),
                    logicalGraph = Ami2ByteArrayPayloadSource(graph),
                    tensorIndex = Ami2ByteArrayPayloadSource(tensorIndex),
                    foundationWeights = Ami2ByteArrayPayloadSource(weights)
                ),
                destination = destination
            )

            assertTrue(result.isFailure)
            assertTrue(!destination.exists())
        } finally {
            tokenizerFile.delete()
            destination.delete()
            File(destination.parentFile, destination.name + ".partial").delete()
        }
    }

    private fun planSectionSha(
        loaded: io.amper.neuroos.core.AmiLoadedArtifact,
        type: AmiSectionType
    ): String = loaded.index.sections.single {
        it.type == type && it.profileId == 0
    }.sha256

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
