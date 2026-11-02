package com.orbital.synth.core.music

/**
 * Composition algorithms kept separate from the realtime transport. All methods
 * are pure: equal inputs and seeds return equal patterns, with no Android APIs or
 * mutable global random state.
 */
object AdvancedPatternComposer {
    /**
     * Repeat independent lane lengths until every lane meets again. For example,
     * 3 against 4 produces a twelve-step pattern rather than truncating either
     * lane. The 64-step limit is deliberate because StepPattern is bounded.
     *
     * Colliding pitches keep the highest-velocity note. The first encountered
     * note wins ties, preserving deterministic note order and gate/tie metadata.
     */
    fun polymeter(vararg lanes: StepPattern): StepPattern {
        require(lanes.isNotEmpty()) { "Provide at least one lane" }
        val subdivision = lanes.first().subdivisionsPerBeat
        require(lanes.all { it.subdivisionsPerBeat == subdivision }) {
            "Polymetric lanes must use the same subdivisions per beat"
        }

        var cycle = 1
        for (lane in lanes) {
            val numberOfSteps = lane.steps.size
            val candidate = cycle / greatestCommonDivisor(cycle, numberOfSteps) * numberOfSteps
            require(candidate <= 64) { "Combined polymeter exceeds the 64-step pattern limit" }
            cycle = candidate
        }

        val steps = List(cycle) { ordinal ->
            val notes = linkedMapOf<Int, SequencerNote>()
            for (lane in lanes) {
                for (note in lane.steps[ordinal % lane.steps.size].notes) {
                    val previous = notes[note.midiNote]
                    if (previous == null || note.velocity > previous.velocity) {
                        notes[note.midiNote] = note
                    }
                }
            }
            require(notes.size <= 16) { "Combined polymeter exceeds 16 notes in one step" }
            SequencerStep(notes.values.toList())
        }
        return StepPattern(steps, subdivision)
    }

    /**
     * Create a repeatable, scale-constrained melodic walk. Step zero is the
     * nearest scale tone to startMidiNote (unless it becomes a rest). From step
     * one onward each step moves by at most maxDegreeLeap scale tones from the
     * previous step, including rests.
     * A seed controls both pitch movement and which steps are silent; no random
     * values depend on the size of the host audio buffer.
     *
     * The generator is compositional, not part of the audio callback. Generate
     * once and hand the result to StepSequencer.replacePattern on its owner thread.
     */
    fun melodicWalk(
        steps: Int = 16,
        scale: MusicalScale = MusicalScales.MINOR_PENTATONIC,
        tonicPitchClass: Int = 0,
        lowestMidiNote: Int = 48,
        highestMidiNote: Int = 84,
        startMidiNote: Int = 60,
        maxDegreeLeap: Int = 2,
        restProbability: Double = 0.12,
        seed: Long = 0L,
        velocity: Int = 96,
        gate: Double = 0.72,
        subdivisionsPerBeat: Int = 4,
    ): StepPattern {
        require(steps in 1..64) { "steps must be 1..64" }
        require(lowestMidiNote in 0..127 && highestMidiNote in lowestMidiNote..127) {
            "MIDI note bounds must be ordered and within 0..127"
        }
        require(startMidiNote in 0..127) { "startMidiNote must be within 0..127" }
        require(maxDegreeLeap in 0..12) { "maxDegreeLeap must be within 0..12" }
        require(restProbability.isFinite() && restProbability in 0.0..1.0) {
            "restProbability must be within 0..1"
        }
        // Validate musical parameters even when every step is a rest.
        val sampleNote = SequencerNote(startMidiNote, velocity, gate)
        require(subdivisionsPerBeat in 1..16)

        val tonic = Math.floorMod(tonicPitchClass, 12)
        val allowed = (lowestMidiNote..highestMidiNote).filter {
            Math.floorMod(it - tonic, 12) in scale.degrees
        }
        require(allowed.isNotEmpty()) { "No notes of the scale fit inside the requested range" }
        var pitchIndex = allowed.indices.minByOrNull { kotlin.math.abs(allowed[it] - startMidiNote) }!!
        val random = StableGenerator(seed)
        val result = List(steps) { ordinal ->
            if (ordinal > 0) {
                val leapSpan = maxDegreeLeap * 2 + 1
                val degreeChange = random.nextBounded(leapSpan) - maxDegreeLeap
                pitchIndex = (pitchIndex + degreeChange).coerceIn(0, allowed.lastIndex)
            }
            val rest = random.nextUnit() < restProbability
            if (rest) {
                SequencerStep()
            } else {
                // An unobtrusive accent every quarter note supplies phrase shape.
                val accentedVelocity = if (ordinal % subdivisionsPerBeat == 0) {
                    (sampleNote.velocity + 10).coerceAtMost(127)
                } else {
                    sampleNote.velocity
                }
                SequencerStep(listOf(sampleNote.copy(
                    midiNote = allowed[pitchIndex],
                    velocity = accentedVelocity,
                )))
            }
        }
        return StepPattern(result, subdivisionsPerBeat)
    }

    private fun greatestCommonDivisor(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val remainder = x % y
            x = y
            y = remainder
        }
        return x
    }

    /**
     * SplitMix64 with an explicitly specified unsigned 53-bit conversion.
     * Kotlin/JVM Long overflow here is intentional and deterministic.
     */
    private class StableGenerator(seed: Long) {
        private var state = seed

        fun nextUnit(): Double {
            state += 0x9E3779B97F4A7C15UL.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
            return ((z xor (z ushr 31)) ushr 11).toDouble() / 9007199254740992.0
        }

        fun nextBounded(size: Int): Int = (nextUnit() * size).toInt().coerceAtMost(size - 1)
    }
}
