package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationRack
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicReference

/** Immutable UI/editor snapshot: the audio thread never mutates this object. */
data class ModulationRouteConfig(
    val enabled: Boolean = false,
    val source: ModSource = ModSource.LFO1,
    val destination: ModDestination = ModDestination.MORPH,
    val depth: Float = 0f,
    val curve: ModCurve = ModCurve.LINEAR,
)

/** A nonpositive beatsPerCycle means free-running Hz. */
data class ModulationLfoConfig(
    val enabled: Boolean = false,
    val shape: LfoShape = LfoShape.SINE,
    val rateHz: Float = 1f,
    val beatsPerCycle: Float = 0f,
    val retriggerOnNote: Boolean = false,
)

/**
 * Audio-thread-owned running modulation state with bounded, lock-free editor
 * snapshots. There is no unbounded command queue: each of 64 routes and eight
 * LFO configurations has exactly one latest immutable atomic value, and touch
 * expression lives in a fixed primitive atomic array.
 *
 * The public configuration methods may be called from UI/MIDI threads. Only
 * [syncBlock], [tick], [onNoteOn], and [advanceSilence] run on the audio worker.
 * No allocation, lock, or Android API occurs inside [tick].
 */
internal class LiveModulationHost(sampleRate: Int) {
    companion object {
        const val ROUTE_COUNT = 64
        const val LFO_COUNT = 8
    }

    private val oscillatorModulation = OscillatorLayerModulation(sampleRate)
    /** Worker-only frames: callers must not publish or mutate these primitive arrays. */
    val oscillatorGainMultipliers: FloatArray get() = oscillatorModulation.gainMultipliers
    val oscillatorPitchMultipliers: DoubleArray get() = oscillatorModulation.pitchMultipliers
    val oscillatorModulationActive: Boolean get() = oscillatorModulation.active
    val oscillatorPitchMipMask: Int get() = oscillatorModulation.mipSafetyMask

    private val rack = ModulationRack(sampleRate, LFO_COUNT, ROUTE_COUNT).apply {
        setDestinationSlewMs(ModDestination.MORPH, 8f)
        setDestinationSlewMs(ModDestination.PITCH, 8f)
        // Touch/LFO sweeps should sound fluid; the voice filters additionally
        // interpolate their pole coefficients at audio rate.
        setDestinationSlewMs(ModDestination.CUTOFF, 12f)
        setDestinationSlewMs(ModDestination.RESONANCE, 12f)
        setDestinationSlewMs(ModDestination.AMP, 8f)
        setDestinationSlewMs(ModDestination.PAN, 8f)
    }

    private val publishedRoutes = AtomicReferenceArray<ModulationRouteConfig>(ROUTE_COUNT).apply {
        for (i in 0 until length()) set(i, ModulationRouteConfig())
    }
    private val appliedRoutes = arrayOfNulls<ModulationRouteConfig>(ROUTE_COUNT)
    private val routeRevision = AtomicInteger(0)
    private var appliedRouteRevision = -1

    private val publishedLfos = AtomicReferenceArray<ModulationLfoConfig>(LFO_COUNT).apply {
        for (i in 0 until length()) {
            set(i, ModulationLfoConfig(enabled = i == 0))
        }
    }
    private val appliedLfos = arrayOfNulls<ModulationLfoConfig>(LFO_COUNT)
    private val lfoRevision = AtomicInteger(0)
    private var appliedLfoRevision = -1

    private val expressionBits = AtomicIntegerArray(ModSource.entries.size)
    private val publishedSteps = AtomicReference(ModulationStepConfig())
    private var appliedStepConfig: ModulationStepConfig? = null
    @Volatile private var visibleStepPlayhead = 0
    private val bpmBits = AtomicInteger(java.lang.Float.floatToRawIntBits(120f))
    private var appliedBpmBits = Int.MIN_VALUE
    private val baseValues = FloatArray(ModDestination.entries.size) { 0.5f }
    private val frameValues = FloatArray(ModDestination.entries.size) { 0.5f }

