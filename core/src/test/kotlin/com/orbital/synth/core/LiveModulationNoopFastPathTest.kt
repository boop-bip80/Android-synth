package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Measured by deterministic worker flags and actual PCM, not wall-clock speed. */
class LiveModulationNoopFastPathTest {
    private fun host() = LiveModulationHost(8_000)

    private val reservedDestinations = arrayOf(
        ModDestination.DRIVE, ModDestination.FX_AMOUNT,
        ModDestination.FILTER_DRIVE, ModDestination.FOLD, ModDestination.LFO_RATE,
    )

    private val wiredDestinations = arrayOf(
        ModDestination.MORPH, ModDestination.PITCH,
        ModDestination.CUTOFF, ModDestination.RESONANCE,
        ModDestination.AMP, ModDestination.PAN,
        ModDestination.OSC1_GAIN, ModDestination.OSC2_GAIN,
        ModDestination.OSC3_GAIN, ModDestination.OSC4_GAIN,
        ModDestination.OSC5_GAIN, ModDestination.OSC6_GAIN,
        ModDestination.OSC7_GAIN, ModDestination.OSC8_GAIN,
        ModDestination.OSC1_PITCH, ModDestination.OSC2_PITCH,
        ModDestination.OSC3_PITCH, ModDestination.OSC4_PITCH,
        ModDestination.OSC5_PITCH, ModDestination.OSC6_PITCH,
        ModDestination.OSC7_PITCH, ModDestination.OSC8_PITCH,
    )

    private fun assertFastPathsOff(m: LiveModulationHost) {
        assertFalse("No effective cable may activate the per-sample matrix", m.active)
        assertFalse("Zero-depth pitch must preserve the constant-pow block path", m.pitchRouteActive)
        assertFalse("No inert cable may retarget the filters", m.filterRouteActive)
        assertFalse(m.ampRouteActive)
        assertFalse(m.panRouteActive)
        assertFalse("No inert oscillator bus may run a per-sample glide", m.oscillatorModulationActive)
        assertEquals(0, m.oscillatorPitchMipMask)
    }

    @Test fun sixtyFourReservedCablesKeepConfigsButNeverWakeAnyDspFastPath() {
        val m = host()
        repeat(64) { slot ->
            val dest = reservedDestinations[slot % reservedDestinations.size]
            val depth = if (slot % 2 == 0) -4f else 4f
            m.setRoute(slot, ModSource.LFO1, dest, depth, ModCurve.EIGHT_STEPS)
        }
        assertFalse(m.syncBlock(.38f, .64f, .15f))
        assertFastPathsOff(m)
        repeat(2_000) { m.tickStepOnly() }
        assertFastPathsOff(m)
        repeat(64) { slot ->
            val saved = m.route(slot)
            assertTrue(saved.enabled)
            assertEquals(reservedDestinations[slot % reservedDestinations.size], saved.destination)
            assertEquals(if (slot % 2 == 0) -4f else 4f, saved.depth, 0f)
            assertEquals(ModCurve.EIGHT_STEPS, saved.curve)
        }
    }

    @Test fun sixtyFourZeroDepthCablesAcrossEveryWiredDestinationStayOff() {
        val m = host()
        repeat(64) { slot ->
            m.setRoute(slot, ModSource.LFO1,
                wiredDestinations[slot % wiredDestinations.size], 0f, ModCurve.SOFT)
        }
        assertFalse(m.syncBlock(.3f, .8f, .16f))
        assertFastPathsOff(m)
        // Changing a formerly inert cable must activate at the very next worker
        // block (no cached flags or stale oscillator masks).
        m.setRoute(10, ModSource.TOUCH_X, ModDestination.PITCH, -.4f, ModCurve.SOFT)
        m.setSource(ModSource.TOUCH_X, 1f)
        assertTrue(m.syncBlock(.3f, .8f, .16f))
        assertTrue(m.pitchRouteActive)
        assertTrue(m.active)
        m.tick()
        assertTrue("Real pitch route must no longer be zero", m.pitchSemitones < -.01f)
        m.setRoute(10, ModSource.TOUCH_X, ModDestination.PITCH, -0.0f, ModCurve.SOFT)
        assertFalse(m.syncBlock(.3f, .8f, .16f))
        assertFastPathsOff(m)
    }

