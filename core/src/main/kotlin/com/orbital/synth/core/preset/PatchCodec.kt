package com.orbital.synth.core.preset

import com.orbital.synth.core.OscillatorSlot

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Human-diffable, deterministic UTF-8 patch format. Values are URL-escaped,
 * so multiline text and separators cannot corrupt the field framing.
 *
 * ORBITAL-PATCH/1
 * id=aurora
 * name=Aurora
 * ...
 * param.CUTOFF=0.62
 */
object PatchCodec {
    const val CURRENT_VERSION: Int = 2
    private const val MAGIC_V1 = "ORBITAL-PATCH/1"
    private const val MAGIC_V2 = "ORBITAL-PATCH/2"
    private val OSC_FIELD = Regex("osc\\.[0-7]")
    private const val MAX_TEXT_LENGTH = 65_536
    private val FIELDS = setOf("id", "name", "author", "description", "tags")

    fun encode(patch: SynthPatch): String {
        val out = StringBuilder(512)
        out.append(if (patch.oscillatorSlots.isEmpty()) MAGIC_V1 else MAGIC_V2).append('\n')
        out.append("id=").append(escape(patch.id)).append('\n')
        out.append("name=").append(escape(patch.name)).append('\n')
        out.append("author=").append(escape(patch.author)).append('\n')
        out.append("description=").append(escape(patch.description)).append('\n')
        out.append("tags=").append(patch.tags.sorted().joinToString(",") { escape(it) }).append('\n')
        for ((key, value) in patch.parameters.toSortedMap()) {
            out.append("param.").append(key).append('=').append(value).append('\n')
        }
        patch.oscillatorSlots.forEachIndexed { index, slot ->
            out.append("osc.").append(index).append('=')
                .append(if (slot.enabled) '1' else '0').append(',')
                .append(slot.waveform).append(',')
                .append(slot.morph).append(',')
                .append(slot.level).append(',')
                .append(slot.coarseSemitones).append(',')
                .append(slot.fineCents).append('\n')
        }
        require(out.length <= MAX_TEXT_LENGTH) { "Patch exceeds format size limit" }
        return out.toString()
    }

    fun decode(text: String): SynthPatch {
        require(text.isNotEmpty() && text.length <= MAX_TEXT_LENGTH) { "Invalid patch size" }
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val version = when (lines.firstOrNull()) {
            MAGIC_V1 -> 1
            MAGIC_V2 -> 2
            else -> throw IllegalArgumentException("Unknown patch format/version")
        }

        val values = linkedMapOf<String, String>()
        for ((index, line) in lines.withIndex()) {
            if (index == 0 || (index == lines.lastIndex && line.isEmpty())) continue
            val equals = line.indexOf('=')
            require(equals > 0) { "Malformed patch field" }
            val key = line.substring(0, equals)
            val value = line.substring(equals + 1)
            require(key in FIELDS || (key.startsWith("param.") &&
                key.removePrefix("param.") in PatchParameters.NAMES) ||
                (version == 2 && OSC_FIELD.matches(key))) { "Unknown patch field" }
            require(key !in values) { "Duplicate patch field" }
            values[key] = value
        }

        val parameters = linkedMapOf<String, Float>()
        for ((key, rawValue) in values) {
            if (!key.startsWith("param.")) continue
            val value = rawValue.toFloatOrNull()
                ?: throw IllegalArgumentException("Malformed parameter value")
            parameters[key.removePrefix("param.")] = value
        }

        val oscillators = mutableListOf<OscillatorSlot>()
        val orderedOscillators = values.entries
            .filter { it.key.startsWith("osc.") }
            .sortedBy { it.key.removePrefix("osc.").toInt() }
        for ((key, rawValue) in orderedOscillators) {
            val index = key.removePrefix("osc.").toInt()
            require(index == oscillators.size) { "Oscillator slots must be sequential" }
            val fields = rawValue.split(',')
            require(fields.size == 6) { "Malformed oscillator settings" }
            val enabled = when (fields[0]) {
                "1" -> true
                "0" -> false
                else -> throw IllegalArgumentException("Malformed oscillator enabled flag")
            }
            oscillators += OscillatorSlot(
                enabled = enabled,
                waveform = fields[1].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed oscillator waveform"),
                morph = fields[2].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed oscillator morph"),
                level = fields[3].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed oscillator level"),
                coarseSemitones = fields[4].toIntOrNull()
                    ?: throw IllegalArgumentException("Malformed oscillator semitones"),
                fineCents = fields[5].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed oscillator cents"),
            )
        }

        fun required(field: String): String =
            unescape(values[field] ?: throw IllegalArgumentException("Missing patch field"))

        val tags = (values["tags"] ?: throw IllegalArgumentException("Missing tags"))
            .takeIf { it.isNotEmpty() }
            ?.split(',')
            ?.map(::unescape)
            ?.toSet()
            ?: emptySet()

        return SynthPatch(
            id = required("id"),
            name = required("name"),
            author = required("author"),
            description = required("description"),
            tags = tags,
            parameters = parameters,
            oscillatorSlots = oscillators,
        )
    }

    private fun escape(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun unescape(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
