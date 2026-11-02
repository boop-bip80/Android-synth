package com.orbital.synth.core.music

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Strict, deterministic, human-diffable ORBITAL-SEQUENCE/1 and /2 formats.
 *
 * The metadata is fixed. Each step has one row, even when empty. Notes use
 * pitch,velocity,gate,probability,tie tuples separated by semicolons.
 * Version 2 adds explicit enable flags without erasing muted source notes.
 * Legacy values with null enabledSteps still encode as V1 for compatibility.
 * No Android dependency, reflection, JSON library or external resources.
 *
 * Import is deliberately strict: duplicate, missing, extra and malformed fields
 * fail rather than silently losing music. A newer format needs a new decoder.
 */
object SequenceCodec {
    const val CURRENT_VERSION = 2
    const val MAX_BYTES = 131_072
    private const val MAGIC_V1 = "ORBITAL-SEQUENCE/1"
    private const val MAGIC_V2 = "ORBITAL-SEQUENCE/2"
    private val META_KEYS_V1 = setOf("id", "name", "bpm", "swing", "subdivisions", "steps")
    private val META_KEYS_V2 = META_KEYS_V1 + "enabled"

    fun encode(sequence: UserSequence): String {
        val out = StringBuilder(512)
        out.append(if (sequence.enabledSteps == null) MAGIC_V1 else MAGIC_V2).append('\n')
        out.append("id=").append(escape(sequence.id)).append('\n')
        out.append("name=").append(escape(sequence.name)).append('\n')
        out.append("bpm=").append(sequence.bpm).append('\n')
        out.append("swing=").append(sequence.swing).append('\n')
        out.append("subdivisions=").append(sequence.pattern.subdivisionsPerBeat).append('\n')
        out.append("steps=").append(sequence.pattern.steps.size).append('\n')
        sequence.enabledSteps?.let { enabled ->
            out.append("enabled=").append(enabled.sorted().joinToString(",")).append('\n')
        }
        sequence.pattern.steps.forEachIndexed { index, step ->
            out.append("step.").append(index).append('=')
            step.notes.forEachIndexed { noteIndex, note ->
                if (noteIndex > 0) out.append(';')
                out.append(note.midiNote).append(',')
                    .append(note.velocity).append(',')
                    .append(note.gate).append(',')
                    .append(note.probability).append(',')
                    .append(if (note.tie) '1' else '0')
            }
            out.append('\n')
        }
        val encoded = out.toString()
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) {
            "Sequence exceeds format size limit"
        }
        return encoded
    }

    fun decode(text: String): UserSequence {
        require(text.isNotEmpty() && text.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) {
            "Invalid sequence size"
        }
        val split = text.split('\n').map { it.removeSuffix("\r") }
        val lines = if (split.lastOrNull().isNullOrEmpty()) split.dropLast(1) else split
        val hasEnabled = when (lines.firstOrNull()) {
            MAGIC_V1 -> false
            MAGIC_V2 -> true
            else -> throw IllegalArgumentException("Unknown sequence format/version")
        }
        val metaKeys = if (hasEnabled) META_KEYS_V2 else META_KEYS_V1

        val fields = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed sequence field" }
            val key = line.substring(0, separator)
            require(key !in fields) { "Duplicate sequence field" }
            require(key in metaKeys || validStepKey(key)) { "Unknown sequence field" }
            fields[key] = line.substring(separator + 1)
        }

        fun value(key: String): String =
            fields[key] ?: throw IllegalArgumentException("Missing sequence field: " + key)
        fun number(key: String): Int =
            value(key).toIntOrNull() ?: throw IllegalArgumentException("Invalid integer: " + key)
        fun decimal(key: String): Double =
            value(key).toDoubleOrNull() ?: throw IllegalArgumentException("Invalid decimal: " + key)

        val stepCount = number("steps")
        require(stepCount in 1..64) { "Sequence must have 1..64 steps" }
        val expected = metaKeys + (0 until stepCount).map { "step." + it }
        require(fields.keys == expected) { "Missing, duplicate or out-of-range step rows" }
        val enabled = if (hasEnabled) parseEnabledSteps(value("enabled"), stepCount) else null

        val pattern = StepPattern(
            steps = List(stepCount) { index ->
                val row = value("step." + index)
                if (row.isEmpty()) {
                    SequencerStep()
                } else {
                    SequencerStep(row.split(';').map { entry ->
                        val tuple = entry.split(',')
                        require(tuple.size == 5) { "Malformed sequencer note" }
                        val pitch = tuple[0].toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid MIDI pitch")
                        val velocity = tuple[1].toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid velocity")
                        val gate = tuple[2].toDoubleOrNull()
                            ?: throw IllegalArgumentException("Invalid gate")
                        val probability = tuple[3].toDoubleOrNull()
                            ?: throw IllegalArgumentException("Invalid probability")
                        val tie = when (tuple[4]) {
                            "0" -> false
                            "1" -> true
                            else -> throw IllegalArgumentException("Invalid tie flag")
                        }
                        SequencerNote(pitch, velocity, gate, probability, tie)
                    })
                }
            },
            subdivisionsPerBeat = number("subdivisions"),
        )
        return UserSequence(
            id = unescape(value("id")),
            name = unescape(value("name")),
            pattern = pattern,
            bpm = decimal("bpm"),
            swing = decimal("swing"),
            enabledSteps = enabled,
        )
    }

    private fun parseEnabledSteps(value: String, stepCount: Int): Set<Int> {
        if (value.isEmpty()) return emptySet()
        val indices = value.split(',').map { entry ->
            require(entry.matches(Regex("0|[1-9][0-9]*"))) { "Invalid enabled-step index" }
            entry.toIntOrNull() ?: throw IllegalArgumentException("Invalid enabled-step index")
        }
        require(indices == indices.distinct().sorted() && indices.all { it in 0 until stepCount }) {
            "Enabled steps must be unique, ascending and in range"
        }
        return indices.toSet()
    }

    private fun validStepKey(key: String): Boolean {
        if (!key.startsWith("step.")) return false
        val index = key.removePrefix("step.")
        return index.matches(Regex("0|[1-9][0-9]*"))
    }

    private fun escape(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun unescape(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
