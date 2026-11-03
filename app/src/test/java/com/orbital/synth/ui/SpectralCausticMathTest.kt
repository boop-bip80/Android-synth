package com.orbital.synth.ui

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectralCausticMathTest {
    @Test fun silentOrCorruptPcmCannotCreateASpectralVortex() {
        assertEquals(0f, SpectralCausticMath.glowStrength(0f), 0f)
        assertEquals(0f, SpectralCausticMath.glowStrength(Float.NaN), 0f)
        assertEquals(0f, SpectralCausticMath.glowStrength(Float.NEGATIVE_INFINITY), 0f)
        assertTrue(SpectralCausticMath.glowStrength(Float.POSITIVE_INFINITY) <= 1f)
        assertEquals(0f, SpectralCausticMath.halfWidth(-1, .8f), 0f)
        assertEquals(0f, SpectralCausticMath.radialScale(
            lane = 3, angle = 0f, phase = 0f, morph = .5f,
            movement = .5f, band = .8f,
        ), 0f)
    }

    @Test fun equallyLoudAcceptedPcmBandsGiveDistinctVolumetricGeometry() {
        val turn = (2.0 * PI).toFloat()
        var lowMidDifference = 0f
        var midHighDifference = 0f
        repeat(SpectralCausticMath.POINTS) { index ->
            val angle = turn * index / SpectralCausticMath.POINTS
            val low = SpectralCausticMath.radialScale(
                lane = 0, angle = angle, phase = .23f, morph = .68f,
                movement = .47f, band = .8f,
            )
            val mid = SpectralCausticMath.radialScale(
                lane = 1, angle = angle, phase = .23f, morph = .68f,
                movement = .47f, band = .8f,
            )
            val high = SpectralCausticMath.radialScale(
                lane = 2, angle = angle, phase = .23f, morph = .68f,
                movement = .47f, band = .8f,
            )
            lowMidDifference += abs(low - mid)
            midHighDifference += abs(mid - high)
            assertTrue("low=$low mid=$mid high=$high",
                low in .39f..1.20f && mid in .39f..1.20f && high in .39f..1.20f)
        }
        assertTrue("Low vs mid should be spatially distinct", lowMidDifference > 4f)
        assertTrue("Mid vs high should be spatially distinct", midHighDifference > 4f)
        assertTrue("Gold bass glow is volumetrically wider than cyan treble",
            SpectralCausticMath.halfWidth(0, .8f) >
                SpectralCausticMath.halfWidth(2, .8f))
    }

    @Test fun changingRealMacroAndAnimationPhaseBendsExistingFilaments() {
        var changed = 0
        val turn = (2.0 * PI).toFloat()
        repeat(SpectralCausticMath.POINTS) { index ->
            val angle = turn * index / SpectralCausticMath.POINTS
            val initial = SpectralCausticMath.radialScale(
                1, angle, .1f, .26f, .39f, .7f)
            val moved = SpectralCausticMath.radialScale(
                1, angle, .8f, .74f, .82f, .7f)
            if (abs(initial - moved) > .012f) changed++
        }
        assertTrue("Actual morph/movement/phase must visibly alter radii: $changed",
            changed >= SpectralCausticMath.POINTS / 2)
    }

    @Test fun allGeometryIsFiniteAndBoundedUnderBadTelemetry() {
        for (lane in 0..2) {
            for (angle in listOf(Float.NaN, Float.POSITIVE_INFINITY, -100f, 0f, 200f)) {
                val r = SpectralCausticMath.radialScale(
                    lane, angle, Float.POSITIVE_INFINITY, Float.NaN,
                    Float.NEGATIVE_INFINITY, Float.NaN)
                assertTrue("radius $lane/$angle => $r", r.isFinite() && r in .39f..1.20f)
                val halfWidth = SpectralCausticMath.halfWidth(lane, Float.NaN)
                assertTrue("half width $halfWidth", halfWidth.isFinite() && halfWidth > 0f)
            }
        }
    }
}
