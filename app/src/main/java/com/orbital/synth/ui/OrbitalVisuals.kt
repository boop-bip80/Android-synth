package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin


/**
 * Unit vectors and fixed visual attributes are computed once, not every time
 * a keyboard press causes the parent Compose tree to redraw its controls.
 */
internal object OrbitalVisualGeometry {
    data class Dust(val direction: Offset, val distance: Float, val gold: Boolean, val alpha: Float)
    data class Tick(val direction: Offset, val major: Boolean, val accent: Boolean = false)

    val dust = (0 until 64).map { i ->
        val angle = i * 2.399963229728653
        val gold = i % 13 == 0
        Dust(
            direction = Offset(cos(angle).toFloat(), sin(angle).toFloat()),
            distance = 1.08f + (i * 31 % 50) / 100f,
            gold = gold,
            alpha = if (gold) .68f else .22f + (i % 5) * .045f,
        )
    }
    val ringTicks = (0 until 96).map { i ->
        val angle = (i * PI * 2.0 / 96.0) - PI / 2
        Tick(
            direction = Offset(cos(angle).toFloat(), sin(angle).toFloat()),
            major = i % 8 == 0,
            accent = i % 24 == 0,
        )
    }
    val knobTicks = (0..32).map { i ->
        val angle = Math.toRadians(135.0 + i * 270.0 / 32.0)
        Tick(
            direction = Offset(cos(angle).toFloat(), sin(angle).toFloat()),
            major = i % 4 == 0,
        )
    }
    val junctions = listOf(25.0, 162.0, 277.0).map { degrees ->
        val angle = Math.toRadians(degrees)
        Offset(cos(angle).toFloat(), sin(angle).toFloat())
    }
    fun ribbonRadii(morph: Float, movement: Float): Array<DoubleArray> {
        val m = morph.coerceIn(0f, 1f)
        val motion = movement.coerceIn(0f, 1f)
        val frequency = 2.75 + m.toDouble() * 2.8
        return Array(12) { layer ->
            val phase = motion.toDouble() * PI * 2.0 + layer * .33
            val shifted = phase * .65
            val phaseCosine = cos(shifted)
            val phaseSine = sin(shifted)
            DoubleArray(OrbitalTraceMath.samples.size) { index ->
                val twist = OrbitalTraceMath.twist(
                    OrbitalTraceMath.samples[index],
                    frequency,
                    phase,
                    phaseCosine,
                    phaseSine,
                )
                .37 + layer * .048 + twist * .86
            }
        }
    }
}

private val graphPanelBrush = Brush.verticalGradient(
    listOf(Color(0xB8183154), Color(0xC4081427)),
)
private val signalPanelBrush = Brush.verticalGradient(
    listOf(Color(0xFF10223D), Color(0xFF091527)),
)

/**
 * Native, parameter-driven visualization. The original 2MB concept image is in
 * design/orbital-ui-reference.png; the app draws its own scalable graphics.
 * Deliberately static unless parameters change: respects reduced-motion settings.
 */
