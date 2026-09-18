package io.amper.neuroos.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.concurrent.withLock

/**
 * Descriptor-bound I/O for security-sensitive sovereign metadata and lock files.
 *
 * Existing leaves and their parent directory are snapshotted before opening. The leaf is
 * opened with NOFOLLOW_LINKS, identities are revalidated before any caller work begins,
 * and the same identities are checked again before returning. For newly-created files,
 * CREATE_NEW closes the missing-leaf race instead of silently opening a path that appeared
 * between validation and open.
 *
 * FileChannel keeps reads, writes, truncation, copies, digests and locking attached to the
 * opened file object. Phase 54 extends the same policy to deletion and directory fsync:
 * deletion validates the exact expected leaf identity immediately before unlink and keeps
 * the parent identity pinned across the metadata mutation, while directory sync never
 * follows a substituted symlink directory.
 *
 * Phase 58 turns complete-line reads into a descriptor snapshot lease. Recovery runs first;
 * then the exact target is exclusively descriptor-locked while bytes are read and any torn
 * suffix is truncated through that same channel. Append, replacement copy and atomic rotation
 * use the same target lease domain, preventing repair from racing a cooperating mutation.
 *
 * Phase 59 extends that target lease to generic create/replace/force operations. Phase 60
 * completes the symmetric read side: metadata text reads, single-line reads and SHA-256
 * digests take the same JVM target lease plus a shared descriptor lock, yielding one stable
 * inode snapshot while cooperating exclusive writers wait. Phase 61 brings identity-bound
 * unlink into that same lease domain so deletion cannot cross an active reader or writer.
 */
internal object DescriptorBoundFileIo {
    private const val BUFFER_SIZE = 8192
    private val NEWLINE: Byte = '\n'.code.toByte()

    internal data class BoundUtf8Text(
        val text: String,
        val identity: SovereignPathIdentity.Snapshot
    )

    private data class BoundRead(
        val bytes: ByteArray,
        val fileIdentity: SovereignPathIdentity.Snapshot
    )

    fun readSingleCompleteUtf8Line(file: File, label: String): String {
        val bytes = readBound(file).bytes
        require(bytes.isNotEmpty()) { "$label is empty" }
        require(bytes.last() == NEWLINE) { "$label is torn" }
        val content = String(bytes, StandardCharsets.UTF_8)
        val lines = content.split('\n').dropLast(1)
        require(lines.size == 1 && lines.single().isNotBlank()) { "invalid $label file" }
        return lines.single()
    }

    fun readUtf8Text(file: File): String = readUtf8TextBound(file).text

    fun readUtf8TextBound(file: File): BoundUtf8Text {
        val read = readBound(file)
        return BoundUtf8Text(
            text = String(read.bytes, StandardCharsets.UTF_8),
            identity = read.fileIdentity
        )
    }

