package com.orbital.synth.core.dsp.oscillator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceAutomationSlewTest {
    @Test fun legatoVelocityChangeDoesNotStepSustainedAmplitude() {
        val unchanged = sustainedVoice()
        val retuned = sustainedVoice()
        repeat(4_096) {
            unchanged.nextSample(0f, 0f, 1.0)
            retuned.nextSample(0f, 0f, 1.0)
        }

        val levelBefore = retuned.envelopeLevel
        // Same pitch isolates the velocity transition from oscillator retuning.
        retuned.begin(69, 0.1f, 2L, null, 0.0, 0f, 0f, legato = true)
        assertEquals("Legato must keep its envelope", levelBefore, retuned.envelopeLevel, 0f)
        val expected = unchanged.nextSample(0f, 0f, 1.0)
        val first = retuned.nextSample(0f, 0f, 1.0)
        assertTrue(
            "Velocity automation jumped on the first sample: ${abs(expected - first)}",
            abs(expected - first) < 0.01f
        )

        var referenceEnergy = 0.0
        var reducedEnergy = 0.0
        repeat(2_400) {
            referenceEnergy += abs(unchanged.nextSample(0f, 0f, 1.0)).toDouble()
            reducedEnergy += abs(retuned.nextSample(0f, 0f, 1.0)).toDouble()
        }
        assertTrue("Legato velocity change must eventually reduce note level",
            reducedEnergy < referenceEnergy * 0.25)
    }

    @Test fun raisingSustainDuringDecayMovesSmoothlyFromCurrentLevel() {
        val voice = Voice(48_000).apply {
            configure(0.001, 2.0, 0.1f, 0.3, 10_000.0, 0.2f)
            begin(69, 1f, 1L, null, 0.0, 0f, 0f)
            preparePitch(1.0)
        }
        repeat(48_000) { voice.nextSample(0f, 0f, 1.0) }
        val before = voice.envelopeLevel
        assertTrue("Test should retarget a partially decayed envelope", before in 0.3f..0.8f)

        voice.configure(0.001, 2.0, 1f, 0.3, 10_000.0, 0.2f)
        voice.nextSample(0f, 0f, 1.0)
        val first = voice.envelopeLevel
        assertTrue("Raising sustain must not jump the envelope", first - before in 0f..0.01f)

        repeat(2_000) { voice.nextSample(0f, 0f, 1.0) }
        assertTrue("The envelope must reach the new sustained level", voice.envelopeLevel > 0.99f)
    }

    @Test fun loweringSustainAfterDecayRemainsSmoothAndReachesTarget() {
        val voice = sustainedVoice()
        repeat(4_096) { voice.nextSample(0f, 0f, 1.0) }
        val before = voice.envelopeLevel
        assertTrue(before > 0.99f)

        voice.configure(0.001, 0.1, 0.15f, 0.3, 18_000.0, 0.2f)
        voice.nextSample(0f, 0f, 1.0)
        assertTrue("Lowering sustain must not step the envelope",
            before - voice.envelopeLevel < 0.01f)
        repeat(2_000) { voice.nextSample(0f, 0f, 1.0) }
        assertTrue("Lower sustain target was not reached",
            abs(voice.envelopeLevel - 0.15f) < 0.005f)
    }

    private fun sustainedVoice(): Voice = Voice(48_000).apply {
        configure(0.001, 0.1, 1f, 0.3, 18_000.0, 0.2f)
        begin(69, 1f, 1L, null, 0.0, 0f, 0f)
        preparePitch(1.0)
    }
}
