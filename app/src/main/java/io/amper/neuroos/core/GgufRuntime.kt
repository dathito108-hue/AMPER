package io.amper.neuroos.core

import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest

private const val GGUF_HEADER_BYTES = 24
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
private const val DEFAULT_GGUF_ALIGNMENT = 32L
private val SUPPORTED_GGUF_VERSIONS = setOf(2L, 3L)

/**
 * Admission limits are sovereign safety ceilings, not device-sizing recommendations.
 * Defaults intentionally leave substantial headroom for large legitimate GGUF artifacts.
 * Deployments that intentionally exceed a structural ceiling can widen the policy explicitly
 * without weakening validation globally.
 */
data class GgufAdmissionPolicy(
    val maxArtifactBytes: Long = 8_796_093_022_208L, // 8 TiB
    val maxTensorCount: ULong = 16_000_000UL,
    val maxMetadataKeyValueCount: ULong = 4_000_000UL,
    val maxMetadataKeyBytes: Long = 65_535L,
    val maxMetadataStringBytes: Long = 1_073_741_824L, // 1 GiB per string value
    val maxMetadataArrayElements: ULong = 64_000_000UL,
    val maxMetadataArrayDepth: Int = 16,
    val maxTensorNameBytes: Long = 64L,
    val maxTensorDimensions: Int = 8,
    val maxTensorElements: ULong = Long.MAX_VALUE.toULong(),
    val maxAlignmentBytes: Long = 16_777_216L // 16 MiB
) {
    init {
        require(maxArtifactBytes >= GGUF_HEADER_BYTES.toLong()) {
            "GGUF admission maxArtifactBytes must cover the fixed header"
        }
        require(maxTensorCount > 0UL) { "GGUF admission maxTensorCount must be positive" }
        require(maxMetadataKeyValueCount > 0UL) {
            "GGUF admission maxMetadataKeyValueCount must be positive"
        }
        require(maxMetadataKeyBytes in 1L..65_535L) {
            "GGUF admission maxMetadataKeyBytes must be within the GGUF key limit"
        }
        require(maxMetadataStringBytes > 0L) {
            "GGUF admission maxMetadataStringBytes must be positive"
        }
        require(maxMetadataArrayElements > 0UL) {
            "GGUF admission maxMetadataArrayElements must be positive"
        }
        require(maxMetadataArrayDepth > 0) {
            "GGUF admission maxMetadataArrayDepth must be positive"
        }
        require(maxTensorNameBytes in 1L..64L) {
            "GGUF admission maxTensorNameBytes must be within the GGUF tensor-name limit"
        }
        require(maxTensorDimensions > 0) {
            "GGUF admission maxTensorDimensions must be positive"
        }
        require(maxTensorElements > 0UL) {
            "GGUF admission maxTensorElements must be positive"
        }
        require(maxAlignmentBytes >= 8L) {
            "GGUF admission maxAlignmentBytes must be at least 8 bytes"
        }
    }
}

data class GgufHeader(
    val version: Long,
    val tensorCount: ULong,
    val metadataKeyValueCount: ULong,
    val supported: Boolean
)

data class GgufArtifactInspection(
    val displayName: String,
    val locator: String,
    val lengthBytes: Long?,
    val sha256: String,
    val header: GgufHeader
)

/**
 * Validates a GGUF artifact before it is admitted into the installed-model catalog.
 *
 * Phase 69 binds parsed identity, SHA-256 and observed byte length to one InputStream.
 * Phase 72 bounds artifact/header resource claims before native load.
 * Phase 73 walks metadata and tensor-info tables from that same stream with checked arithmetic,
 * bounded strings/arrays/dimensions and no allocation derived from an untrusted large length.
 * Phase 74 validates tensor shape products and exact fixed-width footprints.
 * Phase 75 extends exact footprint validation to the classic ggml quantized block layouts.
 * Phase 76 moves exact tensor storage knowledge into an explicit ABI profile so a native backend
 * can bind admission to the runtime version it actually packages instead of inheriting a stale
 * hard-coded table. Unknown/new type IDs remain shape/offset bounded without guessed footprints.
 */
