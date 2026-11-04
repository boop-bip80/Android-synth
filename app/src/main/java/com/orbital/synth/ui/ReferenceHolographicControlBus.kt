package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * A light, deterministic visual wiring diagram BEHIND the five already
 * functional SOUND knobs. Nodes track saved/hosted DSP macro positions;
 * intensity tracks accepted AudioTrack RMS. This is not a spectrum meter,
 * phase analyzer or a second synthetic audio clock.
 */
internal object ReferenceControlBusModel {
    data class Node(val x: Float, val y: Float, val value: Float)

    fun nodes(macros: List<Float>): List<Node> = List(5) { index ->
        val value = macros.getOrNull(index)?.takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f) ?: .5f
        Node(
            x = (index + .5f) / 5f,
            // Route the light through the *knob discs*, behind the controls.
            // At .66 this line crossed WAVE/CUTOFF/etc labels on phones.
            y = .37f + (.5f - value) * .10f,
            value = value,
        )
    }

    fun pcmLight(acceptedRms: Float): Float {
        val rms = acceptedRms.takeIf { it.isFinite() }?.coerceIn(0f, .32f) ?: 0f
        return (rms / .32f).coerceIn(0f, 1f)
    }
}

@Composable
internal fun ReferenceHolographicControlBus(
    macros: List<Float>,
    acceptedRms: Float,
    modifier: Modifier = Modifier,
) {
    // One static Canvas, no remembered animation, no software blur, no RenderEffect.
    Canvas(modifier.testTag("referenceControlSignalBus")) {
        val points = ReferenceControlBusModel.nodes(macros)
        val rms = ReferenceControlBusModel.pcmLight(acceptedRms)
        val colours = arrayOf(
            OrbitalPalette.Violet, OrbitalPalette.Cyan, OrbitalPalette.Gold,
            OrbitalPalette.Violet, OrbitalPalette.Cyan,
        )
        for (index in 0 until 4) {
            val left = points[index]
            val right = points[index + 1]
            val a = Offset(left.x * size.width, left.y * size.height)
            val b = Offset(right.x * size.width, right.y * size.height)
            val colour = colours[index].copy(alpha = .11f + rms * .08f)
            drawLine(colour, a, b, strokeWidth = 6.dp.toPx())
            drawLine(
                colours[index].copy(alpha = .35f + rms * .16f),
                a, b, strokeWidth = .8.dp.toPx(),
            )
        }
        points.forEachIndexed { index, point ->
            val pos = Offset(point.x * size.width, point.y * size.height)
            val tint = colours[index]
            drawCircle(tint.copy(alpha = .14f + rms * .12f),
                radius = (3.5f + point.value * 2f).dp.toPx(), center = pos)
            drawCircle(tint.copy(alpha = .58f + rms * .18f),
                radius = 1.05.dp.toPx(), center = pos)
            // Five groups of three tiny detents read like circuitry under glass.
            for (detent in 0..2) {
                val stem = .018f + detent * .021f
                drawLine(
                    Color.White.copy(alpha = .08f + .06f * point.value),
                    Offset(pos.x, pos.y + stem * size.height),
                    Offset(pos.x, pos.y + (stem + .009f) * size.height),
                    strokeWidth = .6.dp.toPx(),
                )
            }
        }
    }
}
