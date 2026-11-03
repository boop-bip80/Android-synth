package com.orbital.synth.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
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
import androidx.compose.ui.test.swipe
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

    private fun visible(node: SemanticsNodeInteraction): Boolean =
        try {
            node.assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
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

    /** Save actual device screen pixels for strict CI extraction, not a Compose mock. */
    private fun captureNativeFrame(filename: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val screenshot = checkNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "Real compact350 API35 framebuffer is required" }
        try {
            assertTrue("Real Android screen must have physical pixels",
                screenshot.width >= 360 && screenshot.height >= 350)
            // A black/blank framebuffer can still be a valid 19KB PNG.
            // Require genuine chromatic neon pixels sampled from the device's
            // actual framebuffer, not merely a file with a PNG signature.
            var chromaticPixels = 0
            for (y in 0 until screenshot.height step 6) {
                for (x in 0 until screenshot.width step 6) {
                    val argb = screenshot.getPixel(x, y)
                    val red = android.graphics.Color.red(argb)
                    val green = android.graphics.Color.green(argb)
                    val blue = android.graphics.Color.blue(argb)
                    val strongest = maxOf(red, green, blue)
                    val weakest = minOf(red, green, blue)
                    if (strongest >= 110 && strongest - weakest >= 35) chromaticPixels++
                }
            }
            assertTrue(
                "Real OSC8 foreground must contain visible colorful synth controls, " +
                    "not a blank/emulator transitional framebuffer: pixels=$chromaticPixels",
                chromaticPixels >= 70,
            )
            val image = File(compose.activity.filesDir, filename)
            FileOutputStream(image).use { out ->
                assertTrue("Real framebuffer PNG encoder failed",
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.fd.sync()
            }
            assertTrue("Native OSC8 screenshot must contain rendered graphics",
                image.length() > 4096L)
            Log.i(TAG, "phase=compact-native-png-$filename-${image.length()}-bytes")
        } finally {
            screenshot.recycle()
        }
    }

    @Test fun shortLandscapeSoundHasReachableEightLayerEditorAndAnchoredPiano() {
        Log.i(TAG, "phase=start")
        val slots = mutableStateOf(
            List(8) { index ->
                if (index == 7) {
                    OscillatorSlot(
                        enabled = true, waveform = .5f, morph = .33f, level = .44f,
                        coarseSemitones = -7, fineCents = 12f,
                        noiseLevel = .76f, noiseColor = .65f,
                    )
                } else OscillatorSlot()
            }
        )
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
        // Expanded short-height mode is a real *page*: the rack's rail
        // is immediately visible, while patch/engine pages temporarily
        // disappear until the permanent OSC toggle collapses the rack.
        compose.onNodeWithTag("compactPatchHeader").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sound rack ENGINE").assertIsDisplayed()
        Log.i(TAG, "phase=before-visible-osc-selector")
        compose.onNodeWithTag("oscillatorLayerSelectorRow").assertIsDisplayed()
        Log.i(TAG, "phase=after-visible-osc-selector")
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
        // Separate native frame verifies the selected OSC8 chip and anchored
        // keybed on the FIRST 350dp screen, before vertically revealing wave.
        captureNativeFrame("orbital-compact350-osc8-selector.png")
        // A compact OSC8 layer is reachable immediately, but its luminous
        // wave field may be below the short editor fold. Scroll ONLY with
        // real finger gestures, never Compose performScrollTo(), which hangs
        // some API35 hardware rendering paths in this weighted viewport.
        val wave = compose.onNodeWithTag("oscillatorWaveCanvas")
        val editor = compose.onNodeWithTag("soundEditorScroll").assertIsDisplayed()
        val density = compose.activity.resources.displayMetrics.density
        var fullyInView = false
        for (attempt in 0 until 12) {
            val editorRect = editor.fetchSemanticsNode().boundsInRoot
            val waveRect = wave.fetchSemanticsNode().boundsInRoot
            fullyInView = visible(wave) &&
                waveRect.height >= 95f * density &&
                waveRect.top >= editorRect.top - 1f &&
                waveRect.bottom <= editorRect.bottom + 1f
            if (fullyInView) break
            // Geometry-directed, bounded, physically timed finger swipe.
            // If an earlier fling overshot above the editor, reverse travel;
            // never invoke the flaky Compose performScrollTo automation.
            val travel = when {
                waveRect.bottom > editorRect.bottom ->
                    -(waveRect.bottom - editorRect.bottom + 6f)
                waveRect.top < editorRect.top ->
                    editorRect.top - waveRect.top + 6f
                else -> -editorRect.height * .30f
            }.coerceIn(-editorRect.height * .45f, editorRect.height * .45f)
            Log.i(TAG, "phase=luminous-physical-reveal-$attempt wave=$waveRect " +
                "editor=$editorRect drag=$travel")
            editor.performTouchInput {
                val start = Offset(3f, center.y - travel * .5f)
                val end = Offset(3f, center.y + travel * .5f)
                swipe(start = start, end = end, durationMillis = 340)
            }
            compose.waitForIdle()
        }
        val waveBounds = wave.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val editorBounds = editor.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "96dp physical waveform must fit within compact editor after real swipes: " +
                "wave=$waveBounds editor=$editorBounds",
            waveBounds.top >= editorBounds.top - 1f &&
                waveBounds.bottom <= editorBounds.bottom + 1f,
        )
        assertTrue(
            "Compact OSC8 waveform must expose physical 96dp touch height",
            waveBounds.height in (95f * density)..(99f * density),
        )
        Log.i(TAG, "phase=compact-luminous-wave-visible bounds=$waveBounds")

        // Finger taps modify BOTH oscillator axes and forward ALL V6 fields,
        // preserving noise color/level, tuning, waveform and enabled state.
        wave.performTouchInput {
            click(Offset((center.x * 2f) * .82f, (center.y * 2f) * .18f))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val edit = slots.value[7]
            assertEquals(.82f, edit.morph, .07f)
            assertEquals(.82f, edit.level, .07f)
            assertEquals(.76f, edit.noiseLevel, 0f)
            assertEquals(.65f, edit.noiseColor, 0f)
            assertEquals(-7, edit.coarseSemitones)
            assertEquals(12f, edit.fineCents, 0f)
            assertEquals(.5f, edit.waveform, 0f)
            assertTrue(edit.enabled)
        }
        Log.i(TAG, "phase=compact-luminous-osc8-xy-pcm-config-updated")

        // Real framebuffer after OSC8 finger gestures, with the wave visible.
        captureNativeFrame("orbital-compact350-luminous.png")

        // Neither physical editor swipes nor touch synthesis can dislodge the
        // live keybed. The permanent OSC toggle remains immediately available.
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed()
        // Collapse back to the real engine and patch controls. All sound
        // parameters and the anchored note keyboard remain intact.
        compose.onNodeWithTag("compactOscillatorToggle")
            .assertIsDisplayed().performClick()
        compose.onNodeWithTag("oscillatorRack").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sound rack ENGINE").assertIsDisplayed()
        compose.onNodeWithTag("compactPatchHeader").assertExists()
        Log.i(TAG, "phase=finished")
    }


    /**
     * Separately proves ENGINE is reachable immediately without a hidden
     * vertical scroll in a 350dp-high viewport, and routes all five real
     * horizontally touched choices into ParameterId.ENGINE while the piano
     * remains pinned.
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
        // Five compact controls must each expose true TalkBack/touch targets
        // at least 48dp high on a real 350dp-constrained Compose screen.
        val density = compose.activity.resources.displayMetrics.density
        val topNavBounds = compose.onNodeWithTag("compactTopNavigation")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Tiny landscape must use a single compact ≥48dp header/nav row",
            topNavBounds.height >= 48f * density - 1f,
        )
        listOf("Tab SOUND", "Tab PLAY", "Tab SEQ", "Tab FX",
            "Enter immersive performance", "Open patch library").forEach { description ->
            val rect = compose.onNodeWithContentDescription(description)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Compact navigation must keep $description at least 48dp in BOTH axes: $rect",
                rect.height >= 48f * density - 1f &&
                    rect.width >= 48f * density - 1f &&
                    rect.top >= topNavBounds.top - 1f &&
                    rect.bottom <= topNavBounds.bottom + 1f,
            )
        }
        val toolbarNames = listOf("LIVE", "TONE", "VOICE", "ENGINE")
        compose.onNodeWithTag("compactOscillatorToggle").assertIsDisplayed().let { chip ->
            val bounds = chip.fetchSemanticsNode().boundsInRoot
            assertTrue("OSC touch target must be at least 48dp", bounds.height >= 48f * density)
        }
        toolbarNames.forEach { pageName ->
            val chip = compose.onNodeWithContentDescription("Sound rack $pageName")
                .assertIsDisplayed()
            assertTrue(
                "$pageName toolbar touch target must be at least 48dp",
                chip.fetchSemanticsNode().boundsInRoot.height >= 48f * density,
            )
        }
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed()
        // The ENGINE tab must be immediately touchable on a real 350dp
        // landscape screen, before any scroll or offscreen semantics query.
        // This is stronger than claiming existence of an unseen rack button.
        Log.i(TAG, "phase=engine-before-visible-page")
        val enginePage = compose.onNodeWithContentDescription("Sound rack ENGINE")
        enginePage.assertIsDisplayed().performClick()
        Log.i(TAG, "phase=engine-page-open")
        val choiceRowBounds = compose.onNodeWithTag("compactEngineOptionsRow")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val editorBounds = compose.onNodeWithTag("soundEditorScroll")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pianoBounds = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        Log.i(TAG, "geometry nav=$topNavBounds editor=$editorBounds " +
            "engineChoices=$choiceRowBounds piano=$pianoBounds")
        assertTrue(
            "Compact editor must remain ABOVE the pinned live 82dp piano: " +
                "editor=$editorBounds piano=$pianoBounds",
            editorBounds.bottom <= pianoBounds.top + 1f,
        )
        assertTrue(
            "Engine option row must provide 48dp touch corridor; row=$choiceRowBounds editor=$editorBounds",
            choiceRowBounds.height >= 48f * density - 1f,
        )
        assertTrue(
            "All engine choices must fit above piano; row=$choiceRowBounds editor=$editorBounds",
            choiceRowBounds.top >= editorBounds.top - 1f &&
                choiceRowBounds.bottom <= editorBounds.bottom + 1f,
        )
        compose.onNodeWithTag("compactPatchHeader").assertExists()
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
