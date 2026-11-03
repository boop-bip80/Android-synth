package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationRack
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray

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

    private val rack = ModulationRack(sampleRate, LFO_COUNT, ROUTE_COUNT).apply {
        setDestinationSlewMs(ModDestination.MORPH, 8f)
        setDestinationSlewMs(ModDestination.PITCH, 8f)
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
    private val bpmBits = AtomicInteger(java.lang.Float.floatToRawIntBits(120f))
    private var appliedBpmBits = Int.MIN_VALUE
    private val baseValues = FloatArray(ModDestination.entries.size) { 0.5f }
    private val frameValues = FloatArray(ModDestination.entries.size) { 0.5f }

    var active: Boolean = false
        private set
    var pitchRouteActive: Boolean = false
        private set
    var morph: Float = 0.5f
        private set
    var pitchSemitones: Float = 0f
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

    /** Input is normalized bipolar -1..1 (including touch, velocity, macros). */
    fun setSource(source: ModSource, value: Float) {
        if (!value.isFinite()) return
        expressionBits.set(
            source.ordinal,
            java.lang.Float.floatToRawIntBits(value.coerceIn(-1f, 1f)),
        )
    }

    fun syncBlock(baseMorph: Float): Boolean {
        val latestRouteRevision = routeRevision.get()
        if (latestRouteRevision != appliedRouteRevision) {
            var count = 0
            var pitchCount = 0
            for (i in 0 until ROUTE_COUNT) {
                val next = publishedRoutes.get(i)
                if (next !== appliedRoutes[i]) {
                    if (next.enabled) {
                        rack.matrix.setRoute(i, next.source, next.destination, next.depth, next.curve)
                    } else {
                        rack.matrix.clearRoute(i)
                    }
                    appliedRoutes[i] = next
                }
                if (next.enabled) {
                    count++
                    if (next.destination == ModDestination.PITCH) pitchCount++
                }
            }
            active = count > 0
            pitchRouteActive = pitchCount > 0
            appliedRouteRevision = latestRouteRevision
            if (!active) rack.resetSmoothing()
        }

        if (!active) return false

        val latestTempo = bpmBits.get()
        if (latestTempo != appliedBpmBits) {
            rack.setTempoBpm(java.lang.Float.intBitsToFloat(latestTempo))
            appliedBpmBits = latestTempo
        }

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

        for (source in ModSource.entries) {
            rack.setSource(
                source, java.lang.Float.intBitsToFloat(expressionBits.get(source.ordinal))
            )
        }
        baseValues[ModDestination.MORPH.ordinal] = baseMorph.coerceIn(0f, 1f)
        baseValues[ModDestination.PITCH.ordinal] = 0.5f
        return true
    }

    /** Audio-thread only: advances clock exactly once and reads the live buses. */
    fun tick() {
        rack.next(baseValues, frameValues)
        morph = frameValues[ModDestination.MORPH.ordinal]
        // 0.5 is neutral, +/-6 semitones is the programmed full pitch range.
        pitchSemitones = (frameValues[ModDestination.PITCH.ordinal] - 0.5f) * 12f
    }

    fun onNoteOn() {
        if (active) rack.retrigger()
    }

    /** Free-running synchronized modulation must keep time through silent blocks. */
    fun advanceSilence(frames: Int) {
        if (active) rack.advance(frames)
    }
}