@Composable
internal fun OrbitalDisplay(
    morph: Float,
    movement: Float,
    modifier: Modifier = Modifier,
    onExpressionChange: ((Float, Float) -> Unit)? = null,
) {
    val expressionCallback by rememberUpdatedState(onExpressionChange)
    val clampedMorph = morph.coerceIn(0f, 1f)
    val clampedMovement = movement.coerceIn(0f, 1f)
    // Sine-heavy harmonic deformation depends on the two expression parameters,
    // not on canvas size. Reuse it across keyboard, panel and accessibility redraws.
    // Size multiplication remains in DrawScope to preserve the existing geometry.
    val ribbonRadii = remember(clampedMorph, clampedMovement) {
        OrbitalVisualGeometry.ribbonRadii(clampedMorph, clampedMovement)
    }
    Canvas(
        modifier
            .testTag("orbitalExpression")
            .semantics {
                if (onExpressionChange != null) {
                    contentDescription = "Orbital expression surface. Drag horizontally to change morph and vertically to change movement."
                    customActions = listOf(
                        CustomAccessibilityAction("Increase morph") {
                            expressionCallback?.invoke((morph + .05f).coerceAtMost(1f), movement)
                            true
                        },
                        CustomAccessibilityAction("Decrease morph") {
                            expressionCallback?.invoke((morph - .05f).coerceAtLeast(0f), movement)
                            true
                        },
                        CustomAccessibilityAction("Increase movement") {
                            expressionCallback?.invoke(morph, (movement + .05f).coerceAtMost(1f))
                            true
                        },
                        CustomAccessibilityAction("Decrease movement") {
                            expressionCallback?.invoke(morph, (movement - .05f).coerceAtLeast(0f))
                            true
                        },
                    )
                }
            }
            .pointerInput(onExpressionChange != null) {
                if (onExpressionChange != null) {
                    // One detector owns both taps and continuous drags. Consuming the
                    // down event keeps the scrollable editor from stealing a performance gesture.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun moveTo(point: Offset) {
                            val (nextMorph, nextMovement) = expressionCoordinates(
                                point.x, point.y, size.width.toFloat(), size.height.toFloat(),
                            )
                            expressionCallback?.invoke(nextMorph, nextMovement)
                        }
                        moveTo(down.position)
                        down.consume()
                        var lastPosition = down.position
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull {
                                it.id == down.id
                            } ?: break
                            if (!change.pressed) break
                            if (change.position != lastPosition) {
                                moveTo(change.position)
                                lastPosition = change.position
                            }
                            change.consume()
                        }
                    }
                }
            },
    ) {
        val c = Offset(size.width * .5f, size.height * .5f)
        val radius = min(size.width, size.height) * .385f
        if (radius <= 0f) return@Canvas
        val m = clampedMorph
        val motion = clampedMovement

        // The celestial body is calculated from morph and movement rather than
        // animating a disconnected video. No timers, shader allocation or randomness.
        val halo = radius * (1.58f + motion * .12f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    OrbitalPalette.Violet.copy(alpha = .25f + motion * .12f),
                    OrbitalPalette.Blue.copy(alpha = .18f),
                    OrbitalPalette.Cyan.copy(alpha = .08f),
                    Color.Transparent,
                ),
                center = c,
                radius = halo,
            ),
            radius = halo,
            center = c,
        )
        // Floating dust outside the disc establishes a sense of space without
        // obscuring the gesture surface. Coordinates are deterministic.
        for (dust in OrbitalVisualGeometry.dust) {
            val distance = radius * dust.distance
            val p = Offset(
                c.x + dust.direction.x * distance,
                c.y + dust.direction.y * distance * .87f,
            )
            drawCircle(
                color = (if (dust.gold) OrbitalPalette.Gold else OrbitalPalette.Cyan).copy(alpha = dust.alpha),
                radius = (if (dust.gold) 1.35f else .65f).dp.toPx(),
                center = p,
            )
        }

        // Multiple offset illumination gradients give the orb a physical front,
        // a cool terminator and a dense, deliberately dark interior.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF275B9B),
                    Color(0xFF163A79),
                    Color(0xFF0E2253),
                    Color(0xFF070E27),
                    Color(0xFF030712),
                ),
                center = Offset(c.x - radius * .35f, c.y - radius * .34f),
                radius = radius * 1.42f,
            ),
            radius = radius,
            center = c,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    OrbitalPalette.Cyan.copy(alpha = .27f),
                    OrbitalPalette.Blue.copy(alpha = .15f),
                    OrbitalPalette.Violet.copy(alpha = .07f),
                    Color.Transparent,
                ),
                center = Offset(c.x - radius * .42f, c.y - radius * .45f),
                radius = radius * .95f,
            ),
            radius = radius,
            center = c,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    OrbitalPalette.Violet.copy(alpha = .05f),
                    OrbitalPalette.Violet.copy(alpha = .32f),
                    Color(0xFF02040C),
                ),
                center = Offset(c.x + radius * .08f, c.y + radius * .02f),
                radius = radius * 1.07f,
            ),
            radius = radius,
            center = c,
        )
        drawCircle(
            color = OrbitalPalette.Cyan.copy(alpha = .20f),
            radius = radius,
            center = c,
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawCircle(
            color = OrbitalPalette.Violet.copy(alpha = .10f),
            radius = radius * 1.012f,
            center = c,
            style = Stroke(width = 5.dp.toPx()),
        )

        // Faint orbital longitude/latitude arcs put the active timbral ribbons
        // on a readable spherical grid. Both angles are parameter-driven.
        for (band in -3..3) {
            val heightFraction = 1f - kotlin.math.abs(band) * .18f
            val bandRadius = radius * (.14f + heightFraction * .75f)
            rotate(17f + m * 12f, c) {
                drawOval(
                    color = OrbitalPalette.Cyan.copy(alpha = if (band == 0) .18f else .075f),
                    topLeft = Offset(c.x - bandRadius, c.y - radius * .26f - band * radius * .10f),
                    size = Size(bandRadius * 2f, radius * (.52f + heightFraction * .07f)),
                    style = Stroke(width = .55.dp.toPx()),
                )
            }
        }
        for (meridian in -2..2) {
            rotate(meridian * 26f + m * 13f, c) {
                drawOval(
                    color = OrbitalPalette.Blue.copy(alpha = .08f),
                    topLeft = Offset(c.x - radius * .22f, c.y - radius * .96f),
                    size = Size(radius * .44f, radius * 1.92f),
                    style = Stroke(width = .6.dp.toPx()),
                )
            }
        }

        // Twelve harmonic ribbons are not a sampled audio spectrum; their phase,
        // density and curvature communicate the actual morph/movement controls.
        val orbitYScale = .70f + m * .19f
        val orbitSamples = OrbitalTraceMath.samples
        for (layer in 0 until 12) {
            val path = Path()
            val cachedRadii = ribbonRadii[layer]
            for (i in orbitSamples.indices) {
                val sample = orbitSamples[i]
                val rr = radius.toDouble() * cachedRadii[i]
                val x = c.x + sample.cosine.toFloat() * rr.toFloat()
                val y = c.y + sample.sine.toFloat() * rr.toFloat() * orbitYScale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val tint = when (layer % 4) {
                0 -> OrbitalPalette.Cyan
                1 -> OrbitalPalette.Blue
                2 -> OrbitalPalette.Violet
                else -> OrbitalPalette.Gold
            }
            val strength = .54f + motion * .19f
            drawPath(path, tint.copy(alpha = .045f), style = Stroke(width = (8f + layer * .55f).dp.toPx()))
            drawPath(path, tint.copy(alpha = .20f), style = Stroke(width = (3.3f + layer * .13f).dp.toPx()))
            drawPath(path, tint.copy(alpha = strength), style = Stroke(width = (1.05f + layer * .055f).dp.toPx()))
        }

        // Engraved external halo with calibrated, fine tick marks.
        for (tick in OrbitalVisualGeometry.ringTicks) {
            val inner = radius * (if (tick.major) 1.055f else 1.086f)
            val outer = radius * (if (tick.major) 1.14f else 1.111f)
            val tint = if (tick.accent) OrbitalPalette.Gold else OrbitalPalette.Cyan
            drawLine(
                tint.copy(alpha = if (tick.major) .53f else .19f),
                start = c + tick.direction * inner,
                end = c + tick.direction * outer,
                strokeWidth = (if (tick.major) .9f else .5f).dp.toPx(),
            )
        }
        drawCircle(
            color = OrbitalPalette.Cyan.copy(alpha = .14f),
            radius = radius * 1.11f,
            center = c,
            style = Stroke(width = .6.dp.toPx()),
        )

        // Two tilted satellite orbits create the holographic instrument silhouette.
        rotate(28f + 9f * m, c) {
            drawOval(
                brush = Brush.sweepGradient(
                    listOf(
                        OrbitalPalette.Gold.copy(alpha = .10f),
                        OrbitalPalette.Cyan.copy(alpha = .60f),
                        OrbitalPalette.Violet.copy(alpha = .15f),
                        OrbitalPalette.Gold.copy(alpha = .83f),
                        OrbitalPalette.Gold.copy(alpha = .10f),
                    ),
                    center = c,
                ),
                topLeft = Offset(c.x - radius * 1.23f, c.y - radius * .34f),
                size = Size(radius * 2.46f, radius * .68f),
                style = Stroke(width = 1.55.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        rotate(-39f + 8f * motion, c) {
            drawOval(
                color = OrbitalPalette.Blue.copy(alpha = .42f),
                topLeft = Offset(c.x - radius * 1.12f, c.y - radius * .36f),
                size = Size(radius * 2.24f, radius * .72f),
                style = Stroke(width = .85.dp.toPx()),
            )
        }
        // Glowing orbital junctions and an asymmetrical specular glint.
        OrbitalVisualGeometry.junctions.forEachIndexed { index, direction ->
            val p = Offset(
                c.x + direction.x * radius * 1.11f,
                c.y + direction.y * radius * .68f,
            )
            val tint = if (index == 1) OrbitalPalette.Gold else OrbitalPalette.Cyan
            drawCircle(tint.copy(alpha = .09f), 15.dp.toPx(), p)
            drawCircle(tint.copy(alpha = .22f), 7.dp.toPx(), p)
            drawCircle(tint, (2.2f + index * .5f).dp.toPx(), p)
            drawCircle(Color.White.copy(alpha = .9f), .8.dp.toPx(), p)
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = .43f), OrbitalPalette.Cyan.copy(alpha = .10f), Color.Transparent),
                center = Offset(c.x - radius * .35f, c.y - radius * .47f),
                radius = radius * .36f,
            ),
            center = Offset(c.x - radius * .35f, c.y - radius * .47f),
            radius = radius * .36f,
        )

        // The luminous reticle is the accessible XY gesture target, and always
        // displays the real normalized values even when the instrument is silent.
        if (onExpressionChange != null) {
            val edge = 18.dp.toPx()
            val marker = Offset(
                edge + (size.width - edge * 2f).coerceAtLeast(0f) * m,
                edge + (size.height - edge * 2f).coerceAtLeast(0f) * (1f - motion),
            )
            val crosshair = 15.dp.toPx()
            drawLine(OrbitalPalette.Gold.copy(alpha = .21f), marker + Offset(-crosshair, 0f), marker + Offset(crosshair, 0f), .6.dp.toPx())
            drawLine(OrbitalPalette.Gold.copy(alpha = .21f), marker + Offset(0f, -crosshair), marker + Offset(0f, crosshair), .6.dp.toPx())
            drawCircle(OrbitalPalette.Cyan.copy(alpha = .09f), 22.dp.toPx(), marker)
            drawCircle(OrbitalPalette.Gold.copy(alpha = .20f), 13.dp.toPx(), marker)
            drawCircle(Color(0xFF06111F).copy(alpha = .96f), 7.dp.toPx(), marker)
            drawCircle(OrbitalPalette.Gold, 5.dp.toPx(), marker, style = Stroke(1.3.dp.toPx()))
            drawCircle(Color.White, 1.35.dp.toPx(), marker)
        }
    }
}

