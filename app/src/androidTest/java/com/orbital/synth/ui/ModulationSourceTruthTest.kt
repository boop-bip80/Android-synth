package com.orbital.synth.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min

/** Physical API35 pixels, real Compose clicks and TalkBack source truth. */
@RunWith(AndroidJUnit4::class)
class ModulationSourceTruthTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val realFrames by lazy { RealAndroidFrameCapture(compose, compose.activity) }

    /** Reach the actual on-screen slider with bounded physical Android swipes. */
    private fun requirePhysicallyVisible(tag: String) {
        val root = compose.onRoot()
        repeat(7) { attempt ->
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val viewport = root.fetchSemanticsNode().boundsInRoot
            if (bounds.width >= 144f && bounds.height >= 144f &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
            ) return
            if (attempt < 6) root.performTouchInput { swipeUp() }
        }
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        throw AssertionError("Physical 360dp viewport cannot reach " + tag + ": " + bounds)
    }

    private fun accessible(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString(" ")

    @Test
    fun compact360dpFontScale130ShowsTouchableUnwiredAndGestureCableStatuses() {
        var clicked by mutableStateOf(-1)
        val liveLfos = List(8) { ModulationLfoConfig(enabled = true) }
        val unwired = PatchCableTraceModel.describe(0,
            ModulationRouteConfig(true, ModSource.ENV1, ModDestination.CUTOFF, .75f),
            liveLfos, null)
        val gesture = PatchCableTraceModel.describe(1,
            ModulationRouteConfig(true, ModSource.TOUCH_X, ModDestination.RESONANCE, -1f),
            liveLfos, null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1.3f)) {
                MaterialTheme {
                    Column(Modifier.width(360.dp)) {
                        PatchCableTraceCard(unwired, clicked == 0, { clicked = 0 })
                        PatchCableTraceCard(gesture, clicked == 1, { clicked = 1 })
                    }
                }
            }
        }
        for (i in 0..1) {
            val node = compose.onNodeWithTag("patchRoute" + i)
            node.assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("360dp/fontScale1.3 cable must have 48dp touch target",
                bounds.height >= 144f && bounds.width >= 144f)
        }
        assertTrue(accessible("patchRoute0").contains("unwired / config only"))
        assertTrue(accessible("patchRoute1").contains("gesture-driven / touch required"))
        compose.onNodeWithTag("patchRoute1").performClick()
        compose.runOnIdle { assertEquals(1, clicked) }

        // Actual SurfaceFlinger foreground framebuffer, with exact Activity
        // marker proof; never a Compose PixelCopy image.
        realFrames.capture(compose.onNodeWithTag("patchRoute0"), "truthful patch cable").use { frame ->
            assertTrue("real Android framebuffer must render a physical 48dp-wide card",
                frame.width >= 144 && frame.height >= 144)
            var lightPixels = 0
            for (y in 0 until frame.height step 4)
                for (x in 0 until frame.width step 4) {
                    val argb = frame.argbAt(x, y)
                    if (Color.red(argb) + Color.green(argb) + Color.blue(argb) > 89) lightPixels++
                }
            assertTrue("source status and static circuit geometry must be visible",
                lightPixels > 50)
        }
    }

    @Test
    fun actualModulationEditorExplainsSilentSourcesAndKeepsSourceEditingLive() {
        var route by mutableStateOf(
            ModulationRouteConfig(true, ModSource.ENV1, ModDestination.CUTOFF, .75f)
        )
        var lfos by mutableStateOf(List(8) { ModulationLfoConfig(enabled = it == 0) })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1.3f)) {
                MaterialTheme {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        ModulationConstellationPanel(
                            lfos = lfos,
                            routes = List(64) { if (it == 0) route else ModulationRouteConfig() },
                            tempoBpm = 120f,
                            onLfoChange = { index, next ->
                                lfos = lfos.toMutableList().apply { set(index, next) }
                            },
                            onRouteChange = { index, next -> if (index == 0) route = next },
                            onRouteClear = { index ->
                                if (index == 0) route = ModulationRouteConfig()
                            },
                            onTempoChange = {},
                        )
                    }
                }
            }
        }
        assertTrue("Unwired route 1 must not falsely claim audible grid status",
            accessible("modRouteSlot0").contains("UNWIRED SOURCE"))
        assertTrue(accessible("modRouteSlot63").contains("empty"))
        val graph = compose.onNodeWithTag("modulationConstellationVisual")
        val stepPx = 3
        var firstWidth = 0
        var firstHeight = 0
        var firstStride = 0
        lateinit var firstSamples: IntArray
        realFrames.capture(graph, "unwired ENV1 constellation").use { frame ->
            assertTrue("physical graph must be nonempty on 360dp handset",
                frame.width >= 800 && frame.height >= 300)
            firstWidth = frame.width
            firstHeight = frame.height
            firstStride = (frame.width + stepPx - 1) / stepPx
            firstSamples = IntArray(firstStride * ((frame.height + stepPx - 1) / stepPx))
            for (y in 0 until frame.height step stepPx)
                for (x in 0 until frame.width step stepPx)
                    firstSamples[(y / stepPx) * firstStride + x / stepPx] =
                        frame.argbAt(x, y)
        }

        // Control: screenshot the identical UNWIRED/STATIC route twice. Comparing
        // against this same-source animation drift prevents a moving background
        // from falsely proving a route-editor graphic changed.
        var sameSourceDrift = 0
        realFrames.capture(graph, "unchanged ENV1 constellation control").use { frame ->
            assertTrue(frame.width >= 800 && frame.height >= 300)
            for (y in 0 until min(firstHeight, frame.height) step stepPx)
                for (x in 0 until min(firstWidth, frame.width) step stepPx)
                    if (firstSamples[(y / stepPx) * firstStride + x / stepPx] !=
                        frame.argbAt(x, y)) sameSourceDrift++
        }

        // A real immutable route change alters this genuine Android framebuffer:
        // CONFIG cable artwork only, never an invented PCM/FFT measurement.
        compose.runOnIdle { route = route.copy(source = ModSource.LFO1) }
        assertTrue(accessible("modRouteSlot0").contains("DSP GENERATOR CONNECTED"))
        realFrames.capture(graph, "enabled LFO1 constellation").use { frame ->
            assertTrue("physical graph must remain visible after route edit",
                frame.width >= 800 && frame.height >= 300)
            var changed = 0
            for (y in 0 until min(firstHeight, frame.height) step stepPx)
                for (x in 0 until min(firstWidth, frame.width) step stepPx)
                    if (firstSamples[(y / stepPx) * firstStride + x / stepPx] !=
                        frame.argbAt(x, y)) changed++
            assertTrue(
                "Real source edit must change more config pixels than untouched animated drift: " +
                    "changed=$changed drift=$sameSourceDrift",
                changed > sameSourceDrift + 50,
            )
        }

        requirePhysicallyVisible("modulationRouteEditor")
        compose.onNodeWithTag("modulationConnectionState")
            .assertTextContains("DSP GENERATOR CONNECTED", substring = true)
        compose.runOnIdle { route = route.copy(source = ModSource.ENV1) }
        compose.onNodeWithTag("modulationConnectionState")
            .assertTextContains("UNWIRED SOURCE", substring = true)
        assertTrue(accessible("modRouteSlot0").contains("UNWIRED SOURCE"))
        compose.onNodeWithTag("modulationSourceAvailability")
            .assertTextContains("CONFIG ONLY IN THIS APP", substring = true)
        compose.runOnIdle { route = route.copy(source = ModSource.TOUCH_X) }
        compose.onNodeWithTag("modulationConnectionState")
            .assertTextContains("GESTURE-DRIVEN", substring = true)
        assertTrue(accessible("modRouteSlot0").contains("GESTURE-DRIVEN"))
        compose.runOnIdle { route = route.copy(source = ModSource.LFO1, depth = 0f) }
        compose.onNodeWithTag("modulationConnectionState")
            .assertTextContains("ZERO DEPTH / NO MODULATION", substring = true)
        assertTrue(accessible("modRouteSlot0").contains("ZERO DEPTH / NO MODULATION"))

        // TalkBack's actual SetProgress action must invoke the same real host
        // route callback as a finger sliding this bipolar depth fader.
        requirePhysicallyVisible("modulationDepthSlider")
        compose.onNodeWithTag("modulationDepthSlider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle {
            assertEquals(1f, route.depth, .06f)
            assertEquals(ModSource.LFO1, route.source)
        }
        compose.onNodeWithTag("modulationConnectionState")
            .assertTextContains("DSP GENERATOR CONNECTED", substring = true)
    }
}