    /**
     * Returns one stable, newline-terminated snapshot. The same opened target descriptor is
     * held exclusively from the first byte read through a possible torn-tail truncate+force.
     *
     * Recovery is deliberately retried if an R3/R2/R1 marker appears in the small interval
     * between the initial recovery and acquisition of the target descriptor lease. Once the
     * target lease is held, cooperating append/copy/atomic-publication paths cannot mutate the
     * target until this snapshot has either completed or failed closed on identity change.
     */
    fun readCompleteUtf8LinesRecoveringTornTail(file: File): List<String> {
        val normalized = file.absoluteFile.normalize()
        while (true) {
            DurableJournalIo.recoverInterruptedReplace(normalized)
            val snapshot: List<String>? = TargetDescriptorLeaseRegistry.lockFor(normalized).withLock {
                val parent = normalized.parentFile
                    ?: error("descriptor-bound snapshot target has no parent directory")
                SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
                val parentIdentity = SovereignPathIdentity.snapshotDirectory(parent)
                val fileIdentity = SovereignPathIdentity.snapshot(normalized)

                FileChannel.open(
                    normalized.toPath(),
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                ).use { channel ->
                    channel.lock().use {
                        SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                        SovereignPathIdentity.requireSameIdentity(fileIdentity, normalized)

                        if (Files.exists(
                                DurableJournalIo.replacementMarkerFileFor(normalized).toPath(),
                                LinkOption.NOFOLLOW_LINKS
                            )
                        ) {
                            return@withLock null
                        }

                        channel.position(0L)
                        val bytes = readAll(channel)
                        if (bytes.isEmpty()) {
                            SovereignPathIdentity.requireSameIdentity(fileIdentity, normalized)
                            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                            return@withLock emptyList()
                        }

                        var usableBytes = bytes.size
                        if (bytes.last() != NEWLINE) {
                            var lastNewline = -1
                            for (index in bytes.indices.reversed()) {
                                if (bytes[index] == NEWLINE) {
                                    lastNewline = index
                                    break
                                }
                            }
                            usableBytes = lastNewline + 1
                            channel.truncate(usableBytes.toLong())
                            channel.force(true)
                        }

                        SovereignPathIdentity.requireSameIdentity(fileIdentity, normalized)
                        SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                        if (usableBytes == 0) return@withLock emptyList()

                        val content = String(bytes, 0, usableBytes, StandardCharsets.UTF_8)
                        require(content.endsWith('\n')) {
                            "recovered descriptor-bound prefix is not line terminated"
                        }
                        content.split('\n').dropLast(1)
                    }
                }
            }
            if (snapshot != null) return snapshot
        }
    }

    /**
     * Ensures a zero-length leaf exists without truncating an existing file. CREATE_NEW is
     * used for the absent case; an already-existing leaf must pass the sovereign regular-file
     * checks and is forced through its descriptor before returning.
     *
     * @return true only when this call created the directory entry.
     */
    fun ensureFileExists(file: File): Boolean {
        val normalized = file.absoluteFile.normalize()
        return TargetDescriptorLeaseRegistry.lockFor(normalized).withLock {
            val parent = normalized.parentFile ?: error("descriptor-bound file has no parent directory")
            SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
            val parentIdentity = SovereignPathIdentity.snapshotDirectory(parent)
            val path = normalized.toPath()

            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                val identity = SovereignPathIdentity.snapshot(normalized)
                FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    channel.lock().use {
                        SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                        SovereignPathIdentity.requireSameIdentity(identity, normalized)
                        channel.force(true)
                        SovereignPathIdentity.requireSameIdentity(identity, normalized)
                        SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                    }
                }
                return@withLock false
            }

            val created = try {
                FileChannel.open(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE_NEW,
                    LinkOption.NOFOLLOW_LINKS
                )
            } catch (failure: FileAlreadyExistsException) {
                throw IllegalStateException(
                    "descriptor-bound file appeared during CREATE_NEW: ${normalized.path}",
                    failure
                )
            }

