package com.orbital.synth.preset

import com.orbital.synth.core.preset.SynthPatch
import java.text.Normalizer
import java.util.Locale

/** Deterministic, traversal-safe IDs for user patches. */
object UserPatchIds {
    private const val PREFIX = "user-"
    private const val LIMIT = 64
    private val accents = Regex("\\p{M}+")
    private val separators = Regex("[^a-z0-9]+")

    /**
     * Stable ASCII slug. Unsupported scripts deliberately use user-patch rather
     * than writing unsafe or locale-dependent names into filenames.
     */
    fun baseForName(name: String): String {
        require(name.trim().isNotEmpty()) { "Patch name must not be blank" }
        val folded = Normalizer.normalize(name.trim(), Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .replace(accents, "")
        val suffix = folded.replace(separators, "-")
            .trim('-')
            .take(LIMIT - PREFIX.length)
            .trimEnd('-')
            .ifEmpty { "patch" }
        return PREFIX + suffix
    }

    fun uniqueForName(name: String, usedIds: Set<String>): String {
        val base = baseForName(name)
        if (base !in usedIds) return base
        for (number in 2..9_999) {
            val postfix = "-" + number
            val candidate = base.take(LIMIT - postfix.length).trimEnd('-') + postfix
            if (candidate !in usedIds) return candidate
        }
        throw IllegalStateException("User patch ID space exhausted")
    }

    fun isUserId(id: String): Boolean =
        SynthPatch.validId(id) && id.startsWith(PREFIX) && id.length > PREFIX.length &&
            !id.endsWith("-")
}
