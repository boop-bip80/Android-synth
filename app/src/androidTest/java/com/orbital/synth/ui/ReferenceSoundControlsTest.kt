package com.orbital.synth.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceSoundControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fiveNeonKnobsExposeEditableRealParametersAt360dp() {
        var parameters by mutableStateOf(
            mapOf(ParameterId.WAVE to .25f,
                ParameterId.CUTOFF to .4f,
                ParameterId.RESONANCE to .3f,
                ParameterId.MOVEMENT to .55f,
                ParameterId.FX_AMOUNT to .15f),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ReferenceSoundControls(
                        parameters = parameters,
                        lfos = List(8) { ModulationLfoConfig() },
                        routes = List(64) { ModulationRouteConfig() },
                        onParameterChange = { id, next ->
                            parameters = parameters + (id to next)
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("referenceSoundControls").assertExists()
        compose.onNodeWithTag("referenceKnobWAVE").assertExists()
        compose.onNodeWithTag("referenceKnobCUTOFF").assertExists()
        compose.onNodeWithTag("referenceKnobRESONANCE").assertExists()
        compose.onNodeWithTag("referenceKnobMOVEMENT").assertExists()
        compose.onNodeWithTag("referenceKnobFX_AMOUNT").assertExists()
        compose.onNodeWithTag("referenceKnobCUTOFF")
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                assertTrue(action(.82f))
            }
        compose.runOnIdle {
            assertEquals(.82f, parameters[ParameterId.CUTOFF] ?: -1f, .0001f)
            assertEquals(.25f, parameters[ParameterId.WAVE] ?: -1f, .0001f)
        }
    }

    @Test fun expressionRibbonTouchChangesRealMorphAndMovementRatherThanFakeAudioData() {
        var morph by mutableStateOf(.1f)
        var movement by mutableStateOf(.2f)
        var calls = 0
        compose.setContent {
            ReferenceExpressionRibbon(
                morph = morph,
                movement = movement,
                onChange = { nextMorph, nextMovement ->
                    calls++
                    morph = nextMorph
                    movement = nextMovement
                },
            )
        }
        compose.onNodeWithTag("referenceExpressionRibbon")
            .performTouchInput {
                // 75% across, 25% down gives 75% upward movement.
                click(Offset(center.x * 1.5f, center.y * .5f))
            }
        compose.runOnIdle {
            assertTrue("pointer callback must be live", calls >= 1)
            assertTrue("morph must be near pointer x", morph in .72f.. .78f)
            assertTrue("movement must map upward from touch", movement in .72f.. .78f)
        }
    }

    @Test fun fourMiniaturePanelsRepresentSavedSettingsAndOnlyAudibleEnabledRoutes() {
        var liveRoute by mutableStateOf(
            ModulationRouteConfig(
                enabled = true,
                destination = ModDestination.AMP,
                depth = .75f,
            ),
        )
        compose.setContent {
            ReferenceSoundControls(
                parameters = mapOf(
                    ParameterId.ATTACK to .1f,
                    ParameterId.DECAY to .55f,
                    ParameterId.SUSTAIN to .3f,
                    ParameterId.RELEASE to .8f,
                ),
                lfos = List(8) {
                    ModulationLfoConfig(
                        enabled = it == 0,
                        shape = LfoShape.TRIANGLE,
                    )
                },
                routes = listOf(liveRoute) + List(63) { ModulationRouteConfig() },
                onParameterChange = { _, _ -> },
                audioRms = .07f,
            )
        }
        compose.onNodeWithTag("referenceMiniEnv").assertExists()
        compose.onNodeWithTag("referenceMiniLfo").assertExists()
        compose.onNodeWithTag("referenceMiniExpr").assertExists()
        compose.onNodeWithTag("referenceMiniMod").assertExists()
        compose.onNodeWithText("TRIAN").assertExists()
        compose.onNodeWithText("1 LIVE").assertExists()
        compose.runOnIdle {
            liveRoute = liveRoute.copy(depth = 0f)
        }
        compose.onNodeWithText("0 LIVE").assertExists()
    }

    @Test fun visualKnobAccessibilityCoercesOutOfRangeHostEdits() {
        var value by mutableStateOf(.5f)
        compose.setContent {
            ReferenceNeonKnob(
                label = "CUTOFF",
                value = value,
                tint = OrbitalPalette.Cyan,
                tag = "referenceSingleKnob",
                onValueChange = { value = it },
            )
        }
        compose.onNodeWithTag("referenceSingleKnob")
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                assertTrue(action(3.5f))
            }
        compose.runOnIdle { assertEquals(1f, value, .00001f) }
        compose.onNodeWithTag("referenceSingleKnob")
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                assertTrue(action(-3f))
            }
        compose.runOnIdle { assertEquals(0f, value, .00001f) }
    }
}
