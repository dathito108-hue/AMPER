package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufTensorLayoutProfileTest {
    @Test
    fun amperAmiProfileHasExactSupportedSourceGeometry() {
        val profile = GgufTensorLayoutProfiles.AMPER_AMI_V1

        assertEquals("amper-ami-v1", profile.id)
        assertEquals(32UL, profile.layoutFor(2L)?.blockSize) // Q4_0
        assertEquals(18L, profile.layoutFor(2L)?.typeSizeBytes)
        assertEquals(256UL, profile.layoutFor(12L)?.blockSize) // Q4_K
        assertEquals(144L, profile.layoutFor(12L)?.typeSizeBytes)
    }

    @Test
    fun inspectorActuallyUsesInjectedProfileForFootprintValidation() {
        val bytes = singleTensorArtifact(
            tensorType = 99L,
            firstDimension = 1UL,
            dataBytes = 1
        )
        val source = ByteArrayModelArtifactSource(bytes)

        // The generic profile does not know type 99, so it applies shape/offset guards only.
        assertTrue(GgufInspector().inspect(source).isSuccess)

        val customProfile = GgufTensorLayoutProfile(
            id = "test-abi",
            layouts = mapOf(99L to GgufTensorLayout(blockSize = 1UL, typeSizeBytes = 4L))
        )
        val inspector = GgufInspector(tensorLayoutProfile = customProfile)
        val result = inspector.inspect(source)

        assertEquals("test-abi", inspector.tensorLayoutProfileId)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("tensor footprint lies outside tensor data") == true)
    }

    @Test
    fun absentFutureTypeRemainsForwardCompatibleWithinStructuralBounds() {
        val profile = GgufTensorLayoutProfile(
            id = "minimal-native-abi",
            layouts = mapOf(0L to GgufTensorLayout(1UL, 4L))
        )
        val bytes = singleTensorArtifact(
            tensorType = 777L,
            firstDimension = 1UL,
            dataBytes = 1
        )

        val result = GgufInspector(tensorLayoutProfile = profile)
            .inspect(ByteArrayModelArtifactSource(bytes))

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
