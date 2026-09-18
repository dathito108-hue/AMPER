package io.amper.neuroos.core

import org.junit.Assert.assertTrue
import org.junit.Test

class GgufStructuralAdmissionTest {
    @Test
    fun metadataStringLengthBombIsRejectedBeforeAllocation() {
        val out = GgufTestFixtures.header(metadataCount = 1UL)
        GgufTestFixtures.writeString(out, "test.string")
        GgufTestFixtures.writeU32(out, 8L) // GGUF_TYPE_STRING
        GgufTestFixtures.writeU64(out, ULong.MAX_VALUE)

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("metadata string") == true)
    }

    @Test
    fun metadataArrayElementBombIsRejectedBeforeWalkingElements() {
        val out = GgufTestFixtures.header(metadataCount = 1UL)
        GgufTestFixtures.writeString(out, "test.array")
        GgufTestFixtures.writeU32(out, 9L) // GGUF_TYPE_ARRAY
        GgufTestFixtures.writeU32(out, 0L) // GGUF_TYPE_UINT8
        GgufTestFixtures.writeU64(out, 101UL)
        val inspector = GgufInspector(
            GgufAdmissionPolicy(maxMetadataArrayElements = 100UL)
        )

        val result = inspector.inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("array exceeds admission element limit") == true)
    }

    @Test
    fun nestedMetadataArraysAreDepthBounded() {
        val out = GgufTestFixtures.header(metadataCount = 1UL)
        GgufTestFixtures.writeString(out, "test.nested")
        GgufTestFixtures.writeU32(out, 9L) // metadata value is ARRAY
        repeat(2) {
            GgufTestFixtures.writeU32(out, 9L) // element type ARRAY
            GgufTestFixtures.writeU64(out, 1UL)
        }
        val inspector = GgufInspector(
            GgufAdmissionPolicy(maxMetadataArrayDepth = 2)
        )

        val result = inspector.inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("array nesting") == true)
    }

    @Test
    fun tensorNameLengthBombIsRejectedBeforeReadingName() {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeU64(out, 65UL)

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("tensor name") == true)
    }

    @Test
    fun tensorDimensionCountIsBoundedBeforeDimensionWalk() {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 9L)

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("dimension count") == true)
    }

    @Test
    fun misalignedTensorOffsetIsRejected() {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 1L)
        GgufTestFixtures.writeU64(out, 1UL)
        GgufTestFixtures.writeU32(out, 0L)
        GgufTestFixtures.writeU64(out, 1UL)

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not aligned") == true)
    }

    @Test
    fun tensorOffsetMustLandInsideObservedTensorData() {
        val out = GgufTestFixtures.header(tensorCount = 1UL)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 1L)
        GgufTestFixtures.writeU64(out, 1UL)
        GgufTestFixtures.writeU32(out, 0L)
        GgufTestFixtures.writeU64(out, 64UL)
        GgufTestFixtures.padTo(out, 32)
        out.write(ByteArray(32))

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("outside tensor data") == true)
    }

    @Test
    fun explicitAlignmentIsAppliedToTensorOffsets() {
        val out = GgufTestFixtures.header(tensorCount = 1UL, metadataCount = 1UL)
        GgufTestFixtures.writeString(out, "general.alignment")
        GgufTestFixtures.writeU32(out, 4L) // GGUF_TYPE_UINT32
        GgufTestFixtures.writeU32(out, 64L)
        GgufTestFixtures.writeString(out, "tensor")
        GgufTestFixtures.writeU32(out, 1L)
        GgufTestFixtures.writeU64(out, 1UL)
        GgufTestFixtures.writeU32(out, 0L)
        GgufTestFixtures.writeU64(out, 64UL)
        GgufTestFixtures.padTo(out, 64)
        out.write(ByteArray(128))

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isSuccess)
    }

    @Test
    fun invalidBooleanEncodingFailsClosed() {
        val out = GgufTestFixtures.header(metadataCount = 1UL)
        GgufTestFixtures.writeString(out, "test.bool")
        GgufTestFixtures.writeU32(out, 7L) // GGUF_TYPE_BOOL
        out.write(2)

        val result = GgufInspector().inspect(ByteArrayModelArtifactSource(out.toByteArray()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("bool value") == true)
    }
}
