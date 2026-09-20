package io.amper.neuroos.core

/**
 * Exact ggml K-quant decoding for the llama.cpp ABI pinned by AMPER.
 *
 * Storage identities:
 *  Q4_K (type 12): 256 elements / 144 bytes
 *  Q5_K (type 13): 256 elements / 176 bytes
 *  Q6_K (type 14): 256 elements / 210 bytes
 *
 * The implementation mirrors the pinned ggml dequantization semantics. It never writes or
 * requantizes AMI FOUNDATION_WEIGHTS; decoding is transient for reference/correctness execution.
 */
object AmneKQuantCodec {
    const val SUPER_BLOCK_ELEMENTS: Int = 256

    fun decodeQ4K(blocks: ByteArray, elements: Int): FloatArray {
        require(elements > 0 && elements % SUPER_BLOCK_ELEMENTS == 0)
        val expected = Math.multiplyExact(
            elements / SUPER_BLOCK_ELEMENTS,
            AmneTensorEncoding.Q4_K.blockBytes
        )
        require(blocks.size == expected) { "Q4_K byte length mismatch" }

        val output = FloatArray(elements)
        var blockOffset = 0
        var outputOffset = 0
        while (outputOffset < elements) {
            val d = halfAt(blocks, blockOffset)
            val dmin = halfAt(blocks, blockOffset + 2)
            val scalesOffset = blockOffset + 4
            var qOffset = blockOffset + 16
            var scaleIndex = 0
            var groupBase = outputOffset

            repeat(4) {
                val first = scaleMinK4(blocks, scalesOffset, scaleIndex)
                val second = scaleMinK4(blocks, scalesOffset, scaleIndex + 1)
                val d1 = d * first.first.toFloat()
                val m1 = dmin * first.second.toFloat()
                val d2 = d * second.first.toFloat()
                val m2 = dmin * second.second.toFloat()

                for (l in 0 until 32) {
                    val packed = blocks[qOffset + l].toInt() and 0xff
                    output[groupBase + l] =
                        d1 * (packed and 0x0f).toFloat() - m1
                    output[groupBase + 32 + l] =
                        d2 * (packed ushr 4).toFloat() - m2
                }

                qOffset += 32
                scaleIndex += 2
                groupBase += 64
            }

            blockOffset += AmneTensorEncoding.Q4_K.blockBytes
            outputOffset += SUPER_BLOCK_ELEMENTS
        }
        return output
    }

    fun decodeQ5K(blocks: ByteArray, elements: Int): FloatArray {
        require(elements > 0 && elements % SUPER_BLOCK_ELEMENTS == 0)
        val expected = Math.multiplyExact(
            elements / SUPER_BLOCK_ELEMENTS,
            AmneTensorEncoding.Q5_K.blockBytes
        )
        require(blocks.size == expected) { "Q5_K byte length mismatch" }

        val output = FloatArray(elements)
        var blockOffset = 0
        var outputOffset = 0
        while (outputOffset < elements) {
            val d = halfAt(blocks, blockOffset)
            val dmin = halfAt(blocks, blockOffset + 2)
            val scalesOffset = blockOffset + 4
            val highOffset = blockOffset + 16
            var lowOffset = blockOffset + 48
            var scaleIndex = 0
            var highMask1 = 1
            var highMask2 = 2
            var groupBase = outputOffset

            repeat(4) {
                val first = scaleMinK4(blocks, scalesOffset, scaleIndex)
                val second = scaleMinK4(blocks, scalesOffset, scaleIndex + 1)
                val d1 = d * first.first.toFloat()
                val m1 = dmin * first.second.toFloat()
                val d2 = d * second.first.toFloat()
                val m2 = dmin * second.second.toFloat()

                for (l in 0 until 32) {
                    val packed = blocks[lowOffset + l].toInt() and 0xff
                    val high = blocks[highOffset + l].toInt() and 0xff
                    val q1 = (packed and 0x0f) +
                        if ((high and highMask1) != 0) 16 else 0
                    val q2 = (packed ushr 4) +
                        if ((high and highMask2) != 0) 16 else 0
                    output[groupBase + l] = d1 * q1.toFloat() - m1
                    output[groupBase + 32 + l] = d2 * q2.toFloat() - m2
                }

                lowOffset += 32
                scaleIndex += 2
                highMask1 = highMask1 shl 2
                highMask2 = highMask2 shl 2
                groupBase += 64
            }

            blockOffset += AmneTensorEncoding.Q5_K.blockBytes
            outputOffset += SUPER_BLOCK_ELEMENTS
        }
        return output
    }

