package com.orbital.synth.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

@RunWith(AndroidJUnit4::class)
class ReferenceSoundHeroTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactPortraitHasOneRealPlasmaOneOverlaidXYFiveKnobsAndFourTiles() {
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                ReferenceSoundHero(
                    parameters = mapOf(
                        ParameterId.MORPH to .42f,
                        ParameterId.MOVEMENT to .72f,
                    ),
                    lfos = List(8) { ModulationLfoConfig() },
                    routes = List(64) { ModulationRouteConfig() },
                    onParameterChange = { _, _ -> },
                    compact = true,
                )
            }
        }
        compose.onNodeWithTag("referenceSoundHero").assertExists()
        compose.onNodeWithTag("referenceSoundHeroOrb").assertExists()
        compose.onNodeWithTag("orbitalPlasmaField").assertExists()
        compose.onAllNodesWithTag("referenceExpressionRibbon").assertCountEquals(1)
        compose.onNodeWithTag("referenceKnobWAVE").assertExists()
        compose.onNodeWithTag("referenceKnobCUTOFF").assertExists()
        compose.onNodeWithTag("referenceKnobRESONANCE").assertExists()
        compose.onNodeWithTag("referenceKnobMOVEMENT").assertExists()
        compose.onNodeWithTag("referenceKnobFX_AMOUNT").assertExists()
        compose.onNodeWithTag("referenceMiniEnv").assertExists()
        compose.onNodeWithTag("referenceMiniLfo").assertExists()
        compose.onNodeWithTag("referenceMiniExpr").assertExists()
        compose.onNodeWithTag("referenceMiniMod").assertExists()
    }

    @Test fun touchOnLiveOrbEditsExactlyRealMorphAndMovementControls() {
        var parameters by mutableStateOf(mapOf(
            ParameterId.MORPH to .1f,
            ParameterId.MOVEMENT to .3f,
            ParameterId.CUTOFF to .7f,
        ))
        val changes = mutableListOf<ParameterId>()
        compose.setContent {
            ReferenceSoundHero(
                parameters = parameters,
                lfos = List(8) { ModulationLfoConfig() },
                routes = List(64) { ModulationRouteConfig() },
                onParameterChange = { id, value ->
                    changes.add(id)
                    parameters = parameters + (id to value)
                },
            )
        }
        compose.onNodeWithTag("referenceHeroOrbInput").performTouchInput {
            click(Offset(center.x * 1.4f, center.y * .7f))
        }
        compose.runOnIdle {
            assertTrue(changes.contains(ParameterId.MORPH))
            assertTrue(changes.contains(ParameterId.MOVEMENT))
            assertEquals(.70f, parameters[ParameterId.MORPH] ?: -1f, .03f)
            assertEquals(.65f, parameters[ParameterId.MOVEMENT] ?: -1f, .03f)
            assertEquals(.70f, parameters[ParameterId.CUTOFF] ?: -1f, .00001f)
        }
    }

    @Test fun translucentXYInsideOrbStillMapsBothRealHostParameters() {
        var values by mutableStateOf(mapOf(
            ParameterId.MORPH to .1f,
            ParameterId.MOVEMENT to .2f,
        ))
        compose.setContent {
            ReferenceSoundHero(
                parameters = values,
                lfos = List(8) { ModulationLfoConfig() },
                routes = List(64) { ModulationRouteConfig() },
                onParameterChange = { id, next -> values = values + (id to next) },
            )
        }
        compose.onNodeWithTag("referenceExpressionRibbon").performTouchInput {
            click(Offset(center.x * 1.5f, center.y * .50f))
        }
        compose.runOnIdle {
            assertEquals(.75f, values[ParameterId.MORPH] ?: -1f, .03f)
            assertEquals(.75f, values[ParameterId.MOVEMENT] ?: -1f, .03f)
        }
    }

    @Test fun patchAndEngineAffordancesOnlyPerformRealProvidedCallbacks() {
        var patchOpen = 0
        var engineOpen = 0
        var prev = 0
        var next = 0
        compose.setContent {
            ReferenceSoundHero(
                parameters = emptyMap(),
                lfos = emptyList(),
                routes = emptyList(),
                onParameterChange = { _, _ -> },
                patchName = "VIOLET COMET",
                engineName = "LADDER",
                onOpenPatchLibrary = { patchOpen++ },
                onPreviousPatch = { prev++ },
                onNextPatch = { next++ },
                onOpenEngine = { engineOpen++ },
            )
        }
        compose.onNodeWithTag("referenceHeroPatchName").performClick()
        compose.onNodeWithTag("referenceHeroEngineName").performClick()
        compose.onNodeWithTag("referenceHeroPreviousPatch").performClick()
        compose.onNodeWithTag("referenceHeroNextPatch").performClick()
        compose.runOnIdle {
            assertEquals(1, patchOpen)
            assertEquals(1, engineOpen)
            assertEquals(1, prev)
            assertEquals(1, next)
        }
    }
}