@Composable
internal fun ModulationGraph(
    morph: Float,
    movement: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .border(1.dp, OrbitalPalette.PanelEdge, RoundedCornerShape(16.dp))
            .background(
                graphPanelBrush,
                RoundedCornerShape(16.dp),
            ),
    ) {
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(8.dp)) {
            val w = size.width
            val h = size.height
            for (i in 0..10) {
                val x = w * i / 10f
                drawLine(OrbitalPalette.Blue.copy(alpha = .12f), Offset(x, 0f), Offset(x, h))
            }
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(OrbitalPalette.Blue.copy(alpha = .11f), Offset(0f, y), Offset(w, y))
            }
            val colors = listOf(OrbitalPalette.Violet, OrbitalPalette.Cyan, OrbitalPalette.Gold)
            for (layer in 0..2) {
                val path = Path()
                for (i in 0..128) {
                    val x = w * i / 128f
                    val phase = i / 128.0 * PI * (3.3 + morph.toDouble() * 2.0)
                    val y = h * (.5f + sin(phase + layer * 1.42 + movement.toDouble() * 2.5).toFloat() * (.20f + layer * .045f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val tint = colors[layer]
                drawPath(path, tint.copy(alpha = .20f), style = Stroke(width = 8.dp.toPx()))
                drawPath(path, tint.copy(alpha = .90f), style = Stroke(width = (1.4 + layer * .3).dp.toPx()))
                val dx = w * (.18f + layer * .28f)
                val phase = (.18 + layer * .28) * PI * (3.3 + morph.toDouble() * 2.0)
                val dy = h * (.5f + sin(phase + layer * 1.42 + movement.toDouble() * 2.5).toFloat() * (.20f + layer * .045f))
                drawCircle(tint.copy(alpha = .19f), 13.dp.toPx(), Offset(dx, dy))
                drawCircle(tint, 5.dp.toPx(), Offset(dx, dy), style = Stroke(1.5.dp.toPx()))
            }
        }
        Text(
            "MORPH  /  MOVEMENT  /  HARMONIC MOTION",
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            color = OrbitalPalette.Muted,
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
        )
    }
}

