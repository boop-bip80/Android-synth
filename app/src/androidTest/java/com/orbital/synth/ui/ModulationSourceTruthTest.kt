package com.orbital.synth.ui

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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    @get:Rule val compose = createComposeRule()

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

        val frame = compose.onNodeWithTag("patchRoute0").captureToImage().toPixelMap()
        assertTrue("real Android framebuffer must render a physical 48dp-wide card",
            frame.width >= 144 && frame.height >= 144)
        var lightPixels = 0
        for (y in 0 until frame.height step 4) for (x in 0 until frame.width step 4) {
            val c = frame[x, y]
            if (c.red + c.green + c.blue > .35f) lightPixels++
        }
        assertTrue("source status and static circuit geometry must be visible", lightPixels > 50)
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
        val graph = compose.onNodeWithTag("modulationConstellationVisual")
        val before = graph.captureToImage().toPixelMap()
        assertTrue("physical graph must be nonempty on 360dp handset",
            before.width >= 800 && before.height >= 300)

        // A real immutable route change alters the actual configured graphic,
        // not a clock-driven fake waveform or invented PCM measurement.
        compose.runOnIdle { route = route.copy(source = ModSource.LFO1) }
        val after = graph.captureToImage().toPixelMap()
        var changed = 0
        for (y in 0 until min(before.height, after.height) step 3)
            for (x in 0 until min(before.width, after.width) step 3)
                if (before[x, y] != after[x, y]) changed++
        assertTrue("actual route source changed but graph stayed identical", changed > 50)

        compose.onNodeWithTag("modulationRouteEditor").performScrollTo()
        compose.onNodeWithTag("modulationConnectionState")
            .assertTextContains("DSP GENERATOR CONNECTED", substring = true)
        compose.runOnIdle { route = route.copy(source = ModSource.ENV1) }
        compose.onNodeWithTag("modulationConnectionState")
            .assertTextContains("UNWIRED SOURCE", substring = true)
        compose.onNodeWithTag("modulationSourceAvailability")
            .assertTextContains("CONFIG ONLY IN THIS APP", substring = true)
        compose.runOnIdle { route = route.copy(source = ModSource.TOUCH_X) }
        compose.onNodeWithTag("modulationConnectionState")
            .assertTextContains("GESTURE-DRIVEN", substring = true)
        compose.runOnIdle { route = route.copy(source = ModSource.LFO1, depth = 0f) }
        compose.onNodeWithTag("modulationConnectionState")
            .assertTextContains("ZERO DEPTH / NO MODULATION", substring = true)

        // TalkBack's actual SetProgress action must invoke the same real host
        // route callback as a finger sliding this bipolar depth fader.
        compose.onNodeWithTag("modulationDepthSlider").performScrollTo()
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
