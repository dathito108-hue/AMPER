package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiHardwareSnapshot
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Amne2ExecutionViewIdentity(
    val foundationId: String,
    val semanticSha256: String,
    val artifactSha256: String
)

class Amne2ExecutionView internal constructor(
    val file: File,
    val loaded: Ami2LoadedBinaryArtifact,
    val admitted: Amne2AdmittedFoundation,
    private val maxWindowBytes: Int
) {
    init {
        require(maxWindowBytes > 0)
        require(file.canonicalFile == loaded.file.canonicalFile) {
            "AMNE2 execution view file differs from verified AMI2 artifact"
        }
        require(admitted.foundationId == loaded.bundle.foundation.foundationId)
        require(admitted.semanticSha256 == loaded.bundle.foundation.semanticSha256)
        require(admitted.artifactSha256 == loaded.fileSha256)
    }

    val identity: Amne2ExecutionViewIdentity = Amne2ExecutionViewIdentity(
        foundationId = admitted.foundationId,
        semanticSha256 = admitted.semanticSha256,
        artifactSha256 = admitted.artifactSha256
    )

    fun descriptor(role: Ami2ArtifactRole): Ami2BinarySectionDescriptor =
        loaded.sections.single { it.role == role }

    /**
     * Maps a bounded read-only window from one verified AMI2 section.
     *
     * Offsets remain Long so models larger than 2 GiB are representable. Individual Java mapped
     * windows remain Int-sized and bounded to avoid whole-model virtual-memory pressure.
     */
    fun mapWindow(
        role: Ami2ArtifactRole,
        relativeOffset: Long,
        length: Int
    ): MappedByteBuffer {
        require(relativeOffset >= 0L)
        require(length in 1..maxWindowBytes) {
            "AMNE2 mmap window exceeds configured bound"
        }

        val section = descriptor(role)
        val relativeEnd = Math.addExact(relativeOffset, length.toLong())
        require(relativeEnd <= section.length) {
            "AMNE2 mmap window escapes verified AMI2 section: $role"
        }

        val absoluteOffset = Math.addExact(section.offset, relativeOffset)
        FileInputStream(file).channel.use { channel ->
            return channel.map(
                FileChannel.MapMode.READ_ONLY,
                absoluteOffset,
                length.toLong()
            )
        }
    }

    /**
     * Bounded heap read for small control/index slices. Large foundation weights should use mmap.
     */
    fun readBytes(
        role: Ami2ArtifactRole,
        relativeOffset: Long = 0L,
        length: Int
    ): ByteArray {
        require(relativeOffset >= 0L)
        require(length in 1..maxWindowBytes) {
            "AMNE2 read window exceeds configured bound"
        }

        val section = descriptor(role)
        val relativeEnd = Math.addExact(relativeOffset, length.toLong())
        require(relativeEnd <= section.length) {
            "AMNE2 read window escapes verified AMI2 section: $role"
        }

        return ByteArray(length).also { bytes ->
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(Math.addExact(section.offset, relativeOffset))
                raf.readFully(bytes)
            }
        }
    }
}

/**
 * Opens one verified AMI2 artifact as the bounded section view consumed by AMNE2 bring-up.
 *
 * Verification happens once here. The resulting loaded artifact is passed to the Phase630
 * admission contract without re-hashing the entire file a second time.
 */
class Amne2ExecutionViewFactory(
    private val reader: Ami2CanonicalBinaryReader = Ami2CanonicalBinaryReader(),
    private val admission: Amne2ExecutionAdmission = Amne2ExecutionAdmission(),
    private val maxWindowBytes: Int = DEFAULT_MAX_WINDOW_BYTES
) {
    init {
        require(maxWindowBytes > 0)
    }

    fun open(
        artifactFile: File,
        hardware: AmiHardwareSnapshot
    ): Result<Amne2ExecutionView> = runCatching {
        val loaded = reader.read(
            artifactFile,
            verifySectionDigests = true
        ).getOrThrow()
        val admitted = admission
            .admitVerified(loaded, hardware)
            .getOrThrow()

        Amne2ExecutionView(
            file = artifactFile,
            loaded = loaded,
            admitted = admitted,
            maxWindowBytes = maxWindowBytes
        )
    }

    companion object {
        const val DEFAULT_MAX_WINDOW_BYTES: Int = 8 * 1024 * 1024
    }
}
