package com.orbital.synth.core.dsp.processing

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TempoStepSequencerTest {
    private val twoSteps = ModulationStepConfig(
        enabled = true, values = listOf(1f, -1f) + List(14) { 0f },
        length = 2, beatsPerStep = .25f, glide = 0f,
    )

    @Test fun strictPatternValidationRejectsMalformedUnboundedOrNonfinitePads() {
        fun invalid(action: () -> Unit) {
            try { action(); fail("Expected malformed pattern rejected") }
            catch (_: IllegalArgumentException) { }
        }
        invalid { twoSteps.copy(values = listOf(1f, 2f)) }
        invalid { twoSteps.copy(values = List(16) { Float.NaN }) }
        invalid { twoSteps.copy(values = List(16) { 1.01f }) }
        invalid { twoSteps.copy(length = 0) }
        invalid { twoSteps.copy(length = 17) }
        invalid { twoSteps.copy(beatsPerStep = 0f) }
        invalid { twoSteps.copy(beatsPerStep = Float.POSITIVE_INFINITY) }
        invalid { twoSteps.copy(glide = -.01f) }
        invalid { twoSteps.copy(glide = 1.01f) }
        assertEquals(-1f, twoSteps.withStep(5, -4f).values[5])
        assertEquals(1f, twoSteps.withStep(5, 4f).values[5])
        assertEquals(0f, twoSteps.values[5])
    }

    @Test fun forwardReversePingPongAndRandomTraverseRealQuantizedBeats() {
        val expected = mapOf(
            StepDirection.FORWARD to listOf(0, 1, 2, 3, 0, 1),
            StepDirection.REVERSE to listOf(3, 2, 1, 0, 3, 2),
            StepDirection.PING_PONG to listOf(0, 1, 2, 3, 2, 1),
        )
        for ((mode, indices) in expected) {
            val sequencer = TempoStepSequencer(8_000)
            sequencer.configure(twoSteps.copy(length = 4, direction = mode))
            sequencer.setTempoBpm(120f)
            val actual = mutableListOf<Int>()
            repeat(indices.size) {
                actual += sequencer.playheadIndex
                sequencer.advance(1_000) // 120 BPM: one sixteenth note every 1000 frames.
            }
            assertEquals("Mode=$mode", indices, actual)
        }
        val randomA = TempoStepSequencer(8_000)
        val randomB = TempoStepSequencer(8_000)
        val config = twoSteps.copy(length = 16, direction = StepDirection.RANDOM)
        randomA.configure(config)
        randomB.configure(config)
        repeat(100) {
            assertEquals(randomA.playheadIndex, randomB.playheadIndex)
            randomA.advance(1_000)
            randomB.advance(1_000)
        }
    }

    @Test fun silentFastForwardAndOneSampleTicksKeepEquivalentPlayheadsAndGlide() {
        val config = twoSteps.copy(glide = .5f, length = 2)
        val sampled = TempoStepSequencer(8_000)
        val skipped = TempoStepSequencer(8_000)
        sampled.configure(config)
        skipped.configure(config)
        sampled.setTempoBpm(120f)
        skipped.setTempoBpm(120f)
        repeat(8_563) { sampled.next() }
        skipped.advance(8_563)
        assertEquals(sampled.playheadIndex, skipped.playheadIndex)
        assertEquals(sampled.next(), skipped.next(), .0001f)
    }

    @Test fun retriggeringIsOptionalAndDisabledStepSourceIsNeutral() {
        val seq = TempoStepSequencer(8_000)
        assertEquals(0f, seq.next(), 0f)
        seq.configure(twoSteps.copy(retriggerOnNote = true))
        seq.advance(1_500)
        assertEquals(1, seq.playheadIndex)
        seq.onNoteOn()
        assertEquals(0, seq.playheadIndex)
        seq.configure(twoSteps.copy(retriggerOnNote = false))
        seq.advance(1_500)
        val before = seq.playheadIndex
        seq.onNoteOn()
        assertEquals(before, seq.playheadIndex)
        seq.configure(twoSteps.copy(enabled = false))
        assertTrue(abs(seq.next()) < .000001f)
    }

    @Test fun tempoChangesRetainPhaseAndGlideStaysWithinBipolarBounds() {
        val seq = TempoStepSequencer(8_000)
        seq.configure(twoSteps.copy(glide = 1f))
        seq.setTempoBpm(120f)
        seq.advance(1_000)
        assertEquals(1, seq.playheadIndex)
        assertEquals(1f, seq.next(), .0001f)
        seq.setTempoBpm(240f)
        repeat(900) {
            val v = seq.next()
            assertTrue(v.isFinite() && v in -1f..1f)
        }
    }
}
