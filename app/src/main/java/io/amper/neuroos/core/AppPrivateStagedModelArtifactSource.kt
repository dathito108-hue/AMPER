package io.amper.neuroos.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Content-addressed app-private staging for providers whose content:// bytes cannot be reopened by
 * native code through /proc/self/fd on newer Android/SELinux builds.
 *
 * The original locator remains authoritative. Staging merely creates a native-readable cache of
 * the admitted bytes. [ModelArtifactIdentityVerifier] still validates the staged file against the
 * durable installed SHA-256/GGUF identity before a backend may load it.
 */
class AppPrivateStagedModelArtifactSource(
    private val source: ModelArtifactSource,
    private val stagingRoot: File,
    private val contentSha256: String,
    private val expectedLengthBytes: Long? = source.lengthBytes
) : AppPrivateNativeModelPathSource {
    init {
        require(contentSha256.matches(Regex("[0-9a-f]{64}"))) {
            "invalid staged model SHA-256"
        }
        require(expectedLengthBytes == null || expectedLengthBytes >= 0L) {
            "invalid staged model length"
        }
    }

    override val locator: String get() = source.locator
    override val displayName: String get() = source.displayName
    override val lengthBytes: Long? get() = source.lengthBytes
    override fun openStream() = source.openStream()

    @Synchronized
    override fun <T> withNativePath(block: (String) -> T): T {
        val staged = ensureStaged()
        val before = SovereignPathIdentity.snapshot(staged)
        val result = block(staged.toPath().toAbsolutePath().normalize().toString())
        SovereignPathIdentity.requireSameIdentity(before, staged)
        return result
    }

    private fun ensureStaged(): File {
        DurableJournalIo.ensureDirectoryExistsDurably(stagingRoot)
        SovereignPathIdentity.requireDirectory(stagingRoot, allowMissingLeaf = false)

        val target = File(stagingRoot, "sha256-$contentSha256.gguf")
        if (Files.exists(target.toPath())) {
            SovereignPathIdentity.requireManagedFile(target, allowMissingLeaf = false)
            if (expectedLengthBytes == null || target.length() == expectedLengthBytes) {
                return target
            }
            require(target.delete()) {
                "staged native model length changed and stale cache could not be removed"
            }
            DurableJournalIo.syncDirectory(stagingRoot)
        }

        SovereignPathIdentity.requireManagedFile(target)
        val temp = File(
            stagingRoot,
            ".${target.name}.staging-${UUID.randomUUID().toString().replace("-", "")}"
        )
        try {
            SovereignPathIdentity.requireManagedFile(temp)
            var copied = 0L
            source.openStream().use { input ->
                FileOutputStream(temp).use { output ->
                    copied = input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            SovereignPathIdentity.requireManagedFile(temp, allowMissingLeaf = false)
            expectedLengthBytes?.let { expected ->
                require(copied == expected) {
                    "staged native model length mismatch: expected $expected, copied $copied"
                }
            }
            moveIntoPlace(temp, target)
            SovereignPathIdentity.requireManagedFile(target, allowMissingLeaf = false)
            expectedLengthBytes?.let { expected ->
                require(target.length() == expected) {
                    "staged native model final length mismatch"
                }
            }
            target.setReadOnly()
            DurableJournalIo.syncDirectory(stagingRoot)
            return target
        } finally {
            if (temp.exists()) {
                temp.delete()
                runCatching { DurableJournalIo.syncDirectory(stagingRoot) }
            }
        }
    }

    private fun moveIntoPlace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}
