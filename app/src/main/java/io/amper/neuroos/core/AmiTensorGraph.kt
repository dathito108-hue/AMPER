package io.amper.neuroos.core

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

data class AmiTensorDescriptor(
    val name: String,
    val dimensions: List<ULong>,
    val sourceEncodingType: Long,
    val foundationOffset: Long,
    val storageBytes: Long?
) {
    init {
        require(name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= 64)
        require(dimensions.isNotEmpty() && dimensions.size <= 8)
        require(dimensions.all { it > 0UL })
        require(sourceEncodingType >= 0L)
        require(foundationOffset >= 0L)
        storageBytes?.let { require(it > 0L) }
    }

    val elementCount: ULong
        get() {
            var count = 1UL
            dimensions.forEach { dimension ->
                require(count <= ULong.MAX_VALUE / dimension) {
                    "AMI tensor element count overflow: " + name
                }
                count *= dimension
            }
            return count
        }
}

data class AmiTensorGraph(
    val architecture: AmiArchitectureId,
    val tensors: List<AmiTensorDescriptor>,
    val foundationSection: AmiSectionDescriptor
) {
    init {
        require(tensors.isNotEmpty())
        require(tensors.distinctBy { it.name }.size == tensors.size) {
            "AMI tensor names must be unique"
        }
    }

    fun tensor(name: String): AmiTensorDescriptor? =
        tensors.firstOrNull { it.name == name }
}

/**
 * Decodes the AMI-native tensor index produced by Phase599 and binds every tensor to the verified
 * FOUNDATION_WEIGHTS section.
 *
 * Known tensor footprints are checked for overlap/range before a kernel can request an mmap window.
 * Unknown source encodings remain visible to architecture admission but cannot receive a fabricated
 * byte footprint.
 */
class AmiTensorGraphReader(
    private val maxTensorDescriptors: Int = 1_000_000
) {
    init {
        require(maxTensorDescriptors > 0)
    }

    fun read(loaded: AmiLoadedArtifact): Result<AmiTensorGraph> = runCatching {
        val indexSection = loaded.index.sections.single {
            it.type == AmiSectionType.TENSOR_INDEX
        }
        val foundation = loaded.index.sections.single {
            it.type == AmiSectionType.FOUNDATION_WEIGHTS
        }
        require(indexSection.length <= Int.MAX_VALUE.toLong()) {
            "AMI tensor index is too large for bounded JVM decoding"
        }

        val tensors = RandomAccessFile(loaded.file, "r").use { raf ->
            raf.seek(indexSection.offset)
            val end = indexSection.endExclusive
            val countLong = readU32(raf, end)
            require(countLong <= maxTensorDescriptors.toLong()) {
                "AMI tensor count exceeds mobile graph limit: " + countLong
            }
            require(countLong == loaded.index.manifest.tensorCount.toLong()) {
                "AMI tensor index count does not match manifest"
            }

            val out = ArrayList<AmiTensorDescriptor>(countLong.toInt())
            repeat(countLong.toInt()) {
                val nameLength = readU16(raf, end)
                require(nameLength in 1..64)
                val nameBytes = readBytes(raf, end, nameLength)
                val name = nameBytes.toString(Charsets.UTF_8)
                require(name.toByteArray(Charsets.UTF_8).contentEquals(nameBytes)) {
                    "AMI tensor name is not canonical UTF-8"
                }

                val dimensionCount = readU8(raf, end)
                require(dimensionCount in 1..8)
                require(readU8(raf, end) == 0) {
                    "AMI tensor index reserved byte must be zero"
                }
                require(readU16(raf, end) == 0) {
                    "AMI tensor index reserved word must be zero"
                }

                val dimensions = ArrayList<ULong>(dimensionCount)
                repeat(dimensionCount) {
                    val dimension = readU64(raf, end)
                    require(dimension > 0UL)
                    dimensions += dimension
                }

                val sourceType = readU32(raf, end)
                val relativeOffset = readU64(raf, end)
                val encodedStorage = readU64(raf, end)
                require(relativeOffset <= Long.MAX_VALUE.toULong())
                require(encodedStorage <= Long.MAX_VALUE.toULong())

                out += AmiTensorDescriptor(
                    name = name,
                    dimensions = dimensions,
                    sourceEncodingType = sourceType,
                    foundationOffset = relativeOffset.toLong(),
                    storageBytes = encodedStorage.toLong().takeIf { it > 0L }
                )
            }

            require(raf.filePointer == end) {
                "AMI tensor index has trailing or truncated bytes"
            }
            out
        }

        validateFoundationRanges(tensors, foundation)
        AmiTensorGraph(
            architecture = loaded.index.manifest.architecture,
            tensors = tensors,
            foundationSection = foundation
        )
    }

    private fun validateFoundationRanges(
        tensors: List<AmiTensorDescriptor>,
        foundation: AmiSectionDescriptor
    ) {
        tensors.forEach { tensor ->
            require(tensor.foundationOffset < foundation.length) {
                "AMI tensor offset exceeds foundation section: " + tensor.name
            }
            tensor.storageBytes?.let { bytes ->
                val end = Math.addExact(tensor.foundationOffset, bytes)
                require(end <= foundation.length) {
                    "AMI tensor footprint exceeds foundation section: " + tensor.name
                }
            }
        }

        val known = tensors
            .filter { it.storageBytes != null }
            .sortedBy { it.foundationOffset }
        known.zipWithNext().forEach { pair ->
            val left = pair.first
            val right = pair.second
            val leftEnd = Math.addExact(
                left.foundationOffset,
                requireNotNull(left.storageBytes)
            )
            require(leftEnd <= right.foundationOffset) {
                "AMI tensor footprints overlap: " + left.name + " and " + right.name
            }
        }
    }

    private fun readBytes(
        raf: RandomAccessFile,
        end: Long,
        length: Int
    ): ByteArray {
        require(length >= 0)
        require(Math.addExact(raf.filePointer, length.toLong()) <= end) {
            "AMI tensor index is truncated"
        }
        return ByteArray(length).also(raf::readFully)
    }

    private fun readU8(raf: RandomAccessFile, end: Long): Int {
        require(raf.filePointer < end) { "AMI tensor index is truncated" }
        val value = raf.read()
        if (value < 0) throw EOFException("AMI tensor index is truncated")
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
            value = value or ((readU8(raf, end).toLong() and 0xffL) shl (8 * index))
        }
        return value
    }

    private fun readU64(raf: RandomAccessFile, end: Long): ULong {
        var value = 0UL
        repeat(8) { index ->
            value = value or ((readU8(raf, end).toULong() and 0xffUL) shl (8 * index))
        }
        return value
    }
}
