package io.amper.neuroos.core.v2

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ami2BinaryCodecTest {
    @Test
    fun canonicalWriterReaderRoundTripPreservesFoundationIdentity() {
        withTempDir { dir ->
            val payloads = payloads()
            val plan = planFor(payloads)
            val destination = File(dir, "foundation.ami")

            val written = Ami2CanonicalBinaryWriter()
                .write(plan, payloads, destination)
                .getOrThrow()
            val loaded = Ami2CanonicalBinaryReader()
                .read(destination)
                .getOrThrow()

            assertEquals(plan.foundation, written.foundation)
            assertEquals(plan.foundation, loaded.bundle.foundation)
            assertEquals(plan.migrationEvidence, loaded.migrationEvidence)
            assertEquals(
                Ami2FoundationContract.mandatoryArtifacts,
                loaded.bundle.artifacts
            )
            assertEquals(
                Ami2FoundationContract.mandatoryArtifacts.size,
                loaded.sections.size
            )
            assertTrue(written.fileSha256.matches(Regex("[0-9a-f]{64}")))

            val weights = loaded.sections.single {
                it.role == Ami2ArtifactRole.FOUNDATION_WEIGHTS
            }
            assertEquals(
                0L,
                weights.offset % Ami2BinaryLayout.FOUNDATION_ALIGNMENT_BYTES.toLong()
            )

            RandomAccessFile(destination, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                assertEquals(
                    Ami2FoundationContract.magicAscii,
                    magic.toString(Charsets.US_ASCII)
                )
            }
        }
    }

    @Test
    fun sectionTamperFailsIntegrityVerification() {
        withTempDir { dir ->
            val payloads = payloads()
            val plan = planFor(payloads)
            val destination = File(dir, "foundation.ami")
            val written = Ami2CanonicalBinaryWriter()
                .write(plan, payloads, destination)
                .getOrThrow()
            val tokenizer = written.sections.single {
                it.role == Ami2ArtifactRole.TOKENIZER
            }

            RandomAccessFile(destination, "rw").use { raf ->
                raf.seek(tokenizer.offset)
                val original = raf.read()
                require(original >= 0)
                raf.seek(tokenizer.offset)
                raf.write(original xor 0x01)
            }

            val result = Ami2CanonicalBinaryReader().read(destination)
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun writerRejectsPayloadThatDoesNotMatchSemanticPlan() {
        withTempDir { dir ->
            val payloads = payloads()
            val plan = planFor(payloads)
            val changed = payloads.copy(
                tokenizer = "different-tokenizer".toByteArray()
            )

            val result = Ami2CanonicalBinaryWriter().write(
                plan = plan,
                payloads = changed,
                destination = File(dir, "foundation.ami")
            )

            assertTrue(result.isFailure)
        }
    }

    @Test
    fun headerSemanticTamperFailsEvenWhenSectionDigestChecksAreSkipped() {
        withTempDir { dir ->
            val payloads = payloads()
            val plan = planFor(payloads)
            val destination = File(dir, "foundation.ami")
            Ami2CanonicalBinaryWriter()
                .write(plan, payloads, destination)
                .getOrThrow()

            RandomAccessFile(destination, "rw").use { raf ->
                // magic(4) + version(4) + sectionCount(4) + headerBytes(4) + fileLength(8)
                raf.seek(24L)
                val original = raf.read()
                require(original >= 0)
                raf.seek(24L)
                raf.write(original xor 0x01)
            }

            val result = Ami2CanonicalBinaryReader().read(
                destination,
                verifySectionDigests = false
            )
            assertTrue(result.isFailure)
        }
    }

    private fun payloads(): Ami2CanonicalPayloads =
        Ami2CanonicalPayloads(
            tokenizer = "tokenizer-v2".toByteArray(),
            chatProtocol = Ami2CompilationPlanner.canonicalChatProtocolBytes(null),
            logicalGraph = "logical-graph-v2".toByteArray(),
            tensorIndex = "tensor-index-v2".toByteArray(),
            foundationWeights = ByteArray(257) { index -> (index and 0xff).toByte() }
        )

    private fun planFor(payloads: Ami2CanonicalPayloads): Ami2CompilationPlan {
        val lineage = Ami2SourceLineage(
            source = Ami2ImportSource.GGUF_WEIGHTS,
            sourceSha256 = "0".repeat(64),
            sourceByteLength = 1_048_576L
        )
        val tokenizerSha = sha256(payloads.tokenizer)
        val chatProtocolSha = sha256(payloads.chatProtocol)
        val graphSha = sha256(payloads.logicalGraph)
        val weightsSha = sha256(payloads.foundationWeights)
        val semanticSha = Ami2CompilationPlanner.computeSemanticSha256(
            architectureId = "llama",
            lineage = lineage,
            tokenizerSha256 = tokenizerSha,
            chatProtocolSha256 = chatProtocolSha,
            logicalGraphSha256 = graphSha,
            canonicalWeightsSha256 = weightsSha,
            tensorCount = 128,
            vocabularySize = 32_000
        )

        return Ami2CompilationPlan(
            foundation = Ami2FoundationIdentity(
                foundationId = "amper-" + semanticSha.take(32),
                architectureId = "llama",
                lineage = lineage,
                tokenizerSha256 = tokenizerSha,
                chatProtocolSha256 = chatProtocolSha,
                logicalGraphSha256 = graphSha,
                canonicalWeightsSha256 = weightsSha,
                semanticSha256 = semanticSha,
                tensorCount = 128,
                vocabularySize = 32_000
            ),
            artifacts = Ami2FoundationContract.mandatoryArtifacts,
            migrationEvidence = Ami2MigrationEvidence("f".repeat(64))
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private inline fun withTempDir(block: (File) -> Unit) {
        val dir = createTempDir(prefix = "ami2-binary-test-")
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
