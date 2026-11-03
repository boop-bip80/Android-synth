package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveLightMathTest {
    private val light = ImmersiveLightMath

    @Test fun allStaticPhotonSeedsAreFiniteDeterministicAndUnderHardCap() {
        assertEquals(48, light.MAX_EMBERS)
        assertEquals(48, light.embers.size)
        assertTrue(light.PETAL_COUNT <= 12)
        assertTrue(light.CONDUIT_STRANDS <= 9)
        assertTrue(light.embers.all {
            it.angle.isFinite() && it.velocity.isFinite() &&
                it.radiusScale.isFinite() && it.phase in 0f..1f &&
                it.hueLane in 0..3
        })
        assertEquals(light.embers.first(), light.embers[0])
        assertTrue(light.embers.map { it.angle }.distinct().size > 45)
    }

    @Test fun silenceYieldsExactlyZeroConduitAndParticleEnergy() {
        assertEquals(0f, light.conduitEnergy(0f, 0f, 0f), 0f)
        assertEquals(0f, light.burstEnergy(0f, 0f), 0f)
        assertEquals(0f, light.burstOpacity(.5f, 0f), 0f)
        assertEquals(0f, light.burstOpacity(1f, 1f), 0f)
        assertEquals(0f, light.emberDisplacement(light.embers.first(), .5f, .5f, 0f), 0f)
    }

    @Test fun actualPcmAndTouchMonotonicallyLiftVisualEnergy() {
        val idle = light.conduitEnergy(0f, 0f, 0f)
        val moderate = light.conduitEnergy(.3f, .2f, .4f)
        val strong = light.conduitEnergy(.7f, .8f, .9f)
        assertTrue(strong > moderate && moderate > idle)
        assertTrue(light.petalFlare(.9f, .8f, .7f) > light.petalFlare(.1f, .2f, .1f))
        assertTrue(light.burstEnergy(.6f, .9f) > light.burstEnergy(0f, .1f))
    }

    @Test fun photonBloomProgressFadesAndExpandsNaturally() {
        val seed = light.embers[3]
        val start = light.emberDisplacement(seed, 0f, 1f, 400f)
        val half = light.emberDisplacement(seed, .5f, 1f, 400f)
        val end = light.emberDisplacement(seed, 1f, 1f, 400f)
        assertTrue(start >= 0f)
        assertTrue(half > start)
        assertTrue(end > half)
        assertTrue(light.burstOpacity(.15f, .9f) > light.burstOpacity(.55f, .9f))
        assertTrue(light.burstOpacity(.55f, .9f) > light.burstOpacity(.9f, .9f))
    }

    @Test fun controlsAndMetersRejectInvalidOrInfiniteValues() {
        assertEquals(0f, light.safeUnit(Float.NaN), 0f)
        assertEquals(0f, light.safeUnit(Float.POSITIVE_INFINITY), 0f)
        assertTrue(light.conduitEnergy(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY) in 0f..1f)
        assertTrue(light.burstEnergy(Float.NaN, Float.POSITIVE_INFINITY) in 0f..1f)
        assertTrue(light.petalFlare(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY) in 0f..1f)
        assertEquals(0f, light.emberDisplacement(light.embers[0], .2f, 1f, Float.NaN), 0f)
        assertTrue(light.embers.all { light.emberDisplacement(it, 1f, 1f, 512f).isFinite() })
    }

    @Test fun brightnessProxyChangesColorBlendWithoutFabricatingFrequencies() {
        val dark = light.chromaBlend(.05f, .5f)
        val bright = light.chromaBlend(.95f, .5f)
        assertTrue(bright > dark)
        assertTrue(light.chromaBlend(Float.NaN, Float.NaN) in 0f..1f)
        assertTrue(light.chromaBlend(1f, 1f) <= 1f)
    }

    @Test fun tinyFingerJitterDoesNotOverfillFixedTraceBuffer() {
        assertFalse(light.shouldRecordTrailPoint(.5f, .5f, .5001f, .5001f))
        assertTrue(light.shouldRecordTrailPoint(.5f, .5f, .7f, .3f))
        assertFalse(light.shouldRecordTrailPoint(.5f, .5f, Float.NaN, .5f))
        assertTrue(light.shouldRecordTrailPoint(Float.NaN, .5f, .5f, .5f))
    }
}
