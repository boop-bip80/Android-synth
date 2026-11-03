package com.orbital.synth.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceXyModeRailTest {
    @get:Rule val compose = createComposeRule()

    @Test fun railKeepsOriginalHeroHeightAndSixOptionsHaveReadable48DpTargets() {
        var mode by mutableStateOf(ReferenceXyMode.PERFORMANCE)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 2.56875f, fontScale = 1.3f),
                ) {
                    Box(Modifier.width(360.dp).height(230.dp)) {
                        ReferenceXyModeRail(
                            selectedMode = mode,
                            normalizedEngine = .5f,
                            onModeSelected = { mode = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        val root = compose.onNodeWithTag("referenceXyModeRail")
            .fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("referenceXyModeButton")
        button.assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        val b = button.fetchSemanticsNode().boundsInRoot
        assertTrue("XY rail must be inside existing hero", b.left >= root.left &&
            b.right <= root.right && b.bottom <= root.bottom)
        assertTrue("XY rail should leave most of ribbon playable", b.width < root.width * .2f)

        button.performClick()
        val rootAfter = compose.onNodeWithTag("referenceXyModeRail")
            .fetchSemanticsNode().boundsInRoot
        assertEquals("An expanded XY menu must never push the anchored keyboard",
            root.height, rootAfter.height, 1f)

        ReferenceXyMode.entries.forEach { entry ->
            val option = compose.onNodeWithTag("referenceXyMode${entry.name}")
            option.assertExists().assertHeightIsAtLeast(48.dp).assertIsDisplayed()
            val item = option.fetchSemanticsNode()
            assertEquals("Mode must be an accessible radio choice: ${entry.name}",
                Role.RadioButton, item.config[SemanticsProperties.Role])
            val bounds = item.boundsInRoot
            assertTrue("${entry.name} does not fit the 360dp viewport",
                bounds.left >= root.left && bounds.right <= root.right &&
                    bounds.top >= root.top && bounds.bottom <= root.bottom)
        }
    }

    @Test fun selectingRealModeFiresExactlyOneCallbackAndDismissesOverlay() {
        var selected by mutableStateOf(ReferenceXyMode.PERFORMANCE)
        val changes = mutableListOf<ReferenceXyMode>()
        var resetCount = 0
        var openReset = 0
        compose.setContent {
            Box(Modifier.width(360.dp).height(230.dp)) {
                ReferenceXyModeRail(
                    selectedMode = selected,
                    normalizedEngine = 0f,
                    onModeSelected = { selected = it; changes += it },
                    modifier = Modifier.fillMaxSize(),
                    onBeforeOpen = { openReset++ },
                    onBeforeSelect = { resetCount++ },
                )
            }
        }
        compose.onNodeWithTag("referenceXyModeButton").performClick()
        compose.onNodeWithTag("referenceXyModeFILTER").performClick()
        compose.runOnIdle {
            assertEquals(ReferenceXyMode.FILTER, selected)
            assertEquals(listOf(ReferenceXyMode.FILTER), changes)
            assertEquals(1, openReset)
            assertEquals(1, resetCount)
        }
        compose.onNodeWithTag("referenceXyModePalette").assertDoesNotExist()
        compose.onNodeWithTag("referenceXyModeButton").assertExists()
    }

    @Test fun discreteFmAndPdWaveChoicesAreNeverExposedAsContinuousWaveModes() {
        var engine by mutableFloatStateOf(.75f)
        var selected by mutableStateOf(ReferenceXyMode.PERFORMANCE)
        compose.setContent {
            Box(Modifier.width(360.dp).height(230.dp)) {
                ReferenceXyModeRail(
                    selectedMode = selected,
                    normalizedEngine = engine,
                    onModeSelected = { selected = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("referenceXyModeButton").performClick()
        compose.onNodeWithTag("referenceXyModeWAVE_SHAPE").assertIsNotEnabled()
        compose.onNodeWithTag("referenceXyModeFM_COLOR").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ReferenceXyMode.FM_COLOR, selected) }

        compose.runOnIdle { engine = 0f }
        // Model fallback immediately renders PERF: host subsequently saves
        // fallback selection, rather than routing FM parameters to WT.
        compose.onNodeWithTag("referenceXyModeButton").performClick()
        compose.onNodeWithTag("referenceXyModeFM_COLOR").assertIsNotEnabled()
        compose.onNodeWithTag("referenceXyModeWAVE_SHAPE").assertIsEnabled()
    }

    @Test fun fingerHeldBeforePaletteOpensCannotChooseNewModeWithoutFreshDown() {
        var selected by mutableStateOf(ReferenceXyMode.PERFORMANCE)
        val changes = mutableListOf<ReferenceXyMode>()
        compose.setContent {
            Box(Modifier.width(360.dp).height(230.dp)) {
                ReferenceXyModeRail(
                    selectedMode = selected,
                    normalizedEngine = 0f,
                    onModeSelected = { selected = it; changes += it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Learn exact rendered chip position. This test never guesses a
        // percentage or assumes font scale, so it tests the physical hit box.
        compose.onNodeWithTag("referenceXyModeButton").performClick()
        val chipCenter = compose.onNodeWithTag("referenceXyModeFILTER")
            .fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("referenceXyModeBackdrop").performClick()

        val root = compose.onNodeWithTag("referenceXyModeRail")
        val rootOrigin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val buttonCenter = compose.onNodeWithTag("referenceXyModeButton")
            .fetchSemanticsNode().boundsInRoot.center
        // Finger A touches the FUTURE chip position BEFORE the palette
        // exists, then remains physically held while B opens it.
        root.performTouchInput { down(0, chipCenter - rootOrigin) }
        root.performTouchInput {
            down(1, buttonCenter - rootOrigin)
            advanceEventTime(120L)
            up(1)
        }
        compose.onNodeWithTag("referenceXyModeFILTER").assertExists()
        root.performTouchInput {
            moveTo(0, chipCenter - rootOrigin)
            up(0)
        }
        compose.runOnIdle {
            assertEquals("Old UP may not masquerade as a new menu selection",
                emptyList<ReferenceXyMode>(), changes)
        }

        root.performTouchInput {
            down(2, chipCenter - rootOrigin)
            advanceEventTime(120L)
            up(2)
        }
        compose.runOnIdle {
            assertEquals(listOf(ReferenceXyMode.FILTER), changes)
            assertEquals(ReferenceXyMode.FILTER, selected)
        }
    }
}
