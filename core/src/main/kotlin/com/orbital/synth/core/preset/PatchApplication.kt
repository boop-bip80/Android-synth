package com.orbital.synth.core.preset

import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ParameterId

/** Convenience adapter for the DSP parameter contract; no Android dependency. */
fun OrbitalSynth.loadPatch(patch: SynthPatch) {
    for (id in ParameterId.entries) {
        patch.parameters[id.name]?.let { setParameter(id, it) }
    }
}

/** Friendly factory-name alias for application integrations. */
object FactoryPresets {
    val AURORA: SynthPatch get() = FactoryPatches.DEFAULT
    val ALL: List<SynthPatch> get() = FactoryPatches.ALL
    fun byId(id: String): SynthPatch? = FactoryPatches.byId(id)
}
