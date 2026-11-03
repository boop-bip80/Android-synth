package com.orbital.synth.ui

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchCableTraceModelTest {
    private val activeLfos = List(8) { ModulationLfoConfig(enabled = true) }

    @Test fun positiveAndInvertedDepthHaveExactSignsAndIndependentCyanVioletStates() {
        val positive = PatchCableTraceModel.describe(0,
            ModulationRouteConfig(true, ModSource.LFO1, ModDestination.CUTOFF,
                .75f, ModCurve.EXPONENTIAL), activeLfos, null)
        val inverted = PatchCableTraceModel.describe(63,
            ModulationRouteConfig(true, ModSource.LFO8, ModDestination.PAN,
                -1.25f, ModCurve.SOFT), activeLfos, null)
        assertEquals("01", positive.number)
        assertEquals("64", inverted.number)
        assertEquals(PatchCableTraceStatus.PATCHED, positive.status)
        assertEquals(PatchCableTraceStatus.PATCHED, inverted.status)
        assertEquals(PatchCablePolarity.POSITIVE, positive.polarity)
        assertEquals(PatchCablePolarity.INVERTED, inverted.polarity)
        assertEquals("+0.75×", positive.depthCaption)
        assertEquals("-1.25×", inverted.depthCaption)
        assertTrue(positive.accessibleLabel.contains("LFO1 to CUTOFF"))
        assertTrue(inverted.accessibleLabel.contains("LFO8 to PAN"))
        assertTrue(inverted.accessibleLabel.contains("negative") ||
            inverted.accessibleLabel.contains("inverted"))
        assertTrue(inverted.accessibleLabel.contains("SOFT curve"))
        assertTrue(inverted.accessibleLabel.contains("route 64"))
    }

    @Test fun zeroDepthAndNonfiniteInputAreExplicitlyInert() {
        for (depth in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val trace = PatchCableTraceModel.describe(2,
                ModulationRouteConfig(true, ModSource.LFO2, ModDestination.AMP,
                    depth), activeLfos, null)
            assertEquals(PatchCableTraceStatus.ZERO_DEPTH, trace.status)
            assertEquals(PatchCablePolarity.ZERO, trace.polarity)
            assertEquals(0f, trace.depth, 0f)
            assertEquals(0f, trace.strength, 0f)
            assertTrue(trace.accessibleLabel.contains("no audio"))
        }
    }

    @Test fun reservedDestinationNeverClaimsAudioEvenWhenBypassed() {
        for (enabled in listOf(true, false)) {
            val trace = PatchCableTraceModel.describe(9,
                ModulationRouteConfig(enabled, ModSource.TOUCH_X,
                    ModDestination.LFO_RATE, .9f), activeLfos, null)
            assertEquals(PatchCableTraceStatus.RESERVED, trace.status)
            assertTrue(trace.accessibleLabel.contains("RESERVED") ||
                trace.accessibleLabel.contains("reserved"))
            assertFalse(trace.accessibleLabel.contains("patched / signal dependent"))
        }
    }

    @Test fun disabledRouteAndDisabledStepOrLfoAreNotShownAsGeneratingSignal() {
        val off = PatchCableTraceModel.describe(0,
            ModulationRouteConfig(false, ModSource.LFO1,
                ModDestination.MORPH, 1f), activeLfos, null)
        assertEquals(PatchCableTraceStatus.BYPASSED, off.status)

        val lfoOff = PatchCableTraceModel.describe(1,
            ModulationRouteConfig(true, ModSource.LFO2,
                ModDestination.RESONANCE, 1f),
            activeLfos.toMutableList().apply { set(1, ModulationLfoConfig(enabled = false)) },
            null)
        assertEquals(PatchCableTraceStatus.DORMANT_SOURCE, lfoOff.status)

        val stepOff = PatchCableTraceModel.describe(2,
            ModulationRouteConfig(true, ModSource.STEP,
                ModDestination.AMP, 1f), activeLfos,
            ModulationStepConfig(enabled = false))
        assertEquals(PatchCableTraceStatus.DORMANT_SOURCE, stepOff.status)
        val stepOn = PatchCableTraceModel.describe(2,
            ModulationRouteConfig(true, ModSource.STEP,
                ModDestination.AMP, 1f), activeLfos,
            ModulationStepConfig(enabled = true))
        assertEquals(PatchCableTraceStatus.PATCHED, stepOn.status)
    }

    @Test fun externalTouchPatchIsSignalDependentNotFabricatedLfoReadout() {
        val touch = PatchCableTraceModel.describe(4,
            ModulationRouteConfig(true, ModSource.TOUCH_PRESSURE,
                ModDestination.PITCH, -4f), emptyList(), null)
        assertEquals(PatchCableTraceStatus.PATCHED, touch.status)
        assertEquals(1f, touch.strength, 0f)
        assertTrue(touch.accessibleLabel.contains("TOUCH_PRESSURE"))
        assertTrue(touch.accessibleLabel.contains("signal dependent"))
    }

    @Test fun invalidSlotAndExcessMagnitudeAreValidated() {
        val route = ModulationRouteConfig(true, ModSource.LFO1,
            ModDestination.MORPH, 800f)
        val saturated = PatchCableTraceModel.describe(0, route, activeLfos, null)
        assertEquals(4f, saturated.depth, 0f)
        assertEquals(1f, saturated.strength, 0f)
        assertTrue(runCatching {
            PatchCableTraceModel.describe(-1, route, activeLfos, null)
        }.isFailure)
        assertTrue(runCatching {
            PatchCableTraceModel.describe(64, route, activeLfos, null)
        }.isFailure)
    }
}
