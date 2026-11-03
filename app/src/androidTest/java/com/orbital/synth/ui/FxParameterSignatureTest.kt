package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class FxParameterSignatureTest {
    @get:Rule val compose = createComposeRule()

    private fun accessible(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString(" ")

    /** Scroll only when the actual node lies outside the current viewport. */
    private fun ensureVisible(tag: String) {
        val node = compose.onNodeWithTag(tag)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        if (bounds.top < root.top || bounds.bottom > root.bottom) {
            node.performScrollTo()
            compose.waitForIdle()
        }
    }

    @Test fun api35FramebufferRendersDistinctSaturationAndChorusSchematics() {
        val sat = FxModuleConfig(FxModuleType.SATURATION, true, .7f, .85f, .2f, .2f)
        val chorus = FxModuleConfig(FxModuleType.CHORUS, true, .7f, .85f, .8f, .9f)
        compose.setContent {
            MaterialTheme {
                Column {
                    FxParameterSignature(sat, Modifier.fillMaxWidth().height(140.dp),
                        compact = false, tag = "signatureSaturation")
                    FxParameterSignature(chorus, Modifier.fillMaxWidth().height(140.dp),
                        compact = false, tag = "signatureChorus")
                }
            }
        }
        compose.waitForIdle()
        assertTrue(accessible("signatureSaturation").contains("HARMONIC DRIVE"))
        assertTrue(accessible("signatureSaturation").contains("not measured PCM or FFT"))
        assertTrue(accessible("signatureChorus").contains("PRISM CHORUS"))
        assertTrue(accessible("signatureChorus").contains("CHORUS SPEED"))
        val first = compose.onNodeWithTag("signatureSaturation").captureToImage().toPixelMap()
        val second = compose.onNodeWithTag("signatureChorus").captureToImage().toPixelMap()
        val width = min(first.width, second.width)
        val height = min(first.height, second.height)
        assertTrue("Real screenshot must have nonempty geometry", width > 64 && height > 64)
        var different = 0
        for (y in 0 until height step 4) for (x in 0 until width step 4) {
            if (first[x, y] != second[x, y]) different++
        }
        assertTrue("Seven-type renderer did not make distinct effect visuals", different > 100)
    }

    @Test fun constrained360dpFontScale130StillHasReachable48dpBypassAndReorder() {
        var slots by mutableStateOf(listOf(
            FxModuleConfig(FxModuleType.DELAY, true, .6f, .5f, .5f, .5f),
            FxModuleConfig(FxModuleType.CHORUS, true, .6f, .5f, .5f, .5f),
            FxModuleConfig(), FxModuleConfig(), FxModuleConfig(), FxModuleConfig(),
        ))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1.3f)) {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ModularFxWorkbenchPanel(
                            slots = slots,
                            onSlotChange = { index, config ->
                                slots = slots.mapIndexed { i, old ->
                                    if (i == index) config else old
                                }
                            },
                            onMoveSlot = { from, to ->
                                val list = slots.toMutableList()
                                list.add(to, list.removeAt(from))
                                slots = list
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // The miniature is deliberately inside the parent button's semantics
        // and may be clipped by the horizontally scrolling module strip.
        // Assert real TalkBack information, then scroll to the *visible* large
        // signature and inspect the actual Compose root framebuffer.
        val moduleCard = compose.onNodeWithTag("fxSlot0")
        moduleCard.assertHasClickAction()
        assertTrue("Card omitted real FX settings from TalkBack",
            accessible("fxSlot0").contains("configured and enabled") &&
                accessible("fxSlot0").contains("dry wet"))
        ensureVisible("fxSelectedSignature")
        val signature = compose.onNodeWithTag("fxSelectedSignature")
            .fetchSemanticsNode().boundsInRoot
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val darkGlassCapture = compose.onRoot().captureToImage().toPixelMap()
        // Intersect measured on-screen signature bounds with this screenshot.
        // A clipped/offscreen or zero-sized preview fails instead of producing a
        // bogus 0x0 Bitmap or accidentally sampling unrelated luminous text.
        val left = max(0, (signature.left - viewport.left).toInt())
        val right = min(darkGlassCapture.width, (signature.right - viewport.left).toInt())
        val top = max(0, (signature.top - viewport.top).toInt())
        val bottom = min(darkGlassCapture.height, (signature.bottom - viewport.top).toInt())
        assertTrue("360dp signature lacks measured visible framebuffer area",
            right - left > 64 && bottom - top > 64)
        var luminousPixels = 0
        for (y in top until bottom step 4)
            for (x in left until right step 4) {
                val pixel = darkGlassCapture[x, y]
                if (pixel.green > .40f && pixel.blue > .40f) luminousPixels++
            }
        assertTrue("360dp/fontScale1.3 dark glass lost its luminous real FX geometry",
            luminousPixels > 15)
        for (tag in listOf("fxBypassButton", "fxMoveRight")) {
            val node = compose.onNodeWithTag(tag)
            node.assertHasClickAction()
            assertTrue("$tag must have a real 48dp target at 3 px/dp",
                node.fetchSemanticsNode().boundsInRoot.height >= 144f)
        }
        ensureVisible("fxBypassButton")
        compose.onNodeWithTag("fxBypassButton").performClick()
        compose.waitForIdle()
        assertFalse(slots[0].enabled)
        assertEquals(FxModuleType.DELAY, slots[0].type)
        ensureVisible("fxMoveRight")
        compose.onNodeWithTag("fxMoveRight").performClick()
        compose.waitForIdle()
        assertEquals(FxModuleType.DELAY, slots[1].type)
        assertEquals(FxModuleType.CHORUS, slots[0].type)
    }

    @Test fun touchingRealDepthSliderChangesOnlyHostDspConfigAndRenderedSchematic() {
        var slots by mutableStateOf(List(6) { index ->
            if (index == 0) FxModuleConfig(
                FxModuleType.REVERB, true, .68f, .12f, .24f, .61f,
            ) else FxModuleConfig()
        })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModularFxWorkbenchPanel(
                        slots = slots,
                        onSlotChange = { index, value ->
                            slots = slots.mapIndexed { i, old -> if (i == index) value else old }
                        },
                        onMoveSlot = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()
        ensureVisible("fxSelectedSignature")
        val before = compose.onNodeWithTag("fxSelectedSignature").captureToImage().toPixelMap()
        ensureVisible("fxDepthSlider")
        compose.onNodeWithTag("fxDepthSlider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.87f) }
        compose.waitForIdle()
        assertEquals(.87f, slots[0].depth, .05f)
        assertEquals(.68f, slots[0].wet, .001f)
        assertEquals(FxModuleType.REVERB, slots[0].type)
        ensureVisible("fxSelectedSignature")
        val after = compose.onNodeWithTag("fxSelectedSignature").captureToImage().toPixelMap()
        val width = min(before.width, after.width)
        val height = min(before.height, after.height)
        var changed = 0
        for (y in 0 until height step 4) for (x in 0 until width step 4) {
            if (before[x, y] != after[x, y]) changed++
        }
        assertTrue("Depth callback changed DSP config but not parameter geometry", changed > 80)
        assertTrue(accessible("fxSelectedSignature").contains("DECAY"))
    }
}
