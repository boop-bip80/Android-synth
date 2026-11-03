package com.orbital.synth.ui

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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import com.orbital.synth.core.dsp.processing.StepDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production STEP editor, actual Android touch bounds and accessibility actions.
 * Reaching the deep editor uses real finger swipes, never virtual scroll-to or
 * offscreen existence as proof that a control can physically be reached.
 */
@RunWith(AndroidJUnit4::class)
class StepRhythmAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val initial = ModulationStepConfig(
        enabled = false,
        values = List(16) { (it - 8) / 10f },
        length = 7, // Pads outside the active loop must remain editable and saved.
        beatsPerStep = .5f,
        glide = .24f,
        direction = StepDirection.PING_PONG,
        retriggerOnNote = true,
    )
    private var pattern by mutableStateOf(initial)
    private val published = mutableListOf<ModulationStepConfig>()

    private fun revealPadWithPhysicalSwipes(
        index: Int,
        scroll: ScrollState,
        towardStart: Boolean = false,
    ): Rect {
        val viewport = compose.onNodeWithTag("stepAccessViewport")
        val pad = compose.onNodeWithTag("modStepPad" + index)
        repeat(25) { attempt ->
            compose.waitForIdle()
            val bounds = pad.fetchSemanticsNode().boundsInRoot
            val visible = viewport.fetchSemanticsNode().boundsInRoot
            // Fixture density is exactly 3px/dp. Require the entire 76dp
            // painting surface, not a fortuitously visible clipped fragment.
            if (bounds.width >= 48f * 3f - 1f &&
                bounds.height >= 76f * 3f - 1f &&
                bounds.left >= visible.left - 1f &&
                bounds.right <= visible.right + 1f &&
                bounds.top >= visible.top - 1f &&
                bounds.bottom <= visible.bottom + 1f
            ) return bounds
            if (attempt == 24) throw AssertionError(
                "STEP pad $index not fully reachable after 24 real swipes: " +
                    "pad=$bounds viewport=$visible scroll=${scroll.value}/${scroll.maxValue}",
            )
            val above = bounds.height > 0f && bounds.top < visible.top
            val below = bounds.height > 0f && bounds.bottom > visible.bottom
            val atEnd = scroll.maxValue > 0 && scroll.value >= scroll.maxValue - 1
            val down = towardStart || above ||
                ((bounds.width <= 0f || bounds.height <= 0f) && atEnd && !below)
            viewport.performTouchInput {
                // Quiet outer gutter is outside every pad/slider recognizer.
                swipe(
                    start = Offset(4f, visible.height * if (down) .25f else .78f),
                    end = Offset(4f, visible.height * if (down) .78f else .25f),
                    durationMillis = 380L,
                )
            }
        }
        error("Every reveal either returns or fails above")
    }

    private fun setProgress(index: Int, value: Float, expectedAccepted: Boolean) {
        var accepted: Boolean? = null
        compose.onNodeWithTag("modStepPad" + index)
            .performSemanticsAction(SemanticsActions.SetProgress) { accepted = it(value) }
        compose.runOnIdle {
            assertEquals("SetProgress($index, $value) acceptance", expectedAccepted, accepted)
        }
    }

    private fun exercise(widthDp: Float) {
        val scroll = ScrollState(0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1.3f)) {
                MaterialTheme {
                    Box(Modifier.width(widthDp.dp).height(350.dp)
                        .testTag("stepAccessViewport")) {
                        Column(Modifier.verticalScroll(scroll)) {
                            ModulationConstellationPanel(
                                lfos = List(8) { ModulationLfoConfig(enabled = false) },
                                routes = List(64) { ModulationRouteConfig() },
                                tempoBpm = 120f,
                                onLfoChange = { _, _ -> },
                                onRouteChange = { _, _ -> },
                                onRouteClear = {},
                                onTempoChange = {},
                                stepConfig = pattern,
                                stepPlayhead = 3,
                                onStepChange = {
                                    published += it
                                    pattern = it
                                },
                            )
                        }
                    }
                }
            }
        }
        // Every numbered node must be unique, physically reachable and at
        // least 48dp wide even inside the doubly padded phone-size editor.
        for (index in 0 until 16) {
            val bounds = revealPadWithPhysicalSwipes(index, scroll)
            assertTrue("STEP pad $index width at $widthDp dp", bounds.width >= 143f)
            assertTrue("STEP pad $index full height at $widthDp dp", bounds.height >= 227f)
            val semantics = compose.onNodeWithTag("modStepPad" + index)
                .fetchSemanticsNode().config
            assertTrue("STEP pad $index must be adjustable by assistive input",
                semantics.contains(SemanticsActions.SetProgress))
            val range = semantics[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(-1f, range.range.start, 0f)
            assertEquals(1f, range.range.endInclusive, 0f)
            assertEquals(initial.values[index], range.current, 0f)
            assertTrue(semantics[SemanticsProperties.StateDescription].contains("bipolar"))
        }
        compose.onNodeWithTag("modStepPad16").assertDoesNotExist()
        compose.runOnIdle { assertTrue("Scrolling must not paint STEP values", published.isEmpty()) }

        // The last stored pad is OUTSIDE the active 7-step loop. Accessibility
        // editing must change exactly that pad without enabling/resetting STEP.
        setProgress(15, .75f, expectedAccepted = true)
        compose.runOnIdle {
            assertEquals(initial.withStep(15, .75f), pattern)
            assertEquals(1, published.size)
            assertFalse(pattern.enabled)
        }
        val updated = compose.onNodeWithTag("modStepPad15").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(.75f, updated.current, 0f)

        setProgress(15, 4f, expectedAccepted = true)
        compose.runOnIdle { assertEquals(initial.withStep(15, 1f), pattern) }
        setProgress(15, -4f, expectedAccepted = true)
        compose.runOnIdle { assertEquals(initial.withStep(15, -1f), pattern) }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            setProgress(15, invalid, expectedAccepted = false)
        }
        setProgress(15, -1f, expectedAccepted = false) // No duplicate unchanged publication.
        compose.runOnIdle {
            assertEquals(3, published.size)
            assertEquals(initial.withStep(15, -1f), pattern)
        }

        // Physical finger painting still crosses that SAME real host callback.
        // The accessibility change to pad15 must survive a later edit to pad0.
        revealPadWithPhysicalSwipes(0, scroll, towardStart = true)
        compose.onNodeWithTag("modStepPad0").performTouchInput {
            click(Offset(center.x, height * .2f))
        }
        compose.runOnIdle {
            assertEquals(4, published.size)
            assertEquals(.6f, pattern.values[0], .025f)
            assertEquals(-1f, pattern.values[15], 0f)
            for (index in 1 until 15) assertEquals(initial.values[index], pattern.values[index], 0f)
            assertEquals("STEP enable/clock/loop/retrigger metadata must be untouched",
                initial, pattern.copy(values = initial.values))
        }
    }

    @Test fun allSixteenStepPadsAre48dpAndAssistiveEditsAreIsolatedAt350dpFont130() {
        exercise(350f)
    }

    @Test fun allSixteenStepPadsAre48dpAndAssistiveEditsAreIsolatedAt360dpFont130() {
        exercise(360f)
    }
}
