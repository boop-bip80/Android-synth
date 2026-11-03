package com.orbital.synth.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferencePerformanceKeybedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun twoFingersOwnDifferentChromaticNotesAndReleaseIndependently() {
        val events = mutableListOf<String>()
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                ReferencePerformanceKeybed(
                    octave = 4,
                    onNoteOn = { events.add("ON:$it") },
                    onNoteOff = { events.add("OFF:$it") },
                )
            }
        }
        compose.onNodeWithTag("referencePerformanceKeybed").performTouchInput {
            val width = center.x * 2f
            down(0, Offset(width * .05f, center.y))
            down(1, Offset(width * .59f, center.y))
            up(0)
            up(1)
        }
        compose.runOnIdle {
            // C4 and G4 are two different live voices, not one duplicated note.
            assertEquals(listOf("ON:60", "ON:67", "OFF:60", "OFF:67"), events)
        }
    }

    @Test fun twoFingersOnSameKeyReferenceCountUntilLastRelease() {
        val events = mutableListOf<String>()
        compose.setContent {
            ReferencePerformanceKeybed(
                octave = 4,
                onNoteOn = { events.add("ON:$it") },
                onNoteOff = { events.add("OFF:$it") },
            )
        }
        compose.onNodeWithTag("referencePerformanceKeybed").performTouchInput {
            val width = center.x * 2f
            down(0, Offset(width * .21f, center.y))
            down(1, Offset(width * .22f, center.y * 1.4f))
            up(0)
            up(1)
        }
        // Both x values fall on key index 2 = D4 (MIDI 62).
        compose.runOnIdle {
            assertEquals(listOf("ON:62", "OFF:62"), events)
        }
    }

    @Test fun realFingerGlissandoReleasesOldPadBeforeAttackingNewPad() {
        val events = mutableListOf<String>()
        compose.setContent {
            ReferencePerformanceKeybed(
                octave = 4,
                onNoteOn = { events.add("ON:$it") },
                onNoteOff = { events.add("OFF:$it") },
            )
        }
        compose.onNodeWithTag("referencePerformanceKeybed").performTouchInput {
            val width = center.x * 2f
            down(Offset(width * .12f, center.y))
            moveTo(Offset(width * .29f, center.y))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf("ON:61", "OFF:61", "ON:63", "OFF:63"), events)
        }
    }

    @Test fun osPointerCancellationBalancesEveryOwnedNoteWithNoStuckVoices() {
        val opened = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        compose.setContent {
            ReferencePerformanceKeybed(
                octave = 4,
                onNoteOn = { opened.add(it) },
                onNoteOff = { closed.add(it) },
            )
        }
        compose.onNodeWithTag("referencePerformanceKeybed").performTouchInput {
            val width = center.x * 2f
            down(0, Offset(width * .07f, center.y))
            down(1, Offset(width * .93f, center.y))
            cancel()
        }
        compose.runOnIdle {
            assertEquals(listOf(60, 71), opened)
            assertEquals(opened.sorted(), closed.sorted())
        }
    }

    @Test fun octaveRecallAndHidingKeyboardFlushHeldOwnedNotes() {
        val opened = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        var octave by mutableStateOf(4)
        var show by mutableStateOf(true)
        compose.setContent {
            if (show) ReferencePerformanceKeybed(
                octave = octave,
                onNoteOn = { opened.add(it) },
                onNoteOff = { closed.add(it) },
            )
        }
        compose.onNodeWithTag("referencePerformanceKeybed").performTouchInput {
            down(Offset(center.x * .12f, center.y))
        }
        compose.runOnIdle { octave = 5 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(opened.isNotEmpty())
            assertEquals("An old octave must not keep sounding", opened.sorted(), closed.sorted())
        }
        compose.runOnIdle { show = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Disposal must never double-release", opened.sorted(), closed.sorted())
        }
    }

    @Test fun expressiveNoteOnReceivesActualConfiguredVerticalStrikeVelocity() {
        val expressive = mutableListOf<Pair<Int, Float>>()
        val fallback = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        compose.setContent {
            ReferencePerformanceKeybed(
                octave = 4,
                onNoteOn = { fallback.add(it) },
                onNoteOff = { released.add(it) },
                onExpressiveNoteOn = { note, velocity -> expressive.add(note to velocity) },
            )
        }
        compose.onNodeWithTag("referencePerformanceKeybed").performTouchInput {
            val width = center.x * 2f
            click(Offset(width * .05f, center.y * .2f))
        }
        compose.runOnIdle {
            assertEquals(0, fallback.size)
            assertEquals(1, expressive.size)
            assertEquals(60, expressive.single().first)
            assertTrue("top of keybed maps to bright strike",
                expressive.single().second > .90f)
            assertEquals(listOf(60), released)
        }
    }

    @Test fun talkBackOffersEveryNoteOctaveNavigationAndExplicitPanic() {
        var chosenOctave = 4
        var panicCalls = 0
        compose.setContent {
            ReferencePerformanceKeybed(
                octave = 4,
                onNoteOn = {},
                onNoteOff = {},
                onOctaveChange = { chosenOctave = it },
                onAllNotesOff = { panicCalls++ },
            )
        }
        val actions = compose.onNodeWithTag("referencePerformanceKeybed")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(15, actions.size)
        assertTrue(actions.any { it.label == "Play C4" })
        assertTrue(actions.any { it.label == "Play B4" })
        compose.runOnIdle {
            assertTrue(actions.first { it.label == "Octave up" }.action())
            assertTrue(actions.first { it.label == "Panic: all notes off" }.action())
        }
        assertEquals(5, chosenOctave)
        assertEquals(1, panicCalls)
    }

    @Test fun actual360DpCanvasChangesPixelsForRealHeldFinger() {
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                ReferencePerformanceKeybed(
                    octave = 4, onNoteOn = {}, onNoteOff = {},
                    morph = .54f, movement = .34f,
                )
            }
        }
        val node = compose.onNodeWithTag("referencePerformanceKeybed")
        val before = node.captureToImage()
        val start = before.toPixelMap()
        val px = (before.width * .04f).toInt()
        val py = (before.height * .23f).toInt()
        val idleColor = start[px, py]
        node.performTouchInput {
            down(Offset(center.x * .08f, center.y * .46f))
        }
        compose.waitForIdle()
        val pressedColor = node.captureToImage().toPixelMap()[px, py]
        val rgbDelta =
            kotlin.math.abs(pressedColor.red - idleColor.red) +
                kotlin.math.abs(pressedColor.green - idleColor.green) +
                kotlin.math.abs(pressedColor.blue - idleColor.blue)
        assertTrue("Real first-strip touch should visibly brighten glass: $rgbDelta",
            rgbDelta > .07f)
        node.performTouchInput { cancel() }
    }
}
