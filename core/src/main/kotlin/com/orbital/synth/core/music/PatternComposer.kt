package com.orbital.synth.core.music

/**
 * Pure, deterministic composition helpers for building and reshaping step patterns.
 * They have no clock or mutable state; the returned patterns can be passed directly
 * to [StepSequencer.replacePattern] on the transport's owner thread.
 */
object PatternComposer {
    /**
     * Evenly distribute [pulses] note attacks over [steps] using an accumulator.
     * The first pulse falls on step zero. A positive [rotation] moves attacks later.
     * Velocity accents count actual pulses, not empty steps.
     */
    fun euclidean(
        steps: Int,
        pulses: Int,
        midiNote: Int,
        rotation: Int = 0,
        velocity: Int = 96,
        gate: Double = 0.72,
        accentEvery: Int = 0,
        accentVelocity: Int = 120,
        subdivisionsPerBeat: Int = 4,
    ): StepPattern {
        require(steps in 1..64) { "steps must be 1..64" }
        require(pulses in 0..steps) { "pulses must be between zero and steps" }
        require(accentEvery >= 0) { "accentEvery cannot be negative" }
        // Validate even for an empty rhythm; invalid settings should not hide.
        val ordinaryNote = SequencerNote(midiNote, velocity, gate)
        val accentNote = SequencerNote(midiNote, accentVelocity, gate)
        val rhythm = MutableList(steps) { SequencerStep() }
        var pulseOrdinal = 0
        for (step in 0 until steps) {
            if (step * pulses % steps < pulses) {
                val accented = accentEvery > 0 && pulseOrdinal % accentEvery == 0
                val target = Math.floorMod(step.toLong() + rotation.toLong(), steps.toLong()).toInt()
                rhythm[target] = SequencerStep(listOf(if (accented) accentNote else ordinaryNote))
                pulseOrdinal++
            }
        }
        return StepPattern(rhythm, subdivisionsPerBeat)
    }

    /** Rotate all parts together, preserving each step's gate, probability and ties. */
    fun rotate(pattern: StepPattern, amount: Int): StepPattern {
        val length = pattern.steps.size
        val shifted = List(length) { destination ->
            pattern.steps[Math.floorMod(destination.toLong() - amount.toLong(), length.toLong()).toInt()]
        }
        return pattern.copy(steps = shifted)
    }

    /**
     * Transpose a pattern exactly. Reject MIDI overflow rather than clamping,
     * which would merge distinct chord tones into duplicate pitches.
     */
    fun transpose(pattern: StepPattern, semitones: Int): StepPattern = pattern.copy(
        steps = pattern.steps.map { step ->
            SequencerStep(step.notes.map { note ->
                val pitch = note.midiNote.toLong() + semitones.toLong()
                require(pitch in 0L..127L) { "Transpose exceeds MIDI range" }
                note.copy(midiNote = pitch.toInt())
            })
        }
    )

    /**
     * Quantize a pattern to a musical scale. If several chord tones collapse to
     * one target note, keep the strongest velocity; ties use the selected note.
     * Original note order determines the output order at equal velocities.
     */
    fun quantize(pattern: StepPattern, quantizer: ScaleQuantizer): StepPattern = pattern.copy(
        steps = pattern.steps.map { step ->
            val unique = linkedMapOf<Int, SequencerNote>()
            for (note in step.notes) {
                val pitch = quantizer.quantize(note.midiNote)
                val existing = unique[pitch]
                if (existing == null || note.velocity > existing.velocity) {
                    unique[pitch] = note.copy(midiNote = pitch)
                }
            }
            SequencerStep(unique.values.toList())
        }
    )

    /**
     * Merge rhythms of the same length/subdivision. Repeated pitches at one step
     * retain the highest-velocity source; this avoids illegal duplicate pitches.
     */
    fun layer(vararg patterns: StepPattern): StepPattern {
        require(patterns.isNotEmpty()) { "Provide at least one pattern" }
        val first = patterns.first()
        require(patterns.all {
            it.steps.size == first.steps.size && it.subdivisionsPerBeat == first.subdivisionsPerBeat
        }) { "Layered patterns must have matching length and subdivision" }
        return first.copy(steps = first.steps.indices.map { index ->
            val unique = linkedMapOf<Int, SequencerNote>()
            for (pattern in patterns) {
                for (note in pattern.steps[index].notes) {
                    val existing = unique[note.midiNote]
                    if (existing == null || note.velocity > existing.velocity) unique[note.midiNote] = note
                }
            }
            SequencerStep(unique.values.toList())
        })
    }
}
