package io.amper.neuroos.core.v2

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

object Ami2BinaryLayout {
    const val HEADER_REGION_BYTES: Int = 8_192
    const val FIXED_HEADER_BYTES: Int = 64
    const val DESCRIPTOR_BYTES: Int = 64
    const val MAX_BINARY_SECTIONS: Int = 64
    const val DEFAULT_ALIGNMENT_BYTES: Int = 64
    const val FOUNDATION_ALIGNMENT_BYTES: Int = 4_096

    val canonicalRoleOrder: List<Ami2ArtifactRole> =
        Ami2FoundationContract.mandatoryArtifacts.toList()

    init {
        require(
            FIXED_HEADER_BYTES + MAX_BINARY_SECTIONS * DESCRIPTOR_BYTES <= HEADER_REGION_BYTES
        )
    }
}

data class Ami2BinarySectionDescriptor(
    val role: Ami2ArtifactRole,
    val offset: Long,
    val length: Long,
    val alignmentBytes: Int,
    val sha256: String
) {
    init {
        require(offset >= Ami2BinaryLayout.HEADER_REGION_BYTES.toLong()) {
            "AMI2 section overlaps reserved header region: $role"
        }
        require(length > 0L) { "AMI2 section must be non-empty: $role" }
        require(alignmentBytes > 0 && alignmentBytes.countOneBits() == 1) {
            "AMI2 section alignment must be a positive power of two"
        }
        require(offset % alignmentBytes == 0L) {
            "AMI2 section offset does not satisfy alignment: $role"
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "AMI2 section digest must be lowercase SHA-256"
        }
        if (role == Ami2ArtifactRole.FOUNDATION_WEIGHTS) {
            require(alignmentBytes >= Ami2BinaryLayout.FOUNDATION_ALIGNMENT_BYTES) {
                "AMI2 foundation weights must be page aligned"
            }
        }
    }

    val endExclusive: Long
        get() = Math.addExact(offset, length)
}

data class Ami2CanonicalPayloads(
    val tokenizer: ByteArray,
    val chatProtocol: ByteArray,
    val logicalGraph: ByteArray,
    val tensorIndex: ByteArray,
    val foundationWeights: ByteArray
) {
    init {
        require(tokenizer.isNotEmpty()) { "AMI2 tokenizer payload is empty" }
        require(chatProtocol.isNotEmpty()) { "AMI2 chat-protocol payload is empty" }
        require(logicalGraph.isNotEmpty()) { "AMI2 logical-graph payload is empty" }
        require(tensorIndex.isNotEmpty()) { "AMI2 tensor-index payload is empty" }
        require(foundationWeights.isNotEmpty()) { "AMI2 foundation-weight payload is empty" }
    }
}

data class Ami2WrittenBinaryArtifact(
    val file: File,
    val foundation: Ami2FoundationIdentity,
    val sections: List<Ami2BinarySectionDescriptor>,
    val fileSha256: String
)

data class Ami2LoadedBinaryArtifact(
    val file: File,
    val bundle: Ami2FoundationBundle,
    val migrationEvidence: Ami2MigrationEvidence,
    val sections: List<Ami2BinarySectionDescriptor>,
    val fileSha256: String
)

/**
 * Materializes the canonical AMI2 foundation container fixed by [Ami2CompilationPlan].
 *
 * This writer is format infrastructure, not an inference backend. It accepts already-normalized
 * canonical payloads and refuses to publish them if their semantic digests differ from the plan.
 */
