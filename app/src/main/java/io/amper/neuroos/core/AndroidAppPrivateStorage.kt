package io.amper.neuroos.core

import android.content.Context
import java.io.File

/**
 * Canonical Android app-private storage boundary.
 *
 * Some Android builds expose [Context.getFilesDir] through a platform-owned symbolic-link
 * prefix (for example /data/user/0). SovereignPathIdentity deliberately rejects symlinks in
 * AMPER-managed paths, so the trusted OS-provided app root must be canonicalized before AMPER
 * derives any sovereign descendants from it.
 *
 * Only the Android-owned root is resolved here. Symlink rejection inside the resulting
 * amper-sovereign namespace remains unchanged and fail-closed.
 */
internal object AndroidAppPrivateStorage {
    fun canonicalFilesDir(context: Context): File =
        canonicalFilesDir(context.filesDir)

    internal fun canonicalFilesDir(filesDir: File): File {
        val canonical = filesDir.canonicalFile
        require(canonical.exists()) {
            "Android app-private files directory does not exist: $canonical"
        }
        require(canonical.isDirectory) {
            "Android app-private files path is not a directory: $canonical"
        }
        return canonical
    }

    fun sovereignDir(context: Context): File =
        File(canonicalFilesDir(context), "amper-sovereign")
}
