package com.orbital.synth.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sound and gesture data are supplied by the existing real synth host; this
 * never creates a fake FFT or simulates audio. The orb is a visualization of
 * morph, movement, incoming AudioTrack energy, and touch expression.
 *
 * Android 13+: original mathematically shaded liquid/plasma art rendered with
 * GPU AGSL; Android 8–12: deterministic low-cost vector fallback. Neither
 * version allocates in an audio callback or touches the synthesis engine.
 */
@Composable
fun OrbitalPlasmaField(
    morph: Float,
    movement: Float,
    audioRms: Float = 0f,
    audioPeak: Float = 0f,
    touchX: Float = .5f,
    touchY: Float = .5f,
    pressed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val signal = remember(morph, movement, audioRms, audioPeak, touchX, touchY, pressed) {
        OrbitalPlasmaSignal.sanitize(
            morph, movement, audioRms, audioPeak, touchX, touchY, pressed,
        )
    }
    // Draw nothing on a timer when idle. Actual audio or a finger wakes a
    // bounded ~30fps fluid flow; otherwise the lit artwork remains static.
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(signal.isActive) {
        if (signal.isActive) {
            var previous = 0L
            while (true) {
                withFrameNanos { timestamp ->
                    if (previous != 0L) {
                        val seconds = ((timestamp - previous) / 1_000_000_000f)
                            .coerceIn(0f, .05f)
                        elapsed += seconds
                    }
                    previous = timestamp
                }
                delay(33L)
            }
        }
    }
    val visualModifier = modifier
        .testTag("orbitalPlasmaField")
        .semantics {
            contentDescription =
                "Fluid cyan violet and gold sound orb reacts to synthesis controls and performed sound"
        }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PlasmaGpuField(signal, elapsed, visualModifier)
    } else {
        PlasmaVectorFallback(signal, elapsed, visualModifier)
    }
}

/** Pure immutable host-to-renderer contract, independently testable on the JVM. */
internal data class OrbitalPlasmaSignal(
    val morph: Float,
    val movement: Float,
    val rms: Float,
    val peak: Float,
    val touchX: Float,
    val touchY: Float,
    val pressed: Boolean,
) {
    val isActive: Boolean get() = pressed || rms > .005f || peak > .012f

    companion object {
        fun sanitize(
            morph: Float,
            movement: Float,
            rms: Float,
            peak: Float,
            touchX: Float,
            touchY: Float,
            pressed: Boolean,
        ): OrbitalPlasmaSignal {
            fun finiteUnit(value: Float, default: Float): Float =
                if (value.isFinite()) value.coerceIn(0f, 1f) else default
            return OrbitalPlasmaSignal(
                finiteUnit(morph, .5f),
                finiteUnit(movement, .5f),
                finiteUnit(rms, 0f),
                finiteUnit(peak, 0f),
                finiteUnit(touchX, .5f),
                finiteUnit(touchY, .5f),
                pressed,
            )
        }
    }
}

/**
 * AGSL's shader uses cheap hash/value noise and constant bounded loops.
 * Turbulent domain-warped blue/violet ribbons, thin electric shell, warm
 * reflected filaments and individual gold particles occupy a black sphere.
 *
 * This is an original procedural shader—not a flattened copy of the concept,
 * a downloaded synth UI image, or an unsupported spectral-analysis claim.
 */
