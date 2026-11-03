package com.orbital.synth.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.orbital.synth.MainActivity
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Captures actual Compose Canvas pixels: the playable chromatic glass must
 * respond to host parameters/PCM without changing note on/off ownership.
 * No timers, artificial FFT samples or non-audio test inputs enter the widget.
 */
class ReferenceKeybedGlassRiverTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun glassRiverPixelsRespondToParametersAndRealOutputMeterWhileKeysRemainPlayable() {
        val morph = mutableFloatStateOf(.12f)
        val movement = mutableFloatStateOf(.18f)
        val acceptedPcmRms = mutableFloatStateOf(0f)
        val attacks = mutableListOf<Int>()
        val releases = mutableListOf<Int>()

        compose.setContent {
            ReferencePerformanceKeybed(
                octave = 4,
                onNoteOn = { attacks += it },
                onNoteOff = { releases += it },
                modifier = Modifier.fillMaxWidth(),
                morph = morph.floatValue,
                movement = movement.floatValue,
                audioRms = acceptedPcmRms.floatValue,
                preferredHeight = 108.dp,
            )
        }
        val keybed = compose.onNodeWithTag("referencePerformanceKeybed")
        compose.waitForIdle()
        val quiet = keybed.captureToImage().toPixelMap()

        // These values simulate new values supplied by the real host. The
        // renderer never generates its own PCM or invents a note.
        compose.runOnUiThread {
            morph.floatValue = .84f
            movement.floatValue = .76f
            acceptedPcmRms.floatValue = .16f
        }
        compose.waitForIdle()
        val expressive = keybed.captureToImage().toPixelMap()

        assertEquals(quiet.width, expressive.width)
        assertEquals(quiet.height, expressive.height)
        var changed = 0
        val firstY = expressive.height / 5
        val lastY = expressive.height * 4 / 5
        for (y in firstY until lastY step 6) {
            for (x in expressive.width / 12 until expressive.width * 11 / 12 step 6) {
                val a = quiet[x, y]
                val b = expressive[x, y]
                val delta = abs(a.red - b.red) + abs(a.green - b.green) +
                    abs(a.blue - b.blue)
                if (delta > .045f) changed++
            }
        }
        assertTrue(
            "Actual chromatic glass pixels did not respond to host parameter/PCM changes",
            changed >= 36,
        )

        keybed.performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle {
            assertEquals("One fresh finger should attack exactly one note", 1, attacks.size)
            assertEquals("Release must match the owned attack", attacks, releases)
        }
    }
}
