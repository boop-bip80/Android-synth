package com.orbital.synth.ui

import com.orbital.synth.core.dsp.processing.ModDestination
import kotlin.math.ln
import kotlin.math.pow

/**
 * Pure, JVM-testable UI conversions for the 8-LFO / 64-route modulation editor.
 * No UI arithmetic or graph labels influence the audio worker directly.
 */
internal object ModulationConstellationModel {
    val synchronizedBeats = listOf(
        .0625f, .125f, .25f, .5f, 1f, 2f, 4f, 8f, 16f, 32f, 64f,
    )

    /** Musical frequency controls deserve logarithmic, not linear, touch precision. */
    fun hzToSlider(rateHz: Float): Float {
        val safe = if (rateHz.isFinite()) rateHz.coerceIn(.01f, 40f) else 1f
        return (ln(safe.toDouble() / .01) / ln(4000.0)).toFloat().coerceIn(0f, 1f)
    }

    fun sliderToHz(value: Float): Float {
        val safe = if (value.isFinite()) value.coerceIn(0f, 1f) else .5f
        return (.01 * 4000.0.pow(safe.toDouble())).toFloat().coerceIn(.01f, 40f)
    }

    fun safeTempo(bpm: Float): Float =
        (if (bpm.isFinite()) bpm else 120f).coerceIn(20f, 400f)

    fun adjacentBeats(current: Float, step: Int): Float {
        val safe = if (current.isFinite()) current.coerceIn(.0625f, 64f) else 1f
        val nearest = synchronizedBeats.indices.minByOrNull {
            kotlin.math.abs(synchronizedBeats[it] - safe)
        } ?: 4
        return synchronizedBeats[(nearest + step).coerceIn(0, synchronizedBeats.lastIndex)]
    }

    fun audibleTarget(destination: ModDestination): Boolean =
        destination == ModDestination.MORPH || destination == ModDestination.PITCH

    fun slotLabel(index: Int): String = (index.coerceIn(0, 63) + 1).toString().padStart(2, '0')

    fun enumLabel(name: String): String {
        val expanded = name.replace('_', ' ')
        return expanded.replace("LFO", "LFO ").replace("OSC", "OSC ")
            .replace("FM", "FM ").trim()
    }
}
