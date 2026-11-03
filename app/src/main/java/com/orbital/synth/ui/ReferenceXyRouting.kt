package com.orbital.synth.ui

import com.orbital.synth.core.ParameterId

/**
 * The six honest XY destinations supported by the current single-host synth.
 *
 * These pairs edit existing 0..1 [ParameterId] values. They do not install
 * modulation cables, choose a rack LFO, or claim a nonexistent master PAN knob.
 * The UI owns screen geometry (including the original folded ribbon Y axis).
 */
enum class ReferenceXyMode(
    val title: String,
    val xParameterId: ParameterId,
    val yParameterId: ParameterId,
    val xLabel: String,
    val yLabel: String,
    val guidance: String,
    private val supportedEngines: IntRange,
) {
    PERFORMANCE(
        "PERFORMANCE", ParameterId.MORPH, ParameterId.MOVEMENT,
        "Morph", "Movement",
        "Both axes control real morph and core movement.",
        0..4,
    ),
    FILTER(
        "FILTER", ParameterId.CUTOFF, ParameterId.RESONANCE,
        "Filter cutoff", "Filter resonance",
        "Filter cutoff and resonance are bounded on the audio engine.",
        0..4,
    ),
    CORE_VIBRATO(
        "CORE VIBRATO", ParameterId.LFO_RATE, ParameterId.LFO_DEPTH,
        "Core vibrato rate", "Core vibrato depth",
        "Legacy global sine LFO, not the eight-LFO rack. " +
            "Audible modulation requires Movement above zero.",
        0..4,
    ),
    WAVE_SHAPE(
        "WAVE SHAPE", ParameterId.WAVE, ParameterId.MORPH,
        "Wave shape", "Wave morph",
        "Continuous wavetable shaping is available only in engine families zero to two.",
        0..2,
    ),
    FX_SPACE(
        "FX/SPACE", ParameterId.FX_AMOUNT, ParameterId.MOVEMENT,
        "FX amount", "Movement",
        "FX must be above zero for wet effects. Movement also affects core modulation.",
        0..4,
    ),
    FM_COLOR(
        "FM COLOR", ParameterId.MORPH, ParameterId.CHARACTER,
        "FM morph", "FM character",
        "FM color acts on the six-operator FM engine family three only.",
        3..3,
    );

    /** The exact engine family selected by OrbitalSynth's block-level quantizer. */
    fun enabledForEngine(normalizedEngine: Float): Boolean =
        ReferenceXyRouting.engineIndex(normalizedEngine) in supportedEngines

    /**
     * Never leave an invalid mode silently targeting an unrelated engine.
     * The caller should also publish the resolved selection to its UI state.
     */
    fun resolveFallback(normalizedEngine: Float): ReferenceXyMode =
        if (enabledForEngine(normalizedEngine)) this else PERFORMANCE

    /** The accessible description names the *actual* axis controls and caveats. */
    val talkBackLabel: String
        get() = "$title XY pad. Horizontal: $xLabel. Vertical: $yLabel. $guidance"

    /** Labels for the four TalkBack actions; hosts supply the current values. */
    fun actionLabel(axis: ReferenceXyAxis, direction: ReferenceXyDirection): String {
        val name = if (axis == ReferenceXyAxis.X) xLabel else yLabel
        return "${direction.spokenVerb} $name"
    }
}

enum class ReferenceXyAxis { X, Y }

enum class ReferenceXyDirection(val spokenVerb: String) {
    INCREASE("Increase"),
    DECREASE("Decrease"),
}

/** Source-independent normalized values. Neither component is a screen coordinate. */
data class ReferenceXyPoint(val x: Float, val y: Float)

/**
 * Platform-free routing helpers: no Compose state, DSP access, worker, or heap
 * activity on the audio thread. A bad pointer sample safely maps to the center.
 */
object ReferenceXyRouting {
    val defaultMode: ReferenceXyMode = ReferenceXyMode.PERFORMANCE

    fun normalizeValue(raw: Float): Float =
        if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0.5f

    fun normalizePoint(x: Float, y: Float): ReferenceXyPoint =
        ReferenceXyPoint(normalizeValue(x), normalizeValue(y))

    /**
     * Mirror OrbitalSynth's ENGINE formula: (normalized * 4 + .5).toInt()
     * clamped to the five architecture indices 0..4.
     */
    fun engineIndex(normalizedEngine: Float): Int =
        (normalizeValue(normalizedEngine) * 4f + 0.5f).toInt().coerceIn(0, 4)

    fun availableModes(normalizedEngine: Float): List<ReferenceXyMode> =
        ReferenceXyMode.entries.filter { it.enabledForEngine(normalizedEngine) }
}