            created.use { channel ->
                channel.lock().use {
                    SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                    val identity = SovereignPathIdentity.snapshot(normalized)
                    channel.force(true)
                    SovereignPathIdentity.requireSameIdentity(identity, normalized)
                    SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                }
            }
            syncDirectory(parent)
            true
        }
    }

    /** Truncates/replaces UTF-8 content while holding the shared exact-target lease. */
    fun writeUtf8Replacing(file: File, content: String) {
        val normalized = file.absoluteFile.normalize()
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        TargetDescriptorLeaseRegistry.lockFor(normalized).withLock {
            withWritableBoundChannel(normalized, allowCreate = true) { channel, created ->
                channel.lock().use {
                    channel.truncate(0L)
                    channel.position(0L)
                    writeFully(channel, ByteBuffer.wrap(bytes))
                    channel.force(true)
                    if (created) syncParentDirectoryBound(normalized)
                }
            }
        }
    }

    fun copyReplacing(source: File, target: File) {
        require(source.absoluteFile.normalize() != target.absoluteFile.normalize()) {
            "descriptor-bound copy source and target must differ"
        }
        val normalizedTarget = target.absoluteFile.normalize()
        TargetDescriptorLeaseRegistry.lockFor(normalizedTarget).withLock {
            withReadBoundChannel(source) { input ->
                withWritableBoundChannel(normalizedTarget, allowCreate = true) { output, created ->
                    output.lock().use {
                        output.truncate(0L)
                        output.position(0L)
                        input.position(0L)
                        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
                        while (true) {
                            buffer.clear()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            buffer.flip()
                            writeFully(output, buffer)
                        }
                        output.force(true)
                        if (created) syncParentDirectoryBound(normalizedTarget)
                    }
                }
            }
        }
    }

    fun force(file: File) {
        val normalized = file.absoluteFile.normalize()
        TargetDescriptorLeaseRegistry.lockFor(normalized).withLock {
            withWritableBoundChannel(normalized, allowCreate = false) { channel, _ ->
                channel.lock().use { channel.force(true) }
            }
        }
    }

    /** Stable SHA-256 of one exact inode while cooperating exclusive writers wait. */
    fun sha256(file: File): String = withReadSnapshotLease(file) { channel ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        channel.position(0L)
        while (true) {
            buffer.clear()
            val count = channel.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer.array(), 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Opens or creates a persistent lock leaf without ever following the leaf as a symlink. */
    fun <T> withExclusiveLock(file: File, block: () -> T): T =
        withWritableBoundChannel(file, allowCreate = true) { channel, created ->
            if (created) {
                channel.force(true)
                syncParentDirectoryBound(file)
            }
            channel.lock().use { block() }
        }

    /**
     * Deletes a managed regular-file entry only while it is still the expected file object.
     * Phase 61 takes the shared target JVM lease and an exclusive descriptor lock before the
     * final identity check and unlink, so a cooperating reader/writer cannot remain attached
     * to an inode while its managed pathname is removed.
     */
    fun deleteIfExistsBound(
        file: File,
        expectedIdentity: SovereignPathIdentity.Snapshot? = null
    ): Boolean {
        val normalized = file.absoluteFile.normalize()
        return TargetDescriptorLeaseRegistry.lockFor(normalized).withLock {
            val parent = normalized.parentFile ?: error("descriptor-bound file has no parent directory")
            SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
            val parentIdentity = SovereignPathIdentity.snapshotDirectory(parent)
            val path = normalized.toPath()

            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                return@withLock false
            }

            val identity = expectedIdentity ?: SovereignPathIdentity.snapshot(normalized)
            SovereignPathIdentity.requireSameIdentity(identity, normalized)
            FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            ).use { channel ->
                channel.lock().use {
                    SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                    SovereignPathIdentity.requireSameIdentity(identity, normalized)
                    Files.delete(path)
                    SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
                }
            }
            require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                "descriptor-bound deletion did not remove managed path: ${normalized.path}"
            }
            true
        }
    }

    /** Forces directory metadata without following a symlink or accepting directory rebind. */
    fun syncDirectory(directory: File) {
        SovereignPathIdentity.requireDirectory(directory, allowMissingLeaf = false)
        val identity = SovereignPathIdentity.snapshotDirectory(directory)
        FileChannel.open(
            directory.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        ).use { channel ->
            SovereignPathIdentity.requireSameDirectoryIdentity(identity, directory)
            channel.force(true)
            SovereignPathIdentity.requireSameDirectoryIdentity(identity, directory)
        }
    }

    private fun readBound(file: File): BoundRead {
        val normalized = file.absoluteFile.normalize()
        val identity = SovereignPathIdentity.snapshot(normalized)
        val bytes = withReadSnapshotLease(normalized, expectedIdentity = identity) { channel ->
            channel.position(0L)
            readAll(channel)
        }
        return BoundRead(bytes, identity)
    }

    private fun <T> withReadSnapshotLease(
        file: File,
        expectedIdentity: SovereignPathIdentity.Snapshot? = null,
        block: (FileChannel) -> T
    ): T {
        val normalized = file.absoluteFile.normalize()
        return TargetDescriptorLeaseRegistry.lockFor(normalized).withLock {
            withReadBoundChannel(normalized, expectedIdentity) { channel ->
                channel.lock(0L, Long.MAX_VALUE, true).use { block(channel) }
            }
        }
    }

    private fun <T> withReadBoundChannel(
        file: File,
        expectedIdentity: SovereignPathIdentity.Snapshot? = null,
        block: (FileChannel) -> T
    ): T {
        val parent = file.parentFile ?: error("descriptor-bound file has no parent directory")
        SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
        val parentIdentity = SovereignPathIdentity.snapshotDirectory(parent)
        val fileIdentity = expectedIdentity ?: SovereignPathIdentity.snapshot(file)

        return FileChannel.open(
            file.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        ).use { channel ->
            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            SovereignPathIdentity.requireSameIdentity(fileIdentity, file)
            val result = block(channel)
            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            SovereignPathIdentity.requireSameIdentity(fileIdentity, file)
            result
        }
    }

    private fun truncateExistingBound(
        file: File,
        expectedIdentity: SovereignPathIdentity.Snapshot,
        length: Long
    ) {
        require(length >= 0) { "negative descriptor-bound truncate length" }
        val parent = file.parentFile ?: error("descriptor-bound file has no parent directory")
        SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
        val parentIdentity = SovereignPathIdentity.snapshotDirectory(parent)
        SovereignPathIdentity.requireSameIdentity(expectedIdentity, file)

        FileChannel.open(
            file.toPath(),
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        ).use { channel ->
            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            SovereignPathIdentity.requireSameIdentity(expectedIdentity, file)
            channel.truncate(length)
            channel.force(true)
            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            SovereignPathIdentity.requireSameIdentity(expectedIdentity, file)
        }
    }

    private fun <T> withWritableBoundChannel(
        file: File,
        allowCreate: Boolean,
        block: (FileChannel, Boolean) -> T
    ): T {
        val parent = file.parentFile ?: error("descriptor-bound file has no parent directory")
        SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
        val parentIdentity = SovereignPathIdentity.snapshotDirectory(parent)
        val path = file.toPath()
        val existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        val initialIdentity = if (existed) {
            SovereignPathIdentity.snapshot(file)
        } else {
            require(allowCreate) { "descriptor-bound file is missing: ${file.path}" }
            null
        }

        val (channel, created) = if (initialIdentity != null) {
            FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            ) to false
        } else {
            val createdChannel = try {
                FileChannel.open(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE_NEW,
                    LinkOption.NOFOLLOW_LINKS
                )
            } catch (failure: FileAlreadyExistsException) {
                throw IllegalStateException(
                    "descriptor-bound file appeared during CREATE_NEW: ${file.path}",
                    failure
                )
            }
            createdChannel to true
        }

        return channel.use { opened ->
            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            if (initialIdentity != null) {
                SovereignPathIdentity.requireSameIdentity(initialIdentity, file)
            }
            val openedIdentity = SovereignPathIdentity.snapshot(file)
            val result = block(opened, created)
            SovereignPathIdentity.requireSameDirectoryIdentity(parentIdentity, parent)
            SovereignPathIdentity.requireSameIdentity(openedIdentity, file)
            result
        }
    }

    private fun readAll(channel: FileChannel): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        while (true) {
            buffer.clear()
            val count = channel.read(buffer)
            if (count < 0) break
            if (count > 0) output.write(buffer.array(), 0, count)
        }
        return output.toByteArray()
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun syncParentDirectoryBound(file: File) {
        val parent = file.parentFile ?: error("descriptor-bound file has no parent directory")
        syncDirectory(parent)
    }
}
