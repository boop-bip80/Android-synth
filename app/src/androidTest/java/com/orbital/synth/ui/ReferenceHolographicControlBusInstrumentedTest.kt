package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real API35 Compose layout/pointer proof; no screenshot-shaped fake controls. */
@RunWith(AndroidJUnit4::class)
class ReferenceHolographicControlBusInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fiveRealKnobsFloatOverDecorativeBusAndRemainTouchEditableAt360Font13() {
        var params by mutableStateOf(mapOf(
            ParameterId.WAVE to .24f,
            ParameterId.CUTOFF to .37f,
            ParameterId.RESONANCE to .51f,
            ParameterId.MOVEMENT to .63f,
            ParameterId.FX_AMOUNT to .18f,
        ))
        var acceptedRms by mutableFloatStateOf(.05f)
        val opened = mutableListOf<ReferenceMiniSignalDestination>()
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2.5f, 1.3f),
            ) {
                Box(Modifier.width(360.dp)) {
                    ReferenceSoundControls(
                        parameters = params,
                        lfos = emptyList(),
                        routes = emptyList(),
                        onParameterChange = { id, value ->
                            params = params + (id to value)
                        },
                        audioRms = acceptedRms,
                        compact = true,
                        showExpressionRibbon = false,
                        onOpenMiniSignal = { opened += it },
                    )
                }
            }
        }

        val rack = compose.onNodeWithTag("referenceKnobRack")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val wire = compose.onNodeWithTag("referenceControlSignalBus")
            .assertIsDisplayed().assertHasNoClickAction()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Circuit backing must use rack's existing bounds (no extra vertical budget)",
            kotlin.math.abs(rack.width - wire.width) < 2f &&
                kotlin.math.abs(rack.height - wire.height) < 2f)

        // The decorative background must never steal a real parameter swipe.
        compose.onNodeWithTag("referenceKnobWAVE")
            .assertIsDisplayed().performTouchInput {
                swipe(
                    start = center + Offset(0f, size.height * .23f),
                    end = center - Offset(0f, size.height * .23f),
                    durationMillis = 310,
                )
            }
        compose.runOnIdle {
            assertTrue("Existing WAVE macro must still mutate actual host state",
                (params[ParameterId.WAVE] ?: 0f) > .24f)
            acceptedRms = .20f
        }

        val tile = compose.onNodeWithTag("referenceMiniEnv")
            .assertIsDisplayed().assertHasClickAction()
        tile.performTouchInput { down(0, center) }
        compose.onNodeWithTag("referenceMiniEnv").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Touch engaged"),
        )
        tile.performTouchInput { up(0) }
        compose.runOnIdle {
            assertTrue("Full-card touch still routes through actual host callback",
                opened.contains(ReferenceMiniSignalDestination.ENVELOPE))
        }
        compose.onNodeWithTag("referenceMiniEnv").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Ready to edit"),
        )
    }

    @Test fun 350dpLandscapeNeverMountsDecorativePortraitBusOrDisturbsPinnedPiano() {
        compose.setContent {
            Box(Modifier.width(360.dp).height(350.dp)) {
                OrbitalScreen(
                    state = OrbitalUiState(),
                    onNoteOn = {}, onNoteOff = {},
                    onParameterChange = { _, _ -> },
                    onModeChange = {}, onOctaveChange = {},
                )
            }
        }
        compose.onNodeWithTag("referenceControlSignalBus").assertDoesNotExist()
        compose.onNodeWithTag("referenceMiniSignals").assertDoesNotExist()
        val keyboard = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard. Swipe across keys to play; use OCT buttons to change range.",
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Compact live keyboard must retain its 82dp touch geometry",
            keyboard.height >= 82f * density - 1f)
    }
}
