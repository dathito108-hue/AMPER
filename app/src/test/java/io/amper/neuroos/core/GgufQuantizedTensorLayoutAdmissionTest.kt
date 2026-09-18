package io.amper.neuroos.core

import org.junit.Assert.assertTrue
import org.junit.Test

class GgufQuantizedTensorLayoutAdmissionTest {
    @Test
    fun q4_0FootprintMustFitObservedTensorData() {
        val out = tensorArtifact(
            firstDimension = 32UL,
            tensorType = 2L,
            tensorDataBytes = 17
        )

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("tensor footprint lies outside tensor data") == true)
    }

    @Test
    fun q4_0RequiresFirstDimensionToMatchQuantizationBlock() {
        val out = tensorArtifact(
            firstDimension = 31UL,
            tensorType = 2L,
            tensorDataBytes = 64
        )

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("first dimension is incompatible") == true)
    }

    @Test
    fun q4_kExactBlockFootprintIsAccepted() {
        val out = tensorArtifact(
            firstDimension = 256UL,
            tensorType = 12L,
            tensorDataBytes = 144
        )

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out))

        assertTrue(result.isSuccess)
    }

    @Test
    fun q8_kFootprintBeyondObservedDataFailsClosed() {
        val out = tensorArtifact(
            firstDimension = 256UL,
            tensorType = 15L,
            tensorDataBytes = 291
        )

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("tensor footprint lies outside tensor data") == true)
    }

    @Test
    fun unknownNewTensorTypeRemainsForwardCompatible() {
        val out = tensorArtifact(
            firstDimension = 37UL,
            tensorType = 99L,
            tensorDataBytes = 32
        )

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out))

        assertTrue(result.isSuccess)
    }

    private fun tensorArtifact(
        firstDimension: ULong,
        tensorType: Long,
        tensorDataBytes: Int
    ): ByteArray {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 1L)
        GgufTestFixtures.writeU64(out, firstDimension)
        GgufTestFixtures.writeU32(out, tensorType)
        GgufTestFixtures.writeU64(out, 0UL)
        GgufTestFixtures.padTo(out, 32)
        out.write(ByteArray(tensorDataBytes))
        return out.toByteArray()
    }
}
