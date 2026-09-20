package io.amper.neuroos.core

import java.io.EOFException
import java.io.RandomAccessFile

sealed interface AmiGgufMetadataScalar {
    data class Unsigned(val value: ULong) : AmiGgufMetadataScalar
    data class Signed(val value: Long) : AmiGgufMetadataScalar
    data class Floating(val value: Double) : AmiGgufMetadataScalar
    data class BooleanValue(val value: Boolean) : AmiGgufMetadataScalar
    data class Text(val value: String) : AmiGgufMetadataScalar
}

data class AmiGgufMetadataSnapshot(
    val values: Map<String, AmiGgufMetadataScalar>
) {
    fun floating(key: String): Double? =
        when (val value = values[key]) {
            is AmiGgufMetadataScalar.Floating -> value.value
            is AmiGgufMetadataScalar.Unsigned -> value.value.toDouble()
            is AmiGgufMetadataScalar.Signed -> value.value.toDouble()
            else -> null
        }

    fun integer(key: String): Long? =
        when (val value = values[key]) {
            is AmiGgufMetadataScalar.Unsigned ->
                value.value.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
            is AmiGgufMetadataScalar.Signed -> value.value
            else -> null
        }

    fun text(key: String): String? =
        (values[key] as? AmiGgufMetadataScalar.Text)?.value
}

/**
 * Reads scalar model configuration from the preserved GGUF metadata bytes inside AMI TOKENIZER.
 *
 * Large tokenizer arrays are skipped without materialization. The compiler's GRAPH_IR supplies the
 * exact metadata entry count, so this reader never scans blindly for a terminator.
 */
