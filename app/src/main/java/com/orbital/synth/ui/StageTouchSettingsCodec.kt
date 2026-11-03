package com.orbital.synth.ui

/**
 * Stable, small UTF-8 persistence contract for the immersive stage's touch map.
 * This does not contain user patches, MIDI notes, engine state, or personal data.
 *
 * All seven V1 fields are mandatory. Unknown versions or fields are rejected
 * rather than silently misinterpreted. A deliberately narrow, documented V0
 * alias format remains readable for previous key/value configurations.
 */
object StageTouchSettingsCodec {
    const val V1_HEADER = "ORBITAL-STAGE-TOUCH-V1"
    private const val V0_HEADER = "ORBITAL-STAGE-TOUCH-V0"
    const val MAX_BYTES = 4096

    private val currentFields = setOf(
        "tonic", "baseOctave", "octaveRange", "scale",
        "pressureSensitivity", "pitchAxis", "glissando",
    )
    private val legacyFields = currentFields + setOf(
        "octave", "span", "pressure", "axis", "glide", "hold",
    )

    data class Decoded(val settings: StageTouchSettings, val legacy: Boolean)

    class UnsupportedVersionException(message: String) : IllegalArgumentException(message)

    fun encode(settings: StageTouchSettings): String {
        validate(settings)
        return buildString {
            append(V1_HEADER).append('\n')
            append("tonic=").append(settings.tonic).append('\n')
            append("baseOctave=").append(settings.baseOctave).append('\n')
            append("octaveRange=").append(settings.octaveRange).append('\n')
            append("scale=").append(settings.scale.name).append('\n')
            append("pressureSensitivity=").append(settings.pressureSensitivity).append('\n')
            append("pitchAxis=").append(settings.pitchAxis.name).append('\n')
            append("glissando=").append(settings.glissando).append('\n')
        }
    }

    fun decode(text: String): Decoded {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Settings too large" }
        val lines = text.removePrefix("\uFEFF").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        require(lines.isNotEmpty()) { "Empty stage settings" }
        val version = lines.first()
        val mode: Int
        val data: List<String>
        when {
            version == V1_HEADER -> { mode = 1; data = lines.drop(1) }
            version == V0_HEADER -> { mode = 0; data = lines.drop(1) }
            version.startsWith("ORBITAL-STAGE-TOUCH-") ->
                throw UnsupportedVersionException("Unrecognized stage settings version")
            '=' in version -> { mode = 0; data = lines } // Unversioned early key=value files.
            else -> throw IllegalArgumentException("Not a stage settings file")
        }

        val values = linkedMapOf<String, String>()
        for (line in data) {
            val separator = line.indexOf('=')
            require(separator > 0 && separator < line.lastIndex) { "Malformed settings entry" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(key.isNotBlank() && value.isNotBlank() && '=' !in value) {
                "Malformed settings field"
            }
            require(values.putIfAbsent(key, value) == null) { "Duplicate settings field" }
        }
        val validFields = if (mode == 1) currentFields else legacyFields
        require(values.keys.all { it in validFields }) { "Unknown settings field" }
        if (mode == 1) {
            require(values.keys == currentFields) { "Incomplete stage settings" }
        } else {
            require(values.isNotEmpty()) { "Empty legacy stage settings" }
        }

        fun field(primary: String, vararg legacyNames: String): String? {
            val keys = listOf(primary) + legacyNames
            val provided = keys.filter { it in values }
            require(provided.size <= 1) { "Ambiguous settings aliases" }
            return provided.firstOrNull()?.let { values.getValue(it) }
        }
        fun integer(raw: String?, fallback: Int, range: IntRange): Int {
            if (raw == null) return fallback
            val parsed = raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid integer")
            require(parsed in range) { "Out-of-range stage setting" }
            return parsed
        }
        fun fraction(raw: String?, fallback: Float): Float {
            if (raw == null) return fallback
            val parsed = raw.toFloatOrNull() ?: throw IllegalArgumentException("Invalid pressure")
            require(parsed.isFinite() && parsed in 0f..1f) { "Out-of-range pressure" }
            return parsed
        }
        fun flag(raw: String?, fallback: Boolean): Boolean = when (raw) {
            null -> fallback
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Invalid boolean")
        }
        val defaults = StageTouchSettings()
        val scaleName = field("scale")
        val scale = scaleName?.let { name ->
            TouchScale.entries.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("Unknown scale")
        } ?: defaults.scale
        val axisName = field("pitchAxis", "axis")
        val axis = when (axisName) {
            null -> defaults.pitchAxis
            "HORIZONTAL", "X" -> TouchPitchAxis.HORIZONTAL
            "VERTICAL", "Y" -> TouchPitchAxis.VERTICAL
            else -> throw IllegalArgumentException("Unknown pitch axis")
        }
        val held = field("hold")
        val glissando = if (held != null) {
            require(field("glissando", "glide") == null) { "Ambiguous glide and hold" }
            !flag(held, false)
        } else flag(field("glissando", "glide"), defaults.glissando)

        val settings = StageTouchSettings(
            tonic = integer(field("tonic"), defaults.tonic, 0..11),
            baseOctave = integer(field("baseOctave", "octave"), defaults.baseOctave, 0..8),
            octaveRange = integer(field("octaveRange", "span"), defaults.octaveRange, 1..4),
            scale = scale,
            pressureSensitivity = fraction(
                field("pressureSensitivity", "pressure"), defaults.pressureSensitivity
            ),
            pitchAxis = axis,
            glissando = glissando,
        )
        validate(settings)
        return Decoded(settings, legacy = mode == 0)
    }

    fun validate(settings: StageTouchSettings) {
        require(settings.tonic in 0..11) { "Tonic must be in 0..11" }
        require(settings.baseOctave in 0..8) { "Base octave must be in 0..8" }
        require(settings.octaveRange in 1..4) { "Octave range must be in 1..4" }
        require(
            settings.pressureSensitivity.isFinite() &&
                settings.pressureSensitivity in 0f..1f
        ) { "Pressure sensitivity must be finite and in 0..1" }
    }
}
