package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** MIDI offsets for a diatonic white-key keyboard. */
internal fun whiteKeyOffset(index: Int): Int {
    require(index >= 0)
    val whites = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    return (index / 7) * 12 + whites[index % 7]
}

internal fun keyboardHitNote(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    whiteCount: Int,
    octave: Int,
): Int? {
    if (x < 0f || y < 0f || x >= width || y >= height || whiteCount < 2) return null
    val keyWidth = width / whiteCount
    val base = (octave + 1) * 12 // octave=4 is middle C (MIDI 60)
    if (y <= height * .61f) {
        val blackHalfWidth = keyWidth * .32f
        for (i in 0 until whiteCount - 1) {
            if (whiteKeyOffset(i) % 12 !in intArrayOf(0, 2, 5, 7, 9)) continue
            if (kotlin.math.abs(x - (i + 1) * keyWidth) <= blackHalfWidth) {
                return (base + whiteKeyOffset(i) + 1).takeIf { it in 0..127 }
            }
        }
    }
    val whiteIndex = (x / keyWidth).toInt().coerceIn(0, whiteCount - 1)
    return (base + whiteKeyOffset(whiteIndex)).takeIf { it in 0..127 }
}

/**
 * TalkBack actions cover the entire keyboard visible at the current width,
 * including a second octave on larger screens. Unplayable MIDI notes are omitted.
 */
internal fun keyboardAccessibleNotes(whiteCount: Int, octave: Int): List<Pair<Int, String>> {
    if (whiteCount < 2) return emptyList()
    val names = listOf(
        "C", "C sharp", "D", "D sharp", "E", "F",
        "F sharp", "G", "G sharp", "A", "A sharp", "B",
    )
    val base = (octave + 1) * 12
    return (0..whiteKeyOffset(whiteCount - 1)).mapNotNull { semitone ->
        val note = base + semitone
        if (note !in 0..127) null
        else note to "Play ${names[semitone % 12]} ${octave + semitone / 12}"
    }
}

/**
 * Tracks every pointer independently, even when moving across key boundaries.
 * Releasing a finger, cancellation, or disposal always balances note-offs.
 */
