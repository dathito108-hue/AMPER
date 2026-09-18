package io.amper.neuroos.core

import java.io.ByteArrayOutputStream

internal object GgufTestFixtures {
    fun validArtifact(
        version: Long = 3L,
        tensorCount: ULong = 7UL,
        metadataCount: ULong = 11UL,
        payload: ByteArray = byteArrayOf(),
        alignment: Int = 32
    ): ByteArray {
        require(alignment >= 8 && alignment % 8 == 0)
        require(tensorCount <= 100_000UL) { "test fixture tensor count is intentionally bounded" }
        require(metadataCount <= 100_000UL) { "test fixture metadata count is intentionally bounded" }

        val out = header(version, tensorCount, metadataCount)

        var metadataIndex = 0UL
        while (metadataIndex < metadataCount) {
            if (metadataIndex == 0UL) {
                writeString(out, "general.alignment")
                writeU32(out, 4L) // GGUF_TYPE_UINT32
                writeU32(out, alignment.toLong())
            } else {
                writeString(out, "test.meta_$metadataIndex")
                writeU32(out, 0L) // GGUF_TYPE_UINT8
                out.write((metadataIndex and 0xffUL).toInt())
            }
            metadataIndex += 1UL
        }

        var tensorIndex = 0UL
        while (tensorIndex < tensorCount) {
            writeString(out, "tensor_$tensorIndex")
            writeU32(out, 1L) // n_dimensions
            writeU64(out, 1UL) // ne[0]
            writeU32(out, 0L) // ggml type; structural admission intentionally treats it as opaque
            writeU64(out, tensorIndex * alignment.toULong())
            tensorIndex += 1UL
        }

        padTo(out, alignment)
        if (tensorCount > 0UL) {
            val tensorDataBytes = Math.multiplyExact(tensorCount.toLong(), alignment.toLong())
            require(tensorDataBytes <= Int.MAX_VALUE.toLong())
            out.write(ByteArray(tensorDataBytes.toInt()))
        }
        out.write(payload)
        return out.toByteArray()
    }

    fun header(
        version: Long = 3L,
        tensorCount: ULong = 0UL,
        metadataCount: ULong = 0UL
    ): ByteArrayOutputStream = ByteArrayOutputStream().also { out ->
        out.write('G'.code)
        out.write('G'.code)
        out.write('U'.code)
        out.write('F'.code)
        writeU32(out, version)
        writeU64(out, tensorCount)
        writeU64(out, metadataCount)
    }

    fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU64(out, bytes.size.toULong())
        out.write(bytes)
    }

    fun writeU32(out: ByteArrayOutputStream, value: Long) {
        for (i in 0 until 4) {
            out.write(((value ushr (8 * i)) and 0xffL).toInt())
        }
    }

    fun writeU64(out: ByteArrayOutputStream, value: ULong) {
        for (i in 0 until 8) {
            out.write(((value shr (8 * i)) and 0xffUL).toInt())
        }
    }

    fun padTo(out: ByteArrayOutputStream, alignment: Int) {
        while (out.size() % alignment != 0) out.write(0)
    }
}
