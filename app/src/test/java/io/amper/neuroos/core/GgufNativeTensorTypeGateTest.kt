package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufNativeTensorTypeGateTest {
    @Test
    fun genericAdmissionRemainsForwardCompatibleForUnprofiledType() {
        val bytes = singleTensorArtifact(
            tensorType = 777L,
            firstDimension = 1UL,
            dataBytes = 1
        )

        val result = GgufInspector(
            tensorLayoutProfile = GgufTensorLayoutProfiles.CLASSIC_GGML
        ).inspect(ByteArrayModelArtifactSource(bytes))

        assertEquals(
            GgufUnknownTensorTypePolicy.ALLOW_STRUCTURAL_ONLY,
            GgufTensorLayoutProfiles.CLASSIC_GGML.unknownTensorTypePolicy
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun amperAmiProfileRejectsUnprofiledTypeBeforeCoreAdmission() {
        val bytes = singleTensorArtifact(
            tensorType = 777L,
            firstDimension = 1UL,
            dataBytes = 1
        )
        val profile = GgufTensorLayoutProfiles.AMPER_AMI_V1

        val result = GgufInspector(tensorLayoutProfile = profile)
            .inspect(ByteArrayModelArtifactSource(bytes))

        assertEquals(GgufUnknownTensorTypePolicy.REJECT, profile.unknownTensorTypePolicy)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("tensor type 777") == true)
        assertTrue(result.exceptionOrNull()?.message?.contains(profile.id) == true)
    }

    @Test
    fun amperAmiProfileAcceptsProfiledFixedWidthType() {
        val bytes = singleTensorArtifact(
            tensorType = 0L, // F32
            firstDimension = 1UL,
            dataBytes = 4
        )

        val result = GgufInspector(
            tensorLayoutProfile = GgufTensorLayoutProfiles.AMPER_AMI_V1
        ).inspect(ByteArrayModelArtifactSource(bytes))

        assertTrue(result.isSuccess)
    }

    @Test
    fun amperAmiProfileAcceptsProfiledQuantizedType() {
        val bytes = singleTensorArtifact(
            tensorType = 2L, // Q4_0: 32 elements -> 18 encoded bytes
            firstDimension = 32UL,
            dataBytes = 18
        )

        val result = GgufInspector(
            tensorLayoutProfile = GgufTensorLayoutProfiles.AMPER_AMI_V1
        ).inspect(ByteArrayModelArtifactSource(bytes))

        assertTrue(result.isSuccess)
    }

    private fun singleTensorArtifact(
        tensorType: Long,
        firstDimension: ULong,
        dataBytes: Int
    ): ByteArray {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 1L)
        GgufTestFixtures.writeU64(out, firstDimension)
        GgufTestFixtures.writeU32(out, tensorType)
        GgufTestFixtures.writeU64(out, 0UL)
        GgufTestFixtures.padTo(out, 32)
        out.write(ByteArray(dataBytes))
        return out.toByteArray()
    }
}
