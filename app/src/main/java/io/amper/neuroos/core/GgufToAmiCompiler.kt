package io.amper.neuroos.core

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Concrete AMI v1 binary layout.
 *
 * The first two pages are reserved for the fixed header + bounded section table. Runtime sections
 * begin after that region and are aligned independently. A descriptor is fixed-width so native
 * readers never need to parse variable-length metadata before locating a section.
 */
object AmiBinaryLayout {
    const val HEADER_REGION_BYTES: Int = 8192
    const val DESCRIPTOR_BYTES: Int = 64
    const val FIXED_HEADER_BYTES: Int = 32
    const val MAX_BINARY_SECTIONS: Int = 64

    init {
        require(
            FIXED_HEADER_BYTES + MAX_BINARY_SECTIONS * DESCRIPTOR_BYTES <= HEADER_REGION_BYTES
        )
    }
}

data class AmiCompiledArtifact(
    val file: File,
    val index: AmiContainerIndex,
    val sourceSha256: String,
    val outputSha256: String,
    val foundationSha256: String
)

data class AmiGgufTensorEntry(
    val name: String,
    val dimensions: List<ULong>,
    val ggmlType: Long,
    val sourceRelativeOffset: Long,
    val storageBytes: Long?
)

private data class GgufCompileScan(
    val version: Long,
    val tensorCount: Int,
    val metadataCount: Int,
    val architecture: AmiArchitectureId,
    val vocabularySize: Int,
    val metadataStart: Long,
    val metadataEnd: Long,
    val tensorDataOffset: Long,
    val tensors: List<AmiGgufTensorEntry>
)

/**
 * Portable SOURCE_EXACT compiler from GGUF into AMI v1.
 *
 * It does not requantize tensors. The entire GGUF tensor-data region is copied byte-for-byte into
 * FOUNDATION_WEIGHTS and the AMI tensor index keeps offsets relative to that copied region.
 *
 * TOKENIZER stores the original GGUF metadata table bytes in v1. This preserves vocabulary,
 * tokenizer model/pre-tokenizer metadata, chat templates and architecture metadata without
 * interpretation loss while AMI-native tokenizer normalization is developed independently.
 */
