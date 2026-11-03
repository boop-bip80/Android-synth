package com.orbital.synth.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Compose pointer dispatch, lifecycle and accessibility regressions:
 * no MIDI note may survive its final physical/TalkBack owner, nor can a stale
 * timer release an unrelated later finger after the audio host resets.
 */
@RunWith(AndroidJUnit4::class)
class OrbitalKeyboardOwnershipTest {
    @get:Rule val compose = createComposeRule()

    private fun piano() = compose.onNodeWithContentDescription(
        "Multitouch piano keyboard", substring = true,
    )

    @Test fun samePitchFingersAndIndependentGlissReleaseInOwnershipOrder() {
        val events = mutableListOf<String>()
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                OrbitalKeyboard(
                    octave = 4,
                    onNoteOn = { events.add("ON:$it") },
                    onNoteOff = { events.add("OFF:$it") },
                )
            }
        }
        piano().performTouchInput {
            val w = center.x * 2f
            val y = center.y * 1.68f // below black keys, on visible whites
            down(0, Offset(w * .0625f, y)) // C4 = 60
            down(1, Offset(w * .067f, y))  // same C4, no second ON
            moveTo(0, Offset(w * .1875f, y)) // D4 = 62
            up(0)
            up(1)
        }
        compose.runOnIdle {
            assertEquals(
                listOf("ON:60", "ON:62", "OFF:62", "OFF:60"),
                events,
            )
        }
    }

    @Test fun canceledChordAndCompositionDisposalReleaseEachOwnedNoteOnce() {
        val events = mutableListOf<String>()
        var visible by mutableStateOf(true)
        compose.setContent {
            if (visible) Box(Modifier.width(360.dp)) {
                OrbitalKeyboard(4, { events.add("ON:$it") }, { events.add("OFF:$it") })
            }
        }
        piano().performTouchInput {
            val w = center.x * 2f
            val y = center.y * 1.7f
            down(0, Offset(w * .0625f, y))
            down(1, Offset(w * .1875f, y))
            cancel()
        }
        compose.runOnIdle {
            assertEquals(listOf("ON:60", "ON:62", "OFF:60", "OFF:62"), events)
        }
        piano().performTouchInput {
            val w = center.x * 2f
            down(0, Offset(w * .0625f, center.y * 1.7f))
        }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(
                "ON:60", "ON:62", "OFF:60", "OFF:62", "ON:60", "OFF:60",
            ), events)
        }
    }

    @Test fun hostResetIgnoresOldHeldFingerMoveButAllowsFreshFingerSamePitch() {
        val events = mutableListOf<String>()
        var epoch by mutableStateOf(0L)
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                OrbitalKeyboard(
                    octave = 4,
                    onNoteOn = { events.add("ON:$it") },
                    onNoteOff = { events.add("OFF:$it") },
                    performanceResetEpoch = epoch,
                )
            }
        }
        piano().performTouchInput {
            val w = center.x * 2f
            down(0, Offset(w * .0625f, center.y * 1.7f))
        }
        compose.runOnIdle { epoch++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("ON:60", "OFF:60"), events)
        }
        // Pointer 0 was held before the epoch. A move is not a fresh down.
        piano().performTouchInput {
            val w = center.x * 2f
            moveTo(0, Offset(w * .1875f, center.y * 1.7f))
        }
        compose.runOnIdle { assertEquals(listOf("ON:60", "OFF:60"), events) }
        piano().performTouchInput {
            val w = center.x * 2f
            down(1, Offset(w * .0625f, center.y * 1.7f))
            up(1)
            up(0)
        }
        compose.runOnIdle {
            assertEquals(listOf("ON:60", "OFF:60", "ON:60", "OFF:60"), events)
        }
    }

    @Test fun octaveChangeReleasesOldVoiceAndOldFingerCannotAttackNewOctave() {
        val events = mutableListOf<String>()
        var octave by mutableIntStateOf(4)
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                OrbitalKeyboard(
                    octave = octave,
                    onNoteOn = { events.add("ON:$it") },
                    onNoteOff = { events.add("OFF:$it") },
                )
            }
        }
        piano().performTouchInput {
            val w = center.x * 2f
            down(0, Offset(w * .0625f, center.y * 1.7f))
        }
        compose.runOnIdle { octave = 5 }
        compose.waitForIdle()
        piano().performTouchInput {
            val w = center.x * 2f
            moveTo(0, Offset(w * .1875f, center.y * 1.7f))
            down(1, Offset(w * .0625f, center.y * 1.7f))
            up(1)
        }
        compose.runOnIdle {
            assertEquals(listOf("ON:60", "OFF:60", "ON:72", "OFF:72"), events)
        }
        compose.runOnIdle { octave = 4 }
        compose.waitForIdle()
        piano().performTouchInput { up(0) }
        compose.runOnIdle {
            assertEquals(listOf("ON:60", "OFF:60", "ON:72", "OFF:72"), events)
        }
    }

    @Test fun hostCallbackReplacementReleasesNoteToOriginalAudioHost() {
        val events = mutableListOf<String>()
        var host by mutableStateOf("A")
        compose.setContent {
            val thisHost = host
            Box(Modifier.width(360.dp)) {
                OrbitalKeyboard(
                    octave = 4,
                    onNoteOn = { note -> events.add("$thisHost:ON:$note") },
                    onNoteOff = { note -> events.add("$thisHost:OFF:$note") },
                )
            }
        }
        piano().performTouchInput {
            val w = center.x * 2f
            down(0, Offset(w * .0625f, center.y * 1.7f))
        }
        compose.runOnIdle { host = "B" }
        compose.waitForIdle()
        piano().performTouchInput { up(0) }
        compose.runOnIdle {
            assertEquals(listOf("A:ON:60", "A:OFF:60"), events)
        }
    }

    @Test fun talkBackStaleDelayedReleaseCannotStopNewFingerAfterHostReset() {
        val events = mutableListOf<String>()
        var epoch by mutableStateOf(0L)
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                OrbitalKeyboard(
                    octave = 4,
                    onNoteOn = { events.add("ON:$it") },
                    onNoteOff = { events.add("OFF:$it") },
                    performanceResetEpoch = epoch,
                )
            }
        }
        val actions = piano().fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(actions.first { it.label == "Play C 4" }.action())
            epoch++
        }
        compose.waitForIdle()
        piano().performTouchInput {
            val w = center.x * 2f
            down(0, Offset(w * .0625f, center.y * 1.7f))
        }
        // A bounded, deliberate >300ms wait allows the old semantic action's
        // finally to fire. Its revoked owner token must NOT send OFF.
        SystemClock.sleep(450L)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("ON:60", "OFF:60", "ON:60"), events)
        }
        piano().performTouchInput { up(0) }
        compose.runOnIdle {
            assertEquals(listOf("ON:60", "OFF:60", "ON:60", "OFF:60"), events)
        }
    }
}
