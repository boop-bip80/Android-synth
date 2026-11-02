package com.orbital.synth.core

import com.orbital.synth.core.dsp.oscillator.Voice
import com.orbital.synth.core.dsp.oscillator.WavetableBank
import com.orbital.synth.core.dsp.processing.Drive
import com.orbital.synth.core.dsp.processing.StereoChorus
import com.orbital.synth.core.dsp.processing.StereoDelay
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
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
    ATTACK, DECAY, SUSTAIN, RELEASE, LFO_RATE, LFO_DEPTH, GLIDE
}

enum class VoiceMode { POLY, MONO, LEGATO }

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
    private val controls = AtomicIntegerArray(ParameterId.entries.size).also { array ->
        for (id in ParameterId.entries) {
            array.set(id.ordinal, java.lang.Float.floatToRawIntBits(defaultValue(id)))
        }
    }
    private val bendBits = AtomicInteger(java.lang.Float.floatToRawIntBits(0f))
    private val events = ConcurrentLinkedQueue<Event>()
    private val heldNotes = BooleanArray(128)
    // Multiple inputs can hold the same pitch simultaneously (touch, MIDI, sequencer).
    // Pair each note-off with one note-on instead of cutting every matching voice.
    private val heldCounts = IntArray(128)
    private val heldVelocities = FloatArray(128)
    private val heldOrders = LongArray(128)
    // Reused only on the render thread when converting a held mono chord to polyphony.
    private val restoredCounts = IntArray(128)

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
    private var previousFxEnabled = false

    @Volatile private var playbackMode = VoiceMode.POLY
    @Volatile var activeVoiceCount: Int = 0
        private set

    val voiceMode: VoiceMode get() = playbackMode

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

    /** Release all held notes gracefully, without interrupting the audio callback. */
    fun allNotesOff() {
        events.offer(Event.AllNotesOff)
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

        for (voice in voices) {
            voice.configure(attackSec, decaySec, sustain, releaseSec, cutoffHz, resonance)
        }
        drainEvents(glideSec, wave, morph)

        // Reserve room for upward bend and vibrato when selecting anti-alias mip levels.
        val highestPitchMultiplier = 2.0.pow((max(0.0, pitchBend) +
            modulationDepth * 0.5) / 12.0)
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
        } else if (previousFxEnabled) {
            // Clear old echoes on bypass so reopening the effect cannot replay stale buffers.
            // Drop old wet gain along with the tail. Re-enabling at a small
            // amount must not inherit a previous preset's much larger wet mix.
            fxChorus.resetForBypass()
            fxDelay.resetForBypass()
        }
        previousFxEnabled = fxEnabled
        val driveAmount = fxAmount * 0.68f

        // AudioTrack normally keeps requesting blocks even when no keys are held. Avoid
        // evaluating sixteen idle voices, the trigonometric LFO and pitch pow() for every
        // silent frame. Keep the LFO clock and gain slew advancing sample-by-sample so
        // later notes behave exactly as if the silent blocks had been fully rendered.
        if (!fxEnabled && active == 0 && voices.none { it.hasTail }) {
            java.util.Arrays.fill(output, 0, frames * 2, 0f)
            for (frame in 0 until frames) {
                smoothedMasterGain +=
                    (targetMasterGain - smoothedMasterGain) * gainSlewCoefficient
                lfoPhase += lfoIncrement
                if (lfoPhase >= 1.0) lfoPhase -= 1.0
            }
            return
        }

        var offset = 0

        for (frame in 0 until frames) {
            // Sample-rate-independent slew avoids a 29% instantaneous level jump when
            // a second voice joins an already sustained single note.
            smoothedMasterGain +=
                (targetMasterGain - smoothedMasterGain) * gainSlewCoefficient
            val lfo = sin(lfoPhase * 2.0 * PI).toFloat()
            lfoPhase += lfoIncrement
            if (lfoPhase >= 1.0) lfoPhase -= 1.0

            val movingMorph = (morph + lfo * modulationDepth * 0.35f).coerceIn(0f, 1f)
            val vibratoSemitones = (lfo * modulationDepth * 0.5).toDouble()
            val pitchMultiplier = 2.0.pow((pitchBend + vibratoSemitones) / 12.0)

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
            if (fxEnabled) {
                // Preallocated scratch prevents Pair/array creation per frame.
                fxChorus.process(
                    Drive.process(dryLeft, driveAmount),
                    Drive.process(dryRight, driveAmount),
                    chorusScratch,
                    0
                )
                fxDelay.process(
                    chorusScratch[0],
                    chorusScratch[1],
                    fxScratch,
                    0
                )
                output[offset++] = softClip(fxScratch[0])
                output[offset++] = softClip(fxScratch[1])
            } else {
                // Bit-for-bit identical to the pre-FX dry path at FX_AMOUNT=0.
                output[offset++] = dryLeft
                output[offset++] = dryRight
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
                is Event.NoteOn -> handleNoteOn(event.note, event.velocity, glideSec, wave, morph)
                is Event.NoteOff -> handleNoteOff(event.note, glideSec, wave, morph)
                Event.AllNotesOff -> {
                    heldNotes.fill(false)
                    heldCounts.fill(0)
                    previousFrequency = null
                    for (voice in voices) voice.noteOff()
                }
                is Event.Mode -> changeMode(event.mode, glideSec, wave, morph)
            }
        }
    }

    private fun handleNoteOn(note: Int, velocity: Float, glideSec: Double, wave: Float, morph: Float) {
        var alreadyHeld = false
        for (held in heldNotes) if (held) { alreadyHeld = true; break }

        heldNotes[note] = true
        if (heldCounts[note] < Int.MAX_VALUE) heldCounts[note]++
        heldVelocities[note] = velocity
        heldOrders[note] = ++eventOrder
        val toFrequency = Voice.midiFrequency(note)
        val fromFrequency = previousFrequency

        when (playbackMode) {
            VoiceMode.POLY -> {
                chooseVoice().begin(note, velocity, eventOrder, fromFrequency, glideSec, wave, morph)
            }
            VoiceMode.MONO, VoiceMode.LEGATO -> {
                for (index in 1 until voices.size) voices[index].noteOff()
                val voice = voices[0]
                val legato = playbackMode == VoiceMode.LEGATO &&
                    alreadyHeld && voice.active && !voice.releasing
                voice.begin(note, velocity, eventOrder, fromFrequency, glideSec, wave, morph, legato)
            }
        }
        previousFrequency = toFrequency
    }

    private fun handleNoteOff(note: Int, glideSec: Double, wave: Float, morph: Float) {
        val remainingHolds = if (heldCounts[note] > 0) heldCounts[note] - 1 else 0
        heldCounts[note] = remainingHolds
        heldNotes[note] = remainingHolds > 0
        when (playbackMode) {
            VoiceMode.POLY -> {
                // Release only as many sounding voices as there are unmatched note-offs.
                // Prefer the newest voice: a repeated note-on from another source should
                // not cut off an older held touch key. Source IDs would permit exact pairing.
                var sounding = 0
                for (voice in voices) {
                    if (voice.active && !voice.releasing && voice.note == note) sounding++
                }
                while (sounding > remainingHolds) {
                    var newest: Voice? = null
                    for (voice in voices) {
                        if (voice.active && !voice.releasing && voice.note == note &&
                            (newest == null || voice.startedAt > newest.startedAt)
                        ) newest = voice
                    }
                    if (newest == null) break
                    newest.noteOff()
                    sounding--
                }
            }
            VoiceMode.MONO, VoiceMode.LEGATO -> {
                val voice = voices[0]
                if (remainingHolds == 0 && voice.active && voice.note == note) {
                    val remaining = latestHeldNote()
                    if (remaining < 0) {
                        voice.noteOff()
                    } else {
                        val fromFrequency = Voice.midiFrequency(note)
                        val legato = playbackMode == VoiceMode.LEGATO && !voice.releasing
                        voice.begin(
                            remaining, heldVelocities[remaining], ++eventOrder,
                            fromFrequency, glideSec, wave, morph, legato
                        )
                        previousFrequency = Voice.midiFrequency(remaining)
                    }
                }
            }
        }
    }

    private fun changeMode(mode: VoiceMode, glideSec: Double, wave: Float, morph: Float) {
        if (playbackMode == mode) return
        playbackMode = mode
        if (mode == VoiceMode.POLY) {
            restoreHeldPolyVoices(wave, morph)
        } else {
            for (index in 1 until voices.size) voices[index].noteOff()
            val newest = latestHeldNote()
            if (newest >= 0) {
                val voice = voices[0]
                val legato = mode == VoiceMode.LEGATO &&
                    voice.active && !voice.releasing
                voice.begin(
                    newest, heldVelocities[newest], ++eventOrder, previousFrequency,
                    glideSec, wave, morph, legato
                )
                previousFrequency = Voice.midiFrequency(newest)
            } else {
                voices[0].noteOff()
            }
        }
    }

    /**
     * A switch from mono/legato to poly should expose the keys that are *still held*.
     * The mono lead voice remains continuous, while the other voice slots are rebuilt
     * in most-recent-key order (including independently held instances of one pitch).
     * Reuse storage and never steal the continuing lead during a mode transition.
     */
    private fun restoreHeldPolyVoices(wave: Float, morph: Float) {
        if (voices.size <= 1) return
        restoredCounts.fill(0)
        val lead = voices[0]
        if (lead.active && !lead.releasing && lead.note in heldNotes.indices) {
            restoredCounts[lead.note] = 1
        }

        for (slot in 1 until voices.size) {
            var newest = -1
            var newestOrder = Long.MIN_VALUE
            for (pitch in heldNotes.indices) {
                if (heldCounts[pitch] > restoredCounts[pitch] && heldOrders[pitch] > newestOrder) {
                    newest = pitch
                    newestOrder = heldOrders[pitch]
                }
            }
            if (newest == -1) break

            val target = chooseSecondaryVoice()
            target.begin(
                newest, heldVelocities[newest], ++eventOrder,
                null, 0.0, wave, morph
            )
            restoredCounts[newest]++
        }
    }

    private fun chooseSecondaryVoice(): Voice {
        for (index in 1 until voices.size) {
            val voice = voices[index]
            if (!voice.active) return voice
        }

        var selected = voices[1]
        for (index in 2 until voices.size) {
            val voice = voices[index]
            if ((voice.releasing && !selected.releasing) ||
                (voice.releasing == selected.releasing &&
                    (voice.envelopeLevel < selected.envelopeLevel ||
                        (voice.envelopeLevel == selected.envelopeLevel &&
                            voice.startedAt < selected.startedAt)))
            ) {
                selected = voice
            }
        }
        return selected
    }

    private fun latestHeldNote(): Int {
        var candidate = -1
        var latest = Long.MIN_VALUE
        for (note in heldNotes.indices) {
            if (heldNotes[note] && heldOrders[note] > latest) {
                latest = heldOrders[note]
                candidate = note
            }
        }
        return candidate
    }

    private fun chooseVoice(): Voice {
        for (voice in voices) if (!voice.active) return voice

        var result = voices[0]
        for (voice in voices) {
            if (voice.releasing && !result.releasing) {
                result = voice
            } else if (voice.releasing == result.releasing) {
                if (voice.envelopeLevel < result.envelopeLevel ||
                    (voice.envelopeLevel == result.envelopeLevel && voice.startedAt < result.startedAt)
                ) result = voice
            }
        }
        return result
    }

    private fun softClip(value: Float): Float {
        if (value >= 5f) return 1f
        if (value <= -5f) return -1f
        val squared = value * value
        return (value * (27f + squared) / (27f + 9f * squared)).coerceIn(-1f, 1f)
    }

    private sealed interface Event {
        data class NoteOn(val note: Int, val velocity: Float) : Event
        data class NoteOff(val note: Int) : Event
        data class Mode(val mode: VoiceMode) : Event
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
        }
    }
}
