package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.OscillatorSlot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capture real API35 hit-test geometry/callback delivery when direct Canvas
 * gestures differ from semantic button/slider actions.
 */
@RunWith(AndroidJUnit4::class)
class OscillatorNoiseHitTestDiagnosticTest {
    @get:Rule val compose = createComposeRule()

    @Test fun physicalCanvasTouchHitsVisibleNodeAndDeliversCallbacks() {
        var slots by mutableStateOf(List(8) {
            if (it == 0) OscillatorSlot(enabled = true, level = .5f)
            else OscillatorSlot()
        })
        var callbackCount = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OscillatorRackPanel(
                        slots = slots,
                        onSlotChange = { index, next ->
                            callbackCount++
                            slots = slots.mapIndexed { i, old -> if (i == index) next else old }
                        },
                    )
                }
            }
        }

        val pad = compose.onNodeWithTag("oscillatorNoiseCanvas").performScrollTo()
        compose.waitForIdle()
        val nodeBounds = pad.fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val visible = pad.isDisplayed()
        assertTrue("Pad clipped after performScrollTo: pad=$nodeBounds root=$rootBounds, " +
            "visible=$visible", visible)
        // 112dp visual artwork must also be a real, finger-sized pointer target;
        // a 0x0 node is an invisible decoration, not a sound-design surface.
        assertTrue("Noise pad must expose nonzero 48dp+ touch geometry: pad=$nodeBounds " +
            "root=$rootBounds", nodeBounds.width > 48f && nodeBounds.height > 48f)
        val pre = callbackCount
        pad.performTouchInput {
            click(Offset(center.x * 1.6f, center.y * .4f))
        }
        compose.waitForIdle()
        val afterOffset = callbackCount
        val offsetColor = slots[0].noiseColor
        val offsetLevel = slots[0].noiseLevel

        // Center should hit even if an edge is clipped; both gestures must
        // independently deliver an editor callback on a visible pad.
        pad.performTouchInput { click(center) }
        compose.waitForIdle()
        val afterCenter = callbackCount
        val centerColor = slots[0].noiseColor
        val centerLevel = slots[0].noiseLevel
        val details = "pad=$nodeBounds root=$rootBounds callback-count=$pre/$afterOffset/" +
            "$afterCenter touch80=($offsetColor,$offsetLevel) center=($centerColor,$centerLevel)"
        assertTrue("Physical edge touch not delivered to Canvas: $details",
            afterOffset > pre)
        assertTrue("Physical center touch not delivered to Canvas: $details",
            afterCenter > afterOffset)
        assertTrue("Canvas semantic hit target must be nonempty: $details",
            nodeBounds.width > 20f && nodeBounds.height > 20f)
    }
}
