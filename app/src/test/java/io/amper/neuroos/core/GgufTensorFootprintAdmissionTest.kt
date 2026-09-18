package io.amper.neuroos.core

import org.junit.Assert.assertTrue
import org.junit.Test

class GgufTensorFootprintAdmissionTest {
    @Test
    fun tensorElementProductOverflowFailsClosed() {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 2L)
        GgufTestFixtures.writeU64(out, ULong.MAX_VALUE)
        GgufTestFixtures.writeU64(out, 2UL)
        GgufTestFixtures.writeU32(out, 0L) // GGML_TYPE_F32
        GgufTestFixtures.writeU64(out, 0UL)

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("element count") == true)
    }

    @Test
    fun deploymentCanBoundTensorElementCountBeforeNativeLoad() {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 2L)
        GgufTestFixtures.writeU64(out, 11UL)
        GgufTestFixtures.writeU64(out, 10UL)
        GgufTestFixtures.writeU32(out, 0L) // GGML_TYPE_F32
        GgufTestFixtures.writeU64(out, 0UL)
        GgufTestFixtures.padTo(out, 32)
        out.write(ByteArray(512))
        val inspector = GgufInspector(GgufAdmissionPolicy(maxTensorElements = 100UL))

        val result = inspector.inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("element count exceeds admission limit") == true)
    }

    @Test
    fun fixedWidthTensorFootprintMustFitObservedTensorData() {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 1L)
        GgufTestFixtures.writeU64(out, 16UL)
        GgufTestFixtures.writeU32(out, 0L) // GGML_TYPE_F32 => 64 bytes
        GgufTestFixtures.writeU64(out, 0UL)
        GgufTestFixtures.padTo(out, 32)
        out.write(ByteArray(32))

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("tensor footprint lies outside tensor data") == true)
    }

    @Test
    fun fixedWidthTensorFootprintAtExactBoundaryIsAccepted() {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 2L)
        GgufTestFixtures.writeU64(out, 4UL)
        GgufTestFixtures.writeU64(out, 4UL)
        GgufTestFixtures.writeU32(out, 0L) // GGML_TYPE_F32 => 64 bytes
        GgufTestFixtures.writeU64(out, 0UL)
        GgufTestFixtures.padTo(out, 32)
        out.write(ByteArray(64))

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isSuccess)
    }

    @Test
    fun unknownQuantizedTypeRemainsForwardCompatibleButOffsetBounded() {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 1L)
        GgufTestFixtures.writeU64(out, 256UL)
        GgufTestFixtures.writeU32(out, 42L) // future/quantized type: no fixed-width assumption
        GgufTestFixtures.writeU64(out, 0UL)
        GgufTestFixtures.padTo(out, 32)
        out.write(ByteArray(32))

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isSuccess)
    }
}
