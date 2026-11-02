package com.orbital.synth.core.music

import org.junit.Assert.*
import org.junit.Test

class MusicalScaleTest {
    @Test fun everyBuiltInScaleIsUniqueAndValid() {
        assertEquals(20, MusicalScales.ALL.size)
        assertEquals(MusicalScales.ALL.size, MusicalScales.ALL.map { it.id }.toSet().size)
        MusicalScales.ALL.forEach { scale ->
            assertSame(scale, MusicalScales.byId(scale.id))
            assertTrue(0 in scale.degrees)
        }
    }

    @Test fun extendedModesRetainTheirDistinctiveIntervals() {
        assertTrue(1 in MusicalScales.PHRYGIAN_DOMINANT.degrees)
        assertTrue(4 in MusicalScales.PHRYGIAN_DOMINANT.degrees)
        assertFalse(3 in MusicalScales.PHRYGIAN_DOMINANT.degrees)
        assertTrue(6 in MusicalScales.LYDIAN_DOMINANT.degrees)
        assertTrue(10 in MusicalScales.LYDIAN_DOMINANT.degrees)
        assertTrue(11 in MusicalScales.MELODIC_MINOR.degrees)
        assertEquals(5, MusicalScales.HIRAJOSHI.degrees.size)
        assertEquals(8, MusicalScales.DIMINISHED_HALF_WHOLE.degrees.size)
        assertEquals(8, MusicalScales.DIMINISHED_WHOLE_HALF.degrees.size)
        for (scale in MusicalScales.ALL) {
            val quantizer = ScaleQuantizer(scale, tonicPitchClass = 7)
            for (note in 0..127) {
                val result = quantizer.quantize(note)
                assertTrue("${scale.id}: $result", quantizer.accepts(result))
            }
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
