package io.amper.neuroos.core.v2

import io.amper.neuroos.core.GgufCompileSemantics
import io.amper.neuroos.core.GgufInspector
import io.amper.neuroos.core.ModelArtifactSource
import java.io.EOFException
import java.io.File
import java.security.MessageDigest

data class Ami2DirectGgufCompileResult(
    val sourceSha256: String,
    val output: Ami2WrittenBinaryArtifact
)

class GgufToAmi2StreamingCompiler(
    private val inspector: GgufInspector = GgufInspector(),
    private val writer: Ami2CanonicalBinaryWriter = Ami2CanonicalBinaryWriter()
) {
    fun compile(
        source: ModelArtifactSource,
        destination: File
    ): Result<Ami2DirectGgufCompileResult> = runCatching {
        require(destination.extension.equals("ami", ignoreCase = true)) {
            "AMI2 destination must use the .ami extension"
        }
        val destinationCanonical = destination.canonicalFile
        val stagingCanonical = File(
            destination.parentFile ?: File("."),
            destination.name + ".partial"
        ).canonicalFile
        val sourceLocatorFile = source.locator
            .takeIf { it.startsWith("/") }
            ?.let(::File)
            ?.canonicalFile
        require(sourceLocatorFile == null || sourceLocatorFile != destinationCanonical) {
            "GGUF source and AMI2 destination must be different artifacts"
        }
        require(sourceLocatorFile == null || sourceLocatorFile != stagingCanonical) {
            "GGUF source cannot alias the AMI2 staging artifact"
        }

        val firstInspection = inspector.inspect(source).getOrThrow()
        val sourceLength = requireNotNull(firstInspection.lengthBytes) {
            "direct AMI2 compiler requires stable GGUF length"
        }
        val scan = source.openStream().use(GgufCompileSemantics::scan)
        require(scan.tensorCount == firstInspection.header.tensorCount.toLong().toInt()) {
            "GGUF tensor count changed during direct AMI2 compilation"
        }

        val metadataLength = Math.subtractExact(scan.metadataEnd, scan.metadataStart)
        val foundationLength = Math.subtractExact(sourceLength, scan.tensorDataOffset)
        require(metadataLength > 0L) { "GGUF metadata table is empty" }
        require(foundationLength > 0L) { "GGUF tensor-data region is empty" }

        val graphBytes = GgufCompileSemantics.encodeGraphIr(scan)
        val tensorIndexBytes = GgufCompileSemantics.encodeTensorIndex(scan.tensors)
        val chatProtocolBytes =
            Ami2CompilationPlanner.canonicalChatProtocolBytes(scan.chatTemplate)

        val tokenizerSha256 = sha256Range(source, scan.metadataStart, metadataLength)
        val foundationSha256 = sha256Range(source, scan.tensorDataOffset, foundationLength)

        val secondInspection = inspector.inspect(source).getOrThrow()
        require(secondInspection.sha256 == firstInspection.sha256) {
            "GGUF source changed while direct AMI2 semantics were being compiled"
        }
        require(secondInspection.lengthBytes == sourceLength) {
            "GGUF source length changed while direct AMI2 semantics were being compiled"
        }
        require(secondInspection.header == firstInspection.header) {
            "GGUF header changed while direct AMI2 semantics were being compiled"
        }

        val plan = Ami2CompilationPlanner.fromDirectGguf(
            architectureId = scan.architecture.value,
            sourceSha256 = firstInspection.sha256,
            sourceByteLength = sourceLength,
            tokenizerSha256 = tokenizerSha256,
            preservedChatTemplate = scan.chatTemplate,
            logicalGraphSha256 = sha256(graphBytes),
            tensorIndexSha256 = sha256(tensorIndexBytes),
            canonicalWeightsSha256 = foundationSha256,
            tensorCount = scan.tensorCount,
            vocabularySize = scan.vocabularySize
        )

        val output = writer.writeFromSources(
            plan = plan,
            payloads = Ami2CanonicalPayloadSources(
                tokenizer = Ami2ModelArtifactRangePayloadSource(
                    source = source,
                    sourceOffset = scan.metadataStart,
                    length = metadataLength,
                    expectedSha256 = tokenizerSha256
                ),
                chatProtocol = Ami2ByteArrayPayloadSource(chatProtocolBytes),
                logicalGraph = Ami2ByteArrayPayloadSource(graphBytes),
                tensorIndex = Ami2ByteArrayPayloadSource(tensorIndexBytes),
                foundationWeights = Ami2ModelArtifactRangePayloadSource(
                    source = source,
                    sourceOffset = scan.tensorDataOffset,
                    length = foundationLength,
                    expectedSha256 = foundationSha256
                )
            ),
            destination = destination
        ).getOrThrow()

        val finalInspection = inspector.inspect(source).getOrThrow()
        val sourceStable =
            finalInspection.sha256 == firstInspection.sha256 &&
                finalInspection.lengthBytes == sourceLength &&
                finalInspection.header == firstInspection.header
        if (!sourceStable) {
            output.file.delete()
            error("GGUF source changed during direct AMI2 streaming emission")
        }

        Ami2DirectGgufCompileResult(
            sourceSha256 = firstInspection.sha256,
            output = output
        )
    }

    private fun sha256Range(
        source: ModelArtifactSource,
        offset: Long,
        length: Long
    ): String {
        require(offset >= 0L)
        require(length > 0L)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(Ami2BinaryLayout.STREAM_COPY_BUFFER_BYTES)
        source.openStream().use { input ->
            var skipRemaining = offset
            while (skipRemaining > 0L) {
                val wanted = minOf(skipRemaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, wanted)
                if (read < 0) throw EOFException("GGUF source truncated while seeking")
                if (read == 0) continue
                skipRemaining -= read.toLong()
            }

            var remaining = length
            while (remaining > 0L) {
                val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, wanted)
                if (read < 0) throw EOFException("GGUF source range truncated while hashing")
                if (read == 0) continue
                digest.update(buffer, 0, read)
                remaining -= read.toLong()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
