package com.orbital.synth.core.dsp.processing

import org.junit.Assert.*
import org.junit.Test

class TempoLfoTest {
    @Test fun tempoSynchronizationProducesExpectedQuarterCycle() {
        val lfo = TempoLfo(8_000)
        lfo.syncToTempo(120f, 1f) // 2 Hz, 4k frames/cycle
        lfo.setShape(LfoShape.SINE)
        assertEquals(0f, lfo.next(), 1e-5f)
        repeat(999) { lfo.next() }
        assertEquals(1f, lfo.next(), 0.002f)
        repeat(3_000) { lfo.next() }
        assertEquals(0f, lfo.next(), 0.003f)
    }

    @Test fun everyShapeIsFiniteAndBounded() {
        val lfo = TempoLfo(48_000)
        for (shape in LfoShape.entries) {
            lfo.setShape(shape)
            lfo.setRateHz(40f)
            repeat(3_000) {
                val output = lfo.next()
                assertTrue(output.isFinite())
                assertTrue(output in -1f..1f)
            }
        }
    }

    @Test fun randomHoldIsRepeatableForAnIdenticalSeed() {
        val a = TempoLfo(8_000, initialSeed = 42)
        val b = TempoLfo(8_000, initialSeed = 42)
        a.setShape(LfoShape.SAMPLE_HOLD)
        b.setShape(LfoShape.SAMPLE_HOLD)
        a.setRateHz(40f)
        b.setRateHz(40f)
        repeat(2_000) { assertEquals(a.next(), b.next(), 0f) }
    }
}