    @Test fun sixtyFourMixedInertCablesRemainBitExactOnLiveHeldStereoNote() {
        val dry = configuredSynth()
        val inert = configuredSynth()
        repeat(64) { slot ->
            val reserved = slot % 2 == 0
            inert.setModulationRoute(
                slot,
                ModSource.MACRO1,
                if (reserved) reservedDestinations[slot % reservedDestinations.size]
                else wiredDestinations[slot % wiredDestinations.size],
                if (reserved) 3.5f else 0f,
            )
        }
        inert.setModulationSource(ModSource.MACRO1, 1f)
        dry.noteOn(64)
        inert.noteOn(64)
        val firstDry = FloatArray(2_048 * 2)
        val firstInert = FloatArray(firstDry.size)
        dry.renderStereo(firstDry, firstDry.size / 2)
        inert.renderStereo(firstInert, firstInert.size / 2)
        assertRawPcmParity(firstDry, firstInert)
        val secondDry = FloatArray(1_024 * 2)
        val secondInert = FloatArray(secondDry.size)
        dry.renderStereo(secondDry, secondDry.size / 2)
        inert.renderStereo(secondInert, secondInert.size / 2)
        assertRawPcmParity(secondDry, secondInert)

        // Real editor changes are still listened to after the no-op fast path.
        inert.setModulationRoute(1, ModSource.MACRO1, ModDestination.MORPH, .65f)
        dry.renderStereo(secondDry, secondDry.size / 2)
        inert.renderStereo(secondInert, secondInert.size / 2)
        var difference = 0.0
        for (i in secondDry.indices) difference +=
            abs((secondDry[i] - secondInert[i]).toDouble())
        assertTrue("Saved route must become audibly active after depth edit", difference > 1.0)
    }

    @Test fun zeroDepthLfoClockStillAdvancesBeforeLiveMidNoteEdit() {
        val m = host()
        m.setLfo(0, true, LfoShape.SAW_UP, 2f, 0f, false)
        m.setRoute(0, ModSource.LFO1, ModDestination.MORPH, 0f, ModCurve.LINEAR)
        assertFalse(m.syncBlock(.5f, .5f, .5f))
        repeat(1_000) { m.tickStepOnly() } // 2 Hz @ 8 kHz: phase exactly 0.25
        m.setRoute(0, ModSource.LFO1, ModDestination.MORPH, .2f, ModCurve.LINEAR)
        assertTrue(m.syncBlock(.5f, .5f, .5f))
        m.tick()
        assertEquals("Dormant cable must keep the real LFO phase, not restart",
            .4f, m.morph, .002f)
        assertFalse(m.pitchRouteActive)
    }

    @Test fun dormantLfoRetainsRetriggerOnNoteAndCurrentEditorSettings() {
        val m = host()
        m.setRoute(0, ModSource.LFO1, ModDestination.MORPH, 0f, ModCurve.LINEAR)
        m.setLfo(0, true, LfoShape.SAW_UP, 2f, 0f, true)
        assertFalse(m.syncBlock(.5f, .5f, .5f))
        repeat(1_000) { m.tickStepOnly() }
        m.onNoteOn() // retriggers just like the formerly active zero-depth route
        m.setRoute(0, ModSource.LFO1, ModDestination.MORPH, .2f, ModCurve.LINEAR)
        assertTrue(m.syncBlock(.5f, .5f, .5f))
        m.tick()
        assertEquals("A note gate must reset the configured dormant SAW_UP to -1",
            .3f, m.morph, .002f)
    }

    @Test fun sampleHoldRngMatchesExactSequentialLfoAfterDormantClockWraps() {
        val m = host()
        val expectedLfo = com.orbital.synth.core.dsp.processing.TempoLfo(
            8_000, 0x18a2f5c + 0x9e37,
        )
        expectedLfo.setShape(LfoShape.SAMPLE_HOLD)
        expectedLfo.setRateHz(3.5f)
        m.setRoute(0, ModSource.LFO1, ModDestination.MORPH, 0f, ModCurve.LINEAR)
        m.setLfo(0, true, LfoShape.SAMPLE_HOLD, 3.5f, 0f, false)
        assertFalse(m.syncBlock(.5f, .5f, .5f))
        repeat(11_933) {
            m.tickStepOnly()
            expectedLfo.next()
        }
        val expectedSample = expectedLfo.next()
        m.setRoute(0, ModSource.LFO1, ModDestination.MORPH, .3f, ModCurve.LINEAR)
        assertTrue(m.syncBlock(.5f, .5f, .5f))
        m.tick()
        assertEquals("Dormant S&H must advance and preserve every deterministic wrap/RNG draw",
            (.5f + expectedSample * .3f).coerceIn(0f, 1f), m.morph, .00001f)
    }

