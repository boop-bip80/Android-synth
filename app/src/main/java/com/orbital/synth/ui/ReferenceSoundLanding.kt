package com.orbital.synth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.audio.AudioVisualSnapshot
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId

/**
 * Actual SOUND landing composition. The original reference artwork stays in
 * design/ for comparison; the displayed plasma, ribbon, controls and notes
 * are native live components, not a flattened screenshot.
 *
 * Both gesture surfaces and five rotary controls manipulate the same audio
 * host parameters that the keyboard, MIDI and STEP sequencer use.
 */
@Composable
fun ReferenceSoundLanding(
    parameters: Map<ParameterId, Float>,
    lfos: List<ModulationLfoConfig>,
    routes: List<ModulationRouteConfig>,
    onParameterChange: (ParameterId, Float) -> Unit,
    audio: AudioVisualSnapshot,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val morph = finiteNormalized(parameters[ParameterId.MORPH], .5f)
    val movement = finiteNormalized(parameters[ParameterId.MOVEMENT], .5f)
    val onChangeNow by rememberUpdatedState(onParameterChange)
    var touchX by remember { mutableFloatStateOf(.5f) }
    var touchY by remember { mutableFloatStateOf(.5f) }
    var pressed by remember { mutableStateOf(false) }
    val shape = remember { RoundedCornerShape(18.dp) }

    Column(
        modifier.fillMaxWidth().testTag("referenceSoundLanding"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 164.dp else 236.dp)
                .background(Color(0xFF010713), shape)
                .border(1.dp, OrbitalPalette.Cyan.copy(alpha = .35f), shape)
                .testTag("referenceSoundHero")
                .semantics {
                    contentDescription =
                        "Live orbital plasma sound field; tap or drag to edit morph and movement"
                    customActions = listOf(
                        CustomAccessibilityAction("Increase morph") {
                            onChangeNow(ParameterId.MORPH, (morph + .05f).coerceAtMost(1f))
                            true
                        },
                        CustomAccessibilityAction("Decrease morph") {
                            onChangeNow(ParameterId.MORPH, (morph - .05f).coerceAtLeast(0f))
                            true
                        },
                        CustomAccessibilityAction("Increase movement") {
                            onChangeNow(ParameterId.MOVEMENT, (movement + .05f).coerceAtMost(1f))
                            true
                        },
                        CustomAccessibilityAction("Decrease movement") {
                            onChangeNow(ParameterId.MOVEMENT, (movement - .05f).coerceAtLeast(0f))
                            true
                        },
                    )
                }
                .pointerInput(Unit) {
                    fun update(position: Offset) {
                        if (size.width <= 0 || size.height <= 0) return
                        val x = (position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val y = (1f - position.y / size.height.toFloat()).coerceIn(0f, 1f)
                        touchX = x
                        touchY = y
                        onChangeNow(ParameterId.MORPH, x)
                        onChangeNow(ParameterId.MOVEMENT, y)
                    }
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            try { tryAwaitRelease() } finally { pressed = false }
                        },
                        onTap = { update(it) },
                    )
                }
                .pointerInput(Unit) {
                    fun update(position: Offset) {
                        if (size.width <= 0 || size.height <= 0) return
                        val x = (position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val y = (1f - position.y / size.height.toFloat()).coerceIn(0f, 1f)
                        touchX = x
                        touchY = y
                        onChangeNow(ParameterId.MORPH, x)
                        onChangeNow(ParameterId.MOVEMENT, y)
                    }
                    detectDragGestures(
                        onDragStart = {
                            pressed = true
                            update(it)
                        },
                        onDrag = { change, _ ->
                            update(change.position)
                            change.consume()
                        },
                        onDragEnd = { pressed = false },
                        onDragCancel = { pressed = false },
                    )
                },
        ) {
            OrbitalPlasmaField(
                morph = morph,
                movement = movement,
                audioRms = audio.rms,
                audioPeak = audio.peak,
                touchX = touchX,
                touchY = touchY,
                pressed = pressed,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "O R B I T A L   /   LIVE FIELD",
                color = OrbitalPalette.Cyan.copy(alpha = .85f),
                fontSize = 9.sp,
                letterSpacing = 1.0.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart).padding(13.dp),
            )
            Text(
                "X / MORPH     Y / MOVEMENT     AUDIO / PCM",
                color = OrbitalPalette.Text.copy(alpha = .68f),
                fontSize = 8.sp,
                letterSpacing = .4.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
            )
        }

        ReferenceSoundControls(
            parameters = parameters,
            lfos = lfos,
            routes = routes,
            onParameterChange = onParameterChange,
            audioRms = audio.rms,
            compact = compact,
        )
    }
}

private fun finiteNormalized(value: Float?, fallback: Float): Float =
    value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: fallback
