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

    /**
     * Six global buses plus gain/pitch for each of the eight oscillator
     * layers are implemented in OrbitalSynth. Only the destination is DSP
     * capable: the route still needs nonzero depth and an actual driving
     * source before any audio changes.
     */
    private val oscillatorGainDestinations = listOf(
        ModDestination.OSC1_GAIN, ModDestination.OSC2_GAIN,
        ModDestination.OSC3_GAIN, ModDestination.OSC4_GAIN,
        ModDestination.OSC5_GAIN, ModDestination.OSC6_GAIN,
        ModDestination.OSC7_GAIN, ModDestination.OSC8_GAIN,
    )
    private val oscillatorPitchDestinations = listOf(
        ModDestination.OSC1_PITCH, ModDestination.OSC2_PITCH,
        ModDestination.OSC3_PITCH, ModDestination.OSC4_PITCH,
        ModDestination.OSC5_PITCH, ModDestination.OSC6_PITCH,
        ModDestination.OSC7_PITCH, ModDestination.OSC8_PITCH,
    )
    val liveDestinations: List<ModDestination> = listOf(
        ModDestination.PITCH, ModDestination.MORPH,
        ModDestination.CUTOFF, ModDestination.RESONANCE,
        ModDestination.AMP, ModDestination.PAN,
    ) + oscillatorGainDestinations + oscillatorPitchDestinations

    private val liveDestinationSet = liveDestinations.toSet()

    /** Present usable audio routes before editable, future-reserved metadata. */
    val destinationChoices: List<ModDestination> =
        liveDestinations + ModDestination.entries.filterNot { it in liveDestinations }

    fun audibleTarget(destination: ModDestination): Boolean =
        destination in liveDestinationSet

    fun destinationLabel(destination: ModDestination): String =
        (if (audibleTarget(destination)) "LIVE  •  " else "RESERVED  •  ") +
            enumLabel(destination.name)

    fun destinationStatus(destination: ModDestination): String = when (destination) {
        ModDestination.PITCH -> "DSP-CAPABLE TARGET • When driven, applies live semitone modulation."
        ModDestination.MORPH -> "DSP-CAPABLE TARGET • When driven, controls oscillator waveform morph."
        ModDestination.CUTOFF -> "DSP-CAPABLE TARGET • When driven, sweeps the per-voice filter cutoff."
        ModDestination.RESONANCE -> "DSP-CAPABLE TARGET • When driven, changes filter resonance."
        ModDestination.AMP -> "DSP-CAPABLE TARGET • When driven, changes gain before master effects."
        ModDestination.PAN -> "DSP-CAPABLE TARGET • When driven, changes stereo balance before master effects."
        ModDestination.OSC1_GAIN, ModDestination.OSC2_GAIN,
        ModDestination.OSC3_GAIN, ModDestination.OSC4_GAIN,
        ModDestination.OSC5_GAIN, ModDestination.OSC6_GAIN,
        ModDestination.OSC7_GAIN, ModDestination.OSC8_GAIN ->
            "DSP-CAPABLE TARGET • When driven, changes the addressed oscillator layer gain (0–2×, neutral 1×)."
        ModDestination.OSC1_PITCH, ModDestination.OSC2_PITCH,
        ModDestination.OSC3_PITCH, ModDestination.OSC4_PITCH,
        ModDestination.OSC5_PITCH, ModDestination.OSC6_PITCH,
        ModDestination.OSC7_PITCH, ModDestination.OSC8_PITCH ->
            "DSP-CAPABLE TARGET • When driven, bends only the addressed oscillator layer pitch (±6 semitones)."
        else -> "SAVED ROUTE • Reserved for a future audio engine connection."
    }

    /** Keep the on-screen synopsis and JVM assertions tied to one source of truth. */
    val liveTargetsLegend: String =
        "LIVE DSP TARGETS  /  6 GLOBAL: PITCH · MORPH · CUTOFF · RESONANCE · AMP · PAN. " +
            "16 LAYER TARGETS: OSC 1–8 GAIN and OSC 1–8 PITCH. " +
            "Actual modulation requires a driving source and nonzero depth; " +
            "DRIVE · FX AMOUNT · FILTER DRIVE · FOLD · LFO RATE remain reserved."

    fun slotLabel(index: Int): String = (index.coerceIn(0, 63) + 1).toString().padStart(2, '0')

    fun enumLabel(name: String): String {
        val expanded = name.replace('_', ' ')
        return expanded.replace("LFO", "LFO ").replace("OSC", "OSC ")
            .replace("FM", "FM ").trim()
    }
}
