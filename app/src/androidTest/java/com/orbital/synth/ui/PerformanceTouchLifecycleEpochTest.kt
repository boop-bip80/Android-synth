package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.orbital.synth.core.ParameterId
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Actual ActivityScenario ON_STOP / ON_START with an injected finger still down.
 * The audio host panics on stop, but Compose survives; stale note ownership must
 * die before a fresh finger may play the same pitch after resume.
 *
 * The small host mirrors MainActivity's monotonically increasing UI reset epoch.
 * No fake Android dispatch, direct synth calls, or sound-wave assertions are used.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceTouchLifecycleEpochTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun ResetOnRealActivityStop(
        stopCount: AtomicInteger,
        content: @Composable (Long) -> Unit,
    ) {
        var epoch by remember { mutableLongStateOf(0L) }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    stopCount.incrementAndGet()
                    epoch++
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        content(epoch)
    }

    @Test fun heldPianoFingerIsRevokedOnStopAndFreshSamePitchFingerReattacks() {
        val stops = AtomicInteger()
        val events = mutableListOf<String>()
        compose.setContent {
            ResetOnRealActivityStop(stops) { epoch ->
                Box(Modifier.width(360.dp)) {
                    OrbitalKeyboard(
                        octave = 4,
                        onNoteOn = { events.add("ON:$it") },
                        onNoteOff = { events.add("OFF:$it") },
                        performanceResetEpoch = epoch,
                    )
                }
            }
        }
        val piano = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        )
        piano.performTouchInput {
            val width = center.x * 2f
            down(0, Offset(width * .0625f, center.y * 1.7f))
        }
        compose.runOnIdle { assertEquals(listOf("ON:60"), events) }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, stops.get())
            assertEquals(
                "Stop must release the held original pitch exactly once",
                listOf("ON:60", "OFF:60"), events,
            )
        }

        // Pointer 0 was down before backgrounding. Pointer 1 is a *new* down
        // at the same pitch; its attack must not be muted by owner 0.
        piano.performTouchInput {
            val width = center.x * 2f
            down(1, Offset(width * .0625f, center.y * 1.7f))
            // The old finger's late UP is in the *new* host worker epoch.
            // It must not release the newly attacked identical pitch.
            up(0)
        }
        compose.runOnIdle {
            assertEquals(
                "Stale UP must not silence a newer same-pitch finger",
                listOf("ON:60", "OFF:60", "ON:60"), events,
            )
        }
        piano.performTouchInput { up(1) }
        compose.runOnIdle {
            assertEquals(
                "New touch must produce one matching final note-off",
                listOf("ON:60", "OFF:60", "ON:60", "OFF:60"), events,
            )
        }
    }

    @Test fun heldFocusCanvasTouchIsRevokedAcrossStopAndBackDoesNotLeakNotes() {
        val stops = AtomicInteger()
        val events = mutableListOf<String>()
        compose.setContent {
            ResetOnRealActivityStop(stops) { epoch ->
                ImmersiveStage(
                    state = OrbitalUiState(),
                    onNoteOn = { events.add("ON:$it") },
                    onNoteOff = { events.add("OFF:$it") },
                    onParameterChange = { _: ParameterId, _: Float -> },
                    onExit = {},
                    performanceResetEpoch = epoch,
                )
            }
        }
        compose.onNodeWithTag("stageFocusEnter").performClick()
        val canvas = compose.onNodeWithTag("immersiveCanvas").assertExists()
        canvas.performTouchInput { down(0, center) }
        compose.waitForIdle()
        val original = events.filter { it.startsWith("ON:") }.map { it.removePrefix("ON:") }
        assertTrue("A genuine FOCUS down must attack at least one mapped note", original.isNotEmpty())
        assertEquals(
            "Touch sculpting",
            canvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(
            "Touch idle",
            compose.onNodeWithTag("immersiveCanvas").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription],
        )
        compose.onNodeWithTag("stageFocusEnter").assertDoesNotExist() // FOCUS still active
        compose.runOnIdle {
            assertEquals(1, stops.get())
            val releases = events.filter { it.startsWith("OFF:") }.map { it.removePrefix("OFF:") }
            assertEquals(
                "Every original FOCUS contact must be released exactly once after stop",
                original.sorted(), releases.sorted(),
            )
        }

        // A first new pointer must start a new session. The old pointer 0
        // may still be present in motion events, but was NOT freshly pressed.
        canvas.performTouchInput {
            down(1, center)
            up(0) // Delayed old-session UP must NOT silence pointer 1.
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val attacks = events.filter { it.startsWith("ON:") }.map { it.removePrefix("ON:") }
            val releases = events.filter { it.startsWith("OFF:") }.map { it.removePrefix("OFF:") }
            assertEquals(
                "A stale held FOCUS pointer must not reattack after fresh contact",
                original.size * 2, attacks.size,
            )
            assertEquals(
                "Late old-session UP must not release the new active FOCUS note",
                original.sorted(), releases.sorted(),
            )
        }
        assertEquals(
            "Touch sculpting",
            compose.onNodeWithTag("immersiveCanvas").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription],
        )
        canvas.performTouchInput { up(1) }
        compose.waitForIdle()
        compose.runOnIdle {
            val attacks = events.filter { it.startsWith("ON:") }.map { it.removePrefix("ON:") }
            val releases = events.filter { it.startsWith("OFF:") }.map { it.removePrefix("OFF:") }
            assertEquals(
                "New FOCUS touch must release only when its own finger goes up",
                attacks.sorted(), releases.sorted(),
            )
        }

        // Back is pressed while TWO distinct live fingers are held in FOCUS.
        // It must free every mapper lease and reveal the stage controls without
        // trapping the performer in a hidden-chrome view.
        canvas.performTouchInput {
            down(2, Offset(center.x * .75f, center.y * .65f))
            down(3, Offset(center.x * 1.28f, center.y * 1.32f))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(
                "Two fresh held FOCUS contacts should attack a live note",
                events.count { it.startsWith("ON:") } > original.size * 2,
            )
        }
        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("immersiveExit").assertIsDisplayed()
        var afterBack = emptyList<String>()
        compose.runOnIdle {
            val attacks = events.filter { it.startsWith("ON:") }.map { it.removePrefix("ON:") }
            val releases = events.filter { it.startsWith("OFF:") }.map { it.removePrefix("OFF:") }
            assertEquals(
                "Back must release every held FOCUS voice exactly once",
                attacks.sorted(), releases.sorted(),
            )
            afterBack = events.toList()
        }

        // The OS may deliver physical UP after Back's explicit cancel; those
        // obsolete finger IDs must not produce duplicate OFF in the new view.
        canvas.performTouchInput {
            up(2)
            up(3)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                "Late UP events after Back must not release a note twice",
                afterBack, events,
            )
        }
    }
}
