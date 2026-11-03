package com.orbital.synth.core

import com.orbital.synth.core.dsp.oscillator.LayeredOscillatorRack
import com.orbital.synth.core.dsp.oscillator.Voice
import com.orbital.synth.core.dsp.oscillator.WavetableBank
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import com.orbital.synth.core.dsp.processing.Drive
import com.orbital.synth.core.dsp.processing.FxBypassCrossfade
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.ModularFxChain
import com.orbital.synth.core.dsp.processing.StereoChorus
import com.orbital.synth.core.dsp.processing.StereoDelay
import com.orbital.synth.core.dsp.processing.StereoReverb
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Platform-independent, polyphonic ORBITAL audio engine.
 *
 * [noteOn], [noteOff], [allNotesOff], [setVoiceMode], [setPitchBend] and [setParameter]
 * are safe to call from UI/MIDI threads. Only a single dedicated audio thread should
 * call [renderStereo]. Incoming notes/mode events preserve FIFO order within an input generation;
 * a global panic invalidates older notes immediately at the next render block.
 * Controls and pitch bend are atomic latest-value snapshots.
 *
 * The sample loop performs no allocation, lock acquisition, I/O or Android interaction.
 */
enum class ParameterId {
    WAVE, MORPH, CUTOFF, RESONANCE, MOVEMENT, FX_AMOUNT,
    ATTACK, DECAY, SUSTAIN, RELEASE, LFO_RATE, LFO_DEPTH, GLIDE,
    UNISON, DETUNE, ENGINE, CHARACTER
}

enum class VoiceMode { POLY, MONO, LEGATO }

/**
 * One additive oscillator lane in the 8-slot rack.
 *
 * Waveform: 0 sine, 0.25 triangle, 0.5 saw, 0.75 pulse, 1 glass;
 * intermediate values continuously interpolate the band-limited families.
 * Coarse is +/-48 semitones, fine is +/-100 cents. Slots are independent
 * of the legacy UNISON control and disabled by default for compatibility.
 * These immutable snapshots may be safely passed across UI/audio threads.
 */
data class OscillatorSlot(
    val enabled: Boolean = false,
    val waveform: Float = 0f,
    val morph: Float = 1f,
    val level: Float = 0f,
    val coarseSemitones: Int = 0,
    val fineCents: Float = 0f,
    // Zero is exact legacy wavetable PCM. One substitutes noise entirely;
    // intermediate values make tonal/noise hybrids.
    val noiseLevel: Float = 0f,
    // Continuous color: 0 = white, 0.5 = pink, 1 = brown.
    val noiseColor: Float = 0f,
)

