package com.orbital.synth.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReferenceXyLiveHostTest {
    @get:Rule val compose = createComposeRule()

    private fun initialParameters(engine: Float = 0f): Map<ParameterId, Float> =
        mapOf(
            ParameterId.ENGINE to engine,
            ParameterId.MORPH to .35f,
            ParameterId.MOVEMENT to .25f,
            ParameterId.CUTOFF to .15f,
            ParameterId.RESONANCE to .20f,
            ParameterId.WAVE to .30f,
            ParameterId.FX_AMOUNT to .18f,
            ParameterId.LFO_RATE to .22f,
            ParameterId.LFO_DEPTH to .23f,
            ParameterId.CHARACTER to .26f,
        )

    @Test fun realOrbRoutesOnlySelectedFilterAxesAndPreservesUnrelatedParameters() {
        var mode by mutableStateOf(ReferenceXyMode.PERFORMANCE)
        var params by mutableStateOf(initialParameters())
        val edits = mutableListOf<ParameterId>()
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                ReferenceSoundHero(
                    parameters = params,
                    lfos = emptyList(),
                    routes = emptyList(),
                    onParameterChange = { id, value ->
                        edits += id
                        params = params + (id to value)
                    },
                    xyMode = mode,
                    onXyModeSelected = { mode = it },
                    compact = true,
                )
            }
        }
        compose.onNodeWithTag("referenceXyModeButton").performClick()
        compose.onNodeWithTag("referenceXyModeFILTER").performClick()
        compose.runOnIdle {
            assertEquals(ReferenceXyMode.FILTER, mode)
            assertTrue(edits.isEmpty())
        }
        compose.onNodeWithTag("referenceHeroOrbInput").performTouchInput {
            click(Offset(center.x * 1.4f, center.y * 1.1f))
        }
        compose.runOnIdle {
            assertTrue(edits.contains(ParameterId.CUTOFF))
            assertTrue(edits.contains(ParameterId.RESONANCE))
            assertEquals(setOf(ParameterId.CUTOFF, ParameterId.RESONANCE), edits.toSet())
            assertEquals(.70f, params[ParameterId.CUTOFF] ?: -1f, .04f)
            assertEquals(.35f, params[ParameterId.MORPH] ?: -1f, .0001f)
            assertEquals(.25f, params[ParameterId.MOVEMENT] ?: -1f, .0001f)
            assertEquals(.30f, params[ParameterId.WAVE] ?: -1f, .0001f)
        }
    }

    @Test fun filterRibbonAndOrbConnectContinuouslyAcrossOriginalFoldedSeam() {
        var params by mutableStateOf(initialParameters())
        compose.setContent {
            ReferenceSoundHero(
                parameters = params,
                lfos = emptyList(),
                routes = emptyList(),
                onParameterChange = { id, value -> params = params + (id to value) },
                xyMode = ReferenceXyMode.FILTER,
                onXyModeSelected = { },
                compact = true,
            )
        }
        val ribbon = compose.onNodeWithTag("referenceExpressionRibbon")
        ribbon.performTouchInput {
            click(Offset(center.x * 1.5f, center.y * .04f))
        }
        val ribbonValue = params[ParameterId.RESONANCE] ?: -1f
        compose.onNodeWithTag("referenceHeroOrbInput").performTouchInput {
            // The seam is 230dp - 88dp = 142dp in the same hero.
            // Slightly above it must be close to ribbon's upper edge.
            val seam = center.y * 2f * (142f / 230f)
            click(Offset(center.x * 1.5f, seam - center.y * 2f * (3f / 230f)))
        }
        val orbValue = params[ParameterId.RESONANCE] ?: -1f
        compose.runOnIdle {
            assertTrue("Folded-Y ribbon must reach its full range", ribbonValue > .95f)
            assertTrue("Folded-Y orb must reach matching seam range", orbValue > .95f)
            assertTrue("Crossing seam must not jump a new mode's Y value",
                abs(orbValue - ribbonValue) < .07f)
            assertEquals(.75f, params[ParameterId.CUTOFF] ?: -1f, .04f)
        }
    }

    @Test fun modeSwitchWhileOldOrbFingerHeldNeverWritesNewAxesButFreshFingerDoes() {
        var mode by mutableStateOf(ReferenceXyMode.PERFORMANCE)
        var params by mutableStateOf(initialParameters())
        val edits = mutableListOf<Pair<ReferenceXyMode, ParameterId>>()
        compose.setContent {
            ReferenceSoundHero(
                parameters = params,
                lfos = emptyList(), routes = emptyList(),
                onParameterChange = { id, value ->
                    edits += mode to id
                    params = params + (id to value)
                },
                xyMode = mode,
                onXyModeSelected = { mode = it },
            )
        }
        val hero = compose.onNodeWithTag("referenceSoundHero")
        val heroOrigin = hero.fetchSemanticsNode().boundsInRoot.topLeft
        val orbRect = compose.onNodeWithTag("referenceHeroOrbInput")
            .fetchSemanticsNode().boundsInRoot
        val a = orbRect.center + Offset(42f, 0f) - heroOrigin
        hero.performTouchInput {
            down(0, a)
            moveTo(0, a + Offset(12f, 11f))
        }
        compose.runOnIdle {
            assertTrue("A must perform a genuine old-mode edit",
                edits.any { it.first == ReferenceXyMode.PERFORMANCE })
        }
        val railRect = compose.onNodeWithTag("referenceXyModeButton")
            .fetchSemanticsNode().boundsInRoot
        hero.performTouchInput {
            down(1, railRect.center - heroOrigin)
            advanceEventTime(120L)
            up(1)
        }
        val filterRect = compose.onNodeWithTag("referenceXyModeFILTER")
            .fetchSemanticsNode().boundsInRoot
        hero.performTouchInput {
            down(2, filterRect.center - heroOrigin)
            advanceEventTime(120L)
            up(2)
        }
        compose.runOnIdle {
            assertEquals(ReferenceXyMode.FILTER, mode)
        }
        val beforeOldMove = edits.size
        hero.performTouchInput {
            moveTo(0, a + Offset(45f, 29f))
        }
        compose.runOnIdle {
            assertEquals("Held A may not reattack FILTER on an epoch remount",
                beforeOldMove, edits.size)
        }
        val fresh = orbRect.center + Offset(65f, -12f) - heroOrigin
        hero.performTouchInput {
            down(3, fresh)
        }
        compose.runOnIdle {
            assertTrue(edits.drop(beforeOldMove).any {
                it.first == ReferenceXyMode.FILTER && it.second == ParameterId.CUTOFF
            })
            assertTrue(edits.drop(beforeOldMove).any {
                it.first == ReferenceXyMode.FILTER && it.second == ParameterId.RESONANCE
            })
        }
        val beforeARelease = edits.size
        hero.performTouchInput { up(0) }
        compose.runOnIdle {
            assertEquals("Old UP must never write or cancel fresh XY edits",
                beforeARelease, edits.size)
        }
        hero.performTouchInput { up(3) }
    }


    @Test fun portrait411And360AtLargeFontKeepAllFourMiniPlotsAndAnchoredGlassKeys() {
        var screenWidth by mutableStateOf(411)
        var parameters by mutableStateOf(initialParameters())
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2.56875f, fontScale = 1.3f),
            ) {
                Box(Modifier.width(screenWidth.dp).height(820.dp)) {
                    OrbitalScreen(
                        state = OrbitalUiState(parameters = parameters),
                        onNoteOn = {},
                        onNoteOff = {},
                        onParameterChange = { id, value ->
                            parameters = parameters + (id to value)
                        },
                        onModeChange = {},
                        onOctaveChange = {},
                    )
                }
            }
        }
        listOf(411, 360).forEach { width ->
            compose.runOnIdle { screenWidth = width }
            compose.waitForIdle()
            val hero = compose.onNodeWithTag("referenceSoundHero")
                .fetchSemanticsNode().boundsInRoot
            val orb = compose.onNodeWithTag("referenceSoundHeroOrb")
                .fetchSemanticsNode().boundsInRoot
            val rail = compose.onNodeWithTag("referenceXyModeButton")
                .fetchSemanticsNode().boundsInRoot
            val keybed = compose.onNodeWithTag("referencePerformanceKeybed")
                .fetchSemanticsNode().boundsInRoot
            val pixelsPerDp = 2.56875f
            assertTrue("Compact portrait orb must keep 230dp at width $width",
                abs(orb.height - 230f * pixelsPerDp) < 3.5f)
            assertTrue("XY rail must be physically inside existing orb, width $width",
                rail.left >= orb.left && rail.right <= orb.right &&
                    rail.top >= orb.top && rail.bottom <= orb.bottom)
            assertTrue("XY rail may not extend/push the hero or playable glass",
                orb.bottom <= hero.bottom && hero.bottom <= keybed.top)
            assertTrue("Anchored 12-strip piano must retain >=100dp vertical play area",
                keybed.height >= 100f * pixelsPerDp)
            for (tag in listOf(
                "referenceKnobWAVE", "referenceKnobCUTOFF",
                "referenceKnobRESONANCE", "referenceKnobMOVEMENT",
                "referenceKnobFX_AMOUNT", "referenceMiniEnv",
                "referenceMiniLfo", "referenceMiniExpr", "referenceMiniMod",
            )) {
                compose.onNodeWithTag(tag).assertIsDisplayed()
            }
            // This is the genuine Compose-rendered artwork bitmap, not
            // prerecorded fixture pixels and not fake spectrum telemetry.
            val image = compose.onNodeWithTag(
                "orbitalPlasmaField", useUnmergedTree = true,
            ).captureToImage().toPixelMap()
            var visiblyColorful = 0
            val stride = (image.width / 22).coerceAtLeast(1)
            for (y in 20 until image.height - 20 step stride) {
                for (x in 30 until image.width - 30 step stride) {
                    val pixel = image[x, y]
                    val high = maxOf(pixel.red, pixel.green, pixel.blue)
                    val low = minOf(pixel.red, pixel.green, pixel.blue)
                    if (high > .16f && high - low > .04f) visiblyColorful++
                }
            }
            assertTrue("SOUND plasma became dark/unrendered at width $width",
                visiblyColorful > 12)
        }
    }

    @Test fun selectedLiveFilterParametersChangeActualPlasmaPixelsWithoutFakePcm() {
        var params by mutableStateOf(initialParameters())
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                ReferenceSoundHero(
                    parameters = params,
                    lfos = emptyList(), routes = emptyList(),
                    onParameterChange = { id, value -> params = params + (id to value) },
                    xyMode = ReferenceXyMode.FILTER,
                    onXyModeSelected = {},
                    compact = true,
                    audioRms = 0f,
                    audioPeak = 0f,
                )
            }
        }
        val plasma = compose.onNodeWithTag("orbitalPlasmaField", useUnmergedTree = true)
        val before = plasma.captureToImage().toPixelMap()
        compose.onNodeWithTag("referenceHeroOrbInput").performTouchInput {
            click(Offset(center.x * 1.66f, center.y * 1.22f))
        }
        val after = plasma.captureToImage().toPixelMap()
        val step = (before.width / 24).coerceAtLeast(1)
        var changed = 0
        for (py in 30 until minOf(before.height, after.height) - 30 step step) {
            for (px in 50 until minOf(before.width, after.width) - 50 step step) {
                val a = before[px, py]
                val b = after[px, py]
                val delta = abs(a.red - b.red) + abs(a.green - b.green) +
                    abs(a.blue - b.blue)
                if (delta > .065f) changed++
            }
        }
        compose.runOnIdle {
            assertTrue("Real FILTER parameter changes must alter plasma art, not PCM claims",
                changed > 12)
            assertTrue((params[ParameterId.CUTOFF] ?: 0f) > .75f)
            assertFalse((params[ParameterId.RESONANCE] ?: 0f) == .20f)
        }
    }
}
