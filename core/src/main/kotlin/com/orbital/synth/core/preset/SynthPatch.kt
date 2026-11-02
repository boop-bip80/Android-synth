package com.orbital.synth.core.preset

/** Stable engine parameter keys, matching com.orbital.synth.core.ParameterId names. */
object PatchParameters {
    val ALL: List<String> = listOf(
        "WAVE", "MORPH", "CUTOFF", "RESONANCE", "MOVEMENT", "FX_AMOUNT",
        "ATTACK", "DECAY", "SUSTAIN", "RELEASE", "LFO_RATE", "LFO_DEPTH", "GLIDE", "UNISON", "DETUNE",
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