class OrbitalSynth(
    val sampleRate: Int = 48_000,
    val maxVoices: Int = 16
) {
    init {
        require(sampleRate in 8_000..192_000) { "sampleRate must be 8000..192000" }
        require(maxVoices in 1..64) { "maxVoices must be 1..64" }
        // Wavetable generation is eager at construction, never delayed to the audio callback.
        WavetableBank.levelForFrequency(440.0, sampleRate)
    }

    private val voices = Array(maxVoices) { Voice(sampleRate) }
    /** A rack is global patch state; every note gets its own eight oscillator phases. */
    val oscillatorSlotCount: Int get() = LayeredOscillatorRack.SLOT_COUNT
    private val oscillatorSlots =
        AtomicReferenceArray<OscillatorSlot>(LayeredOscillatorRack.SLOT_COUNT).apply {
            for (i in 0 until length()) set(i, OscillatorSlot())
        }
    // Preallocated, render-worker-owned snapshot makes each audio block internally
    // consistent even when an editing finger changes a slot halfway through.
    private val blockOscillatorSlots = Array(LayeredOscillatorRack.SLOT_COUNT) {
        OscillatorSlot()
    }
    private val controls = AtomicIntegerArray(ParameterId.entries.size).also { array ->
        for (id in ParameterId.entries) {
            array.set(id.ordinal, java.lang.Float.floatToRawIntBits(defaultValue(id)))
        }
    }
    private val bendBits = AtomicInteger(java.lang.Float.floatToRawIntBits(0f))
    // UI edits publish fixed-size immutable route/LFO snapshots. Only the audio
    // worker touches the running modulation clocks and destination slews.
    private val liveModulation = LiveModulationHost(sampleRate)
    // The rack itself ticks every sample; costly per-voice tan() targets are
    // refreshed at ~800 Hz, then interpolated by all filters at audio rate.
    // This cadence is continuous across render block sizes.
    private val filterControlInterval = max(1, sampleRate / 800)
    private var filterControlFrame = 0
    private var wasFilterRoutingActive = false
    private val events = ConcurrentLinkedQueue<Event>()
    // Only note/source events reserve slots; hostile MIDI floods cannot retain an
    // unbounded queue or strand releases behind millions of stale note-ons.
    private val maxPendingNoteEvents = 2_048
    private val pendingNoteEvents = AtomicInteger(0)
    internal val pendingNoteEventCount: Int get() = pendingNoteEvents.get()
    // Odd generations accept notes. Even generations are closed on overload
    // until the audio worker releases all held voices at its next block.
    private val noteInputEpoch = AtomicLong(1L)
    // Worker-only generation; no allocation/lock is needed to process panic.
    private var renderedNoteEpoch = 1L
    // Preallocate every held-key record. Render-thread event handling uses primitive
    // storage only: source IDs never create maps/objects or acquire locks in audio.
    // Under a hostile >capacity note-on flood, excess ons are ignored atomically
    // (and their later offs cannot affect another source).
    private val holdCapacity = max(256, maxVoices * 8)
    private val holdActive = BooleanArray(holdCapacity)
    private val holdTagged = BooleanArray(holdCapacity)
    private val holdSource = LongArray(holdCapacity)
    private val holdChannel = IntArray(holdCapacity)
    private val holdNote = IntArray(holdCapacity)
    private val holdVelocity = FloatArray(holdCapacity)
    private val holdOrder = LongArray(holdCapacity)
    private val holdVoice = IntArray(holdCapacity) { -1 }
    private val voiceHold = IntArray(maxVoices) { -1 }
    private val restoredHolds = BooleanArray(holdCapacity)
    private val releasedVoiceSlots = BooleanArray(maxVoices)

    private var eventOrder = 0L
    private var previousFrequency: Double? = null
    private var lfoPhase = 0.0
    // JVM PCM parity tests compare the exact pre-optimization frame math.
    // Worker-owned; never mutate while renderStereo is executing.
    internal var forceLegacyFramePitchMathForTests: Boolean = false
    private var smoothedMasterGain = 0.62f
    // 15 ms time constant: changing voice counts never jumps the level of held notes.
    private val gainSlewCoefficient = (1.0 - exp(-1.0 / (sampleRate * 0.015))).toFloat()

    // Master FX are strictly audio-thread-owned and allocated once at initialization.
    private val fxChorus = StereoChorus(sampleRate).apply { resetForBypass() }
    private val chorusScratch = FloatArray(2)
    private val fxDelay = StereoDelay(sampleRate, maxDelaySeconds = 1.5f).apply { resetForBypass() }
    private val fxScratch = FloatArray(2)
    private val fxReverb = StereoReverb(sampleRate).apply { resetForBypass() }
    private val reverbScratch = FloatArray(2)
    private val fxCrossfade = FxBypassCrossfade(sampleRate)

    // The user-programmable FX chain follows the existing master FX bus. Each
    // processor owns its own delay/chorus history and only the audio worker uses it.
    private val modularFx = ModularFxChain(sampleRate, slotCount = 6)
    val fxSlotCount: Int get() = 6
    private val fxSlots = AtomicReferenceArray<FxModuleConfig>(6).apply {
        for (slot in 0 until length()) set(slot, FxModuleConfig())
    }
    // Reject queued FX moves from a previous patch, even when the UI has
    // already published the next patch's immutable ordered slot snapshots.
    private val fxPatchEpoch = AtomicInteger(0)
    // Ticket ordering is serialized only between UI/MIDI callers; the audio
    // worker never locks or allocates while acknowledging completed moves.
    private val fxMoveEnqueueLock = Any()
    private val issuedFxMoveTicket = AtomicLong(0)
    private val appliedFxMoveTicket = AtomicLong(0)
    private val canceledFxMoveThroughTicket = AtomicLong(0)
    /** Completed worker reorders, including moves between identical FX configs. */
    val lastAppliedFxMoveTicket: Long get() = appliedFxMoveTicket.get()
    /** All tickets at/below this frontier belong to a superseded patch. */
    val lastCanceledFxMoveTicket: Long get() = canceledFxMoveThroughTicket.get()
    // Preallocated block-wide snapshots prevent a mid-block UI edit from giving
    // different frames different active FX topologies.
    private val blockFxSlots = Array(6) { FxModuleConfig() }
    // Hold the old drive amount across a fade-out; Fx_AMOUNT=0 must not alter
    // the wet signal before the 10 ms master-bus crossfade reaches true dry.
    private var driveTarget = 0f
    private var smoothedDrive = 0f
    private val driveSlewCoefficient = (1.0 - exp(-1.0 / (sampleRate * 0.010))).toFloat()

    @Volatile private var playbackMode = VoiceMode.POLY
    @Volatile var activeVoiceCount: Int = 0
        private set

    val voiceMode: VoiceMode get() = playbackMode

    /**
     * MIDI/touch events reserve one of 2048 queue slots BEFORE allocating a
     * command. Saturation closes the input generation and forces a worker-owned
     * global release next render block. A raced producer that never enqueues
     * returns its reservation exactly once; stale enqueued commands are tagged
     * and discarded by the worker without any source ownership side effect.
     */
    private inline fun offerNoteEvent(create: (Long) -> Event) {
        while (true) {
            val epoch = noteInputEpoch.get()
            if ((epoch and 1L) == 0L) return
            val pending = pendingNoteEvents.get()
            if (pending >= maxPendingNoteEvents) {
                if (noteInputEpoch.compareAndSet(epoch, epoch + 1L)) return
                continue
            }
            if (!pendingNoteEvents.compareAndSet(pending, pending + 1)) continue
            var enqueued = false
            try {
                if (noteInputEpoch.get() == epoch) {
                    enqueued = events.offer(create(epoch))
                }
            } finally {
                if (!enqueued) pendingNoteEvents.decrementAndGet()
            }
            return
        }
    }

    /** Backward-compatible, untagged note: only an untagged note-off can release it. */
    fun noteOn(note: Int, velocity: Float = 1f) {
        if (note !in 0..127 || !velocity.isFinite()) return
        if (velocity <= 0f) {
            noteOff(note)
        } else {
            val level = velocity.coerceIn(0f, 1f)
            offerNoteEvent { epoch -> Event.NoteOn(note, level, epoch = epoch) }
        }
    }

    fun noteOff(note: Int) {
        if (note in 0..127) offerNoteEvent { epoch -> Event.NoteOff(note, epoch = epoch) }
    }

    /**
     * Source-owned key press. Source IDs should be stable for a connected input
     * and unique across touch, sequencer and MIDI ports. Channel is zero-based.
     * Repeated ons for one (source, channel, note) stack and offs consume newest
     * first. This keeps independent sources from releasing each other's keys.
     */
    fun noteOn(sourceId: Long, channel: Int, note: Int, velocity: Float = 1f) {
        if (channel !in 0..15 || note !in 0..127 || !velocity.isFinite()) return
        if (velocity <= 0f) {
            noteOff(sourceId, channel, note)
        } else {
            val level = velocity.coerceIn(0f, 1f)
            offerNoteEvent { epoch -> Event.NoteOn(
                note, level, sourceId, channel, true, epoch
            ) }
        }
    }

    fun noteOff(sourceId: Long, channel: Int, note: Int) {
        if (channel in 0..15 && note in 0..127) {
            offerNoteEvent { epoch -> Event.NoteOff(note, sourceId, channel, true, epoch) }
        }
    }

    /**
     * A global note panic increments the generation ATOMICALLY. Notes queued
     * before this call are stale, notes queued afterward are new and audible.
     * The render worker releases every active hold at the next block boundary,
     * regardless of how deep the pending FIFO is; no queue.clear() or audio
     * callback allocation is permitted.
     */
    fun allNotesOff() {
        while (true) {
            val epoch = noteInputEpoch.get()
            if ((epoch and 1L) == 0L) return // overload already has a panic pending
            if (noteInputEpoch.compareAndSet(epoch, epoch + 2L)) return
        }
    }

    /** Gracefully release only keys belonging to this source, across all channels. */
    fun allNotesOff(sourceId: Long) {
        offerNoteEvent { epoch -> Event.SourceAllNotesOff(sourceId, epoch) }
    }

    /** POLY layers notes, MONO retriggers, and LEGATO retunes an already-held voice. */
    fun setVoiceMode(mode: VoiceMode) {
        events.offer(Event.Mode(mode))
    }

    /** MIDI pitch bend, in semitones. Inputs outside -12..+12 are clamped. */
    fun setPitchBend(semitones: Float) {
        if (semitones.isFinite()) {
            bendBits.set(java.lang.Float.floatToRawIntBits(semitones.coerceIn(-12f, 12f)))
        }
    }

    /** Normalized control, 0..1 inclusive; non-finite inputs are ignored. */
    fun setParameter(id: ParameterId, value: Float) {
        if (value.isFinite()) {
            controls.set(id.ordinal, java.lang.Float.floatToRawIntBits(value.coerceIn(0f, 1f)))
        }
    }

    fun parameter(id: ParameterId): Float =
        java.lang.Float.intBitsToFloat(controls.get(id.ordinal))

    val modulationRouteCount: Int get() = LiveModulationHost.ROUTE_COUNT
    val modulationLfoCount: Int get() = LiveModulationHost.LFO_COUNT

    /**
     * Freely route any of eight LFOs, touch/pressure expression or other
     * modulator sources. Real audio targets: MORPH, PITCH, CUTOFF,
     * RESONANCE, AMP and PAN. All other typed targets remain reserved.
     * Edits replace bounded atomic snapshots (no unbounded event queue).
     */
    fun setModulationRoute(
        slot: Int, source: ModSource, destination: ModDestination,
        depth: Float, curve: ModCurve = ModCurve.LINEAR,
    ) = liveModulation.setRoute(slot, source, destination, depth, curve)

    fun clearModulationRoute(slot: Int) = liveModulation.clearRoute(slot)

    fun modulationRoute(slot: Int): ModulationRouteConfig = liveModulation.route(slot)

    /** Restore a disabled route without discarding its editable source and depth. */
    fun setModulationRouteConfig(slot: Int, config: ModulationRouteConfig) =
        liveModulation.setRouteConfig(slot, config)

    fun setModulationLfo(
        slot: Int, enabled: Boolean, shape: LfoShape,
        rateHz: Float = 1f, beatsPerCycle: Float = 0f,
        retriggerOnNote: Boolean = false,
    ) = liveModulation.setLfo(slot, enabled, shape, rateHz, beatsPerCycle, retriggerOnNote)

    fun modulationLfo(slot: Int): ModulationLfoConfig = liveModulation.lfo(slot)

    fun setModulationTempoBpm(bpm: Float) = liveModulation.setTempoBpm(bpm)

    val modulationTempoBpm: Float get() = liveModulation.tempoBpm()

    /** Program sixteen bipolar STEP pads from the UI without audio-thread locks. */
    fun setModulationSteps(config: ModulationStepConfig) = liveModulation.setSteps(config)

    val modulationSteps: ModulationStepConfig get() = liveModulation.steps()

    /** Actual audio-clock STEP index, not a UI animation counter. */
    val modulationStepPlayhead: Int get() = liveModulation.stepPlayhead()

    /** Latest bounded bipolar value for touch X/Y/pressure, macros and MIDI. */
    fun setModulationSource(source: ModSource, bipolarValue: Float) =
        liveModulation.setSource(source, bipolarValue)

    /**
     * Program one of eight *real* wavetable oscillators, available alongside all
     * five existing sound engines. No lock or audio-thread allocation is required:
     * an immutable, validated snapshot is published atomically and picked up at
     * the start of the next audio block. Gain is de-clicked over 8 ms in each voice.
     *
     * Invalid non-finite values are ignored (the previous slot remains unchanged).
     * No slot is active by default, preserving existing patches sample-for-sample.
     */
    fun setOscillatorSlot(
        index: Int,
        enabled: Boolean,
        waveform: Float,
        level: Float,
        coarseSemitones: Int = 0,
        fineCents: Float = 0f,
        morph: Float = 1f,
    ) {
        require(index in 0 until oscillatorSlotCount) { "oscillator slot must be 0..7" }
        if (!waveform.isFinite() || !morph.isFinite() ||
            !level.isFinite() || !fineCents.isFinite()) return
        // Preserve any independently edited noise blend/color when an existing
        // V1-V5 wave/level/transpose UI control publishes a newer snapshot.
        // CAS avoids losing a simultaneous noise-knob update.
        while (true) {
            val prior = oscillatorSlots.get(index)
            val updated = prior.copy(
                enabled = enabled,
                waveform = waveform.coerceIn(0f, 1f),
                morph = morph.coerceIn(0f, 1f),
                level = level.coerceIn(0f, 1f),
                coarseSemitones = coarseSemitones.coerceIn(-48, 48),
                fineCents = fineCents.coerceIn(-100f, 100f),
            )
            if (oscillatorSlots.compareAndSet(index, prior, updated)) return
        }
    }

    /** Configure a full immutable slot, e.g. when recalling a V6 sound patch. */
    fun setOscillatorSlot(index: Int, config: OscillatorSlot) {
        require(index in 0 until oscillatorSlotCount) { "oscillator slot must be 0..7" }
        if (!config.waveform.isFinite() || !config.morph.isFinite() ||
            !config.level.isFinite() || !config.fineCents.isFinite() ||
            !config.noiseLevel.isFinite() || !config.noiseColor.isFinite()) return
        oscillatorSlots.set(index, config.copy(
            waveform = config.waveform.coerceIn(0f, 1f),
            morph = config.morph.coerceIn(0f, 1f),
            level = config.level.coerceIn(0f, 1f),
            coarseSemitones = config.coarseSemitones.coerceIn(-48, 48),
            fineCents = config.fineCents.coerceIn(-100f, 100f),
            noiseLevel = config.noiseLevel.coerceIn(0f, 1f),
            noiseColor = config.noiseColor.coerceIn(0f, 1f),
        ))
    }

    /**
     * Atomically morph any of the eight wavetable layers into a white/pink/brown
     * source. The audio worker slews noise level/color over 8 ms per voice; no
     * random-number work is done when noiseLevel == 0.
     */
    fun setOscillatorNoise(index: Int, noiseLevel: Float, noiseColor: Float) {
        require(index in 0 until oscillatorSlotCount) { "oscillator slot must be 0..7" }
        if (!noiseLevel.isFinite() || !noiseColor.isFinite()) return
        val level = noiseLevel.coerceIn(0f, 1f)
        val color = noiseColor.coerceIn(0f, 1f)
        while (true) {
            val prior = oscillatorSlots.get(index)
            val updated = prior.copy(noiseLevel = level, noiseColor = color)
            if (oscillatorSlots.compareAndSet(index, prior, updated)) return
        }
    }

    /** Current UI-side snapshot. This is not the render worker's mutable state. */
    fun oscillatorSlot(index: Int): OscillatorSlot {
        require(index in 0 until oscillatorSlotCount) { "oscillator slot must be 0..7" }
        return oscillatorSlots.get(index)
    }

    fun clearOscillatorRack() {
        for (slot in 0 until oscillatorSlotCount) {
            oscillatorSlots.set(slot, OscillatorSlot())
        }
    }

    /**
     * Thread-safe UI/MIDI entry point for six independently programmable FX
     * modules after the original master FX. Configuration is published as an
     * immutable atomic snapshot and applied at the next audio block boundary.
     * No mutable processor state is touched by the caller.
     */
    fun setFxSlot(index: Int, config: FxModuleConfig) {
        require(index in 0 until fxSlotCount) { "FX slot must be 0..5" }
        if (!config.wet.isFinite() || !config.depth.isFinite() ||
            !config.rate.isFinite() || !config.stereo.isFinite()) return
        fxSlots.set(index, config.copy(
            wet = config.wet.coerceIn(0f, 1f),
            depth = config.depth.coerceIn(0f, 1f),
            rate = config.rate.coerceIn(0f, 1f),
            stereo = config.stereo.coerceIn(0f, 1f),
        ))
    }

    fun setFxSlot(
        index: Int, type: FxModuleType, enabled: Boolean,
        wet: Float, depth: Float = 0.5f, rate: Float = 0.5f, stereo: Float = 0.5f,
    ) = setFxSlot(index, FxModuleConfig(type, enabled, wet, depth, rate, stereo))

    /** Immutable settings as published by the UI, even during an audible fade. */
    fun fxSlot(index: Int): FxModuleConfig {
        require(index in 0 until fxSlotCount) { "FX slot must be 0..5" }
        return fxSlots.get(index)
    }

    fun clearFxRack() {
        for (slot in 0 until fxSlotCount) fxSlots.set(slot, FxModuleConfig())
    }

    /**
     * A patch recall invalidates old queued rack reorders and retires prior
     * delay/chorus histories on the audio worker before applying the newly
     * published rack snapshots. This works even if a new patch re-enables
     * exactly the same FX configuration without an intervening render block.
     */
    fun resetFxRackHistoryForPatch() {
        synchronized(fxMoveEnqueueLock) {
            fxPatchEpoch.incrementAndGet()
            canceledFxMoveThroughTicket.set(issuedFxMoveTicket.get())
            events.offer(Event.ResetFxRackHistory)
        }
    }

    /**
     * Reorder entire FX processors on the audio worker without copying history.
     * Queuing this event preserves ordering relative to note events and avoids
     * touching the chain or moving module state from the UI thread.
     */
    fun moveFxSlot(from: Int, to: Int) {
        requestFxSlotMove(from, to)
    }

    /**
     * Queue a worker-owned rack reorder. Returns a monotonically increasing
     * positive ticket (zero means a no-op move). UI editors may hold edits to
     * moved slots until ticket <= [lastAppliedFxMoveTicket], unless ticket <=
     * [lastCanceledFxMoveTicket], in which case a newer patch superseded it.
     */
    fun requestFxSlotMove(from: Int, to: Int): Long {
        require(from in 0 until fxSlotCount && to in 0 until fxSlotCount)
        if (from == to) return 0L
        return synchronized(fxMoveEnqueueLock) {
            val ticket = issuedFxMoveTicket.incrementAndGet()
            events.offer(Event.MoveFxSlot(from, to, fxPatchEpoch.get(), ticket))
            ticket
        }
    }

    /**
     * Render [frames] interleaved float stereo frames into an already allocated buffer.
     * Only the first 2*frames entries are written. No Android dependency is required.
     */
    fun renderStereo(output: FloatArray, frames: Int) {
        require(frames >= 0 && frames <= output.size / 2) {
            "output must contain at least 2 * frames samples"
        }

        val wave = parameter(ParameterId.WAVE)
        val morph = parameter(ParameterId.MORPH)
        val cutoff = parameter(ParameterId.CUTOFF)
        val resonance = parameter(ParameterId.RESONANCE)
        val movement = parameter(ParameterId.MOVEMENT)
        val fxAmount = parameter(ParameterId.FX_AMOUNT)
        val attack = parameter(ParameterId.ATTACK)
        val decay = parameter(ParameterId.DECAY)
        val sustain = parameter(ParameterId.SUSTAIN)
        val release = parameter(ParameterId.RELEASE)
        val lfoRate = parameter(ParameterId.LFO_RATE)
        val lfoDepth = parameter(ParameterId.LFO_DEPTH)
        val glide = parameter(ParameterId.GLIDE)
        val unison = parameter(ParameterId.UNISON)
        val detune = parameter(ParameterId.DETUNE)
        // Five architecture families share the same normalized live-control contract.
        // Quantize at block boundaries to keep a stable voice graph within a block.
        val engineModel = (parameter(ParameterId.ENGINE) * 4f + 0.5f)
            .toInt().coerceIn(0, 4)
        val character = parameter(ParameterId.CHARACTER)
        val pitchBend = java.lang.Float.intBitsToFloat(bendBits.get()).toDouble()

        // Logarithmic time/frequency controls provide fine motion near useful short values.
        val attackSec = 0.003 + attack.toDouble() * attack * 3.0
        val decaySec = 0.020 + decay.toDouble() * decay * 3.5
        val releaseSec = 0.020 + release.toDouble() * release * 4.0
        val glideSec = if (glide <= 0f) 0.0 else 0.004 + glide.toDouble() * glide * 0.75
        val cutoffHz = FilterCutoffMath.baseHz(cutoff, sampleRate)
        val lfoHz = 0.05 * 2.0.pow(lfoRate * 8.0)
        val lfoIncrement = lfoHz / sampleRate
        val modulationDepth = movement * lfoDepth
        // At exactly zero depth, LFO phase still advances sample-accurately
        // while its inaudible sine computation can be omitted.
        val useLegacyFrameMath = forceLegacyFramePitchMathForTests
        val calculateLfo = modulationDepth != 0f || useLegacyFrameMath
        // Bypass the entire new mod clock on legacy patches with zero routes.
        val modulationEnabled = liveModulation.syncBlock(morph, cutoff, resonance)
        // Query AFTER worker block synchronization: newly attached PITCH
        // cables must be audible in their very first render block.
        val blockConstantPitch = modulationDepth == 0f &&
            !liveModulation.pitchRouteActive && !useLegacyFrameMath
        val filterRoutingActive = modulationEnabled && liveModulation.filterRouteActive
        if (filterRoutingActive != wasFilterRoutingActive) {
            // First update happens immediately when the user plugs a new cable.
            filterControlFrame = 0
            wasFilterRoutingActive = filterRoutingActive
        }

        for (slot in 0 until oscillatorSlotCount) {
            blockOscillatorSlots[slot] = oscillatorSlots.get(slot)
        }
        for (voice in voices) {
            for (slot in 0 until oscillatorSlotCount) {
                voice.configureOscillatorSlot(slot, blockOscillatorSlots[slot])
            }
            voice.configure(attackSec, decaySec, sustain, releaseSec,
                cutoffHz, resonance, updateFilter = !filterRoutingActive)
            voice.configureUnison(unison, detune)
            voice.configureEngine(engineModel, character, cutoffHz.toFloat(),
                resonance, wave, morph, updateFilter = !filterRoutingActive)
        }
        drainEvents(glideSec, wave, morph)
        for (slot in 0 until fxSlotCount) {
            blockFxSlots[slot] = fxSlots.get(slot)
            modularFx.setSlot(slot, blockFxSlots[slot])
        }
        val modularFxNeedsProcessing = modularFx.needsProcessing

        // Reserve room for upward bend and vibrato when selecting anti-alias mip levels.
        val highestPitchMultiplier = 2.0.pow((max(0.0, pitchBend) +
            modulationDepth * 0.5 + (if (liveModulation.pitchRouteActive) 6.0 else 0.0)) / 12.0)
        for (voice in voices) voice.preparePitch(highestPitchMultiplier)

        var active = 0
        for (voice in voices) if (voice.active) active++
        activeVoiceCount = active
        val targetMasterGain = (0.62 / sqrt(max(1, active).toDouble())).toFloat()
        val fxEnabled = fxAmount > 0f
        // Constant delay time prevents knob movements from abruptly changing tap positions.
        // Configuration only occurs once per render block, never in the sample loop.
        if (fxEnabled) {
            fxChorus.configure(
                rateHz = 0.24f + movement * 0.86f,
                depth = 0.36f + movement * 0.55f,
                wet = fxAmount * 0.42f,
                stereoWidth = 0.94f
            )
            fxDelay.configure(
                timeSeconds = 0.29f,
                feedback = 0.36f + fxAmount * 0.40f,
                wet = fxAmount * 0.52f,
                pingPong = 0.72f
            )
            fxReverb.configure(
                decaySeconds = 1.25f + fxAmount * 3.35f,
                damping = 0.36f + (1f - movement) * 0.24f,
                wet = fxAmount * 0.30f,
                diffusion = 0.70f + movement * 0.25f,
                stereoWidth = 0.92f,
            )
            driveTarget = fxAmount * 0.68f
        }
        // This target can reverse mid-ramp without jumping. On disable, leave
        // chorus/delay/reverb configuration and driveTarget unchanged until fade-out.
        fxCrossfade.setEnabled(fxEnabled)

        // AudioTrack normally keeps requesting blocks even when no keys are held. Avoid
        // evaluating sixteen idle voices, the trigonometric LFO and pitch pow() for every
        // silent frame. Keep the LFO clock and gain slew advancing sample-by-sample so
        // later notes behave exactly as if the silent blocks had been fully rendered.
        if (!fxCrossfade.requiresWetPath && active == 0 && voices.none { it.hasTail }) {
            java.util.Arrays.fill(output, 0, frames * 2, 0f)
            for (frame in 0 until frames) {
                smoothedMasterGain +=
                    (targetMasterGain - smoothedMasterGain) * gainSlewCoefficient
                lfoPhase += lfoIncrement
                if (lfoPhase >= 1.0) lfoPhase -= 1.0
            }
            // Keep configured free-running LFOs in time without 8x trigonometry.
            liveModulation.advanceSilence(frames)
            filterControlFrame = (filterControlFrame + frames) % filterControlInterval
            // Delay/chorus memory can remain audible after all held voices stop.
            // Keep rendering only the modular chain on silence, with no voice or
            // trigonometric voice-LFO work, until its wet/bypass tails retire.
            if (modularFxNeedsProcessing) {
                for (frame in 0 until frames) {
                    modularFx.process(0f, 0f, output, frame * 2)
                }
            }
            return
        }

        // The silent early-return above needs no pitch pow() at all.
        val blockPitchMultiplier = if (blockConstantPitch) {
            2.0.pow(pitchBend / 12.0)
        } else 1.0
        for (frame in 0 until frames) {
            // Sample-rate-independent slew avoids a 29% instantaneous level jump when
            // a second voice joins an already sustained single note.
            smoothedMasterGain +=
                (targetMasterGain - smoothedMasterGain) * gainSlewCoefficient
            val lfo = if (calculateLfo) sin(lfoPhase * 2.0 * PI).toFloat() else 0f
            lfoPhase += lfoIncrement
            if (lfoPhase >= 1.0) lfoPhase -= 1.0

            if (modulationEnabled) liveModulation.tick()
            else liveModulation.tickStepOnly()
            if (filterRoutingActive && filterControlFrame == 0) {
                // Consume all 64 summed cables and let the real SVF/ladder/SEM
                // pole slews work continuously between bounded target updates.
                val hz = FilterCutoffMath.baseHz(liveModulation.cutoff, sampleRate)
                for (voice in voices) {
                    if (voice.active) voice.retargetFilter(hz, liveModulation.resonance)
                }
            }
            filterControlFrame++
            if (filterControlFrame >= filterControlInterval) filterControlFrame = 0
            val routedMorph = if (modulationEnabled) liveModulation.morph else morph
            val movingMorph = (routedMorph + lfo * modulationDepth * 0.35f).coerceIn(0f, 1f)
            val vibratoSemitones = (lfo * modulationDepth * 0.5).toDouble()
            val routedPitchSemitones = if (modulationEnabled)
                liveModulation.pitchSemitones.toDouble() else 0.0
            val pitchMultiplier = if (blockConstantPitch) blockPitchMultiplier else 2.0.pow(
                (pitchBend + vibratoSemitones + routedPitchSemitones) / 12.0
            )

            var left = 0f
            var right = 0f
            for (voice in voices) {
                if (voice.active || voice.hasTail) {
                    val sample = voice.nextSample(wave, movingMorph, pitchMultiplier)
                    left += sample * voice.panLeft
                    right += sample * voice.panRight
                }
            }
            if (modulationEnabled &&
                (liveModulation.ampRouteActive || liveModulation.panRouteActive)) {
                val gain = if (liveModulation.ampRouteActive) liveModulation.ampGain else 1f
                val pan = if (liveModulation.panRouteActive) liveModulation.panOffset else 0f
                // Neutral exactly equals one, preserving the legacy stereo
                // balance. Extreme negative/positive gestures mute right/left.
                left *= gain * (if (pan > 0f) 1f - pan else 1f)
                right *= gain * (if (pan < 0f) 1f + pan else 1f)
            }
            val dryLeft = softClip(left * smoothedMasterGain)
            val dryRight = softClip(right * smoothedMasterGain)
            val offset = frame * 2
            if (fxCrossfade.requiresWetPath) {
                // Always render the previous wet bus through a fade-out, even when
                // FX_AMOUNT just became zero. Do not replace its saved parameters.
                // The crossfader reaches exact dry after a finite 10 ms ramp.
                smoothedDrive += (driveTarget - smoothedDrive) * driveSlewCoefficient
                fxChorus.process(
                    Drive.process(dryLeft, smoothedDrive),
                    Drive.process(dryRight, smoothedDrive),
                    chorusScratch,
                    0
                )
                fxDelay.process(chorusScratch[0], chorusScratch[1], fxScratch, 0)
                fxReverb.process(fxScratch[0], fxScratch[1], reverbScratch, 0)
                fxCrossfade.process(
                    dryLeft, dryRight,
                    softClip(reverbScratch[0]), softClip(reverbScratch[1]),
                    output, offset
                )
                if (fxCrossfade.isFullyDry) {
                    // Clear tails only AFTER the final fully dry output sample.
                    // Re-enabling starts with empty wet history and zero wet mix.
                    fxChorus.resetForBypass()
                    fxDelay.resetForBypass()
                    fxReverb.resetForBypass()
                    smoothedDrive = 0f
                    driveTarget = 0f
                }
            } else {
                // This exact, unprocessed dry path retains legacy behavior and
                // needs neither wet DSP work nor an intermediate allocation.
                output[offset] = dryLeft
                output[offset + 1] = dryRight
            }
            if (modularFxNeedsProcessing) {
                modularFx.process(output[offset], output[offset + 1], output, offset)
            }
        }

        // Keep the UI-facing diagnostic in sync with voices that ended within this block.
        active = 0
        for (voice in voices) if (voice.active) active++
        activeVoiceCount = active
    }

    /** Worker only. Panic is independent of the FIFO and touches no locks or heap. */
    private fun releaseAllHeldNotes() {
        holdActive.fill(false)
        holdVoice.fill(-1)
        voiceHold.fill(-1)
        previousFrequency = null
        for (voice in voices) voice.noteOff()
    }

    private fun synchronizeNoteEpoch() {
        val observed = noteInputEpoch.get()
        if (observed == renderedNoteEpoch) return
        releaseAllHeldNotes()
        // A full queue closes an even epoch. Only the worker can reopen it,
        // once EVERY old voice has received its emergency note-off.
        if ((observed and 1L) == 0L) {
            noteInputEpoch.compareAndSet(observed, observed + 1L)
        }
        // A producer may overload again WHILE we release. Never record an
        // even/closed epoch as handled: otherwise admission would stay closed
        // forever because the next callback would see no generation change.
        val afterRelease = noteInputEpoch.get()
        renderedNoteEpoch = if ((afterRelease and 1L) == 0L) {
            afterRelease - 1L
        } else afterRelease
    }

    private fun noteEventStillCurrent(epoch: Long): Boolean {
        val observed = noteInputEpoch.get()
        if (observed != epoch || (observed and 1L) == 0L) return false
        // Global panic may occur while draining this very block. Apply the
        // release before any new-generation note, then accept that fresh note.
        if (renderedNoteEpoch != observed) synchronizeNoteEpoch()
        return renderedNoteEpoch == epoch && noteInputEpoch.get() == epoch
    }

    private fun drainEvents(glideSec: Double, wave: Float, morph: Float) {
        // Global/overload panic is applied BEFORE even the oldest queued MIDI
        // event. Note FIFO work remains strictly capped at 512 per callback.
        synchronizeNoteEpoch()
        for (index in 0 until 512) {
            when (val event = events.poll() ?: break) {
                is Event.NoteOn -> {
                    pendingNoteEvents.decrementAndGet()
                    if (!noteEventStillCurrent(event.epoch)) continue
                    liveModulation.onNoteOn()
                    handleNoteOn(
                        event.note, event.velocity, event.sourceId, event.channel, event.tagged,
                        glideSec, wave, morph
                    )
                }
                is Event.NoteOff -> {
                    pendingNoteEvents.decrementAndGet()
                    if (!noteEventStillCurrent(event.epoch)) continue
                    handleNoteOff(
                        event.note, event.sourceId, event.channel, event.tagged,
                        glideSec, wave, morph
                    )
                }
                is Event.SourceAllNotesOff -> {
                    pendingNoteEvents.decrementAndGet()
                    if (!noteEventStillCurrent(event.epoch)) continue
                    handleSourceAllNotesOff(event.sourceId, glideSec, wave, morph)
                }
                is Event.Mode -> changeMode(event.mode, glideSec, wave, morph)
                is Event.MoveFxSlot -> {
                    if (event.patchEpoch == fxPatchEpoch.get()) {
                        handleFxSlotMove(event.from, event.to)
                        // Publish completion only after processor history and
                        // atomic slot snapshots were both reordered.
                        appliedFxMoveTicket.set(event.ticket)
                    }
                }
                is Event.ResetFxRackHistory -> modularFx.clearAll()
            }
        }
    }

    /** Worker-only synchronized movement of the immutable settings and DSP history. */
    private fun handleFxSlotMove(from: Int, to: Int) {
        val moving = fxSlots.get(from)
        if (from < to) {
            for (slot in from until to) fxSlots.set(slot, fxSlots.get(slot + 1))
        } else {
            for (slot in from downTo to + 1) fxSlots.set(slot, fxSlots.get(slot - 1))
        }
        fxSlots.set(to, moving)
        modularFx.moveSlot(from, to)
    }

    private fun handleNoteOn(
        note: Int, velocity: Float, sourceId: Long, channel: Int, tagged: Boolean,
        glideSec: Double, wave: Float, morph: Float
    ) {
        val alreadyHeld = latestHeldSlot() >= 0
        val slot = freeHoldSlot()
        if (slot < 0) return

        holdActive[slot] = true
        holdTagged[slot] = tagged
        holdSource[slot] = sourceId
        holdChannel[slot] = channel
        holdNote[slot] = note
        holdVelocity[slot] = velocity
        holdOrder[slot] = ++eventOrder
        holdVoice[slot] = -1

        val fromFrequency = previousFrequency
        when (playbackMode) {
            VoiceMode.POLY -> {
                val voiceIndex = chooseVoiceIndex()
                voices[voiceIndex].begin(
                    note, velocity, eventOrder, fromFrequency, glideSec, wave, morph
                )
                bindVoice(voiceIndex, slot)
            }
            VoiceMode.MONO, VoiceMode.LEGATO -> {
                for (index in 1 until voices.size) {
                    voices[index].noteOff()
                    unbindVoice(index)
                }
                val voice = voices[0]
                val legato = playbackMode == VoiceMode.LEGATO &&
                    alreadyHeld && voice.active && !voice.releasing
                voice.begin(note, velocity, eventOrder, fromFrequency, glideSec, wave, morph, legato)
                bindVoice(0, slot)
            }
        }
        previousFrequency = Voice.midiFrequency(note)
    }

    private fun handleNoteOff(
        note: Int, sourceId: Long, channel: Int, tagged: Boolean,
        glideSec: Double, wave: Float, morph: Float
    ) {
        val slot = newestMatchingHold(note, sourceId, channel, tagged)
        if (slot < 0) return // Duplicate or unmatched off never releases a different source.
        val voiceIndex = removeHold(slot)
        when (playbackMode) {
            VoiceMode.POLY -> if (voiceIndex >= 0) recycleOrReleaseVoice(voiceIndex, wave, morph)
            VoiceMode.MONO, VoiceMode.LEGATO -> {
                if (voiceIndex == 0) {
                    retargetMonoLead(Voice.midiFrequency(note), glideSec, wave, morph)
                }
            }
        }
    }

    private fun handleSourceAllNotesOff(
        sourceId: Long, glideSec: Double, wave: Float, morph: Float
    ) {
        var leadRemoved = false
        val oldLeadNote = voices[0].note
        releasedVoiceSlots.fill(false)
        // Remove all matching holds before choosing any replacement; intermediate
        // retunes would otherwise create unnecessary ghost tails/clicks.
        for (slot in holdActive.indices) {
            if (!holdActive[slot] || !holdTagged[slot] || holdSource[slot] != sourceId) continue
            val voiceIndex = removeHold(slot)
            if (playbackMode == VoiceMode.POLY && voiceIndex >= 0) {
                releasedVoiceSlots[voiceIndex] = true
            } else if (voiceIndex == 0) {
                leadRemoved = true
            }
        }
        if (playbackMode == VoiceMode.POLY) {
            for (index in voices.indices) {
                if (releasedVoiceSlots[index]) recycleOrReleaseVoice(index, wave, morph)
            }
        } else if (leadRemoved) {
            val fromFrequency = if (oldLeadNote in 0..127)
                Voice.midiFrequency(oldLeadNote) else previousFrequency
            retargetMonoLead(fromFrequency, glideSec, wave, morph)
        }
    }

    private fun retargetMonoLead(
        fromFrequency: Double?, glideSec: Double, wave: Float, morph: Float
    ) {
        val slot = latestHeldSlot()
        val voice = voices[0]
        if (slot < 0) {
            voice.noteOff()
            unbindVoice(0)
            return
        }
        // Two physical controls may hold the same pitch. Rebinding ownership
        // without retriggering preserves the current envelope and oscillator.
        if (voice.active && !voice.releasing && voice.note == holdNote[slot]) {
            bindVoice(0, slot)
            return
        }
        val legato = playbackMode == VoiceMode.LEGATO && voice.active && !voice.releasing
        voice.begin(
            holdNote[slot], holdVelocity[slot], ++eventOrder, fromFrequency,
            glideSec, wave, morph, legato
        )
        bindVoice(0, slot)
        previousFrequency = Voice.midiFrequency(holdNote[slot])
    }

    private fun changeMode(mode: VoiceMode, glideSec: Double, wave: Float, morph: Float) {
        if (playbackMode == mode) return
        playbackMode = mode
        if (mode == VoiceMode.POLY) {
            restoreHeldPolyVoices(wave, morph)
        } else {
            for (index in 1 until voices.size) {
                voices[index].noteOff()
                unbindVoice(index)
            }
            val slot = latestHeldSlot()
            if (slot >= 0) {
                val voice = voices[0]
                val legato = mode == VoiceMode.LEGATO && voice.active && !voice.releasing
                voice.begin(
                    holdNote[slot], holdVelocity[slot], ++eventOrder, previousFrequency,
                    glideSec, wave, morph, legato
                )
                bindVoice(0, slot)
                previousFrequency = Voice.midiFrequency(holdNote[slot])
            } else {
                voices[0].noteOff()
                unbindVoice(0)
            }
        }
    }

    /**
     * Restore exact remaining holds (including same-pitch duplicates) when
     * returning from mono/legato to poly. Keep the continuing lead untouched,
     * then choose the newest other holds for available secondary slots.
     */
    private fun restoreHeldPolyVoices(wave: Float, morph: Float) {
        restoredHolds.fill(false)
        val leadSlot = voiceHold[0]
        if (leadSlot >= 0 && holdActive[leadSlot] && voices[0].active &&
            !voices[0].releasing && voices[0].note == holdNote[leadSlot]
        ) {
            restoredHolds[leadSlot] = true
        } else {
            unbindVoice(0)
            val replacement = latestHeldSlot()
            if (replacement >= 0) {
                voices[0].begin(
                    holdNote[replacement], holdVelocity[replacement], ++eventOrder,
                    null, 0.0, wave, morph
                )
                bindVoice(0, replacement)
                restoredHolds[replacement] = true
            }
        }

        for (slotIndex in 1 until voices.size) {
            val holdIndex = newestUnrestoredHold()
            if (holdIndex < 0) break
            val voiceIndex = chooseSecondaryVoiceIndex()
            voices[voiceIndex].begin(
                holdNote[holdIndex], holdVelocity[holdIndex], ++eventOrder,
                null, 0.0, wave, morph
            )
            bindVoice(voiceIndex, holdIndex)
            restoredHolds[holdIndex] = true
        }
    }

    /**
     * When all voice slots were occupied, older held keys may have been stolen.
     * Releasing the current owner should transfer the freed slot to a surviving
     * held key, particularly when two sources hold the same pitch with one voice.
     */
    private fun recycleOrReleaseVoice(voiceIndex: Int, wave: Float, morph: Float) {
        val replacement = newestUnvoicedHold()
        val voice = voices[voiceIndex]
        if (replacement < 0) {
            voice.noteOff()
            return
        }
        if (!voice.releasing && voice.active && voice.note == holdNote[replacement]) {
            // Keep phase/envelope continuous for same-pitch stacked ownership.
            bindVoice(voiceIndex, replacement)
        } else {
            voice.begin(
                holdNote[replacement], holdVelocity[replacement], ++eventOrder,
                null, 0.0, wave, morph
            )
            bindVoice(voiceIndex, replacement)
        }
    }

    private fun newestUnvoicedHold(): Int {
        var selected = -1
        var newestOrder = Long.MIN_VALUE
        for (slot in holdActive.indices) {
            if (holdActive[slot] && holdVoice[slot] < 0 && holdOrder[slot] > newestOrder) {
                selected = slot
                newestOrder = holdOrder[slot]
            }
        }
        return selected
    }

    private fun freeHoldSlot(): Int {
        for (slot in holdActive.indices) if (!holdActive[slot]) return slot
        return -1
    }

    private fun newestMatchingHold(
        note: Int, sourceId: Long, channel: Int, tagged: Boolean
    ): Int {
        var selected = -1
        var newestOrder = Long.MIN_VALUE
        for (slot in holdActive.indices) {
            if (holdActive[slot] && holdTagged[slot] == tagged &&
                holdNote[slot] == note &&
                (!tagged || (holdSource[slot] == sourceId && holdChannel[slot] == channel)) &&
                holdOrder[slot] > newestOrder
            ) {
                newestOrder = holdOrder[slot]
                selected = slot
            }
        }
        return selected
    }

    private fun latestHeldSlot(): Int {
        var selected = -1
        var newestOrder = Long.MIN_VALUE
        for (slot in holdActive.indices) {
            if (holdActive[slot] && holdOrder[slot] > newestOrder) {
                newestOrder = holdOrder[slot]
                selected = slot
            }
        }
        return selected
    }

    private fun newestUnrestoredHold(): Int {
        var selected = -1
        var newestOrder = Long.MIN_VALUE
        for (slot in holdActive.indices) {
            if (holdActive[slot] && !restoredHolds[slot] && holdOrder[slot] > newestOrder) {
                newestOrder = holdOrder[slot]
                selected = slot
            }
        }
        return selected
    }

    /**
     * Unbind an old hold before voice stealing. The hold may remain pressed, but
     * its later off must not affect the new owner occupying that voice slot.
     */
    private fun unbindVoice(voiceIndex: Int) {
        val previous = voiceHold[voiceIndex]
        if (previous >= 0 && holdVoice[previous] == voiceIndex) holdVoice[previous] = -1
        voiceHold[voiceIndex] = -1
    }

    private fun bindVoice(voiceIndex: Int, holdIndex: Int) {
        unbindVoice(voiceIndex)
        voiceHold[voiceIndex] = holdIndex
        holdVoice[holdIndex] = voiceIndex
    }

    private fun removeHold(holdIndex: Int): Int {
        holdActive[holdIndex] = false
        val index = holdVoice[holdIndex]
        if (index >= 0 && voiceHold[index] == holdIndex) {
            unbindVoice(index)
            return index
        }
        holdVoice[holdIndex] = -1
        return -1
    }

    private fun chooseSecondaryVoiceIndex(): Int {
        for (index in 1 until voices.size) if (!voices[index].active) return index
        var selected = 1
        for (index in 2 until voices.size) {
            val voice = voices[index]
            val best = voices[selected]
            if ((voice.releasing && !best.releasing) ||
                (voice.releasing == best.releasing &&
                    (voice.envelopeLevel < best.envelopeLevel ||
                        (voice.envelopeLevel == best.envelopeLevel &&
                            voice.startedAt < best.startedAt)))
            ) selected = index
        }
        return selected
    }

    private fun chooseVoiceIndex(): Int {
        for (index in voices.indices) if (!voices[index].active) return index
        var selected = 0
        for (index in 1 until voices.size) {
            val voice = voices[index]
            val best = voices[selected]
            if ((voice.releasing && !best.releasing) ||
                (voice.releasing == best.releasing &&
                    (voice.envelopeLevel < best.envelopeLevel ||
                        (voice.envelopeLevel == best.envelopeLevel &&
                            voice.startedAt < best.startedAt)))
            ) selected = index
        }
        return selected
    }

    private fun softClip(value: Float): Float {
        if (value >= 5f) return 1f
        if (value <= -5f) return -1f
        val squared = value * value
        return (value * (27f + squared) / (27f + 9f * squared)).coerceIn(-1f, 1f)
    }

    private sealed interface Event {
        data class NoteOn(
            val note: Int, val velocity: Float,
            val sourceId: Long = 0L, val channel: Int = 0, val tagged: Boolean = false,
            val epoch: Long,
        ) : Event
        data class NoteOff(
            val note: Int, val sourceId: Long = 0L,
            val channel: Int = 0, val tagged: Boolean = false,
            val epoch: Long,
        ) : Event
        data class Mode(val mode: VoiceMode) : Event
        data class MoveFxSlot(
            val from: Int, val to: Int, val patchEpoch: Int, val ticket: Long,
        ) : Event
        data object ResetFxRackHistory : Event
        data class SourceAllNotesOff(val sourceId: Long, val epoch: Long) : Event
    }

    companion object {
        fun defaultValue(id: ParameterId): Float = when (id) {
            ParameterId.WAVE -> 0.63f
            ParameterId.MORPH -> 0.78f
            ParameterId.CUTOFF -> 0.62f
            ParameterId.RESONANCE -> 0.28f
            ParameterId.MOVEMENT -> 0.32f
            ParameterId.FX_AMOUNT -> 0.0f
            ParameterId.ATTACK -> 0.05f
            ParameterId.DECAY -> 0.22f
            ParameterId.SUSTAIN -> 0.72f
            ParameterId.RELEASE -> 0.25f
            ParameterId.LFO_RATE -> 0.34f
            ParameterId.LFO_DEPTH -> 0.24f
            ParameterId.GLIDE -> 0.0f
            ParameterId.UNISON -> 0.0f
            ParameterId.DETUNE -> 0.0f
            ParameterId.ENGINE -> 0.0f // original wavetable mode, backwards compatible
            ParameterId.CHARACTER -> 0.0f
        }
    }
}