    @Test fun enablingPreviouslyDisabledLfoWhileCableIsDormantIsHonored() {
        val m = host()
        m.setRoute(0, ModSource.LFO2, ModDestination.MORPH, 0f, ModCurve.LINEAR)
        m.setLfo(1, false, LfoShape.SAW_UP, 2f, 0f, false)
        assertFalse(m.syncBlock(.5f, .5f, .5f))
        repeat(1_000) { m.tickStepOnly() }
        m.setLfo(1, true, LfoShape.SAW_UP, 2f, 0f, false)
        assertFalse("Enabling LFO alone must not awaken a zero-depth DSP cable",
            m.syncBlock(.5f, .5f, .5f))
        repeat(1_000) { m.tickStepOnly() }
        m.setRoute(0, ModSource.LFO2, ModDestination.MORPH, .2f, ModCurve.LINEAR)
        assertTrue(m.syncBlock(.5f, .5f, .5f))
        m.tick()
        assertEquals("LFO2 advances only after enabled, even while its cable is dormant",
            .4f, m.morph, .002f)
    }

    @Test fun stepPlayheadContinuesOnBothDormantAndTrulyUnroutedClocks() {
        for (withInertCable in arrayOf(false, true)) {
            val m = host()
            m.setSteps(ModulationStepConfig(
                enabled = true, beatsPerStep = .25f,
                length = 4, glide = 0f, retriggerOnNote = true,
            ))
            if (withInertCable) {
                m.setRoute(0, ModSource.STEP, ModDestination.AMP, 0f, ModCurve.LINEAR)
            }
            assertFalse(m.syncBlock(.5f, .5f, .5f))
            repeat(1_000) { m.tickStepOnly() }
            assertEquals(1, m.stepPlayhead())
            m.advanceSilence(1_000)
            assertEquals(2, m.stepPlayhead())
            m.onNoteOn()
            assertEquals("STEP note epoch must reset regardless of attached cables",
                0, m.stepPlayhead())
        }
    }

    @Test fun removedOscGainAndPitchCablesKeepEightMillisecondDeclickResidual() {
        val m = host()
        m.setSource(ModSource.TOUCH_X, 1f)
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.OSC1_GAIN, 1f, ModCurve.LINEAR)
        m.setRoute(1, ModSource.TOUCH_X, ModDestination.OSC1_PITCH, .5f, ModCurve.LINEAR)
        assertTrue(m.syncBlock(.5f, .5f, .5f))
        assertTrue(m.oscillatorModulationActive)
        assertEquals(1, m.oscillatorPitchMipMask and 1)
        repeat(800) { m.tick() }
        assertTrue(m.oscillatorGainMultipliers[0] > 1.9f)
        assertTrue(m.oscillatorPitchMultipliers[0] > 1.30)
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.OSC1_GAIN, 0f, ModCurve.LINEAR)
        m.setRoute(1, ModSource.TOUCH_X, ModDestination.OSC1_PITCH, 0f, ModCurve.LINEAR)
        assertFalse("Unplugged cables must immediately leave expensive DSP matrix", m.syncBlock(.5f,.5f,.5f))
        assertTrue("But residual oscillator slew must remain alive", m.oscillatorModulationActive)
        assertTrue("Positive pitch mip reservation must survive a declick fade",
            m.oscillatorPitchMipMask and 1 != 0)
        m.tickStepOnly()
        assertTrue("Unplugging must not instantly snap gain", m.oscillatorGainMultipliers[0] > 1f)
        assertTrue(m.oscillatorPitchMultipliers[0] > 1.0)
        repeat(2_000) { m.tickStepOnly() }
        assertEquals(1f, m.oscillatorGainMultipliers[0], 0f)
        assertEquals(1.0, m.oscillatorPitchMultipliers[0], 0.0)
        assertEquals(0, m.oscillatorPitchMipMask)
        assertFalse(m.oscillatorModulationActive)
    }

    private fun configuredSynth(): OrbitalSynth =
        OrbitalSynth(sampleRate = 8_000, maxVoices = 4).apply {
            setParameter(ParameterId.WAVE, .43f)
            setParameter(ParameterId.MORPH, .15f)
            setParameter(ParameterId.CUTOFF, .98f)
            setParameter(ParameterId.RESONANCE, .1f)
            setParameter(ParameterId.MOVEMENT, 0f)
            setParameter(ParameterId.LFO_DEPTH, 0f)
            setParameter(ParameterId.FX_AMOUNT, 0f)
            setParameter(ParameterId.ATTACK, 0f)
            setParameter(ParameterId.SUSTAIN, 1f)
            setParameter(ParameterId.RELEASE, 0f)
        }

    private fun assertRawPcmParity(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) {
            assertEquals("Raw PCM bit mismatch at stereo index " + index,
                java.lang.Float.floatToRawIntBits(expected[index]),
                java.lang.Float.floatToRawIntBits(actual[index]))
        }
    }
}
