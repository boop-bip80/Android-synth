package com.orbital.synth.core

import com.orbital.synth.core.dsp.oscillator.LayeredOscillatorRack
import com.orbital.synth.core.dsp.oscillator.Voice
import com.orbital.synth.core.dsp.oscillator.WavetableBank
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.Drive
import com.orbital.synth.core.dsp.processing.FxBypassCrossfade
import com.orbital.synth.core.dsp.processing.StereoChorus
import com.orbital.synth.core.dsp.processing.StereoDelay
import com.orbital.synth.core.dsp.processing.StereoReverb
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
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
 * call [renderStereo]. Incoming notes/mode events are applied in order at block boundaries;
 * controls and pitch bend are atomic latest-value snapshots.
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
    private val events = ConcurrentLinkedQueue<Event>()
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
    // Hold the old drive amount across a fade-out; Fx_AMOUNT=0 must not alter
    // the wet signal before the 10 ms master-bus crossfade reaches true dry.
    private var driveTarget = 0f
    private var smoothedDrive = 0f
    private val driveSlewCoefficient = (1.0 - exp(-1.0 / (sampleRate * 0.010))).toFloat()

    @Volatile private var playbackMode = VoiceMode.POLY
    @Volatile var activeVoiceCount: Int = 0
        private set

    val voiceMode: VoiceMode get() = playbackMode

    /** Backward-compatible, untagged note: only an untagged note-off can release it. */
    fun noteOn(note: Int, velocity: Float = 1f) {
        if (note !in 0..127 || !velocity.isFinite()) return
        if (velocity <= 0f) {
            noteOff(note)
        } else {
            events.offer(Event.NoteOn(note, velocity.coerceIn(0f, 1f)))
        }
    }

    fun noteOff(note: Int) {
        if (note in 0..127) events.offer(Event.NoteOff(note))
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
            events.offer(Event.NoteOn(note, velocity.coerceIn(0f, 1f), sourceId, channel, true))
        }
    }

    fun noteOff(sourceId: Long, channel: Int, note: Int) {
        if (channel in 0..15 && note in 0..127) {
            events.offer(Event.NoteOff(note, sourceId, channel, true))
        }
    }

    /** Gracefully release all sources/legacy voices (lifecycle stop and panic). */
    fun allNotesOff() {
        events.offer(Event.AllNotesOff)
    }

    /** Gracefully release only keys belonging to this source, across all channels. */
    fun allNotesOff(sourceId: Long) {
        events.offer(Event.SourceAllNotesOff(sourceId))
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
     * modulator sources. Current real-time audio targets are MORPH and PITCH;
     * other typed destinations are reserved for following sound-engine work.
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
        oscillatorSlots.set(index, OscillatorSlot(
            enabled = enabled,
            waveform = waveform.coerceIn(0f, 1f),
            morph = morph.coerceIn(0f, 1f),
            level = level.coerceIn(0f, 1f),
            coarseSemitones = coarseSemitones.coerceIn(-48, 48),
            fineCents = fineCents.coerceIn(-100f, 100f),
        ))
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
        val cutoffHz = (45.0 * 2.0.pow(cutoff * 8.7)).coerceAtMost(sampleRate * 0.45)
        val lfoHz = 0.05 * 2.0.pow(lfoRate * 8.0)
        val lfoIncrement = lfoHz / sampleRate
        val modulationDepth = movement * lfoDepth
        // Bypass the entire new mod clock on legacy patches with zero routes.
        val modulationEnabled = liveModulation.syncBlock(morph)

        for (slot in 0 until oscillatorSlotCount) {
            blockOscillatorSlots[slot] = oscillatorSlots.get(slot)
        }
        for (voice in voices) {
            for (slot in 0 until oscillatorSlotCount) {
                voice.configureOscillatorSlot(slot, blockOscillatorSlots[slot])
            }
            voice.configure(attackSec, decaySec, sustain, releaseSec, cutoffHz, resonance)
            voice.configureUnison(unison, detune)
            voice.configureEngine(engineModel, character, cutoffHz.toFloat(),
                resonance, wave, morph)
        }
        drainEvents(glideSec, wave, morph)

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
            if (modulationEnabled) liveModulation.advanceSilence(frames)
            return
        }

        for (frame in 0 until frames) {
            // Sample-rate-independent slew avoids a 29% instantaneous level jump when
            // a second voice joins an already sustained single note.
            smoothedMasterGain +=
                (targetMasterGain - smoothedMasterGain) * gainSlewCoefficient
            val lfo = sin(lfoPhase * 2.0 * PI).toFloat()
            lfoPhase += lfoIncrement
            if (lfoPhase >= 1.0) lfoPhase -= 1.0

            if (modulationEnabled) liveModulation.tick()
            val routedMorph = if (modulationEnabled) liveModulation.morph else morph
            val movingMorph = (routedMorph + lfo * modulationDepth * 0.35f).coerceIn(0f, 1f)
            val vibratoSemitones = (lfo * modulationDepth * 0.5).toDouble()
            val routedPitchSemitones = if (modulationEnabled)
                liveModulation.pitchSemitones.toDouble() else 0.0
            val pitchMultiplier = 2.0.pow(
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
        }

        // Keep the UI-facing diagnostic in sync with voices that ended within this block.
        active = 0
        for (voice in voices) if (voice.active) active++
        activeVoiceCount = active
    }

    private fun drainEvents(glideSec: Double, wave: Float, morph: Float) {
        // Bound callback work under hostile MIDI event floods; defer overflow to next block.
        for (index in 0 until 512) {
            when (val event = events.poll() ?: break) {
                is Event.NoteOn -> {
                    liveModulation.onNoteOn()
                    handleNoteOn(
                        event.note, event.velocity, event.sourceId, event.channel, event.tagged,
                        glideSec, wave, morph
                    )
                }
                is Event.NoteOff -> handleNoteOff(
                    event.note, event.sourceId, event.channel, event.tagged, glideSec, wave, morph
                )
                Event.AllNotesOff -> {
                    holdActive.fill(false)
                    holdVoice.fill(-1)
                    voiceHold.fill(-1)
                    previousFrequency = null
                    for (voice in voices) voice.noteOff()
                }
                is Event.SourceAllNotesOff ->
                    handleSourceAllNotesOff(event.sourceId, glideSec, wave, morph)
                is Event.Mode -> changeMode(event.mode, glideSec, wave, morph)
            }
        }
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
            val sourceId: Long = 0L, val channel: Int = 0, val tagged: Boolean = false
        ) : Event
        data class NoteOff(
            val note: Int, val sourceId: Long = 0L,
            val channel: Int = 0, val tagged: Boolean = false
        ) : Event
        data class Mode(val mode: VoiceMode) : Event
        data class SourceAllNotesOff(val sourceId: Long) : Event
        data object AllNotesOff : Event
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
