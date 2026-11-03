package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val KeybedObsidian = Color(0xFF01050E)
private val KeybedGlass = Color(0xFF081326)

/**
 * Twelve chromatic, equal-width performance strips in the supplied original
 * generated reference image. They are *actual playable MIDI notes*; each
 * pointer is a separate owner. All note starts/stops go to the ONE host synth.
 *
 * Vertical touch position is mapped to initial MIDI strike velocity. That is
 * an intentional control surface, not a claim that the device measures force.
 * The decorative liquid-light rivers below come from configured morph /
 * movement parameters, never fabricated FFT or sampled output.
 *
 * On teardown, pointer cancellation, octaves changing, and gesture exit, every
 * locally owned note receives its corresponding note-off. Multiple fingers
 * holding a single note reference-count ownership, avoiding early release.
 */
@Composable
fun ReferencePerformanceKeybed(
    octave: Int,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onExpressiveNoteOn: ((Int, Float) -> Unit)? = null,
    onAllNotesOff: (() -> Unit)? = null,
    onOctaveChange: ((Int) -> Unit)? = null,
    morph: Float = .5f,
    movement: Float = .5f,
    audioRms: Float = 0f,
    preferredHeight: Dp = 108.dp,
) {
    val noteOnNow by rememberUpdatedState(onNoteOn)
    val noteOffNow by rememberUpdatedState(onNoteOff)
    val expressiveNow by rememberUpdatedState(onExpressiveNoteOn)
    val panicNow by rememberUpdatedState(onAllNotesOff)
    val octaveNow by rememberUpdatedState(onOctaveChange)
    val noteOwners = remember { ReferenceKeybedNoteOwnership() }
    // The pure ownership model is not a SnapshotStateMap; an explicit small
    // revision publishes visual on/off feedback only when a token changes.
    var ownerRevision by remember { mutableIntStateOf(0) }
    val activeNotes = remember(ownerRevision) { noteOwners.activeNotes }
    val touchPoints = remember { mutableStateMapOf<PointerId, Offset>() }
    val accessibilityScope = rememberCoroutineScope()
    val chromaticOctave = octave.coerceIn(0, 8)

    fun press(note: Int, velocity: Float): Long {
        // Pair note-off with the host callback present when the very first
        // owner attacked this pitch. Swapping host closures must not strand
        // a voice in an old synth instance.
        val offForThisHost = noteOffNow
        val expressiveForThisHost = expressiveNow
        val onForThisHost = noteOnNow
        val owner = noteOwners.acquire(
            note = note,
            onAttack = {
                if (expressiveForThisHost != null)
                    expressiveForThisHost(note, velocity)
                else onForThisHost(note)
            },
            onRelease = offForThisHost,
        )
        ownerRevision++
        return owner
    }

    fun release(owner: Long) {
        if (noteOwners.release(owner)) ownerRevision++
    }

    fun releaseAllOwned() {
        if (noteOwners.releaseAll() > 0) ownerRevision++
        touchPoints.clear()
    }

    // Both pointerInput cancellation and full composable disappearance must
    // balance notes; the map guards against duplicate sends when both fire.
    DisposableEffect(chromaticOctave) {
        onDispose { releaseAllOwned() }
    }

    val label = "Twelve live chromatic liquid-glass keys, octave " +
        chromaticOctave + ", " + activeNotes.size +
        " currently held. Slide fingers between strips; higher touch position " +
        "gives a brighter initial strike velocity. Light rivers preview synth settings, not PCM"

    val corner = RoundedCornerShape(12.dp)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(preferredHeight)
            .background(KeybedObsidian, corner)
            .border(1.dp, OrbitalPalette.Cyan.copy(alpha = .46f), corner)
            .clip(corner)
            .testTag("referencePerformanceKeybed")
            .semantics {
                contentDescription = label
                customActions = buildList {
                    for (index in 0 until ReferenceKeybedUiModel.KEY_COUNT) {
                        val note = ReferenceKeybedUiModel.noteAt(chromaticOctave, index)
                            ?: continue
                        add(CustomAccessibilityAction(
                            "Play " + ReferenceKeybedUiModel.noteLabel(note),
                        ) {
                            val owner = press(note, .78f)
                            accessibilityScope.launch {
                                try { delay(260L) }
                                finally { release(owner) }
                            }
                            true
                        })
                    }
                    if (onOctaveChange != null) {
                        add(CustomAccessibilityAction("Octave down") {
                            octaveNow?.invoke((chromaticOctave - 1).coerceAtLeast(0))
                            true
                        })
                        add(CustomAccessibilityAction("Octave up") {
                            octaveNow?.invoke((chromaticOctave + 1).coerceAtMost(8))
                            true
                        })
                    }
                    if (onAllNotesOff != null) {
                        add(CustomAccessibilityAction("Panic: all notes off") {
                            releaseAllOwned()
                            panicNow?.invoke()
                            true
                        })
                    }
                }
            }
            .pointerInput(chromaticOctave) {
                awaitPointerEventScope {
                    // Pointer ID owns one unique token, not merely a MIDI
                    // note integer: cancellation never releases another owner.
                    val pointerToNote = mutableMapOf<PointerId, Pair<Int, Long>>()
                    try {
                        while (true) {
                            // Initial pass prevents a scrolling ancestor from
                            // stealing an active musical finger gesture.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            for (change in event.changes) {
                                val previous = pointerToNote[change.id]
                                val keyIndex = if (change.pressed) {
                                    ReferenceKeybedUiModel.keyAt(
                                        change.position.x, change.position.y,
                                        size.width.toFloat(), size.height.toFloat(),
                                    )
                                } else null
                                val next = keyIndex?.let {
                                    ReferenceKeybedUiModel.noteAt(chromaticOctave, it)
                                }
                                if (previous?.first != next) {
                                    if (previous != null) release(previous.second)
                                    if (next == null) pointerToNote.remove(change.id)
                                    else {
                                        val velocity = ReferenceKeybedUiModel.verticalStrikeVelocity(
                                            change.position.y, size.height.toFloat(),
                                        )
                                        pointerToNote[change.id] = next to press(next, velocity)
                                    }
                                }
                                if (next == null) touchPoints.remove(change.id)
                                else touchPoints[change.id] = change.position
                                if (change.pressed || previous != null) change.consume()
                            }
                        }
                    } finally {
                        pointerToNote.values.toList().forEach { release(it.second) }
                        pointerToNote.clear()
                        touchPoints.clear()
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val energy = ReferenceKeybedUiModel.normalized(audioRms * 5f)
        val colors = listOf(
            OrbitalPalette.Blue, OrbitalPalette.Cyan, OrbitalPalette.Blue,
            OrbitalPalette.Violet, OrbitalPalette.Gold, OrbitalPalette.Violet,
            OrbitalPalette.Blue, OrbitalPalette.Cyan, OrbitalPalette.Gold,
            OrbitalPalette.Violet, OrbitalPalette.Cyan, OrbitalPalette.Blue,
        )
        val count = ReferenceKeybedUiModel.KEY_COUNT
        val padWidth = w / count
        val gap = 1.dp.toPx()
        val radius = CornerRadius(5.dp.toPx())

        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF0E2342), KeybedObsidian),
            ),
            size = size,
            cornerRadius = CornerRadius(12.dp.toPx()),
        )
        for (index in 0 until count) {
            val note = ReferenceKeybedUiModel.noteAt(chromaticOctave, index) ?: continue
            val active = note in activeNotes
            val tint = colors[index]
            val left = padWidth * index + gap
            val keySize = Size((padWidth - 2f * gap).coerceAtLeast(0f), h - 2f * gap)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    if (active) listOf(
                        tint.copy(alpha = .95f), tint.copy(alpha = .52f),
                        OrbitalPalette.Violet.copy(alpha = .74f),
                    ) else listOf(
                        Color(0xFF0A1426), KeybedGlass,
                        tint.copy(alpha = .22f + energy * .13f),
                    ),
                ),
                topLeft = Offset(left, gap),
                size = keySize,
                cornerRadius = radius,
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (active) .32f else .12f),
                        tint.copy(alpha = if (active) .26f else .07f),
                        Color.Transparent),
                ),
                topLeft = Offset(left + gap, 2f * gap),
                size = Size((padWidth - 4f * gap).coerceAtLeast(0f), h * .53f),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            drawRoundRect(
                color = if (active) tint.copy(alpha = .9f)
                    else tint.copy(alpha = .36f),
                topLeft = Offset(left, gap),
                size = keySize,
                cornerRadius = radius,
                style = Stroke(if (active) 1.8.dp.toPx() else .7.dp.toPx()),
            )
            drawCircle(
                if (active) Color.White else tint.copy(alpha = .76f),
                radius = if (active) 2.7.dp.toPx() else 1.4.dp.toPx(),
                center = Offset((index + .5f) * padWidth, h * .13f),
            )
            drawLine(
                tint.copy(alpha = if (active) .9f else .43f),
                Offset(left + 3f * gap, h - 3f * gap),
                Offset(left + padWidth - 5f * gap, h - 3f * gap),
                strokeWidth = if (active) 2.dp.toPx() else .8.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // A liquid ribbon connects all twelve equal-width pads. Every path is
        // deterministic from stored MORPH/MOVEMENT parameters, never fake FFT.
        val lightBrush = Brush.horizontalGradient(
            listOf(
                OrbitalPalette.Cyan.copy(alpha = .40f),
                OrbitalPalette.Violet.copy(alpha = .78f),
                OrbitalPalette.Gold.copy(alpha = .75f),
                OrbitalPalette.Cyan.copy(alpha = .79f),
                OrbitalPalette.Violet.copy(alpha = .5f),
            ),
        )
        for (layer in 0..2) {
            val path = Path()
            for (sample in 0..120) {
                val u = sample / 120f
                val y = ReferenceKeybedUiModel.lightRiverAt(
                    u, layer, morph, movement,
                ) * h
                if (sample == 0) path.moveTo(0f, y)
                else path.lineTo(u * w, y)
            }
            drawPath(
                path, lightBrush,
                alpha = .12f + .14f * energy,
                style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                path, lightBrush,
                alpha = .48f + .25f * energy,
                style = Stroke(width = (if (layer == 1) 1.8f else 1.1f).dp.toPx(),
                    cap = StrokeCap.Round),
            )
        }

        // Each real finger gets a bounded local bloom; no synthetic pressure
        // readings, per-note DSP duplicates, or hidden audio engine.
        touchPoints.values.forEach { point ->
            val safePoint = Offset(point.x.coerceIn(0f, w), point.y.coerceIn(0f, h))
            val radiusPx = 36.dp.toPx()
            drawCircle(
                Brush.radialGradient(
                    listOf(OrbitalPalette.Cyan.copy(alpha = .55f),
                        OrbitalPalette.Violet.copy(alpha = .26f),
                        Color.Transparent),
                    center = safePoint,
                    radius = radiusPx,
                ),
                radius = radiusPx,
                center = safePoint,
            )
            drawCircle(
                Color.White.copy(alpha = .85f),
                radius = 2.dp.toPx(),
                center = safePoint,
            )
        }
    }
}
