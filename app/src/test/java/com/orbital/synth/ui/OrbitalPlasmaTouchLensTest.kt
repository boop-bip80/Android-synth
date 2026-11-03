package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic audible-PCM/touch gating; shader is still verified by API35. */
class OrbitalPlasmaTouchLensTest {
    private fun signal(
        pressed: Boolean,
        rms: Float,
        peak: Float,
    ): OrbitalPlasmaSignal = OrbitalPlasmaSignal.sanitize(
        morph = .50f,
        movement = .63f,
        rms = rms,
        peak = peak,
        touchX = .15f,
        touchY = .82f,
        pressed = pressed,
    )

    @Test fun silentOrAudibleIdleDoesNotInventAContactHighlight() {
        assertEquals(0f, OrbitalPlasmaTouchLens.accent(signal(false, 0f, 0f)), 0f)
        assertEquals(0f, OrbitalPlasmaTouchLens.accent(signal(false, .60f, .97f)), 0f)
    }

    @Test fun realAcceptedPcmBrightensHeldFingerOnlyWithinBoundedRange() {
        val silentTouch = OrbitalPlasmaTouchLens.accent(signal(true, 0f, 0f))
        val voicedTouch = OrbitalPlasmaTouchLens.accent(signal(true, .16f, .27f))
        val loudTouch = OrbitalPlasmaTouchLens.accent(signal(true, 1f, 1f))
        assertEquals(.42f, silentTouch, .00001f)
        assertTrue(voicedTouch > silentTouch)
        assertTrue(loudTouch >= voicedTouch)
        assertTrue(loudTouch <= 1f)
    }

    @Test fun invalidAndNegativeMeterReadingsRemainFiniteAndDoNotAmplify() {
        val corrupted = signal(true, Float.NaN, Float.POSITIVE_INFINITY)
        assertEquals(.42f, OrbitalPlasmaTouchLens.accent(corrupted), .00001f)
        val negative = signal(true, -.7f, -1f)
        assertEquals(.42f, OrbitalPlasmaTouchLens.accent(negative), .00001f)
    }
}
