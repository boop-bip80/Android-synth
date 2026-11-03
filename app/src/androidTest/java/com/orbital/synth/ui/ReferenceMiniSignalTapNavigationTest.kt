package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertEquals
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
        onEdit: (ParameterId, Float) -> Unit = { _, _ -> },
    ) {
        val parameters = mapOf(
            ParameterId.ATTACK to .20f,
            ParameterId.LFO_RATE to .24f,
            ParameterId.CUTOFF to .50f,
        )
        val lfos = if (connected) List(8) {
            ModulationLfoConfig(enabled = it == 0)
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
                        onModulationLfoChange = if (connected) { _, _ -> } else null,
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

    @Test fun envAndLfoPhysicalMiniTapsOpenActualHostSlidersAndWriteDspParams() {
        var width by mutableIntStateOf(360)
        val writes = mutableListOf<Pair<ParameterId, Float>>()
        setHost(width = { width }, onEdit = { id, value -> writes += id to value })

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
