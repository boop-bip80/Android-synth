package com.orbital.synth.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.MainActivity
import com.orbital.synth.core.OrbitalSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production MainActivity wiring regression for the V6 colored-noise workbench.
 *
 * The standalone panel tests only prove a callback publishes a new UI value.
 * This exercises the real Activity and inspects its AudioTrack-owned synth
 * configuration after touch, catching accidental seven-argument setters that
 * silently dropped noiseLevel/noiseColor. Offline LiveNoiseIntegrationTest
 * independently verifies these exact parameters alter real rendered PCM.
 */
@RunWith(AndroidJUnit4::class)
class OscillatorNoiseActivityHostTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun whitePinkBrownControlsReachRealAndroidSynthAndSurviveWaveEdit() {
        compose.onNodeWithTag("oscillatorRack").assertExists()
        val synth = liveActivitySynth()

        compose.onNodeWithTag("oscillatorEnable").performScrollTo().performClick()
        compose.onNodeWithTag("oscillatorNoisePINK").performScrollTo().performClick()
        compose.runOnIdle {
            val slot = synth.oscillatorSlot(0)
            assertTrue("The real audio synth must enable the edited oscillator", slot.enabled)
            assertEquals("Pink chip must reach real synthesis", .5f, slot.noiseColor, .001f)
            assertEquals("Pink chip must set a nonzero live blend", .55f, slot.noiseLevel, .001f)
        }

        // A subsequent legacy wave edit must not silently erase the noise mix.
        compose.onNodeWithTag("oscillatorWave3").performScrollTo().performClick()
        compose.runOnIdle {
            val slot = synth.oscillatorSlot(0)
            assertEquals(.75f, slot.waveform, .001f)
            assertEquals(.5f, slot.noiseColor, .001f)
            assertEquals(.55f, slot.noiseLevel, .001f)
        }

        compose.onNodeWithTag("oscillatorNoiseLevel").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.91f) }
        compose.onNodeWithTag("oscillatorNoiseBROWN").performScrollTo().performClick()
        compose.runOnIdle {
            val slot = synth.oscillatorSlot(0)
            assertTrue("Noise mix slider must control real worker configuration",
                slot.noiseLevel in 0.87f..0.95f)
            assertEquals(1f, slot.noiseColor, .001f)
        }
    }

    private fun liveActivitySynth(): OrbitalSynth {
        // Read-only reflection here avoids shipping a production-only debug hook.
        // This is intentionally the exact synth passed to AndroidAudioOutput.
        val field = MainActivity::class.java.getDeclaredField("synth")
        field.isAccessible = true
        return field.get(compose.activity) as OrbitalSynth
    }
}
