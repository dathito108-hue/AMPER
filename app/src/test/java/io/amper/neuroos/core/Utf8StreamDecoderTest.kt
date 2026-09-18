package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Utf8StreamDecoderTest {
    @Test
    fun splitVietnameseAndSupplementaryCodePointsDecodeExactlyOnce() {
        val decoder = Utf8StreamDecoder()
        val bytes = "Aế😀Z".toByteArray(Charsets.UTF_8)

        val chunks = listOf(
            bytes.copyOfRange(0, 2),
            bytes.copyOfRange(2, 4),
            bytes.copyOfRange(4, 6),
            bytes.copyOfRange(6, bytes.size)
        )

        val output = buildString {
            chunks.forEach { append(decoder.append(it)) }
            append(decoder.finish())
        }

        assertEquals("Aế😀Z", output)
    }

    @Test
    fun incompleteTrailingSequenceIsHeldUntilNextPiece() {
        val decoder = Utf8StreamDecoder()
        val euro = "€".toByteArray(Charsets.UTF_8)

        assertEquals("", decoder.append(euro.copyOfRange(0, 1)))
        assertEquals("", decoder.append(euro.copyOfRange(1, 2)))
        assertEquals("€", decoder.append(euro.copyOfRange(2, 3)))
        assertEquals("", decoder.finish())
    }

    @Test
    fun malformedLeaderDoesNotConsumeFollowingAscii() {
        val decoder = Utf8StreamDecoder()

        val output = buildString {
            append(decoder.append(byteArrayOf(0xe1.toByte(), 'A'.code.toByte())))
            append(decoder.finish())
        }

        assertEquals("\uFFFDA", output)
    }

    @Test
    fun truncatedFinalSequenceBecomesSingleReplacementCharacter() {
        val decoder = Utf8StreamDecoder()

        assertEquals("", decoder.append(byteArrayOf(0xf0.toByte(), 0x9f.toByte())))
        assertEquals("\uFFFD", decoder.finish())
    }

    @Test
    fun overlongSurrogateAndOutOfRangeSequencesFailClosed() {
        val decoder = Utf8StreamDecoder()
        val bytes = byteArrayOf(
            0xc0.toByte(), 0xaf.toByte(),
            0xed.toByte(), 0xa0.toByte(), 0x80.toByte(),
            0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()
        )

        val output = decoder.append(bytes) + decoder.finish()

        assertEquals("\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD", output)
    }

    @Test
    fun decoderCannotBeUsedAfterFinish() {
        val decoder = Utf8StreamDecoder()
        assertEquals("", decoder.finish())
        assertThrows(IllegalStateException::class.java) {
            decoder.append(byteArrayOf('x'.code.toByte()))
        }
    }
}
