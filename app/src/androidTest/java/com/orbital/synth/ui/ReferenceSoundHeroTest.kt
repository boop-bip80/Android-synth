package com.orbital.synth.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceSoundHeroTest {
    @get:Rule val compose = createComposeRule()

    /**
     * Inject the second REAL finger at the actual rendered 48dp button rather
     * than guessing a percentage of the parent image. Patch name, font scale,
     * HUD height, and window insets can all move it without changing behavior.
     */
    private fun nextPatchHitInsideOrb(): Offset {
        val next = compose.onNodeWithTag("referenceHeroNextPatch")
            .fetchSemanticsNode().boundsInRoot
        val orb = compose.onNodeWithTag("referenceSoundHeroOrb")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Next patch has no real touch target", next.width >= 40f && next.height >= 40f)
        assertTrue("Next patch hit target is not inside actual orb bounds",
            next.center.x in orb.left..orb.right && next.center.y in orb.top..orb.bottom)
        return next.center - orb.topLeft
    }

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
        assertEquals(1, compose.onAllNodesWithTag("referenceExpressionRibbon").fetchSemanticsNodes().size)
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
            assertEquals(.57f, parameters[ParameterId.MOVEMENT] ?: -1f, .03f)
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
    @Test fun patchAndEngineHudHaveReal48DpTouchAreasAndDoNotLeakToOrb() {
        var patchCalls = 0
        var engineCalls = 0
        var orbEdits = 0
        compose.setContent {
            ReferenceSoundHero(
                parameters = emptyMap(),
                lfos = emptyList(),
                routes = emptyList(),
                onParameterChange = { _, _ -> orbEdits++ },
                onOpenPatchLibrary = { patchCalls++ },
                onOpenEngine = { engineCalls++ },
            )
        }
        compose.onNodeWithTag("referenceHeroPatchName").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("referenceHeroEngineName").assertHeightIsAtLeast(48.dp)
        // Touch near the top/bottom edges of the 48dp hit boxes, well
        // outside the 11sp text glyph, and verify the orb cannot steal it.
        compose.onNodeWithTag("referenceHeroPatchName").performTouchInput {
            click(Offset(center.x, center.y * .08f))
        }
        compose.onNodeWithTag("referenceHeroEngineName").performTouchInput {
            click(Offset(center.x, center.y * 1.86f))
        }
        compose.runOnIdle {
            assertEquals(1, patchCalls)
            assertEquals(1, engineCalls)
            assertEquals(0, orbEdits)
        }
    }

    @Test fun plasmaIsDecorativeAndSingleOrbTalkBackTargetEditsRealParameters() {
        var values by mutableStateOf(mapOf(
            ParameterId.MORPH to .20f,
            ParameterId.MOVEMENT to .40f,
        ))
        compose.setContent {
            ReferenceSoundHero(
                parameters = values,
                lfos = emptyList(),
                routes = emptyList(),
                onParameterChange = { p, v -> values = values + (p to v) },
            )
        }
        val renderedPlasma = compose.onNodeWithTag(
            "orbitalPlasmaField", useUnmergedTree = true,
        ).fetchSemanticsNode()
        assertFalse(renderedPlasma.config.contains(SemanticsProperties.ContentDescription))
        assertEquals(0, compose.onAllNodesWithContentDescription(
            "Fluid cyan violet and gold sound orb",
            substring = true, useUnmergedTree = true,
        ).fetchSemanticsNodes().size)
        val actions = compose.onNodeWithTag(
            "referenceHeroOrbInput", useUnmergedTree = true,
        ).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(4, actions.size)
        compose.runOnIdle {
            assertTrue(actions.first { it.label == "Increase morph" }.action())
            assertTrue(actions.first { it.label == "Decrease movement" }.action())
        }
        compose.runOnIdle {
            assertEquals(.25f, values[ParameterId.MORPH] ?: -1f, .001f)
            assertEquals(.35f, values[ParameterId.MOVEMENT] ?: -1f, .001f)
        }
    }

    @Test fun orbHeldDragOwnsGlowUntilRealReleaseAndOsCancel() {
        var values by mutableStateOf(mapOf(
            ParameterId.MORPH to .30f, ParameterId.MOVEMENT to .30f,
        ))
        compose.setContent {
            ReferenceSoundHero(
                parameters = values,
                lfos = emptyList(),
                routes = emptyList(),
                onParameterChange = { p, v -> values = values + (p to v) },
            )
        }
        val orb = compose.onNodeWithTag("referenceHeroOrbInput")
        orb.performTouchInput {
            down(Offset(center.x * .6f, center.y * .8f))
            moveTo(Offset(center.x * 1.6f, center.y * 1.2f))
        }
        compose.waitForIdle()
        assertEquals(
            "Touch engaged",
            orb.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        compose.runOnIdle {
            assertEquals(.80f, values[ParameterId.MORPH] ?: -1f, .05f)
            assertTrue((values[ParameterId.MOVEMENT] ?: -1f) > 0f)
        }
        orb.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("Touch ready",
            orb.fetchSemanticsNode().config[SemanticsProperties.StateDescription])

        orb.performTouchInput {
            down(Offset(center.x, center.y))
            moveTo(Offset(center.x * .4f, center.y * .9f))
        }
        compose.waitForIdle()
        assertEquals("Touch engaged",
            orb.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        orb.performTouchInput { cancel() }
        compose.waitForIdle()
        assertEquals("Touch ready",
            orb.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test fun heldXyFingerCannotWriteNewPatchAfterSecondFingerHitsNext() {
        var patchId by mutableStateOf("factory:1")
        var navCount = 0
        val recorded = mutableListOf<Pair<String, ParameterId>>()
        compose.setContent {
            ReferenceSoundHero(
                parameters = mapOf(
                    ParameterId.MORPH to .42f,
                    ParameterId.MOVEMENT to .52f,
                ),
                lfos = emptyList(), routes = emptyList(),
                patchName = "SAME DISPLAY NAME",
                patchId = patchId,
                onParameterChange = { id, _ -> recorded.add(patchId to id) },
                onNextPatch = {
                    navCount++
                    patchId = "factory:2"
                },
            )
        }
        val field = compose.onNodeWithTag("referenceSoundHeroOrb")
        // First establish non-vacuous XY movement on the OLD patch.
        field.performTouchInput {
            val width = center.x * 2f
            val height = center.y * 2f
            down(0, Offset(width * .68f, height * .76f))
            moveTo(0, Offset(width * .62f, height * .82f))
        }
        compose.runOnIdle {
            assertTrue("The old ribbon finger must first edit old sound",
                recorded.any { it.first == "factory:1" })
        }
        // A remains HELD. Second pointer touches the actual NEXT button,
        // proved by its live semantics geometry instead of inferred percent.
        val nextHit = nextPatchHitInsideOrb()
        field.performTouchInput {
            down(1, nextHit)
            advanceEventTime(120L)
            up(1)
        }
        compose.runOnIdle {
            assertEquals(1, navCount)
            assertEquals("factory:2", patchId)
        }
        field.performTouchInput {
            val width = center.x * 2f
            val height = center.y * 2f
            moveTo(0, Offset(width * .27f, height * .92f))
            up(0)
        }
        compose.runOnIdle {
            assertFalse("Stale held XY edited freshly loaded patch: $recorded",
                recorded.any { it.first == "factory:2" })
        }
    }

    @Test fun heldXyFingerCannotWriteNewEngineAfterSecondFingerOpensEngine() {
        var engineName by mutableStateOf("SYNTH ENGINE A")
        var navCount = 0
        val recorded = mutableListOf<String>()
        compose.setContent {
            ReferenceSoundHero(
                parameters = mapOf(
                    ParameterId.MORPH to .32f,
                    ParameterId.MOVEMENT to .51f,
                ),
                lfos = emptyList(), routes = emptyList(),
                engineName = engineName,
                onParameterChange = { _, _ -> recorded.add(engineName) },
                onOpenEngine = {
                    navCount++
                    engineName = "SYNTH ENGINE B"
                },
            )
        }
        val field = compose.onNodeWithTag("referenceSoundHeroOrb")
        field.performTouchInput {
            val width = center.x * 2f
            val height = center.y * 2f
            down(0, Offset(width * .68f, height * .76f))
            moveTo(0, Offset(width * .60f, height * .85f))
        }
        compose.runOnIdle {
            assertTrue(recorded.any { it == "SYNTH ENGINE A" })
        }
        field.performTouchInput {
            val width = center.x * 2f
            val height = center.y * 2f
            // ENGINE has a >=48dp hit box in top RIGHT.
            down(1, Offset(width * .82f, height * .14f))
            up(1)
        }
        compose.runOnIdle {
            assertEquals(1, navCount)
            assertEquals("SYNTH ENGINE B", engineName)
        }
        field.performTouchInput {
            val width = center.x * 2f
            val height = center.y * 2f
            moveTo(0, Offset(width * .16f, height * .95f))
            up(0)
        }
        compose.runOnIdle {
            assertFalse("Old XY gesture wrote new engine's parameters: $recorded",
                recorded.any { it == "SYNTH ENGINE B" })
        }
    }

    @Test fun patchSwitchCannotLetCanceledOldOrbPointerDarkenFreshFinger() {
        var patchId by mutableStateOf("A")
        compose.setContent {
            ReferenceSoundHero(
                parameters = mapOf(ParameterId.MORPH to .45f),
                lfos = emptyList(), routes = emptyList(),
                patchId = patchId,
                onParameterChange = { _, _ -> },
                onNextPatch = { patchId = "B" },
            )
        }
        val field = compose.onNodeWithTag("referenceSoundHeroOrb")
        val orb = compose.onNodeWithTag("referenceHeroOrbInput")
        field.performTouchInput {
            val width = center.x * 2f
            val height = center.y * 2f
            down(0, Offset(width * .64f, height * .55f))
        }
        compose.waitForIdle()
        assertEquals("Touch engaged",
            orb.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        val nextHit = nextPatchHitInsideOrb()
        field.performTouchInput {
            down(1, nextHit)
            advanceEventTime(120L)
            up(1)
        }
        compose.runOnIdle { assertEquals("B", patchId) }
        // While stale finger 0 is still held, an independent new finger 2
        // enters the *new patch*. Old pointer cancellation must not clear it.
        field.performTouchInput {
            val width = center.x * 2f
            val height = center.y * 2f
            down(2, Offset(width * .73f, height * .58f))
        }
        compose.waitForIdle()
        assertEquals("Touch engaged",
            orb.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        field.performTouchInput { up(0) }
        compose.waitForIdle()
        assertEquals("Touch engaged",
            orb.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        field.performTouchInput { up(2) }
        compose.waitForIdle()
        assertEquals("Touch ready",
            orb.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

}
