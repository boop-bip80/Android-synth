package com.orbital.synth.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveFieldDrawGateTest {
    @Test fun richNormalStageAlwaysRendersRegardlessOfOpacity() {
        for (alpha in listOf(0f, 0.001f, 0.16f, 0.9f, 1f)) {
            assertFalse(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = false, layerAlpha = alpha))
        }
    }

    @Test fun silentSettledCleanCanvasSkipsExpensiveInvisibleArtwork() {
        assertTrue(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = true, layerAlpha = 0f))
        assertTrue(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = true, layerAlpha = .003f))
        assertTrue(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = true, layerAlpha = 1f / 255f))
    }

    @Test fun fadingAudioAndTouchAfterglowStillRendersUntilEffectivelyInvisible() {
        assertFalse(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = true, layerAlpha = 1f))
        assertFalse(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = true, layerAlpha = .5f))
        assertFalse(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = true, layerAlpha = .1f))
        assertFalse(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = true, layerAlpha = .01f))
    }

    @Test fun corruptOpacityFailsQuietlyOnlyInFocus() {
        assertTrue(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = true, layerAlpha = Float.NaN))
        assertTrue(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = true, layerAlpha = Float.POSITIVE_INFINITY))
        assertFalse(ImmersiveFieldDrawGate.shouldSkip(cleanCanvas = false, layerAlpha = Float.NaN))
    }
}
