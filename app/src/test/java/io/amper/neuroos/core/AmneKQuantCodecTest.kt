package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneKQuantCodecTest {
    @Test
    fun q4KMatchesPinnedGgmlScaleAndNibbleLayout() {
        val block = q4KBlock(quantNibble = 9)
        val decoded = AmneKQuantCodec.decodeQ4K(block, 256)

        assertEquals(256, decoded.size)
        decoded.forEach { value ->
            assertEquals(9f, value, 0f)
        }

        val result = AmneKQuantCodec.matVecQ4K(
            matrixBlocks = block,
            rows = 1,
            columns = 256,
            vector = FloatArray(256) { 1f }
        )
        assertEquals(2304f, result.single(), 0f)
    }

    @Test
    fun q5KMatchesPinnedGgmlHighBitAndLowNibbleLayout() {
        val block = q5KBlock(lowNibble = 9, highBit = false)
        val decoded = AmneKQuantCodec.decodeQ5K(block, 256)

        decoded.forEach { value ->
            assertEquals(9f, value, 0f)
        }

        val withHighBit = q5KBlock(lowNibble = 9, highBit = true)
        val highDecoded = AmneKQuantCodec.decodeQ5K(withHighBit, 256)
        highDecoded.forEach { value ->
            assertEquals(25f, value, 0f)
        }
    }

    @Test
    fun q6KMatchesPinnedGgmlSixBitSignedLayout() {
        val block = q6KBlock(encodedSixBit = 33)
        val decoded = AmneKQuantCodec.decodeQ6K(block, 256)

        decoded.forEach { value ->
            assertEquals(1f, value, 0f)
        }

        val result = AmneKQuantCodec.matVecQ6K(
            matrixBlocks = block,
            rows = 1,
            columns = 256,
            vector = FloatArray(256) { 1f }
        )
        assertEquals(256f, result.single(), 0f)
    }

    @Test
    fun plannerAdmitsKQuantsButQualifiedRegistryKeepsThemOnReference() {
        val hardware = AmiHardwareSnapshot(
            features = setOf(
                AmiHardwareFeature.ARM64,
                AmiHardwareFeature.NEON
            ),
            logicalProcessors = 8,
            memoryClassMb = 512,
            lowRamDevice = false
        )
        val registry = AmneKernelRegistry(
            backends = listOf(AmneReferenceCpuKernels)
        )

        for ((type, primitive) in listOf(
            12L to AmneKernelPrimitive.MATVEC_Q4_K,
            13L to AmneKernelPrimitive.MATVEC_Q5_K,
            14L to AmneKernelPrimitive.MATVEC_Q6_K
        )) {
            val tensor = AmiTensorDescriptor(
                name = "weight-$type",
                dimensions = listOf(256UL, 1UL),
                sourceEncodingType = type,
                foundationOffset = 0L,
                storageBytes = when (type) {
                    12L -> 144L
                    13L -> 176L
                    else -> 210L
                }
            )
            assertEquals(
                primitive,
                AmneTensorKernelPlanner.requiredPrimitive(tensor)
            )
            assertEquals(
                AmneReferenceCpuKernels.descriptor.backendId,
                registry.backendFor(primitive, hardware).descriptor.backendId
            )
        }
    }

    @Test
    fun malformedKQuantStorageFailsClosed() {
        val result = runCatching {
            AmneKQuantCodec.decodeQ4K(
                ByteArray(143),
                256
            )
        }
        assertTrue(result.isFailure)
    }

    private fun q4KBlock(quantNibble: Int): ByteArray {
        require(quantNibble in 0..15)
        val block = ByteArray(144)
        writeHalfOne(block, 0)
        // dmin remains zero.
        for (index in 0 until 4) {
            block[4 + index] = 1
            block[8 + index] = 0
            block[12 + index] = 1
        }
        val packed = quantNibble or (quantNibble shl 4)
        for (index in 16 until 144) {
            block[index] = packed.toByte()
        }
        return block
    }

    private fun q5KBlock(
        lowNibble: Int,
        highBit: Boolean
    ): ByteArray {
        require(lowNibble in 0..15)
        val block = ByteArray(176)
        writeHalfOne(block, 0)
        for (index in 0 until 4) {
            block[4 + index] = 1
            block[8 + index] = 0
            block[12 + index] = 1
        }
        if (highBit) {
            for (index in 16 until 48) {
                block[index] = 0xff.toByte()
            }
        }
        val packed = lowNibble or (lowNibble shl 4)
        for (index in 48 until 176) {
            block[index] = packed.toByte()
        }
        return block
    }

    private fun q6KBlock(encodedSixBit: Int): ByteArray {
        require(encodedSixBit in 0..63)
        val block = ByteArray(210)
        val low = encodedSixBit and 0x0f
        val high = (encodedSixBit ushr 4) and 0x03
        val packedLow = low or (low shl 4)
        val packedHigh =
            high or (high shl 2) or (high shl 4) or (high shl 6)

        for (index in 0 until 128) {
            block[index] = packedLow.toByte()
        }
        for (index in 128 until 192) {
            block[index] = packedHigh.toByte()
        }
        for (index in 192 until 208) {
            block[index] = 1
        }
        writeHalfOne(block, 208)
        return block
    }

    private fun writeHalfOne(bytes: ByteArray, offset: Int) {
        bytes[offset] = 0x00
        bytes[offset + 1] = 0x3c
    }
}