    /**
     * True only when at least one finite, nonzero cable reaches an implemented
     * audio DSP destination. Editor-only/reserved or zero-depth cables remain
     * published for patch recall, without activating per-sample matrix work.
     */
    var active: Boolean = false
        private set

    /**
     * A configured (even zero-depth/reserved) cable historically kept the
     * free-running LFO clock advancing. Preserve that clock cheaply when the
     * audible matrix is asleep so a later depth edit does not restart its phase.
     * Worker-owned; never published or read from a UI thread.
     */
    private var hasEnabledRouteClock = false
    var pitchRouteActive: Boolean = false
        private set
    var filterRouteActive: Boolean = false
        private set
    var ampRouteActive: Boolean = false
        private set
    var panRouteActive: Boolean = false
        private set
    var morph: Float = 0.5f
        private set
    var pitchSemitones: Float = 0f
        private set
    var cutoff: Float = 0.5f
        private set
    var resonance: Float = 0.5f
        private set
    var ampGain: Float = 1f
        private set
    var panOffset: Float = 0f
        private set

    fun setRoute(
        slot: Int, source: ModSource, destination: ModDestination,
        depth: Float, curve: ModCurve,
    ) {
        if (slot !in 0 until ROUTE_COUNT || !depth.isFinite()) return
        publishedRoutes.set(
            slot, ModulationRouteConfig(true, source, destination, depth.coerceIn(-4f, 4f), curve)
        )
        routeRevision.incrementAndGet()
    }

    /** Restore a complete immutable route, including disabled editor metadata. */
    fun setRouteConfig(slot: Int, config: ModulationRouteConfig) {
        if (slot !in 0 until ROUTE_COUNT || !config.depth.isFinite() ||
            config.depth !in -4f..4f) return
        publishedRoutes.set(slot, config)
        routeRevision.incrementAndGet()
    }

    fun clearRoute(slot: Int) {
        if (slot !in 0 until ROUTE_COUNT) return
        publishedRoutes.set(slot, ModulationRouteConfig())
        routeRevision.incrementAndGet()
    }

    fun route(slot: Int): ModulationRouteConfig {
        require(slot in 0 until ROUTE_COUNT)
        return publishedRoutes.get(slot)
    }

    fun setLfo(
        slot: Int, enabled: Boolean, shape: LfoShape,
        rateHz: Float, beatsPerCycle: Float, retriggerOnNote: Boolean,
    ) {
        if (slot !in 0 until LFO_COUNT || !rateHz.isFinite() || !beatsPerCycle.isFinite()) return
        publishedLfos.set(
            slot, ModulationLfoConfig(
                enabled, shape, rateHz.coerceIn(0.01f, 40f),
                if (beatsPerCycle > 0f) beatsPerCycle.coerceIn(0.0625f, 64f) else 0f,
                retriggerOnNote,
            )
        )
        lfoRevision.incrementAndGet()
    }

    fun lfo(slot: Int): ModulationLfoConfig {
        require(slot in 0 until LFO_COUNT)
        return publishedLfos.get(slot)
    }

    fun setTempoBpm(bpm: Float) {
        if (bpm.isFinite()) {
            bpmBits.set(java.lang.Float.floatToRawIntBits(bpm.coerceIn(20f, 400f)))
        }
    }

    fun tempoBpm(): Float = java.lang.Float.intBitsToFloat(bpmBits.get())

    /** Bounded atomic pattern publish; mutable caller lists never enter DSP. */
    fun setSteps(config: ModulationStepConfig) {
        publishedSteps.set(config.copy(values = config.values.toList()))
    }

    fun steps(): ModulationStepConfig = publishedSteps.get()
    fun stepPlayhead(): Int = visibleStepPlayhead

    /** Input is normalized bipolar -1..1 (including touch, velocity, macros). */
    fun setSource(source: ModSource, value: Float) {
        if (!value.isFinite()) return
        expressionBits.set(
            source.ordinal,
            java.lang.Float.floatToRawIntBits(value.coerceIn(-1f, 1f)),
        )
    }