class Ami2CanonicalBinaryWriter {
    fun write(
        plan: Ami2CompilationPlan,
        payloads: Ami2CanonicalPayloads,
        destination: File
    ): Result<Ami2WrittenBinaryArtifact> = runCatching {
        require(destination.extension.equals("ami", ignoreCase = true)) {
            "AMI2 destination must use the .ami extension"
        }
        require(plan.artifacts == Ami2FoundationContract.mandatoryArtifacts) {
            "AMI2 binary writer only accepts the canonical foundation plan"
        }

        validateSemanticPayloads(plan.foundation, payloads)

        val payloadByRole = linkedMapOf<Ami2ArtifactRole, ByteArray>()
        payloadByRole[Ami2ArtifactRole.MANIFEST] = encodeManifest(plan)
        payloadByRole[Ami2ArtifactRole.SOURCE_LINEAGE] = encodeSourceLineage(plan.foundation.lineage)
        payloadByRole[Ami2ArtifactRole.TOKENIZER] = payloads.tokenizer
        payloadByRole[Ami2ArtifactRole.CHAT_PROTOCOL] = payloads.chatProtocol
        payloadByRole[Ami2ArtifactRole.LOGICAL_GRAPH] = payloads.logicalGraph
        payloadByRole[Ami2ArtifactRole.TENSOR_INDEX] = payloads.tensorIndex
        payloadByRole[Ami2ArtifactRole.FOUNDATION_WEIGHTS] = payloads.foundationWeights

        val nonIntegrityDigests = payloadByRole.mapValues { (_, bytes) -> sha256(bytes) }
        payloadByRole[Ami2ArtifactRole.INTEGRITY] =
            encodeIntegrity(plan.foundation.semanticSha256, nonIntegrityDigests)

        require(payloadByRole.keys.toSet() == Ami2FoundationContract.mandatoryArtifacts) {
            "AMI2 writer payload set does not match canonical mandatory artifacts"
        }

        val planned = planSections(payloadByRole)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile ?: File("."), destination.name + ".partial")
        if (temporary.exists()) {
            require(temporary.delete()) { "unable to clear previous AMI2 staging file" }
        }

        writePayloads(temporary, planned, payloadByRole)
        val descriptors = planned.map { plannedSection ->
            val bytes = requireNotNull(payloadByRole[plannedSection.role])
            Ami2BinarySectionDescriptor(
                role = plannedSection.role,
                offset = plannedSection.offset,
                length = bytes.size.toLong(),
                alignmentBytes = plannedSection.alignmentBytes,
                sha256 = sha256(bytes)
            )
        }
        writeHeader(
            output = temporary,
            semanticSha256 = plan.foundation.semanticSha256,
            descriptors = descriptors
        )

        val verified = Ami2CanonicalBinaryReader()
            .read(temporary, verifySectionDigests = true)
            .getOrThrow()
        require(verified.bundle.foundation == plan.foundation) {
            "AMI2 round-trip foundation identity changed before publish"
        }
        require(verified.migrationEvidence == plan.migrationEvidence) {
            "AMI2 round-trip migration evidence changed before publish"
        }

        if (destination.exists()) {
            require(destination.delete()) { "unable to replace existing AMI2 destination" }
        }
        require(temporary.renameTo(destination)) {
            "unable to atomically publish AMI2 container"
        }

