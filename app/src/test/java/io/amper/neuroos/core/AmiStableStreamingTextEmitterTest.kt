package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AmiStableStreamingTextEmitterTest {
    @Test
    fun emitsOnlyPrefixStableAcrossConsecutiveDecodes() {
        val emitter = AmiStableStreamingTextEmitter()

        assertNull(emitter.observe("H"))
        assertEquals("H", emitter.observe("He"))
        assertEquals("e", emitter.observe("Hel"))
        assertEquals("llo", emitter.finish("Hello"))
    }

    @Test
    fun unstableReplacementSuffixIsNeverEmitted() {
        val emitter = AmiStableStreamingTextEmitter()

        assertNull(emitter.observe("A�"))
        assertEquals("A", emitter.observe("A€"))
        assertEquals("€", emitter.finish("A€"))
    }

    @Test
    fun finishRejectsAlreadyStreamedPrefixMutation() {
        val emitter = AmiStableStreamingTextEmitter()

        assertNull(emitter.observe("ab"))
        assertEquals("ab", emitter.observe("abc"))

        assertThrows(IllegalArgumentException::class.java) {
            emitter.finish("ax")
        }
    }

    @Test
    fun finishFlushesSingleTokenResponse() {
        val emitter = AmiStableStreamingTextEmitter()

        assertNull(emitter.observe("xin"))
        assertEquals("xin", emitter.finish("xin"))
    }
}
