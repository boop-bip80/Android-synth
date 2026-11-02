package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.music.SequencerNote
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
            down(center)
            up()
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
        val actions = orbit.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
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

    @Test fun compactSoundLayoutKeepsLabelsOutsideOrbitAndKeyboard() {
        val density = compose.activity.resources.displayMetrics.density
        var previousPatchRequests = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                Box(Modifier.width(360.dp).height(780.dp)) {
                    OrbitalScreen(
                        state = OrbitalUiState(presetCount = 16),
                        onNoteOn = {}, onNoteOff = {},
                        onParameterChange = { _, _ -> },
                        onModeChange = {}, onOctaveChange = {},
                        onPreviousPatch = { previousPatchRequests++ },
                        onNextPatch = {},
                    )
                }
            }
        }

        val bank = compose.onNodeWithTag("compactBankLabel").fetchSemanticsNode().boundsInRoot
        val engine = compose.onNodeWithTag("compactEngineLabel").fetchSemanticsNode().boundsInRoot
        val patch = compose.onNodeWithTag("compactPatchControls").fetchSemanticsNode().boundsInRoot
        val orbit = compose.onNodeWithTag("compactOrbitViewport").fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithTag("compactOrbitHint").fetchSemanticsNode().boundsInRoot
        val graphToggle = compose.onNodeWithTag("compactModulationToggle").fetchSemanticsNode().boundsInRoot
        val keyboard = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).fetchSemanticsNode().boundsInRoot

        assertTrue("Bank and engine captions overlap", bank.right <= engine.left)
        assertTrue("Patch controls intersect the orbit", patch.bottom <= orbit.top)
        assertTrue("Orbital instruction overlaps artwork", orbit.bottom <= hint.top)
        assertTrue("Modulation control overlaps instruction", hint.bottom <= graphToggle.top)
        assertTrue("Compact modulation control is hidden behind keyboard",
            graphToggle.bottom <= keyboard.top)
        assertTrue("Compact modulation control is partially clipped by the editor",
            graphToggle.height >= 47f * density)
        compose.onNodeWithText("MODULATION GRAPH").assertIsDisplayed()

        compose.onNodeWithTag("compactModulationGraph").assertDoesNotExist()
        compose.onNodeWithContentDescription("Previous factory preset").performClick()
        compose.runOnIdle { assertEquals(1, previousPatchRequests) }
        compose.onNodeWithContentDescription("Show modulation graph").performClick()
        compose.onNodeWithTag("compactModulationGraph").assertExists()
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
        ).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(13, actions.size)
        assertTrue("Play C sharp 4" in actions)
        assertTrue("Play A sharp 4" in actions)
        assertTrue("Play C 5" in actions)
    }

    @Test fun wideKeyboardExposesBothOctavesToAccessibility() {
        val notes = keyboardAccessibleNotes(15, 4)
        assertEquals(25, notes.size)
        assertEquals(60 to "Play C 4", notes.first())
        assertTrue(73 to "Play C sharp 5" in notes)
        assertEquals(84 to "Play C 6", notes.last())
        // MIDI notes above 127 cannot be played, even on the wide keyboard.
        val highRange = keyboardAccessibleNotes(15, 8)
        assertEquals(20, highRange.size)
        assertEquals(127, highRange.last().first)
    }

    @Test fun octaveButtonsExposeDisabledBoundaryStates() {
        val octave = mutableIntStateOf(0)
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(octave = octave.intValue),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {},
                onOctaveChange = { octave.intValue = it },
            )
        }
        compose.onNodeWithContentDescription("Lower octave").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Raise octave").performClick()
        compose.runOnIdle { assertEquals(1, octave.intValue) }

        compose.runOnIdle { octave.intValue = 8 }
        compose.onNodeWithContentDescription("Raise octave").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Lower octave").performClick()
        compose.runOnIdle { assertEquals(7, octave.intValue) }
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
        assertEquals(0, compose.onAllNodesWithContentDescription("Step 2 of 1, on").fetchSemanticsNodes().size)
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
        compose.onNodeWithContentDescription("Choose sequence").performClick()
        compose.onNodeWithContentDescription("Select sequence Comet Cascade").performClick()
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
        compose.onNodeWithContentDescription("Choose sequence").assertIsNotEnabled()
        assertEquals(0, compose.onAllNodesWithText("CHOOSE SEQUENCE").fetchSemanticsNodes().size)
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
        compose.onNodeWithContentDescription("Sequence library unavailable").assertIsNotEnabled()
    }

    @Test fun expressionCoordinatesClampOutOfBoundsAndGuardZeroSize() {
        assertEquals(.25f to .75f, expressionCoordinates(25f, 25f, 100f, 100f))
        assertEquals(0f to 1f, expressionCoordinates(-500f, -20f, 100f, 100f))
        assertEquals(1f to 0f, expressionCoordinates(500f, 200f, 100f, 100f))
        assertEquals(.5f to .5f, expressionCoordinates(10f, 10f, 0f, 100f))
    }

    @Test fun stepInspectorEditsPitchWithoutDroppingChordVoices() {
        val edits = mutableListOf<Pair<Int, List<SequencerNote>>>()
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(
                    enabledSteps = setOf(0),
                    sequenceNotesByStep = listOf(
                        listOf(SequencerNote(60), SequencerNote(67, velocity = 110)),
                    ),
                ),
                onNoteOn = {}, onNoteOff = {}, onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
                onSequencerToggle = {}, onStepToggle = {},
                onSequencerStepNotesChange = { step, notes -> edits += step to notes },
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithContentDescription("Increase step note pitch").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(0, edits.single().first)
            assertEquals(61, edits.single().second[0].midiNote)
            assertEquals(67, edits.single().second[1].midiNote)
            assertEquals(110, edits.single().second[1].velocity)
        }
    }

    @Test fun emptyStepAddsValidatedDefaultPitchThroughHost() {
        val edits = mutableListOf<Pair<Int, List<SequencerNote>>>()
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(
                    octave = 4,
                    sequenceNotesByStep = listOf(emptyList()),
                ),
                onNoteOn = {}, onNoteOff = {}, onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
                onSequencerToggle = {}, onStepToggle = {},
                onSequencerStepNotesChange = { step, notes -> edits += step to notes },
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithContentDescription("Add note to selected step").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(0, edits.single().first)
            assertEquals(60, edits.single().second.single().midiNote)
        }
    }

    @Test fun sequenceSaveUsesTrimmedNameAndRequiresHostCallback() {
        var saved: String? = null
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {}, onNoteOff = {}, onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
                onSaveSequence = { saved = it },
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithContentDescription("Save sequence as a new pattern").performScrollTo().performClick()
        compose.onNodeWithTag("sequenceNameInput").performTextReplacement("  Solar Pattern  ")
        compose.onNodeWithText("SAVE").performClick()
        compose.runOnIdle { assertEquals("Solar Pattern", saved) }
    }

    @Test fun sequenceEditingAndSaveControlsAreDisabledWithoutHost() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(sequenceNotesByStep = listOf(listOf(SequencerNote(60)))),
                onNoteOn = {}, onNoteOff = {}, onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
            )
        }
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithContentDescription("Save sequence as a new pattern")
            .performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Increase step note pitch")
            .performScrollTo().assertIsNotEnabled()
    }

}