        Ami2WrittenBinaryArtifact(
            file = destination,
            foundation = plan.foundation,
            sections = descriptors,
            fileSha256 = sha256File(destination)
        )
    }

    private data class PlannedSection(
        val role: Ami2ArtifactRole,
        val offset: Long,
        val alignmentBytes: Int
    )

    private fun validateSemanticPayloads(
        foundation: Ami2FoundationIdentity,
        payloads: Ami2CanonicalPayloads
    ) {
        require(sha256(payloads.tokenizer) == foundation.tokenizerSha256) {
            "AMI2 tokenizer payload does not match foundation identity"
        }
        require(sha256(payloads.chatProtocol) == foundation.chatProtocolSha256) {
            "AMI2 chat-protocol payload does not match foundation identity"
        }
        require(sha256(payloads.logicalGraph) == foundation.logicalGraphSha256) {
            "AMI2 logical-graph payload does not match foundation identity"
        }
        require(sha256(payloads.tensorIndex) == foundation.tensorIndexSha256) {
            "AMI2 tensor-index payload does not match foundation identity"
        }
        require(sha256(payloads.foundationWeights) == foundation.canonicalWeightsSha256) {
            "AMI2 foundation-weight payload does not match foundation identity"
        }

        val recomputed = Ami2CompilationPlanner.computeSemanticSha256(
            architectureId = foundation.architectureId,
            lineage = foundation.lineage,
            tokenizerSha256 = foundation.tokenizerSha256,
            chatProtocolSha256 = foundation.chatProtocolSha256,
            logicalGraphSha256 = foundation.logicalGraphSha256,
            tensorIndexSha256 = foundation.tensorIndexSha256,
            canonicalWeightsSha256 = foundation.canonicalWeightsSha256,
            tensorCount = foundation.tensorCount,
            vocabularySize = foundation.vocabularySize
        )
        require(recomputed == foundation.semanticSha256) {
            "AMI2 foundation semantic digest is not canonical"
        }
        require(
            foundation.foundationId ==
                Ami2CompilationPlanner.foundationIdForSemantic(foundation.semanticSha256)
        ) {
            "AMI2 foundation id is not canonical for its semantic digest"
        }
    }

    private fun planSections(
        payloadByRole: Map<Ami2ArtifactRole, ByteArray>
    ): List<PlannedSection> {
        var cursor = Ami2BinaryLayout.HEADER_REGION_BYTES.toLong()
        return Ami2BinaryLayout.canonicalRoleOrder.map { role ->
            val alignment = if (role == Ami2ArtifactRole.FOUNDATION_WEIGHTS) {
                Ami2BinaryLayout.FOUNDATION_ALIGNMENT_BYTES
            } else {
                Ami2BinaryLayout.DEFAULT_ALIGNMENT_BYTES
            }
            cursor = alignUp(cursor, alignment.toLong())
            val planned = PlannedSection(role, cursor, alignment)
            cursor = Math.addExact(cursor, requireNotNull(payloadByRole[role]).size.toLong())
            planned
        }
    }

    private fun writePayloads(
        output: File,
        planned: List<PlannedSection>,
        payloadByRole: Map<Ami2ArtifactRole, ByteArray>
    ) {
        RandomAccessFile(output, "rw").use { raf ->
            val finalLength = planned.maxOf { section ->
                Math.addExact(
                    section.offset,
                    requireNotNull(payloadByRole[section.role]).size.toLong()
                )
            }
            raf.setLength(finalLength)
            planned.forEach { section ->
                raf.seek(section.offset)
                raf.write(requireNotNull(payloadByRole[section.role]))
            }
        }
    }

    private fun writeHeader(
        output: File,
        semanticSha256: String,
        descriptors: List<Ami2BinarySectionDescriptor>
    ) {
        require(descriptors.size <= Ami2BinaryLayout.MAX_BINARY_SECTIONS)
        RandomAccessFile(output, "rw").use { raf ->
            raf.seek(0L)
            raf.write(Ami2FoundationContract.magicAscii.toByteArray(Charsets.US_ASCII))
            writeU16(raf, Ami2FoundationContract.majorVersion)
            writeU16(raf, Ami2FoundationContract.minorVersion)
            writeU32(raf, descriptors.size.toLong())
            writeU32(raf, Ami2BinaryLayout.HEADER_REGION_BYTES.toLong())
            writeU64(raf, output.length().toULong())
            raf.write(hexToBytes(semanticSha256))
            writeU64(raf, 0UL)

            require(raf.filePointer == Ami2BinaryLayout.FIXED_HEADER_BYTES.toLong())

            descriptors.forEach { descriptor ->
                writeU32(raf, descriptor.role.ordinal.toLong())
                writeU32(raf, 0L)
                writeU64(raf, descriptor.offset.toULong())
                writeU64(raf, descriptor.length.toULong())
                writeU32(raf, descriptor.alignmentBytes.toLong())
                writeU32(raf, 0L)
                raf.write(hexToBytes(descriptor.sha256))
            }
        }
    }

    private fun encodeManifest(plan: Ami2CompilationPlan): ByteArray {
        val f = plan.foundation
        return buildString {
            append("format=AMI2\n")
            append("version=2.0\n")
            append("foundation_id=").append(f.foundationId).append('\n')
            append("architecture=").append(f.architectureId).append('\n')
            append("semantic_sha256=").append(f.semanticSha256).append('\n')
            append("tokenizer_sha256=").append(f.tokenizerSha256).append('\n')
            append("chat_protocol_sha256=").append(f.chatProtocolSha256).append('\n')
            append("logical_graph_sha256=").append(f.logicalGraphSha256).append('\n')
            append("tensor_index_sha256=").append(f.tensorIndexSha256).append('\n')
            append("canonical_weights_sha256=").append(f.canonicalWeightsSha256).append('\n')
            append("tensor_count=").append(f.tensorCount).append('\n')
            append("vocabulary_size=").append(f.vocabularySize).append('\n')
            append("legacy_ami1_sha256=")
                .append(plan.migrationEvidence.legacyAmi1Sha256)
                .append('\n')
        }.toByteArray(Charsets.UTF_8)
    }

    private fun encodeSourceLineage(lineage: Ami2SourceLineage): ByteArray =
        buildString {
            append("source=").append(lineage.source.name).append('\n')
            append("source_sha256=").append(lineage.sourceSha256).append('\n')
            append("source_byte_length=").append(lineage.sourceByteLength).append('\n')
        }.toByteArray(Charsets.UTF_8)

    private fun encodeIntegrity(
        semanticSha256: String,
        sectionDigests: Map<Ami2ArtifactRole, String>
    ): ByteArray = buildString {
        append("format=AMI2-INTEGRITY\n")
        append("semantic_sha256=").append(semanticSha256).append('\n')
        Ami2BinaryLayout.canonicalRoleOrder
            .filter { it != Ami2ArtifactRole.INTEGRITY }
            .forEach { role ->
                append("section.")
                    .append(role.name)
                    .append('=')
                    .append(requireNotNull(sectionDigests[role]))
                    .append('\n')
            }
    }.toByteArray(Charsets.UTF_8)
}

