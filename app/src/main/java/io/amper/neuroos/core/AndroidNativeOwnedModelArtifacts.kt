package io.amper.neuroos.core

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Descriptor-bound source for AMPER-owned GGUF artifacts stored inside the app-private sovereign
 * directory. Native backends receive /proc/self/fd/<n> while the ParcelFileDescriptor remains open.
 */
class AndroidAppPrivateModelArtifactSource(
    private val rootDir: File,
    private val fileName: String
) : DescriptorBoundNativeModelPathSource {
    private val file: File

    init {
        require(fileName.matches(FILE_NAME)) {
            "invalid AMPER-native model artifact file name"
        }
        SovereignPathIdentity.requireDirectory(rootDir, allowMissingLeaf = false)
        file = File(rootDir, fileName)
        SovereignPathIdentity.requireManagedFile(file, allowMissingLeaf = false)
    }

    override val locator: String =
        AndroidAppPrivateModelArtifactResolver.locatorFor(fileName)

    override val displayName: String = fileName

    override val lengthBytes: Long
        get() {
            SovereignPathIdentity.requireManagedFile(file, allowMissingLeaf = false)
            return file.length()
        }

    override fun openStream(): InputStream {
        SovereignPathIdentity.requireManagedFile(file, allowMissingLeaf = false)
        return FileInputStream(file)
    }

    override fun <T> withNativePath(block: (String) -> T): T {
        SovereignPathIdentity.requireManagedFile(file, allowMissingLeaf = false)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor.use {
            return block("/proc/self/fd/" + it.fd)
        }
    }

    companion object {
        internal val FILE_NAME = Regex("[A-Za-z0-9._-]{1,160}")
    }
}

/**
 * Resolves only AMPER-owned app-private artifact locators. It never accepts arbitrary filesystem
 * paths, path separators, symlinks, or files outside [rootDir].
 */
class AndroidAppPrivateModelArtifactResolver(
    private val rootDir: File
) : ModelArtifactResolver, MultimodalProjectorArtifactResolver {
    override fun resolve(model: InstalledModel): ModelArtifactSource? =
        resolveLocator(model.locator)

    override fun resolve(projector: InstalledMultimodalProjector): ModelArtifactSource? =
        resolveLocator(projector.locator)

    private fun resolveLocator(locator: String): ModelArtifactSource? {
        if (!locator.startsWith(PREFIX)) return null
        val fileName = locator.removePrefix(PREFIX)
        if (!fileName.matches(AndroidAppPrivateModelArtifactSource.FILE_NAME)) return null

        return runCatching {
            SovereignPathIdentity.requireDirectory(rootDir, allowMissingLeaf = false)
            val file = File(rootDir, fileName)
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching null
            AndroidAppPrivateModelArtifactSource(rootDir, fileName)
        }.getOrNull()
    }

    companion object {
        private const val PREFIX = "amper-private://native-model/"

        fun locatorFor(fileName: String): String {
            require(fileName.matches(AndroidAppPrivateModelArtifactSource.FILE_NAME)) {
                "invalid AMPER-native model artifact file name"
            }
            return PREFIX + fileName
        }
    }
}