    fun syncBlock(baseMorph: Float, baseCutoff: Float, baseResonance: Float): Boolean {
        val latestRouteRevision = routeRevision.get()
        if (latestRouteRevision != appliedRouteRevision) {
            var count = 0
            var pitchCount = 0
            var filterCount = 0
            var ampCount = 0
            var panCount = 0
            var oscillatorGainMask = 0
            var oscillatorPitchMask = 0
            var hasAnyEnabledCable = false
            for (i in 0 until ROUTE_COUNT) {
                val next = publishedRoutes.get(i)
                // Never let saved but currently unwired destinations, nor zero
                // depth, consume the 48 kHz matrix/filter/pitch fast paths.
                // Keep the exact saved editor config in publishedRoutes.
                val eligible = next.enabled && next.depth.isFinite() &&
                    next.depth != 0f && isWiredAudioDestination(next.destination)
                if (next !== appliedRoutes[i]) {
                    if (eligible) {
                        rack.matrix.setRoute(i, next.source, next.destination, next.depth, next.curve)
                    } else {
                        rack.matrix.clearRoute(i)
                    }
                    appliedRoutes[i] = next
                }
                if (next.enabled) hasAnyEnabledCable = true
                if (eligible) {
                    count++
                    when (next.destination) {
                        ModDestination.PITCH -> pitchCount++
                        ModDestination.CUTOFF, ModDestination.RESONANCE -> filterCount++
                        ModDestination.AMP -> ampCount++
                        ModDestination.PAN -> panCount++
                        else -> {
                            val ordinal = next.destination.ordinal
                            if (ordinal >= ModDestination.OSC1_GAIN.ordinal &&
                                ordinal <= ModDestination.OSC8_GAIN.ordinal) {
                                oscillatorGainMask = oscillatorGainMask or
                                    (1 shl (ordinal - ModDestination.OSC1_GAIN.ordinal))
                            } else if (ordinal >= ModDestination.OSC1_PITCH.ordinal &&
                                ordinal <= ModDestination.OSC8_PITCH.ordinal) {
                                oscillatorPitchMask = oscillatorPitchMask or
                                    (1 shl (ordinal - ModDestination.OSC1_PITCH.ordinal))
                            }
                        }
                    }
                }
            }
            active = count > 0
            hasEnabledRouteClock = hasAnyEnabledCable
            pitchRouteActive = pitchCount > 0
            filterRouteActive = filterCount > 0
            ampRouteActive = ampCount > 0
            panRouteActive = panCount > 0
            oscillatorModulation.configureMasks(oscillatorGainMask, oscillatorPitchMask)
            appliedRouteRevision = latestRouteRevision
            if (!active) rack.resetSmoothing()
        }

        val nextSteps = publishedSteps.get()
        if (nextSteps !== appliedStepConfig) {
            rack.setStepPattern(nextSteps)
            appliedStepConfig = nextSteps
            visibleStepPlayhead = rack.stepPlayhead
        }

        val latestTempo = bpmBits.get()
        if (latestTempo != appliedBpmBits) {
            rack.setTempoBpm(java.lang.Float.intBitsToFloat(latestTempo))
            appliedBpmBits = latestTempo
        }

        // A programmed step clock must also tick when it has no routed cables
        // yet, so the real playhead is available while users draw the pattern.
        // A zero-depth cable must not keep the costly matrix alive, but its
        // LFO still needs current phase/rate/retrigger before a depth edit.
        if (!active && !hasEnabledRouteClock) return false

        val latestLfoRevision = lfoRevision.get()
        if (latestLfoRevision != appliedLfoRevision) {
            val bpm = java.lang.Float.intBitsToFloat(appliedBpmBits)
            for (i in 0 until LFO_COUNT) {
                val next = publishedLfos.get(i)
                if (next !== appliedLfos[i]) {
                    rack.setLfoEnabled(i, next.enabled)
                    rack.setLfoShape(i, next.shape)
                    rack.setLfoRetrigger(i, next.retriggerOnNote)
                    if (next.beatsPerCycle > 0f) {
                        rack.syncLfoToTempo(i, bpm, next.beatsPerCycle)
                    } else {
                        rack.setLfoRateHz(i, next.rateHz)
                    }
                    appliedLfos[i] = next
                }
            }
            appliedLfoRevision = latestLfoRevision
        }

        if (!active) return false

        for (source in ModSource.entries) {
            rack.setSource(
                source, java.lang.Float.intBitsToFloat(expressionBits.get(source.ordinal))
            )
        }
        baseValues[ModDestination.MORPH.ordinal] = baseMorph.coerceIn(0f, 1f)
        baseValues[ModDestination.PITCH.ordinal] = 0.5f
        baseValues[ModDestination.CUTOFF.ordinal] = baseCutoff.coerceIn(0f, 1f)
        baseValues[ModDestination.RESONANCE.ordinal] = baseResonance.coerceIn(0f, 1f)
        // These buses are bipolar around 0.5, so unconnected routes are neutral.
        baseValues[ModDestination.AMP.ordinal] = 0.5f
        baseValues[ModDestination.PAN.ordinal] = 0.5f
        return true
    }

