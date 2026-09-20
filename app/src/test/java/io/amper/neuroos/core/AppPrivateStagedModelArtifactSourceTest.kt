package io.amper.neuroos.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPrivateStagedModelArtifactSourceTest {
    @Test
    fun stagesContentIntoRealAppPrivatePathInsteadOfProcSelfFd() {
        val bytes = GgufTestFixtures.validArtifact(payload = "phase592-native-stage".toByteArray())
        val root = Files.createTempDirectory("amper-stage-root").toFile()
        val staging = File(root, "native-cache")
        val upstream = CountingSource(bytes)
        val staged = AppPrivateStagedModelArtifactSource(
            source = upstream,
            stagingRoot = staging,
            contentSha256 = sha256(bytes),
            expectedLengthBytes = bytes.size.toLong()
        )

        try {
            var firstPath = ""
            staged.withNativePath { path ->
                firstPath = path
                assertFalse(path.startsWith("/proc/self/fd/"))
                val file = File(path)
                assertTrue(file.isFile)
                assertArrayEquals(bytes, file.readBytes())
            }

            var secondPath = ""
            staged.withNativePath { path ->
                secondPath = path
                assertArrayEquals(bytes, File(path).readBytes())
            }

            assertEquals(firstPath, secondPath)
            assertEquals(1, upstream.opens)
            assertTrue(staged is AppPrivateNativeModelPathSource)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleLengthCacheIsRestagedFromAuthoritativeSource() {
        val bytes = GgufTestFixtures.validArtifact(payload = "restage".toByteArray())
        val root = Files.createTempDirectory("amper-stage-stale").toFile()
        val staging = File(root, "native-cache")
        val digest = sha256(bytes)
        try {
            DurableJournalIo.ensureDirectoryExistsDurably(staging)
            File(staging, "sha256-$digest.gguf").writeBytes(byteArrayOf(1, 2, 3))

            val upstream = CountingSource(bytes)
            val staged = AppPrivateStagedModelArtifactSource(
                source = upstream,
                stagingRoot = staging,
                contentSha256 = digest,
                expectedLengthBytes = bytes.size.toLong()
            )

            staged.withNativePath { path ->
                assertArrayEquals(bytes, File(path).readBytes())
            }
            assertEquals(1, upstream.opens)
        } finally {
            root.deleteRecursively()
        }
    }

    private class CountingSource(
        private val bytes: ByteArray
    ) : ModelArtifactSource {
        override val locator: String = "content://phase592/model.gguf"
        override val displayName: String = "model.gguf"
        override val lengthBytes: Long = bytes.size.toLong()
        var opens: Int = 0
            private set

        override fun openStream(): InputStream {
            opens += 1
            return ByteArrayInputStream(bytes)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
