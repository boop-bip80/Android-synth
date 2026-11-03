package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    @get:Rule val compose = createComposeRule()
    private val lfos = List(8) { ModulationLfoConfig(enabled = true) }

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

        // Pixel evidence comes from Android's actual Compose framebuffer. The
        // configuration-derived positive cyan and inverted violet traces must
        // render as two genuinely distinct bitmaps, not just semantics text.
        val forward = compose.onNodeWithTag("patchRoute0").captureToImage().toPixelMap()
        val reversed = compose.onNodeWithTag("patchRoute1").captureToImage().toPixelMap()
        val width = min(forward.width, reversed.width)
        val height = min(forward.height, reversed.height)
        assertTrue("Screenshot captured no actual card pixels", width > 48 && height > 48)
        var differentPixels = 0
        for (y in 0 until height step 3) for (x in 0 until width step 3) {
            if (forward[x, y] != reversed[x, y]) differentPixels++
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
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
        repeat(7) {
            compose.onNodeWithTag("patchRoutePageNext").performScrollTo().performClick()
        }
        compose.onNodeWithTag("patchRoute63").performScrollTo()
        assertTrue(accessibleText(63).contains("LFO8 to PAN"))
        assertTrue(accessibleText(63).contains("depth -2.50×"))
        compose.onNodeWithTag("patchRoute63").performClick()
        compose.onNodeWithTag("patchGraphRouteEditor").performScrollTo()
        compose.onNodeWithTag("patchGraphDepthSlider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-1.5f) }
        compose.runOnIdle {
            assertEquals(-1.5f, bank.value[63].depth, .05f)
            assertEquals(ModDestination.PAN, bank.value[63].destination)
            assertTrue(bank.value.take(63).all { it == ModulationRouteConfig() })
        }
    }
}
