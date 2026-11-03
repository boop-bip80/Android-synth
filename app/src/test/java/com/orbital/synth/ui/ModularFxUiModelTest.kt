package com.orbital.synth.ui

import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModularFxUiModelTest {
    @Test fun emptySlotsDistinctFromConfiguredAndBypassedModules() {
        val default = FxModuleConfig()
        val chorus = ModularFxUiModel.newModule(FxModuleType.CHORUS)
        assertFalse(ModularFxUiModel.configured(default))
        assertTrue(ModularFxUiModel.configured(chorus))
        assertTrue(ModularFxUiModel.configured(chorus.copy(enabled = false)))
        assertEquals(1, ModularFxUiModel.firstFreeIndex(listOf(chorus.copy(enabled = false), default)))
    }

    @Test fun addingAnyModuleProducesWetAudibleDefaultWithinNormalizedBounds() {
        for (type in FxModuleType.entries) {
            val fx = ModularFxUiModel.newModule(type)
            assertTrue(fx.enabled)
            assertTrue(ModularFxUiModel.configured(fx))
            assertTrue(fx.wet in 0f..1f)
            assertTrue(fx.depth in 0f..1f)
            assertTrue(fx.rate in 0f..1f)
            assertTrue(fx.stereo in 0f..1f)
        }
    }

    @Test fun firstFreeSlotDoesNotOverflowEightProcessorLimit() {
        val active = ModularFxUiModel.newModule(FxModuleType.DELAY)
        assertEquals(-1, ModularFxUiModel.firstFreeIndex(List(8) { active }))
        assertEquals(-1, ModularFxUiModel.firstFreeIndex(List(9) { active }.dropLast(1)))
        assertEquals(6, ModularFxUiModel.firstFreeIndex(List(8) { if (it == 6) FxModuleConfig() else active }))
    }

    @Test fun delayReadoutsMirrorDspLimitsNotImaginedParameters() {
        assertEquals("25 ms", ModularFxUiModel.rateDisplay(FxModuleType.DELAY, 0f))
        assertEquals("1175 ms", ModularFxUiModel.rateDisplay(FxModuleType.DELAY, 1f))
        assertEquals("88%", ModularFxUiModel.depthDisplay(FxModuleType.DELAY, 1f))
        assertEquals("100%", ModularFxUiModel.stereoDisplay(FxModuleType.DELAY, 1f))
    }

    @Test fun tremoloAndChorusRangeMatchActualProcessorMapping() {
        assertEquals("0.05 Hz", ModularFxUiModel.rateDisplay(FxModuleType.TREMOLO, 0f))
        assertEquals("12.80 Hz", ModularFxUiModel.rateDisplay(FxModuleType.TREMOLO, 1f))
        assertEquals("6.05 Hz", ModularFxUiModel.rateDisplay(FxModuleType.CHORUS, 1f))
        assertEquals("180°", ModularFxUiModel.stereoDisplay(FxModuleType.TREMOLO, 1f))
    }

    @Test fun saturationShowsOnlyControlsTheDspActuallyUses() {
        assertFalse(ModularFxUiModel.usesRate(FxModuleType.SATURATION))
        assertFalse(ModularFxUiModel.usesStereo(FxModuleType.SATURATION))
        assertEquals("NOT USED", ModularFxUiModel.rateDisplay(FxModuleType.SATURATION, .75f))
        assertEquals("NOT USED", ModularFxUiModel.stereoDisplay(FxModuleType.SATURATION, .75f))
        assertTrue(ModularFxUiModel.usesRate(FxModuleType.DELAY))
    }

    @Test fun creativeFxNamesAndReadoutsMatchActualBoundedDspRanges() {
        for (type in listOf(
            FxModuleType.REVERB, FxModuleType.PHASER, FxModuleType.BITCRUSH,
        )) {
            assertTrue(ModularFxUiModel.title(type).isNotBlank())
            assertTrue(ModularFxUiModel.shortName(type).isNotBlank())
            assertTrue(ModularFxUiModel.description(type).isNotBlank())
            assertTrue(ModularFxUiModel.usesRate(type))
            assertTrue(ModularFxUiModel.usesStereo(type))
        }
        assertEquals("0.2 s", ModularFxUiModel.depthDisplay(FxModuleType.REVERB, 0f))
        assertEquals("8.0 s", ModularFxUiModel.depthDisplay(FxModuleType.REVERB, 1f))
        assertEquals("100% DARK", ModularFxUiModel.rateDisplay(FxModuleType.REVERB, 1f))
        assertEquals("0.04 Hz", ModularFxUiModel.rateDisplay(FxModuleType.PHASER, 0f))
        assertEquals("180°", ModularFxUiModel.stereoDisplay(FxModuleType.PHASER, 1f))
        assertEquals("16 bits", ModularFxUiModel.depthDisplay(FxModuleType.BITCRUSH, 0f))
        assertEquals("4 bits", ModularFxUiModel.depthDisplay(FxModuleType.BITCRUSH, 1f))
        assertEquals("250 Hz", ModularFxUiModel.rateDisplay(FxModuleType.BITCRUSH, 0f))
        assertEquals("20000 Hz", ModularFxUiModel.rateDisplay(FxModuleType.BITCRUSH, 1f))
    }

    @Test fun nonfiniteAndOvershootingValuesAreClampedForComposeUi() {
        assertEquals(0f, ModularFxUiModel.sanitized(Float.NaN))
        assertEquals(0f, ModularFxUiModel.sanitized(Float.POSITIVE_INFINITY))
        assertEquals(0f, ModularFxUiModel.sanitized(-25f))
        assertEquals(1f, ModularFxUiModel.sanitized(25f))
        assertEquals("0%", ModularFxUiModel.wetDisplay(Float.NaN))
        assertEquals("100%", ModularFxUiModel.wetDisplay(1.8f))
    }
}
