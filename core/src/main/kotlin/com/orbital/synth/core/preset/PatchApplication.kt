package com.orbital.synth.core.preset

import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ParameterId

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
}

/** Friendly factory-name alias for application integrations. */
object FactoryPresets {
    val AURORA: SynthPatch get() = FactoryPatches.DEFAULT
    val ALL: List<SynthPatch> get() = FactoryPatches.ALL
    fun byId(id: String): SynthPatch? = FactoryPatches.byId(id)
}
