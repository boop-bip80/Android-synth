package com.orbital.synth.ui

import android.content.Context
import android.os.Process
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.MainActivity
import com.orbital.synth.audio.AndroidAudioOutput
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import com.orbital.synth.core.preset.PatchCodec
import com.orbital.synth.preset.PatchOutcome
import com.orbital.synth.preset.UserPatchRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in two-phase regression: prepare; host adb force-stop; cold relaunch;
 * verify in a DIFFERENT Android OS process. ActivityScenario.recreate alone
 * cannot prove process-death persistence. Default instrumentation explicitly
 * skips both phases; the CI driver invokes each and checks its result.
 * Test data is one unique user patch and one private marker file.
 */
@RunWith(AndroidJUnit4::class)
class StepProcessRestartInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val marker: Path
        get() = context.filesDir.toPath().resolve("orbital-step-process-smoke.marker")

    private fun requirePhase(phase: String) {
        assumeTrue(
            "Opt-in two-process test: CI supplies orbitalStepRestartPhase",
            InstrumentationRegistry.getArguments().getString("orbitalStepRestartPhase") == phase,
        )
    }

    private fun repository(): UserPatchRepository =
        UserPatchRepository(context, OrbitalSynth(sampleRate = 8_000, maxVoices = 2))

    private fun synthOf(scenario: ActivityScenario<MainActivity>): OrbitalSynth {
        lateinit var result: OrbitalSynth
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("synth")
            field.isAccessible = true
            result = field.get(activity) as OrbitalSynth
        }
        return result
    }

    private fun outputOf(scenario: ActivityScenario<MainActivity>): AndroidAudioOutput {
        lateinit var result: AndroidAudioOutput
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("audioOutput")
            field.isAccessible = true
            result = field.get(activity) as AndroidAudioOutput
        }
        return result
    }

    private fun assertClockAdvances(synth: OrbitalSynth) {
        val original = synth.modulationStepPlayhead
        compose.waitUntil(7_000L) { synth.modulationStepPlayhead != original }
        assertTrue("STEP audio clock has not been armed", synth.modulationSteps.enabled)
    }

    private fun verifyPlayFocusReturn(
        scenario: ActivityScenario<MainActivity>,
        synth: OrbitalSynth,
        audio: AndroidAudioOutput,
    ) {
        compose.onNodeWithContentDescription("Tab PLAY").performClick()
        compose.onNodeWithContentDescription("Tab PLAY").assertIsSelected()
        compose.onNodeWithTag("immersiveModeToggle").performClick()
        compose.onNodeWithTag("stageFocusEnter").performClick()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        canvas.assertExists()
        canvas.performTouchInput {
            down(Offset(center.x * .76f, center.y * .62f))
        }
        try {
            compose.waitUntil(7_000L) {
                audio.audioVisualSnapshot.rms > .002f &&
                    audio.audioVisualSnapshot.peak > .004f
            }
        } finally {
            canvas.performTouchInput { up() }
        }
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("immersiveExit").performClick()
        compose.onNodeWithContentDescription("Tab PLAY").assertIsSelected()
        compose.waitUntil(10_000L) { synth.activeVoiceCount == 0 }
        assertTrue("Audio worker stopped after STEP/FOCUS/PLAY", audio.isRunning)
        assertClockAdvances(synth)
    }

    private fun assertPersistedV5(
        repo: UserPatchRepository,
        id: String,
        expected: ModulationStepConfig? = null,
    ): ModulationStepConfig {
        val loaded = runBlocking { repo.load(id) }
        assertTrue("Real user-bank patch missing: " + loaded, loaded is PatchOutcome.Success)
        val patch = (loaded as PatchOutcome.Success).value
        val steps = checkNotNull(patch.modulationSteps) {
            "User-bank V5 patch lost the STEP source"
        }
        assertTrue("Loaded STEP should stay enabled", steps.enabled)
        assertTrue("STEP pad 1 polarity was lost", steps.values[0] > .35f)
        assertTrue("STEP pad 2 polarity was lost", steps.values[1] < -.35f)
        if (expected != null) assertEquals(expected, steps)
        val exported = runBlocking { repo.exportPatchText(id) }
        assertTrue("Failed to export persisted V5 patch", exported is PatchOutcome.Success)
        val text = (exported as PatchOutcome.Success).value
        assertTrue("Patch downgraded from V5", text.startsWith("ORBITAL-PATCH/5\n"))
        assertEquals(steps, PatchCodec.decode(text).modulationSteps)
        return steps
    }

    @Test fun prepareStepPatchAndProveFocusPlayStateBeforeProcessDeath() {
        requirePhase("prepare")
        assertFalse("Refusing to overwrite an existing restart-smoke marker", Files.exists(marker))
        val patchName = "CI-Step-Restart-" + System.currentTimeMillis() + "-" + Process.myPid()
        val repo = repository()
        var preparedId: String? = null
        var prepared = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val synth = synthOf(scenario)
                val audio = outputOf(scenario)
                compose.waitUntil(10_000L) { audio.isRunning }
                compose.onNodeWithContentDescription("Tab FX").performClick()
                compose.onNodeWithTag("modStepRhythmPanel").assertExists()
                compose.onNodeWithContentDescription("Toggle STEP modulator")
                    .performScrollTo().performClick()
                compose.onNodeWithTag("modStepPad0").performScrollTo().performTouchInput {
                    click(Offset(center.x, center.y * .3f))
                }
                compose.onNodeWithTag("modStepPad1").performScrollTo().performTouchInput {
                    click(Offset(center.x, center.y * 1.7f))
                }
                val programmed = synth.modulationSteps
                assertTrue(programmed.enabled)
                assertTrue(programmed.values[0] > .35f)
                assertTrue(programmed.values[1] < -.35f)
                assertClockAdvances(synth)
                verifyPlayFocusReturn(scenario, synth, audio)
                assertEquals("STEP config changed in stage/PLAY", programmed, synth.modulationSteps)
    
                // Save through the real activity's patch-library callback.
                compose.onNodeWithContentDescription("Open patch library").performClick()
                compose.onNodeWithContentDescription("Save current sound as a new patch")
                    .performClick()
                compose.onNodeWithTag("patchNameInput").performTextReplacement(patchName)
                compose.onNodeWithText("SAVE AS").performClick()
                var savedId: String? = null
                compose.waitUntil(20_000L) {
                    val list = runBlocking { repo.listUser() }
                    savedId = if (list is PatchOutcome.Success) {
                        list.value.firstOrNull { it.name == patchName }?.id
                    } else null
                    savedId != null
                }
                val id = checkNotNull(savedId)
                preparedId = id
                assertPersistedV5(repo, id, programmed)
                Files.write(
                    marker,
                    (id + "\n" + Process.myPid() + "\n" + patchName + "\n")
                        .toByteArray(Charsets.UTF_8),
                )
                prepared = true
                println("ORBITAL_STEP_PROCESS_PREPARED id=" + id +
                    " original_pid=" + Process.myPid() + " v5=true clock=PASS focus=PASS")
            }
        } finally {
            if (!prepared) {
                preparedId?.let { runBlocking { repo.deleteUser(it) } }
                Files.deleteIfExists(marker)
            }
        }
    }

    @Test fun verifyAfterForcedDeathColdRestartAndDeleteOnlyOurPatch() {
        requirePhase("verify")
        assertTrue("Prepare phase never saved its marker", Files.isRegularFile(marker))
        val rows = Files.readAllLines(marker, Charsets.UTF_8)
        assertTrue("Corrupt process-smoke marker", rows.size >= 3)
        val id = rows[0]
        val oldPid = rows[1].toInt()
        val name = rows[2]
        assertTrue("Wrong isolated test patch", name.startsWith("CI-Step-Restart-"))
        assertNotEquals(
            "Verification instrumentation was not a different OS process",
            oldPid, Process.myPid(),
        )
        val repo = repository()
        try {
            val expected = assertPersistedV5(repo, id)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val synth = synthOf(scenario)
                val audio = outputOf(scenario)
                compose.waitUntil(10_000L) { audio.isRunning }
                compose.onNodeWithContentDescription("Open patch library").performClick()
                val selector = "Load " + name + ", user patch"
                compose.waitUntil(15_000L) {
                    runCatching {
                        compose.onNodeWithContentDescription(selector).assertExists()
                    }.isSuccess
                }
                compose.onNodeWithContentDescription(selector).performScrollTo().performClick()
                compose.waitUntil(15_000L) { synth.modulationSteps == expected }

                compose.onNodeWithContentDescription("Tab FX").performClick()
                compose.onNodeWithTag("modStepRhythmPanel").assertExists()
                val pad0 = compose.onNodeWithTag("modStepPad0")
                    .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
                val pad1 = compose.onNodeWithTag("modStepPad1")
                    .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
                assertTrue("UI lost positive V5 pad: " + pad0, pad0.contains(", 0."))
                assertTrue("UI lost negative V5 pad: " + pad1, pad1.contains(", -0."))
                assertClockAdvances(synth)
                verifyPlayFocusReturn(scenario, synth, audio)
                assertEquals("STEP settings dropped after cold relaunch", expected, synth.modulationSteps)
                println("ORBITAL_STEP_PROCESS_VERIFIED old_pid=" + oldPid +
                    " new_pid=" + Process.myPid() + " v5=true clock=PASS focus=PASS")
            }
        } finally {
            // Never touch any ID except the one recorded by our prepare phase.
            val removed = runBlocking { repo.deleteUser(id) }
            assertTrue("Could not clean up isolated test patch", removed is PatchOutcome.Success)
            Files.deleteIfExists(marker)
        }
    }
}
