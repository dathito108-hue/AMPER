package io.amper.neuroos.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

data class AmiGgufTensorEntry(
    val name: String,
    val dimensions: List<ULong>,
    val ggmlType: Long,
    val sourceRelativeOffset: Long,
    val storageBytes: Long?
)

data class GgufCompileScan(
    val version: Long,
    val tensorCount: Int,
    val metadataCount: Int,
    val architecture: AmiArchitectureId,
    val vocabularySize: Int,
    val metadataStart: Long,
    val metadataEnd: Long,
    val tensorDataOffset: Long,
    val tensors: List<AmiGgufTensorEntry>,
    val chatTemplate: String?
)

/**
 * One canonical GGUF compiler-semantic scanner shared by AMI1 migration tooling and the AMI2
 * direct compiler. This is intentionally not a runtime parser.
 */
object GgufCompileSemantics {
    fun scan(input: InputStream): GgufCompileScan {
        val reader = Reader(input)
        reader.requireMagic()
        val version = reader.readU32("GGUF version")
        require(version == 2L || version == 3L) {
            "unsupported GGUF version for AMPER compilation: $version"
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
        var chatTemplate: String? = null
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
                        reader.skipString("tokenizer token", MAX_METADATA_STRING_BYTES)
                    }
                }
                "tokenizer.chat_template" -> {
                    require(type == GGUF_TYPE_STRING) {
                        "tokenizer.chat_template must be GGUF string"
                    }
                    chatTemplate = reader.readString(
                        "tokenizer.chat_template",
                        MAX_CHAT_TEMPLATE_BYTES
                    )
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
                    "GGUF general.architecture is required for AMPER compilation"
                }.lowercase()
            ),
            vocabularySize = requireNotNull(vocabularySize) {
                "GGUF tokenizer.ggml.tokens is required for AMPER compilation"
            },
            metadataStart = metadataStart,
            metadataEnd = metadataEnd,
            tensorDataOffset = reader.position,
            tensors = tensors,
            chatTemplate = chatTemplate
        )
    }

    fun encodeGraphIr(scan: GgufCompileScan): ByteArray =
        buildString {
            appendLine("graph_ir=AMI_GRAPH_V1")
            appendLine("source=GGUF")
            appendLine("source_version=" + scan.version)
            appendLine("architecture=" + scan.architecture.value)
            appendLine("tensor_count=" + scan.tensorCount)
            appendLine("metadata_count=" + scan.metadataCount)
        }.toByteArray(Charsets.UTF_8)

    fun encodeTensorIndex(tensors: List<AmiGgufTensorEntry>): ByteArray {
        val out = ByteArrayOutputStream()
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

    private fun storageBytes(
        dimensions: List<ULong>,
        ggmlType: Long
    ): Long? {
        val layout = GgufTensorLayoutProfiles.CLASSIC_GGML.layoutFor(ggmlType) ?: return null
        var elementCount = 1UL
        dimensions.forEach { dimension ->
            require(elementCount <= ULong.MAX_VALUE / dimension) {
                "GGUF tensor element count overflow during AMPER compilation"
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

    private class Reader(private val input: InputStream) {
        var position: Long = 0L
            private set

        fun requireMagic() {
            val magic = readExact(4, "GGUF magic")
            require(
                magic.contentEquals(
                    byteArrayOf(
                        'G'.code.toByte(),
                        'G'.code.toByte(),
                        'U'.code.toByte(),
                        'F'.code.toByte()
                    )
                )
            ) { "invalid GGUF magic" }
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
                GGUF_TYPE_STRING -> skipString("metadata string", MAX_METADATA_STRING_BYTES)
                GGUF_TYPE_ARRAY -> {
                    val elementType = readU32("metadata array element type")
                    val count = readU64("metadata array element count")
                    require(count <= MAX_METADATA_ARRAY_ELEMENTS)
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
                            skipString("metadata array string", MAX_METADATA_STRING_BYTES)
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

    private const val GGUF_TYPE_UINT8 = 0L
    private const val GGUF_TYPE_INT8 = 1L
    private const val GGUF_TYPE_UINT16 = 2L
    private const val GGUF_TYPE_INT16 = 3L
    private const val GGUF_TYPE_UINT32 = 4L
    private const val GGUF_TYPE_INT32 = 5L
    private const val GGUF_TYPE_FLOAT32 = 6L
    private const val GGUF_TYPE_BOOL = 7L
    private const val GGUF_TYPE_STRING = 8L
    private const val GGUF_TYPE_ARRAY = 9L
    private const val GGUF_TYPE_UINT64 = 10L
    private const val GGUF_TYPE_INT64 = 11L
    private const val GGUF_TYPE_FLOAT64 = 12L
    private const val MAX_METADATA_STRING_BYTES = 1_073_741_824L
    private const val MAX_CHAT_TEMPLATE_BYTES = 1 * 1024 * 1024
    private val MAX_METADATA_ARRAY_ELEMENTS = 64_000_000UL
}
