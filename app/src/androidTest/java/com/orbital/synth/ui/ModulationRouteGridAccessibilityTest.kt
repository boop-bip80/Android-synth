package com.orbital.synth.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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

/** Android foreground hardware pixels + true physical finger swipes at 1.3x fonts. */
@RunWith(AndroidJUnit4::class)
class ModulationRouteGridAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val realFrames by lazy { RealAndroidFrameCapture(compose, compose.activity) }

    /**
     * Strictly real swipes in the visible 350/360dp viewport. Do not use
     * programmatic Compose scroll calls: heavyweight graphs hang Swangle.
     * Zero-size clipped child bounds are not misread as "above"; the worker's
     * read-only scroll state tells us when to reverse away from the bottom.
     */
    private fun revealByPhysicalSwipes(
        tag: String,
        scroll: ScrollState,
        minTouchDp: Float = 48f,
    ): Rect {
        val viewport = compose.onNodeWithTag("modGridViewport")
        val node = compose.onNodeWithTag(tag)
        val requiredPx = minTouchDp * 3f
        repeat(23) { attempt ->
            compose.waitForIdle()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val visible = viewport.fetchSemanticsNode().boundsInRoot
            val fullyVisible = bounds.width >= requiredPx - 1f &&
                bounds.height >= requiredPx - 1f &&
                bounds.left >= visible.left - 1f &&
                bounds.right <= visible.right + 1f &&
                bounds.top >= visible.top - 1f &&
                bounds.bottom <= visible.bottom + 1f
            if (fullyVisible) return bounds

            if (attempt == 22) throw AssertionError(
                "Could not reach $tag after 22 actual finger swipes: node=$bounds " +
                    "viewport=$visible scroll=${scroll.value}/${scroll.maxValue}",
            )
            // A *partially* clipped 48dp button also needs opposite travel.
            val below = bounds.height > 0f && bounds.bottom > visible.bottom
            val above = bounds.height > 0f && bounds.top < visible.top
            val reachedEnd = scroll.maxValue > 0 && scroll.value >= scroll.maxValue - 1
            val swipeDown = above || ((bounds.width <= 0f || bounds.height <= 0f) && reachedEnd && !below)
            viewport.performTouchInput {
                val x = 4f // Quiet panel gutter; no nested XY slider captures drag.
                val startY = visible.height * if (swipeDown) .25f else .78f
                val endY = visible.height * if (swipeDown) .78f else .25f
                swipe(
                    start = Offset(x, startY),
                    end = Offset(x, endY),
                    durationMillis = 380L,
                )
            }
        }
        error("Unreachable: loop must return or assert")
    }

    private fun exerciseViewport(widthDp: Float) {
        // Pixel6 physical host width >=360dp, fixture intentionally constrains
        // to real phone-sized 350/360dp with 1.3x Android font scale.
        val scroll = ScrollState(0)
        var routes by mutableStateOf(List(64) { index ->
            if (index == 63) {
                ModulationRouteConfig(
                    enabled = true,
                    source = ModSource.TOUCH_X, // gesture only; no fake background LFO
                    destination = ModDestination.CUTOFF,
                    depth = -.25f,
                )
            } else ModulationRouteConfig()
        })
        val callbackIndices = mutableListOf<Int>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1.3f)) {
                MaterialTheme {
                    Box(Modifier.width(widthDp.dp).height(widthDp.dp)
                        .testTag("modGridViewport")) {
                        Column(Modifier.verticalScroll(scroll)) {
                            ModulationConstellationPanel(
                                lfos = List(8) { ModulationLfoConfig(enabled = false) },
                                routes = routes,
                                tempoBpm = 120f,
                                onLfoChange = { _, _ -> },
                                onRouteChange = { index, value ->
                                    callbackIndices += index
                                    routes = routes.toMutableList().apply { set(index, value) }
                                },
                                onRouteClear = { index ->
                                    callbackIndices += index
                                    routes = routes.toMutableList().apply {
                                        set(index, ModulationRouteConfig())
                                    }
                                },
                                onTempoChange = {},
                            )
                        }
                    }
                }
            }
        }
        // Verify all 64 IDs survived adaptive row-major layout. Android sees
        // last node even when currently clipped, but tapping requires visibility.
        compose.onNodeWithTag("modRouteSlot0").assertHasClickAction()
        val last = compose.onNodeWithTag("modRouteSlot63")
        last.assertHasClickAction()
        val physical = revealByPhysicalSwipes("modRouteSlot63", scroll)
        assertTrue("Route64 tappable area must occupy >=48dp at width $widthDp",
            physical.width >= 144f && physical.height >= 144f)
        assertTrue("Saved gesture route is explicit, never called audible",
            last.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
                .joinToString(" ").contains("GESTURE-DRIVEN"))

        // A REAL finger click of route64, not a semantics click/virtual scroll.
        last.performTouchInput { click() }
        compose.onNodeWithTag("modRouteSlot63").assertIsSelected()
        compose.onNodeWithText("ROUTE 64").assertExists()

        // OS framebuffer of this very Activity: selected gold border + text
        // must physically exist; no Compose PixelCopy or fake canvas image.
        realFrames.capture(last, "selected modulation route $widthDp dp").use { frame ->
            assertTrue(frame.width >= 144 && frame.height >= 144)
            var bright = 0
            for (y in 0 until frame.height step 3)
                for (x in 0 until frame.width step 3) {
                    val color = frame.argbAt(x, y)
                    if (Color.red(color) + Color.green(color) + Color.blue(color) > 110) bright++
                }
            assertTrue("Physical selected pad must actually be drawn", bright > 25)
        }
        compose.runOnIdle {
            assertTrue("Selecting a route cannot mutate its audio/patch state",
                callbackIndices.isEmpty())
            assertEquals(ModSource.TOUCH_X, routes[63].source)
            assertEquals(-.25f, routes[63].depth, 0f)
        }

        // Moving from the physical pad to the exact route64 slider must retain
        // the selection; TalkBack SetProgress edits ONLY saved slot63.
        revealByPhysicalSwipes("modulationDepthSlider", scroll)
        compose.onNodeWithTag("modulationDepthSlider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-1f) }
        compose.runOnIdle {
            assertEquals(listOf(63), callbackIndices)
            assertEquals(-1f, routes[63].depth, .06f)
            assertTrue(routes[63].enabled)
            assertEquals(ModSource.TOUCH_X, routes[63].source)
            assertTrue(routes.take(63).all { it == ModulationRouteConfig() })
        }
        compose.onNodeWithTag("modRouteSlot63").assertIsSelected()
    }

    @Test fun full64RouteGridHasPhysical48dpLastPadAndTalkBackAt350dpFont130() {
        exerciseViewport(350f)
    }

    @Test fun full64RouteGridHasPhysical48dpLastPadAndTalkBackAt360dpFont130() {
        exerciseViewport(360f)
    }
}
