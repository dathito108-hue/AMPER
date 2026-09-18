package io.amper.neuroos.core

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

data class Pcm16AudioMetrics(
    val sampleRateHz: Int,
    val sampleCount: Int,
    val durationMs: Long,
    val rms: Double,
    val peak: Double,
    val zeroCrossingRate: Double
)

data class EncodedLiveAudio(
    val wavBytes: ByteArray,
    val metrics: Pcm16AudioMetrics
) {
    init {
        require(wavBytes.isNotEmpty())
        require(wavBytes.size <= InferenceAttachment.MAX_ATTACHMENT_BYTES)
    }

    fun toAttachment(
        displayName: String = "live-microphone.wav"
    ): InferenceAttachment = InferenceAttachment.fromBytes(
        kind = InferenceAttachmentKind.AUDIO,
        source = InferenceAttachmentSource.LIVE_MICROPHONE,
        mediaType = "audio/wav",
        displayName = displayName,
        bytes = wavBytes
    )
}

/**
 * Pure PCM16/WAVE encoder shared by Android live capture and JVM tests.
 *
 * Output is canonical little-endian RIFF/WAVE: mono PCM16 with no metadata chunks. This keeps the
 * payload deterministic, bounded, and directly decodable by the pinned libmtmd miniaudio helper.
 */
object Pcm16WaveEncoder {
    private const val RIFF_HEADER_BYTES = 44
    private const val PCM_FORMAT = 1
    private const val CHANNELS_MONO = 1
    private const val BITS_PER_SAMPLE = 16

    fun encodeMono(
        samples: ShortArray,
        sampleRateHz: Int
    ): EncodedLiveAudio {
        require(sampleRateHz in 8_000..96_000) { "unsupported audio sample rate" }
        require(samples.isNotEmpty()) { "audio sample buffer is empty" }

        val dataBytes = Math.multiplyExact(samples.size, 2)
        val totalBytes = Math.addExact(RIFF_HEADER_BYTES, dataBytes)
        require(totalBytes <= InferenceAttachment.MAX_ATTACHMENT_BYTES) {
            "encoded live audio exceeds inference attachment limit"
        }

        val out = ByteArrayOutputStream(totalBytes)
        out.writeAscii("RIFF")
        out.writeLe32(36 + dataBytes)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeLe32(16)
        out.writeLe16(PCM_FORMAT)
        out.writeLe16(CHANNELS_MONO)
        out.writeLe32(sampleRateHz)
        val byteRate = Math.multiplyExact(sampleRateHz, 2)
        out.writeLe32(byteRate)
        out.writeLe16(2)
        out.writeLe16(BITS_PER_SAMPLE)
        out.writeAscii("data")
        out.writeLe32(dataBytes)

        var sumSquares = 0.0
        var peak = 0
        var zeroCrossings = 0
        var previous = samples[0].toInt()
        samples.forEachIndexed { index, value ->
            val sample = value.toInt()
            out.write(sample and 0xff)
            out.write((sample ushr 8) and 0xff)
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            sumSquares += sample.toDouble() * sample.toDouble()
            if (
                index > 0 &&
                ((previous < 0 && sample >= 0) || (previous >= 0 && sample < 0))
            ) {
                zeroCrossings += 1
            }
            previous = sample
        }

        val bytes = out.toByteArray()
        check(bytes.size == totalBytes)
        val sampleCount = samples.size
        val durationMs = (sampleCount.toLong() * 1_000L / sampleRateHz.toLong())
            .coerceAtLeast(1L)
        val rms = sqrt(sumSquares / sampleCount.toDouble()) / Short.MAX_VALUE.toDouble()
        val peakNormalized = peak.toDouble() / Short.MAX_VALUE.toDouble()
        val zeroCrossingRate =
            zeroCrossings.toDouble() / sampleCount.toDouble()

        return EncodedLiveAudio(
            wavBytes = bytes,
            metrics = Pcm16AudioMetrics(
                sampleRateHz = sampleRateHz,
                sampleCount = sampleCount,
                durationMs = durationMs,
                rms = rms.coerceIn(0.0, 1.0),
                peak = peakNormalized.coerceIn(0.0, 1.0),
                zeroCrossingRate = zeroCrossingRate.coerceIn(0.0, 1.0)
            )
        )
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        value.forEach { char ->
            require(char.code in 0..127)
            write(char.code)
        }
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        require(value in 0..0xffff)
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        require(value >= 0)
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