    /** Audio-thread only: advances clock exactly once and reads the live buses. */
    fun tick() {
        rack.next(baseValues, frameValues)
        morph = frameValues[ModDestination.MORPH.ordinal]
        // 0.5 is neutral, +/-6 semitones is the programmed full pitch range.
        pitchSemitones = (frameValues[ModDestination.PITCH.ordinal] - 0.5f) * 12f
        cutoff = frameValues[ModDestination.CUTOFF.ordinal]
        resonance = frameValues[ModDestination.RESONANCE.ordinal]
        // User-programmed AMP route is a 0..2 linear multiplier, neutral at 1.
        ampGain = frameValues[ModDestination.AMP.ordinal] * 2f
        panOffset = (frameValues[ModDestination.PAN.ordinal] - 0.5f) * 2f
        if (oscillatorModulation.active) oscillatorModulation.next(frameValues)
        if (rack.stepEnabled && visibleStepPlayhead != rack.stepPlayhead) {
            visibleStepPlayhead = rack.stepPlayhead
        }
    }

    fun onNoteOn() {
        if (active || hasEnabledRouteClock || rack.stepEnabled) {
            rack.retrigger()
            visibleStepPlayhead = rack.stepPlayhead
        }
    }

    /**
     * No effective DSP cable: preserve legacy free-running LFO/STEP clocks
     * without any trig, matrix, destination smoothing, or heap allocation.
     */
    fun tickStepOnly() {
        // When the last OSC cable disappears, its gain and pitch still return
        // to exact neutral over 8 ms while the voices continue playing.
        if (oscillatorModulation.active) oscillatorModulation.next(null)
        if (hasEnabledRouteClock) {
            rack.advance(1)
        } else if (rack.stepEnabled) {
            rack.advanceStepOnly(1)
        }
        if (rack.stepEnabled && visibleStepPlayhead != rack.stepPlayhead) {
            visibleStepPlayhead = rack.stepPlayhead
        }
    }

    /** Free-running synchronized modulation must keep time through silent blocks. */
    fun advanceSilence(frames: Int) {
        if (active || hasEnabledRouteClock) rack.advance(frames)
        else if (rack.stepEnabled) rack.advanceStepOnly(frames)
        if (rack.stepEnabled) visibleStepPlayhead = rack.stepPlayhead
        // Only called after the voice/FX-tail silence check in OrbitalSynth.
        oscillatorModulation.resetWhileSilent()
    }

    /**
     * Only these 22 normalized buses currently drive synthesis code. Other
     * stable ModDestination ordinals remain serializable for future engines.
     */
    private fun isWiredAudioDestination(destination: ModDestination): Boolean {
        return when (destination) {
            ModDestination.PITCH, ModDestination.MORPH, ModDestination.CUTOFF,
            ModDestination.RESONANCE, ModDestination.AMP, ModDestination.PAN -> true
            else -> {
                val ordinal = destination.ordinal
                (ordinal >= ModDestination.OSC1_GAIN.ordinal &&
                    ordinal <= ModDestination.OSC8_GAIN.ordinal) ||
                    (ordinal >= ModDestination.OSC1_PITCH.ordinal &&
                        ordinal <= ModDestination.OSC8_PITCH.ordinal)
            }
        }
    }
}
