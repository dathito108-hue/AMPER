package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppPrivateStorageTest {
    @Test
    fun canonicalFilesDirResolvesTrustedPlatformAliasBeforeSovereignPaths() {
        val root = createTempDir(prefix = "amper-android-storage-")
        try {
            val physical = File(root, "physical-files").apply {
                assertTrue(mkdirs())
            }
            val alias = File(root, "platform-alias")
            Files.createSymbolicLink(alias.toPath(), physical.toPath())

            assertTrue(Files.isSymbolicLink(alias.toPath()))

            val canonical = AndroidAppPrivateStorage.canonicalFilesDir(alias)

            assertEquals(physical.canonicalFile, canonical)
            assertFalse(Files.isSymbolicLink(canonical.toPath()))

            val sovereign = File(canonical, "amper-sovereign")
            assertTrue(sovereign.toPath().startsWith(physical.canonicalFile.toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun canonicalFilesDirRejectsMissingRoot() {
        val root = createTempDir(prefix = "amper-android-storage-missing-")
        val missing = File(root, "missing")
        try {
            val failure = runCatching {
                AndroidAppPrivateStorage.canonicalFilesDir(missing)
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
        } finally {
            root.deleteRecursively()
        }
    }
}
