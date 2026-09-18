package io.amper.neuroos.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * User-initiated, bounded microphone capture for model-native audio understanding.
 *
 * No file is created. PCM exists only in this method's bounded arrays and the returned WAV payload;
 * callers decide whether to attach it to one inference turn or discard it.
 */
class AndroidLiveAudioAttachmentCapture(context: Context) {
    private val appContext = context.applicationContext

    fun capture(
        durationMs: Long = DEFAULT_CAPTURE_MS
    ): Result<EncodedLiveAudio> = runCatching {
        require(durationMs in MIN_CAPTURE_MS..MAX_CAPTURE_MS) {
            "live audio capture must be $MIN_CAPTURE_MS..$MAX_CAPTURE_MS ms"
        }
        check(
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "RECORD_AUDIO permission is not granted" }

        val minimumBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minimumBytes > 0) { "microphone buffer size is unavailable" }

        val targetSamples = Math.multiplyExact(
            SAMPLE_RATE_HZ.toLong(),
            durationMs
        ).div(1_000L).toInt()
        require(targetSamples > 0)
        val maxWavBytes = 44L + targetSamples.toLong() * 2L
        require(maxWavBytes <= InferenceAttachment.MAX_ATTACHMENT_BYTES.toLong()) {
            "requested live audio would exceed attachment limit"
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimumBytes, READ_BUFFER_SAMPLES * 2)
        )
        require(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "microphone recorder failed to initialize"
        }

        val output = ShortArray(targetSamples)
        val scratch = ShortArray(READ_BUFFER_SAMPLES)
        var written = 0

        try {
            recorder.startRecording()
            while (written < targetSamples) {
                val requested = minOf(scratch.size, targetSamples - written)
                val read = recorder.read(
                    scratch,
                    0,
                    requested,
                    AudioRecord.READ_BLOCKING
                )
                when {
                    read > 0 -> {
                        scratch.copyInto(
                            destination = output,
                            destinationOffset = written,
                            startIndex = 0,
                            endIndex = read
                        )
                        written += read
                    }
                    read == 0 -> continue
                    else -> error("microphone read failed: $read")
                }
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
            recorder.release()
        }

        require(written > 0) { "microphone produced no PCM samples" }
        Pcm16WaveEncoder.encodeMono(
            samples = if (written == output.size) output else output.copyOf(written),
            sampleRateHz = SAMPLE_RATE_HZ
        )
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_CAPTURE_MS = 4_000L
        const val MIN_CAPTURE_MS = 500L
        const val MAX_CAPTURE_MS = 12_000L
        private const val READ_BUFFER_SAMPLES = 2_048
    }
}