class AmiPreservedGgufMetadataReader(
    private val maxEntries: Int = 1_000_000,
    private val maxScalarStringBytes: Int = 1 * 1024 * 1024
) {
    init {
        require(maxEntries > 0)
        require(maxScalarStringBytes > 0)
    }

    fun read(loaded: AmiLoadedArtifact): Result<AmiGgufMetadataSnapshot> = runCatching {
        val metadataCount = readMetadataCount(loaded)
        require(metadataCount in 1..maxEntries) {
            "AMI preserved GGUF metadata count exceeds mobile limit: $metadataCount"
        }

        val section = loaded.index.sections.single {
            it.type == AmiSectionType.TOKENIZER
        }
        val values = linkedMapOf<String, AmiGgufMetadataScalar>()

        RandomAccessFile(loaded.file, "r").use { raf ->
            raf.seek(section.offset)
            val end = section.endExclusive
            repeat(metadataCount) {
                val key = readString(
                    raf = raf,
                    end = end,
                    label = "GGUF metadata key",
                    maxBytes = 65_535
                )
                val type = readU32(raf, end)
                val scalar = readScalarOrSkipValue(
                    raf = raf,
                    end = end,
                    type = type,
                    depth = 0
                )
                if (scalar != null) {
                    require(values.put(key, scalar) == null) {
                        "duplicate preserved GGUF metadata key: $key"
                    }
                }
            }
            require(raf.filePointer == end) {
                "AMI preserved GGUF metadata length does not match GRAPH_IR count"
            }
        }

        AmiGgufMetadataSnapshot(values)
    }

    private fun readMetadataCount(loaded: AmiLoadedArtifact): Int {
        val graph = loaded.index.sections.single {
            it.type == AmiSectionType.GRAPH_IR
        }
        require(graph.length in 1L..64L * 1024L) {
            "AMI GRAPH_IR exceeds metadata reader limit"
        }
        val text = RandomAccessFile(loaded.file, "r").use { raf ->
            raf.seek(graph.offset)
            val bytes = ByteArray(graph.length.toInt())
            raf.readFully(bytes)
            bytes.toString(Charsets.UTF_8)
        }
        val countText = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("metadata_count=") }
            ?.substringAfter('=')
            ?: error("AMI GRAPH_IR metadata_count is missing")
        return countText.toInt().also { require(it > 0) }
    }

    private fun readScalarOrSkipValue(
        raf: RandomAccessFile,
        end: Long,
        type: Long,
        depth: Int
    ): AmiGgufMetadataScalar? {
        require(depth < 16) { "GGUF metadata nesting exceeds reader limit" }
        return when (type) {
            GGUF_TYPE_UINT8 ->
                AmiGgufMetadataScalar.Unsigned(readU8(raf, end).toULong())
            GGUF_TYPE_INT8 ->
                AmiGgufMetadataScalar.Signed(readU8(raf, end).toByte().toLong())
            GGUF_TYPE_UINT16 ->
                AmiGgufMetadataScalar.Unsigned(readU16(raf, end).toULong())
            GGUF_TYPE_INT16 ->
                AmiGgufMetadataScalar.Signed(readU16(raf, end).toShort().toLong())
            GGUF_TYPE_UINT32 ->
                AmiGgufMetadataScalar.Unsigned(readU32(raf, end).toULong())
            GGUF_TYPE_INT32 ->
                AmiGgufMetadataScalar.Signed(readU32(raf, end).toInt().toLong())
            GGUF_TYPE_FLOAT32 ->
                AmiGgufMetadataScalar.Floating(
                    Float.fromBits(readU32(raf, end).toInt()).toDouble()
                )
            GGUF_TYPE_BOOL -> {
                val value = readU8(raf, end)
                require(value == 0 || value == 1) {
                    "invalid GGUF metadata boolean"
                }
                AmiGgufMetadataScalar.BooleanValue(value == 1)
            }
            GGUF_TYPE_STRING ->
                AmiGgufMetadataScalar.Text(
                    readString(
                        raf = raf,
                        end = end,
                        label = "GGUF metadata string",
                        maxBytes = maxScalarStringBytes
                    )
                )
            GGUF_TYPE_ARRAY -> {
                val elementType = readU32(raf, end)
                val count = readU64(raf, end)
                require(count <= MAX_ARRAY_ELEMENTS.toULong()) {
                    "GGUF metadata array exceeds reader limit"
                }
                skipArray(
                    raf = raf,
                    end = end,
                    elementType = elementType,
                    count = count,
                    depth = depth + 1
                )
                null
            }
            GGUF_TYPE_UINT64 ->
                AmiGgufMetadataScalar.Unsigned(readU64(raf, end))
            GGUF_TYPE_INT64 ->
                AmiGgufMetadataScalar.Signed(readU64(raf, end).toLong())
            GGUF_TYPE_FLOAT64 ->
                AmiGgufMetadataScalar.Floating(
                    Double.fromBits(readU64(raf, end).toLong())
                )
            else -> error("invalid GGUF metadata value type: $type")
        }
    }

    private fun skipArray(
        raf: RandomAccessFile,
        end: Long,
        elementType: Long,
        count: ULong,
        depth: Int
    ) {
        when (elementType) {
            GGUF_TYPE_UINT8,
            GGUF_TYPE_INT8,
            GGUF_TYPE_BOOL ->
                skipExact(raf, end, checkedArrayBytes(count, 1L))
            GGUF_TYPE_UINT16,
            GGUF_TYPE_INT16 ->
                skipExact(raf, end, checkedArrayBytes(count, 2L))
            GGUF_TYPE_UINT32,
            GGUF_TYPE_INT32,
            GGUF_TYPE_FLOAT32 ->
                skipExact(raf, end, checkedArrayBytes(count, 4L))
            GGUF_TYPE_UINT64,
            GGUF_TYPE_INT64,
            GGUF_TYPE_FLOAT64 ->
                skipExact(raf, end, checkedArrayBytes(count, 8L))
            GGUF_TYPE_STRING -> repeatCount(count) {
                skipString(
                    raf = raf,
                    end = end,
                    label = "GGUF metadata array string",
                    maxBytes = MAX_ARRAY_STRING_BYTES
                )
            }
            GGUF_TYPE_ARRAY -> repeatCount(count) {
                val nestedType = readU32(raf, end)
                val nestedCount = readU64(raf, end)
                require(nestedCount <= MAX_ARRAY_ELEMENTS.toULong())
                skipArray(
                    raf = raf,
                    end = end,
                    elementType = nestedType,
                    count = nestedCount,
                    depth = depth + 1
                )
            }
            else -> error("invalid GGUF metadata array element type: $elementType")
        }
        require(depth < 16) { "GGUF metadata nesting exceeds reader limit" }
    }

    private fun readString(
        raf: RandomAccessFile,
        end: Long,
        label: String,
        maxBytes: Int
    ): String {
        val length = readU64(raf, end)
        require(length <= maxBytes.toULong()) { "$label exceeds reader limit" }
        val bytes = readBytes(raf, end, length.toInt())
        return bytes.toString(Charsets.UTF_8)
    }

    private fun skipString(
        raf: RandomAccessFile,
        end: Long,
        label: String,
        maxBytes: Long
    ) {
        val length = readU64(raf, end)
        require(length <= maxBytes.toULong()) { "$label exceeds reader limit" }
        require(length <= Long.MAX_VALUE.toULong())
        skipExact(raf, end, length.toLong())
    }

    private fun readBytes(
        raf: RandomAccessFile,
        end: Long,
        length: Int
    ): ByteArray {
        require(length >= 0)
        require(Math.addExact(raf.filePointer, length.toLong()) <= end) {
            "preserved GGUF metadata is truncated"
        }
        return ByteArray(length).also(raf::readFully)
    }

    private fun skipExact(
        raf: RandomAccessFile,
        end: Long,
        length: Long
    ) {
        require(length >= 0L)
        val target = Math.addExact(raf.filePointer, length)
        require(target <= end) {
            "preserved GGUF metadata is truncated"
        }
        raf.seek(target)
    }

    private fun readU8(raf: RandomAccessFile, end: Long): Int {
        require(raf.filePointer < end) {
            "preserved GGUF metadata is truncated"
        }
        val value = raf.read()
        if (value < 0) throw EOFException("preserved GGUF metadata is truncated")
        return value
    }

    private fun readU16(raf: RandomAccessFile, end: Long): Int {
        val b0 = readU8(raf, end)
        val b1 = readU8(raf, end)
        return b0 or (b1 shl 8)
    }

    private fun readU32(raf: RandomAccessFile, end: Long): Long {
        var value = 0L
        repeat(4) { index ->
            value = value or
                ((readU8(raf, end).toLong() and 0xffL) shl (8 * index))
        }
        return value
    }

    private fun readU64(raf: RandomAccessFile, end: Long): ULong {
        var value = 0UL
        repeat(8) { index ->
            value = value or
                ((readU8(raf, end).toULong() and 0xffUL) shl (8 * index))
        }
        return value
    }

    private fun checkedArrayBytes(count: ULong, width: Long): Long {
        require(count <= Long.MAX_VALUE.toULong() / width.toULong()) {
            "GGUF metadata array byte length overflow"
        }
        return count.toLong() * width
    }

    private inline fun repeatCount(count: ULong, block: () -> Unit) {
        var index = 0UL
        while (index < count) {
            block()
            index += 1UL
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

        const val MAX_ARRAY_ELEMENTS = 64_000_000
        const val MAX_ARRAY_STRING_BYTES = 1_073_741_824L
    }
}
