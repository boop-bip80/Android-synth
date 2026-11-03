package com.orbital.synth.ui

import kotlin.math.PI
import kotlin.math.sin

/**
 * Geometry for the reference's 12 equal-width chromatic touch strips. This
 * maps fingers to real MIDI notes; it never invents audio samples or MPE data.
 */
internal object ReferenceKeybedUiModel {
    const val KEY_COUNT = 12

    private val names = arrayOf(
        "C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B",
    )

    fun normalized(raw: Float): Float =
        if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0f

    /** Outside or nonfinite positions deliberately acquire no note. */
    fun keyAt(x: Float, y: Float, width: Float, height: Float): Int? {
        if (!x.isFinite() || !y.isFinite() ||
            !width.isFinite() || !height.isFinite() ||
            width <= 0f || height <= 0f ||
            x !in 0f..<width || y !in 0f..<height) return null
        return (x * KEY_COUNT / width).toInt().coerceIn(0, KEY_COUNT - 1)
    }

    fun noteAt(octave: Int, key: Int): Int? {
        if (octave !in 0..8 || key !in 0 until KEY_COUNT) return null
        val note = (octave + 1) * 12 + key
        return note.takeIf { it in 0..127 }
    }

    fun noteLabel(note: Int): String {
        if (note !in 0..127) return "Note"
        return names[note % 12] + (note / 12 - 1).toString()
    }

    /**
     * User-selectable strike intensity is the initial finger's Y coordinate.
     * Never claims that a glass capacitive panel measures calibrated force.
     */
    fun verticalStrikeVelocity(y: Float, height: Float): Float {
        if (!y.isFinite() || !height.isFinite() || height <= 0f) return .72f
        return (.45f + .55f * (1f - normalized(y / height))).coerceIn(.45f, 1f)
    }

    /**
     * A decorative parameter-driven light river crossing the twelve pads.
     * It is not PCM samples, a spectral analyzer, or a MIDI/MPE playback trace.
     */
    fun lightRiverAt(
        x: Float,
        layer: Int,
        morph: Float,
        movement: Float,
    ): Float {
        val u = normalized(x)
        val m = normalized(morph)
        val v = normalized(movement)
        val phase = (u * (1.2f + .6f * m) + v * .34f +
            layer * .19f) * (2f * PI.toFloat())
        val broad = sin(phase) * (.095f + .09f * m)
        val fine = sin(phase * 2.1f + layer * .9f) * .022f
        return (.59f + broad + fine + (layer - 1) * .058f).coerceIn(.12f, .91f)
    }
}