/**
 * Bounded reader/verifier for canonical AMI2 foundation containers.
 *
 * It independently reconstructs the semantic digest from section hashes plus source lineage, so
 * valid physical section tables cannot silently substitute a different foundation identity.
 */
class Ami2CanonicalBinaryReader {
    fun read(
        file: File,
        verifySectionDigests: Boolean = true
    ): Result<Ami2LoadedBinaryArtifact> = runCatching {
        require(file.exists() && file.isFile) { "AMI2 file does not exist" }

        val header = parseHeader(file)
        validateDescriptors(header.descriptors, file.length())

        if (verifySectionDigests) {
            header.descriptors.forEach { descriptor ->
                require(
                    sha256Range(file, descriptor.offset, descriptor.length) == descriptor.sha256
                ) {
                    "AMI2 section digest mismatch: ${descriptor.role}"
                }
            }
        }

        val byRole = header.descriptors.associateBy { it.role }
        val manifest = parseFields(
            readTextSection(file, requireNotNull(byRole[Ami2ArtifactRole.MANIFEST])),
            "AMI2 manifest"
        )
        val lineageFields = parseFields(
            readTextSection(file, requireNotNull(byRole[Ami2ArtifactRole.SOURCE_LINEAGE])),
            "AMI2 source lineage"
        )
        val integrity = parseFields(
            readTextSection(file, requireNotNull(byRole[Ami2ArtifactRole.INTEGRITY])),
            "AMI2 integrity"
        )

        require(manifest["format"] == "AMI2") { "AMI2 manifest format mismatch" }
        require(manifest["version"] == "2.0") { "AMI2 manifest version mismatch" }

        val lineage = Ami2SourceLineage(
            source = Ami2ImportSource.valueOf(
                requireField(lineageFields, "source", "AMI2 source lineage")
            ),
            sourceSha256 = requireField(lineageFields, "source_sha256", "AMI2 source lineage"),
            sourceByteLength = requireField(
                lineageFields,
                "source_byte_length",
                "AMI2 source lineage"
            ).toLong()
        )

        fun digest(role: Ami2ArtifactRole): String =
            requireNotNull(byRole[role]).sha256

        require(
            requireField(manifest, "tokenizer_sha256", "AMI2 manifest") ==
                digest(Ami2ArtifactRole.TOKENIZER)
        ) { "AMI2 tokenizer identity does not match section table" }
        require(
            requireField(manifest, "chat_protocol_sha256", "AMI2 manifest") ==
                digest(Ami2ArtifactRole.CHAT_PROTOCOL)
        ) { "AMI2 chat-protocol identity does not match section table" }
        require(
            requireField(manifest, "logical_graph_sha256", "AMI2 manifest") ==
                digest(Ami2ArtifactRole.LOGICAL_GRAPH)
        ) { "AMI2 logical-graph identity does not match section table" }
        require(
            requireField(manifest, "tensor_index_sha256", "AMI2 manifest") ==
                digest(Ami2ArtifactRole.TENSOR_INDEX)
        ) { "AMI2 tensor-index identity does not match section table" }
        require(
            requireField(manifest, "canonical_weights_sha256", "AMI2 manifest") ==
                digest(Ami2ArtifactRole.FOUNDATION_WEIGHTS)
        ) { "AMI2 foundation-weight identity does not match section table" }

        val semanticSha256 = Ami2CompilationPlanner.computeSemanticSha256(
            architectureId = requireField(manifest, "architecture", "AMI2 manifest"),
            lineage = lineage,
            tokenizerSha256 = digest(Ami2ArtifactRole.TOKENIZER),
            chatProtocolSha256 = digest(Ami2ArtifactRole.CHAT_PROTOCOL),
            logicalGraphSha256 = digest(Ami2ArtifactRole.LOGICAL_GRAPH),
            tensorIndexSha256 = digest(Ami2ArtifactRole.TENSOR_INDEX),
            canonicalWeightsSha256 = digest(Ami2ArtifactRole.FOUNDATION_WEIGHTS),
            tensorCount = requireField(manifest, "tensor_count", "AMI2 manifest").toInt(),
            vocabularySize = requireField(
                manifest,
                "vocabulary_size",
                "AMI2 manifest"
            ).toInt()
        )

        require(semanticSha256 == header.semanticSha256) {
            "AMI2 header semantic digest mismatch"
        }
        require(
            semanticSha256 == requireField(manifest, "semantic_sha256", "AMI2 manifest")
        ) { "AMI2 manifest semantic digest mismatch" }

        val foundationId = requireField(manifest, "foundation_id", "AMI2 manifest")
        require(
            foundationId == Ami2CompilationPlanner.foundationIdForSemantic(semanticSha256)
        ) { "AMI2 foundation id does not match semantic digest" }

        val foundation = Ami2FoundationIdentity(
            foundationId = foundationId,
            architectureId = requireField(manifest, "architecture", "AMI2 manifest"),
            lineage = lineage,
            tokenizerSha256 = digest(Ami2ArtifactRole.TOKENIZER),
            chatProtocolSha256 = digest(Ami2ArtifactRole.CHAT_PROTOCOL),
            logicalGraphSha256 = digest(Ami2ArtifactRole.LOGICAL_GRAPH),
            tensorIndexSha256 = digest(Ami2ArtifactRole.TENSOR_INDEX),
            canonicalWeightsSha256 = digest(Ami2ArtifactRole.FOUNDATION_WEIGHTS),
            semanticSha256 = semanticSha256,
            tensorCount = requireField(manifest, "tensor_count", "AMI2 manifest").toInt(),
            vocabularySize = requireField(
                manifest,
                "vocabulary_size",
                "AMI2 manifest"
            ).toInt()
        )

        require(integrity["format"] == "AMI2-INTEGRITY") {
            "AMI2 integrity payload format mismatch"
        }
        require(
            requireField(integrity, "semantic_sha256", "AMI2 integrity") == semanticSha256
        ) { "AMI2 integrity semantic binding mismatch" }
        Ami2BinaryLayout.canonicalRoleOrder
            .filter { it != Ami2ArtifactRole.INTEGRITY }
            .forEach { role ->
                require(
                    requireField(integrity, "section.${role.name}", "AMI2 integrity") ==
                        digest(role)
                ) {
                    "AMI2 integrity section binding mismatch: $role"
                }
            }

        val bundle = Ami2FoundationBundle(
            foundation = foundation,
            artifacts = header.descriptors.mapTo(linkedSetOf()) { it.role }
        )

        Ami2LoadedBinaryArtifact(
            file = file,
            bundle = bundle,
            migrationEvidence = Ami2MigrationEvidence(
                requireField(manifest, "legacy_ami1_sha256", "AMI2 manifest")
            ),
            sections = header.descriptors,
            fileSha256 = sha256File(file)
        )
    }

