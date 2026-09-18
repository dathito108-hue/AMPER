package io.amper.neuroos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionCoreTest {
    @Test
    fun silenceAloneNeverProducesUtterance() {
        val assembler = BoundedVoiceUtteranceAssembler()
        val silence = ShortArray(320)

        repeat(200) {
            val result = assembler.feed(silence)
            assertNull(result)
        }

        assertFalse(assembler.isActiveUtterance())
        assertNull(assembler.flushActive())
    }

    @Test
    fun speechThenTrailingSilenceProducesBoundedWav() {
        val assembler = BoundedVoiceUtteranceAssembler()
        val loud = ShortArray(320) { index ->
            if (index % 2 == 0) 8_000 else -8_000
        }
        val silence = ShortArray(320)

        repeat(5) { assertNull(assembler.feed(silence)) }
        repeat(3) { assertNull(assembler.feed(loud)) }

        var completed: EncodedLiveAudio? = null
        repeat(40) {
            if (completed == null) {
                completed = assembler.feed(silence)
            }
        }

        val encoded = requireNotNull(completed)
        assertTrue(encoded.wavBytes.size <= InferenceAttachment.MAX_ATTACHMENT_BYTES)
        assertTrue(encoded.metrics.durationMs in 700L..1_200L)
        assertTrue(encoded.metrics.rms > 0.01)
        assertTrue(
            encoded.wavBytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF"
        )
        assertTrue(
            encoded.wavBytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE"
        )
        assertFalse(assembler.isActiveUtterance())
    }

    @Test
    fun maxUtteranceDurationForcesCompletionWithoutSilence() {
        val assembler = BoundedVoiceUtteranceAssembler(
            maxUtteranceMs = 500
        )
        val loud = ShortArray(320) { 12_000 }

        var completed: EncodedLiveAudio? = null
        repeat(40) {
            if (completed == null) {
                completed = assembler.feed(loud)
            }
        }

        val encoded = requireNotNull(completed)
        assertTrue(encoded.metrics.durationMs in 480L..520L)
        assertFalse(assembler.isActiveUtterance())
    }

    @Test
    fun latestUtteranceStoreCopiesExpiresAndClears() {
        val store = EphemeralVoiceUtteranceStore()
        val encoded = Pcm16WaveEncoder.encodeMono(
            ShortArray(1_600) { 2_000 },
            16_000
        )
        val source = encoded.wavBytes.copyOf()
        store.publish(
            encoded = EncodedLiveAudio(source, encoded.metrics),
            capturedAtEpochMs = 1_000L
        )

        source[0] = 0
        val first = requireNotNull(
            store.latest(maxAgeMs = 1_000L, nowEpochMs = 1_500L)
        )
        assertArrayEquals(encoded.wavBytes, first.wavBytes)

        first.wavBytes[1] = 0
        val second = requireNotNull(
            store.latest(maxAgeMs = 1_000L, nowEpochMs = 1_500L)
        )
        assertArrayEquals(encoded.wavBytes, second.wavBytes)

        assertNull(store.latest(maxAgeMs = 100L, nowEpochMs = 1_101L))
        store.clear()
        assertFalse(store.hasUtterance())
    }
}
