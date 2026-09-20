package io.amper.neuroos.core

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

data class AmiLoadedArtifact(
    val file: File,
    val index: AmiContainerIndex,
    val fileSha256: String
)

/**
 * Bounded AMI v1 binary reader and integrity verifier.
 *
 * Parsing the header never trusts section offsets or lengths until the complete descriptor set has
 * passed AmiContainerIndex structural validation. Digest verification is explicit and performed
 * before the artifact is admitted into AMPER-owned storage/runtime paths.
 */
class AmiBinaryReader {
    fun read(
        file: File,
        verifySectionDigests: Boolean = true
    ): Result<AmiLoadedArtifact> = runCatching {
        require(file.exists() && file.isFile) { "AMI file does not exist" }
        require(file.extension.equals("ami", ignoreCase = true)) {
            "AMI reader requires .ami artifact"
        }

        val parsed = parseHeader(file)
        if (verifySectionDigests) {
            parsed.index.sections.forEach { descriptor ->
                require(
                    sha256Range(file, descriptor.offset, descriptor.length) == descriptor.sha256
                ) {
                    "AMI section digest mismatch: " + descriptor.type
                }
            }
        }

        AmiLoadedArtifact(
            file = file,
            index = parsed.index,
            fileSha256 = sha256File(file)
        )
    }

    private data class ParsedHeader(
        val index: AmiContainerIndex
    )

    private fun parseHeader(file: File): ParsedHeader {
        val descriptors = mutableListOf<AmiSectionDescriptor>()
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            require(magic.toString(Charsets.US_ASCII) == AmperMobileIntelligenceFormat.MAGIC_ASCII) {
                "invalid AMI magic"
            }

            val major = readU16(raf)
            val minor = readU16(raf)
            require(major == AmperMobileIntelligenceFormat.MAJOR_VERSION) {
                "unsupported AMI major version: $major"
            }
            require(minor <= AmperMobileIntelligenceFormat.MINOR_VERSION) {
                "unsupported AMI minor version: $minor"
            }

            val sectionCount = readU32(raf)
            require(sectionCount in 1L..AmiBinaryLayout.MAX_BINARY_SECTIONS.toLong()) {
                "AMI section count exceeds binary limit"
            }

            val headerBytes = readU32(raf)
            require(headerBytes == AmiBinaryLayout.HEADER_REGION_BYTES.toLong()) {
                "unsupported AMI header region size: $headerBytes"
            }

            val declaredFileLength = readU64(raf)
            require(declaredFileLength <= Long.MAX_VALUE.toULong())
            require(declaredFileLength.toLong() == file.length()) {
                "AMI file length changed or header length is invalid"
            }
            val reserved = readU64(raf)
            require(reserved == 0UL) { "AMI v1 reserved header field must be zero" }

            repeat(sectionCount.toInt()) {
                val typeOrdinal = readU32(raf)
                require(typeOrdinal in 0L until AmiSectionType.values().size.toLong()) {
                    "invalid AMI section type id: $typeOrdinal"
                }
                val profileId = readU32(raf)
                require(profileId <= Int.MAX_VALUE.toLong())
                val offset = readU64(raf)
                val length = readU64(raf)
                val alignment = readU32(raf)
                val descriptorReserved = readU32(raf)
                require(descriptorReserved == 0L) {
                    "AMI v1 descriptor reserved field must be zero"
                }
                require(offset <= Long.MAX_VALUE.toULong())
                require(length <= Long.MAX_VALUE.toULong())
                require(alignment in 1L..Int.MAX_VALUE.toLong())

                val digestBytes = ByteArray(32)
                raf.readFully(digestBytes)

                descriptors += AmiSectionDescriptor(
                    type = AmiSectionType.values()[typeOrdinal.toInt()],
                    offset = offset.toLong(),
                    length = length.toLong(),
                    alignmentBytes = alignment.toInt(),
                    sha256 = digestBytes.toHex(),
                    profileId = profileId.toInt()
                )
            }
        }

        descriptors.forEach { descriptor ->
            require(descriptor.offset >= AmiBinaryLayout.HEADER_REGION_BYTES.toLong()) {
                "AMI section overlaps reserved header region: " + descriptor.type
            }
            require(descriptor.endExclusive <= file.length()) {
                "AMI section exceeds file length: " + descriptor.type
            }
        }

        require(descriptors.none { it.type == AmiSectionType.EXECUTION_PROFILE }) {
            "AMI execution-profile metadata decoding is not available until the profile compiler phase"
        }

