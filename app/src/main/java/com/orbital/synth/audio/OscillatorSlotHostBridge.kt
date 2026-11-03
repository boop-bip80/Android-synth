package com.orbital.synth.audio

import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.OscillatorSlot

/**
 * The one UI -> audio-worker bridge for the complete V6 oscillator snapshot.
 *
 * Sound-design editors publish immutable [OscillatorSlot] values on the UI
 * thread. [OrbitalSynth] validates/clamps again and the actual AudioTrack-owned
 * synth consumes the snapshot at the next render-block boundary. Keeping the
 * six wave fields and two noise fields together prevents the legacy seven-arg
 * setter from silently erasing touch-programmed noise color and blend.
 *
 * A true return means the snapshot was accepted for publication, not that the
 * audio worker has already rendered it; each voice smooths edits over 8ms.
 */
object OscillatorSlotHostBridge {
    fun publish(synth: OrbitalSynth, index: Int, next: OscillatorSlot): Boolean {
        if (index !in 0 until synth.oscillatorSlotCount) return false
        if (!next.waveform.isFinite() || !next.morph.isFinite() ||
            !next.level.isFinite() || !next.fineCents.isFinite() ||
            !next.noiseLevel.isFinite() || !next.noiseColor.isFinite()
        ) return false
        synth.setOscillatorSlot(index, next)
        return true
    }
}
