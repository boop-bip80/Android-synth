package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.LayerAmpEnvelope
import kotlin.math.roundToInt

/**
 * A programmable layer VCA/ADSR panel, not a fake live audio meter.
 * Each individual layer envelope is subordinate to the existing master Voice
 * ADSR: a short master release can truncate a longer layer release.
 */
@Composable
fun LayerAmpEnvelopePanel(
    envelope: LayerAmpEnvelope,
    onEnvelopeChange: (LayerAmpEnvelope) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val cyan = Color(0xFF39E6FF)
    val violet = Color(0xFFB694FF)
    val gold = Color(0xFFFFC972)
    val glass = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, if (envelope.enabled) cyan.copy(alpha = .55f)
                else cyan.copy(alpha = .19f), glass)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF06152C), Color(0xFF0C0A22), Color(0xFF030915)),
                ), glass,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("oscillatorAmpEnvelopePanel"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "LAYER VCA  /  ADSR",
                modifier = Modifier.weight(1f),
                color = cyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .85.sp,
            )
            Text(
                if (envelope.enabled) "ARMED" else "BYPASS",
                modifier = Modifier
                    .border(
                        1.dp, if (envelope.enabled) cyan else violet.copy(alpha = .35f),
                        RoundedCornerShape(10.dp),
                    )
                    .background(
                        if (envelope.enabled) cyan.copy(alpha = .19f) else Color(0xFF090F22),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable(role = Role.Switch) {
                        onEnvelopeChange(envelope.copy(enabled = !envelope.enabled))
                    }
                    .semantics {
                        contentDescription = "Independent oscillator amplitude envelope"
                        stateDescription = if (envelope.enabled) "On" else "Bypassed"
                    }
                    .testTag("oscillatorAmpEnvelopeToggle")
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                color = if (envelope.enabled) cyan else violet,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Canvas(
            Modifier.fillMaxWidth()
                .height(if (compact) 76.dp else 112.dp)
                .background(Color(0xFF020813), RoundedCornerShape(10.dp))
                .semantics {
                    contentDescription = "Illustrative programmed ADSR contour, not live audio telemetry"
                }
                .testTag("oscillatorAmpEnvelopeContour"),
        ) {
            val w = size.width
            val h = size.height
            // Stage widths intentionally normalized for visual legibility.
            // Physical time maps in LayerAmpEnvelopeBank to 1ms–4s,
            // 5ms–3s and 5ms–4s, independent of buffer length.
            val aFrac = .09f + envelope.attack * .23f
            val dFrac = .09f + envelope.decay * .20f
            val holdFrac = .13f
            val rFrac = .16f + envelope.release * .19f
            val scale = .89f / (aFrac + dFrac + holdFrac + rFrac)
            val x0 = w * .055f
            val x1 = x0 + w * aFrac * scale
            val x2 = x1 + w * dFrac * scale
            val x3 = x2 + w * holdFrac * scale
            val x4 = x3 + w * rFrac * scale
            val bottom = h * .86f
            val peak = h * .12f
            val sustainY = bottom - (bottom - peak) * envelope.sustain
            for (line in 0..4) {
                val y = h * (.16f + line * .17f)
                drawLine(cyan.copy(alpha = .08f),
                    Offset(0f, y), Offset(w, y), 1f)
            }
            // UI redraw allocations are distinct from zero-allocation DSP.
            val curve = Path().apply {
                moveTo(x0, bottom)
                lineTo(x1, peak)
                lineTo(x2, sustainY)
                lineTo(x3, sustainY)
                lineTo(x4, bottom)
            }
            val tone = if (envelope.enabled) cyan else violet.copy(alpha = .55f)
            drawPath(curve, tone.copy(alpha = .08f), style = Stroke(width = 19f,
                cap = StrokeCap.Round))
            drawPath(curve, tone.copy(alpha = .21f), style = Stroke(width = 9f,
                cap = StrokeCap.Round))
            drawPath(curve, tone, style = Stroke(width = 2.5f,
                cap = StrokeCap.Round))
            drawLine(violet.copy(alpha = .45f),
                Offset(x2, sustainY), Offset(x3, sustainY), strokeWidth = 1.4f)
            drawCircle(gold, radius = 3.8f, center = Offset(x1, peak))
            drawCircle(violet, radius = 3.3f, center = Offset(x2, sustainY))
        }
        Text(
            "PROGRAMMED SHAPE  /  EACH LAYER IS GATED INSIDE THE MASTER VCA",
            color = Color(0xFF8C9FC1), fontSize = 8.sp,
        )
        EnvelopeParameterSlider(
            label = "ATTACK", normalized = envelope.attack,
            display = stageMs(envelope.attack, .001f, 4f),
            tint = cyan, onValueChange = { onEnvelopeChange(envelope.copy(attack = it)) },
            tag = "oscillatorAmpAttack",
        )
        EnvelopeParameterSlider(
            label = "DECAY", normalized = envelope.decay,
            display = stageMs(envelope.decay, .005f, 3f),
            tint = violet, onValueChange = { onEnvelopeChange(envelope.copy(decay = it)) },
            tag = "oscillatorAmpDecay",
        )
        EnvelopeParameterSlider(
            label = "SUSTAIN", normalized = envelope.sustain,
            display = (envelope.sustain * 100f).roundToInt().toString() + "%",
            tint = cyan, onValueChange = { onEnvelopeChange(envelope.copy(sustain = it)) },
            tag = "oscillatorAmpSustain",
        )
        EnvelopeParameterSlider(
            label = "RELEASE", normalized = envelope.release,
            display = stageMs(envelope.release, .005f, 4f),
            tint = gold, onValueChange = { onEnvelopeChange(envelope.copy(release = it)) },
            tag = "oscillatorAmpRelease",
        )
    }
}

private fun stageMs(value: Float, minimum: Float, maximum: Float): String {
    val milliseconds = ((minimum + value * value * maximum) * 1000f).roundToInt()
    return if (milliseconds >= 1_000) {
        ((milliseconds / 10f).roundToInt() / 100f).toString() + " S"
    } else milliseconds.toString() + " MS"
}

@Composable
private fun EnvelopeParameterSlider(
    label: String,
    normalized: Float,
    display: String,
    tint: Color,
    onValueChange: (Float) -> Unit,
    tag: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = Color(0xFFACBCD5),
            fontSize = 9.sp,
            letterSpacing = .7.sp,
        )
        Text(
            display, color = tint, fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Slider(
        value = normalized.coerceIn(0f, 1f),
        valueRange = 0f..1f,
        onValueChange = onValueChange,
        colors = SliderDefaults.colors(
            thumbColor = tint, activeTrackColor = tint,
            inactiveTrackColor = Color(0xFF23334C),
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { contentDescription = label + " " + display }
            .testTag(tag),
    )
}
