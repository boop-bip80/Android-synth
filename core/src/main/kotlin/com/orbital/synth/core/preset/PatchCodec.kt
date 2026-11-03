package com.orbital.synth.core.preset

import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import com.orbital.synth.core.dsp.processing.StepDirection

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
    const val CURRENT_VERSION: Int = 5
    private const val MAGIC_V1 = "ORBITAL-PATCH/1"
    private const val MAGIC_V2 = "ORBITAL-PATCH/2"
    private const val MAGIC_V3 = "ORBITAL-PATCH/3"
    private const val MAGIC_V4 = "ORBITAL-PATCH/4"
    private const val MAGIC_V5 = "ORBITAL-PATCH/5"
    private val OSC_FIELD = Regex("osc\\.[0-7]")
    private val LFO_FIELD = Regex("lfo\\.[0-7]")
    private val ROUTE_FIELD = Regex("route\\.([0-9]|[1-5][0-9]|6[0-3])")
    private val FX_FIELD = Regex("fx\\.[0-5]")
    private const val MAX_TEXT_LENGTH = 65_536
    private val FIELDS = setOf("id", "name", "author", "description", "tags")

    fun encode(patch: SynthPatch): String {
        val out = StringBuilder(512)
        val hasFx = patch.fxSlots.isNotEmpty()
        val hasSteps = patch.modulationSteps != null
        val hasModulation = patch.modulationRoutes.isNotEmpty() ||
            patch.modulationLfos.isNotEmpty() || patch.modulationTempoBpm != 120f
        out.append(
            when {
                hasSteps -> MAGIC_V5
                hasFx -> MAGIC_V4
                hasModulation -> MAGIC_V3
                patch.oscillatorSlots.isNotEmpty() -> MAGIC_V2
                else -> MAGIC_V1
            }
        ).append('\n')
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
        if (hasModulation || hasFx || hasSteps) {
            out.append("mod.tempo=").append(patch.modulationTempoBpm).append('\n')
            patch.modulationLfos.forEachIndexed { index, lfo ->
                out.append("lfo.").append(index).append('=')
                    .append(if (lfo.enabled) '1' else '0').append(',')
                    .append(lfo.shape.name).append(',')
                    .append(lfo.rateHz).append(',')
                    .append(lfo.beatsPerCycle).append(',')
                    .append(if (lfo.retriggerOnNote) '1' else '0').append('\n')
            }
            patch.modulationRoutes.forEachIndexed { index, route ->
                out.append("route.").append(index).append('=')
                    .append(if (route.enabled) '1' else '0').append(',')
                    .append(route.source.name).append(',')
                    .append(route.destination.name).append(',')
                    .append(route.depth).append(',')
                    .append(route.curve.name).append('\n')
            }
        }
        if (hasFx) {
            patch.fxSlots.forEachIndexed { index, fx ->
                out.append("fx.").append(index).append('=')
                    .append(if (fx.enabled) '1' else '0').append(',')
                    .append(fx.type.name).append(',')
                    .append(fx.wet).append(',')
                    .append(fx.depth).append(',')
                    .append(fx.rate).append(',')
                    .append(fx.stereo).append('\n')
            }
        }
        patch.modulationSteps?.let { steps ->
            out.append("step.seq=")
                .append(if (steps.enabled) '1' else '0').append(',')
                .append(steps.direction.name).append(',')
                .append(steps.length).append(',')
                .append(steps.beatsPerStep).append(',')
                .append(steps.glide).append(',')
                .append(if (steps.retriggerOnNote) '1' else '0')
            for (value in steps.values) out.append(',').append(value)
            out.append('\n')
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
            MAGIC_V3 -> 3
            MAGIC_V4 -> 4
            MAGIC_V5 -> 5
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
                (version >= 2 && OSC_FIELD.matches(key)) ||
                (version >= 3 && (key == "mod.tempo" ||
                    LFO_FIELD.matches(key) || ROUTE_FIELD.matches(key))) ||
                (version >= 4 && FX_FIELD.matches(key)) ||
                (version == 5 && key == "step.seq")) {
                "Unknown patch field"
            }
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

        val tempo = if (version >= 3) {
            values["mod.tempo"]?.toFloatOrNull()
                ?: throw IllegalArgumentException("Missing or invalid modulation tempo")
        } else 120f

        val lfos = mutableListOf<ModulationLfoConfig>()
        val orderedLfos = values.entries
            .filter { it.key.startsWith("lfo.") }
            .sortedBy { it.key.removePrefix("lfo.").toInt() }
        for ((key, rawValue) in orderedLfos) {
            val index = key.removePrefix("lfo.").toInt()
            require(index == lfos.size) { "LFO slots must be sequential" }
            val fields = rawValue.split(',')
            require(fields.size == 5) { "Malformed LFO settings" }
            lfos += ModulationLfoConfig(
                enabled = flag(fields[0]),
                shape = LfoShape.valueOf(fields[1]),
                rateHz = fields[2].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed LFO frequency"),
                beatsPerCycle = fields[3].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed LFO beat sync"),
                retriggerOnNote = flag(fields[4]),
            )
        }

        val routes = mutableListOf<ModulationRouteConfig>()
        val orderedRoutes = values.entries
            .filter { it.key.startsWith("route.") }
            .sortedBy { it.key.removePrefix("route.").toInt() }
        for ((key, rawValue) in orderedRoutes) {
            val index = key.removePrefix("route.").toInt()
            require(index == routes.size) { "Modulation routes must be sequential" }
            val fields = rawValue.split(',')
            require(fields.size == 5) { "Malformed modulation route" }
            routes += ModulationRouteConfig(
                enabled = flag(fields[0]),
                source = ModSource.valueOf(fields[1]),
                destination = ModDestination.valueOf(fields[2]),
                depth = fields[3].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed modulation depth"),
                curve = ModCurve.valueOf(fields[4]),
            )
        }

        val fxSlots = mutableListOf<FxModuleConfig>()
        val orderedFx = values.entries
            .filter { it.key.startsWith("fx.") }
            .sortedBy { it.key.removePrefix("fx.").toInt() }
        for ((key, rawValue) in orderedFx) {
            val index = key.removePrefix("fx.").toInt()
            require(index == fxSlots.size) { "FX slots must be sequential" }
            val fields = rawValue.split(',')
            require(fields.size == 6) { "Malformed FX module settings" }
            fxSlots += FxModuleConfig(
                enabled = flag(fields[0]),
                type = FxModuleType.valueOf(fields[1]),
                wet = fields[2].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed FX wet level"),
                depth = fields[3].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed FX depth"),
                rate = fields[4].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed FX rate"),
                stereo = fields[5].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed FX stereo"),
            )
        }
        if (version == 4) require(fxSlots.isNotEmpty()) {
            "V4 patches must contain explicit FX slot state"
        }

        val steps = if (version == 5) {
            val fields = (values["step.seq"]
                ?: throw IllegalArgumentException("V5 STEP settings are required")).split(',')
            require(fields.size == 22) { "A V5 STEP lane requires sixteen pads" }
            ModulationStepConfig(
                enabled = flag(fields[0]),
                direction = StepDirection.valueOf(fields[1]),
                length = fields[2].toIntOrNull()
                    ?: throw IllegalArgumentException("Malformed STEP length"),
                beatsPerStep = fields[3].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed STEP duration"),
                glide = fields[4].toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed STEP glide"),
                retriggerOnNote = flag(fields[5]),
                values = fields.drop(6).map { it.toFloatOrNull()
                    ?: throw IllegalArgumentException("Malformed STEP pad") },
            )
        } else null

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
            modulationRoutes = routes,
            modulationLfos = lfos,
            modulationTempoBpm = tempo,
            fxSlots = fxSlots,
            modulationSteps = steps,
        )
    }

    private fun flag(raw: String): Boolean = when (raw) {
        "1" -> true
        "0" -> false
        else -> throw IllegalArgumentException("Malformed patch boolean")
    }

    private fun escape(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun unescape(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
