package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.dsp.processing.LfoShape
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual API35 Compose hit-testing, geometry and live host routes. No mocked
 * editor popups or fake audio. Width 360/411dp, TalkBack scale 1.3x.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceMiniSignalTapNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2.5f

    private fun setHost(
        width: () -> Int,
        connected: Boolean = true,
        firstEnabledLfoIndex: Int = 0,
        onEdit: (ParameterId, Float) -> Unit = { _, _ -> },
        onGardenEdit: (Int, ModulationLfoConfig) -> Unit = { _, _ -> },
    ) {
        val parameters = mapOf(
            ParameterId.ATTACK to .20f,
            ParameterId.LFO_RATE to .24f,
            ParameterId.CUTOFF to .50f,
        )
        val lfos = if (connected) List(8) { index ->
            ModulationLfoConfig(
                enabled = index == firstEnabledLfoIndex,
                shape = if (index == firstEnabledLfoIndex) LfoShape.TRIANGLE
                    else LfoShape.SINE,
            )
        } else emptyList()
        val routes = if (connected) List(64) { ModulationRouteConfig() } else emptyList()
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density, fontScale = 1.3f),
            ) {
                Box(Modifier.width(width().dp).height(820.dp)) {
                    OrbitalScreen(
                        state = OrbitalUiState(
                            parameters = parameters,
                            modulationLfos = lfos,
                            modulationRoutes = routes,
                        ),
                        onNoteOn = {},
                        onNoteOff = {},
                        onParameterChange = onEdit,
                        onModeChange = {},
                        onOctaveChange = {},
                        onModulationLfoChange = if (connected) onGardenEdit else null,
                        onModulationRouteChange = if (connected) { _, _ -> } else null,
                        onModulationRouteClear = if (connected) { _ -> } else null,
                        onModulationTempoChange = if (connected) { _ -> } else null,
                    )
                }
            }
        }
    }

    private fun tapCard(tag: String) {
        // A pointer, not a synthetic "invoke callback" test. Also prove the
        // assistive action on exactly the same physical card.
        compose.onNodeWithTag(tag)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performTouchInput { click(center) }
        compose.waitForIdle()
    }

    /** A partly clipped 48dp editor button is not a usable physical touch target. */
    private fun fullyInEditor(editor: Rect, target: Rect, minHeightDp: Float): Boolean =
        target.width >= 48f * density - 1f &&
            target.height >= minHeightDp * density - 1f &&
            target.top >= editor.top - 1f &&
            target.bottom <= editor.bottom + 1f

    /**
     * No Compose test-only auto-scroll: touch the SAME real editor viewport a
     * human uses. The 3px inset avoids nested LFO horizontal shape chips.
     * Require fully visible >=48dp geometry after at most six bounded swipes.
     */
    private fun physicallyRevealGardenAction(tag: String) {
        val editor = compose.onNodeWithTag("soundEditorScroll").assertIsDisplayed()
        repeat(6) {
            val target = compose.onNodeWithTag(tag)
            val editorRect = editor.fetchSemanticsNode().boundsInRoot
            val targetRect = try {
                target.fetchSemanticsNode().boundsInRoot
            } catch (_: AssertionError) {
                Rect.Zero
            }
            if (fullyInEditor(editorRect, targetRect, 48f)) {
                target.assertIsDisplayed()
                return
            }
            editor.performTouchInput {
                swipe(
                    start = Offset(3f, size.height * .79f),
                    end = Offset(3f, size.height * .21f),
                    durationMillis = 280,
                )
            }
            compose.waitForIdle()
        }
        val target = compose.onNodeWithTag(tag).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val editorRect = editor.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Real LFO action must be fully touchable >=48dp after physical swipes: " +
                tag + " editor=" + editorRect + " target=" + target,
            fullyInEditor(editorRect, target, 48f),
        )
    }

    @Test fun portraitMiniCardsRemainFourReal48dpPlusHitTargetsAndKeysRemainVisible() {
        var width by mutableIntStateOf(411)
        setHost(width = { width })
        for (testedWidth in listOf(411, 360)) {
            compose.runOnIdle { width = testedWidth }
            compose.waitForIdle()
            val piano = compose.onNodeWithTag("referencePerformanceKeybed")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            for (tag in listOf(
                "referenceMiniEnv", "referenceMiniLfo", "referenceMiniExpr", "referenceMiniMod",
            )) {
                val card = compose.onNodeWithTag(tag)
                    .assertIsDisplayed()
                    .assertHasClickAction()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("Mini card must keep >=48dp width: " + tag + " at " + testedWidth,
                    card.width / density >= 48f)
                assertTrue("Mini card must keep >=48dp height: " + tag + " at " + testedWidth,
                    card.height / density >= 48f)
                assertTrue("Mini card may never overlap anchored playable piano: " + tag,
                    card.bottom < piano.top)
            }
        }
    }

    @Test fun envAndDisconnectedCoreLfoTapsOpenActualSlidersAndWriteDspParams() {
        var width by mutableIntStateOf(360)
        val writes = mutableListOf<Pair<ParameterId, Float>>()
        setHost(
            width = { width },
            connected = false,
            onEdit = { id, value -> writes += id to value },
        )

        tapCard("referenceMiniEnv")
        compose.onNodeWithContentDescription("Tab FX").assertIsDisplayed()
        compose.onNodeWithTag("miniEnvActualEditor").assertIsDisplayed()
        compose.onNodeWithContentDescription("ATTACK. Fade-in time")
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                assertTrue(action(.73f))
            }
        compose.runOnIdle {
            assertTrue(writes.any { it.first == ParameterId.ATTACK && it.second > .70f })
        }
        compose.onNodeWithContentDescription("Tab SOUND").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("CORE").assertExists()
        tapCard("referenceMiniLfo")
        compose.onNodeWithTag("miniLfoActualEditor").assertIsDisplayed()
        compose.onNodeWithContentDescription("LFO RATE. Modulation speed")
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                assertTrue(action(.81f))
            }
        compose.runOnIdle {
            assertTrue(writes.any { it.first == ParameterId.LFO_RATE && it.second > .78f })
        }
        compose.onNodeWithTag("referencePerformanceKeybed").assertIsDisplayed()
    }

    @Test fun connectedLfoMiniTapRevealsActualFirstEnabledRackLaneAndEditsIt() {
        var width by mutableIntStateOf(360)
        val laneWrites = mutableListOf<Pair<Int, ModulationLfoConfig>>()
        setHost(
            width = { width },
            connected = true,
            firstEnabledLfoIndex = 5,
            onGardenEdit = { index, config -> laneWrites += index to config },
        )
        compose.onNodeWithText("TRIAN").assertExists()
        tapCard("referenceMiniLfo")
        compose.onNodeWithContentDescription("Tab FX").assertIsDisplayed()
        // The exact first-enabled node must already be in the real viewport:
        // no test scroll can disguise a broken product BringIntoView.
        val lane = compose.onNodeWithTag("lfoGardenNode5")
            .assertIsDisplayed()
        val laneBounds = lane.fetchSemanticsNode().boundsInRoot
        val editorBounds = compose.onNodeWithTag("soundEditorScroll")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Matching LFO source must be fully in the viewport, not clipped",
            fullyInEditor(editorBounds, laneBounds, 84f))
        lane.performTouchInput { click(center) }
        compose.onNodeWithText("SOURCE  06  /  TRIANGLE").assertExists()
        // This is the SAME eight-LFO editor visualized in the mini trace,
        // not the unrelated legacy DSP LFO_RATE / LFO_DEPTH sliders.
        compose.onNodeWithTag("lfoConfigurationGarden").assertExists()
        physicallyRevealGardenAction("lfoGardenShapeSINE")
        compose.onNodeWithTag("lfoGardenShapeSINE")
            .performTouchInput { click(center) }
        compose.runOnIdle {
            assertTrue("Genuine lane-six shape callback must reach host", laneWrites.any {
                it.first == 5 && it.second.shape == LfoShape.SINE
            })
        }
        compose.onNodeWithTag("referencePerformanceKeybed").assertIsDisplayed()
    }

    @Test fun heldMiniTapSurvivesRealParameterRecompositionAtSoundTelemetryCadence() {
        var liveMorph by mutableFloatStateOf(.30f)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density, fontScale = 1.3f),
            ) {
                Box(Modifier.width(360.dp).height(820.dp)) {
                    OrbitalScreen(
                        state = OrbitalUiState(
                            parameters = mapOf(ParameterId.MORPH to liveMorph),
                        ),
                        onNoteOn = {}, onNoteOff = {},
                        onParameterChange = { _, _ -> },
                        onModeChange = {}, onOctaveChange = {},
                    )
                }
            }
        }
        val tile = compose.onNodeWithTag("referenceMiniEnv")
            .assertIsDisplayed().assertHasClickAction()
        // Inject a genuine DOWN, force unrelated SOUND parameter recompose while
        // finger stays held, then real UP. A pointerInput(callback) key would
        // cancel the pending gesture whenever the 20Hz PCM/UI path updates.
        tile.performTouchInput { down(0, center) }
        compose.runOnIdle { liveMorph = .70f }
        tile.performTouchInput { up(0) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Tab FX").assertIsDisplayed()
        compose.onNodeWithTag("miniEnvActualEditor").assertIsDisplayed()
    }

    @Test fun expressionMiniPhysicallyOpensSameSixActualXyRoutingModes() {
        var width by mutableIntStateOf(360)
        setHost(width = { width })
        tapCard("referenceMiniExpr")
        compose.onNodeWithTag("referenceXyModePalette").assertIsDisplayed()
        compose.onNodeWithTag("referenceXyModeFILTER")
            .performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("referenceXyModePalette").assertDoesNotExist()
        compose.onNodeWithTag("referenceXyModeButton").assertIsDisplayed()
    }

    @Test fun modMiniRevealsGenuine64RouteEditorOnlyWhenHostConnected() {
        var width by mutableIntStateOf(360)
        setHost(width = { width }, connected = true)
        tapCard("referenceMiniMod")
        compose.onNodeWithContentDescription("Tab FX").assertIsDisplayed()
        compose.onNodeWithTag("miniModActualEditor").assertIsDisplayed()
        compose.onNodeWithTag("modulationConstellationPanel").assertIsDisplayed()
        compose.onNodeWithTag("modRouteSlot0").assertExists()
        compose.onNodeWithTag("modRouteSlot63").assertExists()
        compose.onNodeWithTag("referencePerformanceKeybed").assertIsDisplayed()
    }

    @Test fun disconnectedModPreviewNeverClaimsActionOrNavigatesToFakeMatrix() {
        var width by mutableIntStateOf(360)
        setHost(width = { width }, connected = false)
        val mod = compose.onNodeWithTag("referenceMiniMod")
            .assertIsDisplayed().assertHasNoClickAction()
        // Even tapping the same physical region cannot conjure an editor.
        mod.performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("referenceMiniEnv").assertIsDisplayed()
        compose.onNodeWithTag("miniModActualEditor").assertDoesNotExist()
    }
}
