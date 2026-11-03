package com.orbital.synth.ui

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A dedicated real-API35 compact-height Compose regression, executed by its own
 * fresh emulator lane. This test remains fully asserted; isolation prevents an
 * unrelated long-running Compose suite from exhausting the emulator process.
 */
@RunWith(AndroidJUnit4::class)
class Compact350SoundInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    companion object { private const val TAG = "OrbitalCompact350" }

    /** Real bounded drag, fail-closed if the control remains offscreen. */
    private fun revealWithPhysicalEditorSwipes(
        node: SemanticsNodeInteraction,
        label: String,
        maxSwipes: Int = 5,
    ) {
        val editor = compose.onNodeWithTag("soundEditorScroll")
        for (attempt in 0 until maxSwipes) {
            if (node.isDisplayed()) return
            val target = node.fetchSemanticsNode().boundsInRoot
            val viewport = editor.fetchSemanticsNode().boundsInRoot
            val towardBottom = target.center.y >= viewport.center.y
            editor.performTouchInput {
                val x = center.x
                val lower = center.y * 1.70f
                val upper = center.y * .30f
                val start = if (towardBottom) lower else upper
                val end = if (towardBottom) upper else lower
                down(Offset(x, start))
                moveTo(Offset(x, (2f * start + end) / 3f))
                moveTo(Offset(x, (start + 2f * end) / 3f))
                moveTo(Offset(x, end))
                up()
            }
            Log.i(TAG, "phase=physical-vertical-" + label + "-attempt-" + attempt)
        }
        // A live performer needs this button onscreen; don't accept offscreen semantics.
        node.assertIsDisplayed()
    }

    @Test fun shortLandscapeSoundHasReachableEightLayerEditorAndAnchoredPiano() {
        Log.i(TAG, "phase=start")
        val slots = mutableStateOf(List(8) { OscillatorSlot() })
        Log.i(TAG, "phase=before-set-content")
        compose.setContent {
            Box(Modifier.width(360.dp).height(350.dp)) {
                OrbitalScreen(
                    state = OrbitalUiState(oscillatorSlots = slots.value),
                    onNoteOn = {}, onNoteOff = {},
                    onParameterChange = { _, _ -> },
                    onModeChange = {}, onOctaveChange = {},
                    onOscillatorSlotChange = { index, next ->
                        slots.value = slots.value.toMutableList().apply { set(index, next) }
                    },
                )
            }
        }
        Log.i(TAG, "phase=before-initial-piano")
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed()
        Log.i(TAG, "phase=before-legacy-keybed-absence")
        compose.onNodeWithTag("referencePerformanceKeybed").assertDoesNotExist()
        // The first child of the compact editor is a 48dp touch action.
        // Assert actual first-screen visibility and inject a physical click.
        // Compose performScrollTo() on this weighted editor repeatedly
        // hung fresh API35 emulators. No synthetic scroll is needed here:
        // the first real action must be visible without scrolling.
        Log.i(TAG, "phase=before-visible-toggle-click")
        compose.onNodeWithTag("compactOscillatorToggle")
            .assertIsDisplayed().performClick()
        Log.i(TAG, "phase=after-visible-toggle-click")
        compose.onNodeWithTag("oscillatorRack").assertExists()
        // Physically scroll the narrow editor, then reach and select the
        // far-right (eighth) oscillator inside a horizontally scrolling row.
        // The original piano assertions stay unchanged below this addition.
        Log.i(TAG, "phase=before-editor-physical-swipe")
        compose.onNodeWithTag("soundEditorScroll").performTouchInput {
            val x = center.x
            down(Offset(x, center.y * 1.7f))
            moveTo(Offset(x, center.y * 1.1f))
            moveTo(Offset(x, center.y * .55f))
            moveTo(Offset(x, center.y * .3f))
            up()
        }
        Log.i(TAG, "phase=after-editor-physical-swipe")
        val firstLayer = compose.onNodeWithTag("oscillatorLayer0")
        revealWithPhysicalEditorSwipes(firstLayer, "osc-row")
        val eighthLayer = compose.onNodeWithTag("oscillatorLayer7")
        val rack = compose.onNodeWithTag("oscillatorRack")
        val rowTouchY = firstLayer.fetchSemanticsNode().boundsInRoot.center.y -
            rack.fetchSemanticsNode().boundsInRoot.top
        var rackSwipes = 0
        while (!eighthLayer.isDisplayed() && rackSwipes < 5) {
            rack.performTouchInput {
                val y = rowTouchY.coerceIn(2f, size.height - 2f)
                down(Offset(size.width * .85f, y))
                moveTo(Offset(size.width * .65f, y))
                moveTo(Offset(size.width * .4f, y))
                moveTo(Offset(size.width * .15f, y))
                up()
            }
            rackSwipes += 1
            Log.i(TAG, "phase=physical-osc-row-swipe-" + rackSwipes)
        }
        eighthLayer.assertIsDisplayed().performClick()
        eighthLayer.assertIsSelected()
        Log.i(TAG, "phase=after-eighth-layer-selected")
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed()
        Log.i(TAG, "phase=finished")
    }


    /**
     * Separately proves the actual compact ENGINE page is reachable by a
     * physical scroll in a 350dp-high viewport and routes all five choices
     * into the real ParameterId.ENGINE callback while piano remains pinned.
     */
    @Test fun shortLandscapeCanSelectAllFiveArchitecturesWithAnchoredPiano() {
        Log.i(TAG, "phase=engine-method-enter")
        val engineId = ParameterId.entries.first { it.name == "ENGINE" }
        val parameters = mutableStateOf(mapOf(engineId to 0f))
        // Match the actual host layout: OSC toggle is mounted only with eight
        // slots and an editable callback, and consumes the first 48dp row.
        val slots = mutableStateOf(List(8) { OscillatorSlot() })
        val engineCalls = mutableListOf<Float>()
        val noteOns = mutableListOf<Int>()
        Log.i(TAG, "phase=engine-before-setContent")
        compose.setContent {
            Box(Modifier.width(360.dp).height(350.dp)) {
                OrbitalScreen(
                    state = OrbitalUiState(
                        parameters = parameters.value,
                        oscillatorSlots = slots.value,
                    ),
                    onNoteOn = { noteOns += it }, onNoteOff = {},
                    onParameterChange = { id, next ->
                        if (id == engineId) {
                            parameters.value = parameters.value + (engineId to next)
                            engineCalls += next
                        }
                    },
                    onModeChange = {}, onOctaveChange = {},
                    onOscillatorSlotChange = { index, next ->
                        slots.value = slots.value.toMutableList().apply { set(index, next) }
                    },
                )
            }
        }
        Log.i(TAG, "phase=engine-after-setContent-before-toggle")
        compose.onNodeWithTag("compactOscillatorToggle").assertIsDisplayed()
        Log.i(TAG, "phase=engine-after-initial-toggle")
        Log.i(TAG, "phase=engine-before-initial-piano-assert")
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed()
        Log.i(TAG, "phase=engine-after-initial-piano-assert")
        Log.i(TAG, "phase=engine-before-physical-swipe")
        compose.onNodeWithTag("soundEditorScroll").performTouchInput {
            val x = center.x
            down(Offset(x, center.y * 1.7f))
            moveTo(Offset(x, center.y * 1.1f))
            moveTo(Offset(x, center.y * .55f))
            moveTo(Offset(x, center.y * .3f))
            up()
        }
        Log.i(TAG, "phase=engine-after-physical-swipe")
        val enginePage = compose.onNodeWithContentDescription("Sound rack ENGINE")
        revealWithPhysicalEditorSwipes(enginePage, "engine-nav")
        enginePage.assertIsDisplayed().performClick()
        Log.i(TAG, "phase=engine-page-open")
        val firstArchitecture = compose.onNodeWithContentDescription(
            "Select synthesis architecture ORBITAL WT",
        )
        revealWithPhysicalEditorSwipes(firstArchitecture, "first-architecture")
        val editor = compose.onNodeWithTag("soundEditorScroll")
        val archRowTouchY = firstArchitecture.fetchSemanticsNode().boundsInRoot.center.y -
            editor.fetchSemanticsNode().boundsInRoot.top
        compose.onAllNodesWithContentDescription(
            "Select synthesis architecture", substring = true,
        ).assertCountEquals(5)

        val architectures = listOf(
            "ORBITAL WT" to 0f,
            "LADDER" to .25f,
            "SEM" to .50f,
            "SIX-OP FM" to .75f,
            "PHASE DIST." to 1f,
        )
        architectures.forEach { (label, expectedValue) ->
            Log.i(TAG, "phase=engine-select-${label.replace(' ', '-')}")
            val choice = compose.onNodeWithContentDescription(
                "Select synthesis architecture $label",
            )
            var architectureSwipes = 0
            while (!choice.isDisplayed() && architectureSwipes < 5) {
                editor.performTouchInput {
                    val y = archRowTouchY.coerceIn(2f, size.height - 2f)
                    down(Offset(size.width * .85f, y))
                    moveTo(Offset(size.width * .65f, y))
                    moveTo(Offset(size.width * .4f, y))
                    moveTo(Offset(size.width * .15f, y))
                    up()
                }
                architectureSwipes += 1
                Log.i(TAG, "phase=physical-engine-row-swipe-" + label + "-" + architectureSwipes)
            }
            choice.assertIsDisplayed().performClick()
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("Engine $label callback", expectedValue,
                    parameters.value[engineId] ?: -1f, .0001f)
            }
        }
        compose.runOnIdle {
            assertEquals("All five engine choices must route to host", 5, engineCalls.size)
            assertEquals(5, engineCalls.distinct().size)
        }
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed()
        // A *real pointer* to the anchored legacy piano must still emit note-on
        // after scrolling the editor and selecting a digital architecture.
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle { assertTrue("Pinned piano lost note ingress", noteOns.isNotEmpty()) }
        Log.i(TAG, "phase=engine-finished")
    }
}
