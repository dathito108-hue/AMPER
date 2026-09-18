package io.amper.neuroos.core

/**
 * Small deterministic incremental UTF-8 decoder for native token streaming.
 *
 * llama.cpp token pieces are byte sequences, not guaranteed Java strings: one Unicode code point
 * may be split across multiple sampled tokens and supplementary characters use four-byte UTF-8.
 * JNI NewStringUTF accepts Modified UTF-8, so feeding raw token pieces directly to it is unsafe.
 *
 * This decoder keeps only an incomplete trailing sequence between calls and emits ordinary Kotlin
 * UTF-16 strings. Malformed bytes are replaced deterministically with U+FFFD. [finish] must be
 * called exactly once at the end of a successful stream to flush an incomplete trailing sequence.
 */
internal class Utf8StreamDecoder {
    private var pending = ByteArray(0)
    private var finished = false

    fun append(bytes: ByteArray): String {
        check(!finished) { "UTF-8 stream decoder is already finished" }
        if (bytes.isEmpty()) return ""
        return decode(bytes, final = false)
    }

    fun finish(): String {
        check(!finished) { "UTF-8 stream decoder is already finished" }
        finished = true
        return decode(ByteArray(0), final = true)
    }

    private fun decode(bytes: ByteArray, final: Boolean): String {
        val input = ByteArray(pending.size + bytes.size)
        pending.copyInto(input, destinationOffset = 0)
        bytes.copyInto(input, destinationOffset = pending.size)
        pending = ByteArray(0)

        val out = StringBuilder(input.size)
        var index = 0
        while (index < input.size) {
            val first = input[index].toInt() and 0xff
            val width = when {
                first <= 0x7f -> 1
                first in 0xc2..0xdf -> 2
                first in 0xe0..0xef -> 3
                first in 0xf0..0xf4 -> 4
                else -> {
                    out.append(REPLACEMENT)
                    index += 1
                    continue
                }
            }

            val remaining = input.size - index
            var valid = true
            for (offset in 1 until minOf(width, remaining)) {
                val continuation = input[index + offset].toInt() and 0xff
                if (continuation !in 0x80..0xbf) {
                    valid = false
                    break
                }
            }

            if (!valid) {
                out.append(REPLACEMENT)
                index += 1
                continue
            }

            if (remaining < width) {
                if (!final) {
                    pending = input.copyOfRange(index, input.size)
                    break
                }
                out.append(REPLACEMENT)
                index = input.size
                continue
            }

            if (width >= 3) {
                val second = input[index + 1].toInt() and 0xff
                valid = when (first) {
                    0xe0 -> second >= 0xa0
                    0xed -> second <= 0x9f
                    0xf0 -> second >= 0x90
                    0xf4 -> second <= 0x8f
                    else -> true
                }
            }

            if (!valid) {
                out.append(REPLACEMENT)
                index += 1
                continue
            }

            val codePoint = when (width) {
                1 -> first
                2 -> ((first and 0x1f) shl 6) or
                    (input[index + 1].toInt() and 0x3f)
                3 -> ((first and 0x0f) shl 12) or
                    ((input[index + 1].toInt() and 0x3f) shl 6) or
                    (input[index + 2].toInt() and 0x3f)
                else -> ((first and 0x07) shl 18) or
                    ((input[index + 1].toInt() and 0x3f) shl 12) or
                    ((input[index + 2].toInt() and 0x3f) shl 6) or
                    (input[index + 3].toInt() and 0x3f)
            }

            if (codePoint <= 0xffff) {
                out.append(codePoint.toChar())
            } else {
                val scalar = codePoint - 0x10000
                out.append((0xd800 + (scalar ushr 10)).toChar())
                out.append((0xdc00 + (scalar and 0x3ff)).toChar())
            }
            index += width
        }

        return out.toString()
    }

    companion object {
        private const val REPLACEMENT: Char = '\uFFFD'
    }
}