@Composable
internal fun OrbitalKeyboard(
    octave: Int,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
    preferredHeight: androidx.compose.ui.unit.Dp? = null,
) {
    val noteOn by rememberUpdatedState(onNoteOn)
    val noteOff by rememberUpdatedState(onNoteOff)
    val pressedCounts = remember { mutableStateMapOf<Int, Int>() }
    val accessibilityScope = rememberCoroutineScope()

    // Share note ownership between simultaneous fingers and TalkBack actions.
    // A key is released only after its last owner lets go.
    fun pressNote(note: Int) {
        val holders = pressedCounts[note] ?: 0
        if (holders == 0) noteOn(note)
        pressedCounts[note] = holders + 1
    }
    fun releaseNote(note: Int) {
        val holders = pressedCounts[note] ?: return
        if (holders <= 1) {
            pressedCounts.remove(note)
            noteOff(note)
        } else {
            pressedCounts[note] = holders - 1
        }
    }

    BoxWithConstraints(modifier) {
        val whiteCount = if (maxWidth >= 560.dp) 15 else 8
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(preferredHeight ?: if (whiteCount == 15) 142.dp else 158.dp)
                .semantics {
                    contentDescription = "Multitouch piano keyboard. Swipe across keys to play; use OCT buttons to change range."
                    customActions = keyboardAccessibleNotes(whiteCount, octave).map { (note, label) ->
                        CustomAccessibilityAction(label) {
                            pressNote(note)
                            accessibilityScope.launch {
                                try {
                                    delay(300)
                                } finally {
                                    releaseNote(note)
                                }
                            }
                            true
                        }
                    }
                }
                .pointerInput(octave, whiteCount) {
                    awaitPointerEventScope {
                        val pointerToNote = mutableMapOf<PointerId, Int>()
                        // All owners use the same reference-counted note lifecycle.
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                for (change in event.changes) {
                                    val previous = pointerToNote[change.id]
                                    val next = if (change.pressed) {
                                        keyboardHitNote(
                                            change.position.x,
                                            change.position.y,
                                            size.width.toFloat(),
                                            size.height.toFloat(),
                                            whiteCount,
                                            octave,
                                        )
                                    } else null
                                    if (previous != next) {
                                        if (previous != null) releaseNote(previous)
                                        if (next != null) pressNote(next)
                                        if (next == null) pointerToNote.remove(change.id)
                                        else pointerToNote[change.id] = next
                                    }
                                    if (change.pressed || previous != null) change.consume()
                                }
                            }
                        } finally {
                            pointerToNote.values.toList().forEach { releaseNote(it) }
                            pointerToNote.clear()
                        }
                    }
                },
        ) {
            val keyWidth = size.width / whiteCount
            val corner = CornerRadius(7.dp.toPx())
            val keyInset = 1.dp.toPx()
            // Illuminated floating-key tray with real-time per-note response.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(OrbitalPalette.Blue.copy(alpha = .30f), OrbitalPalette.Void.copy(alpha = .90f)),
                ),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
            for (i in 0 until whiteCount) {
                val note = (octave + 1) * 12 + whiteKeyOffset(i)
                val active = pressedCounts.containsKey(note)
                val tint = when (i % 7) {
                    0, 4 -> OrbitalPalette.Cyan
                    2, 5 -> OrbitalPalette.Blue
                    else -> OrbitalPalette.Violet
                }
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        if (active) listOf(tint.copy(alpha = .91f), tint.copy(alpha = .56f))
                        else listOf(Color(0xFF153256), Color(0xFF0A1B36), Color(0xFF090D24), tint.copy(alpha = .20f)),
                    ),
                    topLeft = Offset(i * keyWidth + 1.dp.toPx(), 0f),
                    size = Size(keyWidth - 2.dp.toPx(), size.height),
                    cornerRadius = corner,
                )
                // Glass sheen floats above the key body; the key surface remains
                // darker near the bottom so the pressed colour reads at a glance.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (active) .32f else .10f),
                            tint.copy(alpha = if (active) .18f else .045f),
                            Color.Transparent,
                        ),
                    ),
                    topLeft = Offset(i * keyWidth + keyInset * 2f, 2.dp.toPx()),
                    size = Size(keyWidth - keyInset * 4f, size.height * .47f),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
                drawRoundRect(
                    color = if (active) tint else OrbitalPalette.PanelEdge,
                    topLeft = Offset(i * keyWidth + 1.dp.toPx(), 0f),
                    size = Size(keyWidth - 2.dp.toPx(), size.height),
                    cornerRadius = corner,
                    style = Stroke(if (active) 2.dp.toPx() else .8.dp.toPx()),
                )
                drawCircle(
                    if (active) Color.White else tint.copy(alpha = .75f),
                    radius = if (active) 3.2.dp.toPx() else 1.8.dp.toPx(),
                    center = Offset((i + .5f) * keyWidth, 14.dp.toPx()),
                )
                drawLine(
                    color = tint.copy(alpha = if (active) .92f else .38f),
                    start = Offset(i * keyWidth + keyWidth * .22f, size.height - 3.dp.toPx()),
                    end = Offset((i + 1) * keyWidth - keyWidth * .22f, size.height - 3.dp.toPx()),
                    strokeWidth = if (active) 2.5.dp.toPx() else 1.dp.toPx(),
                )
            }
            // Black keys overlay the whites: hit testing uses exactly these bounds.
            for (i in 0 until whiteCount - 1) {
                if (whiteKeyOffset(i) % 12 !in intArrayOf(0, 2, 5, 7, 9)) continue
                val note = (octave + 1) * 12 + whiteKeyOffset(i) + 1
                val active = pressedCounts.containsKey(note)
                val w = keyWidth * .64f
                val left = (i + 1) * keyWidth - w / 2f
                drawRoundRect(
                    color = Color.Black.copy(alpha = .55f),
                    topLeft = Offset(left + 1.dp.toPx(), 2.dp.toPx()),
                    size = Size(w, size.height * .61f + 3.dp.toPx()),
                    cornerRadius = CornerRadius(7.dp.toPx()),
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        if (active) listOf(OrbitalPalette.Gold, OrbitalPalette.Violet)
                        else listOf(Color(0xFF040811), Color(0xFF111D39), Color(0xFF030813)),
                    ),
                    topLeft = Offset(left, 0f),
                    size = Size(w, size.height * .61f),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (active) .28f else .09f), Color.Transparent),
                    ),
                    topLeft = Offset(left + 2.dp.toPx(), 2.dp.toPx()),
                    size = Size(w - 4.dp.toPx(), size.height * .22f),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawRoundRect(
                    color = if (active) OrbitalPalette.Gold else OrbitalPalette.PanelEdge,
                    topLeft = Offset(left, 0f),
                    size = Size(w, size.height * .61f),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(if (active) 1.8.dp.toPx() else .75.dp.toPx()),
                )
            }
        }
    }
}
