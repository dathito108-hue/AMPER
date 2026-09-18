package io.amper.neuroos.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files

class NativeModelPathLeaseContractTest {
    @Test
    fun ordinaryFilePathIsNotAdvertisedAsDescriptorBound() {
        val path = Files.createTempFile("amper-native-model", ".gguf")
        try {
            val source = FileModelArtifactSource(path.toFile())
            assertTrue(source is NativeModelPathSource)
            assertFalse(source is DescriptorBoundNativeModelPathSource)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun descriptorBoundContractKeepsLeaseAliveForWholeCallback() {
        val source = FakeDescriptorBoundSource()

        source.withNativePath { path ->
            assertTrue(path.startsWith("/proc/self/fd/"))
            assertTrue(source.leaseOpen)
        }

        assertFalse(source.leaseOpen)
    }

    private class FakeDescriptorBoundSource : DescriptorBoundNativeModelPathSource {
        override val locator: String = "content://models/test.gguf"
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long = 0L
        var leaseOpen: Boolean = false
            private set

        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun <T> withNativePath(block: (String) -> T): T {
            check(!leaseOpen)
            leaseOpen = true
            return try {
                block("/proc/self/fd/42")
            } finally {
                leaseOpen = false
            }
        }
    }
}
