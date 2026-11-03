package com.orbital.synth.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Performance-only visualization: the stage owns gestures, note lifetime and synthesis
 * callbacks. This Composable does not intercept touch or invent input audio samples.
 *
 * Controls are in [0, 1], and the artwork is a *visual analogy* of timbre, not an
 * oscilloscope/spectrum analyzer. Morph changes filament topology; movement advances
 * the currents; cutoff changes the aperture; resonance sharpens the gold corona;
 * normalized touch position, intensity and event count illuminate a local disturbance.
 */
@Composable
fun ImmersiveVisualField(
    modifier: Modifier = Modifier,
    morph: Float,
    movement: Float,
    cutoff: Float,
    resonance: Float,
    touchX: Float = 0.5f,
    touchY: Float = 0.5f,
    touchIntensity: Float = 0f,
    pulse: Long = 0L,
    audioRms: Float = 0f,
    audioPeak: Float = 0f,
    audioTransient: Float = 0f,
    audioBrightness: Float = 0f,
) {
    fun normalized(value: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else fallback

    val timbre = normalized(morph, .5f)
    val motion = normalized(movement, .5f)
    val aperture = normalized(cutoff, .5f)
    val focus = normalized(resonance, .5f)
    val fingerX = normalized(touchX, .5f)
    val fingerY = normalized(touchY, .5f)
    val pressure = normalized(touchIntensity, 0f)
    // Actual output-PCM meter; optional args preserve original old-host visuals.
    val pcm = remember(audioRms, audioPeak, audioTransient, audioBrightness) {
        AudioVisualResponse.from(audioRms, audioPeak, audioTransient, audioBrightness)
    }

    // The idle black surface has no active animation at all, saving battery.
    // Android animator-off settings also disable every moving graphic.
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val animatedPhase = if (pcm.shouldAnimate(pressure, animationsEnabled)) {
        val clock = rememberInfiniteTransition(label = "immersive-energy")
        val phase by clock.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 11_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "orbital-currents",
        )
        phase
    } else 0f
    val impact = remember { Animatable(1f) }
    val audioImpact = remember { Animatable(1f) }
    // Rising-edge threshold avoids restarting/cancelling the 620 ms animation
    // on every 30 Hz meter update (which would leave a stuck transient ring).
    val transientHit = pcm.transient > .30f
    LaunchedEffect(transientHit, animationsEnabled) {
        if (!animationsEnabled) {
            audioImpact.snapTo(1f)
        } else if (transientHit) {
            audioImpact.snapTo(0f)
            audioImpact.animateTo(1f, tween(durationMillis = 620, easing = LinearEasing))
        } else if (audioImpact.value < 1f) {
            // A metered spike may decay sooner than the ripple. Complete its
            // fade rather than leaving the previous animation cancelled in place.
            audioImpact.animateTo(1f, tween(durationMillis = 320, easing = LinearEasing))
        }
    }

    // Compose gesture coordinates are tracked for a translucent performance trail.
    // Keep only the latest 24 points, even during long drags; no event grows memory.
    val touchTrace = remember { mutableStateListOf<Offset>() }
    LaunchedEffect(fingerX, fingerY, pressure, pulse) {
        if (pressure > .01f) {
            val next = Offset(fingerX, fingerY)
            val last = touchTrace.lastOrNull()
            if (last == null || ImmersiveLightMath.shouldRecordTrailPoint(
                    last.x, last.y, next.x, next.y,
                )
            ) {
                touchTrace.add(next)
                while (touchTrace.size > 24) touchTrace.removeAt(0)
            }
        }
    }
    LaunchedEffect(pressure) {
        if (pressure <= .01f) {
            delay(900)
            touchTrace.clear()
        }
    }
    LaunchedEffect(pulse) {
        if (pulse > 0L && animationsEnabled) {
            impact.snapTo(0f)
            impact.animateTo(1f, tween(durationMillis = 850, easing = LinearEasing))
        } else {
            impact.snapTo(1f)
        }
    }

    Canvas(modifier.testTag("immersiveVisualField")) {
        val width = size.width
        val height = size.height
        val shortEdge = min(width, height)
        if (shortEdge < 1f) return@Canvas

        val cyan = Color(0xFF5AF6FF)
        val ice = Color(0xFF9BF8FF)
        val electricBlue = Color(0xFF397BFF)
        val ultraviolet = Color(0xFFBD69FF)
        val amber = Color(0xFFFFD58A)
        val ink = Color(0xFF010207)
        val turn = (2.0 * PI).toFloat()
        val energy = (.58f + pressure * .42f + pcm.energyBoost).coerceAtMost(1.18f)
        val c = Offset(
            width * (.5f + (fingerX - .5f) * pressure * .10f),
            height * (.49f + (fingerY - .5f) * pressure * .075f),
        )
        val radius = shortEdge * (.265f + aperture * .045f) * (1f + pcm.radiusBoost)
        val touch = Offset(width * fingerX, height * fingerY)
        val stroke = (shortEdge / 390f).coerceIn(.6f, 2.2f)
        val conduitEnergy = ImmersiveLightMath.conduitEnergy(pressure, pcm.rms, pcm.peak)
        val petalFlare = ImmersiveLightMath.petalFlare(pressure, pcm.rms, pcm.transient)
        val chroma = ImmersiveLightMath.chromaBlend(pcm.brightness, aperture)
        val livingHue = androidx.compose.ui.graphics.lerp(ultraviolet, cyan, chroma)

        drawRect(ink)

        // Sparse, deterministic dust field gives true black room to breathe.
        // Normalized positions and base opacity are computed only once at class init.
        for (i in ImmersiveFieldGeometry.stars.indices) {
            val star = ImmersiveFieldGeometry.stars[i]
            val twinkle = if (animationsEnabled) {
                (.72f + .28f * sin(animatedPhase * turn + i * .61f))
            } else 1f
            val color = when (i % 9) {
                0 -> amber
                1, 2 -> ultraviolet
                else -> ice
            }
            drawCircle(
                color.copy(alpha = (star.alpha * twinkle * (1f + pcm.rms * .12f)).coerceAtMost(1f)),
                radius = (if (i % 11 == 0) 1.2f else .55f) * stroke,
                center = Offset(width * star.x, height * star.y),
            )
        }

        // Silk-like interference currents: they move as the modulation macro changes.
        // They remain background details, not a misleading FFT visualization.
        for (band in 0..5) {
            val path = Path()
            val horizontalSteps = 64
            for (i in 0..horizontalSteps) {
                val u = i.toFloat() / horizontalSteps
                val x = width * u
                val bend = sin(u * turn * (1.25f + timbre) + animatedPhase * turn +
                    band * .83f + motion * 2.4f)
                val y = height * (.15f + band * .145f) +
                    bend * shortEdge * (.035f + band * .003f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val tint = when (band % 3) {
                0 -> electricBlue
                1 -> ultraviolet
                else -> cyan
            }
            drawPath(path, tint.copy(alpha = .027f), style = Stroke(width = 17f * stroke))
            drawPath(path, tint.copy(alpha = .11f), style = Stroke(width = .70f * stroke))
        }

        // Three depth-graded light volumes establish a physical-looking core.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    ultraviolet.copy(alpha = .21f * energy),
                    electricBlue.copy(alpha = .13f),
                    cyan.copy(alpha = .035f),
                    Color.Transparent,
                ),
                center = c,
                radius = radius * 2.3f,
            ),
            center = c,
            radius = radius * 2.3f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF091C37),
                    Color(0xFF07112C),
                    Color(0xFF050817),
                    ink,
                ),
                center = c + Offset(-radius * .24f, -radius * .25f),
                radius = radius * 1.38f,
            ),
            center = c,
            radius = radius * .94f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    cyan.copy(alpha = (.19f + aperture * .09f) * energy),
                    electricBlue.copy(alpha = .18f),
                    ultraviolet.copy(alpha = .08f),
                    Color.Transparent,
                ),
                center = c + Offset(-radius * .37f, -radius * .37f),
                radius = radius * 1.18f,
            ),
            center = c,
            radius = radius * .98f,
        )

        // Reactive aurora petals: twelve sculpted Bézier light volumes bloom from
        // the orbital shell. Their size responds to REAL PCM level/transient and
        // held-touch intensity; they disappear during idle silence. Each petal
        // draws a wide low-alpha bloom plus two crisp interference filaments.
        if (pressure > .01f || pcm.rms > .018f || pcm.transient > .04f) {
            val phase = if (animationsEnabled) animatedPhase * turn * .085f else 0f
            for (petal in 0 until ImmersiveLightMath.PETAL_COUNT) {
                val a = turn * petal / ImmersiveLightMath.PETAL_COUNT + phase +
                    timbre * .22f
                val span = .20f + focus * .16f
                val inner = radius * (.66f + aperture * .10f)
                val outer = radius * (1.36f + petalFlare * .28f)
                val begin = c + Offset(cos(a) * inner, sin(a) * inner * .78f)
                val tipA = a + span
                val tip = c + Offset(cos(tipA) * outer, sin(tipA) * outer * .78f)
                val endA = a + span * 2.2f
                val end = c + Offset(cos(endA) * inner, sin(endA) * inner * .78f)
                val wing = Path().apply {
                    moveTo(begin.x, begin.y)
                    cubicTo(
                        c.x + cos(a + .08f) * outer * .83f,
                        c.y + sin(a + .08f) * outer * .70f,
                        tip.x, tip.y,
                        end.x, end.y,
                    )
                }
                val tint = when (petal % 4) {
                    0 -> amber
                    1 -> livingHue
                    2 -> ultraviolet
                    else -> electricBlue
                }
                drawPath(wing, tint.copy(alpha = .028f * petalFlare),
                    style = Stroke(width = 22f * stroke, cap = StrokeCap.Round))
                drawPath(wing, tint.copy(alpha = .15f * petalFlare),
                    style = Stroke(width = 3.6f * stroke, cap = StrokeCap.Round))
                drawPath(wing, ice.copy(alpha = .25f * petalFlare),
                    style = Stroke(width = .57f * stroke, cap = StrokeCap.Round))
            }
        }

        // Concentric etched circles and rotating orbital ellipses.
        for (layer in 1..4) {
            val rr = radius * (1f + layer * .13f)
            drawCircle(
                color = if (layer % 2 == 0) ultraviolet.copy(alpha = .11f) else cyan.copy(alpha = .12f),
                radius = rr,
                center = c,
                style = Stroke(width = stroke * .53f),
            )
        }
        rotate(33f + animatedPhase * 14f + timbre * 17f, pivot = c) {
            drawOval(
                brush = Brush.sweepGradient(
                    listOf(
                        amber.copy(alpha = .03f),
                        cyan.copy(alpha = .74f),
                        electricBlue.copy(alpha = .11f),
                        ultraviolet.copy(alpha = .34f),
                        amber.copy(alpha = .81f),
                        amber.copy(alpha = .03f),
                    ),
                    center = c,
                ),
                topLeft = Offset(c.x - radius * 1.37f, c.y - radius * .41f),
                size = Size(radius * 2.74f, radius * .82f),
                style = Stroke(width = 1.9f * stroke, cap = StrokeCap.Round),
            )
        }
        rotate(-42f - animatedPhase * 21f, pivot = c) {
            drawOval(
                color = ultraviolet.copy(alpha = .36f),
                topLeft = Offset(c.x - radius * 1.28f, c.y - radius * .28f),
                size = Size(radius * 2.56f, radius * .56f),
                style = Stroke(width = 1.0f * stroke),
            )
        }

        // A visible relationship between the finger and sounding orbit: nine
        // high-curvature light tendons bend toward the actual touched pixel.
        // Disconnected/idle surfaces never draw a remembered finger position.
        if (pressure > .01f && conduitEnergy > .01f) {
            val dx = touch.x - c.x
            val dy = touch.y - c.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val tangent = Offset(-dy / distance, dx / distance)
            val touchToCore = Offset(dx, dy)
            for (strand in 0 until ImmersiveLightMath.CONDUIT_STRANDS) {
                val lane = (strand - 4) * .25f
                val start = c + tangent * (lane * radius * .08f)
                val end = touch + tangent * (lane * shortEdge * .009f)
                val twist = sin(animatedPhase * turn * .62f + strand * .79f + timbre * 3.1f) *
                    shortEdge * (.022f + motion * .02f)
                val ctrl1 = c + touchToCore * .28f + tangent * (lane * radius * .22f + twist)
                val ctrl2 = c + touchToCore * .72f -
                    tangent * (lane * radius * .12f + twist * .48f)
                val filament = Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(ctrl1.x, ctrl1.y, ctrl2.x, ctrl2.y, end.x, end.y)
                }
                val tint = when (strand % 4) {
                    0 -> amber
                    1 -> ultraviolet
                    2 -> cyan
                    else -> livingHue
                }
                drawPath(filament, tint.copy(alpha = .032f * conduitEnergy),
                    style = Stroke(width = 15f * stroke, cap = StrokeCap.Round))
                drawPath(filament, tint.copy(alpha = .24f * conduitEnergy),
                    style = Stroke(width = 2.8f * stroke, cap = StrokeCap.Round))
                drawPath(filament, ice.copy(alpha = .41f * conduitEnergy),
                    style = Stroke(width = .7f * stroke, cap = StrokeCap.Round))
            }
        }

        // Live timbre ribbons: phase and fold depend on the controls, not random noise.
        // 8 x 96 points is a bounded amount of geometry even on small/old phones.
        val t = animatedPhase * turn
        val wobbleFrequency = 2.5f + timbre * 4.5f
        for (layer in 0 until 8) {
            val line = Path()
            for (i in ImmersiveFieldGeometry.orbit.indices) {
                val unit = ImmersiveFieldGeometry.orbit[i]
                val wave = sin(unit.angle * wobbleFrequency + motion * 4.1f + t * .73f +
                    layer * .42f)
                val flutter = sin(unit.angle * (7.1f + focus * 4.7f) -
                    t * 1.42f + layer * .89f)
                val rr = radius * (.43f + layer * .073f +
                    wave * (.075f + timbre * .048f + pcm.filamentBoost) +
                    flutter * (.026f * focus + pcm.brightness * .008f))
                val x = c.x + unit.x * rr
                val y = c.y + unit.y * rr * (.76f + timbre * .16f)
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            line.close()
            val tint = when (layer % 4) {
                0 -> cyan
                1 -> electricBlue
                2 -> ultraviolet
                else -> amber
            }
            drawPath(line, tint.copy(alpha = .038f * energy),
                style = Stroke(width = (10.0f + layer * .36f) * stroke))
            drawPath(line, tint.copy(alpha = .20f * energy),
                style = Stroke(width = 3.4f * stroke))
            drawPath(line, tint.copy(alpha = (.61f + focus * .17f) * energy),
                style = Stroke(width = .86f * stroke, cap = StrokeCap.Round))
        }

        // Resonance controls the tight corona; cutoff opens the central aperture.
        val coronaCount = 72
        for (i in 0 until coronaCount) {
            val tick = ImmersiveFieldGeometry.corona[i]
            val major = i % 6 == 0
            val inside = radius * (1.10f + if (major) .02f else .09f)
            val outside = inside + radius * (if (major) .14f + focus * .07f else .03f)
            drawLine(
                (if (i % 9 == 0) amber else cyan).copy(alpha = if (major) .64f else .22f),
                c + Offset(tick.x * inside, tick.y * inside),
                c + Offset(tick.x * outside, tick.y * outside),
                strokeWidth = if (major) 1.25f * stroke else .50f * stroke,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(
            color = cyan.copy(alpha = .43f),
            center = c,
            radius = radius * (.30f + .18f * aperture),
            style = Stroke(width = stroke * (1.0f + focus)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    Color.White.copy(alpha = .37f * energy),
                    cyan.copy(alpha = .25f * energy),
                    ultraviolet.copy(alpha = .09f),
                    Color.Transparent,
                ),
                center = c,
                radius = radius * (.29f + .16f * aperture),
            ),
            center = c,
            radius = radius * (.29f + .16f * aperture),
        )
        drawCircle(Color.White.copy(alpha = .83f * energy), radius = 1.9f * stroke, center = c)

        // A small set of harmonically related brightness bars at the foot of the
        // screen makes the current configuration legible while the keys are hidden.
        val baseline = height * .93f
        val barCount = 48
        for (i in 0 until barCount) {
            val u = i.toFloat() / (barCount - 1)
            val s1 = sin(u * turn * (3.0f + timbre * 3.0f) + t + motion * 2.7f)
            val s2 = sin(u * turn * (8.0f + focus * 4.0f) - t * 1.65f)
            val amplitude = (.2f + .54f * kotlin.math.abs(s1) +
                .26f * kotlin.math.abs(s2)) * (.35f + aperture * .65f)
            val hh = shortEdge * .065f * amplitude * (1f + pcm.rms * .33f)
            val x = width * (.07f + u * .86f)
            drawLine(
                color = if (i % 9 == 0) amber.copy(alpha = .78f) else cyan.copy(alpha = .35f),
                start = Offset(x, baseline),
                end = Offset(x, baseline - hh),
                strokeWidth = (if (i % 9 == 0) 1.6f else .75f) * stroke,
                cap = StrokeCap.Round,
            )
        }

        // True PCM waveform-rate brightness influences a separate spectral volume.
        // Silent/missing telemetry draws nothing here, preserving design parity.
        if (pcm.rms > .005f || pcm.peak > .02f) {
            val pcmCenter = c + Offset(0f, radius * .08f)
            val pcmHue = androidx.compose.ui.graphics.lerp(
                ultraviolet, cyan, pcm.brightness,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        pcmHue.copy(alpha = (.18f * pcm.rms + .10f * pcm.peak).coerceAtMost(.28f)),
                        electricBlue.copy(alpha = .04f * pcm.peak),
                        Color.Transparent,
                    ),
                    center = pcmCenter,
                    radius = radius * (1.28f + pcm.peak * .32f),
                ),
                center = pcmCenter,
                radius = radius * (1.28f + pcm.peak * .32f),
            )
        }

        // Actual PCM dynamics open irregular arc-shaped caustics around the
        // aperture. These are level-controlled light paths, NOT frequency bins.
        // Brightness (a waveform-slope proxy) selects color, while RMS/peak
        // govern size and opacity; no audio -> no caustic draw calls.
        val audible = (pcm.rms * .62f + pcm.peak * .38f).coerceIn(0f, 1f)
        if (audible > .014f) {
            for (arc in 0 until 7) {
                val rr = radius * (.58f + arc * .094f + pcm.radiusBoost * .45f)
                val angle = arc * 51.43f + timbre * 70f +
                    if (animationsEnabled) animatedPhase * 95f else 0f
                val tint = when (arc % 4) {
                    0 -> amber
                    1 -> livingHue
                    2 -> electricBlue
                    else -> ultraviolet
                }
                drawArc(
                    color = tint.copy(alpha = (.065f + audible * .36f) * (.65f + arc * .038f)),
                    startAngle = angle,
                    sweepAngle = (19f + aperture * 26f + pcm.brightness * 18f),
                    useCenter = false,
                    topLeft = Offset(c.x - rr, c.y - rr * .82f),
                    size = Size(rr * 2f, rr * 1.64f),
                    style = Stroke(width = (.85f + audible * 1.65f) * stroke, cap = StrokeCap.Round),
                )
            }
        }

        // Touch trail is deterministic, bounded and owned by the event-driven stage.
        if (touchTrace.isNotEmpty()) {
            val n = touchTrace.size
            for (i in 1 until n) {
                val before = touchTrace[i - 1]
                val after = touchTrace[i]
                val a = Offset(before.x * width, before.y * height)
                val b = Offset(after.x * width, after.y * height)
                val opacity = (.10f + .60f * i / n) * energy
                drawLine(ultraviolet.copy(alpha = opacity * .13f), a, b,
                    strokeWidth = 24f * stroke, cap = StrokeCap.Round)
                drawLine(cyan.copy(alpha = opacity * .20f), a, b,
                    strokeWidth = 9f * stroke, cap = StrokeCap.Round)
                drawLine(ice.copy(alpha = opacity), a, b,
                    strokeWidth = 1.5f * stroke, cap = StrokeCap.Round)
            }
            for (i in 0 until n step 3) {
                val sample = touchTrace[i]
                drawCircle(
                    color = (if (i % 6 == 0) ultraviolet else amber).copy(
                        alpha = (.14f + .38f * i / n) * energy,
                    ),
                    radius = (1.2f + 1.5f * i / n) * stroke,
                    center = Offset(sample.x * width, sample.y * height),
                )
            }
        }

        // Each fresh touch expands a visible wave. Repeated taps restart the pulse
        // and fingers remain illuminated at their actual screen position.
        if (pulse > 0 && impact.value < 1f && animationsEnabled) {
            val progress = impact.value
            val opacity = (1f - progress) * .78f
            for (ring in 0..2) {
                val r = shortEdge * (.025f + progress * (.24f + ring * .10f))
                drawCircle(
                    color = (if (ring == 1) amber else cyan).copy(alpha = opacity * (.6f - ring * .14f)),
                    radius = r,
                    center = touch,
                    style = Stroke(width = (3.1f - ring * .7f) * stroke),
                )
            }
        }

        // A fresh tap or actual PCM transient releases 48 bounded, deterministic
        // photon motes. They fly from the performed pixel (touch) or sounding
        // nucleus (PCM), never from a random point. Once the event decays, the
        // entire layer stops drawing; idle battery cost is zero.
        val gestureBurst = animationsEnabled && pulse > 0L && impact.value < 1f
        val pcmBurst = animationsEnabled && pcm.transient > .03f &&
            audioImpact.value < 1f
        if (gestureBurst || pcmBurst) {
            val useGesture = gestureBurst &&
                (!pcmBurst || impact.value <= audioImpact.value)
            val progress = if (useGesture) impact.value else audioImpact.value
            val origin = if (useGesture) touch else c
            val burstLevel = if (useGesture)
                ImmersiveLightMath.burstEnergy(.86f, pcm.transient)
            else ImmersiveLightMath.burstEnergy(0f, pcm.transient)
            val opacity = ImmersiveLightMath.burstOpacity(progress, burstLevel)
            if (opacity > .003f) {
                for (seed in ImmersiveLightMath.embers) {
                    val spread = ImmersiveLightMath.emberDisplacement(
                        seed, progress, burstLevel, shortEdge,
                    )
                    val spin = if (animationsEnabled) animatedPhase * turn * .19f else 0f
                    val a = seed.angle + spin +
                        sin(seed.phase * turn + timbre * 2f) * .12f
                    val point = origin + Offset(
                        cos(a) * spread,
                        sin(a) * spread * (.79f + aperture * .16f),
                    )
                    val tint = when (seed.hueLane) {
                        0 -> amber
                        1 -> livingHue
                        2 -> ultraviolet
                        else -> ice
                    }
                    val alpha = (opacity * (.26f + seed.phase * .34f)).coerceAtMost(1f)
                    drawCircle(
                        color = tint.copy(alpha = alpha * .15f),
                        radius = seed.radiusScale * (5f + 3f * pcm.peak) * stroke,
                        center = point,
                    )
                    drawCircle(
                        color = tint.copy(alpha = alpha),
                        radius = seed.radiusScale * .98f * stroke,
                        center = point,
                    )
                }
            }
        }

        // Rising measured PCM transients launch independent fading concentric light.
        if (animationsEnabled && audioImpact.value < 1f) {
            val progress = audioImpact.value
            val opacity = (1f - progress) * .52f
            val tint = androidx.compose.ui.graphics.lerp(ultraviolet, cyan, pcm.brightness)
            for (ring in 0..2) {
                drawCircle(
                    color = tint.copy(alpha = opacity * (1f - ring * .2f)),
                    radius = radius * (.65f + progress * (.88f + ring * .21f)),
                    center = c,
                    style = Stroke(width = (2.2f - ring * .45f) * stroke),
                )
            }
        }

        if (pressure > .01f) {
            val active = .35f + pressure * .65f
            drawCircle(cyan.copy(alpha = .09f * active), 38f * stroke, touch)
            drawCircle(ultraviolet.copy(alpha = .22f * active), 19f * stroke, touch)
            drawCircle(amber.copy(alpha = .54f * active), 9f * stroke, touch,
                style = Stroke(width = 1.3f * stroke))
            drawCircle(Color.White.copy(alpha = .93f), (1.4f + 1.8f * pressure) * stroke, touch)
            val cross = 15f * stroke
            drawLine(ice.copy(alpha = .36f), touch + Offset(-cross, 0f),
                touch + Offset(cross, 0f), .6f * stroke)
            drawLine(ice.copy(alpha = .36f), touch + Offset(0f, -cross),
                touch + Offset(0f, cross), .6f * stroke)
        }
    }
}

private object ImmersiveFieldGeometry {
    data class Point(val x: Float, val y: Float, val angle: Float)
    data class Star(val x: Float, val y: Float, val alpha: Float)

    // Trigonometry for static unit vectors runs exactly once, not on the draw path.
    val orbit = Array(96) { index ->
        val theta = (2.0 * PI * index / 96.0).toFloat()
        Point(cos(theta), sin(theta), theta)
    }
    val corona = Array(72) { index ->
        val theta = (2.0 * PI * index / 72.0).toFloat()
        Point(cos(theta), sin(theta), theta)
    }
    val stars = Array(94) { index ->
        val theta = index * 2.399963229728653
        val radial = .10 + ((index * 37) % 79) / 100.0
        Star(
            x = (.5 + cos(theta) * radial * .54).toFloat().coerceIn(0f, 1f),
            y = (.5 + sin(theta) * radial * .54).toFloat().coerceIn(0f, 1f),
            alpha = (.08f + (index % 7) * .045f),
        )
    }
}