    private data class Header(
        val semanticSha256: String,
        val descriptors: List<Ami2BinarySectionDescriptor>
    )

    private fun parseHeader(file: File): Header {
        val descriptors = mutableListOf<Ami2BinarySectionDescriptor>()
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            require(magic.toString(Charsets.US_ASCII) == Ami2FoundationContract.magicAscii) {
                "invalid AMI2 magic"
            }

            val major = readU16(raf)
            val minor = readU16(raf)
            require(major == Ami2FoundationContract.majorVersion) {
                "unsupported AMI2 major version: $major"
            }
            require(minor <= Ami2FoundationContract.minorVersion) {
                "unsupported AMI2 minor version: $minor"
            }

            val sectionCount = readU32(raf)
            require(sectionCount in 1L..Ami2BinaryLayout.MAX_BINARY_SECTIONS.toLong()) {
                "AMI2 section count exceeds binary limit"
            }
            val headerBytes = readU32(raf)
            require(headerBytes == Ami2BinaryLayout.HEADER_REGION_BYTES.toLong()) {
                "unsupported AMI2 header region size: $headerBytes"
            }
            val declaredFileLength = readU64(raf)
            require(declaredFileLength <= Long.MAX_VALUE.toULong())
            require(declaredFileLength.toLong() == file.length()) {
                "AMI2 file length changed or header length is invalid"
            }

            val semanticBytes = ByteArray(32)
            raf.readFully(semanticBytes)
            val semanticSha256 = semanticBytes.toHex()
            val reserved = readU64(raf)
            require(reserved == 0UL) { "AMI2 header reserved field must be zero" }

            repeat(sectionCount.toInt()) {
                val roleOrdinal = readU32(raf)
                require(roleOrdinal in 0L until Ami2ArtifactRole.values().size.toLong()) {
                    "invalid AMI2 artifact role id: $roleOrdinal"
                }
                val flags = readU32(raf)
                require(flags == 0L) { "unsupported AMI2 descriptor flags: $flags" }

                val offset = readU64(raf)
                val length = readU64(raf)
                val alignment = readU32(raf)
                val descriptorReserved = readU32(raf)
                require(descriptorReserved == 0L) {
                    "AMI2 descriptor reserved field must be zero"
                }
                require(offset <= Long.MAX_VALUE.toULong())
                require(length <= Long.MAX_VALUE.toULong())
                require(alignment in 1L..Int.MAX_VALUE.toLong())

                val digestBytes = ByteArray(32)
                raf.readFully(digestBytes)
                descriptors += Ami2BinarySectionDescriptor(
                    role = Ami2ArtifactRole.values()[roleOrdinal.toInt()],
                    offset = offset.toLong(),
                    length = length.toLong(),
                    alignmentBytes = alignment.toInt(),
                    sha256 = digestBytes.toHex()
                )
            }
            return Header(semanticSha256, descriptors)
        }
    }

    private fun validateDescriptors(
        descriptors: List<Ami2BinarySectionDescriptor>,
        fileLength: Long
    ) {
        require(descriptors.size == Ami2FoundationContract.mandatoryArtifacts.size) {
            "canonical AMI2 requires exactly the mandatory foundation artifacts"
        }
        Ami2FoundationContract.mandatoryArtifacts.forEach { role ->
            require(descriptors.count { it.role == role } == 1) {
                "canonical AMI2 requires exactly one $role section"
            }
        }
        require(
            descriptors.mapTo(linkedSetOf()) { it.role } ==
                Ami2FoundationContract.mandatoryArtifacts
        ) { "canonical AMI2 cannot contain optional/device artifacts in the foundation file" }
        require(descriptors.map { it.role } == Ami2BinaryLayout.canonicalRoleOrder) {
            "canonical AMI2 section order is invalid"
        }

        descriptors.forEach { descriptor ->
            val expectedAlignment =
                if (descriptor.role == Ami2ArtifactRole.FOUNDATION_WEIGHTS) {
                    Ami2BinaryLayout.FOUNDATION_ALIGNMENT_BYTES
                } else {
                    Ami2BinaryLayout.DEFAULT_ALIGNMENT_BYTES
                }
            require(descriptor.alignmentBytes == expectedAlignment) {
                "canonical AMI2 section alignment is invalid: ${descriptor.role}"
            }
            require(descriptor.endExclusive <= fileLength) {
                "AMI2 section exceeds file length: ${descriptor.role}"
            }
        }
        descriptors.sortedBy { it.offset }.zipWithNext().forEach { (left, right) ->
            require(left.endExclusive <= right.offset) {
                "AMI2 sections overlap: ${left.role} and ${right.role}"
            }
        }
    }

    private fun readTextSection(
        file: File,
        descriptor: Ami2BinarySectionDescriptor
    ): String {
        require(descriptor.length in 1L..MAX_CONTROL_SECTION_BYTES.toLong()) {
            "AMI2 control section exceeds reader limit: ${descriptor.role}"
        }
        val bytes = ByteArray(descriptor.length.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(descriptor.offset)
            raf.readFully(bytes)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun parseFields(text: String, label: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "$label contains malformed field" }
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                require(fields.put(key, value) == null) {
                    "$label contains duplicate field: $key"
                }
            }
        return fields
    }

    private fun requireField(
        fields: Map<String, String>,
        key: String,
        label: String
    ): String = requireNotNull(fields[key]) { "$label field is missing: $key" }

    private companion object {
        const val MAX_CONTROL_SECTION_BYTES: Int = 256 * 1024
    }
}

