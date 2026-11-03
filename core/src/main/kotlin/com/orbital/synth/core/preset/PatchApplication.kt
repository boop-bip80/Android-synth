package com.orbital.synth.core.preset

import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModSource

/** Convenience adapter for the DSP parameter contract; no Android dependency. */
fun OrbitalSynth.loadPatch(patch: SynthPatch) {
    for (id in ParameterId.entries) {
        // ORBITAL-PATCH/1 files predating stereo unison lack these keys.
        // Neutralize new controls on import so a prior patch's spread/detune
        // cannot leak into older sounds.
        val value = patch.parameters[id.name] ?: when (id.name) {
            "UNISON", "DETUNE", "ENGINE", "CHARACTER" -> 0f
            else -> null
        }
        value?.let { setParameter(id, it) }
    }
    // Patches created before the modular rack must never inherit oscillators
    // from the previously loaded sound. Missing slots are intentionally silent.
    clearOscillatorRack()
    patch.oscillatorSlots.forEachIndexed { index, slot ->
        setOscillatorSlot(
            index = index,
            enabled = slot.enabled,
            waveform = slot.waveform,
            level = slot.level,
            coarseSemitones = slot.coarseSemitones,
            fineCents = slot.fineCents,
            morph = slot.morph,
        )
    }

    // V1/V2 patches do not contain modulation. Clear *all* previous routes,
    // latches, tempos and LFO shapes before loading any new patch so an older
    // preset never inherits the previous preset's audible modulation.
    for (index in 0 until modulationRouteCount) clearModulationRoute(index)
    for (index in 0 until modulationLfoCount) {
        setModulationLfo(index, enabled = index == 0, shape = LfoShape.SINE,
            rateHz = 1f, beatsPerCycle = 0f, retriggerOnNote = false)
    }
    for (source in ModSource.entries) setModulationSource(source, 0f)
    setModulationTempoBpm(patch.modulationTempoBpm)
    patch.modulationLfos.forEachIndexed { index, config ->
        setModulationLfo(
            index, config.enabled, config.shape, config.rateHz,
            config.beatsPerCycle, config.retriggerOnNote,
        )
    }
    patch.modulationRoutes.forEachIndexed { index, config ->
        setModulationRouteConfig(index, config)
    }

    // A factory/V1/V2/V3 patch must not carry any of the last sound's FX
    // modules forward. The six default positions are always reset first.
    clearFxRack()
    patch.fxSlots.forEachIndexed { index, config ->
        setFxSlot(index, config)
    }
}

/** Friendly factory-name alias for application integrations. */
object FactoryPresets {
    val AURORA: SynthPatch get() = FactoryPatches.DEFAULT
    val ALL: List<SynthPatch> get() = FactoryPatches.ALL
    fun byId(id: String): SynthPatch? = FactoryPatches.byId(id)
}
