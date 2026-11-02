package com.orbital.synth.core.dsp.oscillator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceFilterSlewTest {
    @Test fun liveCutoffJumpBeginsSmoothlyButReachesAudiblyDifferentOutput() {
        val unchanged = configuredVoice(19_000.0)
        val automated = configuredVoice(19_000.0)
        repeat(4_096) {
            unchanged.nextSample(0.61f, 1f, 1.0)
            automated.nextSample(0.61f, 1f, 1.0)
        }

        automated.configure(0.001, 0.1, 1f, 0.3, 110.0, 0.2f)
        val firstExpected = unchanged.nextSample(0.61f, 1f, 1.0)
        val firstAutomated = automated.nextSample(0.61f, 1f, 1.0)
        assertTrue(
            "Updating cutoff should not abruptly replace all filter coefficients",
            abs(firstExpected - firstAutomated) < 0.015f
        )

        var difference = 0.0
        repeat(2_048) {
            difference += abs(
                unchanged.nextSample(0.61f, 1f, 1.0) -
                    automated.nextSample(0.61f, 1f, 1.0)
            )
        }
        assertTrue("Slewed cutoff must eventually affect the sound", difference > 1.0)
    }

    @Test fun inactiveVoiceReceivesTheLatestFilterSettingsWithoutStaleSlew() {
        val reused = Voice(48_000)
        reused.configure(0.001, 0.1, 1f, 0.3, 19_000.0, 0.1f)
        reused.configure(0.001, 0.1, 1f, 0.3, 200.0, 0.8f)
        val fresh = configuredVoice(200.0, 0.8f)
        reused.begin(69, 1f, 1L, null, 0.0, 0.75f, 1f)
        reused.preparePitch(1.0)
        repeat(1_024) {
            assertEquals(
                fresh.nextSample(0.75f, 1f, 1.0),
                reused.nextSample(0.75f, 1f, 1.0), 0f
            )
        }
    }

    @Test fun rapidExtremeCutoffAutomationNeverProducesNonFiniteOutput() {
        val voice = configuredVoice(100.0, 1f)
        repeat(4_096) { frame ->
            if (frame % 31 == 0) {
                val cutoff = if ((frame / 31) % 2 == 0) 20.0 else 20_000.0
                voice.configure(0.001, 0.1, 1f, 0.3, cutoff, 1f)
            }
            val sample = voice.nextSample(1f, 1f, 1.0)
            assertTrue("Non-finite filter sample at frame $frame", sample.isFinite())
            assertTrue("Unbounded filter sample at frame $frame", abs(sample) <= 8.00001f)
        }
    }

    private fun configuredVoice(cutoff: Double, resonance: Float = 0.2f): Voice =
        Voice(48_000).apply {
            configure(0.001, 0.1, 1f, 0.3, cutoff, resonance)
            begin(69, 1f, 1L, null, 0.0, 0.75f, 1f)
            preparePitch(1.0)
        }
}
