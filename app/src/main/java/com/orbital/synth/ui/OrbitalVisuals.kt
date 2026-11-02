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
        val radius = min(size.width, size.height) * .405f
        val m = morph.coerceIn(0f, 1f)
        val motion = movement.coerceIn(0f, 1f)
        drawCircle(
            Brush.radialGradient(
                listOf(
                    OrbitalPalette.Violet.copy(alpha = .28f),
                    OrbitalPalette.Blue.copy(alpha = .16f),
                    OrbitalPalette.Void.copy(alpha = 0f),
                ),
                center = c,
                radius = radius * 1.52f,
            ),
            radius = radius * 1.52f,
            center = c,
        )
        drawCircle(
            Brush.radialGradient(
                listOf(
                    Color(0xFF133D73),
                    Color(0xFF0B2B5C),
                    OrbitalPalette.Violet.copy(alpha = .32f),
                    Color(0xFF061226),
                ),
                center = Offset(c.x - radius * .24f, c.y - radius * .28f),
                radius = radius * 1.36f,
            ),
            radius = radius,
            center = c,
        )
        for (ring in 0..4) {
            drawCircle(
                OrbitalPalette.Blue.copy(alpha = .10f + ring * .045f),
                radius = radius * (.70f + ring * .093f),
                center = c,
                style = Stroke(width = (1 + ring % 2).dp.toPx()),
            )
        }
        for (layer in 0..7) {
            val path = Path()
            for (i in 0..180) {
                val a = i * (PI * 2.0 / 180.0)
                val phase = motion.toDouble() * PI * 2 + layer * .43
                val twist = sin(a * (3.0 + m.toDouble() * 2.0) + phase) * .13 +
                    sin(a * 7.0 - phase * .65) * .055
                val rr = radius.toDouble() * (.55 + layer * .049 + twist)
                val x = c.x + cos(a).toFloat() * rr.toFloat()
                val y = c.y + sin(a).toFloat() * rr.toFloat() * (.88f + m * .12f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val tint = when (layer % 4) {
                0 -> OrbitalPalette.Cyan
                1 -> OrbitalPalette.Blue
                2 -> OrbitalPalette.Violet
                else -> OrbitalPalette.Gold
            }
            drawPath(path, tint.copy(alpha = .10f), style = Stroke(width = (6 + layer).dp.toPx()))
            drawPath(path, tint.copy(alpha = .74f), style = Stroke(width = (1.0 + layer * .15).dp.toPx()))
        }
        rotate(24f, c) {
            drawOval(
                color = OrbitalPalette.Gold.copy(alpha = .60f),
                topLeft = Offset(c.x - radius * 1.13f, c.y - radius * .35f),
                size = Size(radius * 2.26f, radius * .70f),
                style = Stroke(width = .9.dp.toPx()),
            )
        }
        rotate(-32f, c) {
            drawOval(
                color = OrbitalPalette.Cyan.copy(alpha = .25f),
                topLeft = Offset(c.x - radius * .96f, c.y - radius * .40f),
                size = Size(radius * 1.92f, radius * .80f),
                style = Stroke(width = .75.dp.toPx()),
            )
        }
        // Deterministic constellation; no random values or recomposition jitter.
        for (i in 0 until 52) {
            val a = i * 2.39996323
            val d = (.15 + (i * 47 % 89) / 100.0) * radius.toDouble()
            val p = Offset(c.x + (cos(a) * d).toFloat(), c.y + (sin(a) * d).toFloat())
            val gold = i % 6 == 0
            drawCircle(
                if (gold) OrbitalPalette.Gold.copy(alpha = .8f) else OrbitalPalette.Cyan.copy(alpha = .24f),
                radius = (if (gold) 1.5 else .7).dp.toPx(),
                center = p,
            )
        }
        listOf(32.0, 158.0, 270.0).forEachIndexed { index, deg ->
            val a = Math.toRadians(deg)
            val p = Offset(
                c.x + (cos(a) * radius.toDouble() * 1.10).toFloat(),
                c.y + (sin(a) * radius.toDouble() * .56).toFloat(),
            )
            val tint = if (index == 1) OrbitalPalette.Gold else OrbitalPalette.Cyan
            drawCircle(tint.copy(alpha = .16f), 12.dp.toPx(), p)
            drawCircle(tint, (3.5 + index).dp.toPx(), p)
        }
        // A luminous reticle follows the sound parameters; it is inset to remain visible at extremes.
        if (onExpressionChange != null) {
            val edge = 16.dp.toPx()
            val marker = Offset(
                edge + (size.width - edge * 2f).coerceAtLeast(0f) * m,
                edge + (size.height - edge * 2f).coerceAtLeast(0f) * (1f - motion),
            )
            drawCircle(OrbitalPalette.Cyan.copy(alpha = .13f), 21.dp.toPx(), marker)
            drawCircle(OrbitalPalette.Gold.copy(alpha = .28f), 13.dp.toPx(), marker)
            drawCircle(OrbitalPalette.Void.copy(alpha = .95f), 7.dp.toPx(), marker)
            drawCircle(OrbitalPalette.Gold, 5.dp.toPx(), marker, style = Stroke(1.5.dp.toPx()))
            drawCircle(Color.White, 1.5.dp.toPx(), marker)
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
                Brush.verticalGradient(listOf(Color(0xB8183154), Color(0xC4081427))),
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
            "POSITION   /   MORPH   /   SPECTRAL",
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
            val r = size.minDimension * .39f
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = .22f), Color.Transparent)), r * 1.44f, c)
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF172F4D), Color(0xFF060D1A))),
                r, c,
            )
            drawCircle(OrbitalPalette.PanelEdge.copy(alpha = .9f), r, c, style = Stroke(1.dp.toPx()))
            drawArc(
                tint.copy(alpha = .16f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(c.x - r * 1.14f, c.y - r * 1.14f),
                size = Size(r * 2.28f, r * 2.28f),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                tint,
                startAngle = 135f,
                sweepAngle = 270f * v,
                useCenter = false,
                topLeft = Offset(c.x - r * 1.14f, c.y - r * 1.14f),
                size = Size(r * 2.28f, r * 2.28f),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            )
            val angle = Math.toRadians((135 + 270 * v).toDouble())
            drawLine(
                Color.White,
                Offset(c.x + cos(angle).toFloat() * r * .45f, c.y + sin(angle).toFloat() * r * .45f),
                Offset(c.x + cos(angle).toFloat() * r * .80f, c.y + sin(angle).toFloat() * r * .80f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
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
                Brush.verticalGradient(listOf(Color(0xFF10223D), Color(0xFF091527))),
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