private const val ORBITAL_PLASMA_AGSL = """
    uniform float2 resolution;
    uniform float seconds;
    uniform float uMorph;
    uniform float uMovement;
    uniform float uRms;
    uniform float uPeak;
    uniform float2 uTouch;
    uniform float uPressed;

    float hash21(float2 p) {
        float3 q = fract(float3(p.x, p.y, p.x) * 0.1031);
        q += dot(q, q.yzx + 33.33);
        return fract((q.x + q.y) * q.z);
    }
    float valueNoise(float2 p) {
        float2 cell = floor(p);
        float2 f = fract(p);
        float2 smoothF = f * f * (3.0 - 2.0 * f);
        float a = hash21(cell);
        float b = hash21(cell + float2(1.0, 0.0));
        float c = hash21(cell + float2(0.0, 1.0));
        float d = hash21(cell + float2(1.0, 1.0));
        return mix(mix(a, b, smoothF.x), mix(c, d, smoothF.x), smoothF.y);
    }
    float turbulence(float2 p) {
        float sum = 0.0;
        float amp = 0.55;
        for (int k = 0; k < 3; k++) {
            sum += amp * valueNoise(p);
            p = p * 1.97 + float2(7.1, -4.3);
            amp *= 0.47;
        }
        return sum;
    }
    half4 main(float2 fragCoord) {
        float m = min(resolution.x, resolution.y);
        float2 p = (fragCoord - resolution * 0.5) / m;
        float r = length(p);
        float a = atan(p.y, p.x);
        float t = seconds * (0.15 + 0.19 * uMovement);
        float expression = clamp(uRms * 1.8 + uPeak * 0.42, 0.0, 0.8)
                           + uPressed * 0.16;
        float2 displacement = (uTouch - 0.5) * 0.14;

        float wave = turbulence(float2(a * 0.93 + t, r * 13.7 - t * 0.4)
                       + displacement);
        float spiral = sin(a * (2.5 + uMorph * 1.7) -
                           r * 20.0 + t * 1.5 + wave * 5.7);
        float2 warp = float2(
            turbulence(p * 5.1 + wave * 1.2 + float2(t, -t * 0.38)),
            turbulence(p * 6.9 - wave * 0.8 + float2(-t * 0.46, t))
        ) - 0.5;
        float2 q = p + warp * (0.090 + uMovement * 0.055 + expression * 0.033);
        float body = 1.0 - smoothstep(0.390, 0.482, length(q));
        float halo = exp(-r * r * 10.0) * 0.16;
        float edge = exp(-pow((r - 0.407 + wave * 0.026) * 31.0, 2.0));

        float2 ribbonP = p + warp * 0.075;
        float flow = ribbonP.y -
            (0.13 * sin(ribbonP.x * (8.4 + uMorph * 3.4) + t * 1.3 + wave * 3.2)
             + 0.10 * sin(ribbonP.x * 17.2 - t * 0.8 - spiral * 0.8));
        float blueArc = exp(-abs(flow + 0.075 * spiral) * (27.0 + uMorph * 12.0));
        float violetArc = exp(-abs(flow - 0.085 - 0.06 * wave) * 30.0);
        float goldArc = exp(-abs(flow + 0.120 - 0.08 * spiral) * 37.0);
        float swirling = pow(max(0.0, sin(a * 3.9 + 3.0 * wave + r * 17.0)), 3.0);
        float caustic = pow(max(0.0, sin(a * 6.0 - r * 31.0 + wave * 11.0)),
                            8.0) * 0.17;

        float3 deep = float3(0.006, 0.016, 0.055);
        float3 electric = float3(0.025, 0.59, 1.0);
        float3 violet = float3(0.41, 0.17, 1.0);
        float3 amber = float3(1.0, 0.57, 0.19);
        float3 pearl = float3(0.66, 0.93, 1.0);
        float3 rgb = deep * body * 2.8 + electric * halo;
        rgb += electric * (blueArc * 0.75 + edge * 0.55 + caustic) * body;
        rgb += violet * (violetArc * 0.83 + swirling * 0.21) * body;
        rgb += amber * (goldArc * 0.69 + caustic * 0.42) * body;
        rgb += pearl * pow(max(0.0, blueArc * goldArc), 2.0) * 0.42 * body;

        // Sparse warm firefly motes are confined to the sphere. Their position
        // is deterministic, so a still instrument never spins an idle timer.
        float2 starGrid = (p + 0.6) * 135.0;
        float2 starCell = floor(starGrid);
        float2 starPos = fract(starGrid) - 0.5;
        float starSeed = hash21(starCell);
        float glitter = step(0.983, starSeed) *
            pow(max(0.0, 1.0 - length(starPos) * 3.7), 2.0) * body;
        rgb += amber * glitter * (1.4 + expression * 1.2);
        rgb += float3(0.07, 0.13, 0.24) * exp(-pow((r - 0.47) * 20.0, 2.0))
               * (0.3 + expression * 0.3);

        rgb *= 0.86 + expression * 0.68;
        rgb = rgb / (1.0 + rgb * 0.20);
        return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun PlasmaGpuField(
    signal: OrbitalPlasmaSignal,
    elapsed: Float,
    modifier: Modifier,
) {
    // RuntimeShader is tied to this composition, not shared globally between
    // UI screens that may be drawing concurrently with distinct audio data.
    val shader = remember { RuntimeShader(ORBITAL_PLASMA_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier = modifier) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("seconds", elapsed)
        shader.setFloatUniform("uMorph", signal.morph)
        shader.setFloatUniform("uMovement", signal.movement)
        shader.setFloatUniform("uRms", signal.rms)
        shader.setFloatUniform("uPeak", signal.peak)
        shader.setFloatUniform("uTouch", signal.touchX, signal.touchY)
        shader.setFloatUniform("uPressed", if (signal.pressed) 1f else 0f)
        drawRect(Color.Black)
        drawRect(brush)
    }
}

/** Legacy Android devices still receive luminous, touch-reactive original art. */
@Composable
private fun PlasmaVectorFallback(
    signal: OrbitalPlasmaSignal,
    elapsed: Float,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        drawRect(Color.Black)
        val center = Offset(size.width * .5f, size.height * .5f)
        val radius = min(size.width, size.height) * .445f
        val energy = 0.45f + 0.6f * signal.rms + .28f * signal.peak
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    Color(0xFF080E37),
                    Color(0xFF12245E),
                    Color(0xFF061429),
                    Color.Black,
                ),
                center = center,
                radius = radius * 1.32f,
            ),
            radius = radius * 1.32f,
            center = center,
        )
        val colors = listOf(
            Color(0xFF39BFFF), Color(0xFF6D57EE),
            Color(0xFFE5A657), Color(0xFF38AFFF),
            Color(0xFFAC66E7), Color(0xFFFBC17A),
        )
        for (band in 0 until 9) {
            val path = Path()
            for (i in 0..120) {
                val xUnit = i / 120f * 2f - 1f
                val x = center.x + xUnit * radius * .96f
                val envelope = sqrt((1f - xUnit * xUnit).coerceAtLeast(0f))
                val noiseWave = sin((xUnit * (4.5f + signal.morph * 4f) +
                    band * .22f + elapsed * .35f).toDouble()).toFloat()
                val secondary = cos((xUnit * 9.1f - band * .32f +
                    elapsed * .21f).toDouble()).toFloat()
                val yUnit = (band - 4f) * .025f +
                    (noiseWave * .19f + secondary * .07f) * envelope
                val y = center.y + yUnit * radius * (1f + signal.movement * .4f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val tint = colors[band % colors.size]
            drawPath(path, tint.copy(alpha = .045f * energy),
                style = Stroke(width = 18f))
            drawPath(path, tint.copy(alpha = .37f * energy),
                style = Stroke(width = 2.1f))
        }
        drawCircle(
            color = Color(0xFF5BDFFF).copy(alpha = .29f * energy),
            radius = radius * .986f,
            center = center,
            style = Stroke(width = 1.4f),
        )
        // Deterministically seeded gold sparks; no Random in the draw loop.
        repeat(110) { i ->
            val angle = (i * 2.399963229728653).toFloat() +
                signal.movement * .14f
            val d = radius * sqrt((i + .5f) / 111f) * .89f
            val pos = Offset(
                center.x + cos(angle.toDouble()).toFloat() * d,
                center.y + sin(angle.toDouble()).toFloat() * d,
            )
            val alpha = .18f + (i % 7) / 11f
            drawCircle(
                Color(0xFFFFBF63).copy(alpha = alpha * energy.coerceAtMost(1f)),
                radius = if (i % 9 == 0) 1.8f else .7f,
                center = pos,
            )
        }
    }
}
