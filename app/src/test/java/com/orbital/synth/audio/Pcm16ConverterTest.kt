package com.orbital.synth.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Pcm16ConverterTest {
    @Test
    fun clipsAndSanitizesEveryNonFiniteValue() {
        val source = floatArrayOf(
            -2f, -1f, -0.5f, 0f, 0.5f, 1f, 2f,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        )
        val target = ShortArray(source.size)
        Pcm16Converter.convert(source, target, source.size)
        assertEquals(Short.MIN_VALUE, target[0])
        assertEquals(Short.MIN_VALUE, target[1])
        assertTrue(target[2] < 0)
        assertEquals(0.toShort(), target[3])
        assertTrue(target[4] > 0)
        assertEquals(Short.MAX_VALUE, target[5])
        assertEquals(Short.MAX_VALUE, target[6])
        assertEquals(0.toShort(), target[7])
        assertEquals(0.toShort(), target[8])
        assertEquals(0.toShort(), target[9])
    }

    @Test
    fun keepsTailUnchangedWhenRenderingShortChunk() {
        val destination = shortArrayOf(55, 55, 55)
        Pcm16Converter.convert(floatArrayOf(0f, 1f, -1f), destination, 2)
        assertEquals(0.toShort(), destination[0])
        assertEquals(Short.MAX_VALUE, destination[1])
        assertEquals(55.toShort(), destination[2])
    }

    @Test
    fun rejectsImpossibleSampleCount() {
        try {
            Pcm16Converter.convert(FloatArray(1), ShortArray(2), 2)
            fail("Expected bounds error")
        } catch (_: IllegalArgumentException) {
            // Destination capacity alone cannot justify reading past source data.
        }
    }
}
