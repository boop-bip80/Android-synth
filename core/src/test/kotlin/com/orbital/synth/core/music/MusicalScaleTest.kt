package com.orbital.synth.core.music

import org.junit.Assert.*
import org.junit.Test

class MusicalScaleTest {
    @Test fun everyBuiltInScaleIsUniqueAndValid() {
        assertEquals(MusicalScales.ALL.size, MusicalScales.ALL.map { it.id }.toSet().size)
        MusicalScales.ALL.forEach { scale ->
            assertSame(scale, MusicalScales.byId(scale.id))
            assertTrue(0 in scale.degrees)
        }
    }

    @Test fun quantizesToNearestPitchClass() {
        val q = ScaleQuantizer(MusicalScales.MAJOR, tonicPitchClass = 0)
        assertEquals(60, q.quantize(61))
        assertEquals(62, q.quantize(62))
        assertEquals(57, q.quantize(58))
        assertTrue(q.accepts(72))
        assertFalse(q.accepts(73))
    }

    @Test fun tieDirectionIsExplicit() {
        val lower = ScaleQuantizer(MusicalScales.MAJOR, 0, NoteTieBreak.LOWER)
        val higher = ScaleQuantizer(MusicalScales.MAJOR, 0, NoteTieBreak.HIGHER)
        assertEquals(60, lower.quantize(61))
        assertEquals(62, higher.quantize(61))
    }

    @Test fun transpositionAndBoundsAlwaysStayMidiSafe() {
        for (tonic in -24..24) {
            val quantizer = ScaleQuantizer(MusicalScales.BLUES, tonic)
            for (note in -30..160) {
                val result = quantizer.quantize(note)
                assertTrue(result in 0..127)
                assertTrue(quantizer.accepts(result))
            }
        }
    }

    @Test fun chromaticIsIdentityWithinMidiBounds() {
        val q = ScaleQuantizer(MusicalScales.CHROMATIC)
        for (n in 0..127) assertEquals(n, q.quantize(n))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidScaleFailsFast() {
        MusicalScale("bad", "Bad", setOf(1, 3))
    }
}
