package com.orbital.synth.ui

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.MainActivity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real Android host + real Compose editor, not a fake screen state.
 * This file is the only app preference file touched by the test. Its original
 * bytes are restored afterward, including unsupported future versions.
 */
@RunWith(AndroidJUnit4::class)
class StageTouchActivityRestartTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var preferenceFile: Path
    private lateinit var store: StageTouchSettingsHostStore
    private var originalBytes: ByteArray? = null

    @Before fun protectExistingPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferenceFile = context.filesDir.toPath().resolve("orbital-stage-touch.settings")
        store = StageTouchSettingsHostStore.forApplication(context)
        runBlocking { withTimeout(15_000L) { store.awaitPendingWrites() } }
        originalBytes = if (Files.exists(preferenceFile)) {
            Files.readAllBytes(preferenceFile)
        } else null
    }

    @After fun restoreExistingPreferences() {
        runBlocking { withTimeout(15_000L) { store.awaitPendingWrites() } }
        val previous = originalBytes
        if (previous == null) {
            Files.deleteIfExists(preferenceFile)
        } else {
            Files.write(preferenceFile, previous)
        }
    }

    @Test fun allEightSettingsRestoreAndUserEditSurvivesRealActivityRecreation() {
        val saved = StageTouchSettings(
            tonic = 9, baseOctave = 5, octaveRange = 3,
            scale = TouchScale.BLUES, pressureSensitivity = .72f,
            pitchAxis = TouchPitchAxis.VERTICAL, glissando = false,
        )
        Files.write(preferenceFile, StageTouchSettingsCodec.encode(saved).toByteArray(Charsets.UTF_8))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            enterConfiguration()
            // The actual activity may still be restoring on Dispatchers.IO.
            awaitSelected("stageScale-BLUES")
            compose.onNodeWithTag("stageTonic-9").assertIsSelected()
            compose.onNodeWithTag("stageBaseOctave-5").assertIsSelected()
            compose.onNodeWithTag("stageOctaveRange-3").assertIsSelected()
            compose.onNodeWithTag("stageAxisVertical").assertIsSelected()
            compose.onNodeWithTag("stageMotionHold").assertIsSelected()
            compose.onNodeWithTag("stageHapticsOff").assertIsSelected()

            val prior = store.requestedRevision()
            compose.onNodeWithTag("stageScale-MAJOR").performScrollTo().performClick()
            compose.onNodeWithTag("stageHapticsOn").performClick()
            compose.onNodeWithTag("stageHapticsOn").assertIsSelected()
            waitForHostSave(prior, expectedEdits = 2)
            val expected = saved.copy(scale = TouchScale.MAJOR, hapticsEnabled = true)
            val repo = StageTouchSettingsRepository(
                ApplicationProvider.getApplicationContext<Context>()
            )
            assertEquals(StageTouchSettingsLoad(
                expected, StageTouchLoadSource.SAVED,
            ), runBlocking { repo.load() })

            compose.onNodeWithTag("stageConfigClose").performScrollTo().performClick()
            scenario.recreate()
            enterConfiguration()
            awaitSelected("stageScale-MAJOR")
            compose.onNodeWithTag("stageTonic-9").assertIsSelected()
            compose.onNodeWithTag("stageBaseOctave-5").assertIsSelected()
            compose.onNodeWithTag("stageOctaveRange-3").assertIsSelected()
            compose.onNodeWithTag("stageAxisVertical").assertIsSelected()
            compose.onNodeWithTag("stageMotionHold").assertIsSelected()
            compose.onNodeWithTag("stageHapticsOn").assertIsSelected()
            assertEquals(expected, runBlocking { repo.load().settings })
        }
    }

    @Test fun unknownNewerSettingsAreNotSilentlyDowngradedByRealStageEdits() {
        val future = "ORBITAL-STAGE-TOUCH-V873\nfutureControl=1\n"
        Files.write(preferenceFile, future.toByteArray(Charsets.UTF_8))
        ActivityScenario.launch(MainActivity::class.java).use {
            enterConfiguration()
            awaitSelected("stageScale-CHROMATIC")
            val prior = store.requestedRevision()
            compose.onNodeWithTag("stageScale-MAJOR").performScrollTo().performClick()
            waitForHostSave(prior)
            assertEquals(
                StageTouchSettingsSave.UnreadableExisting(
                    StageTouchLoadSource.DEFAULT_UNSUPPORTED,
                ),
                store.notices.value?.result,
            )
            assertEquals(future, String(Files.readAllBytes(preferenceFile), Charsets.UTF_8))
            assertTrue(
                "No implicit version downgrade is allowed",
                String(Files.readAllBytes(preferenceFile), Charsets.UTF_8).startsWith("ORBITAL-STAGE-TOUCH-V873"),
            )
        }
    }

    private fun enterConfiguration() {
        compose.onNodeWithTag("immersiveModeToggle").performClick()
        compose.onNodeWithTag("stageConfigOpen").performClick()
    }

    private fun awaitSelected(tag: String) {
        compose.waitUntil(15_000L) {
            runCatching { compose.onNodeWithTag(tag).assertIsSelected() }.isSuccess
        }
    }

    private fun waitForHostSave(previousRevision: Long, expectedEdits: Int = 1) {
        compose.waitUntil(15_000L) {
            store.requestedRevision() >= previousRevision + expectedEdits
        }
        runBlocking { withTimeout(15_000L) { store.awaitPendingWrites() } }
    }
}