class GgufToAmiCompiler(
    private val inspector: GgufInspector = GgufInspector()
) {
    fun compile(
        source: ModelArtifactSource,
        destination: File
    ): Result<AmiCompiledArtifact> = runCatching {
        require(destination.extension.lowercase() == "ami") {
            "AMI destination must use the .ami extension"
        }

        val inspected = inspector.inspect(source).getOrThrow()
        val observedLength = requireNotNull(inspected.lengthBytes) {
            "GGUF compiler requires a stable observed source length"
        }
        val scan = source.openStream().use(::scan)
        require(scan.tensorCount == inspected.header.tensorCount.toLong().toInt()) {
            "GGUF compiler scan tensor count changed after admission"
        }

        val manifest = AmiManifest(
            architecture = scan.architecture,
            tensorCount = scan.tensorCount,
            vocabularySize = scan.vocabularySize,
            source = AmiSourceLineage(
                sourceFormat = AmiSourceFormat.GGUF,
                sourceSha256 = inspected.sha256,
                sourceByteLength = observedLength,
                sourcePrecisionPreserved = true
            ),
            canonicalPrecisionPolicy = AmiPrecisionPolicy.SOURCE_EXACT
        )

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile ?: File("."), destination.name + ".partial")
        if (temporary.exists()) require(temporary.delete()) {
            "unable to clear previous AMI compiler staging file"
        }

        val manifestBytes = encodeManifest(manifest)
        val graphBytes = encodeGraphIr(scan)
        val tensorIndexBytes = encodeTensorIndex(scan.tensors)
        val integrityBytes = encodeIntegritySeed(inspected.sha256, observedLength)

        val metadataLength = Math.subtractExact(scan.metadataEnd, scan.metadataStart)
        val foundationLength = Math.subtractExact(observedLength, scan.tensorDataOffset)
        require(metadataLength > 0L) { "GGUF metadata table is empty" }
        require(foundationLength > 0L) { "GGUF tensor-data region is empty" }

        val planned = planSections(
            listOf(
                PlannedSection.Bytes(AmiSectionType.MANIFEST, 64, manifestBytes),
                PlannedSection.SourceRange(
                    AmiSectionType.TOKENIZER,
                    64,
                    scan.metadataStart,
                    metadataLength
                ),
                PlannedSection.Bytes(AmiSectionType.GRAPH_IR, 64, graphBytes),
                PlannedSection.Bytes(AmiSectionType.TENSOR_INDEX, 64, tensorIndexBytes),
                PlannedSection.SourceRange(
                    AmiSectionType.FOUNDATION_WEIGHTS,
                    AmperMobileIntelligenceFormat.PAGE_ALIGNMENT_BYTES,
                    scan.tensorDataOffset,
                    foundationLength
                ),
                PlannedSection.Bytes(AmiSectionType.INTEGRITY, 64, integrityBytes)
            )
        )

        writePayloads(
            source = source,
            output = temporary,
            sections = planned
        )

        val descriptors = planned.map { section ->
            AmiSectionDescriptor(
                type = section.type,
                offset = section.offset,
                length = section.length,
                alignmentBytes = section.alignment,
                sha256 = sha256Range(temporary, section.offset, section.length)
            )
        }
        val index = AmiContainerIndex(
            manifest = manifest,
            sections = descriptors
        )

        writeHeader(temporary, index)
        validateBinary(temporary, index)

        if (destination.exists()) require(destination.delete()) {
            "unable to replace existing AMI destination"
        }
        require(temporary.renameTo(destination)) {
            "unable to atomically publish compiled AMI"
        }

        val foundation = index.sections.single {
            it.type == AmiSectionType.FOUNDATION_WEIGHTS
        }
        AmiCompiledArtifact(
            file = destination,
            index = index,
            sourceSha256 = inspected.sha256,
            outputSha256 = sha256File(destination),
            foundationSha256 = foundation.sha256
        )
    }

    private fun scan(input: InputStream): GgufCompileScan {
        val reader = GgufCompileReader(input)
        reader.requireMagic()
        val version = reader.readU32("GGUF version")
        require(version == 2L || version == 3L) {
            "unsupported GGUF version for AMI compiler: $version"
        }
        val tensorCountULong = reader.readU64("GGUF tensor count")
        val metadataCountULong = reader.readU64("GGUF metadata count")
        require(tensorCountULong <= Int.MAX_VALUE.toULong())
        require(metadataCountULong <= Int.MAX_VALUE.toULong())
        val tensorCount = tensorCountULong.toInt()
        val metadataCount = metadataCountULong.toInt()

        val metadataStart = reader.position
        var architecture: String? = null
        var vocabularySize: Int? = null
        var alignment = 32L

        repeat(metadataCount) {
            val key = reader.readString("metadata key", 65_535)
            val type = reader.readU32("metadata type")
            when (key) {
                "general.architecture" -> {
                    require(type == GGUF_TYPE_STRING) {
                        "general.architecture must be GGUF string"
                    }
                    architecture = reader.readString("general.architecture", 256)
                }
                "general.alignment" -> {
                    require(type == GGUF_TYPE_UINT32) {
                        "general.alignment must be GGUF uint32"
                    }
                    alignment = reader.readU32("general.alignment")
                    require(alignment >= 8L && alignment % 8L == 0L)
                }
                "tokenizer.ggml.tokens" -> {
                    require(type == GGUF_TYPE_ARRAY) {
                        "tokenizer.ggml.tokens must be GGUF array"
                    }
                    val elementType = reader.readU32("tokenizer token element type")
                    require(elementType == GGUF_TYPE_STRING) {
                        "tokenizer.ggml.tokens must be an array of strings"
                    }
                    val count = reader.readU64("tokenizer vocabulary size")
                    require(count in 1UL..Int.MAX_VALUE.toULong())
                    vocabularySize = count.toInt()
                    repeat(vocabularySize!!) {
                        reader.skipString("tokenizer token", 1_073_741_824L)
                    }
                }
                else -> reader.skipMetadataValue(type, 0)
            }
        }
        val metadataEnd = reader.position

        val tensors = ArrayList<AmiGgufTensorEntry>(tensorCount)
        repeat(tensorCount) {
            val name = reader.readString("tensor name", 64)
            val nDimensions = reader.readU32("tensor dimension count").toInt()
            require(nDimensions in 1..8)
            val dimensions = ArrayList<ULong>(nDimensions)
            repeat(nDimensions) {
                val dimension = reader.readU64("tensor dimension")
                require(dimension > 0UL)
                dimensions += dimension
            }
            val ggmlType = reader.readU32("tensor type")
            val sourceRelativeOffsetULong = reader.readU64("tensor offset")
            require(sourceRelativeOffsetULong <= Long.MAX_VALUE.toULong())
            val sourceRelativeOffset = sourceRelativeOffsetULong.toLong()
            require(sourceRelativeOffset % alignment == 0L)

            tensors += AmiGgufTensorEntry(
                name = name,
                dimensions = dimensions,
                ggmlType = ggmlType,
                sourceRelativeOffset = sourceRelativeOffset,
                storageBytes = storageBytes(dimensions, ggmlType)
            )
        }

        val remainder = reader.position % alignment
        if (remainder != 0L) {
            reader.skipExact(alignment - remainder, "GGUF tensor-data alignment")
        }

        return GgufCompileScan(
            version = version,
            tensorCount = tensorCount,
            metadataCount = metadataCount,
            architecture = AmiArchitectureId(
                requireNotNull(architecture) {
                    "GGUF general.architecture is required for AMI compilation"
                }.lowercase()
            ),
            vocabularySize = requireNotNull(vocabularySize) {
                "GGUF tokenizer.ggml.tokens is required for AMI compilation"
            },
            metadataStart = metadataStart,
            metadataEnd = metadataEnd,
            tensorDataOffset = reader.position,
            tensors = tensors
        )
    }

    private fun storageBytes(
        dimensions: List<ULong>,
        ggmlType: Long
    ): Long? {
        val layout = GgufTensorLayoutProfiles.CLASSIC_GGML.layoutFor(ggmlType) ?: return null
        var elementCount = 1UL
        dimensions.forEach { dimension ->
            require(elementCount <= ULong.MAX_VALUE / dimension) {
                "GGUF tensor element count overflow during AMI compilation"
            }
            elementCount *= dimension
        }
        val first = dimensions.first()
        require(first % layout.blockSize == 0UL) {
            "GGUF tensor first dimension is incompatible with profiled block size"
        }
        require(elementCount % layout.blockSize == 0UL) {
            "GGUF tensor element count is incompatible with profiled block size"
        }
        val blocks = elementCount / layout.blockSize
        require(blocks <= Long.MAX_VALUE.toULong() / layout.typeSizeBytes.toULong())
        return blocks.toLong() * layout.typeSizeBytes
    }

    private fun encodeManifest(manifest: AmiManifest): ByteArray =
        buildString {
            appendLine("format=AMI")
            appendLine("version=1.0")
            appendLine("architecture=" + manifest.architecture.value)
            appendLine("tensor_count=" + manifest.tensorCount)
            appendLine("vocabulary_size=" + manifest.vocabularySize)
            appendLine("source_format=" + manifest.source.sourceFormat.name)
            appendLine("source_sha256=" + manifest.source.sourceSha256)
            appendLine("source_byte_length=" + manifest.source.sourceByteLength)
            appendLine("source_precision_preserved=" + manifest.source.sourcePrecisionPreserved)
            appendLine("canonical_precision=" + manifest.canonicalPrecisionPolicy.name)
            appendLine("context_tiers=" + manifest.contextTiers.sorted().joinToString(","))
        }.toByteArray(Charsets.UTF_8)

    private fun encodeGraphIr(scan: GgufCompileScan): ByteArray =
        buildString {
            appendLine("graph_ir=AMI_GRAPH_V1")
            appendLine("source=GGUF")
            appendLine("source_version=" + scan.version)
            appendLine("architecture=" + scan.architecture.value)
            appendLine("tensor_count=" + scan.tensorCount)
            appendLine("metadata_count=" + scan.metadataCount)
        }.toByteArray(Charsets.UTF_8)

    private fun encodeIntegritySeed(sourceSha256: String, sourceLength: Long): ByteArray =
        buildString {
            appendLine("integrity=AMI_INTEGRITY_V1")
            appendLine("source_sha256=$sourceSha256")
            appendLine("source_byte_length=$sourceLength")
            appendLine("foundation_copy=SOURCE_EXACT")
        }.toByteArray(Charsets.UTF_8)

    private fun encodeTensorIndex(tensors: List<AmiGgufTensorEntry>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        writeU32(out, tensors.size.toLong())
        tensors.forEach { tensor ->
            val name = tensor.name.toByteArray(Charsets.UTF_8)
            require(name.size <= 64)
            writeU16(out, name.size)
            out.write(name)
            out.write(tensor.dimensions.size)
            out.write(0)
            writeU16(out, 0)
            tensor.dimensions.forEach { writeU64(out, it) }
            writeU32(out, tensor.ggmlType)
            writeU64(out, tensor.sourceRelativeOffset.toULong())
            writeU64(out, (tensor.storageBytes ?: 0L).toULong())
        }
        return out.toByteArray()
    }

    private sealed interface PlannedSection {
        val type: AmiSectionType
        val alignment: Int
        val length: Long
        var offset: Long

        data class Bytes(
            override val type: AmiSectionType,
            override val alignment: Int,
            val bytes: ByteArray,
            override var offset: Long = 0L
        ) : PlannedSection {
            override val length: Long get() = bytes.size.toLong()
        }

        data class SourceRange(
            override val type: AmiSectionType,
            override val alignment: Int,
            val sourceOffset: Long,
            override val length: Long,
            override var offset: Long = 0L
        ) : PlannedSection
    }

    private fun planSections(sections: List<PlannedSection>): List<PlannedSection> {
        require(sections.size <= AmiBinaryLayout.MAX_BINARY_SECTIONS)
        var cursor = AmiBinaryLayout.HEADER_REGION_BYTES.toLong()
        sections.forEach { section ->
            cursor = alignUp(cursor, section.alignment.toLong())
            section.offset = cursor
            cursor = Math.addExact(cursor, section.length)
        }
        return sections
    }

    private fun writePayloads(
        source: ModelArtifactSource,
        output: File,
        sections: List<PlannedSection>
    ) {
        RandomAccessFile(output, "rw").use { raf ->
            raf.setLength(AmiBinaryLayout.HEADER_REGION_BYTES.toLong())
            sections.forEach { section ->
                raf.seek(section.offset)
                when (section) {
                    is PlannedSection.Bytes -> raf.write(section.bytes)
                    is PlannedSection.SourceRange -> {
                        source.openStream().use { input ->
                            discardExact(input, section.sourceOffset)
                            copyExact(input, raf, section.length)
                        }
                    }
                }
            }
        }
    }

    private fun writeHeader(file: File, index: AmiContainerIndex) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0L)
            raf.write(AmperMobileIntelligenceFormat.MAGIC_ASCII.toByteArray(Charsets.US_ASCII))
            writeU16(raf, AmperMobileIntelligenceFormat.MAJOR_VERSION)
            writeU16(raf, AmperMobileIntelligenceFormat.MINOR_VERSION)
            writeU32(raf, index.sections.size.toLong())
            writeU32(raf, AmiBinaryLayout.HEADER_REGION_BYTES.toLong())
            writeU64(raf, file.length().toULong())
            writeU64(raf, 0UL)

            index.sections.forEach { descriptor ->
                writeU32(raf, descriptor.type.ordinal.toLong())
                writeU32(raf, descriptor.profileId.toLong())
                writeU64(raf, descriptor.offset.toULong())
                writeU64(raf, descriptor.length.toULong())
                writeU32(raf, descriptor.alignmentBytes.toLong())
                writeU32(raf, 0L)
                raf.write(hexToBytes(descriptor.sha256))
            }
        }
    }

    private fun validateBinary(file: File, index: AmiContainerIndex) {
        require(file.length() >= AmiBinaryLayout.HEADER_REGION_BYTES)
        val seen = linkedSetOf<AmiSectionType>()
        index.sections.forEach { descriptor ->
            require(descriptor.endExclusive <= file.length()) {
                "AMI section exceeds output file: " + descriptor.type
            }
            require(sha256Range(file, descriptor.offset, descriptor.length) == descriptor.sha256) {
                "AMI section digest mismatch after write: " + descriptor.type
            }
            seen += descriptor.type
        }
        require(seen.containsAll(AmperMobileIntelligenceFormat.mandatorySections))
    }

    private fun sha256File(file: File): String =
        FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            digest.digest().toHex()
        }

    private fun sha256Range(file: File, offset: Long, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            var remaining = length
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0L) {
                val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                val read = raf.read(buffer, 0, wanted)
                if (read < 0) throw EOFException("AMI section is truncated while hashing")
                if (read == 0) continue
                digest.update(buffer, 0, read)
                remaining -= read.toLong()
            }
        }
        return digest.digest().toHex()
    }

    private fun discardExact(input: InputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw EOFException("source is truncated while seeking")
            if (read == 0) continue
            remaining -= read.toLong()
        }
    }

    private fun copyExact(input: InputStream, output: RandomAccessFile, length: Long) {
        var remaining = length
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw EOFException("source range is truncated")
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private fun alignUp(value: Long, alignment: Long): Long {
        require(alignment > 0L)
        val remainder = value % alignment
        return if (remainder == 0L) value else Math.addExact(value, alignment - remainder)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length == 64)
        return ByteArray(32) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun writeU16(out: java.io.OutputStream, value: Int) {
        require(value in 0..0xffff)
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private fun writeU32(out: java.io.OutputStream, value: Long) {
        require(value in 0L..0xffff_ffffL)
        repeat(4) { index -> out.write(((value ushr (8 * index)) and 0xffL).toInt()) }
    }

    private fun writeU64(out: java.io.OutputStream, value: ULong) {
        repeat(8) { index -> out.write(((value shr (8 * index)) and 0xffUL).toInt()) }
    }

    private fun writeU16(out: RandomAccessFile, value: Int) {
        require(value in 0..0xffff)
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private fun writeU32(out: RandomAccessFile, value: Long) {
        require(value in 0L..0xffff_ffffL)
        repeat(4) { index -> out.write(((value ushr (8 * index)) and 0xffL).toInt()) }
    }

    private fun writeU64(out: RandomAccessFile, value: ULong) {
        repeat(8) { index -> out.write(((value shr (8 * index)) and 0xffUL).toInt()) }
    }

    private class GgufCompileReader(private val input: InputStream) {
        var position: Long = 0L
            private set

        fun requireMagic() {
            val magic = readExact(4, "GGUF magic")
            require(magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
                "invalid GGUF magic"
            }
        }

        fun readU32(label: String): Long {
            val bytes = readExact(4, label)
            return (bytes[0].toLong() and 0xffL) or
                ((bytes[1].toLong() and 0xffL) shl 8) or
                ((bytes[2].toLong() and 0xffL) shl 16) or
                ((bytes[3].toLong() and 0xffL) shl 24)
        }

        fun readU64(label: String): ULong {
            val bytes = readExact(8, label)
            var value = 0UL
            repeat(8) { index ->
                value = value or ((bytes[index].toULong() and 0xffUL) shl (8 * index))
            }
            return value
        }

        fun readString(label: String, maxBytes: Int): String {
            val length = readU64(label + " length")
            require(length <= maxBytes.toULong()) {
                "$label exceeds compiler limit"
            }
            return readExact(length.toInt(), label).toString(Charsets.UTF_8)
        }

        fun skipString(label: String, maxBytes: Long) {
            val length = readU64(label + " length")
            require(length <= maxBytes.toULong()) {
                "$label exceeds compiler limit"
            }
            skipExact(length.toLong(), label)
        }

        fun skipMetadataValue(type: Long, depth: Int) {
            require(depth < 16) { "GGUF metadata nesting exceeds compiler limit" }
            when (type) {
                GGUF_TYPE_UINT8, GGUF_TYPE_INT8 -> skipExact(1, "metadata scalar")
                GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> skipExact(2, "metadata scalar")
                GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32 ->
                    skipExact(4, "metadata scalar")
                GGUF_TYPE_BOOL -> {
                    val value = readExact(1, "metadata bool")[0].toInt() and 0xff
                    require(value == 0 || value == 1)
                }
                GGUF_TYPE_STRING -> skipString("metadata string", 1_073_741_824L)
                GGUF_TYPE_ARRAY -> {
                    val elementType = readU32("metadata array element type")
                    val count = readU64("metadata array element count")
                    require(count <= 64_000_000UL)
                    when (elementType) {
                        GGUF_TYPE_UINT8, GGUF_TYPE_INT8 ->
                            skipExact(checkedArrayBytes(count, 1), "metadata array")
                        GGUF_TYPE_UINT16, GGUF_TYPE_INT16 ->
                            skipExact(checkedArrayBytes(count, 2), "metadata array")
                        GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32 ->
                            skipExact(checkedArrayBytes(count, 4), "metadata array")
                        GGUF_TYPE_UINT64, GGUF_TYPE_INT64, GGUF_TYPE_FLOAT64 ->
                            skipExact(checkedArrayBytes(count, 8), "metadata array")
                        GGUF_TYPE_BOOL -> repeatCount(count) {
                            val value = readExact(1, "metadata bool")[0].toInt() and 0xff
                            require(value == 0 || value == 1)
                        }
                        GGUF_TYPE_STRING -> repeatCount(count) {
                            skipString("metadata array string", 1_073_741_824L)
                        }
                        GGUF_TYPE_ARRAY -> repeatCount(count) {
                            skipMetadataValue(GGUF_TYPE_ARRAY, depth + 1)
                        }
                        else -> error("invalid GGUF metadata array element type: $elementType")
                    }
                }
                GGUF_TYPE_UINT64, GGUF_TYPE_INT64, GGUF_TYPE_FLOAT64 ->
                    skipExact(8, "metadata scalar")
                else -> error("invalid GGUF metadata value type: $type")
            }
        }

        fun skipExact(length: Long, label: String) {
            require(length >= 0L)
            var remaining = length
            val buffer = ByteArray(8192)
            while (remaining > 0L) {
                val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, wanted)
                if (read < 0) throw EOFException("$label is truncated")
                if (read == 0) continue
                position = Math.addExact(position, read.toLong())
                remaining -= read.toLong()
            }
        }

        private fun readExact(length: Int, label: String): ByteArray {
            require(length >= 0)
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read < 0) throw EOFException("$label is truncated")
                if (read == 0) continue
                offset += read
                position = Math.addExact(position, read.toLong())
            }
            return bytes
        }

        private fun checkedArrayBytes(count: ULong, width: Long): Long {
            require(count <= Long.MAX_VALUE.toULong() / width.toULong())
            return count.toLong() * width
        }

        private inline fun repeatCount(count: ULong, block: () -> Unit) {
            var index = 0UL
            while (index < count) {
                block()
                index += 1UL
            }
        }
    }

    private companion object {
        const val GGUF_TYPE_UINT8 = 0L
        const val GGUF_TYPE_INT8 = 1L
        const val GGUF_TYPE_UINT16 = 2L
        const val GGUF_TYPE_INT16 = 3L
        const val GGUF_TYPE_UINT32 = 4L
        const val GGUF_TYPE_INT32 = 5L
        const val GGUF_TYPE_FLOAT32 = 6L
        const val GGUF_TYPE_BOOL = 7L
        const val GGUF_TYPE_STRING = 8L
        const val GGUF_TYPE_ARRAY = 9L
        const val GGUF_TYPE_UINT64 = 10L
        const val GGUF_TYPE_INT64 = 11L
        const val GGUF_TYPE_FLOAT64 = 12L
    }
}
