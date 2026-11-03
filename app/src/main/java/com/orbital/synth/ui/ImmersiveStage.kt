package com.orbital.synth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.ParameterId
import kotlin.math.abs

/**
 * Keyboardless performance canvas. This is a real input surface: touches are routed
 * to the application's one audio engine through the same note and parameter
 * callbacks as the physical keyboard. There is no second synth in the renderer.
 */
@Composable
internal fun ImmersiveStage(
    state: OrbitalUiState,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    onParameterChange: (ParameterId, Float) -> Unit,
    onExit: () -> Unit,
) {
    val mapper = remember { TouchPerformanceMapper() }
    val haptics = LocalHapticFeedback.current
    val latestOn = rememberUpdatedState(onNoteOn)
    val latestOff = rememberUpdatedState(onNoteOff)
    val latestParameter = rememberUpdatedState(onParameterChange)
    // Dedupe minute pointer jitter before triggering Compose state invalidations.
    // Finger position and the visual renderer still update on every pointer event.
    val lastExpression = remember { FloatArray(4) { Float.NaN } }
    var touchX by remember { mutableFloatStateOf(.5f) }
    var touchY by remember { mutableFloatStateOf(.5f) }
    var touchIntensity by remember { mutableFloatStateOf(0f) }
    var pulse by remember { mutableLongStateOf(0L) }
    var lastHapticAt by remember { mutableLongStateOf(-1000L) }

    // Never route an audio event through visual-only state. All updates go to the
    // same callbacks that own the AudioTrack worker and its source-specific notes.
    fun deliver(events: List<TouchVoiceEvent>) {
        for (event in events) {
            when (event) {
                is TouchVoiceEvent.NoteOn -> latestOn.value(event.note)
                is TouchVoiceEvent.NoteOff -> latestOff.value(event.note)
                is TouchVoiceEvent.Expression -> {
                    fun send(index: Int, id: ParameterId, value: Float) {
                        if (lastExpression[index].isNaN() ||
                            abs(lastExpression[index] - value) >= .008f
                        ) {
                            lastExpression[index] = value
                            latestParameter.value(id, value)
                        }
                    }
                    send(0, ParameterId.CUTOFF, event.cutoff)
                    send(1, ParameterId.MORPH, event.morph)
                    send(2, ParameterId.MOVEMENT, event.movement)
                    send(3, ParameterId.FX_AMOUNT, event.fx)
                }
            }
        }
    }

    // Retuning the octave first releases old notes, so no hanging keys remain.
    LaunchedEffect(mapper, state.octave) {
        deliver(
            mapper.updateConfig(
                TouchPerformanceConfig(rootMidiNote = ((state.octave + 1) * 12).coerceIn(0, 127)),
            ),
        )
    }
    DisposableEffect(mapper) {
        onDispose {
            deliver(mapper.cancelAll())
        }
    }

    val parameter = { id: ParameterId ->
        (state.parameters[id] ?: .5f).coerceIn(0f, 1f)
    }
    val cyan = Color(0xFF6DEBFF)
    val dim = Color(0xFF9EAFC0)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("immersiveStage"),
    ) {
        // A separate renderer paints audio-parameter-driven trails, particles and
        // harmonic arcs. The background is truly black; visual luminance is tied
        // to the performed gesture rather than to an idle animated wallpaper.
        ImmersiveVisualField(
            modifier = Modifier.fillMaxSize().alpha(if (touchIntensity > 0f) 1f else .16f),
            morph = parameter(ParameterId.MORPH),
            movement = parameter(ParameterId.MOVEMENT),
            cutoff = parameter(ParameterId.CUTOFF),
            resonance = parameter(ParameterId.RESONANCE),
            touchX = touchX,
            touchY = touchY,
            touchIntensity = touchIntensity,
            pulse = pulse,
        )

        Box(
            Modifier
                .fillMaxSize()
                .testTag("immersiveCanvas")
                .semantics {
                    contentDescription = "Immersive sound field"
                }
                .pointerInput(mapper) {
                    // awaitEachGesture completes only after every pointer is up.
                    // The finally block makes note-off safe under OS gesture
                    // cancellation, screen changes and recomposition disposal.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val activeIds = mutableSetOf<Long>()
                        fun normalizeX(x: Float): Float =
                            (x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        fun normalizeY(y: Float): Float =
                            (y / size.height.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

                        fun start(id: Long, x: Float, y: Float, pressure: Float, time: Long) {
                            activeIds.add(id)
                            touchX = normalizeX(x)
                            touchY = normalizeY(y)
                            touchIntensity = pressure.coerceIn(.15f, 1f)
                            pulse += 1L
                            // A restrained one-shot tactile accent, never a
                            // continuous vibration or a high-frequency haptic loop.
                            if (time - lastHapticAt >= 80L) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastHapticAt = time
                            }
                            deliver(mapper.touchDown(id, touchX, touchY, pressure, time))
                        }
                        start(
                            down.id.value,
                            down.position.x,
                            down.position.y,
                            down.pressure,
                            down.uptimeMillis,
                        )
                        down.consume()
                        try {
                            var pressed: Boolean
                            do {
                                val pointerEvent = awaitPointerEvent()
                                pointerEvent.changes.forEach { change ->
                                    val id = change.id.value
                                    if (change.pressed) {
                                        if (id !in activeIds) {
                                            start(
                                                id, change.position.x, change.position.y,
                                                change.pressure, change.uptimeMillis,
                                            )
                                        } else {
                                            touchX = normalizeX(change.position.x)
                                            touchY = normalizeY(change.position.y)
                                            touchIntensity = change.pressure.coerceIn(.15f, 1f)
                                            deliver(
                                                mapper.touchMove(
                                                    id, touchX, touchY, change.pressure,
                                                    change.uptimeMillis,
                                                ),
                                            )
                                        }
                                    } else if (activeIds.remove(id)) {
                                        deliver(mapper.touchUp(id))
                                    }
                                    change.consume()
                                }
                                pressed = pointerEvent.changes.any { it.pressed }
                            } while (pressed)
                        } finally {
                            // A genuine release has already been removed; a cancelled
                            // gesture or backgrounding still releases every live key.
                            activeIds.forEach { id -> deliver(mapper.touchUp(id)) }
                            activeIds.clear()
                            touchIntensity = 0f
                        }
                    }
                },
        )

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .testTag("immersiveExit")
                    .border(1.dp, cyan.copy(alpha = .55f), RoundedCornerShape(24.dp))
                    .background(Color(0xFF06121A).copy(alpha = .93f), RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button) {
                        deliver(mapper.cancelAll())
                        touchIntensity = 0f
                        onExit()
                    }
                    .semantics { contentDescription = "Exit immersive performance" }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "‹  EXIT STAGE",
                    color = cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.15.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "O R B I T A L",
                    color = Color.White.copy(alpha = .78f),
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                )
                Text(
                    "IMMERSIVE / LIVE",
                    color = cyan.copy(alpha = .75f),
                    fontSize = 9.sp,
                    letterSpacing = 1.3.sp,
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                if (touchIntensity > 0f) "SCULPTING THE FIELD" else "TOUCH THE VOID",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp,
                color = cyan.copy(alpha = if (touchIntensity > 0f) .85f else .48f),
            )
            Text(
                state.patchName.uppercase(),
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                color = Color.White.copy(alpha = .74f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "X  /  PITCH + MORPH     Y  /  FILTER + MOTION     PRESSURE  /  EXPRESSION",
                fontSize = 8.sp,
                letterSpacing = .5.sp,
                color = dim.copy(alpha = .67f),
                maxLines = 2,
            )
        }
    }
}