class GgufInspector(
    private val admissionPolicy: GgufAdmissionPolicy = GgufAdmissionPolicy(),
    private val tensorLayoutProfile: GgufTensorLayoutProfile = GgufTensorLayoutProfiles.CLASSIC_GGML
) {
    val tensorLayoutProfileId: String get() = tensorLayoutProfile.id

    fun inspect(source: ModelArtifactSource): Result<GgufArtifactInspection> = runCatching {
        val displayName = source.displayName
        val locator = source.locator
        val declaredLength = source.lengthBytes

        declaredLength?.let { declared ->
            require(declared >= 0L) { "model artifact declared a negative length" }
            require(declared <= admissionPolicy.maxArtifactBytes) {
                "model artifact exceeds GGUF admission size limit: declared=$declared max=${admissionPolicy.maxArtifactBytes}"
            }
        }

        val snapshot = source.openStream().use { input ->
            inspectSnapshot(input, declaredLength)
        }

        declaredLength?.let { declared ->
            require(declared == snapshot.lengthBytes) {
                "model artifact length changed during admission: declared=$declared observed=${snapshot.lengthBytes}"
            }
        }

        GgufArtifactInspection(
            displayName = displayName,
            locator = locator,
            lengthBytes = snapshot.lengthBytes,
            sha256 = snapshot.sha256,
            header = snapshot.header
        )
    }

    /**
     * Probe is intentionally observational: callers can inspect a structurally readable fixed
     * header and learn that a GGUF version is unsupported. Full admission remains fail-closed
     * in [inspect].
     */
    fun probe(source: ModelArtifactSource): Result<GgufHeader> = runCatching {
        source.openStream().use(::readHeader)
    }

    private fun inspectSnapshot(input: InputStream, declaredLength: Long?): ArtifactSnapshot {
        val digest = MessageDigest.getInstance("SHA-256")
        val reader = SnapshotReader(input, digest, admissionPolicy.maxArtifactBytes)
        val headerBytes = reader.readExact(GGUF_HEADER_BYTES, "GGUF header")
        val header = parseHeader(headerBytes)
        requireSupportedAndBounded(header)

        declaredLength?.let { declared ->
            require(declared >= reader.bytesRead) {
                "model artifact declared length is smaller than its GGUF header"
            }
        }

        val structure = validateStructure(reader, header)
        reader.drain()

        if (header.tensorCount > 0UL) {
            val tensorDataBytes = reader.bytesRead - structure.tensorDataOffset
            require(tensorDataBytes > 0L) {
                "GGUF declares tensors but contains no tensor data"
            }
            require(structure.maxTensorOffset < tensorDataBytes) {
                "GGUF tensor offset lies outside tensor data: offset=${structure.maxTensorOffset} dataBytes=$tensorDataBytes"
            }
            require(structure.maxKnownTensorEnd <= tensorDataBytes) {
                "GGUF tensor footprint lies outside tensor data: end=${structure.maxKnownTensorEnd} dataBytes=$tensorDataBytes"
            }
        }

        return ArtifactSnapshot(
            header = header,
            lengthBytes = reader.bytesRead,
            sha256 = digest.digest().toHex()
        )
    }

    private fun validateStructure(reader: SnapshotReader, header: GgufHeader): StructuralSnapshot {
        var alignment = DEFAULT_GGUF_ALIGNMENT
        var metadataIndex = 0UL
        while (metadataIndex < header.metadataKeyValueCount) {
            val keyLength = boundedLength(
                reader.readU64("metadata key length"),
                admissionPolicy.maxMetadataKeyBytes,
                "GGUF metadata key"
            )
            require(keyLength > 0L) { "GGUF metadata key must not be empty" }
            val keyBytes = reader.readExact(keyLength.toInt(), "GGUF metadata key")
            require(keyBytes.all { (it.toInt() and 0xff) in 0x20..0x7e }) {
                "GGUF metadata key must contain printable ASCII bytes"
            }
            val key = keyBytes.toString(Charsets.US_ASCII)
            val valueType = reader.readU32("metadata value type")
            requireMetadataType(valueType)

            if (key == "general.alignment") {
                require(valueType == GGUF_TYPE_UINT32) {
                    "GGUF general.alignment must be UINT32"
                }
                val requestedAlignment = reader.readU32("general.alignment")
                alignment = validateAlignment(requestedAlignment)
            } else {
                consumeMetadataValue(reader, valueType, depth = 0)
            }
            metadataIndex += 1UL
        }

        var maxTensorOffset = -1L
        var maxKnownTensorEnd = 0L
        var tensorIndex = 0UL
        while (tensorIndex < header.tensorCount) {
            val nameLength = boundedLength(
                reader.readU64("tensor name length"),
                admissionPolicy.maxTensorNameBytes,
                "GGUF tensor name"
            )
            require(nameLength > 0L) { "GGUF tensor name must not be empty" }
            reader.skipExact(nameLength, "GGUF tensor name")

            val dimensionCount = reader.readU32("tensor dimension count")
            require(dimensionCount in 1L..admissionPolicy.maxTensorDimensions.toLong()) {
                "GGUF tensor dimension count exceeds admission limit: claimed=$dimensionCount max=${admissionPolicy.maxTensorDimensions}"
            }
            var elementCount = 1UL
            var firstDimension = 0UL
            var dimensionIndex = 0L
            while (dimensionIndex < dimensionCount) {
                val dimension = reader.readU64("tensor dimension")
                require(dimension > 0UL) { "GGUF tensor dimensions must be positive" }
                if (dimensionIndex == 0L) firstDimension = dimension
                elementCount = checkedTensorElements(elementCount, dimension)
                dimensionIndex += 1L
            }

            val tensorType = reader.readU32("tensor type")
            val offset = boundedLength(
                reader.readU64("tensor offset"),
                admissionPolicy.maxArtifactBytes,
                "GGUF tensor offset"
            )
            require(offset % alignment == 0L) {
                "GGUF tensor offset is not aligned: offset=$offset alignment=$alignment"
            }
            if (offset > maxTensorOffset) maxTensorOffset = offset

            tensorLayoutProfile.layoutFor(tensorType)?.let { layout ->
                val storageBytes = checkedTensorStorageBytes(elementCount, firstDimension, layout)
                val end = try {
                    Math.addExact(offset, storageBytes)
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("GGUF tensor footprint overflows addressable range")
                }
                require(end <= admissionPolicy.maxArtifactBytes) {
                    "GGUF tensor footprint exceeds artifact admission limit"
                }
                if (end > maxKnownTensorEnd) maxKnownTensorEnd = end
            }
            tensorIndex += 1UL
        }

        val remainder = reader.bytesRead % alignment
        val padding = if (remainder == 0L) 0L else alignment - remainder
        reader.skipExact(padding, "GGUF tensor-data alignment padding")

        return StructuralSnapshot(
            tensorDataOffset = reader.bytesRead,
            maxTensorOffset = maxTensorOffset,
            maxKnownTensorEnd = maxKnownTensorEnd
        )
    }

    private fun checkedTensorElements(current: ULong, dimension: ULong): ULong {
        require(current <= ULong.MAX_VALUE / dimension) {
            "GGUF tensor element count overflows unsigned range"
        }
        val next = current * dimension
        require(next <= admissionPolicy.maxTensorElements) {
            "GGUF tensor element count exceeds admission limit: claimed=$next max=${admissionPolicy.maxTensorElements}"
        }
        return next
    }

    private fun checkedTensorStorageBytes(
        elementCount: ULong,
        firstDimension: ULong,
        layout: GgufTensorLayout
    ): Long {
        require(firstDimension % layout.blockSize == 0UL) {
            "GGUF tensor first dimension is incompatible with quantization block size: dimension=$firstDimension block=${layout.blockSize}"
        }
        require(elementCount % layout.blockSize == 0UL) {
            "GGUF tensor element count is incompatible with quantization block size"
        }
        val blockCount = elementCount / layout.blockSize
        require(blockCount <= Long.MAX_VALUE.toULong() / layout.typeSizeBytes.toULong()) {
            "GGUF tensor byte footprint overflows addressable range"
        }
        return blockCount.toLong() * layout.typeSizeBytes
    }

    private fun consumeMetadataValue(reader: SnapshotReader, type: Long, depth: Int) {
        when (type) {
            GGUF_TYPE_UINT8, GGUF_TYPE_INT8 -> reader.skipExact(1L, "metadata scalar")
            GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> reader.skipExact(2L, "metadata scalar")
            GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32 ->
                reader.skipExact(4L, "metadata scalar")
            GGUF_TYPE_BOOL -> {
                val value = reader.readU8("metadata bool")
                require(value == 0 || value == 1) { "GGUF bool value must be 0 or 1" }
            }
            GGUF_TYPE_STRING -> consumeMetadataString(reader)
            GGUF_TYPE_ARRAY -> consumeMetadataArray(reader, depth)
            GGUF_TYPE_UINT64, GGUF_TYPE_INT64, GGUF_TYPE_FLOAT64 ->
                reader.skipExact(8L, "metadata scalar")
            else -> error("unsupported GGUF metadata value type: $type")
        }
    }

    private fun consumeMetadataString(reader: SnapshotReader) {
        val length = boundedLength(
            reader.readU64("metadata string length"),
            admissionPolicy.maxMetadataStringBytes,
            "GGUF metadata string"
        )
        reader.skipExact(length, "GGUF metadata string")
    }

    private fun consumeMetadataArray(reader: SnapshotReader, depth: Int) {
        require(depth < admissionPolicy.maxMetadataArrayDepth) {
            "GGUF metadata array nesting exceeds admission limit: max=${admissionPolicy.maxMetadataArrayDepth}"
        }
        val elementType = reader.readU32("metadata array element type")
        requireMetadataType(elementType)
        val elementCount = reader.readU64("metadata array element count")
        require(elementCount <= admissionPolicy.maxMetadataArrayElements) {
            "GGUF metadata array exceeds admission element limit: claimed=$elementCount max=${admissionPolicy.maxMetadataArrayElements}"
        }

        when (elementType) {
            GGUF_TYPE_UINT8, GGUF_TYPE_INT8 ->
                reader.skipExact(checkedArrayBytes(elementCount, 1L), "metadata array")
            GGUF_TYPE_UINT16, GGUF_TYPE_INT16 ->
                reader.skipExact(checkedArrayBytes(elementCount, 2L), "metadata array")
            GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32 ->
                reader.skipExact(checkedArrayBytes(elementCount, 4L), "metadata array")
            GGUF_TYPE_UINT64, GGUF_TYPE_INT64, GGUF_TYPE_FLOAT64 ->
                reader.skipExact(checkedArrayBytes(elementCount, 8L), "metadata array")
            GGUF_TYPE_BOOL -> {
                var index = 0UL
                while (index < elementCount) {
                    val value = reader.readU8("metadata bool array")
                    require(value == 0 || value == 1) { "GGUF bool array value must be 0 or 1" }
                    index += 1UL
                }
            }
            GGUF_TYPE_STRING -> {
                var index = 0UL
                while (index < elementCount) {
                    consumeMetadataString(reader)
                    index += 1UL
                }
            }
            GGUF_TYPE_ARRAY -> {
                var index = 0UL
                while (index < elementCount) {
                    consumeMetadataArray(reader, depth + 1)
                    index += 1UL
                }
            }
        }
    }

    private fun checkedArrayBytes(elementCount: ULong, elementBytes: Long): Long {
        val maxElements = Long.MAX_VALUE.toULong() / elementBytes.toULong()
        require(elementCount <= maxElements) { "GGUF metadata array byte size overflows" }
        val bytes = elementCount.toLong() * elementBytes
        require(bytes <= admissionPolicy.maxArtifactBytes) {
            "GGUF metadata array byte size exceeds artifact admission limit"
        }
        return bytes
    }

    private fun requireMetadataType(type: Long) {
        require(type in GGUF_TYPE_UINT8..GGUF_TYPE_FLOAT64) {
            "invalid GGUF metadata value type: $type"
        }
    }

    private fun validateAlignment(rawAlignment: Long): Long {
        require(rawAlignment >= 8L && rawAlignment <= admissionPolicy.maxAlignmentBytes) {
            "GGUF alignment exceeds admission limit: claimed=$rawAlignment max=${admissionPolicy.maxAlignmentBytes}"
        }
        require(rawAlignment % 8L == 0L) {
            "GGUF alignment must be a multiple of 8: $rawAlignment"
        }
        return rawAlignment
    }

    private fun boundedLength(value: ULong, max: Long, label: String): Long {
        require(value <= Long.MAX_VALUE.toULong()) { "$label length exceeds signed addressable range" }
        val length = value.toLong()
        require(length <= max) { "$label exceeds admission limit: claimed=$length max=$max" }
        return length
    }

    private fun requireSupportedAndBounded(header: GgufHeader) {
        require(header.supported) {
            "GGUF version ${header.version} is structurally valid but not supported by this runtime contract"
        }
        require(header.tensorCount <= admissionPolicy.maxTensorCount) {
            "GGUF tensor count exceeds admission resource limit: claimed=${header.tensorCount} max=${admissionPolicy.maxTensorCount}"
        }
        require(header.metadataKeyValueCount <= admissionPolicy.maxMetadataKeyValueCount) {
            "GGUF metadata count exceeds admission resource limit: claimed=${header.metadataKeyValueCount} max=${admissionPolicy.maxMetadataKeyValueCount}"
        }
    }

    private fun readHeader(input: InputStream): GgufHeader {
        val bytes = ByteArray(GGUF_HEADER_BYTES)
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            when {
                read < 0 -> throw EOFException("GGUF header requires $GGUF_HEADER_BYTES bytes")
                read == 0 -> {
                    val one = input.read()
                    if (one < 0) throw EOFException("GGUF header requires $GGUF_HEADER_BYTES bytes")
                    bytes[offset++] = one.toByte()
                }
                else -> offset += read
            }
        }
        return parseHeader(bytes)
    }

    private fun parseHeader(bytes: ByteArray): GgufHeader {
        require(bytes.size >= GGUF_HEADER_BYTES)
        require(bytes[0] == 'G'.code.toByte() && bytes[1] == 'G'.code.toByte() &&
            bytes[2] == 'U'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            "invalid GGUF magic"
        }
        val version = u32le(bytes, 4)
        require(version > 0) { "invalid GGUF version" }
        return GgufHeader(
            version = version,
            tensorCount = u64le(bytes, 8),
            metadataKeyValueCount = u64le(bytes, 16),
            supported = version in SUPPORTED_GGUF_VERSIONS
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun u32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun u64le(bytes: ByteArray, offset: Int): ULong {
        var value = 0UL
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toULong() and 0xffUL) shl (8 * i))
        }
        return value
    }

    private class SnapshotReader(
        private val input: InputStream,
        private val digest: MessageDigest,
        private val maxBytes: Long
    ) {
        private val scratch = ByteArray(8 * 1024)
        var bytesRead: Long = 0L
            private set

        fun readU8(label: String): Int = readExact(1, label)[0].toInt() and 0xff

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
            for (i in 0 until 8) {
                value = value or ((bytes[i].toULong() and 0xffUL) shl (8 * i))
            }
            return value
        }

        fun readExact(length: Int, label: String): ByteArray {
            require(length >= 0)
            val target = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(target, offset, length - offset)
                when {
                    read < 0 -> throw EOFException("$label is truncated")
                    read == 0 -> {
                        val one = input.read()
                        if (one < 0) throw EOFException("$label is truncated")
                        target[offset++] = one.toByte()
                        recordByte(one)
                    }
                    else -> {
                        record(target, offset, read)
                        offset += read
                    }
                }
            }
            return target
        }

        fun skipExact(length: Long, label: String) {
            require(length >= 0L)
            var remaining = length
            while (remaining > 0L) {
                val wanted = minOf(remaining, scratch.size.toLong()).toInt()
                val read = input.read(scratch, 0, wanted)
                when {
                    read < 0 -> throw EOFException("$label is truncated")
                    read == 0 -> {
                        val one = input.read()
                        if (one < 0) throw EOFException("$label is truncated")
                        recordByte(one)
                        remaining -= 1L
                    }
                    else -> {
                        record(scratch, 0, read)
                        remaining -= read.toLong()
                    }
                }
            }
        }

        fun drain() {
            while (true) {
                val read = input.read(scratch)
                when {
                    read < 0 -> return
                    read == 0 -> {
                        val one = input.read()
                        if (one < 0) return
                        recordByte(one)
                    }
                    else -> record(scratch, 0, read)
                }
            }
        }

        private fun record(bytes: ByteArray, offset: Int, length: Int) {
            val next = Math.addExact(bytesRead, length.toLong())
            require(next <= maxBytes) {
                "model artifact exceeds GGUF admission size limit while reading: observed=$next max=$maxBytes"
            }
            digest.update(bytes, offset, length)
            bytesRead = next
        }

        private fun recordByte(value: Int) {
            val next = Math.addExact(bytesRead, 1L)
            require(next <= maxBytes) {
                "model artifact exceeds GGUF admission size limit while reading: observed=$next max=$maxBytes"
            }
            digest.update(value.toByte())
            bytesRead = next
        }
    }

    private data class StructuralSnapshot(
        val tensorDataOffset: Long,
        val maxTensorOffset: Long,
        val maxKnownTensorEnd: Long
    )

    private data class ArtifactSnapshot(
        val header: GgufHeader,
        val lengthBytes: Long,
        val sha256: String
    )
}
