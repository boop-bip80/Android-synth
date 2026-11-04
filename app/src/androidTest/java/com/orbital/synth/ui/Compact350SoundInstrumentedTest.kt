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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.SemanticsActions
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

    /**
     * Reach a real offscreen control in the 104dp-high weighted compact editor
     * using bounded, geometry-directed physical finger swipes only.
     * Returns exact physical renderer bounds, never a test-only fake gesture.
     */
    private fun physicallyRevealWithinCompactEditor(
        tag: String,
        minHeightDp: Float,
    ): Rect {
        val node = compose.onNodeWithTag(tag)
        val editor = compose.onNodeWithTag("soundEditorScroll").assertIsDisplayed()
        val density = compose.activity.resources.displayMetrics.density
        // The first OSC toggle/selector lies ABOVE the editor after deep wave edits;
        // wave and precision rails initially lie BELOW it. When Compose clips
        // offscreen bounds to zero, this remembers which physical way to swipe.
        var lastKnownAbove = tag == "compactOscillatorToggle" ||
            tag == "oscillatorLayerSelectorRow"
        for (attempt in 0 until 12) {
            val editorRect = editor.fetchSemanticsNode().boundsInRoot
            val targetRect = node.fetchSemanticsNode().boundsInRoot
            val fullyInView = visible(node) &&
                targetRect.height >= minHeightDp * density - 1f &&
                targetRect.top >= editorRect.top - 1f &&
                targetRect.bottom <= editorRect.bottom + 1f
            if (fullyInView) break
            val requiredPx = minHeightDp * density
            val hasPhysicalBounds = targetRect.width > 1f && targetRect.height > 1f
            val clippedAtTop = targetRect.top <= editorRect.top + 1f
            val clippedAtBottom = targetRect.bottom >= editorRect.bottom - 1f
            // A physical swipe loses ~12px to touch slop; use at least 30px
            // for a tiny 3–6px shortfall, but never overshoot the 39px window
            // that accommodates the complete 96dp wave in this 350dp layout.
            val fineFingerDrag = (requiredPx - targetRect.height + 14f)
                .coerceIn(30f, editorRect.height * .21f)
            if (hasPhysicalBounds && targetRect.height < requiredPx - 1f) {
                if (clippedAtTop && !clippedAtBottom) lastKnownAbove = true
                if (clippedAtBottom && !clippedAtTop) lastKnownAbove = false
            }
            val travel = when {
                // Offscreen Compose semantics can have Rect(0,0,0,0).
                // Above toggle/selector: finger DOWN. Below wave/rails: UP.
                !hasPhysicalBounds ->
                    if (lastKnownAbove) editorRect.height * .38f
                    else -editorRect.height * .38f
                targetRect.height < requiredPx - 1f && clippedAtTop && !clippedAtBottom ->
                    fineFingerDrag
                targetRect.height < requiredPx - 1f && clippedAtBottom && !clippedAtTop ->
                    -fineFingerDrag
                targetRect.bottom > editorRect.bottom ->
                    -(targetRect.bottom - editorRect.bottom + 14f).coerceAtLeast(30f)
                targetRect.top < editorRect.top ->
                    (editorRect.top - targetRect.top + 14f).coerceAtLeast(30f)
                else ->
                    if (lastKnownAbove) editorRect.height * .20f
                    else -editorRect.height * .20f
            }.coerceIn(-editorRect.height * .45f, editorRect.height * .45f)
            Log.i(TAG, "phase=physical-reveal-$tag-$attempt target=$targetRect " +
                "editor=$editorRect drag=$travel")
            editor.performTouchInput {
                // The 3px panel gutter is outside nested XY gesture owners.
                val start = Offset(3f, center.y - travel * .5f)
                val end = Offset(3f, center.y + travel * .5f)
                swipe(start = start, end = end, durationMillis = 340)
            }
            compose.waitForIdle()
        }
        val targetRect = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val editorRect = editor.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag physical bounds must be completely within actual 350dp editor: " +
                "target=$targetRect editor=$editorRect",
            targetRect.top >= editorRect.top - 1f &&
                targetRect.bottom <= editorRect.bottom + 1f &&
                targetRect.height >= minHeightDp * density - 1f,
        )
        return targetRect
    }

    /** Save actual device screen pixels for strict CI extraction, not a Compose mock. */
    private fun captureNativeFrame(filename: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Selection semantics do not prove SurfaceFlinger has presented pixels.
        // Prove this exact Activity owns the physical foreground first.
        val targetTag = if (filename.contains("osc8-selector")) {
            "oscillatorLayerSelectorRow"
        } else {
            "oscillatorWaveCanvas"
        }
        RealAndroidFrameCapture(compose, compose.activity).capture(
            compose.onNodeWithTag(targetTag), "compact350 foreground $filename",
        ).use { region ->
            assertTrue("Visible OSC target must occupy actual physical framebuffer",
                region.width > 8 && region.height > 8)
        }

        val automation = instrumentation.uiAutomation
        var lastChromaticPixels = -1
        var lastMarkerPixels = -1
        var lastRootPackage: String? = null
        for (attempt in 0 until 6) {
            compose.waitForIdle()
            // A screenshot can precede the next Android compositor frame.
            Thread.sleep(180L)
            val screenshot = automation.takeScreenshot()
            if (screenshot == null) {
                Log.w(TAG, "phase=compact-native-frame-$filename-$attempt-null")
                continue
            }
            try {
                assertTrue("Real Android screen must have physical pixels",
                    screenshot.width >= 360 && screenshot.height >= 350)
                val rootPackage = runCatching {
                    automation.rootInActiveWindow?.packageName?.toString()
                }.getOrNull()
                var chromaticPixels = 0
                var markerPixels = 0
                for (y in 0 until screenshot.height step 6) {
                    for (x in 0 until screenshot.width step 6) {
                        val argb = screenshot.getPixel(x, y)
                        val red = android.graphics.Color.red(argb)
                        val green = android.graphics.Color.green(argb)
                        val blue = android.graphics.Color.blue(argb)
                        val strongest = maxOf(red, green, blue)
                        val weakest = minOf(red, green, blue)
                        if (strongest >= 110 && strongest - weakest >= 35) chromaticPixels++
                        if (x >= screenshot.width * 3 / 4 &&
                            y >= screenshot.height * 3 / 4 &&
                            red >= 245 && green <= 10 && blue >= 245
                        ) markerPixels++
                    }
                }
                lastChromaticPixels = chromaticPixels
                lastMarkerPixels = markerPixels
                lastRootPackage = rootPackage
                val appForeground = rootPackage?.startsWith("com.orbital.synth") == true
                Log.i(TAG, "phase=compact-native-frame-$filename-attempt-$attempt " +
                    "chroma=$chromaticPixels marker=$markerPixels root=$rootPackage " +
                    "real=" + screenshot.width + "x" + screenshot.height)
                if (appForeground && markerPixels <= 3 && chromaticPixels >= 70) {
                    val image = File(compose.activity.filesDir, filename)
                    FileOutputStream(image).use { out ->
                        assertTrue("Real framebuffer PNG encoder failed",
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, out))
                        out.fd.sync()
                    }
                    assertTrue("Native OSC8 screenshot must contain rendered graphics",
                        image.length() > 4096L)
                    Log.i(TAG, "phase=compact-native-png-$filename-" +
                        image.length() + "-bytes")
                    return
                }

                // Preserve the latest *real* failing framebuffer in case
                // all six captures remain transitional/under-saturated.
                val failure = File(
                    compose.activity.filesDir, "orbital-compact350-capture-failure.png",
                )
                FileOutputStream(failure).use { out ->
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.fd.sync()
                }
            } finally {
                screenshot.recycle()
            }
        }
        throw AssertionError(
            "Real OSC8 foreground must contain visible colorful synth controls " +
                "after six genuine frames: chroma=$lastChromaticPixels (required>=70), " +
                "marker=$lastMarkerPixels (required<=3), root=$lastRootPackage",
        )
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
        val waveBounds = physicallyRevealWithinCompactEditor(
            "oscillatorWaveCanvas", minHeightDp = 95f,
        )
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(
            "Compact OSC8 waveform must expose a physical 96dp touch field",
            waveBounds.height in (95f * density)..(99f * density),
        )
        Log.i(TAG, "phase=compact-luminous-wave-visible bounds=$waveBounds")
        val wave = compose.onNodeWithTag("oscillatorWaveCanvas").assertIsDisplayed()

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

        // The 96dp waveform intentionally cannot share this 104dp viewport
        // with two 52dp rails; prove the actual touch rails remain physically
        // reachable after another bounded finger swipe, without collapsing
        // the OSC8 editor or dislodging the permanent piano.
        val railBounds = physicallyRevealWithinCompactEditor(
            "luminousMorphControl", minHeightDp = 48f,
        )
        val levelRail = compose.onNodeWithTag("luminousLevelControl")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Compact level rail still needs a real 48dp TalkBack target",
            levelRail.height >= 48f * density - 1f)
        assertTrue("Both compact controls must occupy the same reachable row",
            kotlin.math.abs(railBounds.top - levelRail.top) <= density)
        compose.onNodeWithTag("luminousMorphControl")
            .assertIsDisplayed().performTouchInput {
                click(Offset((center.x * 2f) * .23f, center.y))
            }
        compose.waitForIdle()
        compose.onNodeWithTag("luminousLevelControl")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.61f) }
        compose.waitForIdle()
        compose.runOnIdle {
            val next = slots.value[7]
            assertEquals("Compact morph rail must transmit real finger X",
                .23f, next.morph, .07f)
            assertEquals("Compact level must honor TalkBack SetProgress",
                .61f, next.level, .005f)
            assertEquals(.76f, next.noiseLevel, 0f)
            assertEquals(.65f, next.noiseColor, 0f)
            assertTrue(next.enabled)
        }
        Log.i(TAG, "phase=compact-luminous-osc8-physical-rails-and-talkback-pass")

        // Neither physical editor swipes nor touch synthesis can dislodge
        // the live keybed. Return UP the editor with real finger gestures;
        // the first-row OSC toggle is reachable again without synthetic scroll.
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed()
        Log.i(TAG, "phase=before-physical-return-to-osc-toggle")
        physicallyRevealWithinCompactEditor(
            "compactOscillatorToggle", minHeightDp = 48f,
        )
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

    /** Require the complete physical 48dp action, not a merely clipped semantics node. */
    private fun assertCompactEditorTouchTarget(
        node: SemanticsNodeInteraction,
        label: String,
    ) {
        val density = compose.activity.resources.displayMetrics.density
        val editor = compose.onNodeWithTag("soundEditorScroll")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bounds = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(
            label + " needs a complete physical 48dp touch target: " + bounds,
            bounds.width >= 48f * density - 1f &&
                bounds.height >= 48f * density - 1f,
        )
        assertTrue(
            label + " must be fully inside the real compact editor: " +
                "target=" + bounds + " editor=" + editor,
            bounds.left >= editor.left - 1f &&
                bounds.right <= editor.right + 1f &&
                bounds.top >= editor.top - 1f &&
                bounds.bottom <= editor.bottom + 1f,
        )
    }

    private fun assertCompactPianoStillPlays(
        attacks: List<Int>,
        releases: List<Int>,
        expectedNotes: Int,
    ) {
        val density = compose.activity.resources.displayMetrics.density
        val editor = compose.onNodeWithTag("soundEditorScroll")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val keyboard = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed()
        val bounds = keyboard.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The real 82dp piano must remain below the scrolling editor: " +
                "piano=" + bounds + " editor=" + editor,
            bounds.height >= 82f * density - 1f &&
                bounds.top >= editor.bottom - 1f,
        )
        keyboard.performTouchInput { down(center); up() }
        compose.runOnIdle {
            assertEquals("Each physical piano touch must emit exactly one note-on",
                expectedNotes, attacks.size)
            assertEquals("Physical note-offs must balance every note-on", attacks, releases)
        }
    }

    /** Changing the visible panel must not reset the selected SOUND page. */
    @Test fun compactSoundPageRetainsSelectionAcrossOscillatorRoundTrips() {
        val slots = mutableStateOf(List(8) { OscillatorSlot() })
        val attacks = mutableListOf<Int>()
        val releases = mutableListOf<Int>()
        compose.setContent {
            Box(Modifier.width(360.dp).height(350.dp)) {
                OrbitalScreen(
                    state = OrbitalUiState(oscillatorSlots = slots.value),
                    onNoteOn = { attacks += it }, onNoteOff = { releases += it },
                    onParameterChange = { _, _ -> },
                    onModeChange = {}, onOctaveChange = {},
                    onOscillatorSlotChange = { index, next ->
                        slots.value = slots.value.toMutableList().apply { set(index, next) }
                    },
                )
            }
        }
        val toggle = compose.onNodeWithTag("compactOscillatorToggle")
        listOf("ENGINE", "VOICE", "TONE").forEachIndexed { index, page ->
            val tab = compose.onNodeWithContentDescription("Sound rack $page")
            assertCompactEditorTouchTarget(tab, page + " selector")
            tab.performTouchInput { down(center); up() }
            tab.assertIsSelected()
            when (page) {
                "ENGINE" -> compose.onNodeWithTag("compactEngineOptionsRow").assertExists()
                "VOICE" -> compose.onNodeWithTag("compactFxBar_UNISON").assertExists()
                "TONE" -> compose.onNodeWithTag("compactFxBar_CUTOFF").assertExists()
            }
            assertCompactEditorTouchTarget(toggle, "Expand OSC")
            toggle.performTouchInput { down(center); up() }
            compose.onNodeWithTag("oscillatorRack").assertExists()
            // The five-action rail stays mounted; selected page controls
            // disappear while OSC is open, rather than remaining hidden twins.
            assertCompactEditorTouchTarget(tab, page + " selector while OSC is open")
            when (page) {
                "ENGINE" -> compose.onNodeWithTag("compactEngineOptionsRow").assertDoesNotExist()
                "VOICE" -> compose.onNodeWithTag("compactFxBar_UNISON").assertDoesNotExist()
                "TONE" -> compose.onNodeWithTag("compactFxBar_CUTOFF").assertDoesNotExist()
            }
            assertCompactEditorTouchTarget(toggle, "Collapse OSC")
            toggle.performTouchInput { down(center); up() }
            compose.onNodeWithTag("oscillatorRack").assertDoesNotExist()
            // Selection and actual control identity survive the round trip.
            tab.assertIsSelected()
            when (page) {
                "ENGINE" -> compose.onNodeWithTag("compactEngineOptionsRow").assertExists()
                "VOICE" -> compose.onNodeWithTag("compactFxBar_UNISON").assertExists()
                "TONE" -> compose.onNodeWithTag("compactFxBar_CUTOFF").assertExists()
            }
            assertCompactPianoStillPlays(attacks, releases, expectedNotes = index + 1)
        }
        Log.i(TAG, "phase=page-round-trips-and-balanced-piano-finished")
    }

    /**
     * Reach both real preset arrows with physical finger scrolling. Require
     * full 48dp targets before clicking; offscreen existence alone is not proof.
     */
    @Test fun compactPatchArrowsReachableByRealVerticalScrollAndPianoRemainsPlayable() {
        val slots = mutableStateOf(List(8) { OscillatorSlot() })
        var previous = 0
        var next = 0
        val attacks = mutableListOf<Int>()
        val releases = mutableListOf<Int>()
        compose.setContent {
            Box(Modifier.width(360.dp).height(350.dp)) {
                OrbitalScreen(
                    state = OrbitalUiState(
                        patchName = "AURORA",
                        oscillatorSlots = slots.value,
                    ),
                    onNoteOn = { attacks += it },
                    onNoteOff = { releases += it },
                    onParameterChange = { _, _ -> },
                    onModeChange = {}, onOctaveChange = {},
                    onPreviousPatch = { previous++ },
                    onNextPatch = { next++ },
                    onOscillatorSlotChange = { index, replacement ->
                        slots.value = slots.value.toMutableList().apply { set(index, replacement) }
                    },
                )
            }
        }
        compose.onNodeWithTag("compactPatchHeader").assertExists()
        // Reuse the bounded geometry-directed real finger-scroll helper.
        // Its 3px outer gutter is outside child parameter drag recognizers.
        physicallyRevealWithinCompactEditor("compactPatchControls", minHeightDp = 48f)
        val previousArrow = compose.onNodeWithContentDescription("Previous factory preset")
        val nextArrow = compose.onNodeWithContentDescription("Next factory preset")
        assertCompactEditorTouchTarget(previousArrow, "Previous preset")
        assertCompactEditorTouchTarget(nextArrow, "Next preset")
        previousArrow.performTouchInput { down(center); up() }
        nextArrow.performTouchInput { down(center); up() }
        compose.runOnIdle {
            assertEquals("Previous preset callback", 1, previous)
            assertEquals("Next preset callback", 1, next)
        }
        assertCompactPianoStillPlays(attacks, releases, expectedNotes = 1)
        Log.i(TAG, "phase=preset-physical-touch-and-balanced-piano-finished")
    }
}
