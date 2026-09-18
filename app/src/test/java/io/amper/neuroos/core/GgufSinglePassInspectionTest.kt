package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class GgufSinglePassInspectionTest {
    @Test
    fun inspectionBindsHeaderDigestAndLengthToOneStream() {
        val first = GgufTestFixtures.validArtifact(payload = "first-artifact".toByteArray())
        val second = GgufTestFixtures.validArtifact(payload = "substituted-artifact".toByteArray())
        val source = SwitchingSource(
            streams = listOf(first, second),
            declaredLength = null
        )

        val inspection = GgufInspector().inspect(source).getOrThrow()

        assertEquals(1, source.openCount)
        assertEquals(first.size.toLong(), inspection.lengthBytes)
        assertEquals(sha256(first), inspection.sha256)
        assertEquals(3L, inspection.header.version)
        assertEquals(7UL, inspection.header.tensorCount)
        assertEquals(11UL, inspection.header.metadataKeyValueCount)
    }

    @Test
    fun declaredLengthMustMatchObservedSnapshotLength() {
        val bytes = GgufTestFixtures.validArtifact(payload = ByteArray(32) { it.toByte() })
        val source = SwitchingSource(
            streams = listOf(bytes),
            declaredLength = bytes.size.toLong() + 1L
        )

        val result = GgufInspector().inspect(source)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("length changed during admission") == true)
        assertEquals(1, source.openCount)
    }

    @Test
    fun inspectionMakesForwardProgressWhenBulkReadTemporarilyReturnsZero() {
        val bytes = GgufTestFixtures.validArtifact(payload = ByteArray(64) { (it * 3).toByte() })
        val source = object : ModelArtifactSource {
            override val locator: String = "test://zero-read.gguf"
            override val displayName: String = "zero-read.gguf"
            override val lengthBytes: Long? = bytes.size.toLong()
            override fun openStream(): InputStream = ZeroOnceInputStream(bytes)
        }

        val inspection = GgufInspector().inspect(source).getOrThrow()

        assertEquals(bytes.size.toLong(), inspection.lengthBytes)
        assertEquals(sha256(bytes), inspection.sha256)
    }

    @Test
    fun oversizedDeclaredArtifactIsRejectedBeforeOpeningProvider() {
        val bytes = GgufTestFixtures.validArtifact(tensorCount = 0UL, metadataCount = 0UL)
        val source = SwitchingSource(
            streams = listOf(bytes),
            declaredLength = 65L
        )
        val inspector = GgufInspector(
            GgufAdmissionPolicy(maxArtifactBytes = 64L)
        )

        val result = inspector.inspect(source)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("admission size limit") == true)
        assertEquals(0, source.openCount)
    }

    @Test
    fun providerWithoutDeclaredLengthIsBoundedWhileHashing() {
        val bytes = GgufTestFixtures.validArtifact(payload = ByteArray(80) { it.toByte() })
        val source = SwitchingSource(
            streams = listOf(bytes),
            declaredLength = null
        )
        val inspector = GgufInspector(
            GgufAdmissionPolicy(maxArtifactBytes = 64L)
        )

        val result = inspector.inspect(source)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("while reading") == true)
        assertEquals(1, source.openCount)
    }

    @Test
    fun hostileTensorClaimFailsImmediatelyAfterHeader() {
        val bytes = GgufTestFixtures.header(
            tensorCount = 101UL,
            metadataCount = 1UL
        ).toByteArray()
        val source = SwitchingSource(
            streams = listOf(bytes),
            declaredLength = null
        )
        val inspector = GgufInspector(
            GgufAdmissionPolicy(
                maxArtifactBytes = 1_024L,
                maxTensorCount = 100UL,
                maxMetadataKeyValueCount = 100UL
            )
        )

        val result = inspector.inspect(source)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("tensor count") == true)
        assertEquals(1, source.openCount)
    }

    @Test
    fun hostileMetadataClaimFailsAdmission() {
        val bytes = GgufTestFixtures.header(
            tensorCount = 1UL,
            metadataCount = 101UL
        ).toByteArray()
        val inspector = GgufInspector(
            GgufAdmissionPolicy(
                maxArtifactBytes = 1_024L,
                maxTensorCount = 100UL,
                maxMetadataKeyValueCount = 100UL
            )
        )

        val result = inspector.inspect(ByteArrayModelArtifactSource(bytes))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("metadata count") == true)
    }

    @Test
    fun explicitWiderPolicyAllowsArtifactAboveSmallerDeploymentLimit() {
        val bytes = GgufTestFixtures.validArtifact(
            tensorCount = 0UL,
            metadataCount = 0UL,
            payload = ByteArray(80) { (it * 5).toByte() }
        )
        val source = ByteArrayModelArtifactSource(bytes)
        val narrowLimit = bytes.size.toLong() - 1L
        val widerLimit = bytes.size.toLong()

        val narrow = GgufInspector(
            GgufAdmissionPolicy(maxArtifactBytes = narrowLimit)
        ).inspect(source)
        val wider = GgufInspector(
            GgufAdmissionPolicy(maxArtifactBytes = widerLimit)
        ).inspect(source)

        assertTrue(narrow.isFailure)
        assertTrue(wider.isSuccess)
        assertEquals(bytes.size.toLong(), wider.getOrThrow().lengthBytes)
    }

    private class SwitchingSource(
        private val streams: List<ByteArray>,
        private val declaredLength: Long?
    ) : ModelArtifactSource {
        var openCount: Int = 0
            private set

        override val locator: String = "test://switching.gguf"
        override val displayName: String = "switching.gguf"
        override val lengthBytes: Long? get() = declaredLength

        override fun openStream(): InputStream {
            val index = openCount.coerceAtMost(streams.lastIndex)
            openCount += 1
            return ByteArrayInputStream(streams[index])
        }
    }

    private class ZeroOnceInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var zeroReturned = false

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!zeroReturned) {
                zeroReturned = true
                return 0
            }
            return delegate.read(buffer, offset, length)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
