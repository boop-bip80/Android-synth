package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitalPlasmaSignalTest {
    @Test fun untrustedTelemetryAndParametersAreAlwaysFiniteAndBounded() {
        val signal = OrbitalPlasmaSignal.sanitize(
            morph = Float.NaN,
            movement = Float.POSITIVE_INFINITY,
            rms = Float.NEGATIVE_INFINITY,
            peak = 8f,
            touchX = Float.NaN,
            touchY = -5f,
            pressed = false,
        )
        assertEquals(.5f, signal.morph, 0f)
        assertEquals(.5f, signal.movement, 0f)
        assertEquals(0f, signal.rms, 0f)
        assertEquals(1f, signal.peak, 0f)
        assertEquals(.5f, signal.touchX, 0f)
        assertEquals(0f, signal.touchY, 0f)
        assertTrue(signal.isActive)
    }

    @Test fun idleArtIsStaticButRealSoundOrFingerEnablesFrameUpdates() {
        val idle = OrbitalPlasmaSignal.sanitize(.4f, .3f, 0f, 0f, .5f, .5f, false)
        assertFalse(idle.isActive)
        val live = OrbitalPlasmaSignal.sanitize(.4f, .3f, .21f, .43f, .7f, .5f, false)
        assertTrue(live.isActive)
        val finger = OrbitalPlasmaSignal.sanitize(.4f, .3f, 0f, 0f, .7f, .5f, true)
        assertTrue(finger.isActive)
        assertFalse(OrbitalPlasmaSignal.sanitize(
            .4f, .3f, .004f, .011f, .7f, .5f, false,
        ).isActive)
        assertEquals(.21f, live.rms, 0f)
    }
}