private fun alignUp(value: Long, alignment: Long): Long {
    require(alignment > 0L)
    val remainder = value % alignment
    return if (remainder == 0L) value else Math.addExact(value, alignment - remainder)
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

private fun sha256Range(file: File, offset: Long, length: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(offset)
        var remaining = length
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = raf.read(buffer, 0, wanted)
            if (read < 0) throw EOFException("AMI2 section is truncated during verification")
            if (read == 0) continue
            digest.update(buffer, 0, read)
            remaining -= read.toLong()
        }
    }
    return digest.digest().toHex()
}

private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun hexToBytes(hex: String): ByteArray {
    require(hex.matches(Regex("[0-9a-f]{64}")))
    return ByteArray(32) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun writeU16(out: RandomAccessFile, value: Int) {
    require(value in 0..0xffff)
    out.write(value and 0xff)
    out.write((value ushr 8) and 0xff)
}

private fun writeU32(out: RandomAccessFile, value: Long) {
    require(value in 0L..0xffff_ffffL)
    repeat(4) { index ->
        out.write(((value ushr (8 * index)) and 0xffL).toInt())
    }
}

private fun writeU64(out: RandomAccessFile, value: ULong) {
    repeat(8) { index ->
        out.write(((value shr (8 * index)) and 0xffUL).toInt())
    }
}

private fun readU16(input: RandomAccessFile): Int {
    val b0 = input.read()
    val b1 = input.read()
    if (b0 < 0 || b1 < 0) throw EOFException("AMI2 header is truncated")
    return b0 or (b1 shl 8)
}

private fun readU32(input: RandomAccessFile): Long {
    var value = 0L
    repeat(4) { index ->
        val byte = input.read()
        if (byte < 0) throw EOFException("AMI2 header is truncated")
        value = value or ((byte.toLong() and 0xffL) shl (8 * index))
    }
    return value
}

private fun readU64(input: RandomAccessFile): ULong {
    var value = 0UL
    repeat(8) { index ->
        val byte = input.read()
        if (byte < 0) throw EOFException("AMI2 header is truncated")
        value = value or ((byte.toULong() and 0xffUL) shl (8 * index))
    }
    return value
}
