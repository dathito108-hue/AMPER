package io.amper.neuroos.core

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

data class VoiceUtteranceSnapshot(
    val wavBytes: ByteArray,
    val metrics: Pcm16AudioMetrics,
    val capturedAtEpochMs: Long
) {
    init {
        require(wavBytes.isNotEmpty())
        require(wavBytes.size <= InferenceAttachment.MAX_ATTACHMENT_BYTES)
        require(capturedAtEpochMs >= 0L)
    }

    fun toAttachment(): InferenceAttachment =
        InferenceAttachment.fromBytes(
            kind = InferenceAttachmentKind.AUDIO,
            source = InferenceAttachmentSource.VOICE_SESSION,
            mediaType = "audio/wav",
            displayName = "voice-session-$capturedAtEpochMs.wav",
            bytes = wavBytes
        )
}

class EphemeralVoiceUtteranceStore {
    private val latest = AtomicReference<VoiceUtteranceSnapshot?>(null)

    fun publish(
        encoded: EncodedLiveAudio,
        capturedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        latest.set(
            VoiceUtteranceSnapshot(
                wavBytes = encoded.wavBytes.copyOf(),
                metrics = encoded.metrics,
                capturedAtEpochMs = capturedAtEpochMs
            )
        )
    }

    fun latest(
        maxAgeMs: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): VoiceUtteranceSnapshot? {
        require(maxAgeMs > 0L)
        val snapshot = latest.get() ?: return null
        val age = nowEpochMs - snapshot.capturedAtEpochMs
        if (age < 0L || age > maxAgeMs) return null
        return snapshot.copy(wavBytes = snapshot.wavBytes.copyOf())
    }

    fun clear() {
        latest.set(null)
    }

    fun hasUtterance(): Boolean = latest.get() != null
}

/**
 * Streaming, bounded voice activity detector and utterance assembler.
 *
 * This is intentionally signal-only. It does not infer semantics, identity, language, or speaker.
 * Its only job is to segment PCM into bounded WAV utterances for the explicitly selected audio model.
 */
class BoundedVoiceUtteranceAssembler(
    private val sampleRateHz: Int = 16_000,
    private val frameSamples: Int = 320,
    private val speechStartRms: Double = 0.025,
    private val speechContinueRms: Double = 0.012,
    private val startFramesRequired: Int = 3,
    private val endSilenceMs: Int = 700,
    private val preRollMs: Int = 200,
    private val maxUtteranceMs: Int = 12_000
) {
    private val silenceFramesRequired =
        ((endSilenceMs.toLong() * sampleRateHz + frameSamples * 1_000L - 1L) /
            (frameSamples * 1_000L)).toInt().coerceAtLeast(1)
    private val preRollFrames =
        ((preRollMs.toLong() * sampleRateHz + frameSamples * 1_000L - 1L) /
            (frameSamples * 1_000L)).toInt().coerceAtLeast(0)
    private val maxSamples =
        (sampleRateHz.toLong() * maxUtteranceMs / 1_000L).toInt().coerceAtLeast(frameSamples)

    private val preRoll = ArrayDeque<ShortArray>()
    private var active = false
    private var startFrames = 0
    private var silenceFrames = 0
    private var utterance = ShortAccumulator(maxSamples)

    init {
        require(sampleRateHz in 8_000..96_000)
        require(frameSamples > 0)
        require(speechStartRms in 0.0..1.0)
        require(speechContinueRms in 0.0..1.0)
        require(speechContinueRms <= speechStartRms)
        require(startFramesRequired > 0)
        require(endSilenceMs > 0)
        require(preRollMs >= 0)
        require(maxUtteranceMs in 500..12_000)
    }

    fun feed(frame: ShortArray): EncodedLiveAudio? {
        require(frame.isNotEmpty())
        require(frame.size <= frameSamples) { "voice frame exceeds configured frame size" }

        val copy = frame.copyOf()
        val rms = normalizedRms(copy)

        if (!active) {
            rememberPreRoll(copy)
            if (rms >= speechStartRms) {
                startFrames += 1
                if (startFrames >= startFramesRequired) {
                    active = true
                    silenceFrames = 0
                    utterance = ShortAccumulator(maxSamples)
                    preRoll.forEach { utterance.append(it) }
                    preRoll.clear()
                }
            } else {
                startFrames = 0
            }
            return if (active && utterance.isFull()) complete() else null
        }

        utterance.append(copy)
        if (rms >= speechContinueRms) {
            silenceFrames = 0
        } else {
            silenceFrames += 1
        }

        return when {
            utterance.isFull() -> complete()
            silenceFrames >= silenceFramesRequired -> complete()
            else -> null
        }
    }

    fun flushActive(): EncodedLiveAudio? =
        if (active && utterance.size > 0) complete() else null

    fun reset() {
        active = false
        startFrames = 0
        silenceFrames = 0
        preRoll.clear()
        utterance = ShortAccumulator(maxSamples)
    }

    fun isActiveUtterance(): Boolean = active

    private fun rememberPreRoll(frame: ShortArray) {
        if (preRollFrames <= 0) return
        preRoll.addLast(frame)
        while (preRoll.size > preRollFrames) {
            preRoll.removeFirst()
        }
    }

    private fun complete(): EncodedLiveAudio {
        val samples = utterance.toShortArray()
        reset()
        return Pcm16WaveEncoder.encodeMono(samples, sampleRateHz)
    }

    private fun normalizedRms(samples: ShortArray): Double {
        var sumSquares = 0.0
        samples.forEach { sample ->
            val value = sample.toDouble()
            sumSquares += value * value
        }
        return (
            sqrt(sumSquares / samples.size.toDouble()) /
                Short.MAX_VALUE.toDouble()
            ).coerceIn(0.0, 1.0)
    }

    private class ShortAccumulator(private val maxSamples: Int) {
        private var data = ShortArray(minOf(maxSamples, 4_096))
        var size: Int = 0
            private set

        fun append(samples: ShortArray) {
            if (size >= maxSamples) return
            val accepted = minOf(samples.size, maxSamples - size)
            ensureCapacity(size + accepted)
            samples.copyInto(data, size, 0, accepted)
            size += accepted
        }

        fun isFull(): Boolean = size >= maxSamples

        fun toShortArray(): ShortArray = data.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= data.size) return
            var next = data.size.coerceAtLeast(1)
            while (next < required) {
                next = minOf(maxSamples, next * 2)
                if (next == data.size) break
            }
            data = data.copyOf(next)
        }
    }
}
