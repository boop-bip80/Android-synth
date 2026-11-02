package com.orbital.synth.core.music

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Strict, deterministic, human-diffable ORBITAL-SEQUENCE/1 format.
 *
 * The metadata is fixed. Each step has one row, even when empty. Notes use
 * pitch,velocity,gate,probability,tie tuples separated by semicolons.
 * No Android dependency, reflection, JSON library or external resources.
 *
 * Import is deliberately strict: duplicate, missing, extra and malformed fields
 * fail rather than silently losing music. A newer format needs a new decoder.
 */
object SequenceCodec {
    const val CURRENT_VERSION = 1
    const val MAX_BYTES = 131_072
    private const val MAGIC = "ORBITAL-SEQUENCE/1"
    private val META_KEYS = setOf("id", "name", "bpm", "swing", "subdivisions", "steps")

    fun encode(sequence: UserSequence): String {
        val out = StringBuilder(512)
        out.append(MAGIC).append('\n')
        out.append("id=").append(escape(sequence.id)).append('\n')
        out.append("name=").append(escape(sequence.name)).append('\n')
        out.append("bpm=").append(sequence.bpm).append('\n')
        out.append("swing=").append(sequence.swing).append('\n')
        out.append("subdivisions=").append(sequence.pattern.subdivisionsPerBeat).append('\n')
        out.append("steps=").append(sequence.pattern.steps.size).append('\n')
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
        require(lines.firstOrNull() == MAGIC) { "Unknown sequence format/version" }

        val fields = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed sequence field" }
            val key = line.substring(0, separator)
            require(key !in fields) { "Duplicate sequence field" }
            require(key in META_KEYS || validStepKey(key)) { "Unknown sequence field" }
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
        val expected = META_KEYS + (0 until stepCount).map { "step." + it }
        require(fields.keys == expected) { "Missing, duplicate or out-of-range step rows" }

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
        )
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
