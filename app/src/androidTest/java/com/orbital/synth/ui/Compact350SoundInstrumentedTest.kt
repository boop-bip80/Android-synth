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

    /** Exercise the same physical vertical editor gesture a performer can use. */
    private fun swipeEditorUp() {
        compose.onNodeWithTag("soundEditorScroll").performTouchInput {
            val x = center.x
            down(Offset(x, center.y * 1.7f))
            moveTo(Offset(x, center.y * 1.1f))
            moveTo(Offset(x, center.y * .55f))
            moveTo(Offset(x, center.y * .3f))
            up()
        }
    }

    private fun visible(node: SemanticsNodeInteraction): Boolean =
        try {
            node.assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
        }

    private fun revealByRealVerticalSwipes(node: SemanticsNodeInteraction, label: String) {
        repeat(8) { attempt ->
            if (visible(node)) return
            Log.i(TAG, "phase=vertical-reveal-$label-$attempt")
            swipeEditorUp()
        }
        // Never claim eight layers / ENGINE reachability from assertExists alone.
        node.assertIsDisplayed()
    }

    private fun swipeSelectorRowLeft(rowTag: String) {
        compose.onNodeWithTag(rowTag).assertIsDisplayed().performTouchInput {
            val y = center.y
            down(Offset(center.x * 1.7f, y))
            moveTo(Offset(center.x * .9f, y))
            moveTo(Offset(center.x * .22f, y))
            up()
        }
    }

    private fun revealByRealHorizontalSwipes(
        node: SemanticsNodeInteraction,
        rowTag: String,
        label: String,
    ) {
        repeat(7) { attempt ->
            if (visible(node)) return
            Log.i(TAG, "phase=horizontal-reveal-$label-$attempt")
            swipeSelectorRowLeft(rowTag)
        }
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
        // Actually reveal the selector rail in the tiny weighted editor,
        // then swipe its real horizontal scroll region to reach OSC8.
        // Compose performScrollTo() hung the Android 35 emulator; user touch
        // gestures both preserve the real interaction and prove reachability.
        Log.i(TAG, "phase=before-editor-physical-swipe")
        revealByRealVerticalSwipes(
            compose.onNodeWithTag("oscillatorLayerSelectorRow"), "osc-selector",
        )
        Log.i(TAG, "phase=after-editor-physical-swipe")
        val eighth = compose.onNodeWithTag("oscillatorLayer7")
        revealByRealHorizontalSwipes(
            eighth, "oscillatorLayerSelectorRow", "oscillator-layer-8",
        )
        eighth.assertIsDisplayed().performClick()
        compose.onNodeWithTag("oscillatorLayer7").assertIsSelected()
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
        val engineId = ParameterId.entries.first { it.name == "ENGINE" }
        val parameters = mutableStateOf(mapOf(engineId to 0f))
        // Match the actual host layout: OSC toggle is mounted only with eight
        // slots and an editable callback, and consumes the first 48dp row.
        val slots = mutableStateOf(List(8) { OscillatorSlot() })
        val engineCalls = mutableListOf<Float>()
        val noteOns = mutableListOf<Int>()
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
        compose.onNodeWithTag("compactOscillatorToggle").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed()
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
        revealByRealVerticalSwipes(enginePage, "engine-page")
        enginePage.assertIsDisplayed().performClick()
        Log.i(TAG, "phase=engine-page-open")
        revealByRealVerticalSwipes(
            compose.onNodeWithTag("compactEngineOptionsRow"), "engine-choices",
        )
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
            revealByRealHorizontalSwipes(choice, "compactEngineOptionsRow", label)
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
