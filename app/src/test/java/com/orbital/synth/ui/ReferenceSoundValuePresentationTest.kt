package com.orbital.synth.ui

import com.orbital.synth.core.ParameterId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceSoundValuePresentationTest {
    @Test fun cutoffUsesSharedRealDspCurveNotOldApproximation() {
        assertEquals("45 Hz", ReferenceSoundValuePresentation
            .format(ParameterId.CUTOFF, 0f, 48_000).visual)
        val mid = ReferenceSoundValuePresentation.format(ParameterId.CUTOFF, .63f, 48_000)
        assertEquals("2.0 kHz", mid.visual)
        assertTrue(mid.spoken.contains("base filter cutoff 2010 hertz"))
        assertTrue(mid.spoken.contains("modulation may change"))
        assertEquals("18.7 kHz", ReferenceSoundValuePresentation
            .format(ParameterId.CUTOFF, 1f, 48_000).visual)
        assertEquals("3.6 kHz", ReferenceSoundValuePresentation
            .format(ParameterId.CUTOFF, 1f, 8_000).visual)
        assertEquals("7.2 kHz", ReferenceSoundValuePresentation
            .format(ParameterId.CUTOFF, 1f, 16_000).visual)
    }

    @Test fun otherFourMacroValuesRemainHonestNormalizedPercentages() {
        for (id in listOf(ParameterId.WAVE, ParameterId.RESONANCE,
                ParameterId.MOVEMENT, ParameterId.FX_AMOUNT)) {
            val info = ReferenceSoundValuePresentation.format(id, .42f, 48_000)
            assertEquals("42%", info.visual)
            assertTrue(info.spoken.contains("42 percent"))
            assertFalse(info.spoken.contains("Hz"))
            assertFalse(info.spoken.contains("dB"))
        }
    }

    @Test fun valuesAreBoundedFiniteAndLocaleIndependent() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("2.0 kHz", ReferenceSoundValuePresentation
                .format(ParameterId.CUTOFF, .63f, 48_000).visual)
            assertEquals("0%", ReferenceSoundValuePresentation
                .format(ParameterId.MOVEMENT, Float.NaN, 48_000).visual)
            assertEquals("0%", ReferenceSoundValuePresentation
                .format(ParameterId.FX_AMOUNT, Float.POSITIVE_INFINITY, 48_000).visual)
            assertEquals("100%", ReferenceSoundValuePresentation
                .format(ParameterId.WAVE, 2f, 48_000).visual)
            assertEquals("0%", ReferenceSoundValuePresentation
                .format(ParameterId.WAVE, -2f, 48_000).visual)
        } finally {
            Locale.setDefault(was)
        }
    }
}
