package com.orbital.synth.audio

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Activity poll and accessible recovery UI deterministically.
 * Inject only the existing terminal-notice boundary under its owner lock; do not
 * sabotage AudioTrack, require a physical audio route, or pretend to test a
 * hardware failure. The bounded retry policy has separate JVM coverage.
 */
@RunWith(AndroidJUnit4::class)
class AudioFailureNoticeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun terminalNoticeIsAccessibleConsumedOnceAndRetryIsExplicit() {
        val output = liveOutput()
        val message = "Audio output stopped unexpectedly. Test recovery notice."
        publishNotice(output, message)
        awaitNotice(message)
        compose.onNodeWithText("Audio output stopped").assertIsDisplayed()
        compose.onNodeWithText("Retry audio").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsDisplayed()
        assertNull("The Activity must consume the terminal notice exactly once",
            output.fatalErrorMessage)

        compose.onNodeWithText("Later").performClick()
        SystemClock.sleep(160L) // Several actual 32ms Activity polls.
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.onNodeWithText("Audio output stopped").assertDoesNotExist()

        // A distinct failure is reportable even if Android supplies the same text.
        publishNotice(output, message)
        awaitNotice(message)
        val previousEpoch = output.performanceSessionEpoch
        compose.onNodeWithText("Retry audio").performClick()
        compose.runOnIdle {
            assertTrue(
                "User retry must explicitly stop/invalidate the old audio session",
                output.performanceSessionEpoch > previousEpoch,
            )
        }
        // Sink/focus availability is device-dependent: success dismisses the
        // dialog, while synchronous failure intentionally keeps a useful error.
    }

    @Test fun stopClearsVisibleNoticeAndBackgroundPollCannotConsumeAnother() {
        val output = liveOutput()
        val oldMessage = "Previous foreground audio failure."
        publishNotice(output, oldMessage)
        awaitNotice(oldMessage)

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertNull("onStop must discard any old pending worker notice", output.fatalErrorMessage)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        SystemClock.sleep(160L)
        compose.onNodeWithText(oldMessage).assertDoesNotExist()
        compose.onNodeWithText("Audio output stopped").assertDoesNotExist()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // A test-only late notification probes the poll's foreground gate.
        // Real stale-worker publication is independently generation-guarded.
        val backgroundMessage = "Background notification probe."
        publishNotice(output, backgroundMessage)
        SystemClock.sleep(160L)
        assertEquals(
            "A stopped Activity must not consume a notice or present background UI",
            backgroundMessage,
            output.fatalErrorMessage,
        )
        // ActivityScenario closes the stopped Activity; onDestroy/close clears it.
    }

    private fun awaitNotice(message: String) {
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    private fun liveOutput(): AndroidAudioOutput {
        val field = MainActivity::class.java.getDeclaredField("audioOutput")
        field.isAccessible = true
        return field.get(compose.activity) as AndroidAudioOutput
    }

    private fun publishNotice(output: AndroidAudioOutput, message: String) {
        val lockField = AndroidAudioOutput::class.java.getDeclaredField("lifecycleLock")
        val policyField = AndroidAudioOutput::class.java.getDeclaredField("failureRecovery")
        lockField.isAccessible = true
        policyField.isAccessible = true
        synchronized(lockField.get(output)) {
            (policyField.get(output) as AudioFailureRecoveryPolicy).publishFatalNotice(message)
        }
    }
}