    fun decodeQ6K(blocks: ByteArray, elements: Int): FloatArray {
        require(elements > 0 && elements % SUPER_BLOCK_ELEMENTS == 0)
        val expected = Math.multiplyExact(
            elements / SUPER_BLOCK_ELEMENTS,
            AmneTensorEncoding.Q6_K.blockBytes
        )
        require(blocks.size == expected) { "Q6_K byte length mismatch" }

        val output = FloatArray(elements)
        var blockOffset = 0
        var outputOffset = 0
        while (outputOffset < elements) {
            val lowBase = blockOffset
            val highBase = blockOffset + 128
            val scaleBase = blockOffset + 192
            val d = halfAt(blocks, blockOffset + 208)

            repeat(2) { half ->
                val ql = lowBase + half * 64
                val qh = highBase + half * 32
                val sc = scaleBase + half * 8
                val out = outputOffset + half * 128

                for (l in 0 until 32) {
                    val iscale = l / 16
                    val low0 = blocks[ql + l].toInt() and 0xff
                    val low1 = blocks[ql + 32 + l].toInt() and 0xff
                    val high = blocks[qh + l].toInt() and 0xff

                    val q1 = ((low0 and 0x0f) or (((high ushr 0) and 3) shl 4)) - 32
                    val q2 = ((low1 and 0x0f) or (((high ushr 2) and 3) shl 4)) - 32
                    val q3 = ((low0 ushr 4) or (((high ushr 4) and 3) shl 4)) - 32
                    val q4 = ((low1 ushr 4) or (((high ushr 6) and 3) shl 4)) - 32

                    output[out + l] =
                        d * blocks[sc + iscale].toInt().toFloat() * q1.toFloat()
                    output[out + 32 + l] =
                        d * blocks[sc + iscale + 2].toInt().toFloat() * q2.toFloat()
                    output[out + 64 + l] =
                        d * blocks[sc + iscale + 4].toInt().toFloat() * q3.toFloat()
                    output[out + 96 + l] =
                        d * blocks[sc + iscale + 6].toInt().toFloat() * q4.toFloat()
                }
            }

            blockOffset += AmneTensorEncoding.Q6_K.blockBytes
            outputOffset += SUPER_BLOCK_ELEMENTS
        }
        return output
    }

    fun matVecQ4K(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray = matVec(
        matrixBlocks,
        rows,
        columns,
        vector,
        AmneTensorEncoding.Q4_K,
        ::decodeQ4K
    )

    fun matVecQ5K(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray = matVec(
        matrixBlocks,
        rows,
        columns,
        vector,
        AmneTensorEncoding.Q5_K,
        ::decodeQ5K
    )

    fun matVecQ6K(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray
    ): FloatArray = matVec(
        matrixBlocks,
        rows,
        columns,
        vector,
        AmneTensorEncoding.Q6_K,
        ::decodeQ6K
    )

    fun decodeRow(
        encoding: AmneTensorEncoding,
        rowBytes: ByteArray,
        elements: Int
    ): FloatArray = when (encoding) {
        AmneTensorEncoding.Q4_K -> decodeQ4K(rowBytes, elements)
        AmneTensorEncoding.Q5_K -> decodeQ5K(rowBytes, elements)
        AmneTensorEncoding.Q6_K -> decodeQ6K(rowBytes, elements)
        else -> error("not a K-quant encoding: $encoding")
    }

    private fun matVec(
        matrixBlocks: ByteArray,
        rows: Int,
        columns: Int,
        vector: FloatArray,
        encoding: AmneTensorEncoding,
        decode: (ByteArray, Int) -> FloatArray
    ): FloatArray {
        require(rows > 0 && columns > 0)
        require(columns % SUPER_BLOCK_ELEMENTS == 0) {
            "$encoding column count must be a multiple of 256"
        }
        require(vector.size == columns)

        val blocksPerRow = columns / SUPER_BLOCK_ELEMENTS
        val rowBytes = Math.multiplyExact(blocksPerRow, encoding.blockBytes)
        require(
            matrixBlocks.size == Math.multiplyExact(rows, rowBytes)
        ) {
            "$encoding matrix byte length mismatch"
        }

        val output = FloatArray(rows)
        for (row in 0 until rows) {
            val start = row * rowBytes
            val decoded = decode(
                matrixBlocks.copyOfRange(start, start + rowBytes),
                columns
            )
            var sum = 0.0
            for (column in 0 until columns) {
                sum += decoded[column].toDouble() * vector[column].toDouble()
            }
            output[row] = sum.toFloat()
        }
        return output
    }

    private fun scaleMinK4(
        bytes: ByteArray,
        offset: Int,
        index: Int
    ): Pair<Int, Int> {
        require(index in 0..7)
        return if (index < 4) {
            Pair(
                bytes[offset + index].toInt() and 63,
                bytes[offset + index + 4].toInt() and 63
            )
        } else {
            Pair(
                (bytes[offset + index + 4].toInt() and 0x0f) or
                    (((bytes[offset + index - 4].toInt() and 0xff) ushr 6) shl 4),
                ((bytes[offset + index + 4].toInt() and 0xff) ushr 4) or
                    (((bytes[offset + index].toInt() and 0xff) ushr 6) shl 4)
            )
        }
    }

    private fun halfAt(bytes: ByteArray, offset: Int): Float {
        val bits =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)
        return AmneReferenceCpuKernels.halfToFloat(bits)
    }
}
