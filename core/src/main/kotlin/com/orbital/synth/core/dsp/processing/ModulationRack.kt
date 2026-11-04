package com.orbital.synth.core.dsp.processing

import kotlin.math.exp

/**
 * A reusable, fixed-capacity collection of eight tempo-syncable LFO lanes and
 * up to 256 freely assignable modulation routes. No arrays or route objects
 * are allocated by [next]; use one instance per synth or independent voice.
 *
 * Configure this rack on the render thread. UI/MIDI events must first cross
 * the owning synth's thread-safe event/parameter boundary.
 *
 * Destination values use the engine's normalized 0..1 range; for an oscillator
 * pitch destination, 0.5 is the neutral center. A host must explicitly apply
 * the resulting parameters to make routes audible.
 */
class ModulationRack(
    val sampleRate: Int = 48_000,
    val lfoCount: Int = 8,
    routeCount: Int = 64,
    randomSeed: Int = 0x18a2f5c,
) {
    init {
        require(sampleRate in 8_000..384_000)
        require(lfoCount in 1..8)
    }

    val matrix = ModulationMatrix(routeCount)
    private val lfoIds = intArrayOf(
        ModSource.LFO1.ordinal, ModSource.LFO2.ordinal, ModSource.LFO3.ordinal,
        ModSource.LFO4.ordinal, ModSource.LFO5.ordinal, ModSource.LFO6.ordinal,
        ModSource.LFO7.ordinal, ModSource.LFO8.ordinal,
    )
    private val lfos = Array(lfoCount) { slot ->
        TempoLfo(sampleRate, (randomSeed + 0x9e37 * (slot + 1)).let { if (it == 0) 1 else it })
    }
    private val lfoEnabled = BooleanArray(lfoCount) { it == 0 }
    private val stepSequencer = TempoStepSequencer(sampleRate, randomSeed)
    private var externalStepValue = 0f

    val stepPlayhead: Int get() = stepSequencer.playheadIndex
    val stepEnabled: Boolean get() = stepSequencer.enabled
    fun setStepPattern(config: ModulationStepConfig) = stepSequencer.configure(config)
    fun advanceStepOnly(samples: Int) = stepSequencer.advance(samples)
    private val retriggerOnGate = BooleanArray(lfoCount)
    private val beatsPerCycle = FloatArray(lfoCount)
    private var tempoBpm = 120f

    private val sourceValues = FloatArray(ModSource.entries.size)
    private val targetValues = FloatArray(ModDestination.entries.size)
    private val smoothedValues = FloatArray(ModDestination.entries.size)
    private val slewAlpha = FloatArray(ModDestination.entries.size) { 1f }
    private val smoothingReady = BooleanArray(ModDestination.entries.size)

    /** Disabled lanes pause at their current phase and output zero. */
    fun setLfoEnabled(slot: Int, enabled: Boolean) {
        checkSlot(slot)
        lfoEnabled[slot] = enabled
        if (!enabled) sourceValues[lfoIds[slot]] = 0f
    }

    fun setLfoShape(slot: Int, shape: LfoShape) {
        checkSlot(slot)
        lfos[slot].setShape(shape)
    }

    /** Free-running Hz; disables beat synchronization for this lane. */
    fun setLfoRateHz(slot: Int, hertz: Float) {
        checkSlot(slot)
        beatsPerCycle[slot] = 0f
        lfos[slot].setRateHz(hertz)
    }

    /** Beats are quarter-notes, e.g. 4 beats/cycle = one 4/4 bar. */
    fun syncLfoToTempo(slot: Int, bpm: Float, beats: Float) {
        checkSlot(slot)
        beatsPerCycle[slot] = safeBeats(beats)
        setTempoBpm(bpm)
    }

    /** Recalculate all tempo-synced lanes while preserving oscillator phases. */
    fun setTempoBpm(bpm: Float) {
        tempoBpm = safeTempo(bpm)
        stepSequencer.setTempoBpm(tempoBpm)
        for (i in lfos.indices) {
            if (beatsPerCycle[i] > 0f) {
                lfos[i].syncToTempo(tempoBpm, beatsPerCycle[i])
            }
        }
    }

    /** Set a lane's initial phase without changing its wave or current rate. */
    fun setLfoPhase(slot: Int, normalizedPhase: Float) {
        checkSlot(slot)
        lfos[slot].reset(normalizedPhase)
    }

    fun setLfoRetrigger(slot: Int, enabled: Boolean) {
        checkSlot(slot)
        retriggerOnGate[slot] = enabled
    }

    /** Invoke on a note gate to restart only lanes configured for retrigger. */
    fun retrigger() {
        for (i in lfos.indices) {
            if (retriggerOnGate[i]) lfos[i].reset()
        }
        stepSequencer.onNoteOn()
    }

    /**
     * External expression values, such as TOUCH_X/Y and TOUCH_PRESSURE. These
     * do not automatically appear from the phone hardware; the host supplies
     * them. Values are bipolar and non-finite input is ignored. LFO slots are
     * written by [next] and should not be set through this API.
     */
    fun setSource(source: ModSource, value: Float) {
        if (!value.isFinite()) return
        val safe = value.coerceIn(-1f, 1f)
        if (source == ModSource.STEP) externalStepValue = safe
        sourceValues[source.ordinal] = safe
    }

    /** 0 ms bypasses smoothing; nonzero times are first-order sample slews. */
    fun setDestinationSlewMs(destination: ModDestination, milliseconds: Float) {
        val ms = (if (milliseconds.isFinite()) milliseconds else 0f).coerceIn(0f, 5_000f)
        slewAlpha[destination.ordinal] = if (ms == 0f) 1f else {
            (1.0 - exp(-1000.0 / (sampleRate * ms))).toFloat().coerceIn(0f, 1f)
        }
    }

    /**
     * Advance each enabled LFO exactly one sample and apply every route.
     * Call this once per rendered sample to preserve tempo and slew timing.
     * [base] and [out] may be the same array. No temporary objects are made.
     */
    fun next(base: FloatArray, out: FloatArray) {
        for (i in lfos.indices) {
            sourceValues[lfoIds[i]] = if (lfoEnabled[i]) lfos[i].next() else 0f
        }
        // A programmed STEP sequencer overrides the older manual STEP source
        // without discarding its external value when the lane is disabled.
        sourceValues[ModSource.STEP.ordinal] =
            if (stepSequencer.enabled) stepSequencer.next() else externalStepValue
        matrix.apply(base, sourceValues, targetValues)
        for (i in targetValues.indices) {
            val target = targetValues[i]
            val nextValue = if (!smoothingReady[i] || slewAlpha[i] >= 1f) {
                smoothingReady[i] = true
                target
            } else {
                smoothedValues[i] + (target - smoothedValues[i]) * slewAlpha[i]
            }
            smoothedValues[i] = nextValue
            out[i] = nextValue.coerceIn(0f, 1f)
        }
    }

    /** Preserve free-running LFO phase through an audio engine's silent fast path. */
    fun advance(samples: Int) {
        require(samples >= 0)
        if (samples == 0) return
        for (i in lfos.indices) {
            if (lfoEnabled[i]) lfos[i].advance(samples)
        }
        stepSequencer.advance(samples)
    }

    /**
     * Prime destination slews to their dry normalized baseline without
     * advancing LFO/STEP phase or evaluating any routes. Called only at the
     * worker's block boundary while every published DSP cable is dormant.
     * A zero-depth -> live depth gesture then begins from dry, without a
     * one-sample full-depth jump on first activation.
     */
    fun primeSmoothingAtBase(base: FloatArray) {
        require(base.size >= ModDestination.entries.size)
        for (i in smoothedValues.indices) {
            val value = base[i]
            smoothedValues[i] = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            smoothingReady[i] = true
        }
    }

    /** Useful when loading a new preset: avoids old glide targets leaking in. */
    fun resetSmoothing() {
        smoothingReady.fill(false)
    }

    private fun checkSlot(slot: Int) {
        require(slot in 0 until lfoCount) { "LFO slot out of range: $slot" }
    }

    private fun safeTempo(bpm: Float): Float =
        (if (bpm.isFinite()) bpm else 120f).coerceIn(20f, 400f)

    private fun safeBeats(beats: Float): Float =
        (if (beats.isFinite()) beats else 1f).coerceIn(0.0625f, 64f)
}
