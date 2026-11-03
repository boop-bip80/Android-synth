package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.orbital.synth.audio.AudioVisualSnapshot
import java.util.concurrent.atomic.AtomicInteger
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
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

    @Test fun soundAndImmersivePollProvidedAudioOnlyWhileTheirRealFieldIsVisible() {
        val reads = AtomicInteger(0)
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
                onReadAudioVisual = {
                    reads.incrementAndGet()
                    AudioVisualSnapshot(
                        rms = .35f, peak = .78f, transient = .42f, brightness = .55f,
                    )
                },
            )
        }
        compose.onNodeWithTag("referenceSoundHero").assertExists()
        compose.waitUntil(4_000L) { reads.get() >= 2 }
        compose.onNodeWithContentDescription("Tab PLAY").performClick()
        compose.waitForIdle()
        Thread.sleep(110L)
        val paused = reads.get()
        Thread.sleep(180L)
        compose.runOnIdle {
            assertEquals("PCM reader must cancel outside SOUND", paused, reads.get())
        }
        compose.onNodeWithTag("immersiveModeToggle").performClick()
        compose.waitUntil(4_000L) { reads.get() > paused + 1 }
        compose.onNodeWithTag("immersiveStage").assertExists()
        compose.onNodeWithTag("immersiveVisualField").assertExists()
        compose.onNodeWithTag("immersiveExit").performClick()
        compose.onNodeWithTag("immersiveStage").assertDoesNotExist()
    }

    @Test fun oscillatorWorkbenchEditsLiveLayersWithoutTouchingOtherVoices() {
        val liveSlots = mutableStateOf(List(8) { OscillatorSlot() })
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(oscillatorSlots = liveSlots.value),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {},
                onOctaveChange = {},
                onOscillatorSlotChange = { index, next ->
                    liveSlots.value = liveSlots.value.toMutableList().apply { set(index, next) }
                },
            )
        }

        compose.onNodeWithTag("referenceSoundAdvancedToggle")
            .performScrollTo().performClick()
        compose.onNodeWithTag("oscillatorRack").assertExists()
        compose.onNodeWithTag("oscillatorEnable").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(liveSlots.value[0].enabled)
            assertTrue(liveSlots.value[0].level > 0f)
        }

        compose.onNodeWithTag("oscillatorWave2").performScrollTo().assertIsDisplayed().performClick()
        // A new immutable slot revision must be observed before another control
        // is edited; otherwise a delayed Compose frame can overwrite the wave.
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(.5f, liveSlots.value[0].waveform)
        }
        compose.onNodeWithTag("oscillatorCoarseUp").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(.5f, liveSlots.value[0].waveform)
            assertEquals(1, liveSlots.value[0].coarseSemitones)
        }

        compose.onNodeWithTag("oscillatorAdd").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, liveSlots.value.count { it.enabled })
            assertTrue(liveSlots.value[1].enabled)
            assertEquals(.5f, liveSlots.value[0].waveform)
        }

        compose.onNodeWithTag("oscillatorRemove").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, liveSlots.value.count { it.enabled })
            assertTrue(liveSlots.value[0].enabled)
        }
    }

    @Test fun fxTabConnectsTheConstellationEditorToHostRouteState() {
        val lfos = mutableStateOf(List(8) { ModulationLfoConfig(enabled = it == 0) })
        val cables = mutableStateOf(List(64) { ModulationRouteConfig() })
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(
                    modulationLfos = lfos.value,
                    modulationRoutes = cables.value,
                ),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {},
                onOctaveChange = {},
                onModulationLfoChange = { index, next ->
                    lfos.value = lfos.value.toMutableList().apply { set(index, next) }
                },
                onModulationRouteChange = { index, next ->
                    cables.value = cables.value.toMutableList().apply { set(index, next) }
                },
                onModulationRouteClear = { index ->
                    cables.value = cables.value.toMutableList().apply {
                        set(index, ModulationRouteConfig())
                    }
                },
                onModulationTempoChange = {},
            )
        }
        compose.onNodeWithContentDescription("Tab FX").performClick()
        compose.onNodeWithTag("modulationConstellationPanel").assertExists()
        compose.onNodeWithContentDescription("Toggle route 1")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(cables.value[0].enabled) }
        compose.onNodeWithContentDescription("Clear route 1")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(!cables.value[0].enabled) }
    }

    @Test fun realSoundGraphOpensOnlyWithFullHostRoutesAndEditsAudibleDspSlots() {
        val layers = mutableStateOf(List(8) { OscillatorSlot() })
        val lfos = List(8) { ModulationLfoConfig(enabled = true) }
        val cables = mutableStateOf(List(64) { ModulationRouteConfig() })
        val effects = mutableStateOf(
            List(6) { index ->
                if (index == 0) FxModuleConfig(
                    type = FxModuleType.CHORUS, enabled = true, wet = .51f,
                ) else FxModuleConfig()
            },
        )
        val moves = mutableListOf<Pair<Int, Int>>()
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(
                    oscillatorSlots = layers.value,
                    modulationLfos = lfos,
                    modulationRoutes = cables.value,
                    fxSlots = effects.value,
                    modulationSteps = ModulationStepConfig(enabled = true),
                ),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
                onOscillatorSlotChange = { index, config ->
                    layers.value = layers.value.toMutableList().apply { set(index, config) }
                },
                onModulationRouteChange = { index, config ->
                    cables.value = cables.value.toMutableList().apply { set(index, config) }
                },
                onModulationRouteClear = { index ->
                    cables.value = cables.value.toMutableList().apply {
                        set(index, ModulationRouteConfig())
                    }
                },
                onFxMoveSlot = { from, to ->
                    moves += from to to
                    effects.value = effects.value.toMutableList().apply {
                        add(to, removeAt(from))
                    }
                },
            )
        }
        // Hidden until explicitly requested: the original plasma remains
        // first-viewport focus; this is the REAL advanced routing canvas.
        compose.onNodeWithTag("patchGraphPanel").assertDoesNotExist()
        compose.onNodeWithTag("referenceSoundGraphToggle")
            .performScrollTo().performClick()
        compose.onNodeWithTag("patchGraphPanel").assertExists()
        compose.onNodeWithTag("patchLfo1").performScrollTo().performClick()
        compose.onNodeWithTag("patchBusCUTOFF").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(cables.value[0].enabled)
            assertEquals(ModSource.LFO2, cables.value[0].source)
            assertEquals(ModDestination.CUTOFF, cables.value[0].destination)
        }
        compose.onNodeWithTag("patchOscillator0").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(layers.value[0].enabled)
            assertTrue(layers.value[0].level > 0f)
        }
        compose.onNodeWithTag("patchFxMoveRight").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(0 to 1), moves)
            assertEquals(FxModuleType.CHORUS, effects.value[1].type)
        }
        compose.onNodeWithTag("referenceSoundGraphToggle")
            .performScrollTo().performClick()
        compose.onNodeWithTag("patchGraphPanel").assertDoesNotExist()
    }

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
        compose.onNodeWithTag("referenceKnobCUTOFF").assertExists()
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithText("16-STEP ORBIT").assertExists()
        compose.onNodeWithContentDescription("Tab FX").performClick()
        compose.onNodeWithText("ENVELOPES  /  EFFECTS").assertExists()
        compose.onNodeWithContentDescription("Tab PLAY").performClick()
        compose.onNodeWithText("PERFORMANCE").assertExists()
    }

    @Test fun soundUsesOriginalChromaticStripsWhileOtherTabsKeepRealPiano() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
            )
        }
        val glass = compose.onNodeWithTag("referencePerformanceKeybed")
        val piano = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        )
        glass.assertIsDisplayed()
        piano.assertDoesNotExist()
        listOf("Tab FX", "Tab SEQ", "Tab PLAY").forEach { tab ->
            compose.onNodeWithContentDescription(tab).performClick()
            piano.assertIsDisplayed()
            glass.assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("Tab SOUND").performClick()
        glass.assertIsDisplayed()
        piano.assertDoesNotExist()
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
        compose.onNodeWithTag("referenceHeroPreviousPatch").performClick()
        compose.onNodeWithTag("referenceHeroNextPatch").performClick()
        compose.onNodeWithTag("referenceHeroPatchName").assertExists()
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
        compose.onNodeWithTag("referenceHeroPreviousPatch").assertDoesNotExist()
        compose.onNodeWithTag("referenceHeroNextPatch").assertDoesNotExist()
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
        compose.onNodeWithTag("referenceHeroOrbInput").assertIsDisplayed().performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle {
            assertTrue("Morph touch callback missing", ParameterId.MORPH in updates)
            assertTrue("Movement touch callback missing", ParameterId.MOVEMENT in updates)
            assertEquals(.5f, updates.getValue(ParameterId.MORPH), .02f)
            // Orb Y folds continuously into the overlaid ribbon at 142dp.
            assertEquals(.81f, updates.getValue(ParameterId.MOVEMENT), .04f)
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
        val orbit = compose.onNodeWithTag("referenceHeroOrbInput")
        val actions = orbit.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertTrue("Increase morph" in actions)
        assertTrue("Decrease movement" in actions)
        orbit.performTouchInput {
            down(Offset(center.x * .7f, center.y * .4f))
            moveTo(Offset(center.x + 42f, center.y * .6f))
            moveTo(Offset(center.x + 82f, center.y * .8f))
            up()
        }
        compose.runOnIdle {
            val morphValues = updates.filter { it.first == ParameterId.MORPH }.map { it.second }
            val movementValues = updates.filter { it.first == ParameterId.MOVEMENT }.map { it.second }
            assertTrue("Morph should respond during a drag", morphValues.size >= 2)
            assertTrue("Movement should respond during a drag", movementValues.size >= 2)
            assertTrue("Morph must increase moving right", morphValues.last() > morphValues.first())
            assertTrue("Movement increases moving toward the seamless XY seam",
                movementValues.last() > movementValues.first())
        }
    }

    @Test fun compactPortraitReferenceHeroKeepsOverlayControlsAndKeysLegible() {
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
        val orb = compose.onNodeWithTag("referenceSoundHeroOrb")
            .fetchSemanticsNode().boundsInRoot
        val ribbon = compose.onNodeWithTag("referenceExpressionRibbon",
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val keybed = compose.onNodeWithTag("referencePerformanceKeybed")
            .fetchSemanticsNode().boundsInRoot
        val patch = compose.onNodeWithTag("referenceHeroPatchName")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("PATCH label should float inside the real orb",
            patch.top >= orb.top && patch.bottom < orb.bottom)
        assertTrue("Real XY ribbon must visually overlay plasma",
            ribbon.top > orb.top && ribbon.bottom <= orb.bottom + 2f)
        assertTrue("Reference artwork must not overlap anchored playable keys",
            orb.bottom <= keybed.top)
        assertTrue("Chromatic touch keybed too short to play",
            keybed.height >= 90f * density)
        compose.onNodeWithTag("referenceKnobRack").assertExists()
        compose.onNodeWithTag("referenceMiniSignals").assertExists()
        compose.onNodeWithTag("compactPatchControls").assertDoesNotExist()
        compose.onNodeWithTag("referenceHeroPreviousPatch").performClick()
        compose.runOnIdle { assertEquals(1, previousPatchRequests) }
        compose.onNodeWithTag("referenceSoundAdvancedToggle")
            .performScrollTo().performClick()
        compose.onNodeWithTag("referenceSoundAdvancedToggle").assertExists()
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
        compose.onNodeWithContentDescription("Tab PLAY").performClick()
        val actions = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(13, actions.size)
        assertTrue("Play C sharp 4" in actions)
        assertTrue("Play A sharp 4" in actions)
        assertTrue("Play C 5" in actions)
    }

    @Test fun heldSoundChordMustReleaseOnTabSwitchAndNewSoundKeybedCanReattack() {
        val events = mutableListOf<String>()
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(octave = 4),
                onNoteOn = { events.add("ON:$it") },
                onNoteOff = { events.add("OFF:$it") },
                onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
            )
        }
        compose.onNodeWithTag("referencePerformanceKeybed").performTouchInput {
            val width = center.x * 2f
            down(0, Offset(width * .06f, center.y))
            down(1, Offset(width * .59f, center.y))
        }
        compose.runOnIdle {
            assertEquals(listOf("ON:60", "ON:67"), events)
        }
        compose.onNodeWithContentDescription("Tab PLAY").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("ON:60", "ON:67", "OFF:60", "OFF:67"), events)
        }
        compose.onNodeWithTag("referencePerformanceKeybed").assertDoesNotExist()
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertExists()
        compose.onNodeWithContentDescription("Tab SOUND").performClick()
        compose.onNodeWithTag("referencePerformanceKeybed").performTouchInput {
            val width = center.x * 2f
            down(2, Offset(width * .06f, center.y))
            up(2)
        }
        compose.runOnIdle {
            assertEquals(
                listOf("ON:60", "ON:67", "OFF:60", "OFF:67", "ON:60", "OFF:60"),
                events,
            )
        }
    }

    @Test fun savingToDifferentPatchIdKeepsHeldNoteButSameIdRecallEpochResetsIt() {
        val state = mutableStateOf(OrbitalUiState(
            activePatchId = "user:a", performanceResetEpoch = 0L, octave = 4,
        ))
        val on = mutableListOf<Int>()
        val off = mutableListOf<Int>()
        compose.setContent {
            OrbitalScreen(
                state = state.value,
                onNoteOn = { on.add(it) },
                onNoteOff = { off.add(it) },
                onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
            )
        }
        val first = compose.onNodeWithTag("referencePerformanceKeybed")
        first.performTouchInput {
            down(0, Offset(center.x * .10f, center.y))
        }
        compose.runOnIdle {
            assertEquals(listOf(60), on)
            // SAVE AS changes identity without panicking an active voice.
            state.value = state.value.copy(activePatchId = "user:b")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Harmless save-as must not cut a held note", emptyList<Int>(), off)
            // Selecting the SAME ID now recalls sound and panics its notes.
            state.value = state.value.copy(performanceResetEpoch = 1L)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(60), off) }
        compose.onNodeWithTag("referencePerformanceKeybed").performTouchInput {
            down(1, Offset(center.x * .10f, center.y))
            up(1)
        }
        compose.runOnIdle {
            assertEquals(listOf(60, 60), on)
            assertEquals(listOf(60, 60), off)
        }
    }

    @Test fun audioReaderUsesLatestHostCallbackAcrossRapidKnobStateRecompositions() {
        val state = mutableStateOf(OrbitalUiState())
        val reads = AtomicInteger(0)
        compose.setContent {
            OrbitalScreen(
                state = state.value,
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
                onReadAudioVisual = {
                    reads.incrementAndGet()
                    AudioVisualSnapshot(rms = .14f, peak = .35f)
                },
            )
        }
        compose.waitUntil(4_000L) { reads.get() >= 3 }
        val before = reads.get()
        // A churn of host-owned knobs must NOT relaunch the PCM reader as
        // if onReadAudioVisual were a LaunchedEffect identity key.
        repeat(18) { i ->
            compose.runOnIdle {
                state.value = state.value.copy(parameters = mapOf(
                    ParameterId.WAVE to ((i % 11) / 10f),
                ))
            }
        }
        Thread.sleep(260L)
        val after = reads.get()
        assertTrue("Real PCM poll silently stopped after control changes", after > before)
        assertTrue("20Hz reader restarted excessively: before=$before after=$after",
            after - before <= 11)
        compose.onNodeWithContentDescription("Tab FX").performClick()
        compose.waitForIdle()
        Thread.sleep(90L)
        val paused = reads.get()
        Thread.sleep(180L)
        assertEquals("No PCM poll while FX tab is showing", paused, reads.get())
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
