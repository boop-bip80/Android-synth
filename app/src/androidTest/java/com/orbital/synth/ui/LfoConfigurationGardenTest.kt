package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class LfoConfigurationGardenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realEightLfoNodeAndShapeSyncTouchesPublishOnlySelectedAtomicSnapshot() {
        var lfos by mutableStateOf(List(8) { index ->
            ModulationLfoConfig(enabled = index == 0, rateHz = 1f)
        })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LfoConfigurationGarden(
                        lfos = lfos, routes = emptyList(), tempoBpm = 120f,
                        onLfoChange = { slot, next ->
                            lfos = lfos.mapIndexed { i, old -> if (i == slot) next else old }
                        },
                        onRouteChange = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lfoGardenNode0").assertHasClickAction()
        compose.onNodeWithTag("lfoGardenNode7").assertHasClickAction()
        compose.onNodeWithTag("lfoGardenNode2").performClick()
        compose.onNodeWithTag("lfoGardenShapeSQUARE").performClick()
        compose.waitForIdle()
        assertEquals(LfoShape.SQUARE, lfos[2].shape)
        assertEquals(LfoShape.SINE, lfos[0].shape)
        compose.onNodeWithTag("lfoGardenSync").performClick()
        compose.waitForIdle()
        assertEquals(2f, lfos[2].beatsPerCycle, .001f)
        compose.onNodeWithTag("lfoGardenFaster").performClick()
        compose.waitForIdle()
        assertEquals(1f, lfos[2].beatsPerCycle, .001f)
        compose.onNodeWithTag("lfoGardenEnabled").performClick()
        compose.waitForIdle()
        assertTrue(lfos[2].enabled)
        assertEquals(0f, lfos[0].beatsPerCycle, .001f)
        assertEquals(1f, lfos[0].rateHz, .001f)
    }

    @Test fun touchingTrueDspTargetDepthEditsRouteEvenWhenLfoSourcePaused() {
        val original = List(64) { slot -> when (slot) {
            0 -> ModulationRouteConfig(true, ModSource.LFO1, ModDestination.CUTOFF, .5f)
            1 -> ModulationRouteConfig(true, ModSource.LFO1, ModDestination.OSC1_GAIN, 2f)
            2 -> ModulationRouteConfig(true, ModSource.LFO2, ModDestination.PAN, -.7f)
            else -> ModulationRouteConfig()
        } }
        var routes by mutableStateOf(original)
        val lfos = List(8) { ModulationLfoConfig(enabled = false) }
        compose.setContent {
            MaterialTheme {
                LfoConfigurationGarden(
                    lfos = lfos, routes = routes, tempoBpm = 120f,
                    onLfoChange = { _, _ -> },
                    onRouteChange = { slot, next ->
                        routes = routes.mapIndexed { i, old -> if (i == slot) next else old }
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lfoGardenEditingCable").assertExists()
        val statusText = compose.onNodeWithTag("lfoGardenCableStatus")
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ")
        assertTrue(statusText.contains("CONNECTED ROUTES"))
        assertTrue(statusText.contains("2 CONFIGURED"))
        // Layer gain is now genuinely wired into DSP alongside CUTOFF.
        // Both routes are editable targets even while the LFO is paused.
        assertTrue(statusText.contains("2 DSP TARGETS"))
        val nodeDescription = compose.onNodeWithTag("lfoGardenNode0")
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            .joinToString(" ")
        assertTrue(nodeDescription.contains("configured DSP-target outgoing cables"))
        compose.onNodeWithTag("lfoGardenCableAudibility").assertExists()
        val routeSlider = compose.onNodeWithTag("lfoGardenRouteDepth")
        routeSlider.performSemanticsAction(SemanticsActions.SetProgress) { it(-1.75f) }
        compose.waitForIdle()
        assertEquals(-1.75f, routes[0].depth, .06f)
        assertEquals(2f, routes[1].depth, .001f)
        assertEquals(-.7f, routes[2].depth, .001f)
        assertEquals(ModDestination.CUTOFF, routes[0].destination)
        compose.onNodeWithTag("lfoGardenNode7").performClick()
        compose.onNodeWithTag("lfoGardenNoLiveCable").assertExists()
    }

    @Test fun api35Compact360dpFontScale130HasActualStaticLuminousPixelsAnd48dpControls() {
        var lfos by mutableStateOf(List(8) { index ->
            ModulationLfoConfig(enabled = index == 0, shape = LfoShape.SINE, rateHz = 1f)
        })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1.3f)) {
                MaterialTheme {
                    LfoConfigurationGarden(
                        lfos = lfos, routes = emptyList(), tempoBpm = 120f,
                        onLfoChange = { slot, next ->
                            lfos = lfos.mapIndexed { i, old -> if (i == slot) next else old }
                        },
                        onRouteChange = { _, _ -> },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        compose.waitForIdle()
        val preview = compose.onNodeWithTag("lfoGardenPreview")
        val description = preview.fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString(" ")
        assertTrue(description.contains("not a live phase or measured PCM"))
        val before = preview.captureToImage().toPixelMap()
        assertTrue("real API35 Canvas must occupy a visible 360dp viewport",
            before.width >= 800 && before.height >= 300)
        var glow = 0
        for (y in 0 until before.height step 4)
            for (x in 0 until before.width step 4) {
                val pixel = before[x, y]
                if (pixel.green > .39f && pixel.blue > .43f) glow++
            }
        assertTrue("real preview failed to draw its configured cyan source trace", glow > 80)
        for (tag in listOf("lfoGardenNode0", "lfoGardenNode7",
                "lfoGardenShapeSINE", "lfoGardenShapeTRIANGLE",
                "lfoGardenSync", "lfoGardenEnabled")) {
            val node = compose.onNodeWithTag(tag)
            node.assertHasClickAction()
            assertTrue("compact 360dp target must remain at least 48dp: " + tag,
                node.fetchSemanticsNode().boundsInRoot.height >= 144f)
        }
        compose.onNodeWithTag("lfoGardenShapeTRIANGLE").performClick()
        compose.waitForIdle()
        assertEquals(LfoShape.TRIANGLE, lfos[0].shape)
        val after = preview.captureToImage().toPixelMap()
        val w = min(before.width, after.width)
        val h = min(before.height, after.height)
        var changed = 0
        for (y in 0 until h step 4) for (x in 0 until w step 4)
            if (before[x, y] != after[x, y]) changed++
        assertTrue("touch callback changed real snapshot but visual config shape stayed stale",
            changed > 100)
    }

    @Test fun sampleHoldClearlyStatesIllustrativeSequenceAndDoesNotInventLiveClock() {
        compose.setContent {
            MaterialTheme {
                LfoConfigurationGarden(
                    lfos = List(8) { ModulationLfoConfig(
                        enabled = true, shape = LfoShape.SAMPLE_HOLD
                    ) },
                    routes = emptyList(), tempoBpm = 120f,
                    onLfoChange = { _, _ -> }, onRouteChange = { _, _ -> },
                )
            }
        }
        compose.waitForIdle()
        val label = compose.onNodeWithTag("lfoGardenTruthLegend")
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ")
        assertTrue(label.contains("illustrative held voltages"))
        val preview = compose.onNodeWithTag("lfoGardenPreview")
        val description = preview.fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString(" ")
        assertTrue(description.contains("not actual random audio values"))
        val one = preview.captureToImage().toPixelMap()
        compose.waitForIdle()
        val two = preview.captureToImage().toPixelMap()
        for (y in 0 until min(one.height, two.height) step 5)
            for (x in 0 until min(one.width, two.width) step 5)
                assertEquals("without audio phase access, a UI animation would be dishonest",
                    one[x, y], two[x, y])
    }
}
