package com.orbital.synth.core.preset

import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.dsp.processing.FxModuleConfig

/** Stable engine parameter keys, matching com.orbital.synth.core.ParameterId names. */
object PatchParameters {
    val ALL: List<String> = listOf(
        "WAVE", "MORPH", "CUTOFF", "RESONANCE", "MOVEMENT", "FX_AMOUNT",
        "ATTACK", "DECAY", "SUSTAIN", "RELEASE", "LFO_RATE", "LFO_DEPTH", "GLIDE", "UNISON", "DETUNE",
        "ENGINE", "CHARACTER",
    )
    val NAMES: Set<String> = ALL.toSet()
}

/**
 * A versioned patch payload independent of Android's storage system.
 * Values are normalized [0, 1] to agree with OrbitalSynth.setParameter.
 */
data class SynthPatch(
    val id: String,
    val name: String,
    val author: String = "Orbital",
    val description: String = "",
    val tags: Set<String> = emptySet(),
    val parameters: Map<String, Float>,
    /** Extra additive oscillators; empty means a legacy, single-engine patch. */
    val oscillatorSlots: List<OscillatorSlot> = emptyList(),
    /** Ordered edit state for up to 64 modulation routes (including disabled lanes). */
    val modulationRoutes: List<ModulationRouteConfig> = emptyList(),
    /** Ordered LFO settings; empty means the legacy default, first LFO active. */
    val modulationLfos: List<ModulationLfoConfig> = emptyList(),
    /** Saved transport tempo for beat-synchronized modulation. */
    val modulationTempoBpm: Float = 120f,
    /** Ordered, independently editable six-slot stereo FX chain; empty is V1/V2/V3. */
    val fxSlots: List<FxModuleConfig> = emptyList(),
) {
    init {
        require(validId(id)) { "Invalid patch identifier" }
        require(name.isNotBlank() && name.length <= 80)
        require(author.isNotBlank() && author.length <= 80)
        require(description.length <= 240)
        require(tags.size <= 12 && tags.all { it.isNotBlank() && it.length <= 24 })
        require(parameters.isNotEmpty())
        require(parameters.keys.all { it in PatchParameters.NAMES }) { "Unknown parameter key" }
        require(parameters.values.all { it.isFinite() && it in 0f..1f }) {
            "Parameters must be normalized, finite values"
        }
        require(oscillatorSlots.size <= 8) { "A patch supports at most eight additive oscillators" }
        require(oscillatorSlots.all { slot ->
            slot.waveform.isFinite() && slot.waveform in 0f..1f &&
                slot.morph.isFinite() && slot.morph in 0f..1f &&
                slot.level.isFinite() && slot.level in 0f..1f &&
                slot.coarseSemitones in -48..48 &&
                slot.fineCents.isFinite() && slot.fineCents in -100f..100f
        }) { "Invalid oscillator slot settings" }
        require(modulationRoutes.size <= 64) { "A patch supports at most 64 modulation routes" }
        require(modulationRoutes.all { it.depth.isFinite() && it.depth in -4f..4f }) {
            "Invalid modulation route depth"
        }
        require(modulationLfos.size <= 8) { "A patch supports at most eight LFOs" }
        require(modulationLfos.all { lfo ->
            lfo.rateHz.isFinite() && lfo.rateHz in 0.01f..40f &&
                lfo.beatsPerCycle.isFinite() &&
                (lfo.beatsPerCycle == 0f || lfo.beatsPerCycle in 0.0625f..64f)
        }) { "Invalid LFO settings" }
        require(modulationTempoBpm.isFinite() && modulationTempoBpm in 20f..400f) {
            "Invalid modulation transport tempo"
        }
        require(fxSlots.size <= 6) { "A patch supports at most six live FX slots" }
        require(fxSlots.all { fx ->
            fx.wet.isFinite() && fx.wet in 0f..1f &&
                fx.depth.isFinite() && fx.depth in 0f..1f &&
                fx.rate.isFinite() && fx.rate in 0f..1f &&
                fx.stereo.isFinite() && fx.stereo in 0f..1f
        }) { "Invalid FX slot settings" }
    }

    fun parameter(key: String, fallback: Float = 0.5f): Float = parameters[key] ?: fallback

    companion object {
        private val ID_RULE = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        fun validId(id: String): Boolean = ID_RULE.matches(id)
    }
}

/** In-memory library for factory and imported/user patches, with deterministic ID behavior. */
class PatchCollection(initial: Iterable<SynthPatch> = FactoryPatches.ALL) {
    private val patches = linkedMapOf<String, SynthPatch>()

    init { initial.forEach { save(it) } }

    fun list(): List<SynthPatch> = patches.values.toList()
    fun get(id: String): SynthPatch? = patches[id]

    /** Saving an existing ID intentionally replaces it; other patch IDs are unaffected. */
    fun save(patch: SynthPatch) {
        patches[patch.id] = patch
    }

    fun importPatch(encoded: String): SynthPatch {
        val patch = PatchCodec.decode(encoded)
        save(patch)
        return patch
    }

    fun exportPatch(id: String): String? = get(id)?.let(PatchCodec::encode)
    fun remove(id: String): Boolean = patches.remove(id) != null
}