        val manifestDescriptor = descriptors.singleOrNull {
            it.type == AmiSectionType.MANIFEST
        } ?: error("AMI manifest section is missing")
        require(manifestDescriptor.length in 1L..MAX_MANIFEST_BYTES.toLong()) {
            "AMI manifest exceeds reader limit"
        }
        val manifestText = readRange(
            file,
            manifestDescriptor.offset,
            manifestDescriptor.length.toInt()
        ).toString(Charsets.UTF_8)
        val manifest = parseManifest(manifestText)

        return ParsedHeader(
            AmiContainerIndex(
                manifest = manifest,
                sections = descriptors
            )
        )
    }

    private fun parseManifest(text: String): AmiManifest {
        val fields = linkedMapOf<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "malformed AMI manifest line" }
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                require(fields.put(key, value) == null) {
                    "duplicate AMI manifest key: $key"
                }
            }

        require(fields["format"] == "AMI") { "AMI manifest format mismatch" }
        require(fields["version"] == "1.0") { "AMI manifest version mismatch" }

        val sourceFormat = AmiSourceFormat.valueOf(
            requireNotNull(fields["source_format"]) { "AMI source format missing" }
        )
        val sourceSha = requireNotNull(fields["source_sha256"]) {
            "AMI source digest missing"
        }
        val sourceLength = requireNotNull(fields["source_byte_length"]) {
            "AMI source length missing"
        }.toLong()
        val precisionPreserved = when (
            requireNotNull(fields["source_precision_preserved"]) {
                "AMI source precision flag missing"
            }
        ) {
            "true" -> true
            "false" -> false
            else -> error("AMI source precision flag is invalid")
        }
        val precision = AmiPrecisionPolicy.valueOf(
            requireNotNull(fields["canonical_precision"]) {
                "AMI canonical precision missing"
            }
        )
        val tiers = requireNotNull(fields["context_tiers"]) {
            "AMI context tiers missing"
        }.split(',')
            .map { it.toInt() }
            .toSortedSet()

        return AmiManifest(
            architecture = AmiArchitectureId(
                requireNotNull(fields["architecture"]) { "AMI architecture missing" }
            ),
            tensorCount = requireNotNull(fields["tensor_count"]) {
                "AMI tensor count missing"
            }.toInt(),
            vocabularySize = requireNotNull(fields["vocabulary_size"]) {
                "AMI vocabulary size missing"
            }.toInt(),
            source = AmiSourceLineage(
                sourceFormat = sourceFormat,
                sourceSha256 = sourceSha,
                sourceByteLength = sourceLength,
                sourcePrecisionPreserved = precisionPreserved
            ),
            canonicalPrecisionPolicy = precision,
            contextTiers = tiers
        )
    }

    private fun readRange(file: File, offset: Long, length: Int): ByteArray {
        require(length >= 0)
        val bytes = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        return bytes
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
                if (read < 0) throw EOFException("AMI section is truncated during verification")
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

    private fun readU16(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        if (b0 < 0 || b1 < 0) throw EOFException("AMI header is truncated")
        return b0 or (b1 shl 8)
    }

    private fun readU32(raf: RandomAccessFile): Long {
        var value = 0L
        repeat(4) { index ->
            val byte = raf.read()
            if (byte < 0) throw EOFException("AMI header is truncated")
            value = value or ((byte.toLong() and 0xffL) shl (8 * index))
        }
        return value
    }

    private fun readU64(raf: RandomAccessFile): ULong {
        var value = 0UL
        repeat(8) { index ->
            val byte = raf.read()
            if (byte < 0) throw EOFException("AMI header is truncated")
            value = value or ((byte.toULong() and 0xffUL) shl (8 * index))
        }
        return value
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}

/**
 * Windowed read-only mmap access for AMI tensor sections.
 *
 * Large mobile models are not mapped as one Java buffer. Callers map bounded windows so files over
 * 2 GiB remain representable and the runtime can keep resident virtual-memory pressure under control.
 */
class AmiMappedSectionAccess(
    private val file: File,
    private val descriptor: AmiSectionDescriptor,
    private val maxWindowBytes: Int = 256 * 1024 * 1024
) {
    init {
        require(file.exists() && file.isFile)
        require(maxWindowBytes > 0)
    }

    fun mapWindow(
        relativeOffset: Long,
        length: Int
    ): MappedByteBuffer {
        require(relativeOffset >= 0L)
        require(length in 1..maxWindowBytes)
        val end = Math.addExact(relativeOffset, length.toLong())
        require(end <= descriptor.length) {
            "AMI mmap window exceeds section: " + descriptor.type
        }
        FileInputStream(file).channel.use { channel ->
            return channel.map(
                FileChannel.MapMode.READ_ONLY,
                Math.addExact(descriptor.offset, relativeOffset),
                length.toLong()
            )
        }
    }
}
