package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbitalScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tabsAndKnobsAreRealSemanticsNodes() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _: ParameterId, _: Float -> },
                onModeChange = {},
                onOctaveChange = {},
            )
        }
        compose.onNodeWithText("O R B I T A L").assertExists()
        compose.onNodeWithContentDescription("CUTOFF").assertExists()
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithText("16-STEP ORBIT").assertExists()
        compose.onNodeWithContentDescription("Tab FX").performClick()
        compose.onNodeWithText("ENVELOPES  /  EFFECTS").assertExists()
        compose.onNodeWithContentDescription("Tab PLAY").performClick()
        compose.onNodeWithText("PERFORMANCE").assertExists()
    }

    @Test fun performanceKeyboardStaysVisibleAcrossTabs() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {},
                onOctaveChange = {},
            )
        }
        val keyboard = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard",
            substring = true,
        )
        keyboard.assertIsDisplayed()
        listOf("Tab FX", "Tab SEQ", "Tab PLAY", "Tab SOUND").forEach { tab ->
            compose.onNodeWithContentDescription(tab).performClick()
            keyboard.assertIsDisplayed()
        }
    }

    @Test fun keyboardRemainsAvailableInCompactHeight() {
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(350.dp)) {
                OrbitalScreen(
                    state = OrbitalUiState(),
                    onNoteOn = {},
                    onNoteOff = {},
                    onParameterChange = { _, _ -> },
                    onModeChange = {},
                    onOctaveChange = {},
                )
            }
        }
        val keyboard = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard",
            substring = true,
        )
        keyboard.assertIsDisplayed()
        compose.onNodeWithContentDescription("Tab FX").performClick()
        keyboard.assertIsDisplayed()
    }

    @Test fun presetArrowsAreAccessibleAndCallNavigation() {
        var previous = 0
        var next = 0
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(
                    patchName = "HORIZON",
                    presetIndex = 1,
                    presetCount = 6,
                ),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {},
                onOctaveChange = {},
                onPreviousPatch = { previous++ },
                onNextPatch = { next++ },
            )
        }
        compose.onNodeWithContentDescription("Previous factory preset").performClick()
        compose.onNodeWithContentDescription("Next factory preset").performClick()
        compose.onNodeWithText("PATCH 2 / 6").assertExists()
        compose.runOnIdle {
            assertEquals(1, previous)
            assertEquals(1, next)
        }
    }

    @Test fun patchArrowsDoNotPretendToWorkWithoutCallbacks() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {},
                onOctaveChange = {},
            )
        }
        compose.onNodeWithContentDescription("Previous factory preset").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next factory preset").assertIsNotEnabled()
    }

    @Test fun soundOrbitIsAnInteractiveExpressionSurface() {
        val updates = mutableMapOf<ParameterId, Float>()
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { id, value -> updates[id] = value },
                onModeChange = {}, onOctaveChange = {},
            )
        }
        compose.onNodeWithTag("orbitalExpression").assertIsDisplayed().performTouchInput {
            click(center)
        }
        compose.runOnIdle {
            assertTrue("Morph touch callback missing", ParameterId.MORPH in updates)
            assertTrue("Movement touch callback missing", ParameterId.MOVEMENT in updates)
            assertEquals(.5f, updates.getValue(ParameterId.MORPH), .02f)
            assertEquals(.5f, updates.getValue(ParameterId.MOVEMENT), .02f)
        }
    }

    @Test fun soundOrbitDragTracksBothAxesAndAdvertisesAccessibleAdjustments() {
        val updates = mutableListOf<Pair<ParameterId, Float>>()
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { id, value -> updates += id to value },
                onModeChange = {}, onOctaveChange = {},
            )
        }
        val orbit = compose.onNodeWithTag("orbitalExpression")
        val actions = orbit.fetchSemanticsNode().config[SemanticsProperties.CustomActions].map { it.label }
        assertTrue("Increase morph" in actions)
        assertTrue("Decrease movement" in actions)
        orbit.performTouchInput {
            down(center)
            moveTo(Offset(center.x + 42f, center.y - 35f))
            moveTo(Offset(center.x + 82f, center.y - 70f))
            up()
        }
        compose.runOnIdle {
            val morphValues = updates.filter { it.first == ParameterId.MORPH }.map { it.second }
            val movementValues = updates.filter { it.first == ParameterId.MOVEMENT }.map { it.second }
            assertTrue("Morph should respond during a drag", morphValues.size >= 2)
            assertTrue("Movement should respond during a drag", movementValues.size >= 2)
            assertTrue("Morph must increase moving right", morphValues.last() > morphValues.first())
            assertTrue("Movement must increase moving upward", movementValues.last() > movementValues.first())
        }
    }

    @Test fun keyboardExposesChromaticAccessibleActions() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
            )
        }
        val actions = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).fetchSemanticsNode().config[SemanticsProperties.CustomActions].map { it.label }
        assertEquals(13, actions.size)
        assertTrue("Play C sharp 4" in actions)
        assertTrue("Play A sharp 4" in actions)
        assertTrue("Play C 5" in actions)
    }

    @Test fun keyboardHitTestingMatchesBlackAndWhiteGeometry() {
        assertEquals(60, keyboardHitNote(25f, 150f, 800f, 160f, 8, 4))
        assertEquals(61, keyboardHitNote(100f, 12f, 800f, 160f, 8, 4))
        assertEquals(62, keyboardHitNote(100f, 140f, 800f, 160f, 8, 4))
        assertEquals(72, keyboardHitNote(799f, 140f, 800f, 160f, 8, 4))
        assertNull(keyboardHitNote(-1f, 10f, 800f, 160f, 8, 4))
        assertNull(keyboardHitNote(900f, 10f, 800f, 160f, 8, 4))
    }

    @Test fun patchLibraryLoadsSelectedSoundAndNamesSavedSound() {
        var selectedId: String? = null
        var savedName: String? = null
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(
                    patchName = "AURORA",
                    patchChoices = listOf(
                        PatchChoice("factory-aurora", "AURORA", true),
                        PatchChoice("user-nebula", "NEBULA", false),
                    ),
                    activePatchId = "factory-aurora",
                ),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
                onSavePatch = { savedName = it },
                onSelectPatch = { selectedId = it },
            )
        }
        compose.onNodeWithContentDescription("Open patch library").performClick()
        compose.onNodeWithContentDescription("Load NEBULA, user patch").performClick()
        compose.runOnIdle { assertEquals("user-nebula", selectedId) }

        compose.onNodeWithContentDescription("Open patch library").performClick()
        compose.onNodeWithContentDescription("Save current sound as a new patch").performClick()
        compose.onNodeWithTag("patchNameInput").performTextReplacement("  New Orbit  ")
        compose.onNodeWithText("SAVE AS").performClick()
        compose.runOnIdle { assertEquals("New Orbit", savedName) }
    }

    @Test fun sequenceStepToggleExposesReadableState() {
        val toggled = mutableListOf<Int>()
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(enabledSteps = setOf(0, 4)),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
                onSequencerToggle = {}, onStepToggle = { toggled += it },
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithContentDescription("Step 1 of 16, on").performClick()
        compose.onNodeWithContentDescription("Step 2 of 16, off").performClick()
        compose.runOnIdle { assertEquals(listOf(0, 1), toggled) }
    }

    @Test fun thirtyTwoStepGridExposesLastStepAndRoutesItsIndex() {
        val toggled = mutableListOf<Int>()
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(sequencerStepCount = 32, enabledSteps = setOf(0, 31)),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
                onSequencerToggle = {}, onStepToggle = { toggled += it },
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithText("32-STEP ORBIT").assertExists()
        compose.onNodeWithContentDescription("Step 32 of 32, on").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(31), toggled) }
        compose.onNodeWithContentDescription("Multitouch piano keyboard", substring = true).assertIsDisplayed()
    }

    @Test fun sixtyFourStepGridScrollsWithoutMovingTheKeyboard() {
        val toggled = mutableListOf<Int>()
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(380.dp)) {
                OrbitalScreen(
                    state = OrbitalUiState(sequencerStepCount = 64, enabledSteps = setOf(0, 63)),
                    onNoteOn = {}, onNoteOff = {},
                    onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
                    onSequencerToggle = {}, onStepToggle = { toggled += it },
                )
            }
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithText("64-STEP ORBIT").assertExists()
        compose.onNodeWithContentDescription("Step 64 of 64, on").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(63), toggled) }
        compose.onNodeWithContentDescription("Multitouch piano keyboard", substring = true).assertIsDisplayed()
    }

    @Test fun singleStepGridClampsLengthsAndDoesNotExposeExtraCells() {
        val toggled = mutableListOf<Int>()
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(sequencerStepCount = 0, enabledSteps = setOf(0, 1)),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
                onSequencerToggle = {}, onStepToggle = { toggled += it },
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithText("1-STEP ORBIT").assertExists()
        compose.onNodeWithContentDescription("Step 1 of 1, on").performClick()
        compose.onNodeWithContentDescription("Step 2 of 1, on").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(0), toggled) }
    }

    @Test fun sequenceLengthPresetButtonsQueueOnlyNonActiveChoices() {
        val requested = mutableListOf<Int>()
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(sequencerStepCount = 32),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
                onSequencerLengthChange = { requested += it },
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithContentDescription("Set sequence length to 32 steps").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Set sequence length to 16 steps").performClick()
        compose.onNodeWithContentDescription("Set sequence length to 64 steps").performClick()
        compose.runOnIdle { assertEquals(listOf(16, 64), requested) }
    }

    @Test fun sequenceLengthStepButtonsEnforceOneAndSixtyFourLimits() {
        var requested = -1
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(sequencerStepCount = 1),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
                onSequencerLengthChange = { requested = it },
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithContentDescription("Decrease sequence length").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Increase sequence length").performClick()
        compose.runOnIdle { assertEquals(2, requested) }
    }

    @Test fun sequenceLengthControlsDoNotPretendToWorkWithoutHost() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(sequencerStepCount = 64),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithContentDescription("Set sequence length to 16 steps").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Increase sequence length").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Decrease sequence length").assertIsNotEnabled()
    }

    @Test fun factorySequenceSelectorShowsActiveSequenceAndDispatchesStableId() {
        var selectedId: String? = null
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(
                    sequenceChoices = listOf(
                        SequenceChoice("aurora", "Aurora Orbit"),
                        SequenceChoice("comet", "Comet Cascade"),
                        SequenceChoice("ion-bass", "Ion Bassline"),
                    ),
                    activeSequenceId = "aurora",
                ),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
                onSelectSequence = { selectedId = it },
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithText("Aurora Orbit").assertExists()
        compose.onNodeWithContentDescription("Choose factory sequence").performClick()
        compose.onNodeWithContentDescription("Select factory sequence Comet Cascade").performClick()
        compose.runOnIdle { assertEquals("comet", selectedId) }
    }

    @Test fun factorySequenceSelectorNeverOffersInvalidOrDuplicateIds() {
        assertEquals(
            listOf(SequenceChoice("aurora", "Aurora Orbit"), SequenceChoice("comet", "Comet Cascade")),
            validSequenceChoices(
                listOf(
                    SequenceChoice("", "Empty ID"),
                    SequenceChoice("aurora", "Aurora Orbit"),
                    SequenceChoice("comet", "Comet Cascade"),
                    SequenceChoice("comet", "Duplicate Comet"),
                    SequenceChoice("blank-name", ""),
                ),
            ),
        )
    }

    @Test fun factorySequenceSelectorIsDisabledWithoutHostCallback() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(
                    sequenceChoices = listOf(SequenceChoice("aurora", "Aurora Orbit")),
                    activeSequenceId = "aurora",
                ),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithContentDescription("Choose factory sequence").assertIsNotEnabled()
        compose.onNodeWithText("CHOOSE SEQUENCE").assertDoesNotExist()
    }

    @Test fun factorySequenceSelectorIsDisabledForEmptyLibrary() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(sequenceChoices = emptyList()),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> }, onModeChange = {}, onOctaveChange = {},
                onSelectSequence = { error("Empty library must not dispatch") },
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithContentDescription("Factory sequences unavailable").assertIsNotEnabled()
    }

    @Test fun expressionCoordinatesClampOutOfBoundsAndGuardZeroSize() {
        assertEquals(.25f to .75f, expressionCoordinates(25f, 25f, 100f, 100f))
        assertEquals(0f to 1f, expressionCoordinates(-500f, -20f, 100f, 100f))
        assertEquals(1f to 0f, expressionCoordinates(500f, 200f, 100f, 100f))
        assertEquals(.5f to .5f, expressionCoordinates(10f, 10f, 0f, 100f))
    }
}
