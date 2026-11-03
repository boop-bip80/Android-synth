package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class PatchCableTraceCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val lfos = List(8) { ModulationLfoConfig(enabled = true) }

    private fun reveal(tag: String, scroll: ScrollState): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        revealByRealFingerSwipes(
            compose, scroll, "patchCableTestViewport", node, tag,
        )
        return node
    }

    /** Real OS frame pixels, promptly recycled before the next route capture. */
    private data class CardPixels(
        val width: Int,
        val height: Int,
        val columns: Int,
        val everyThirdPixel: IntArray,
    ) {
        fun at(x: Int, y: Int): Int =
            everyThirdPixel[(y / 3) * columns + (x / 3)]
    }

    private fun samplePhysicalCard(
        capture: RealAndroidFrameCapture,
        slot: Int,
    ): CardPixels {
        val node = compose.onNodeWithTag("patchRoute" + slot)
        return capture.capture(node, "patch-cable-route-" + slot).use { frame ->
            val columns = (frame.width + 2) / 3
            val rows = (frame.height + 2) / 3
            CardPixels(
                width = frame.width,
                height = frame.height,
                columns = columns,
                everyThirdPixel = IntArray(columns * rows) { index ->
                    frame.argbAt((index % columns) * 3, (index / columns) * 3)
                },
            )
        }
    }

    private fun accessibleText(slot: Int): String =
        compose.onNodeWithTag("patchRoute" + slot).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString(" ")

    @Test fun realCyanAndVioletCableCardsExposePolarityAndAreFullSizeTouchTargets() {
        val selected = mutableIntStateOf(-1)
        val plus = PatchCableTraceModel.describe(0,
            ModulationRouteConfig(true, ModSource.LFO1, ModDestination.CUTOFF,
                1.25f, ModCurve.EXPONENTIAL), lfos, null)
        val minus = PatchCableTraceModel.describe(1,
            ModulationRouteConfig(true, ModSource.LFO2, ModDestination.RESONANCE,
                -1.25f, ModCurve.SOFT), lfos, null)
        compose.setContent {
            MaterialTheme {
                Column {
                    PatchCableTraceCard(plus, selected.intValue == 0,
                        onClick = { selected.intValue = 0 })
                    PatchCableTraceCard(minus, selected.intValue == 1,
                        onClick = { selected.intValue = 1 })
                }
            }
        }
        assertTrue(accessibleText(0).contains("LFO1 to CUTOFF"))
        assertTrue(accessibleText(0).contains("depth +1.25×"))
        assertTrue(accessibleText(0).contains("positive polarity"))
        assertTrue(accessibleText(0).contains("EXPONENTIAL curve"))
        assertTrue(accessibleText(1).contains("LFO2 to RESONANCE"))
        assertTrue(accessibleText(1).contains("depth -1.25×"))
        assertTrue(accessibleText(1).contains("inverted polarity"))
        val targetDensity = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        for (slot in 0..1) {
            val node = compose.onNodeWithTag("patchRoute" + slot)
            node.assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("Route card must exceed 48dp, slot $slot",
                bounds.height >= 48f * targetDensity)
        }
        compose.onNodeWithTag("patchRoute1").performClick()
        compose.runOnIdle { assertEquals(1, selected.intValue) }

        // Physical Android SurfaceFlinger evidence from this exact foreground
        // Activity. Sample every third actual pixel, then promptly recycle the
        // full screenshot before capturing the second route; no PixelCopy.
        // Opposite polarity must remain visibly distinct, not just semantic.
        val realFrames = RealAndroidFrameCapture(compose, compose.activity)
        val forward = samplePhysicalCard(realFrames, 0)
        val reversed = samplePhysicalCard(realFrames, 1)
        val width = min(forward.width, reversed.width)
        val height = min(forward.height, reversed.height)
        assertTrue("Screenshot captured no actual card pixels", width > 48 && height > 48)
        var differentPixels = 0
        for (y in 0 until height step 3) for (x in 0 until width step 3) {
            if (forward.at(x, y) != reversed.at(x, y)) differentPixels++
        }
        assertTrue("Two opposite-polarity cables rendered indistinguishably",
            differentPixels > 30)
    }

    @Test fun disabledAndReservedPathsStayTruthfulToTalkBack() {
        val disabledLfo = PatchCableTraceModel.describe(3,
            ModulationRouteConfig(true, ModSource.LFO1, ModDestination.MORPH, 1f),
            List(8) { ModulationLfoConfig(enabled = false) }, null)
        val reserved = PatchCableTraceModel.describe(4,
            ModulationRouteConfig(true, ModSource.ENV1, ModDestination.LFO_RATE, 1f),
            lfos, null)
        compose.setContent {
            MaterialTheme {
                Column {
                    PatchCableTraceCard(disabledLfo, false, onClick = {})
                    PatchCableTraceCard(reserved, false, onClick = {})
                }
            }
        }
        assertTrue(accessibleText(3).contains("source paused / not generating"))
        assertTrue(accessibleText(4).contains("reserved / no audio"))
    }

    @Test fun paged64CableCardSelectsTheActualEngineSlotAndEditsItsSignedDepth() {
        val bank = mutableStateOf(List(64) { index ->
            if (index == 63) ModulationRouteConfig(
                enabled = true, source = ModSource.LFO8,
                destination = ModDestination.PAN, depth = -2.5f,
            ) else ModulationRouteConfig()
        })
        val scroll = ScrollState(0)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.widthIn(max = 360.dp).fillMaxHeight().testTag("patchCableTestViewport")) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    PatchGraphPanel(
                        oscillators = emptyList(),
                        lfos = lfos,
                        routes = bank.value,
                        fxSlots = emptyList(),
                        onRouteChange = { slot, next ->
                            bank.value = bank.value.toMutableList().apply { set(slot, next) }
                        },
                        onRouteClear = { slot ->
                            bank.value = bank.value.toMutableList().apply {
                                set(slot, ModulationRouteConfig())
                            }
                        },
                        onMoveFxSlot = { _, _ -> },
                    )
                    }
                }
            }
        }
        repeat(7) {
            reveal("patchRoutePageNext", scroll).performClick()
        }
        reveal("patchRoute63", scroll)
        assertTrue(accessibleText(63).contains("LFO8 to PAN"))
        assertTrue(accessibleText(63).contains("depth -2.50×"))
        compose.onNodeWithTag("patchRoute63").performClick()
        reveal("patchGraphDepthSlider", scroll)
        compose.onNodeWithTag("patchGraphDepthSlider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-1.5f) }
        compose.runOnIdle {
            assertEquals(-1.5f, bank.value[63].depth, .05f)
            assertEquals(ModDestination.PAN, bank.value[63].destination)
            assertTrue(bank.value.take(63).all { it == ModulationRouteConfig() })
        }
    }
}