@Composable
internal fun NeonKnob(
    label: String,
    value: Float,
    valueLabel: String,
    tint: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 58.dp,
) {
    val change by rememberUpdatedState(onValueChange)
    val v = value.coerceIn(0f, 1f)
    val currentValue by rememberUpdatedState(v)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(
            Modifier
                .size(diameter)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f, 0)
                    setProgress { next ->
                        change(next.coerceIn(0f, 1f))
                        true
                    }
                }
                .pointerInput(label) {
                    var dragValue = currentValue
                    detectDragGestures(
                        onDragStart = { dragValue = currentValue },
                        onDrag = { pointer, drag ->
                            pointer.consume()
                            dragValue = (dragValue - drag.y / 170f).coerceIn(0f, 1f)
                            change(dragValue)
                        },
                    )
                },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension * .365f
            // Satin-black metal body, backlit edge and a legible value track.
            drawCircle(
                Brush.radialGradient(listOf(tint.copy(alpha = .19f), Color.Transparent), center = c, radius = r * 1.56f),
                r * 1.56f, c,
            )
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF173457), Color(0xFF09162A), Color(0xFF040913)),
                    center = Offset(c.x - r * .28f, c.y - r * .30f),
                    radius = r * 1.38f,
                ),
                r, c,
            )
            drawCircle(tint.copy(alpha = .24f), r * 1.02f, c, style = Stroke(.65.dp.toPx()))
            drawCircle(Color.White.copy(alpha = .10f), r * .88f, c, style = Stroke(.65.dp.toPx()))
            drawCircle(Color(0xFF040A16), r * .72f, c)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF163457), Color(0xFF0B1A2D), Color(0xFF050913)),
                    center = Offset(c.x - r * .34f, c.y - r * .37f),
                    radius = r,
                ),
                r * .67f, c,
            )
            for (tick in OrbitalVisualGeometry.knobTicks) {
                drawLine(
                    tint.copy(alpha = if (tick.major) .31f else .16f),
                    start = c + tick.direction * r * 1.13f,
                    end = c + tick.direction * r * (if (tick.major) 1.27f else 1.21f),
                    strokeWidth = (if (tick.major) .8f else .5f).dp.toPx(),
                )
            }
            val arcPosition = Offset(c.x - r * 1.08f, c.y - r * 1.08f)
            val arcSize = Size(r * 2.16f, r * 2.16f)
            drawArc(
                tint.copy(alpha = .15f),
                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = arcPosition, size = arcSize,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                tint.copy(alpha = .20f),
                startAngle = 135f, sweepAngle = 270f * v, useCenter = false,
                topLeft = arcPosition, size = arcSize,
                style = Stroke(6.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                tint,
                startAngle = 135f, sweepAngle = 270f * v, useCenter = false,
                topLeft = arcPosition, size = arcSize,
                style = Stroke(2.25.dp.toPx(), cap = StrokeCap.Round),
            )
            val angle = Math.toRadians((135 + 270 * v).toDouble())
            val marker = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(
                tint.copy(alpha = .22f),
                c + marker * r * .22f,
                c + marker * r * .79f,
                strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round,
            )
            drawLine(
                Color.White.copy(alpha = .96f),
                c + marker * r * .26f,
                c + marker * r * .80f,
                strokeWidth = 1.65.dp.toPx(), cap = StrokeCap.Round,
            )
            drawCircle(Color.White.copy(alpha = .35f), r * .12f, c)
            drawCircle(tint.copy(alpha = .67f), r * .065f, c)
        }
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = OrbitalPalette.Text, letterSpacing = .7.sp, textAlign = TextAlign.Center, maxLines = 1)
        Text(valueLabel, fontSize = 10.sp, color = OrbitalPalette.Muted, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
internal fun SignalTile(
    title: String,
    value: Float,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String = title,
) {
    Column(
        modifier
            .border(1.dp, OrbitalPalette.PanelEdge, RoundedCornerShape(12.dp))
            .background(
                signalPanelBrush,
                RoundedCornerShape(12.dp),
            )
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(title, fontSize = 10.sp, color = OrbitalPalette.Muted, letterSpacing = 1.sp)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .semantics { this.contentDescription = contentDescription },
        ) {
            val path = Path()
            val v = value.coerceIn(0f, 1f)
            for (i in 0..40) {
                val x = size.width * i / 40f
                val t = i / 40f
                val y = size.height * when (title) {
                    "ENV" -> when {
                        t < .20f -> .92f - t * 3.7f
                        t < .48f -> .18f + (t - .20f) * 1.20f
                        else -> .52f + (t - .48f) * .55f
                    }
                    "LFO" -> .5f + sin(t.toDouble() * PI * (3.0 + v.toDouble() * 5.0)).toFloat() * .30f
                    "MPE" -> .82f - .65f * (1f / (1f + kotlin.math.exp(-(t - .48f) * (5 + 9 * v))))
                    else -> if ((i / 9) % 2 == 0) .25f else .77f
                }
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, tint.copy(alpha = .20f), style = Stroke(width = 6.dp.toPx()))
            drawPath(path, tint, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
