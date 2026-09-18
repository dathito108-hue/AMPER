package io.amper.neuroos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16WaveEncoderTest {
    @Test
    fun canonicalMonoPcm16WaveHasCorrectHeaderAndLittleEndianSamples() {
        val encoded = Pcm16WaveEncoder.encodeMono(
            samples = shortArrayOf(0, 1, -1, 32767, -32768),
            sampleRateHz = 16_000
        )
        val bytes = encoded.wavBytes

        assertEquals("RIFF", ascii(bytes, 0, 4))
        assertEquals("WAVE", ascii(bytes, 8, 4))
        assertEquals("fmt ", ascii(bytes, 12, 4))
        assertEquals(16, le32(bytes, 16))
        assertEquals(1, le16(bytes, 20))
        assertEquals(1, le16(bytes, 22))
        assertEquals(16_000, le32(bytes, 24))
        assertEquals(32_000, le32(bytes, 28))
        assertEquals(2, le16(bytes, 32))
        assertEquals(16, le16(bytes, 34))
        assertEquals("data", ascii(bytes, 36, 4))
        assertEquals(10, le32(bytes, 40))
        assertEquals(54, bytes.size)

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00,
                0x01, 0x00,
                0xff.toByte(), 0xff.toByte(),
                0xff.toByte(), 0x7f,
                0x00, 0x80.toByte()
            ),
            bytes.copyOfRange(44, bytes.size)
        )
    }

    @Test
    fun encodedAudioBecomesBoundedAudioAttachment() {
        val samples = ShortArray(16_000) { index ->
            if (index % 2 == 0) 10_000 else -10_000
        }
        val encoded = Pcm16WaveEncoder.encodeMono(samples, 16_000)
        val attachment = encoded.toAttachment("live.wav")

        assertEquals(InferenceAttachmentKind.AUDIO, attachment.kind)
        assertEquals("audio/wav", attachment.mediaType)
        assertEquals("live.wav", attachment.displayName)
        assertEquals(32_044, attachment.lengthBytes)
        assertArrayEquals(encoded.wavBytes, attachment.readBytes())
        assertEquals(1_000L, encoded.metrics.durationMs)
        assertEquals(16_000, encoded.metrics.sampleCount)
        assertTrue(encoded.metrics.rms > 0.30)
        assertTrue(encoded.metrics.peak > 0.30)
        assertTrue(encoded.metrics.zeroCrossingRate > 0.90)
    }

    @Test
    fun oversizePcmIsRejectedBeforeWaveAllocation() {
        val maxSamples =
            (InferenceAttachment.MAX_ATTACHMENT_BYTES - 44) / 2
        val tooMany = ShortArray(maxSamples + 1)

        val failure = runCatching {
            Pcm16WaveEncoder.encodeMono(tooMany, 16_000)
        }

        assertTrue(failure.isFailure)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
