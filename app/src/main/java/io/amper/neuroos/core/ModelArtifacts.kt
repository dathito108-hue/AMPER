package io.amper.neuroos.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Stable abstraction over user-owned model bytes. A source can be a normal file,
 * Android content URI adapter, removable storage, or another future provider.
 * The sovereign kernel never owns model identity or assumes a concrete storage API.
 */
interface ModelArtifactSource {
    val locator: String
    val displayName: String
    val lengthBytes: Long?
    fun openStream(): InputStream
}

/**
 * Optional bridge for native runtimes that require a POSIX path. Implementations
 * keep any resource needed for the path alive for the whole block.
 *
 * This base interface does not promise that the path is descriptor-bound. A plain
 * filesystem path can still be substituted between verification and native open.
 */
interface NativeModelPathSource : ModelArtifactSource {
    fun <T> withNativePath(block: (String) -> T): T
}

/**
 * Stronger native-path contract: the path passed to [withNativePath] names an already-open
 * descriptor whose lifetime covers the complete block. Native backends that require the
 * verified bytes to remain pinned through model load should require this marker rather than
 * accepting an arbitrary [NativeModelPathSource].
 */
interface DescriptorBoundNativeModelPathSource : NativeModelPathSource

/**
 * Native path backed by an AMPER-controlled app-private file rather than /proc/self/fd.
 *
 * Android 16/SELinux may deny reopening /proc/self/fd/<n> by pathname even when the app owns the
 * descriptor. This contract permits a real app-private pathname only when the source guarantees
 * that the file is inside a validated private directory and keeps its filesystem identity stable
 * for the complete callback. Native backends must still re-verify the staged bytes against the
 * installed SHA-256/GGUF identity immediately before loading.
 */
interface AppPrivateNativeModelPathSource : NativeModelPathSource

internal fun ModelArtifactSource.requireTrustedNativePathSource(
    consumer: String
): NativeModelPathSource {
    require(
        this is DescriptorBoundNativeModelPathSource ||
            this is AppPrivateNativeModelPathSource
    ) {
        "$consumer requires a descriptor-bound or verified app-private model path"
    }
    return this as NativeModelPathSource
}

/**
 * Generic file source used by JVM tooling/tests and non-native inspection. Its native path is
 * a normal filesystem pathname, so it intentionally does NOT implement
 * [DescriptorBoundNativeModelPathSource]. Android production import uses a descriptor-bound
 * content-URI source instead.
 */
class FileModelArtifactSource(private val file: File) : NativeModelPathSource {
    override val locator: String = file.absolutePath
    override val displayName: String = file.name
    override val lengthBytes: Long? get() = file.takeIf { it.exists() }?.length()
    override fun openStream(): InputStream = FileInputStream(file)
    override fun <T> withNativePath(block: (String) -> T): T = block(file.absolutePath)
}

class ByteArrayModelArtifactSource(
    private val bytes: ByteArray,
    override val displayName: String = "memory.gguf",
    override val locator: String = "memory://$displayName"
) : ModelArtifactSource {
    override val lengthBytes: Long = bytes.size.toLong()
    override fun openStream(): InputStream = ByteArrayInputStream(bytes)
}
